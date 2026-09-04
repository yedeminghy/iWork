---
name: web-artifacts-builder
description: Create a self-contained interactive HTML artifact when the user needs a dashboard, visualization, form, or multi-section web experience; do not use for a simple prose response or when another document format is the requested deliverable.
license: Apache-2.0; see LICENSE.txt
---

# Web Artifacts Builder

Adaptation notice: the original Apache-2.0 Skill content was modified for iWork's local permission and preview model.

Create one self-contained `.html` file that iWork can expose as an output artifact and open in its sandboxed preview.

## Build requirements

- Write the final file with `workspace.write_text` under an approved writable root. File writes remain subject to the normal approval flow.
- Keep HTML, CSS, and JavaScript in the same file. Do not depend on a CDN, remote font, tracking script, external image, or network request unless the user explicitly requests it and the application policy permits it.
- Prefer semantic HTML, responsive layout, keyboard access, visible focus states, readable contrast, and concise controls.
- Use inline SVG or CSS for decorative graphics. Escape user-provided text before inserting it into markup or script data.
- Do not include credentials, private paths, conversation content not requested for the artifact, or code that uploads local data.
- Avoid excessive centered layouts, purple gradients, uniform rounded cards, and decorative animation that obscures the content.

## Workflow

1. Identify the artifact's audience, decisions, data, and required interactions.
2. Choose the smallest useful layout and interaction model. Use plain JavaScript unless a component abstraction materially improves the result.
3. Generate the complete HTML in one pass and write it to a descriptive filename at the approved workspace root.
4. Return the output artifact to the user. Use iWork's safe preview for a visual check when layout or interaction is material to correctness.
5. If the target already exists, request an explicit overwrite instead of silently replacing it.

The bundled legacy shell helpers are reference material only. They are not execution authority and must not be invoked through an unapproved shell or used to install dependencies automatically.
