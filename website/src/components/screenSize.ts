// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { usePluginData } from '@docusaurus/useGlobalData';
import type { ScreenSizes } from '@site/plugins/screens';

// The build pictures njMule with twice the pixels it draws them with, sharp on screens of high density.
const DENSITY = 2;

/** The size to draw a screenshot at, in CSS pixels, or nothing when the build has not made it. */
export function useScreenSize(set: string, id: string): { width?: number; height?: number } {
    const sizes = usePluginData('njmule-screens') as ScreenSizes | undefined;
    const size = sizes?.[`${set}/${id}`];
    return size ? { width: Math.round(size[0] / DENSITY), height: Math.round(size[1] / DENSITY) } : {};
}
