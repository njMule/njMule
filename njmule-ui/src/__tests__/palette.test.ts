// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { type Command, matching, step } from '../palette';

describe('the command palette', () => {
    let commands: Command[] = [];
    let index = 0;
    let found: string[] = [];

    it('should go round from the last command to the first', () => {
        givenCommands(['Transfers', 'Search', 'Shared files']);
        whenTheChoiceMoves(2, 1);
        thenTheChoiceIs(0);
    });

    it('should go round from the first command to the last', () => {
        givenCommands(['Transfers', 'Search', 'Shared files']);
        whenTheChoiceMoves(0, -1);
        thenTheChoiceIs(2);
    });

    it('should pass over a disabled command', () => {
        givenCommands(['Transfers', '-About njMule', 'Search']);
        whenTheChoiceMoves(0, 1);
        thenTheChoiceIs(2);
    });

    it('should start on the first command that runs', () => {
        givenCommands(['-Set up njMule', 'Light or dark']);
        whenTheChoiceMoves(0, 0);
        thenTheChoiceIs(1);
    });

    it('should keep the commands that hold every word typed, then what the text is', () => {
        givenCommands(['Open Incoming Folder', 'Light or dark', 'Collapse sidebar']);
        whenTheTextIs('in fold');
        thenTheCommandsAre(['Open Incoming Folder', 'Search for in fold']);
    });

    function givenCommands(titles: string[]): void {
        commands = titles.map((title) => ({
            title: title.replace(/^-/, ''),
            keys: '',
            run: () => {},
            disabled: title.startsWith('-'),
        }));
    }

    function whenTheChoiceMoves(at: number, by: number): void {
        index = step(commands, at, by);
    }

    function whenTheTextIs(text: string): void {
        found = matching(commands, text, (t) => [{ title: `Search for ${t}`, keys: '', run: () => {} }]).map(
            (c) => c.title,
        );
    }

    function thenTheChoiceIs(expected: number): void {
        expect(index).toBe(expected);
    }

    function thenTheCommandsAre(expected: string[]): void {
        expect(found).toEqual(expected);
    }
});
