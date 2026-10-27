// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Pictures the web interface for the documentation: starts it on the capture tour's example data (njmule-gui's
// WebCaptureDemo, as njmule-gui's webCaptureArgs task says), signs in and pictures each place, light and dark, with
// the browser already on this computer (Chrome, Edge or the one NJMULE_CAPTURE_BROWSER names): Playwright downloads
// none. Usage: node scripts/capture-web.mjs <launcher.json> <output folder>

import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { chromium } from 'playwright-core';

const [launcherFile, output] = process.argv.slice(2);
if (!launcherFile || !output) {
    console.error('Usage: node scripts/capture-web.mjs <launcher.json> <output folder>');
    process.exit(2);
}
const launcher = JSON.parse(readFileSync(launcherFile, 'utf8'));
const VIEWPORT = { width: 1280, height: 800 };
const SETTLE = 900;
const missed = [];

// The web interface on example data, until its standard input closes.
async function startDemo() {
    const demo = spawn(launcher.java, [`@${launcher.args}`], { stdio: ['pipe', 'pipe', 'inherit'] });
    const lines = createInterface({ input: demo.stdout });
    const ready = new Promise((resolve, reject) => {
        lines.on('line', (line) => {
            if (line.startsWith('{')) {
                resolve(JSON.parse(line));
            }
        });
        demo.on('exit', (code) => reject(new Error(`the web interface stopped (${code})`)));
    });
    const timeout = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('the web interface did not start')), 120_000),
    );
    return { demo, info: await Promise.race([ready, timeout]) };
}

async function openBrowser() {
    const path = process.env.NJMULE_CAPTURE_BROWSER;
    if (path) {
        return chromium.launch({ executablePath: path });
    }
    for (const channel of ['chrome', 'msedge', 'chromium']) {
        try {
            return await chromium.launch({ channel });
        } catch {
            // The next one.
        }
    }
    throw new Error('no Chrome or Edge found: set NJMULE_CAPTURE_BROWSER to a Chromium-based browser');
}

async function shot(page, folder, name) {
    await page.waitForTimeout(SETTLE);
    await page.screenshot({ path: join(folder, `${name}.png`) });
}

// One step: a failure is noted and the next step goes on, from a fresh place.
async function step(name, run) {
    try {
        await run();
    } catch (e) {
        missed.push(`${name}: ${e.message.slice(0, 800)}`);
    }
}

async function signIn(page, url, password) {
    await page.goto(url);
    await page.locator('input[name="password"]').fill(password);
    await Promise.all([page.waitForURL(/#\//), page.locator('input[name="password"]').press('Enter')]);
    await page.waitForLoadState('networkidle');
}

async function scheme(browser, info, colorScheme) {
    const folder = join(output, colorScheme);
    mkdirSync(folder, { recursive: true });
    const context = await browser.newContext({ viewport: VIEWPORT, colorScheme, locale: 'en-US' });
    // The set-up asked once in a browser is already done; the theme follows the scheme of the system.
    await context.addInitScript(() => {
        localStorage.setItem('njmule.setup', 'done');
        localStorage.removeItem('njmule.theme');
        // The list alone, without the details under it (shown until a viewer hides them).
        localStorage.setItem('njmule.details.shown', 'false');
    });
    const page = await context.newPage();
    const go = async (route) => {
        await page.goto(`${info.url}#${route}`);
        await page.waitForLoadState('networkidle');
        await page.keyboard.press('Escape');
    };

    await step('web-login', async () => {
        await page.goto(info.url);
        await page.locator('input[name="password"]').waitFor();
        await shot(page, folder, 'web-login');
    });
    await signIn(page, info.url, info.password);

    await step('web-transfers', async () => {
        await go('/transfers');
        await shot(page, folder, 'web-transfers');
    });
    await step('web-transfers-sources', async () => {
        await go('/transfers');
        await page.locator('.el-table__body tr.el-table__row').first().dblclick();
        await shot(page, folder, 'web-transfers-sources');
    });
    for (const [index, name] of [
        [1, 'web-transfers-uploads'],
        [2, 'web-transfers-queue'],
        [3, 'web-transfers-banned'],
        [4, 'web-transfers-known'],
    ]) {
        await step(name, async () => {
            await go('/transfers');
            await page.locator('.shelves .el-segmented__item').nth(index).click();
            await shot(page, folder, name);
        });
    }
    await step('web-add-links', async () => {
        await go('/transfers');
        // The segment chosen last is remembered: back to the downloads, where the button is.
        await page.locator('.shelves .el-segmented__item').first().click();
        await page
            .getByRole('button', { name: /add links/i })
            .filter({ visible: true })
            .first()
            .click();
        await page
            .locator('#nj-links')
            .fill(
                'ed2k://|file|Harbor.Lights.2024.4K.mkv|4939212800|9C4E2A07B1D35F8E61A0C2D47E93B5F1|/\n' +
                    'ed2k://|file|lumenos-4.1-desktop-amd64.iso|6217003008|E2B71C09A4F35D8E10C6A2B94D7F53E1|/',
            );
        await shot(page, folder, 'web-add-links');
    });
    await step('web-search', async () => {
        await go('/search');
        await shot(page, folder, 'web-search-empty');
        const query = page.locator('form input[type="text"], form input:not([type])').first();
        await query.fill('paper boats');
        await query.press('Enter');
        await page.waitForTimeout(1500);
        await shot(page, folder, 'web-search');
    });
    await step('web-shared', async () => {
        await go('/shared');
        await shot(page, folder, 'web-shared');
    });
    await step('web-messages', async () => {
        await go('/messages');
        await shot(page, folder, 'web-messages');
    });
    for (const part of ['servers', 'kad', 'log']) {
        await step(`web-network-${part}`, async () => {
            await go(`/network/${part}`);
            await shot(page, folder, `web-network-${part}`);
        });
    }
    await step('web-statistics', async () => {
        await go('/statistics');
        await shot(page, folder, 'web-statistics');
    });
    await step('web-settings', async () => {
        await go('/settings');
        await shot(page, folder, 'web-settings');
    });
    await step('web-palette', async () => {
        await go('/transfers');
        await page.keyboard.press('Control+k');
        await shot(page, folder, 'web-palette');
        await page.keyboard.press('Escape');
    });
    await step('web-phone', async () => {
        await page.setViewportSize({ width: 390, height: 844 });
        await go('/transfers');
        await shot(page, folder, 'web-phone');
        await page.setViewportSize(VIEWPORT);
    });
    await context.close();

    // A guest sees, and changes, nothing but what it may look at.
    const guest = await browser.newContext({ viewport: VIEWPORT, colorScheme, locale: 'en-US' });
    const guestPage = await guest.newPage();
    await step('web-guest', async () => {
        await signIn(guestPage, info.url, info.guest);
        await guestPage.goto(`${info.url}#/transfers`);
        await guestPage.waitForLoadState('networkidle');
        await shot(guestPage, folder, 'web-guest');
    });
    await guest.close();
}

rmSync(output, { recursive: true, force: true });
const { demo, info } = await startDemo();
let browser;
try {
    browser = await openBrowser();
    for (const colorScheme of ['light', 'dark']) {
        await scheme(browser, info, colorScheme);
    }
} finally {
    await browser?.close();
    demo.stdin.end();
    await new Promise((resolve) => demo.on('exit', resolve));
}
for (const m of missed) {
    console.warn(`not pictured: ${m}`);
}
console.log(`Pictured the web interface in ${output}`);
