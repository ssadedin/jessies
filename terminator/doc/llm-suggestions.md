# LLM Suggestions

Terminator can ask a large language model for help with whatever is in your terminal: why a command
failed, what an error in a log means, what some source code or a diff does, or how to do something you
describe. It reads the text Terminator already has on screen, including output from remote machines over
SSH, so nothing needs to be installed where you're logged in.

It's designed for **local models** (Ollama, llama.cpp, LM Studio and the like) through the
OpenAI-compatible API, because terminals are full of sensitive information.

## Privacy

- **Nothing is sent unless you ask.** A request is only made when you press the Suggest shortcut, and only
  if "Enable LLM suggestions" is on in Preferences. It's off by default.
- **Local and private networks only**, unless you turn on "Allow endpoints outside local and private
  networks". The endpoint's host must resolve to loopback, a private (RFC 1918) address, link-local, a
  Tailscale-style 100.64/10 address, or an IPv6 unique local address. No proxy is used for these endpoints,
  so a system proxy can't carry requests off your machine.
- **Likely secrets are redacted** before sending: private keys, cloud and API tokens, JWTs, passwords in
  URLs, and `KEY=value` pairs whose key mentions a password, secret, token or API key. You can add your own
  patterns (see below). Redaction is a safety net, not a guarantee.
- **You can see exactly what would be sent**, without sending it, with Edit > LLM > Preview Request...
- **Nothing is logged** unless you turn on "Log LLM requests and responses", which writes to
  `~/.terminator/llm/debug.log`, readable only by you.
- **Nothing is run.** A suggested command is only ever put on the command line when you press Tab, and
  never with a newline.

## Setting up

1. Run an OpenAI-compatible server with a model:
   - **Ollama**: `ollama pull qwen3:8b` (or another model). The endpoint is `http://localhost:11434/v1`.
   - **llama.cpp**: `llama-server -m model.gguf --port 8080`. The endpoint is `http://localhost:8080/v1`.
   - **LM Studio**: start the local server. The endpoint is usually `http://localhost:1234/v1`.
   - A model on another machine on your network (for example a GPU box) works too, using its private
     address or a Tailscale address.
2. In Terminator, open Preferences, go to the **LLM** tab, turn on **Enable LLM suggestions**, and set the
   **endpoint** and **model** (the name your server uses, such as `qwen3:8b`).
3. If your server needs an API key, put it in the environment variable named in Preferences
   (`TERMINATOR_LLM_API_KEY` by default) before starting Terminator. It's never stored.

To check an endpoint and model outside Terminator:

    java -cp terminator/.generated/classes:salma-hayek/.generated/classes:terminator/lib/jars/gson-2.14.0.jar \
        terminator.llm.OpenAiClient http://localhost:11434/v1 qwen3:8b "Say hello"

## Using it

Press **Ctrl+Cmd+L** on Mac OS, or **Ctrl+Shift+L** elsewhere (also Edit > LLM > Suggest). A panel
appears in the top-right corner of the terminal and the answer streams into it.

- **Ask something**: type your request as a comment at the prompt, then press the shortcut:

      $ # find files over 100M changed in the last week

  `#`, `--` and `//` are recognised straight away (see "Comment markers" in Preferences). Other styles,
  such as `'` in PowerShell, or plain words, are usually recognised by the classifier (below).
- **Don't ask anything**: just press the shortcut. After a failed command you'll get a diagnosis and a
  fix; with an error in a log you're viewing, an explanation of it; otherwise, an explanation of what's on
  screen, such as what the source code in your editor does or what a diff changes.

In the panel:

- **Tab** puts a suggested command on the command line, replacing the request you typed, without running
  it. If that isn't safe (a full-screen program is running, the line has changed, there's other text on
  the line, or the terminal is asking for a password) the command is copied to the clipboard instead and
  the panel says why.
- **Copy**, or the Copy shortcut (Cmd+C on Mac OS, Alt+C elsewhere) when nothing is selected in the terminal, copies just
  the command, or the whole answer if it isn't a command.
- **Esc** closes the panel and cancels the request. Typing anything else closes it too, and the key goes
  to the terminal as usual.

### How the answer is chosen

When you've typed a request, the model answers it directly. Otherwise it takes two steps: a
**classifier** first decides what kind of help you most likely need, then a prompt written for that kind
of help produces the answer. The bundled kinds (scenarios) are:

| Scenario | Chosen when | The answer |
|---|---|---|
| `explicit-request` | you typed a question or instruction on the prompt line | answers it, often with a command |
| `command-error` | a command you just ran failed | the likely cause and a fix |
| `log-error` | an error is visible in a log or output you're viewing | what it means and what to check |
| `explain` | anything else, or when it's unclear | an explanation of what's on screen |

If the classifier step is too slow, set **Classifier model** to a smaller, faster model, or turn on
**Skip classification pass** to use one general prompt instead.

## Your files

