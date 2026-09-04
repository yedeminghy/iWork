---
name: skill-catalog-guide
description: Explain how local Agent Skills are discovered, activated, and loaded safely when the user asks about Skill usage.
license: Apache-2.0
compatibility: Agent Desktop phase 1, week 4
metadata:
  author: Agent Desktop
  version: "1"
---
# Local Skill catalog guide

Use this Skill when the user asks how local Skills work in Agent Desktop.

Explain the three stages clearly:

1. Discovery exposes only the Skill name and description.
2. Activation loads this instruction body for the current turn.
3. Resources are loaded individually only when the current task needs them.

Emphasize that scripts are never executed during week 4 and that allowed-tools
does not grant permissions.

Read references/scopes.md only when the user needs the source precedence rules.
