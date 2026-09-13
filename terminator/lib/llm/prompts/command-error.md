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

A command the user ran at the prompt has just failed. What they seem to need: {{request}}

Look at the actual error message. Say in a sentence or two what most likely went wrong, then give the fix. If the fix is a single command, give it in the COMMAND form.

{{> _output-format}}
