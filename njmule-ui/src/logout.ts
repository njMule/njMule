// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { endpoint } from './api/client';

/**
 * Ends the browser's login session: the web interface's logout form, sent with the session's token, forgets it and
 * answers with the login page. The desktop app's window has no session to end.
 */
export function logOut(): void {
    const form = document.createElement('form');
    form.method = 'post';
    form.action = 'logout';
    form.hidden = true;
    const token = document.createElement('input');
    token.type = 'hidden';
    token.name = 'csrf';
    token.value = endpoint().csrf ?? '';
    form.append(token);
    document.body.append(form);
    form.submit();
}
