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
4. The answer streams into the overlay. *(Redesigned in Phase 3: nothing is
   ever typed into the terminal automatically.)*
   - **A command** (the model replied `COMMAND: …`) is shown on its own with
     "Tab to insert". **Tab** puts it on the command line without running
     it, replacing the typed `# request` if there was one. If that isn't safe
     (§5.3), it's copied to the clipboard instead and the overlay says why.
   - **Copy**, or the Copy shortcut (Cmd+C / Alt+C) when nothing is selected
     in the terminal, copies just the command, or the whole answer if it
     isn't a command.
   - Esc, or any other key, closes the overlay; the key still reaches the
     terminal.

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
 SuggestionOverlay (streams the reply; SuggestionReply parses COMMAND:)
   │
   ▼  user presses Tab
 SuggestionInserter (safety checks) ──► TerminalControl.sendUtf8String, or copy and explain
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
| `SuggestionReply` | Parses a finished reply: a `COMMAND:` first line (tolerating case, backticks, a code fence and trailing explanation) is a command. Decides what's shown and what Copy copies. |
| `SuggestionInserter` | When the user presses Tab: safety checks, then erases the request and inserts the command (no newline), or explains why not. |
| `SuggestionOverlay` | Swing component shown over the terminal (§8). |
| `LlmDebugLog` | Opt-in log of requests and responses. |

### 3.2 Changes to existing files

| File | Change |
|---|---|
| `TerminatorMenuBar.java` | New **Edit → LLM** submenu (built with `GuiUtilities.makeMenu`, added after `CopyModeAction` in `makeEditMenu()`), containing `LlmSuggestAction` ("Suggest") and `LlmPreviewRequestAction` ("Preview Request…"), both `extends AbstractPaneAction`. Accelerators are fixed, not built from `defaultKeyStrokeModifiers`: Mac uses `CTRL_DOWN_MASK \| META_DOWN_MASK` + `L`, and everything else uses `CTRL_DOWN_MASK \| SHIFT_DOWN_MASK` + `L`. On Mac, Cmd is present, so `isKeyboardEquivalent` already sends the event to the menu bar and `^L` never reaches the pty. **Test this.** |
| `JTerminalPane.java` | Create the `LlmSuggestController`. Wrap `scrollPane` in a layered container so the overlay can sit on top of it and moves with the pane when tabs change. **Linux/Windows hotkey:** Ctrl+Shift+L doesn't match `isKeyboardEquivalent` when the default modifier is Alt, so `KeyHandler` would otherwise send `^L` (clear screen) to the pty. *(As built in Phase 2:)* `keyPressed` leaves the chord unconsumed so the menu accelerator runs the action, and `keyTyped` swallows the matching `^L` (`0x0C`). When comparing modifiers, use the `_DOWN_MASK` value itself, not `KeyStroke.getModifiers()`, which also includes the legacy bits; that mistake let `^L` through in the first end-to-end run. The terminal can't tell Ctrl+Shift+L from Ctrl+L anyway, so no key the user could use is lost. **Overlay keys:** if the overlay is showing, Esc closes it (and cancels any request) and is consumed; on Linux the `KEY_TYPED` Esc that follows is swallowed too. Any other key apart from modifiers and menu shortcuts closes it (cancelling the request) and is then handled as usual. |
| `TerminatorPreferences.java` | New "LLM" preferences group (§6.3). |
| `terminator/lib/jars/` | Add the JSON library jar (§9.3). Both `universal.make` (`EXTRA_JARS`) and `invoke-java.rb` already pick up `lib/jars/*.jar`. |

## 4. Context extraction

- **Source:** `TerminalModel.getTextLine(i).getString()` for
  `i ∈ [max(0, lineCount − N), lineCount)`, walking backwards from the last
  line until the **character budget** (default 8000) is used up. The budget
  is a hard limit. The most recent lines, which are the visible screen, are
  kept first and scrollback fills whatever is left; if the screen alone is
  over budget, its oldest lines are dropped.
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
and `//` by default. The list is a preference and is in **priority order**
(see §5.2). `'` can be added to it but is **off by default** because shell
quoting makes false matches too likely.

Markers that aren't on the list, including `'` and anything else such as
`%`, `;` or `REM`, are still handled: the request goes through the two-pass
path and the classifier can **rescue** it (§6.1). Local detection is just a
fast path that skips pass 1.

### 5.2 Detection on the cursor line

The request must be on the **cursor line**, which is not necessarily the last
line on screen. A marker occurrence qualifies if it:

1. is at the start of the line or follows whitespace. This skips root
   prompts like `root@h:~#` and URLs like `http://`;
