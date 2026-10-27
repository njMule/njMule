// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactNode } from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import { useColorMode } from '@docusaurus/theme-common';
import ThemedImage from '@theme/ThemedImage';
import styles from './Screenshot.module.css';

export interface ScreenshotProps {
    /** The picture's name, as the build makes it (scripts/prepare.mjs copies the pictures the pages name). */
    id: string;
    alt: string;
    caption?: ReactNode;
    /** `contrast` for the pictures in Windows' high contrast, which have no light or dark version. */
    set?: 'themed' | 'contrast';
    /** How wide to draw it, in pixels at most: dialogs are smaller than windows. */
    width?: number;
}

/**
 * A picture of njMule taken by the build on example data, in the light or dark theme as the page is read; a click
 * opens it at full size.
 */
export default function Screenshot({ id, alt, caption, set = 'themed', width }: ScreenshotProps): ReactNode {
    const light = useBaseUrl(`/img/screens/${set === 'contrast' ? 'contrast' : 'light'}/${id}.png`);
    const dark = useBaseUrl(`/img/screens/${set === 'contrast' ? 'contrast' : 'dark'}/${id}.png`);
    const { colorMode } = useColorMode();
    return (
        <figure className={styles.figure} style={width ? { maxWidth: width } : undefined}>
            <a
                className={styles.frame}
                href={colorMode === 'dark' ? dark : light}
                target="_blank"
                rel="noreferrer"
                title="Open at full size"
            >
                <ThemedImage alt={alt} sources={{ light, dark }} loading="lazy" decoding="async" />
            </a>
            {caption ? <figcaption className={styles.caption}>{caption}</figcaption> : null}
        </figure>
    );
}
