---
scenario: explicit-request
description: The user has typed a question or instruction on the current line, usually as a comment, and wants it answered.
---
{{> _system}}

{{> _about-the-user}}
---8<---
{{> _terminal}}

## Request

The user typed this request on the cursor line, after the comment marker "{{comment_marker}}":

{{request}}

Answer the request, using what's in the terminal where it's relevant.

{{> _output-format}}
