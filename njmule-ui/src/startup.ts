// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { App } from 'vue';
import { known, t } from './i18n';

// The window before its interface shows: it says that njMule is starting while the engine starts, and why the
// interface cannot show when it cannot, rather than staying empty.

/** How long a start lasts before the window says so: a quick one shows nothing in between. */
export const STARTING_DELAY_MS = 500;

const FAILED = 'nj.startup.failed';
// In English when not even the English texts could be read, which may be the very failure to tell.
const FAILED_IN_ENGLISH =
    "njMule could not start. Try again, and if it happens again, report it with this message and njMule's log.";

function element(tag: string, className: string, text?: string): HTMLElement {
    const made = document.createElement(tag);
    made.className = className;
    if (text !== undefined) {
        made.textContent = text;
    }
    return made;
}

/** Says in {@code place} that njMule is starting, once the start lasts; returns what stops saying it. */
export function sayStarting(place: HTMLElement): () => void {
    const timer = window.setTimeout(() => {
        const screen = element('div', 'nj-startup');
        screen.setAttribute('role', 'status');
        screen.append(element('div', 'nj-startup-spinner'), element('p', 'nj-startup-text', t('nj.startup.starting')));
        place.replaceChildren(screen);
    }, STARTING_DELAY_MS);
    return () => {
        window.clearTimeout(timer);
        place.replaceChildren();
    };
}

/**
 * Mounts {@code app} in {@code place}, or throws what its root failed with while setting itself up or drawing, which
 * would leave the window empty; a part of it that fails leaves the rest working, as it always does.
 */
export function mountOrFail(app: App, place: HTMLElement): void {
    let mounted = false;
    const failures: unknown[] = [];
    app.config.errorHandler = (error, instance) => {
        if (!mounted && (instance === null || instance.$parent === null)) {
            failures.push(error);
        }
        console.error(error);
    };
    app.mount(place);
    mounted = true;
    if (failures.length > 0) {
        app.unmount();
        throw failures[0];
    }
}

/** Shows in {@code place} that njMule could not start, its engine or its interface, and why. */
export function sayFailed(place: HTMLElement, failure: unknown): void {
    const screen = element('div', 'nj-startup');
    screen.setAttribute('role', 'alert');
    const reason = failure instanceof Error ? failure.message : String(failure);
    screen.append(
        element('p', 'nj-startup-text', known(FAILED) ? t(FAILED) : FAILED_IN_ENGLISH),
        element('p', 'nj-startup-reason', reason),
    );
    place.replaceChildren(screen);
}
