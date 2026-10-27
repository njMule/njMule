// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import type { LoadContext, Plugin } from '@docusaurus/types';
import { imageSizeFromFile } from 'image-size/fromFile';

/** Width and height in pixels of each screenshot, by `<set>/<id>`. */
export type ScreenSizes = Record<string, [number, number]>;

/**
 * Tells the pages the size of each screenshot the build made (scripts/prepare.mjs), so that they draw it at its own
 * size: the pictures have twice the pixels they are drawn with.
 */
export default function screens(context: LoadContext): Plugin {
    const folder = join(context.siteDir, 'build', 'generated', 'static', 'img', 'screens');
    return {
        name: 'njmule-screens',
        async loadContent() {
            const sizes: ScreenSizes = {};
            if (!existsSync(folder)) {
                return sizes;
            }
            for (const set of readdirSync(folder, { withFileTypes: true }).filter((d) => d.isDirectory())) {
                for (const file of readdirSync(join(folder, set.name)).filter((f) => f.endsWith('.png'))) {
                    const { width, height } = await imageSizeFromFile(join(folder, set.name, file));
                    sizes[`${set.name}/${file.slice(0, -'.png'.length)}`] = [width, height];
                }
            }
            return sizes;
        },
        async contentLoaded({ content, actions }) {
            actions.setGlobalData(content);
        },
        getPathsToWatch() {
            return [join(folder, '**', '*.png')];
        },
    };
}
