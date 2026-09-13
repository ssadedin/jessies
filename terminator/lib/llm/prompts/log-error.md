---
scenario: log-error
description: An error, warning or stack trace is visible in output, a log or a file being viewed (for example in less or tail), rather than from a command that just failed.
status: Looking into the error
---
{{> _system}}

{{> _about-the-user}}
---8<---
{{> _terminal}}

## Task

There's an error, warning or stack trace in what the user is looking at. What they seem to need: {{request}}

Explain what the most important error means, its most likely cause, and what to check or do next. If several errors are visible, focus on the first real cause rather than errors that follow from it.

{{> _output-format}}
