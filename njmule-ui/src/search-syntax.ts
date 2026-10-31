// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The syntax of the search box as the engine reads it, to tell while typing whether a search can start: words and
// quoted phrases, AND, OR, NOT (or a leading -), parentheses and attributes such as @size>700m, @type=video or
// @length>=1:30:00. The engine's parser has no counterpart to call from here, so its rules are followed one by one.

/** The longest expression a search takes. */
export const MAX_EXPRESSION = 1024;
const MAX_OPERATORS = 10;
const MAX_DEPTH = 32;
const ED2K_FILE_LINK = /ed2k:\/\/\|file\|[^|]+\|[0-9]+\|[a-fA-F0-9]{32}\|\//y;
const HMS = /(\d+):(\d+)(?::(\d+))?/y;
const WORD_END = ' "()<>=';

type State = 'initial' | 'number' | 'size' | 'type' | 'string' | 'length';
type Kind = 'string' | 'number' | 'type' | 'link' | 'attribute' | 'operator' | 'and' | 'or' | 'not' | '(' | ')' | 'end';

interface Attribute {
    name: string;
    numeric: boolean;
    state: State;
}

interface Token {
    kind: Kind;
    equal?: boolean;
    attribute?: Attribute;
}

// Any prefix of at least three letters names an attribute.
const ATTRIBUTES: Attribute[] = [
    { name: 'size', numeric: true, state: 'size' },
    { name: 'type', numeric: false, state: 'type' },
    { name: 'ext', numeric: false, state: 'string' },
    { name: 'availability', numeric: true, state: 'number' },
    { name: 'sources', numeric: true, state: 'number' },
    { name: 'complete', numeric: true, state: 'number' },
    { name: 'bitrate', numeric: true, state: 'number' },
    { name: 'length', numeric: true, state: 'length' },
    { name: 'codec', numeric: false, state: 'string' },
    { name: 'rating', numeric: true, state: 'number' },
    { name: 'title', numeric: false, state: 'string' },
    { name: 'album', numeric: false, state: 'string' },
    { name: 'artist', numeric: false, state: 'string' },
];

const TYPES = ['audio', 'video', 'image', 'document', 'program', 'archive'];

class Invalid extends Error {}

/** Whether the engine takes {@code expression} as a search. */
export function isSearchExpression(expression: string): boolean {
    if (expression.length > MAX_EXPRESSION) {
        return false;
    }
    try {
        new Parser(lex(expression)).top();
        return true;
    } catch (e) {
        if (e instanceof Invalid) {
            return false;
        }
        throw e;
    }
}

