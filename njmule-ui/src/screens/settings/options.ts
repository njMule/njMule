// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { computed, ref, shallowRef } from 'vue';
import { answer, api } from '../../api/client';
import { rate } from '../../format';
import { language, t } from '../../i18n';
import { preferences } from '../../model/engine';
import { holds, PAGES, type Field, type Page, type Row, type Section } from '../../settings';
import { problem } from '../../toasts';
import {
    checkRules,
    convertRate,
    numberRules,
    rateKib,
    rateRules,
    rateText,
    suitedUnit,
    type InputRules,
    type RateUnit,
} from './inputs';
import { overMask, PASSWORD_MASK, passwordsSet, readPasswords } from './passwords';

// The options as the screen shows them: the saved preferences, what waits for Apply, and what is typed and not set
// yet. A field applies when it is left or on Enter, a switch or a list at once; the ports, the proxy and the network
// interface wait for Apply and reconnect.

/** The preferences before anyone changes them, read once. */
export const defaults = shallowRef<Record<string, string>>({});
/** What waits for Apply, by key. */
export const held = ref<Record<string, string>>({});
/** What is typed in a field and not set yet, by key. */
export const typed = ref<Record<string, string>>({});
/** The unit a speed limit is typed in, once the user chose one. */
export const units = ref<Record<string, RateUnit>>({});
/** The limits whose "No limit" box the user cleared, while no speed is set yet. */
export const limited = ref<Record<string, boolean>>({});
/** The categories a next-download switch takes again when it is turned on. */
export const nextKept = ref<Record<string, string>>({});

/** An option with where it shows. */
export interface Place {
    page: Page;
    section: Section;
    row: Row;
}

export const PLACES: Place[] = PAGES.flatMap((page) =>
    page.sections.flatMap((section) => section.rows.map((row) => ({ page, section, row }))),
);

const PASSWORD_KEYS = new Set(
    PLACES.flatMap((p) => p.row.fields.filter((f) => f.kind === 'password').map((f) => f.key)),
);

function without<T>(map: Record<string, T>, key: string): Record<string, T> {
    const rest = { ...map };
    delete rest[key];
    return rest;
}

/** Forgets what is typed, the units chosen and the boxes cleared: the fields show the saved values again. */
export function dropDrafts(): void {
    typed.value = {};
    units.value = {};
    limited.value = {};
    nextKept.value = {};
}

function dropDraftsOf(key: string): void {
    typed.value = without(typed.value, key);
    units.value = without(units.value, key);
    limited.value = without(limited.value, key);
    nextKept.value = without(nextKept.value, key);
}

export async function readDefaults(): Promise<void> {
    try {
        defaults.value = await answer(api().GET('/preferences/defaults'));
    } catch {
        // An engine without the defaults: no option shows as changed.
    }
}

/** The value the engine keeps. */
export function saved(key: string): string {
    return preferences.value[key] ?? '';
}

/** The value set, waiting or kept. */
export function value(key: string): string {
    return held.value[key] ?? saved(key);
}

export function enabled(field: Field): boolean {
    return holds(field.enabledWhen, value);
}

function required(field: Field): boolean {
    return field.requiredWhen !== undefined && holds([field.requiredWhen], value);
}

function rated(field: Field): boolean {
    return field.kind === 'limit' || field.kind === 'line';
}

/** The unit a speed field shows: Mbit/s for a line, else the one chosen or the one that suits the speed. */
export function unitOf(field: Field): RateUnit {
    return field.kind === 'line' ? 'MBIT' : (units.value[field.key] ?? suitedUnit(Number(value(field.key))));
}

/** Whether a limit is none: its box is ticked. */
export function unlimited(field: Field): boolean {
    return field.kind === 'limit' && !limited.value[field.key] && value(field.key) === '0';
}

/** What a field takes and holds, as the desktop app's inputs; none for a switch or a list. */
export function rulesOf(field: Field): InputRules | undefined {
    switch (field.kind) {
        case 'number':
            return numberRules(field.min ?? 0, field.max ?? Number.MAX_SAFE_INTEGER, t);
        case 'limit':
        case 'line':
            return rateRules(unitOf(field), field.min ?? 1, false, t, rate);
        case 'text':
        case 'lines':
        case 'folder':
            if (field.check !== undefined) {
                return checkRules(field.check, t, {
                    max: field.max,
                    required: required(field),
                    placeholders: field.placeholders,
                });
            }
            // A cap only, with nothing to say.
            return field.max === undefined
                ? undefined
                : { allowed: () => true, max: field.max, hint: '', valid: () => true };
        default:
            return undefined;
    }
}

