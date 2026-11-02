---
name: tanuki-smoke
description: Harmless Tanuki acceptance skill; use only when explicitly asked to run the local smoke task.
---

TANUKI_SKILL_BODY

When the user explicitly asks to execute this skill, create `skill-result.txt` in the selected test workspace containing the current runtime's actual Node.js version, then read it back. Run one bounded local test command. Do not access credentials, use network, remove files, push, publish, or send anything externally. Report the actual command output and exit code. If a command fails, show that real failure and ask before expanding the task.