function lex(text: string): Token[] {
    const out: Token[] = [];
    let pos = 0;
    let state = 'initial' as State;

    const wordEnd = (start: number): number => {
        let end = start;
        while (end < text.length && !WORD_END.includes(text[end]!)) {
            end++;
        }
        return end;
    };
    const digits = (from: number): number => {
        let i = from;
        while (i < text.length && text[i]! >= '0' && text[i]! <= '9') {
            i++;
        }
        return i;
    };
    // digits[.digits] or .digits, then an optional exponent.
    const numberEnd = (start: number): number => {
        let i = digits(start);
        const before = i > start;
        let after = false;
        if (text[i] === '.') {
            const j = digits(i + 1);
            after = j > i + 1;
            if (before || after) {
                i = j;
            }
        }
        if (!before && !after) {
            return start;
        }
        if (text[i] === 'e' || text[i] === 'E') {
            let j = i + 1;
            if (text[j] === '+' || text[j] === '-') {
                j++;
            }
            const k = digits(j);
            if (k > j) {
                i = k;
            }
        }
        return i;
    };
    const quoted = (): void => {
        let i = pos + 1;
        let empty = true;
        for (;;) {
            if (i >= text.length || text[i] === '\n') {
                throw new Invalid('unterminated string');
            }
            const c = text[i++]!;
            if (c === '"') {
                break;
            }
            if (c === '\\' && i < text.length) {
                i++;
            }
            empty = false;
        }
        pos = i;
        if (!empty) {
            out.push({ kind: 'string' });
        }
    };
    // >=, <=, <>, =, > or <; false where none starts.
    const operator = (): boolean => {
        const two = text.substring(pos, pos + 2);
        const symbol = ['>=', '<=', '<>'].includes(two) ? two : '=><'.includes(text[pos]!) ? text[pos]! : '';
        if (symbol === '') {
            return false;
        }
        pos += symbol.length;
        out.push({ kind: 'operator', equal: symbol === '=' });
        return true;
    };

    const initial = (): void => {
        const start = pos;
        const c = text[pos]!;
        if (c === '"') {
            quoted();
            return;
        }
        if (c === '(' || c === ')') {
            pos++;
            out.push({ kind: c });
            return;
        }
        if (c === '=') {
            pos++;
            out.push({ kind: 'operator', equal: true });
            return;
        }
        if (c === '<' || c === '>') {
            throw new Invalid('operator without attribute');
        }
        const end = wordEnd(start);
        const word = text.substring(start, end);
        ED2K_FILE_LINK.lastIndex = start;
        if (ED2K_FILE_LINK.test(text) && ED2K_FILE_LINK.lastIndex >= end) {
            pos = ED2K_FILE_LINK.lastIndex;
            out.push({ kind: 'link' });
            return;
        }
        if (word === 'AND' || word === 'OR' || word === 'NOT') {
            out.push({ kind: word === 'AND' ? 'and' : word === 'OR' ? 'or' : 'not' });
            pos = end;
        } else if (c === '-') {
            out.push({ kind: 'not' });
            pos = start + 1;
        } else if (c === '@') {
            let nameEnd = start + 1;
            while (nameEnd < text.length && text[nameEnd]! >= 'a' && text[nameEnd]! <= 'z') {
                nameEnd++;
            }
            const name = text.substring(start + 1, nameEnd);
            // A longer word (as "@size5") is not an attribute, and '@' cannot start a word.
            const attribute =
                word.length === nameEnd - start
                    ? ATTRIBUTES.find(
                          (a) => name.length >= 3 && name.length <= a.name.length && a.name.startsWith(name),
                      )
                    : undefined;
            if (attribute === undefined) {
                throw new Invalid('unknown attribute');
            }
            pos = nameEnd;
            out.push({ kind: 'attribute', attribute });
            state = attribute.state;
        } else {
            out.push({ kind: 'string' });
            pos = end;
        }
    };

    const number = (): void => {
        const start = pos;
        if (operator()) {
            return;
        }
        if (state === 'length') {
            HMS.lastIndex = start;
            if (HMS.test(text)) {
                pos = HMS.lastIndex;
                out.push({ kind: 'number' });
                state = 'initial';
                return;
            }
        }
        const end = numberEnd(start);
        if (end === start) {
            throw new Invalid('number expected');
        }
        const value = Number(text.substring(start, end));
        pos = end;
        const unit = text[pos] ?? ' ';
        let factor: number;
        if (state === 'number') {
            factor = ({ k: 1e3, m: 1e6, g: 1e9 } as Record<string, number>)[unit] ?? 0;
        } else if (state === 'size') {
            // No unit: megabytes.
            factor =
                ({ b: 1, k: 1024, m: 1024 ** 2, g: 1024 ** 3 } as Record<string, number>)[unit.toLowerCase()] ??
                -(1024 ** 2);
        } else {
            factor = ({ s: 1, m: 60, h: 3600 } as Record<string, number>)[unit] ?? 0;
        }
        if (factor > 0) {
            pos++;
        }
        if (!(value * (factor === 0 ? 1 : Math.abs(factor)) < 2 ** 63)) {
            throw new Invalid('number too large');
        }
        out.push({ kind: 'number' });
        state = 'initial';
    };

    const type = (): void => {
        if (text[pos] === '=') {
            pos++;
            out.push({ kind: 'operator', equal: true });
            return;
        }
        let end = pos;
        while (end < text.length && /[A-Za-z]/.test(text[end]!)) {
            end++;
        }
        const word = text.substring(pos, end).toLowerCase();
        const prefix = (name: string) => word.length >= 3 && word.length <= name.length && name.startsWith(word);
        if (!(TYPES.some(prefix) || word === 'img' || word === 'iso' || word === 'cd')) {
            throw new Invalid('@type');
        }
        pos = end;
        out.push({ kind: 'type' });
        state = 'initial';
    };

    const string = (): void => {
        if (text[pos] === '=') {
            pos++;
            out.push({ kind: 'operator', equal: true });
            return;
        }
        if (text[pos] === '"') {
            quoted();
            state = 'initial';
            return;
        }
        const end = wordEnd(pos);
        if (end === pos) {
            throw new Invalid('value expected');
        }
        out.push({ kind: 'string' });
        pos = end;
        state = 'initial';
    };

    for (;;) {
        while (text[pos] === ' ') {
            pos++;
        }
        if (pos >= text.length) {
            out.push({ kind: 'end' });
            return out;
        }
        switch (state) {
            case 'initial':
                initial();
                break;
            case 'number':
            case 'size':
            case 'length':
                number();
                break;
            case 'type':
                type();
                break;
            case 'string':
                string();
                break;
        }
    }
}