2. is followed by a space and then non-blank text. This is required for `'`,
   so a stray quote in `echo 'foo` doesn't match; for other markers the space
   is optional but preferred;
3. has the cursor at or after the end of the request text.

At each position only the longest matching marker is considered, so `--`
isn't read as `-`. Among qualifying occurrences, the **first occurrence of
the highest-priority marker** wins, not the first on the line. So in
`$ git checkout -- file # restore it`, the `#` comment beats the command's
own `--`. *(Changed in Phase 1: the original "first occurrence on the line"
rule got this example wrong.)*

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

### 5.3 Inserting a suggested command (Tab)

*(Redesigned in Phase 3.)* Nothing is typed automatically. When the overlay
shows a finished command suggestion, **Tab** inserts it, and only if all of
these are true:

- the alternate screen is off, both now and when the request was made;
- the cursor line text **and** cursor column are **unchanged** since the
  snapshot, so nothing has been typed or printed in the meantime;
- one of:
  - there was an explicit request, either detected locally (§5.2) or
    rescued by the classifier and verified (§6.1), or
  - there's no request and the cursor is at an empty prompt: whitespace
    before the cursor, after a non-alphanumeric character (`$`, `#`, `%`,
    `>`, `]`, `:`, `➤`, `❯`…), with nothing after the cursor. Text the user
    has typed usually ends in a letter or digit;
- the line isn't asking for a password or passphrase (otherwise the command
  would be typed invisibly, and Enter would submit it).

Otherwise the command is copied to the clipboard and the overlay explains
why it wasn't inserted.

**Insertion:**

1. Erase the request: send `DEL` (`^?`, the same as `ERASE_STRING` in
   `JTerminalPane`) once for each character from `startColumn` to the
   cursor. Backspaces are used instead of `^U` because they erase only the
   comment. A user who typed `ls -la # why…` keeps `ls -la`, and backspace
   behaves the same in bash, zsh, psql and REPLs whatever their key bindings.
2. Send the command itself, with no comment marker, since the user has
   explicitly accepted it. Use bracketed paste when the shell has turned it
   on, so the text goes in literally (for example in vi-mode shells).
3. **Sanitise first:** refuse a command containing any control character
   (`< 0x20` including tab and newline, `0x7f`, C1 `0x80`–`0x9f`) or longer
   than 1000 characters. Only the reply's first `COMMAND:` line is used.
   **Never send CR or LF.**

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
- Sends `response_format: {"type": "json_object"}`, which Ollama, llama.cpp
  and vLLM support. LM Studio rejects it with HTTP 400, so on a 400 the
  request is retried once without it. The reply is parsed leniently: remove
  `<think>` blocks, then take everything from the first `{` to the last `}`,
  which copes with code fences and chatter. If parsing fails or the scenario
  is unknown, use **`explain`** *(changed in Phase 4, at the user's request:
  when it's unclear what the user wants, explain what's in the terminal,
  such as what source code does or what a diff changes).*
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
- `temperature` is 0. `max_tokens` is **not** set: reasoning models count
  their thinking against it and could return an empty reply.
- An optional **classifier model** preference lets a smaller, faster model
  do this pass; it defaults to the main model.
- A **"Skip classification"** preference uses `general` directly, for
  slow models. `general` has no `scenario:` front matter, so the classifier
  never chooses it. It explains an error if there is one, and otherwise
  explains what's in the terminal.