/** The text a field shows: what is typed, else the value in the field's form. */
export function shown(field: Field): string {
    const draft = typed.value[field.key];
    if (draft !== undefined) {
        return draft;
    }
    if (field.kind === 'password') {
        // The engine never gives a password: one typed, or dots while one is set.
        return held.value[field.key] ?? (masked(field) ? PASSWORD_MASK : '');
    }
    if (rated(field)) {
        const kib = value(field.key);
        return kib === '' || (field.kind === 'limit' && kib === '0')
            ? ''
            : rateText(Number(kib), unitOf(field), language.value);
    }
    return value(field.key);
}

/** Whether a field holds a wrong value: one typed, or one another option made wrong, as a host a proxy needs. */
export function wrong(field: Field): boolean {
    const rules = rulesOf(field);
    if (rules === undefined || Object.keys(preferences.value).length === 0) {
        return false;
    }
    const draft = typed.value[field.key];
    if (rated(field)) {
        // A speed as it was saved is right, even one too small for the unit's decimals.
        const untouched = draft === undefined && !limited.value[field.key];
        return !unlimited(field) && !untouched && !rules.valid(shown(field));
    }
    if (draft === undefined && field.requiredWhen === undefined) {
        return false;
    }
    return !rules.valid((draft ?? value(field.key)).trim());
}

/** What the first wrong field of a row takes, in words; empty while all are right. */
export function rowError(row: Row): string {
    const field = row.fields.find(wrong);
    return field === undefined ? '' : (rulesOf(field)?.hint ?? '');
}

/** The tooltip of a field: what it takes, and the line to fix when it names one. */
export function tipOf(field: Field): string {
    const rules = rulesOf(field);
    return rules === undefined ? '' : (rules.tip?.(shown(field)) ?? rules.hint);
}

/** Whether the saved value of an option differs from its default; a password, never given, never does. */
export function changed(row: Row): boolean {
    return row.fields.some(
        (f) => f.kind !== 'password' && defaults.value[f.key] !== undefined && saved(f.key) !== defaults.value[f.key],
    );
}

/** The first option with a wrong value, on any page, and its field. */
export function firstWrong(): { place: Place; field: Field } | undefined {
    for (const place of PLACES) {
        const field = place.row.fields.find(wrong);
        if (field !== undefined) {
            return { place, field };
        }
    }
    return undefined;
}

async function save(changes: Record<string, string>): Promise<boolean> {
    try {
        preferences.value = await answer(api().PATCH('/preferences', { body: changes }));
        if (Object.keys(changes).some((key) => PASSWORD_KEYS.has(key))) {
            void readPasswords();
        }
        return true;
    } catch (e) {
        problem((e as Error).message);
        return false;
    }
}

/** Saves what waits for Apply; false when the engine refused it. */
export async function saveHeld(): Promise<boolean> {
    if (Object.keys(held.value).length === 0) {
        return true;
    }
    if (!(await save(held.value))) {
        return false;
    }
    held.value = {};
    return true;
}

/** Sets values of a row: saved at once, or waiting for Apply when the row waits; a password is always set. */
export async function setValues(row: Row, values: Record<string, string>): Promise<void> {
    const password = (key: string) => row.fields.find((f) => f.key === key)?.kind === 'password';
    if (row.held) {
        let next = { ...held.value };
        for (const [key, v] of Object.entries(values)) {
            next = v === saved(key) && !password(key) ? without(next, key) : { ...next, [key]: v };
        }
        held.value = next;
        return;
    }
    const changes = Object.fromEntries(Object.entries(values).filter(([key, v]) => v !== saved(key) || password(key)));
    if (Object.keys(changes).length > 0) {
        await save(changes);
    }
}

/** Whether a password field shows the dots of a password set, nothing being typed or waiting. */
export function masked(field: Field): boolean {
    return (
        field.kind === 'password' &&
        passwordsSet.value[field.key] === true &&
        held.value[field.key] === undefined &&
        typed.value[field.key] === undefined
    );
}

export function type(field: Field, text: string): void {
    // The first change of the dots keeps only what was typed: a password set is replaced, or cleared.
    const kept = masked(field) ? overMask(text) : text;
    typed.value = { ...typed.value, [field.key]: kept };
}

