<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# njMule branding

Original artwork of the njMule project (no eMule asset is used), licensed GPL-3.0-or-later.

| File | Content |
|---|---|
| `njmule-icon.svg` | master application icon: a stylised mule head in deep indigo strokes on a pastel periwinkle rounded square |
| `generated/` | PNG 16–1024 px, `njmule.ico` (Windows), `njmule.icns` (macOS) rendered from the master |

Palette: sober pastel tones, see `docs/PLAN.md` §6.7.

| Role | Colour | Note |
|---|---|---|
| Brand (square) | `#A9B8EA` | pastel periwinkle, stands out on light and dark taskbars |
| Ink (mark) | `#26304F` | deep indigo, 6.6:1 against the brand colour (WCAG AA) |

Regenerate after editing the master (the GUI copies the 16–512 px PNGs into
`njmule-gui/src/main/resources/org/njmule/gui/icons/`):

```bash
java -Djava.awt.headless=true tools/IconGen.java branding/njmule-icon.svg branding/generated
```

`IconGen` supports only the SVG subset used here (rect, circle, paths with M/L/C/Q/Z, solid fills,
round strokes); keep the master within it.
