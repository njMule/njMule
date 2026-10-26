// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { CSSProperties, ReactNode } from 'react';
import { ICONS } from '../../../njmule-webapp/src/icons';

export type IconName = keyof typeof ICONS;

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
    const icon: { path: string; kind?: string } = ICONS[name];
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