// A speed left as it was shown gives back the exact KiB/s it was shown for.
async function commitRate(row: Row, field: Field): Promise<void> {
    const draft = typed.value[field.key];
    if (draft === undefined && !limited.value[field.key]) {
        return;
    }
    const unit = unitOf(field);
    const kib = rateKib(shown(field), unit, field.min ?? 1);
    if (kib === null || kib === undefined) {
        return;
    }
    const was = value(field.key);
    const same = draft !== undefined && was !== '0' && draft.trim() === rateText(Number(was), unit, language.value);
    await setValues(row, { [field.key]: same ? was : String(kib) });
    if (typed.value[field.key] === draft) {
        typed.value = without(typed.value, field.key);
    }
    limited.value = without(limited.value, field.key);
}

/** Sets what is typed in a field when it is right; a wrong value stays, its error under the option. */
export async function commit(row: Row, field: Field): Promise<void> {
    if (rated(field)) {
        return commitRate(row, field);
    }
    const draft = typed.value[field.key];
    if (draft === undefined) {
        return;
    }
    const text = field.kind === 'password' ? draft : draft.trim();
    if (rulesOf(field)?.valid(text) === false) {
        return;
    }
    await setValues(row, { [field.key]: field.kind === 'number' ? String(Number(text)) : text });
    if (typed.value[field.key] === draft) {
        typed.value = without(typed.value, field.key);
    }
}

const PORTS = ['net.tcpPort', 'net.udpPort', 'net.upnp'];

/** The ports as their fields give them: a change while a test runs lets a new one start. */
export const portChoice = computed(() => PORTS.map((key) => typed.value[key] ?? held.value[key] ?? saved(key)).join());

/**
 * Applies the ports as their fields give them, as the desktop app does before testing them; the other options keep
 * waiting for Apply.
 */
export async function applyPorts(): Promise<void> {
    for (const { row } of PLACES) {
        for (const field of row.fields.filter((f) => PORTS.includes(f.key))) {
            await commit(row, field);
        }
    }
    const ports = Object.fromEntries(Object.entries(held.value).filter(([key]) => PORTS.includes(key)));
    if (Object.keys(ports).length > 0 && (await save(ports))) {
        held.value = Object.fromEntries(Object.entries(held.value).filter(([key]) => !PORTS.includes(key)));
    }
}

/** Sets what is typed in every field, as Apply does first. */
export async function commitAll(): Promise<void> {
    for (const { row } of PLACES) {
        for (const field of row.fields) {
            if (typed.value[field.key] !== undefined || limited.value[field.key]) {
                await commit(row, field);
            }
        }
    }
}

/** Another unit keeps the speed typed: the number is written again in it. */
export async function setUnit(row: Row, field: Field, unit: RateUnit): Promise<void> {
    const draft = typed.value[field.key];
    if (draft !== undefined) {
        typed.value = { ...typed.value, [field.key]: convertRate(draft, unitOf(field), unit, language.value) };
    }
    units.value = { ...units.value, [field.key]: unit };
    await commitRate(row, field);
}

/** No limit sets 0 at once; clearing the box waits for a speed typed. */
export async function setUnlimited(row: Row, field: Field, on: boolean): Promise<void> {
    if (on) {
        dropDraftsOf(field.key);
        await setValues(row, { [field.key]: '0' });
    } else {
        limited.value = { ...limited.value, [field.key]: true };
        await commitRate(row, field);
    }
}

/** Whether the next download starts: its switch. */
export function nextOn(field: Field): boolean {
    const v = value(field.key);
    return v !== 'NONE' && v !== '';
}

/** The categories the next download takes, also while its switch is off. */
export function nextChoice(field: Field): string {
    return nextOn(field) ? value(field.key) : (nextKept.value[field.key] ?? 'ANY');
}

export async function setNextOn(row: Row, field: Field, on: boolean): Promise<void> {
    if (!on) {
        nextKept.value = { ...nextKept.value, [field.key]: nextChoice(field) };
    }
    await setValues(row, { [field.key]: on ? nextChoice(field) : 'NONE' });
}

/** The values of a list of choices ticked. */
export function flags(field: Field): string[] {
    return value(field.key)
        .split(',')
        .filter((v) => v !== '');
}

export function setFlag(row: Row, field: Field, flag: string, on: boolean): Promise<void> {
    const now = flags(field).filter((v) => v !== flag);
    return setValues(row, { [field.key]: (on ? [...now, flag] : now).join(',') });
}

/** Back to the defaults of a row, as its reset link does. */
export async function reset(row: Row): Promise<void> {
    row.fields.forEach((f) => dropDraftsOf(f.key));
    await setValues(row, Object.fromEntries(row.fields.map((f) => [f.key, defaults.value[f.key] ?? ''])));
}
