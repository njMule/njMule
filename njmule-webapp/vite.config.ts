// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vitest/config';

// Relative paths everywhere: the app works under any path a reverse proxy gives it.
export default defineConfig({
    base: './',
    plugins: [vue()],
    build: {
        outDir: 'build/dist',
        emptyOutDir: true,
        // The licenses of every bundled package, shipped beside the app.
        license: { fileName: 'THIRD-PARTY.md' },
        chunkSizeWarningLimit: 2048,
    },
    test: {
        environment: 'happy-dom',
    },
});
