// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Pictures the interface for the documentation, the web interface's and the desktop app's pages alike: starts it on
// example data (njmule-http's WebCaptureDemo, as its webCaptureArgs task says), signs in and pictures each place,
// light, dark and in high contrast, with the browser already on this computer (Chrome, Edge or the one
// NJMULE_CAPTURE_BROWSER names): Playwright downloads none. Usage: node scripts/capture-web.mjs <launcher.json> <output
// folder>

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
// Twice the pixels, as the desktop app's pictures: sharp on screens of high density, drawn at half their size.
const CONTEXT = { viewport: VIEWPORT, deviceScaleFactor: 2, locale: 'en-US' };
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

// A part of the page alone, such as a dialog or a pop-up, as the desktop app's pictures of a window were.
async function shotOf(locator, folder, name) {
    await locator.page().waitForTimeout(SETTLE);
    await locator.screenshot({ path: join(folder, `${name}.png`) });
}

// The pictures of the desktop app's pages: the same interface, shown here in the browser on the same example data.
async function desktopPictures(page, folder, info, go) {
    const rows = () => page.locator('.data-table .row');
    const dialog = () => page.locator('[role=dialog], [role=alertdialog]').last();
    const more = async (item) => {
        await page.locator('.top-bar .more').click();
        await page.getByRole('menuitem', { name: item }).click();
    };
    const settingsPage = async (index) => {
        await go('/settings');
        await page.locator('.page-item').nth(index).click();
    };

    for (const [id, route] of [
        ['transfers', '/transfers'],
        ['shared', '/shared'],
        ['messages', '/messages'],
        ['statistics', '/statistics'],
    ]) {
        await step(id, async () => {
            await go(route);
            await shot(page, folder, id);
        });
    }
    await step('transfers-sources', async () => {
        await go('/transfers');
        await rows().first().dblclick();
        await shot(page, folder, 'transfers-sources');
    });
    await step('transfers-details', async () => {
        await go('/transfers');
        await rows().first().click();
        await page.keyboard.press('Alt+Enter');
        await shot(page, folder, 'transfers-details');
        await page.evaluate(() => localStorage.setItem('njmule.transfers.details', 'false'));
        await page.reload();
        await shown(page);
    });
    await step('transfers-ctx-downloads', async () => {
        await go('/transfers');
        await rows().first().click({ button: 'right' });
        await shot(page, folder, 'transfers-ctx-downloads');
    });
    await step('transfers-ctx-downloads-sub-more-sub-sources-shared-with-other-files', async () => {
        await go('/transfers');
        await rows().first().dblclick();
        await rows().first().click({ button: 'right' });
        await page.getByRole('menuitem', { name: /^more$/i }).hover();
        await page
            .getByRole('menuitem', { name: /other files/i })
            .first()
            .hover();
        await shot(page, folder, 'transfers-ctx-downloads-sub-more-sub-sources-shared-with-other-files');
    });
    await step('transfers-dropdown-category', async () => {
        await go('/transfers');
        await page.locator('#category').click();
        await shot(page, folder, 'transfers-dropdown-category');
    });
    await step('dialog-categories', async () => {
        await go('/transfers');
        await page.getByRole('button', { name: 'Categories' }).click();
        await dialog().getByText('Films').first().click();
        await shotOf(dialog(), folder, 'dialog-categories');
    });
    await step('dialog-add-links', async () => {
        await go('/transfers');
        await page.locator('#add-links').click();
        await page
            .locator('#links-text')
            .fill(
                'ed2k://|file|Harbor.Lights.2024.4K.mkv|4939212800|9C4E2A07B1D35F8E61A0C2D47E93B5F1|/\n' +
                    'ed2k://|file|lumenos-4.1-desktop-amd64.iso|6217003008|E2B71C09A4F35D8E10C6A2B94D7F53E1|/',
            );
        await shotOf(dialog(), folder, 'dialog-add-links');
    });
    await step('search-live', async () => {
        await go('/search');
        await page.locator('#search-query').fill('paper boats');
        await page.locator('#search-query').press('Enter');
        await page.waitForTimeout(1500);
        await shot(page, folder, 'search-live');
    });
    await step('search-tab-lumenos-12', async () => {
        await go('/search');
        await page.locator('.search-tabs [role=tab]', { hasText: 'lumenos' }).first().click();
        await shot(page, folder, 'search-tab-lumenos-12');
    });
    await step('search-parameters', async () => {
        await go('/search');
        await page.locator('button[aria-label="Filters"]:visible').first().click();
        await shot(page, folder, 'search-parameters');
    });
    await step('dialog-share-sensitive', async () => {
        await go('/shared');
        await page.getByRole('button', { name: 'Share a folder' }).click();
        await dialog().locator('input').first().fill('D:\\');
        await dialog().getByRole('button', { name: 'Share' }).click();
        await page.waitForTimeout(500);
        await shotOf(dialog(), folder, 'dialog-share-sensitive');
    });
    await step('servers', async () => {
        await go('/network');
        await page.locator('.segments .segment').first().click();
        await shot(page, folder, 'servers');
    });
    await step('servers-panel', async () => {
        await go('/network');
        await page.locator('.segments .segment').first().click();
        await rows().first().click();
        await shot(page, folder, 'servers-panel');
    });
    await step('servers-add', async () => {
        await go('/network');
        await page.locator('.segments .segment').first().click();
        await page.getByRole('button', { name: 'Add servers' }).click();
        await shot(page, folder, 'servers-add');
    });
    await step('kad', async () => {
        await go('/network');
        await page.locator('.segments .segment').nth(1).click();
        await shot(page, folder, 'kad');
    });
    await step('log', async () => {
        await go('/network');
        await page.locator('.segments .segment').nth(2).click();
        await shot(page, folder, 'log');
        await shot(page, folder, 'log-tab-log');
    });
    await step('log-tab-my-info', async () => {
        await go('/network');
        await page.locator('.segments .segment').nth(2).click();
        await page.getByRole('tab', { name: 'My Info' }).click();
        await shot(page, folder, 'log-tab-my-info');
    });
    await step('palette', async () => {
        await go('/transfers');
        await page.keyboard.press('Control+k');
        await shot(page, folder, 'palette');
    });
    await step('shortcuts', async () => {
        await go('/transfers');
        await page.keyboard.press('F1');
        await shotOf(dialog(), folder, 'shortcuts');
    });
    await step('sidebar-folded', async () => {
        await go('/transfers');
        await page.keyboard.press('Control+b');
        await shot(page, folder, 'sidebar-folded');
        await page.keyboard.press('Control+b');
    });
    await step('topbar-limits', async () => {
        await go('/transfers');
        await page.getByRole('button', { name: /njMule speed/ }).click();
        await shot(page, folder, 'topbar-limits');
    });
    await step('topbar-network', async () => {
        await go('/transfers');
        await page.locator('.network-control').click();
        await shot(page, folder, 'topbar-network');
    });
    // A link pasted in the transfers adds its download, which a message at the bottom of the window tells.
    await step('toasts', async () => {
        await go('/transfers');
        await page.evaluate(() => {
            const data = new DataTransfer();
            data.setData(
                'text/plain',
                'ed2k://|file|Northern.Lights.Timelapse.mkv|734003200|7D3A0C5E91B2F4A68C0E1D2B3F4A5C6D|/',
            );
            document.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true }));
        });
        await page.locator('.toast').first().waitFor();
        await shot(page, folder, 'toasts');
    });
    await step('dialog-about', async () => {
        await go('/transfers');
        await more('About njMule');
        await shotOf(dialog(), folder, 'dialog-about');
    });
    await step('dialog-import', async () => {
        await go('/transfers');
        await more(/Import from eMule/);
        await shotOf(dialog(), folder, 'dialog-import');
        await dialog().getByRole('button', { name: 'Check' }).click();
        await page.waitForTimeout(1500);
        await shotOf(dialog(), folder, 'dialog-import-report');
    });
    await step('dialog-wizard', async () => {
        await go('/transfers');
        await more('Set up njMule');
        await shotOf(dialog(), folder, 'dialog-wizard-1');
        for (const n of [2, 3, 4, 5]) {
            await dialog().getByRole('button', { name: 'Next' }).click();
            await shotOf(dialog(), folder, `dialog-wizard-${n}`);
            if (n === 2) {
                await shot(page, folder, 'dialog-wizard-2-over');
            }
        }
    });
    for (let index = 0; index < 13; index++) {
        const id = `settings-page-${String(index + 1).padStart(2, '0')}`;
        await step(id, async () => {
            await settingsPage(index);
            await shot(page, folder, id);
        });
    }
    await step('dialog-server-list-addresses', async () => {
        await settingsPage(3);
        await page.getByRole('button', { name: 'List...' }).click();
        await shotOf(dialog(), folder, 'dialog-server-list-addresses');
    });
    await step('settings-changed-only', async () => {
        await settingsPage(1);
        await page.getByRole('button', { name: /changed only/i }).click();
        await shot(page, folder, 'settings-changed-only');
        await page.getByRole('button', { name: /changed only/i }).click();
    });
    await step('settings-invalid-field', async () => {
        await settingsPage(1);
        await page.locator('[id="option-net.tcpPort"]').fill('99999');
        await shot(page, folder, 'settings-invalid-field');
        await page.reload();
        await shown(page);
    });
    // Last: the port typed stays pending until njMule reconnects.
    await step('settings-pending', async () => {
        await settingsPage(1);
        const port = page.locator('[id="option-net.tcpPort"]');
        await port.fill('4663');
        await port.press('Enter');
        await shot(page, folder, 'settings-pending');
        await page.getByRole('button', { name: 'Discard' }).click();
    });
    // The small windows of the notification area's icon, as their page shows them in a browser.
    for (const [id, view] of [
        ['tray-menu', 'tray'],
        ['minimule', 'minimule'],
    ]) {
        await step(id, async () => {
            await page.goto(`${info.url}?view=${view}`);
            const shown = page.locator('#app > *').first();
            await shown.waitFor();
            await page.waitForTimeout(2500);
            await shotOf(shown, folder, id);
        });
    }
}

