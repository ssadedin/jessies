# LLM Suggestions for Terminator — Implementation Plan

Status: draft · Branch: `feat-llm-suggest`

## 1. Goal

On a hotkey, Terminator takes what's on screen in the current pane, asks an
LLM through an OpenAI-compatible API (a local model by default) for a useful
suggestion, and shows the result in one of two ways:

- **Single line.** The command is typed into the terminal behind a comment
  marker (`# ls -la /var/log`), so the user can read it, edit it and run it.
- **Multi-line.** A small overlay in the top-right corner of the pane, in a
  smaller font.

Nothing is sent unless the user presses the hotkey. There is no background
activity in v1.

### Non-goals for v1

- Automatic or background triggering.
- Conversation state across requests. Continuity comes only from what is
  still visible on screen.
- Session summaries or "memory" (see §11, future work).
- Running commands on the user's behalf. We never send a newline.

## 2. User experience

1. The user is in a shell, often over SSH. They either:
   - type a comment on the prompt line, such as `# find files over 1G modified this week`, or
   - have just hit an error (a failed command, or an error in a log they're
     viewing) and type nothing.
2. They press **Ctrl+Cmd+L** on Mac, or **Ctrl+Shift+L** on Linux/Windows,
   whatever the "use Alt as Meta" setting. The action is also in the
   **Edit → LLM** menu.
3. The overlay appears straight away with a status line
   (`Reading request…` / `Diagnosing command error…`). Esc cancels.
4. The result arrives:
   - **A command, with a safe place to put it:** the typed request is erased
     and `# <command>` is typed in its place using the same comment marker.
     The overlay closes.
   - **Anything else:** the answer streams into the overlay. Esc or any key
     closes it (the key still reaches the terminal); a Copy button copies the
     text.

## 3. Architecture

All new code goes in a new package, `terminator.llm`. Only the UI code runs on
the Swing event thread (EDT). The snapshot is taken on the EDT, and
everything after that runs on a background thread and works on immutable data.

```
 Hotkey (TerminatorMenuBar.LlmSuggestAction)
   │  EDT
   ▼
 TerminalSnapshot.capture(pane)          ← copies lines, cursor, alt-buffer flag, title
   │
   ▼  background executor
 RequestDetector.detect(snapshot)        ← finds a comment request on the cursor line
   │
   ▼
 Redactor.redact(snapshot text)
   │
   ▼
 SuggestionPipeline
   ├─ explicit request? ──yes──► scenario = "explicit-request"
   └─ no ──► Pass 1: Classifier (LLM, JSON) ──► scenario + inferred request
   │
   ▼
 Pass 2: PromptTemplates.render(scenario, vars) ──► OpenAiClient.streamChat()
   │
   ▼  EDT (streamed chunks)
 SuggestionPresenter
   ├─ COMMAND reply and safe to insert ──► SuggestionInserter → TerminalControl.sendUtf8String
   └─ otherwise ──► SuggestionOverlay
```

### 3.1 New classes (`terminator/src/terminator/llm/`)

| Class | Responsibility |
|---|---|
| `LlmSuggestController` | One per `JTerminalPane`. Owns the in-flight request (at most one), handles cancellation and connects the stages together. |
| `TerminalSnapshot` | Immutable copy of: lines (visible screen plus scrollback up to the budget), cursor line index and column, cursor line text, `usingAlternateBuffer`, terminal name/title, width. Captured on the EDT from `TerminalModel`. |
| `RequestDetector` | Finds the comment marker and request text on the cursor line. Returns `DetectedRequest{marker, text, startColumn, endColumn}` or none. See §5. |
| `Redactor` | Regex-based secret scrubbing. Built-in patterns plus a user file. See §7. |
| `EndpointGuard` | Rejects endpoints that aren't local or private unless the user has explicitly allowed them. See §7. |
| `PromptTemplates` | Loads bundled default templates and user overrides, parses front matter, renders `{{var}}` placeholders. See §6. |
| `Classifier` | Pass 1. Builds the classification prompt from the available scenario descriptions and parses the JSON reply leniently. |
| `OpenAiClient` | `POST {endpoint}/chat/completions` with `stream: true`, parses server-sent events and supports cancellation. Uses `java.net.http.HttpClient` (see §9.2). |
| `SuggestionPresenter` | Decides whether a reply is a single-line command or overlay text, and sends it to the right place. |
| `SuggestionInserter` | Safety checks, then erases the request and types `<marker> <command>` into the pty. |
| `SuggestionOverlay` | Swing component shown over the terminal (§8). |
| `LlmDebugLog` | Opt-in log of requests and responses. |

