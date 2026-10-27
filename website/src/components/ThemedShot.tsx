// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactNode } from 'react';
import useBaseUrl from '@docusaurus/useBaseUrl';
import ThemedImage from '@theme/ThemedImage';

/**
 * A screenshot of njMule in the theme the page is read in, without a frame, for layouts of their own (the home
 * page). scripts/prepare.mjs copies the pictures named this way, as for Screenshot.
 */
export default function ThemedShot({
    id,
    alt,
    className,
    eager = false,
}: {
    id: string;
    alt: string;
    className?: string;
    eager?: boolean;
}): ReactNode {
    const light = useBaseUrl(`/img/screens/light/${id}.png`);
    const dark = useBaseUrl(`/img/screens/dark/${id}.png`);
    return (
        <ThemedImage
            className={className}
            alt={alt}
            sources={{ light, dark }}
            loading={eager ? 'eager' : 'lazy'}
            decoding="async"
        />
    );
}