// Adjacent terms are ANDed with the lowest priority; NOT binds tighter than OR, which binds tighter than AND.
class Parser {
    private next = 0;
    private operators = 0;
    private depth = 0;

    constructor(private readonly tokens: Token[]) {}

    top(): void {
        const first = this.peek();
        if (first.kind === 'link' && this.tokens[1]?.kind === 'end') {
            return;
        }
        if (first.kind === 'end') {
            throw new Invalid('empty expression');
        }
        this.andList();
        if (this.peek().kind !== 'end' || this.operators > MAX_OPERATORS) {
            throw new Invalid('unexpected end');
        }
    }

    private andList(): void {
        this.andExpr();
        if (this.starts(this.peek())) {
            this.operators++;
            this.andList();
        }
    }

    private andExpr(): void {
        this.orExpr();
        while (this.peek().kind === 'and') {
            this.take();
            this.operators++;
            this.operand(() => this.orExpr());
        }
    }

    private orExpr(): void {
        this.notExpr();
        while (this.peek().kind === 'or') {
            this.take();
            this.operators++;
            this.operand(() => this.notExpr());
        }
    }

    private notExpr(): void {
        this.primary();
        while (this.peek().kind === 'not') {
            this.take();
            this.operators++;
            this.operand(() => this.primary());
        }
    }

    private operand(rule: () => void): void {
        if (!this.starts(this.peek())) {
            throw new Invalid('missing operand');
        }
        rule();
    }

    private primary(): void {
        const t = this.take();
        if (t.kind === 'string') {
            return;
        }
        if (t.kind === 'attribute') {
            this.attribute(t.attribute!);
            return;
        }
        if (t.kind !== '(' || ++this.depth > MAX_DEPTH || !this.starts(this.peek())) {
            throw new Invalid('unexpected token');
        }
        this.andList();
        if (this.peek().kind !== ')') {
            throw new Invalid('missing closing parenthesis');
        }
        this.take();
        this.depth--;
    }

    private attribute(a: Attribute): void {
        const op = this.take();
        const value = this.take();
        const expected = a.numeric ? 'number' : a.name === 'type' ? 'type' : 'string';
        if (op.kind !== 'operator' || (!a.numeric && !op.equal) || value.kind !== expected) {
            throw new Invalid(`@${a.name}`);
        }
    }

    private starts(t: Token): boolean {
        return t.kind === 'string' || t.kind === 'attribute' || t.kind === '(' || t.kind === 'operator';
    }

    private peek(): Token {
        return this.tokens[this.next]!;
    }

    private take(): Token {
        const t = this.tokens[this.next]!;
        if (t.kind !== 'end') {
            this.next++;
        }
        return t;
    }
}
