<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { filesHere } from '../../files';
import { rate } from '../../format';
import { t } from '../../i18n';
import type { IconName } from '../../icons';
import type { Platform } from '../../platform';
import { label, words, type Action, type Field, type Row } from '../../settings';
import Icon from '../../ui/Icon.vue';
import Switch from '../../ui/Switch.vue';
import FieldInput from './FieldInput.vue';
import { rateKib, withProgram } from './inputs';
import { interfaceChoices, interfaceName, interfaces } from './interfaces';
import {
    commit,
    enabled,
    flags,
    masked,
    nextChoice,
    nextOn,
    rulesOf,
    setFlag,
    setNextOn,
    setUnit,
    setUnlimited,
    setValues,
    shown,
    tipOf,
    type,
    typed,
    unitOf,
    unlimited,
    value,
    wrong,
} from './options';
import RateField from './RateField.vue';

// The controls of one option, each bound to its key, and the button the option has, if any.

const props = defineProps<{ row: Row; title: string; platform?: Platform }>();
const emit = defineEmits<{ act: [action: Action] }>();

const BUTTONS: Record<Action, { text: string; icon: IconName }> = {
    checkUpdate: { text: 'nj.import.check', icon: 'REFRESH' },
    serverLists: { text: 'IDS_EDITLIST', icon: 'EDIT' },
    updateIpFilter: { text: 'IDS_SV_UPDATE', icon: 'REFRESH' },
    testNotifications: { text: 'IDS_TEST', icon: 'SEND' },
};

// A button before the switch it goes with; after the field otherwise.
const buttonFirst = computed(() => props.row.action === 'serverLists');

// The IP filter's lists are fetched only while they are right.
const buttonOff = computed(() => {
    const field = props.row.fields[0];
    if (props.row.action !== 'updateIpFilter' || field === undefined) {
        return false;
    }
    return shown(field).trim() === '' || wrong(field);
});

function id(field: Field): string {
    return `option-${field.key}`;
}

// A name for a control that shares its row with others; a lone control has the row's name.
const named = computed(() => (props.row.fields.length > 1 || props.row.label === '' ? props.title : undefined));

// The desktop app sizes fields in columns of a wide letter; a field that grows takes the row's room.
function size(field: Field): Record<string, string> {
    const columns = field.columns ?? (field.kind === 'number' ? 6 : 12);
    const width = `calc(${columns} * 0.94em + 18px)`;
    return field.grow ? { flex: '1 1 auto', width, minWidth: '0' } : { width, maxWidth: '100%' };
}

function placeholder(field: Field): string | undefined {
    return field.placeholder === undefined ? undefined : words(field.placeholder, t);
}

function chosen(e: Event): string {
    return (e.target as HTMLSelectElement).value;
}

// The speed of a line in bytes too: as it is typed, or exactly as it is saved.
function about(field: Field): string {
    const draft = typed.value[field.key];
    const saved = value(field.key);
    const kib = draft === undefined ? (saved === '' ? undefined : Number(saved)) : rateKib(draft, 'MBIT', 0);
    return kib === null || kib === undefined ? '' : `≈ ${rate(kib * 1024)}`;
}

// Typing in the dots of a password set replaces it whole.
function focused(field: Field, e: FocusEvent): void {
    if (masked(field)) {
        (e.target as HTMLInputElement).select();
    }
}

// A path typed or chosen: a folder or a command line of the engine's computer, a sound or a program of this one.
function browsable(field: Field): boolean {
    return ['folder', 'command', 'sound', 'program'].includes(field.kind);
}

function canBrowse(field: Field): boolean {
    const platform = props.platform;
    if (platform === undefined || !enabled(field)) {
        return false;
    }
    return field.kind === 'command' ? filesHere(platform) : platform.desktop;
}

async function browse(field: Field): Promise<void> {
    const platform = props.platform;
    if (platform === undefined) {
        return;
    }
    let chosen: string | null;
    if (field.kind === 'sound') {
        chosen = await platform.chooseFile(props.title, 'WAV, MP3', SOUND_EXTENSIONS);
    } else if (field.kind === 'program' || field.kind === 'command') {
        chosen = await platform.chooseFile(props.title, 'EXE', PROGRAM_EXTENSIONS);
    } else {
        chosen = await platform.chooseFolder(props.title);
    }
    if (chosen) {
        // A program chosen for a command line takes the place of the one typed, before the same arguments.
        type(field, field.kind === 'command' ? withProgram(shown(field), chosen) : chosen);
        await commit(props.row, field);
    }
}

// The kinds of sound the window plays; the programs of Windows, any file elsewhere.
const SOUND_EXTENSIONS = ['wav', 'mp3'];
const PROGRAM_EXTENSIONS = navigator.platform.startsWith('Win') ? ['exe'] : [];

// The sound as the notifications would play it, applied or not.
function hear(field: Field): void {
    props.platform?.playSound(shown(field).trim());
}
</script>

