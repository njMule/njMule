// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import MDXComponents from '@theme-original/MDXComponents';
import Screenshot from '@site/src/components/Screenshot';
import NjIcon from '@site/src/components/NjIcon';
import { DockerImage, Downloads, Version } from '@site/src/components/Release';

// Components every page may use without importing them.
export default {
    ...MDXComponents,
    Screenshot,
    NjIcon,
    Version,
    Downloads,
    DockerImage,
};