### 3.2 Changes to existing files

| File | Change |
|---|---|
| `TerminatorMenuBar.java` | New **Edit → LLM** submenu (built with `GuiUtilities.makeMenu`, added after `CopyModeAction` in `makeEditMenu()`), containing `LlmSuggestAction` ("Suggest") and `LlmPreviewRequestAction` ("Preview Request…"), both `extends AbstractPaneAction`. Accelerators are fixed, not built from `defaultKeyStrokeModifiers`: Mac uses `CTRL_DOWN_MASK \| META_DOWN_MASK` + `L`, and everything else uses `CTRL_DOWN_MASK \| SHIFT_DOWN_MASK` + `L`. On Mac, Cmd is present, so `isKeyboardEquivalent` already sends the event to the menu bar and `^L` never reaches the pty. **Test this.** |
| `JTerminalPane.java` | Create the `LlmSuggestController`. Wrap `scrollPane` in a layered container so the overlay can sit on top of it and moves with the pane when tabs change. **Linux/Windows hotkey:** Ctrl+Shift+L doesn't match `isKeyboardEquivalent` when the default modifier is Alt, so `KeyHandler` would otherwise send `^L` (clear screen) to the pty. In `keyPressed`, recognise the chord, run the action, consume the event, and set a flag so the matching `keyTyped` (`0x0C`) is swallowed too. The terminal can't tell Ctrl+Shift+L from Ctrl+L anyway, so no key the user could use is lost. **Overlay keys:** if the overlay is showing, Esc closes it (and cancels any request) and is consumed; any other key closes it and is then handled as usual. |
| `TerminatorPreferences.java` | New "LLM" preferences group (§6.3). |
| `terminator/lib/jars/` | Add the JSON library jar (§9.3). Both `universal.make` (`EXTRA_JARS`) and `invoke-java.rb` already pick up `lib/jars/*.jar`. |

## 4. Context extraction

- **Source:** `TerminalModel.getTextLine(i).getString()` for
  `i ∈ [max(0, lineCount − N), lineCount)`, walking backwards from the last
  line until the **character budget** (default 8000) is used up. The whole
  visible screen always goes in first; scrollback fills whatever budget is
  left.
- **Clean-up:** strip trailing whitespace from each line, drop trailing blank
  lines, and collapse runs of more than two blank lines.
- **Alternate screen** (vim, less, top): send that screen only, with no
  scrollback, and tell the prompt (`{{screen_kind}} = "full-screen application"`).
  Single-line insertion is disabled in this mode (§5.3).
- **Metadata:** the pane title, which is often `user@host: cwd` from the
  remote shell's title escape; the local OS; the terminal width.
- **Thread safety:** terminal output is applied to the model on the EDT (via
  `processActions`), so the snapshot must be captured on the EDT.
  Capturing about 8KB of text is cheap.

## 5. Request detection and insertion

### 5.1 Comment markers

Detection runs locally, with no LLM involved. It uses the markers `#`, `--`
and `//` by default, matched longest first. The list is a preference, and `'`
can be added to it but is **off by default** because shell quoting makes false
matches too likely.

Markers that aren't on the list, including `'` and anything else such as
`%`, `;` or `REM`, are still handled: the request goes through the two-pass
path and the classifier can **rescue** it (§6.1). Local detection is just a
fast path that skips pass 1.

### 5.2 Detection on the cursor line

The request must be on the **cursor line**, which is not necessarily the last
line on screen. Scan the cursor line left to right and pick the **first**
marker occurrence that:

1. is at the start of the line or follows whitespace. This skips root
   prompts like `root@h:~#` and URLs like `http://`;
