// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { isSearchExpression } from '../search-syntax';

describe('the syntax of the search box', () => {
    let valid = false;

    it.each([
        'debian netinst',
        '"night of the living dead"',
        'debian -netinst',
        'linux AND (debian OR ubuntu) NOT arch',
        'film @size>700m @type=video',
        'song @length>=3:30 @bitrate>=192',
        'ed2k://|file|My%20File.iso|1048576|0123456789ABCDEF0123456789ABCDEF|/',
    ])('should take %s', (expression) => {
        whenTheExpressionIsChecked(expression);
        thenItIsValid(true);
    });

    it.each([
        '',
        '"unterminated',
        '(debian',
        'debian AND',
        '-netinst',
        '@size',
        '@colour=red',
        'film @type=movie',
        'film @ext>mkv',
        'a OR b OR c OR d OR e OR f OR g OR h OR i OR j OR k OR l',
    ])('should refuse %s', (expression) => {
        whenTheExpressionIsChecked(expression);
        thenItIsValid(false);
    });

    function whenTheExpressionIsChecked(expression: string): void {
        valid = isSearchExpression(expression);
    }

    function thenItIsValid(expected: boolean): void {
        expect(valid).toBe(expected);
    }
});
