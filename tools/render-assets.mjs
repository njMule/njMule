// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Renders branding icons (PNG 16..1024, ICO, ICNS), country flags (32x24 PNG) and the 1280x640
// repository social preview with the locally installed Chromium/Edge via website's playwright-core.
//
// Usage:
//   node tools/render-assets.mjs icons branding/njmule-icon.svg branding/generated
//   node tools/render-assets.mjs flags <flag-icons>/flags/4x3 njmule-ui/flags
//   node tools/render-assets.mjs social-preview branding/generated/njmule-512.png branding/generated/njmule-social-preview.png

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const require = createRequire(join(root, 'website', 'package.json'));
const { chromium } = require('playwright-core');

const PNG_SIZES = [16, 22, 24, 32, 48, 64, 72, 128, 256, 512, 1024];
const ICO_SIZES = [16, 24, 32, 48, 64, 128, 256];
const ICNS_TYPES = [
    ['icp4', 16],
    ['icp5', 32],
    ['icp6', 64],
    ['ic07', 128],
    ['ic08', 256],
    ['ic09', 512],
    ['ic10', 1024],
];

async function launchBrowser() {
    const custom = process.env.NJMULE_CAPTURE_BROWSER;
    if (custom) {
        return chromium.launch({ executablePath: custom, headless: true });
    }
    for (const channel of ['chrome', 'msedge']) {
        try {
            return await chromium.launch({ channel, headless: true });
        } catch {
            // Try the next installed browser channel.
        }
    }
    throw new Error('No Chromium-based browser found; set NJMULE_CAPTURE_BROWSER');
}

function packIco(pngs) {
    const headerSize = 6 + 16 * ICO_SIZES.length;
    const total = headerSize + ICO_SIZES.reduce((sum, s) => sum + pngs.get(s).length, 0);
    const buf = Buffer.alloc(total);
    buf.writeUInt16LE(0, 0);
    buf.writeUInt16LE(1, 2);
    buf.writeUInt16LE(ICO_SIZES.length, 4);
    let offset = headerSize;
    ICO_SIZES.forEach((size, idx) => {
        const png = pngs.get(size);
        const pos = 6 + idx * 16;
        buf[pos] = size >= 256 ? 0 : size;
        buf[pos + 1] = size >= 256 ? 0 : size;
        buf[pos + 2] = 0;
        buf[pos + 3] = 0;
        buf.writeUInt16LE(1, pos + 4);
        buf.writeUInt16LE(32, pos + 6);
        buf.writeUInt32LE(png.length, pos + 8);
        buf.writeUInt32LE(offset, pos + 12);
        png.copy(buf, offset);
        offset += png.length;
    });
    return buf;
}

function packIcns(pngs) {
    const total = 8 + ICNS_TYPES.reduce((sum, [, s]) => sum + 8 + pngs.get(s).length, 0);
    const buf = Buffer.alloc(total);
    buf.write('icns', 0, 'ascii');
    buf.writeUInt32BE(total, 4);
    let offset = 8;
    for (const [tag, size] of ICNS_TYPES) {
        const png = pngs.get(size);
        buf.write(tag, offset, 'ascii');
        buf.writeUInt32BE(8 + png.length, offset + 4);
        png.copy(buf, offset + 8);
        offset += 8 + png.length;
    }
    return buf;
}

async function renderSvgToPng(page, svgMarkup, width, height) {
    await page.setViewportSize({ width, height });
    await page.setContent(
        `<!doctype html><style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}svg{width:${width}px;height:${height}px;display:block}</style>${svgMarkup}`,
    );
    return page.screenshot({ type: 'png', omitBackground: true });
}

async function renderIcons(svgPath, outDir) {
    const svgMarkup = readFileSync(svgPath, 'utf8');
    mkdirSync(outDir, { recursive: true });
    const browser = await launchBrowser();
    try {
        const page = await browser.newPage();
        const pngs = new Map();
        for (const size of PNG_SIZES) {
            const png = await renderSvgToPng(page, svgMarkup, size, size);
            pngs.set(size, png);
            writeFileSync(join(outDir, `njmule-${size}.png`), png);
        }
        writeFileSync(join(outDir, 'njmule.ico'), packIco(pngs));
        writeFileSync(join(outDir, 'njmule.icns'), packIcns(pngs));
        console.log(`Icons written to ${resolve(outDir)}`);
    } finally {
        await browser.close();
    }
}

async function renderFlags(srcDir, outDir) {
    mkdirSync(outDir, { recursive: true });
    const browser = await launchBrowser();
    try {
        const page = await browser.newPage();
        let written = 0;
        for (const name of readdirSync(srcDir).sort()) {
            if (!/^[a-z]{2}\.svg$/.test(name)) {
                continue;
            }
            const svgMarkup = readFileSync(join(srcDir, name), 'utf8');
            const png = await renderSvgToPng(page, svgMarkup, 32, 24);
            writeFileSync(join(outDir, `${name.slice(0, 2)}.png`), png);
            written++;
        }
        console.log(`${written} flags written to ${resolve(outDir)}`);
    } finally {
        await browser.close();
    }
}

async function renderSocialPreview(iconPath, outPath) {
    if (!existsSync(iconPath)) {
        throw new Error(`Missing icon: ${iconPath}`);
    }
    const b64 = readFileSync(iconPath).toString('base64');
    const browser = await launchBrowser();
    try {
        const page = await browser.newPage({ viewport: { width: 1280, height: 640 } });
        await page.setContent(`<!doctype html>
<style>
  html, body { margin: 0; width: 1280px; height: 640px; background: #F4F6FB; display: flex; align-items: center; font-family: "Segoe UI", system-ui, -apple-system, sans-serif; }
  .wrap { display: flex; align-items: center; gap: 80px; padding-left: 120px; }
  img { width: 360px; height: 360px; }
  h1 { margin: 0; font-size: 132px; font-weight: 700; color: #24427A; line-height: 1; }
  .bar { width: 120px; height: 12px; border-radius: 6px; background: #F5A65B; margin: 28px 0 24px 6px; }
  .sub { margin: 0 0 12px 4px; font-size: 44px; color: #4C566A; }
  .meta { margin: 0 0 0 4px; font-size: 30px; color: #6B7385; }
</style>
<div class="wrap">
  <img src="data:image/png;base64,${b64}" alt="">
  <div>
    <h1>njMule</h1>
    <div class="bar"></div>
    <p class="sub">New Jack Mule</p>
    <p class="meta">eD2K and Kad client &nbsp;·&nbsp; GPL-3.0</p>
  </div>
</div>`);
        await page.screenshot({ path: outPath, type: 'png' });
        console.log(`Social preview written to ${resolve(outPath)}`);
    } finally {
        await browser.close();
    }
}

const [cmd, arg1, arg2] = process.argv.slice(2);
if (cmd === 'icons' && arg1 && arg2) {
    await renderIcons(arg1, arg2);
} else if (cmd === 'flags' && arg1 && arg2) {
    await renderFlags(arg1, arg2);
} else if (cmd === 'social-preview' && arg1 && arg2) {
    await renderSocialPreview(arg1, arg2);
} else {
    console.error('Usage: node tools/render-assets.mjs <icons|flags|social-preview> <input> <output>');
    process.exit(2);
}