2. is followed by a space and then non-blank text. This is required for `'`,
   so a stray quote in `echo 'foo` doesn't match; for other markers the space
   is optional but preferred;
3. has the cursor at or after the end of the request text.

Test cases (unit tests with the project's existing `@Test` / `TestRunner`
tooling):

| Cursor line | Result |
|---|---|
| `user@h:~$ # list big files` | `#`, "list big files" |
| `root@h:~# # why is disk full` | `#`, "why is disk full" (skips the prompt's `#`) |
| `mysql> -- top 10 tables by size` | `--` |
| `> // parse this json` | `//` |
| `PS C:\> ' what does this do` | none with default markers, so it goes to pass 1 and is rescued; `'` if the marker is enabled |
| `$ echo 'foo` | none, even with `'` enabled |
| `$ curl http://x/y` | none |
| `$ ls -la # why does this fail` | `#`, "why does this fail"; the `ls -la` before it is left alone |

**Deferred:** the case where the user already pressed Enter on the comment,
so the request is on the previous prompt line and the cursor line is an empty
prompt. Pass 1 will usually pick this up from the screen anyway.

### 5.3 Single-line insertion rules

A reply is treated as a single-line command only if it matches the output
convention (§6.2) **and** all of these are true:

- the alternate screen is off;
- the cursor line text is **unchanged** since the snapshot (compare
  strings), so the user hasn't typed in the meantime;
- the cursor is at the end of the line;
- one of:
  - there was an explicit request, either detected locally (§5.2) or
    rescued by the classifier and verified (§6.1), or
  - there's no request and the cursor line looks like an empty prompt: it
    ends with a common prompt terminator (`$ `, `# `, `% `, `> `, `] `) and
    nothing follows it.

Otherwise the command is shown in the overlay with a Copy button.

**Insertion:**

1. Erase the request: send `DEL` (`^?`, the same as `ERASE_STRING` in
   `JTerminalPane`) once for each character from `startColumn` to the
   cursor. Backspaces are used instead of `^U` because they erase only the
   comment. A user who typed `ls -la # why…` keeps `ls -la`, and backspace
   behaves the same in bash, zsh, psql and REPLs whatever their key bindings.
2. Send `<marker> <command>`, using the marker the user typed, or `#` when
   there was no request.
3. **Sanitise first:** reject a command containing any control character
   (`< 0x20`, `0x7f`, ESC) or longer than 500 characters; reject a
   multi-line reply outright (it goes to the overlay instead). **Never send
   CR or LF.**

Note for docs: zsh needs `setopt interactivecomments` if the user wants to
press Enter on a commented line. Normally they delete the marker first, so
this rarely matters.

## 6. Prompts and templates

### 6.1 Two-pass pipeline

**Pass 1 — classify** (skipped when a request was detected locally, §5.2):

- Input: the redacted snapshot, the **cursor line on its own** as
  `{{cursor_line}}`, and the list of scenarios (name and description) taken
  from the installed templates. The built-in `explicit-request` scenario is
  always on that list. Its description tells the model the user may have
  typed a question or instruction on the current line, perhaps as a comment
  in syntax Terminator didn't recognise (`' …`, `% …`, `; …`, `REM …`), or
  as plain words.
- Asks for JSON:
  ```json
  {"scenario": "<name>",
   "request": "<one-sentence user need, explicit or inferred>",
   "request_span": "<exact text copied from the cursor line, including its comment marker, or empty>",
   "comment_marker": "<the marker the user used, or empty>",
   "confidence": 0.0}
  ```
- Sends `response_format: {"type": "json_object"}`, which Ollama, llama.cpp,
  LM Studio and vLLM mostly support. The reply is parsed leniently: take the
  first `{…}` block. If parsing fails or the scenario is unknown, use
  `general`.
- **Rescuing a request.** When `scenario = explicit-request`, the reply is
  checked against the snapshot. Model-reported columns are never trusted.
  - `request_span` must appear **verbatim** in the cursor line and end at
    the cursor (ignoring trailing whitespace), and `comment_marker`, if
    given, must be the start of `request_span`, and must be preceded by the
    start of the line or whitespace (the same rule as §5.2).
  - If all of that holds, Terminator builds a
    `DetectedRequest{marker, text, startColumn, endColumn}` from the position
    it found itself. From here the request is treated exactly like a locally
    detected one, including single-line insertion (§5.3).
  - If not, the request text is still used for pass 2, but the result goes
    to the **overlay only**. Nothing is erased or inserted.
  - `RequestDetector` has unit tests for the verification step, using
    made-up classifier replies: correct, wrong-span, span-not-at-cursor and
    marker-mismatch cases.
- `max_tokens` is small (about 150) and `temperature` 0.
- An optional **classifier model** preference lets a smaller, faster model
  do this pass; it defaults to the main model.
- A **"Skip classification"** preference uses `general` directly, for
  slow models.

**Pass 2 — answer:** render the chosen scenario's template and stream the
reply.

### 6.2 Output convention (plain text, no JSON)

Every answer template ends with the same instruction, from a shared
`_output-format.md` partial:

> If the single most useful response is one shell command the user could
> run, reply with exactly one line: `COMMAND: <command>`.
> Otherwise reply with a concise explanation (at most ~15 lines); put any
> commands on their own lines.

The presenter buffers the first 8 characters of the stream. If they are
`COMMAND:`, it collects the rest of the reply and applies §5.3. If not, it
streams straight into the overlay.

### 6.3 Preferences ("LLM" group in `TerminatorPreferences`)

| Key | Type | Default |
|---|---|---|
| `llmEnabled` | Boolean | `false` |
| `llmEndpoint` | String | `http://localhost:11434/v1` |
| `llmModel` | String | `""` (must be set) |
| `llmClassifierModel` | String | `""` (use `llmModel`) |
| `llmApiKeyEnvVar` | String | `TERMINATOR_LLM_API_KEY` (optional; the key never goes in the prefs file) |
| `llmContextChars` | Integer | `8000` |
| `llmSkipClassification` | Boolean | `false` |
| `llmAllowNonLocalEndpoint` | Boolean | `false` |
| `llmRedactSecrets` | Boolean | `true` |
| `llmCommentMarkers` | String | `# -- //` (space-separated; `'` can be added) |
| `llmTimeoutSeconds` | Integer | `120` |
| `llmOverlayFontSizeDelta` | Integer | `-2` |
| `llmDebugLog` | Boolean | `false` |

### 6.4 Template files

Default templates ship with the app in `terminator/lib/llm/`. The user can
override any of them, or add new ones, in `~/.terminator/llm/`
(`org.jessies.terminator.dotDirectory` + `/llm`). A user file with the same
name replaces the bundled one.

```
~/.terminator/llm/
  context.md                 # user's general context, always included
  redact-patterns.txt        # extra regexes, one per line
  prompts/
    _system.md               # shared system prompt
    _output-format.md        # shared output convention (§6.2)
    classify.md              # pass 1
    explicit-request.md      # scenario: user typed a comment request
    command-error.md         # scenario: last command failed
    log-error.md             # scenario: error visible in a log / output
    general.md               # fallback: "what's most useful here?"
    <user-added>.md          # any extra scenario; auto-listed in classify
```

Each scenario template has front matter:

```markdown
---
scenario: command-error
description: The most recent command in the shell failed or printed an error.
---
{{> _system}}
## About the user
{{user_context}}
## Terminal ({{screen_kind}}, title: {{title}})
{{screen}}
## Task
The user's last command appears to have failed. {{request}}
Explain the most likely cause and give the fix.
{{> _output-format}}
```

The `_system`, `## About the user` and similar blocks go in the
`system` message, and the screen and task go in the `user` message. The
split marker is `---8<---`.

**Variables:** `{{screen}}`, `{{cursor_line}}`, `{{screen_kind}}`, `{{title}}`, `{{os}}`,
`{{request}}` (explicit or inferred), `{{comment_marker}}`,
`{{user_context}}`, `{{scenario}}`. **Partials:** `{{> name}}`. Rendering is
a simple regex replacement with no template engine. Unknown variables are
left in and logged in debug mode.

**User context** (`context.md`) is free text: the user's field,
environment, preferences, network and file-system layout. It is **not**
redacted, because the user wrote it, and it counts against the character
budget before the screen does.

## 7. Privacy and safety

- **Hotkey only.** No request is ever made without an explicit key press.
  `llmEnabled` defaults to off, and pressing the hotkey while it's off
  opens a short dialog pointing to Preferences.
- **Endpoint guard.** Unless `llmAllowNonLocalEndpoint` is on, the endpoint
  host must resolve **only** to loopback, RFC 1918 (10/8, 172.16/12,
  192.168/16), link-local, CGNAT 100.64/10 (Tailscale) or IPv6 ULA/loopback.
  Resolution happens on every request. Redirects are disabled
  (`HttpClient.Redirect.NEVER`). `HttpClient` doesn't expose the address it
  actually connected to, so there is a small window for DNS rebinding
  between our lookup and the connection. That's acceptable when the threat
  is accidentally sending data to a cloud endpoint; the point of the guard is
  to stop configuration mistakes, not a hostile local DNS server.
- **Redaction** (on by default) applies to the screen text before any
  pass. Built-in patterns:
  - `-----BEGIN … PRIVATE KEY-----` … `END` blocks
  - AWS access key IDs `A(KIA|SIA)[0-9A-Z]{16}`, and `aws_secret_access_key\s*[=:]\s*\S+`
  - `(?i)\b(password|passwd|pwd|secret|token|api[_-]?key|client[_-]?secret)\b\s*[=:]\s*\S+`
  - `(?i)authorization:\s*\S+(\s+\S+)?`, `(?i)bearer\s+[A-Za-z0-9._~+/-]+=*`
  - GitHub `gh[pousr]_[A-Za-z0-9]{36,}`, OpenAI-style `sk-[A-Za-z0-9_-]{20,}`, Slack `xox[abprs]-[A-Za-z0-9-]+`
  - JWTs `eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+`
  - URL credentials `://[^/\s:@]+:[^/\s@]+@`

  Matches become `[REDACTED]`. User patterns in `redact-patterns.txt` are
  added to these.
- **API key** is read from the environment variable named in preferences and
  never stored.
- **No logging** unless `llmDebugLog` is on. Debug logs go to
  `~/.terminator/llm/debug.log` with permissions set to `600`.
- **Edit → LLM → Preview Request…** (menu only, no shortcut). It shows the exact
  redacted system and user messages that would be sent, without sending
  them. This lets the user check redaction and context, and helps with
  tuning templates.
- **No execution.** Never send CR or LF; control characters are sanitised (§5.3).

## 8. Overlay

- A `SuggestionOverlay extends JComponent` in a layer above the scroll pane
  inside `JTerminalPane`, anchored top-right with a 12px margin. Width is at
  most 50% of the pane (minimum about 40 columns); height is at most 40%,
  with a scroll bar beyond that.
- Uses the terminal font at `size + llmOverlayFontSizeDelta`. Colours come
  from the palette: background is the terminal background mixed about 8%
  toward the foreground, at 95% opacity; the border is the selection colour.
- Contents: a header line (scenario and status/spinner), a body (read-only
  `JTextArea` with wrapping, streamed into), and a footer (`Copy` · `Esc to close`).
- Code fences in the reply are shown as-is. Markdown rendering is out of
  scope for v1.
- Focus stays on the terminal. The overlay never takes keyboard focus. Key
  behaviour is handled in `JTerminalPane.KeyHandler` (§3.2).
- Errors (connection refused, 4xx/5xx, timeout, endpoint rejected) appear in
  the overlay in the error colour with a one-line hint.

## 9. Technical decisions

### 9.1 JDK baseline: move to 17 gradually, fall back to 11 if needed

**Goal:** build and run on JDK 17 with no Java 8 assumptions in new code.
This is done as a small, time-boxed step at the start of Phase 0 (0a). If it runs
into serious friction we **fall back to 11** (Terminator already runs on 11
routinely) instead of letting it grow into a migration project.

**Where things stand today**

- `salma-hayek/native/Headers/JAVA_MAJOR_VERSION.h` sets the version to `8`.
  That value is used by:
  - `universal.make`: `-source`/`-target $(JAVA_MAJOR_VERSION)`, plus an
    `-bootclasspath …/jre/lib/rt.jar` check for API compatibility. JDK 9 and
    later have no `rt.jar`.
  - `find-jdk-root.rb`: Windows registry and FreeBSD lookups.
  - `java-launcher.cpp`: Windows JRE version check.
- `invoke-java.rb` `check_java_version` only requires Java 6 or later.
- This affects every salma-hayek project (Evergreen, lwm), not just
  Terminator.

**Trial compile already done** (2026-09-13, JDK 25 `javac`, output to a
scratch directory, repo untouched):

| Sources | `--release 17` | `--release 11` |
|---|---|---|
| salma-hayek + terminator (251 files) | 0 errors; 85 warnings (84 `this-escape`, 1 `dangling-doc-comments`, both JDK 21+ lints) | 0 errors |
| salma-hayek + evergreen + lwm (302 files) | 0 errors | not tried |

So **compiling is not the risk. Runtime reflection is.** JDK 17 blocks
reflective access to JDK internals, and these call sites will fail there.
They currently work on 11 only because 11 allows that access by default.

| Call site | Reaches into | Effect on 17 | Fix |
|---|---|---|---|
| `e.gui.HorizontalScrollWheelListener` (used by `TerminalView`) | `BasicScrollBarUI.scrollByUnits/ByBlock` | Horizontal wheel scrolling fails and prints a stack trace | `--add-opens java.desktop/javax.swing.plaf.basic=ALL-UNNAMED`, or reimplement with the public `JScrollBar` API (preferred; it's a few lines) |
| `e.util.GuiUtilities.fixWmClass` | `sun.awt.X11.XToolkit.awtAppClassName` | Linux WM_CLASS is wrong (the failure is already caught and logged) | `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED` on Linux |
| `e.util.ProcessUtilities.getProcessId` | `Process.pid` field | Returns −1 (Evergreen `BuildUi`) | Replace with `Process.pid()` (Java 9+) |
| `e.util.TimerUtilities` | `javax.swing.TimerQueue` | Debug menu / hung-exit diagnostics only | `--add-opens java.desktop/javax.swing=ALL-UNNAMED`, or accept that it degrades |

Every one of these already catches the exception, so none will crash the app.

**Steps**, one commit each so they're easy to revert:

1. `JAVA_MAJOR_VERSION.h` → `17`.
2. `universal.make`: for version 9 and later, use `--release $(JAVA_MAJOR_VERSION)`
   in place of `-source`/`-target`/`-bootclasspath`, for both javac and
   ecj. This removes the `rt.jar`/`BOOT_JDK` logic for modern JDKs and gives
   real API checking. Add `-Xlint:-this-escape` only if the build JDK's javac
   accepts it.
3. `invoke-java.rb`:
   - require Java ≥ `JAVA_MAJOR_VERSION` (read from the header, the same way
     `find-jdk-root.rb` does), with a clear "Terminator needs JDK 17+"
     message, not an `UnsupportedClassVersionError`;
   - add the `--add-opens` flags from the table above that are still needed.
4. Replace `ProcessUtilities.getProcessId` with `Process.pid()`, and fix
   `HorizontalScrollWheelListener` using public API.
5. Mac: `find-jdk-root.rb` runs a bare `/usr/libexec/java_home`. Change it to
   `java_home -v 17+` so the build doesn't pick an older default JDK. Check
   which JVM `invoke-java.rb` launches at runtime on Mac.
6. Windows `java-launcher.cpp` compares `version < "1.17"` as strings. That
   happens to work for "17"/"21" but is fragile. **Not fixed in this
   branch**; noted for later.

**Smoke test** (Mac first, then Linux): start the app; new tab and window;
typing, including dead keys and IME; copy mode; copy/paste; font size
Cmd+/−; Preferences dialog; screen menu bar and Dock icon on Mac; horizontal
wheel scrolling; `ssh` session; vim/less (alternate screen); resize; close
with a running process; `make test`.

**When to fall back.** Fall back to 11 if, within about a day of work, JDK 17
causes a runtime failure in core terminal behaviour (the pty/native layer,
key handling, or Mac menu bar and Dock integration) with no simple fix. To
fall back, set the header to `11`; the `--release` change from step 2 stays.
**This decision is made at the end of Phase 0a, before any LLM code exists**,
so new code can freely use Java 17 features (records, switch expressions,
text blocks, `var`, pattern-matching `instanceof`) without risking a rewrite.
If we do fall back, the only thing lost from §9.2 is a cancellation detail.

### 9.2 HTTP: `java.net.http.HttpClient`

- `HttpClient.newBuilder().followRedirects(NEVER).connectTimeout(…)`, one
  shared instance.
- Streaming: `sendAsync(request, BodyHandlers.ofLines())`, then parse
  `data: …` lines and stop at `data: [DONE]`.
- Cancelling: `CompletableFuture.cancel(true)` on the future, which closes
  the connection on JDK 16 and later. Also close the `Stream<String>` from
  `ofLines()` so the reader stops at once. (On 11, cancelling doesn't abort
  the underlying exchange; closing the stream still stops us reading.)
- API key: add `Authorization: Bearer …` only when the environment variable
  is set.

### 9.3 JSON: Gson

Gson (Apache-2.0) is a single jar of about 290KB with no dependencies. It
goes in `terminator/lib/jars/gson-<latest>.jar` and is only used in
`terminator.llm`, with records for the message and response types.
**Check** that `package-for-distribution.rb` includes `lib/jars` in the
Mac, Debian and MSI packages. If not, fix the packaging in this branch.

### 9.4 Threading

- `HttpClient`'s own async executor does the network I/O. One app-wide
  single-thread executor handles pipeline orchestration (snapshot
  processing, redaction, template rendering).
- At most one request per pane. Pressing the hotkey while one is in flight
  cancels it and starts a new one.
- Stream chunks reach the EDT through `GuiUtilities.invokeLater`, grouped
  about every 50ms so the UI isn't flooded with events.

## 10. Implementation phases

Each phase ends in a working, committable state.

**Phase 0a — JDK 17 (time-boxed to about 1 day; see §9.1)**
- Steps 1–5 of §9.1, one commit each.
- Run the smoke test on Mac and Linux.
- **Decision point:** stay on 17, or set the header to 11. Record the result
  in this document.

*Status (2026-09-13):* steps 1–5 are done (commits `1d309d82`..`58a82a1d`).
Verified on **Linux aarch64 only** (Ubuntu 26.04, in a container):
- `salma-hayek`, `terminator` and `evergreen` build cleanly with JDK 17 and
  JDK 25, producing class file version 61.
- All 24 salma-hayek unit tests pass on 17 and 25, including a new test for
  `HorizontalScrollWheelListener`. `make test` in `terminator` still
  reports "No tests found!", as it did before these changes.
- Launched under Xvfb on JDK 17: the window opens, the shell runs in the
  pty, typed input reaches it, both `--add-opens` flags are on the JVM
  command line, the window can be found by X11 class `Terminator` (so
  `fixWmClass` works), and there are no reflection warnings or exceptions in
  the log.
- The launcher refuses a (fake) Java 11 `java` with "requires Java 17 or
  newer".

**Still to do before the decision point:** the full smoke test on **Mac**
(build via `java_home -v 17+`, screen menu bar, Dock, Cmd shortcuts, copy
mode, font size, preferences, horizontal scrolling) and interactive Linux
checks that Xvfb can't cover (IME, dead keys, resize).

**Phase 0b — Groundwork**
- Add the Gson jar; confirm `make` and `make test` pass and the app starts.
- Check packaging includes `lib/jars`.
- Add the `terminator.llm` package skeleton, the LLM preferences group and
  the empty Edit → LLM submenu.

**Phase 1 — Core logic, no UI, unit-tested**
- `TerminalSnapshot` (logic that builds from a list of lines, testable
  without Swing), `RequestDetector` (the §5.2 table), `Redactor`,
  `EndpointGuard`, `PromptTemplates` (front matter, partials, variables,
  override precedence), `OpenAiClient` SSE parser (tested against recorded
  stream fixtures).
- A manual smoke test against a local Ollama or llama.cpp server via a small
  `main()`.

**Phase 2 — Hotkey to overlay, single pass**
- `LlmSuggestAction` and its accelerators, including swallowing `^L` for
  Ctrl+Shift+L on Linux (§3.2); `LlmSuggestController`; `SuggestionOverlay`;
  key handling for closing the overlay; cancellation; error display.
- Every reply goes to the overlay. Locally detected requests use the
  `explicit-request` template; everything else uses `general`.
- Edit → LLM → Preview Request….
- **Milestone:** usable day to day, read-only.

**Phase 3 — Single-line insertion**
- The `COMMAND:` convention, `SuggestionPresenter`, `SuggestionInserter`
  with every §5.3 check and sanitising.
- Manual test matrix: bash and zsh locally, bash over SSH, psql, python REPL,
  vim (should fall back to the overlay), typing during the request (should
  fall back to the overlay).

**Phase 4 — Two-pass classification**
- `Classifier`, `classify.md`, `command-error.md`, `log-error.md`, the
  classifier-model and skip-classification preferences, scenarios listed
  automatically from user templates.
- Rescuing requests with unrecognised markers, plus span verification (§6.1).
  Manual checks: `' …` with the default markers, `% …` in a MATLAB/Octave
  prompt, a plain-English request with no marker.
- The overlay header shows the chosen scenario.

**Phase 5 — Polish and docs**
- User-context file, first-run creation of `~/.terminator/llm/` containing
  commented example files.
- Short user docs (setup with Ollama and llama.cpp, the zsh
  `interactivecomments` note, writing templates, redaction).
- Tune the default prompts against 2–3 common local models (such as Qwen
  and Llama variants) using a set of saved screen snapshots.

## 11. Future work

- **Session memory:** background summaries of terminal history per pane,
  added as `{{session_summary}}`. This needs its own privacy design, because
  it would process data without a key press.
- Follow-up questions in the overlay (multi-turn).
- Picking a line from the overlay to insert.
- Per-host context files chosen by matching the title (`user@host`).
- Detecting a request on the previous prompt line (§5.2, deferred).
- Markdown rendering in the overlay.

## 12. Decisions log

| Date | Decision |
|---|---|
| 2026-09-13 | Local models through the OpenAI-compatible `/v1/chat/completions` API with streaming. Hotkey-only triggering. |
| 2026-09-13 | Two-pass pipeline (classify, then answer from a scenario template). User-editable templates plus an always-included `context.md`. |
| 2026-09-13 | Context is the screen plus scrollback within a configurable character budget. Redaction on by default. |
| 2026-09-13 | Add a JSON library (Gson) instead of hand-writing a parser. |
| 2026-09-13 | Each request stands alone in v1. Session memory is future work. |
| 2026-09-13 | Menu: **Edit → LLM**. Hotkey: Ctrl+Cmd+L on Mac, Ctrl+Shift+L elsewhere. |
| 2026-09-13 | Default markers are `#`, `--` and `//`. `'` is off by default; requests using it (or any other syntax) can be rescued by pass 1 with span verification. |
| 2026-09-13 | Move to JDK 17 gradually (Phase 0a), with 11 as the fallback. No Java 8 assumptions in new code. |

## 13. Open questions

None blocking. Items to settle during implementation:

1. The outcome of the Phase 0a decision point (17 or 11).
2. Whether `package-for-distribution.rb` bundles `lib/jars` (Phase 0b).