// One step: a failure is noted and the next step goes on, from a fresh place.
async function step(name, run) {
    try {
        await run();
    } catch (e) {
        missed.push(`${name}: ${e.message.slice(0, 800)}`);
    }
}

// The app is shown once its top bar is; its stream of events stays open, so the network is never idle.
async function shown(page) {
    await page.locator('.top-bar').waitFor({ timeout: 60_000 });
}

async function signIn(page, url, password) {
    await page.goto(url);
    await page.locator('input[name="password"]').fill(password);
    await page.locator('input[name="password"]').press('Enter');
    await shown(page);
}

async function scheme(browser, info, colorScheme) {
    const folder = join(output, colorScheme);
    mkdirSync(folder, { recursive: true });
    const context = await browser.newContext({ ...CONTEXT, colorScheme });
    // The theme follows the scheme of the system; the list alone, without the details under it.
    await context.addInitScript(() => {
        localStorage.removeItem('njmule.theme');
        localStorage.setItem('njmule.transfers.details', 'false');
    });
    const page = await context.newPage();
    // Loaded again each time, so that nothing a step left open covers the next one.
    const go = async (route) => {
        await page.goto(`${info.url}#${route}`);
        await page.reload();
        await shown(page);
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
        await page.locator('.data-table .row').first().dblclick();
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
            await page.locator('.segments .segment').nth(index).click();
            await shot(page, folder, name);
        });
    }
    await step('web-add-links', async () => {
        await go('/transfers');
        // The segment chosen last is remembered: back to the downloads, where the button is.
        await page.locator('.segments .segment').first().click();
        await page
            .getByRole('button', { name: /add links/i })
            .filter({ visible: true })
            .first()
            .click();
        await page
            .locator('#links-text')
            .fill(
                'ed2k://|file|Harbor.Lights.2024.4K.mkv|4939212800|9C4E2A07B1D35F8E61A0C2D47E93B5F1|/\n' +
                    'ed2k://|file|lumenos-4.1-desktop-amd64.iso|6217003008|E2B71C09A4F35D8E10C6A2B94D7F53E1|/',
            );
        await shot(page, folder, 'web-add-links');
    });
    await step('web-search', async () => {
        await go('/search');
        await shot(page, folder, 'web-search-empty');
        const query = page.locator('#search-query');
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
    for (const [index, part] of ['servers', 'kad', 'log'].entries()) {
        await step(`web-network-${part}`, async () => {
            await go('/network');
            await page.locator('.segments .segment').nth(index).click();
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
    await desktopPictures(page, folder, info, go);
    await context.close();

    // A guest sees, and changes, nothing but what it may look at.
    const guest = await browser.newContext({ ...CONTEXT, colorScheme });
    const guestPage = await guest.newPage();
    await step('web-guest', async () => {
        await signIn(guestPage, info.url, info.guest);
        await guestPage.goto(`${info.url}#/transfers`);
        await shown(guestPage);
        await shot(guestPage, folder, 'web-guest');
    });
    await guest.close();
}

// The transfers under a dark high contrast theme of the system, which the interface follows.
async function contrast(browser, info) {
    const folder = join(output, 'contrast');
    mkdirSync(folder, { recursive: true });
    const context = await browser.newContext({ ...CONTEXT, colorScheme: 'dark', forcedColors: 'active' });
    const page = await context.newPage();
    await step('transfers (contrast)', async () => {
        await signIn(page, info.url, info.password);
        await page.goto(`${info.url}#/transfers`);
        await shown(page);
        await shot(page, folder, 'transfers');
    });
    await context.close();
}

rmSync(output, { recursive: true, force: true });
const { demo, info } = await startDemo();
let browser;
try {
    browser = await openBrowser();
    for (const colorScheme of ['light', 'dark']) {
        await scheme(browser, info, colorScheme);
    }
    await contrast(browser, info);
} finally {
    await browser?.close();
    demo.stdin.end();
    await new Promise((resolve) => demo.on('exit', resolve));
}
for (const m of missed) {
    console.warn(`not pictured: ${m}`);
}
console.log(`Pictured the web interface in ${output}`);
