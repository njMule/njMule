// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { overMask, PASSWORD_MASK } from '../screens/settings/passwords';

describe('a password set, shown as dots', () => {
    let field = '';
    let password = '';

    it('should take what is typed over the dots chosen as the new password', () => {
        givenTheFieldHolds('s3cret!');
        whenTheDotsAreChanged();
        thenThePasswordIs('s3cret!');
    });

    it('should take what is typed after the dots as the whole new password', () => {
        givenTheFieldHolds(`${PASSWORD_MASK}new`);
        whenTheDotsAreChanged();
        thenThePasswordIs('new');
    });

    it('should clear the password when dots are deleted', () => {
        givenTheFieldHolds(PASSWORD_MASK.slice(1));
        whenTheDotsAreChanged();
        thenThePasswordIs('');
    });

    function givenTheFieldHolds(text: string): void {
        field = text;
    }

    function whenTheDotsAreChanged(): void {
        password = overMask(field);
    }

    function thenThePasswordIs(expected: string): void {
        expect(password).toBe(expected);
    }
});
