<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
# njMule branding

Original artwork of the njMule project (no eMule asset is used), licensed GPL-3.0-or-later.

| File | Content |
|---|---|
| `njmule-icon.svg` | master application icon: a stylised mule head in off-white strokes with orange accents on a deep blue rounded square |
| `generated/` | PNG 16–1024 px, `njmule.ico` (Windows), `njmule.icns` (macOS) rendered from the master |

Brand colours: a deep blue with warm orange accents. The user interface itself keeps sober pastel
tones compatible with the AtlantaFX themes.

| Role | Colour | Note |
|---|---|---|
| Brand (square) | `#24427A` | deep blue, also used as the badge label colour |
| Mark | `#F4F6FB` | off-white strokes, 9.1:1 against the brand blue |
| Accent | `#F5A65B` | warm orange for forelock, eyes and nostrils, 4.9:1 against the brand blue; dark badge text on it reads at 6.3:1 |

Regenerate after editing the master (the GUI copies the 16–512 px PNGs into
`njmule-gui/src/main/resources/org/njmule/gui/icons/`):

```bash
java -Djava.awt.headless=true tools/IconGen.java branding/njmule-icon.svg branding/generated
```

`IconGen` supports only the SVG subset used here (rect, circle, paths with M/L/C/Q/Z, solid fills,
round strokes); keep the master within it.
