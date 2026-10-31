// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { createApp } from 'vue';
import { language, windowLanguage } from '../i18n';
import { readTheme } from '../theme';
import { whileHidden } from '../toasts';
import MiniMuleView from './MiniMuleView.vue';
import TrayMenuView from './TrayMenuView.vue';
import { popupHost } from './host';

const VIEWS: Record<string, typeof TrayMenuView | typeof MiniMuleView> = {
    tray: TrayMenuView,
    minimule: MiniMuleView,
};

/** Shows {@code view}, the menu of the notification area's icon or MiniMule, in this window. */
export function mountPopup(view: string): void {
    const shown = VIEWS[view];
    if (shown === undefined) {
        return;
    }
    // The look and the language chosen in the main window, kept by it, come here too: the language as the page loads.
    window.addEventListener('storage', () => {
        readTheme();
        if (windowLanguage(navigator.languages) !== language.value) {
            window.location.reload();
        }
    });
    const host = popupHost();
    // A limit that cannot be set says so where the user looks: the main window hides.
    whileHidden((text) => host.notify(text));
    createApp(shown, { host }).mount('#app');
}