**Bundled scenarios** (Phase 4): `explicit-request` (status "Answering"),
`command-error` ("Diagnosing the error"), `log-error` ("Looking into the
error") and `explain` ("Explaining", also the fallback). Each template's
`status:` front matter is what the overlay shows while answering; during pass
1 it shows "Working out what would help". A `COMMAND:` reply may be followed
by up to three lines of explanation, which suits error diagnoses.

**Pass 2 — answer:** render the chosen scenario's template and stream the
reply.

### 6.2 Output convention (plain text, no JSON)

Every answer template ends with the same instruction, from a shared
`_output-format.md` partial:

> If the single most useful response is one shell command the user could
> run, reply with exactly one line: `COMMAND: <command>`.
> Otherwise reply with a concise explanation (at most ~15 lines); put any
> commands on their own lines.

The reply always streams into the overlay. When it finishes,
`SuggestionReply` checks whether the first line is `COMMAND: …`. If so, the
overlay shows just the command, offers Tab to insert it (§5.3), and Copy
copies just the command.

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
| `llmOverlayFontPercent` | Integer | `85` (the preferences UI only accepts positive integers, so a size delta such as −2 won't work) |
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
    _about-the-user.md       # shared: includes {{user_context}}
    _terminal.md             # shared: includes {{screen}} in <terminal> tags
    _output-format.md        # shared output convention (§6.2)
    classify.md              # pass 1
    explicit-request.md      # scenario: user typed a comment request
    command-error.md         # scenario: last command failed
    log-error.md             # scenario: error visible in a log / output
    explain.md               # scenario: explain what's in the terminal; the fallback
    general.md               # used instead of pass 1 when classification is skipped
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
---8<---
## Terminal ({{screen_kind}}, title: {{title}})
{{screen}}
## Task
The user's last command appears to have failed. {{request}}
Explain the most likely cause and give the fix.
{{> _output-format}}
```

Everything before the `---8<---` line goes in the `system` message, and
everything after it in the `user` message. A template without the marker is
all `user` message. Partials are expanded before variables; variable values
are inserted as-is and never scanned again, so terminal text containing `{{`
can't inject placeholders.

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
- **No proxy for local endpoints.** Unless `llmAllowNonLocalEndpoint` is on,
  the HTTP client is built with `NO_PROXY`. Otherwise a system or corporate
  proxy could carry a "local" request off the machine, since the proxy
  rather than our code makes the actual connection. *(Added in Phase 1.)*
- **Redaction** (on by default) applies to the screen text before any
  pass. Built-in patterns:
  - `-----BEGIN … PRIVATE KEY-----` … `END` blocks
  - AWS access key IDs `A(KIA|SIA)[0-9A-Z]{16}`
  - any `key = value` or `key: value` whose key *contains* password, passwd,
    secret, token or api key (so `DB_PASSWORD=…`, `GITHUB_TOKEN=…`,
    `aws_secret_access_key = …` and JSON `"password": "…"` all match), with
    quoted values handled; also `pwd=…`. Only the value is replaced. This is
    deliberately broad (`max_tokens=100` gets redacted too). *(Broadened in
    Phase 1: `\bpassword\b` missed `DB_PASSWORD`, because `_` counts as a
    word character.)*
  - `Authorization: …` headers and `Bearer …` tokens
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
- Uses the terminal font scaled to `llmOverlayFontPercent`. Colours come
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

- `HttpClient.newBuilder().version(HTTP_1_1).followRedirects(NEVER).connectTimeout(…)`,
  with `NO_PROXY` for local endpoints (§7). HTTP/1.1 avoids `h2c` upgrade
  attempts that some local servers handle badly.
- Streaming: `sendAsync(request, BodyHandlers.ofLines())`, then parse
  `data: …` lines and stop at `data: [DONE]`.
- Cancelling: `CompletableFuture.cancel(true)` on the future, which closes
  the connection on JDK 16 and later. Also close the `Stream<String>` from
  `ofLines()` so the reader stops at once. (On 11, cancelling doesn't abort
  the underlying exchange; closing the stream still stops us reading.)
- API key: add `Authorization: Bearer …` only when the environment variable
  is set.
- Timeout: `HttpRequest.timeout` only covers waiting for the response headers,
  so a separate deadline covers the whole request, including a stalled
  stream. When it expires the request fails with an `LlmException` and the
  connection is released.
- Errors (HTTP status with OpenAI- or Ollama-style error bodies, connection
  refused, timeouts, error events in the stream) all become an `LlmException`
  with a message the user can act on.

### 9.3 JSON: Gson

Gson (Apache-2.0) is a single jar of about 310KB. It
goes in `terminator/lib/jars/gson-<latest>.jar` and is only used in
`terminator.llm`, with records for the message and response types.
**Check** that `package-for-distribution.rb` includes `lib/jars` in the
Mac, Debian and MSI packages. If not, fix the packaging in this branch.

Gson's annotations refer to Error Prone's, so `error_prone_annotations`
(about 20KB) is in `lib/jars` too. It's only needed at compile time; without
it, `javac -Xlint:all` warns wherever `JsonParser` is used.

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

Follow-up `3ca2d5ce`: the Mac JDK lookup now checks `JAVA_HOME` first (so
SDKMAN! and Homebrew JDKs work), then `java_home -v 17+`, and otherwise fails
with a clear message.

**Decision (2026-09-13): stay on JDK 17.** The Mac smoke test passed.

**Phase 0b — Groundwork**
- Add the Gson jar; confirm `make` and `make test` pass and the app starts.
- Check packaging includes `lib/jars`.
- Add the `terminator.llm` package skeleton and the LLM preferences group.
  The Edit → LLM submenu moves to Phase 2, so the menu never shows items
  that do nothing.

*Status (2026-09-13): done.*
- `terminator/lib/jars/gson-2.14.0.jar`. The SHA-1 matched on two Maven
  Central mirrors; provenance is in `lib/jars/README.txt`.
- **Packaging needs no change:** the installer file list includes all of
  `lib/`, and `invoke-java.rb` put the jar on the runtime classpath (checked
  on a launched app).
- New "LLM" preferences tab with the §6.3 keys, added after Presets because
  `willAddRows` looks Presets up by index. Checked visually under Xvfb.
- `terminator.llm.LlmSettings`: an immutable record read from preferences
  (classifier model falls back to the main model, API key comes from the
  environment, markers sorted longest first), with unit tests. Terminator's
  `make test` now runs 2 passing tests; before this it reported "No tests
  found!".

**Phase 1 — Core logic, no UI, unit-tested**
- `TerminalSnapshot` (logic that builds from a list of lines, testable
  without Swing), `RequestDetector` (the §5.2 table), `Redactor`,
  `EndpointGuard`, `PromptTemplates` (front matter, partials, variables,
  override precedence), `OpenAiClient` SSE parser (tested against recorded
  stream fixtures).
- A manual smoke test against a local Ollama or llama.cpp server via a small
  `main()`.

*Status (2026-09-13): done* (commits `ef684ef3`..`ce27bb96`). 28 unit tests
pass consistently on JDK 17 and JDK 25:
- `TerminalSnapshot`, `RequestDetector` (the §5.2 table, edge cases and
  verification of classifier-reported requests), `Redactor`, `EndpointGuard`
  and `PromptTemplates` (including loading from temporary directories).
- `ChatStreamParser`: recorded Ollama- and llama.cpp-style streams, JSON
  replies from servers that don't stream, and error bodies.
- `OpenAiClient`, end to end against a local fake server: streaming, the
  Authorization header, HTTP errors, connection refused, cancelling
  mid-stream (the reader thread is freed straight away), and the
  whole-request timeout.

**Not done here:** the smoke test against a real model, because there's no
LLM server in the build environment. To try it by hand:
`java -cp terminator/.generated/classes:salma-hayek/.generated/classes:terminator/lib/jars/gson-2.14.0.jar terminator.llm.OpenAiClient http://localhost:11434/v1 <model> "Say hello"`.

Design changes made during this phase (all reflected above): markers have a
priority order (§5.2), the context budget is a hard limit (§4), the secret
key/value pattern is broader (§7), local endpoints use no proxy (§7), there's a
timeout on the whole request (§9.2), and `error_prone_annotations` is added
(§9.3).

**Phase 2 — Hotkey to overlay, single pass**
- The Edit → LLM submenu; `LlmSuggestAction` and its accelerators, including swallowing `^L` for
  Ctrl+Shift+L on Linux (§3.2); `LlmSuggestController`; `SuggestionOverlay`;
  key handling for closing the overlay; cancellation; error display.
- Every reply goes to the overlay. Locally detected requests use the
  `explicit-request` template; everything else uses `general`.
- Edit → LLM → Preview Request….
- **Milestone:** usable day to day, read-only.

*Status (2026-09-13): done* (commits `038b5bc0`..`cb68a209`). 33 unit tests
pass on JDK 17 and 25. The test that renders the real bundled templates
checks they have no unknown variables. It was also checked end to end under
Xvfb on Linux against a fake streaming server:
- A `#` request streams into the overlay, and the screen isn't cleared.
- Esc closes the overlay without sending Esc to the shell.
- Typing cancels the request (the server sees the disconnect) and still
  reaches the shell.
- With the server stopped, the overlay shows "Couldn't connect … Is the
  server running?".
- The Edit → LLM submenu and Preview Request… work.
- The debug log is created with permissions 600.

Also added: `SuggestionPipeline` (shared by Suggest and Preview), `LlmFiles`
(template, context and redaction files re-read on every request so edits
apply at once), a "Not configured" message when no model is set, and a Copy
button. The user context shares the character budget, but at least 1000
characters of terminal are always kept.

**Not yet checked on Mac:** Ctrl+Cmd+L with the screen menu bar, and how the
overlay looks.

**Phase 3 — Inserting and copying commands** *(redesigned: Tab to accept, not automatic insertion)*
- `SuggestionReply` (the `COMMAND:` convention), `SuggestionInserter` with
  every §5.3 check and sanitising, Tab handling in the overlay, and the Copy
  shortcut acting on the overlay when nothing is selected.
- Manual test matrix: bash and zsh locally, bash over SSH, psql, python REPL,
  vim (should copy instead), typing during the request (closes the overlay).

*Status (2026-09-13): done* (commits `e8ce09ff`, `ddee8ca4`). 62 unit tests
pass (salma-hayek and terminator), including `SuggestionReply` and
`SuggestionInserter` (insertion replacing a request, a range of prompt
styles including `➤` and `❯`, and every refusal). Checked under Xvfb with
bash:
- A command is shown without its label, with "Tab to insert".
- Alt+C copies just the command; pasting it back confirmed that.
- Tab replaced `# count words in each file` with the command, which wasn't
  run.
- Tab at an empty prompt inserted the command.
- With `ls -la` already typed, Tab refused, copied instead and explained why.

**Not yet checked:** zsh, SSH, psql and Python REPLs, and anything on Mac.

**Phase 4 — Two-pass classification**
- `Classifier`, `classify.md`, `command-error.md`, `log-error.md`, the
  classifier-model and skip-classification preferences, scenarios listed
  automatically from user templates.
- Rescuing requests with unrecognised markers, plus span verification (§6.1).
  Manual checks: `' …` with the default markers, `% …` in a MATLAB/Octave
  prompt, a plain-English request with no marker.
- The overlay header shows the chosen scenario.

*Status (2026-09-13): done* (commits `2f1e72d8`..`f872c0b5`). 67 unit tests
pass, including `Classifier` (lenient JSON, unknown scenarios, rescued and
paraphrased requests, full-screen apps) and rendering the classifier prompt
and every bundled scenario with no unknown variables. Checked under Xvfb
against a fake classifier and answer server:
- A failed `ls` → classifier reply wrapped in a code fence → `command-error`
  → a command plus a diagnosis, which Tab inserted.
- A diff on screen → `explain`.
- `' what does this do`, with `'` not among the markers → rescued as
  `explicit-request` → Tab replaced the request with the command.
- HTTP 400 for `response_format` → retried without it → a reply that wasn't
  JSON → the `explain` fallback.
- Preview Request shows both passes and sends nothing.

**Still to do:** tune the prompts against real local models (Phase 5); check
`% …` in Octave and plain-English requests with a real model.

**Phase 5 — Polish and docs**
- User-context file, first-run creation of `~/.terminator/llm/` containing
  commented example files.
- Short user docs (setup with Ollama and llama.cpp, writing templates,
  redaction). The zsh `interactivecomments` note is no longer needed, because
  nothing is inserted with a `#` prefix.
- Tune the default prompts against 2–3 common local models (such as Qwen
  and Llama variants) using a set of saved screen snapshots.

*Status (2026-09-13): done except the tuning runs* (commits `b24bb76a`..`511bc552`):
- **Starter files:** the first suggestion, or the new Edit > LLM > Show LLM
  Files, creates `~/.terminator/llm/` containing a commented `context.md`,
  `redact-patterns.txt` and `prompts/README.txt`. `<!-- -->` comments in
  `context.md` aren't sent. An existing directory is left alone. Checked
  under Xvfb, including the fallback dialog showing the path when no file
  manager is available.
- **User guide:** `doc/llm-suggestions.md`, linked from the README.
- **Tuning harness:** `PromptEval` plus 12 fixtures in `tests/llm-eval/`
  (failed git push, missing Python module, permission denied, a Java stack
  trace in less, an nginx upstream error, a diff, Python in vim, `df`
  output, `#` and `--` requests, and requests in `'` and plain words). It
  was checked end to end against the fake server. **The tuning runs
  themselves weren't possible here**, because model downloads are blocked
  in the build environment. Run `PromptEval` on a machine with Ollama and
  adjust the templates from its report.
- 72 unit tests pass.

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
| 2026-09-13 | Phase 0a done: **staying on JDK 17** after the Linux and Mac smoke tests. |
| 2026-09-13 | Gson 2.14.0. Overlay font size is a percentage. The Edit → LLM menu arrives with its actions in Phase 2. |
| 2026-09-13 | Phase 4: when it's unclear what the user wants, **explain what's in the terminal** (code, diffs, config, output) rather than guessing. `explain` is the classifier's fallback, and `general` explains too. |
| 2026-09-13 | Phase 3 redesigned after trying Phase 2: **no automatic insertion**. Tab inserts a shown command (without a `#` prefix, never run); if that isn't safe it's copied instead. Copy and the Copy shortcut copy just the command. |

## 13. Open questions

None blocking.
