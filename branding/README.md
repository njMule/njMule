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
| `njmule-avatar.svg`, `generated/njmule-avatar-512.png` | the icon on a full square with a smaller mark, for circular avatars (GitHub organisation, forums) |
| `generated/njmule-social-preview.png` | 1280×640 repository social preview (GitHub Settings → Social preview) |

Brand colours: a deep blue with warm orange accents. The user interface itself keeps sober pastel
tones compatible with its themes.

| Role | Colour | Note |
|---|---|---|
| Brand (square) | `#24427A` | deep blue, also used as the badge label colour |
| Mark | `#F4F6FB` | off-white strokes, 9.1:1 against the brand blue |
| Accent | `#F5A65B` | warm orange for forelock, eyes and nostrils, 4.9:1 against the brand blue; dark badge text on it reads at 6.3:1 |

Regenerate after editing the master (the desktop window takes its icons from `generated/`, see
`njmule-desktop/src-tauri/tauri.conf.json`):

```bash
./gradlew -q iconTools
java -Djava.awt.headless=true -cp build/tools/jsvg-2.2.0.jar tools/IconGen.java branding/njmule-icon.svg branding/generated
```

`IconGen` renders the SVG with [JSVG](https://github.com/weisJ/jsvg) (MIT), a build-time tool only.

The social preview is rendered from the 512 px icon:

```bash
java -Djava.awt.headless=true tools/SocialPreview.java branding/generated/njmule-512.png branding/generated/njmule-social-preview.png
```
