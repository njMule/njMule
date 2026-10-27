// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { CSSProperties, ReactNode } from 'react';
import { ICONS } from '../../../njmule-webapp/src/icons';

// The few icons only the site needs, drawn on the apps' grid and stroke: the systems njMule installs on. They show the
// kind of machine, not a vendor's logo.
const SITE_ICONS = {
    DESKTOP: {
        path: 'M4.5 4h15A1.5 1.5 0 0 1 21 5.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 14.5v-9A1.5 1.5 0 0 1 4.5 4zM3 7.5h18M8.5 20h7M12 16v4',
    },
    LAPTOP: {
        path: 'M6 5.5h12a1 1 0 0 1 1 1V15H5V6.5a1 1 0 0 1 1-1zM2.5 15h19v1.5a1.5 1.5 0 0 1-1.5 1.5H4a1.5 1.5 0 0 1-1.5-1.5z',
    },
    TERMINAL: {
        path: 'M4.5 4.5h15A1.5 1.5 0 0 1 21 6v12a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18V6a1.5 1.5 0 0 1 1.5-1.5zM7 9.5l3 2.5-3 2.5M12.5 15H17',
    },
    CONTAINER: { path: 'M12 3.5l8 4.5v8l-8 4.5-8-4.5V8zM4 8l8 4.5L20 8M12 12.5v8' },
    NAS: {
        path: 'M6.5 3.5h11A1.5 1.5 0 0 1 19 5v14a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5zM8.5 7.5h7M8.5 11h7M8.5 16.5h.01M11.5 16.5h.01',
    },
};

const ALL_ICONS = { ...ICONS, ...SITE_ICONS };

export type IconName = keyof typeof ALL_ICONS;

/** One of njMule's own line icons (the web app's src/icons.ts, drawn as the apps draw them). */
export default function NjIcon({
    name,
    size = 24,
    style,
}: {
    name: IconName;
    size?: number;
    style?: CSSProperties;
}): ReactNode {
    const icon: { path: string; kind?: string } = ALL_ICONS[name];
    const filled = icon.kind === 'filled';
    return (
        <svg
            viewBox="0 0 24 24"
            width={size}
            height={size}
            aria-hidden="true"
            focusable="false"
            style={style}
            fill={filled ? 'currentColor' : 'none'}
            stroke={filled ? 'none' : 'currentColor'}
            strokeWidth={1.75}
            strokeLinecap="round"
            strokeLinejoin="round"
        >
            <path d={icon.path} />
        </svg>
    );
}