<template>
    <button v-if="row.action && buttonFirst" type="button" class="button" @click="emit('act', row.action)">
        <Icon :name="BUTTONS[row.action].icon" />{{ t(BUTTONS[row.action].text) }}
    </button>
    <template v-for="field in row.fields" :key="field.key">
        <Switch
            v-if="field.kind === 'switch'"
            :id="id(field)"
            :model-value="value(field.key) === 'true'"
            :label="title"
            :disabled="!enabled(field)"
            @update:model-value="(on: boolean) => setValues(row, { [field.key]: String(on) })"
        />
        <select
            v-else-if="field.kind === 'choice'"
            :id="id(field)"
            class="field choice"
            :value="value(field.key)"
            :disabled="!enabled(field)"
            :aria-label="named"
            @change="setValues(row, { [field.key]: chosen($event) })"
        >
            <option v-for="o in field.options" :key="o.value" :value="o.value">{{ label(t(o.label)) }}</option>
        </select>
        <template v-else-if="field.kind === 'next'">
            <select
                class="field choice"
                :value="nextChoice(field)"
                :disabled="!nextOn(field)"
                :aria-label="title"
                @change="setValues(row, { [field.key]: chosen($event) })"
            >
                <option v-for="o in field.options" :key="o.value" :value="o.value">{{ label(t(o.label)) }}</option>
            </select>
            <Switch
                :id="id(field)"
                :model-value="nextOn(field)"
                :label="title"
                @update:model-value="(on: boolean) => setNextOn(row, field, on)"
            />
        </template>
        <select
            v-else-if="field.kind === 'interface'"
            :id="id(field)"
            class="field choice interface"
            :value="value(field.key)"
            :disabled="!enabled(field)"
            :aria-label="named"
            @change="setValues(row, { [field.key]: chosen($event) })"
        >
            <option v-for="c in interfaceChoices(value(field.key), interfaces)" :key="c" :value="c">
                {{ interfaceName(c, interfaces, t) }}
            </option>
        </select>
        <div v-else-if="field.kind === 'flags'" :id="id(field)" class="flags" role="group" :aria-label="title">
            <label v-for="o in field.options" :key="o.value" class="check">
                <input
                    type="checkbox"
                    :checked="flags(field).includes(o.value)"
                    @change="setFlag(row, field, o.value, ($event.target as HTMLInputElement).checked)"
                />{{ label(t(o.label)) }}
            </label>
        </div>
        <RateField
            v-else-if="field.kind === 'limit' || field.kind === 'line'"
            :id="id(field)"
            :text="shown(field)"
            :unit="unitOf(field)"
            :units="field.kind === 'line' ? ['MBIT'] : ['KB', 'MB']"
            :none="field.kind === 'limit' ? unlimited(field) : undefined"
            :about="field.kind === 'line' ? about(field) : undefined"
            :tip="tipOf(field)"
            :invalid="wrong(field)"
            :label="title"
            @text="type(field, $event)"
            @unit="setUnit(row, field, $event)"
            @none="setUnlimited(row, field, $event)"
            @commit="commit(row, field)"
        />
        <template v-else>
            <FieldInput
                :id="id(field)"
                :model-value="shown(field)"
                :allowed="rulesOf(field)?.allowed"
                :max="rulesOf(field)?.max"
                :tip="tipOf(field)"
                :invalid="wrong(field)"
                :lines="field.kind === 'lines' ? (field.lines ?? 3) : undefined"
                :type="field.kind === 'password' ? 'password' : 'text'"
                :autocomplete="field.kind === 'password' ? 'new-password' : 'off'"
                :inputmode="field.kind === 'number' ? 'numeric' : undefined"
                :dir="field.ltr || field.kind === 'number' ? 'ltr' : 'auto'"
                :placeholder="placeholder(field)"
                :disabled="!enabled(field)"
                :aria-label="named"
                :style="size(field)"
                @focus="focused(field, $event)"
                @update:model-value="type(field, $event)"
                @commit="commit(row, field)"
            />
            <span v-if="field.unit">{{ t(field.unit) }}</span>
            <button
                v-if="browsable(field)"
                type="button"
                class="button"
                :disabled="!canBrowse(field)"
                @click="browse(field)"
            >
                <Icon name="FOLDER_OPEN" />{{ t('IDS_PW_BROWSE') }}
            </button>
            <button
                v-if="field.kind === 'sound'"
                type="button"
                class="button"
                :disabled="!platform?.desktop || shown(field).trim() === '' || wrong(field)"
                @click="hear(field)"
            >
                <Icon name="VOLUME" />{{ t('IDS_TEST') }}
            </button>
        </template>
    </template>
    <button
        v-if="row.action && !buttonFirst"
        type="button"
        class="button"
        :class="{ top: row.fields.length > 0 }"
        :disabled="buttonOff"
        @click="emit('act', row.action)"
    >
        <Icon :name="BUTTONS[row.action].icon" />{{ t(BUTTONS[row.action].text) }}
    </button>
</template>

<style scoped>
.choice {
    min-width: 176px;
    max-width: 320px;
}

/* The desktop app's width for an interface named with its addresses. */
.interface {
    width: 260px;
    min-width: 140px;
}

/* A button beside a list of lines keeps to its top. */
.top {
    align-self: flex-start;
}

.flags {
    display: flex;
    flex-wrap: wrap;
    gap: 8px 16px;
}

.check {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

.check input {
    width: 16px;
    height: 16px;
    margin: 0;
}
</style>