Edit > LLM > Show LLM Files opens `~/.terminator/llm/`, which is created with commented starter files the
first time you use suggestions. Changes take effect on the next suggestion.

- **`context.md`**: information about you and your environment that's sent with every request, such as
  what you work on, the operating systems of the machines you use, where data lives, and tools you prefer.
  Anything inside `<!-- -->` comments isn't sent. It isn't redacted, so don't put secrets in it, and keep
  it short, since it uses part of the context budget.
- **`redact-patterns.txt`**: extra Java regular expressions to redact, one per line. A pattern with a group
  named `secret`, such as `customer_id=(?<secret>\d+)`, has only that group replaced.
- **`prompts/`**: your own prompt templates, described next.

## Prompt templates

The bundled templates are in `terminator/lib/llm/prompts/` (in the installation, `lib/llm/prompts/`). A
file in `~/.terminator/llm/prompts/` with the same name replaces the bundled one; copy a template there to
change it.

A template is a Markdown file with optional front matter:

    ---
    scenario: command-error
    description: A command the user ran at the prompt has just failed or printed an error.
    status: Diagnosing the error
    ---
    {{> _system}}

    {{> _about-the-user}}
    ---8<---
    {{> _terminal}}

    ## Task
    ...

- Everything before the `---8<---` line is the **system** message; everything after it is the **user**
  message.
- `{{> name}}` includes another template. Names starting with `_` are conventionally partials.
- `{{name}}` is replaced by a variable: `screen` (the terminal text, redacted), `cursor_line`,
  `screen_kind` (a shell session or a full-screen application), `title` (the terminal title), `os` (the
  local operating system), `request` (what the user asked for or seems to need), `comment_marker`,
  `user_context` (from `context.md`) and `scenario`. The classifier template also gets `scenarios`.
  Unknown variables are left as they are and reported by Preview Request.
- **A template with `scenario:` front matter is a scenario the classifier can choose**, using its
  `description`, so you can add your own, for example one for Kubernetes output or for your lab's pipeline
  logs. Make the description specific. `status` is what the panel shows while answering.
- Replies are expected to follow the convention in `_output-format.md`: a first line of
  `COMMAND: <command>` for a command (which Tab can insert), otherwise plain text.
- `classify.md` is the classifier's prompt. It must ask for the JSON object that Terminator reads:
  `{"scenario", "request", "request_span", "comment_marker", "confidence"}`. `explain.md` is used whenever
  the classifier's reply can't be used, so keep a template with that name. `general.md` is used when the
  classifier is skipped.

Edit > LLM > Preview Request... shows exactly what would be sent with your templates.

## Tuning the prompts

Local models vary a lot in how well they follow instructions. `terminator/tests/llm-eval/` contains saved
terminal screens, each with the scenario and kind of answer it should get. `PromptEval` runs them through
the same pipeline Terminator uses, with your own templates and files, and reports the results as Markdown:

    java -cp terminator/.generated/classes:salma-hayek/.generated/classes:terminator/lib/jars/gson-2.14.0.jar \
        -Dorg.jessies.projectRoot=terminator \
        terminator.llm.PromptEval --endpoint http://localhost:11434/v1 --model qwen3:8b \
        [--classifier-model qwen3:1.7b] [fixture-name...] > eval.md

Each fixture is a text file with header lines, a `---` line, and the terminal text, whose last line is the
cursor line:

    expect-scenario: command-error
    expect-command: yes
    expect-mentions: chmod
    alternate-buffer: false
    title: deploy@web01: /opt/app
    ---
    deploy@web01:/opt/app$ ./deploy.sh production
    -bash: ./deploy.sh: Permission denied
    deploy@web01:/opt/app$ 

`expect-command` is `yes`, `no` or `any`; `expect-insertable` checks whether a request's position was
verified (so Tab could replace it); `expect-mentions` lists words, any one of which the answer must contain.
Add screens from your own work to see how a model copes with what you actually do.

## Troubleshooting

- **"LLM suggestions are turned off"**: turn on "Enable LLM suggestions" in the LLM tab of Preferences.
- **"Not configured"**: set the endpoint and model.
- **"Couldn't connect to the LLM endpoint... Is the server running?"**: check the server and the port.
- **"...isn't on a local or private network"**: the endpoint's host resolves to a public address. Use a
  local model, or turn on "Allow endpoints outside local and private networks" if you really mean it.
- **HTTP 404 mentioning the model**: the model name doesn't match your server's (for Ollama, see
  `ollama list`).
- **Slow**: use a smaller classifier model, skip the classification pass, or lower the context budget.
  Reasoning ("thinking") models are much slower to answer.
- **Poor answers**: try Preview Request to check what the model is given, add to `context.md`, try another
  model, or adjust the templates and check with `PromptEval`.
- **Anything else**: turn on "Log LLM requests and responses" and look at `~/.terminator/llm/debug.log`.
