<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { answer, api, ApiError, type Schemas } from '../../api/client';
import { t } from '../../i18n';
import { message, problem } from '../../toasts';
import Icon from '../../ui/Icon.vue';
import Switch from '../../ui/Switch.vue';
import FieldInput from './FieldInput.vue';
import { numberRules, printable } from './inputs';
import SettingRow from './SettingRow.vue';
import {
    asciiChar,
    hostNamesValid,
    MAX_NAMES,
    MAX_PASSWORD,
    MIN_PASSWORD,
    names,
    passwordValid,
    readWebSettings,
    WEB_DEFAULTS,
    webSettings as web,
} from './webui';

// The web interface's page of the Options: its settings, kept in a file of their own and followed from the next start,
// each saved as it is set; the passwords once both fields agree; the API token copied or replaced. Only the desktop
// window on njMule's computer may change them: elsewhere they show disabled.

type Change = Schemas['WebInterfaceChange'];
type Text = 'port' | 'hosts' | 'proxies';

const typed = ref<Partial<Record<Text, string>>>({});
// The fields whose value the engine refused, such as a proxy that is no address: wrong as one typed wrong.
const refused = ref<Partial<Record<Text, boolean>>>({});
// Turned on before there is a password: it stays on screen, and is saved with the password.
const enabledHeld = ref(false);
const password = ref('');
const again = ref('');
const guest = ref('');
const guestAgain = ref('');

// Sized as the desktop app's fields; the field's own element takes no style of this page.
const PORT_SIZE = { width: 'calc(6 * 0.94em + 18px)' };
const NAMES_SIZE = { flex: '1 1 auto', minWidth: '0' };
const PASSWORD_SIZE = { width: '180px' };

const changeable = computed(() => web.value?.changeable === true);
const portRules = computed(() => numberRules(1, 65535, t));

onMounted(readWebSettings);

async function change(body: Change): Promise<boolean> {
    try {
        web.value = await answer(api().PATCH('/webui', { body }));
        return true;
    } catch (e) {
        problem((e as Error).message);
        return false;
    }
}

// ------------------------------------------------------------------ the switches

function enabled(): boolean {
    return (web.value?.enabled ?? false) || enabledHeld.value;
}

async function setEnabled(on: boolean): Promise<void> {
    if (on && !web.value?.hasPassword) {
        enabledHeld.value = true;
        problem(t('nj.settings.invalid', t('nj.webui.needsPassword')));
        return;
    }
    enabledHeld.value = false;
    await change({ enabled: on });
}

function yesNo(on: boolean): string {
    return t(on ? 'IDS_YES' : 'IDS_NO');
}

// ------------------------------------------------------------------ the fields

function saved(field: Text): string {
    const w = web.value;
    if (w === undefined) {
        return '';
    }
    return field === 'port' ? String(w.port) : w[field].join(', ');
}

function shown(field: Text): string {
    return typed.value[field] ?? saved(field);
}

function valid(field: Text, text: string): boolean {
    switch (field) {
        case 'port':
            return portRules.value.valid(text);
        case 'hosts':
            return hostNamesValid(text);
        default:
            return true;
    }
}

function wrong(field: Text): boolean {
    const draft = typed.value[field];
    return refused.value[field] === true || (draft !== undefined && !valid(field, draft.trim()));
}

// Whole keys, which the build finds.
const TITLES = { hosts: 'nj.webui.hosts', proxies: 'nj.webui.proxies' } as const;
const HINTS: Record<Text, string> = { port: '', hosts: 'nj.webui.hostsHint', proxies: 'nj.webui.proxiesHint' };

function tipOf(field: Text): string {
    return field === 'port' ? portRules.value.hint : t(HINTS[field]);
}

function errorOf(field: Text): string {
    return wrong(field) ? tipOf(field) : '';
}

function type(field: Text, text: string): void {
    typed.value = { ...typed.value, [field]: text };
    refused.value = { ...refused.value, [field]: undefined };
}

// A value is set when its field is left or on Enter, if it is right and differs.
async function commit(field: Text): Promise<void> {
    const draft = typed.value[field];
    if (draft === undefined || !valid(field, draft.trim())) {
        return;
    }
    const text = draft.trim();
    if (text === saved(field) || (field !== 'port' && names(text).join(', ') === saved(field))) {
        typed.value = { ...typed.value, [field]: undefined };
        return;
    }
    const body: Change = field === 'port' ? { port: Number(text) } : { [field]: names(text) };
    try {
        web.value = await answer(api().PATCH('/webui', { body }));
        typed.value = { ...typed.value, [field]: undefined };
    } catch (e) {
        if (e instanceof ApiError && e.status === 400) {
            refused.value = { ...refused.value, [field]: true };
        } else {
            problem((e as Error).message);
        }
    }
}

async function resetField(field: Text): Promise<void> {
    typed.value = { ...typed.value, [field]: undefined };
    refused.value = { ...refused.value, [field]: undefined };
    await change(field === 'port' ? { port: WEB_DEFAULTS.port } : { [field]: [] });
}

// ------------------------------------------------------------------ the passwords

const passwordError = computed(() =>
    !passwordValid(password.value)
        ? t('nj.input.password', MIN_PASSWORD)
        : again.value !== password.value
          ? t('nj.input.passwordAgain')
          : '',
);

const guestError = computed(() =>
    !passwordValid(guest.value)
        ? t('nj.input.password', MIN_PASSWORD)
        : guestAgain.value !== guest.value
          ? t('nj.input.passwordAgain')
          : '',
);

// A new password replaces the one kept once both fields agree; they empty then.
async function savePassword(): Promise<void> {
    if (password.value === '' || password.value !== again.value || !passwordValid(password.value)) {
        return;
    }
    const body: Change = enabledHeld.value ? { password: password.value, enabled: true } : { password: password.value };
    if (await change(body)) {
        password.value = '';
        again.value = '';
        enabledHeld.value = false;
        message(t('nj.settings.saved'));
    }
}

// The guest's password may not be the admin's, which the engine alone can tell.
async function saveGuest(): Promise<void> {
    if (guest.value === '' || guest.value !== guestAgain.value || !passwordValid(guest.value)) {
        return;
    }
    try {
        web.value = await answer(api().PATCH('/webui', { body: { guestPassword: guest.value } }));
        guest.value = '';
        guestAgain.value = '';
        message(t('nj.settings.saved'));
    } catch (e) {
        const words = (e as Error).message;
        problem(words.includes('must differ') ? t('nj.webui.guestSame') : words);
    }
}

async function removeGuest(): Promise<void> {
    if (await change({ guestPassword: '' })) {
        message(t('nj.settings.saved'));
    }
}

// ------------------------------------------------------------------ the API token

async function copyToken(renew: boolean): Promise<void> {
    try {
        const { token } = await answer(renew ? api().POST('/webui/token') : api().GET('/webui/token'));
        await navigator.clipboard.writeText(token);
        message(t(renew ? 'nj.webui.tokenRenewed' : 'nj.webui.tokenCopied'));
    } catch (e) {
        problem(t('nj.webui.tokenFailed', (e as Error).message));
    }
}
</script>

<template>
    <section class="settings-card web">
        <p v-if="web && !changeable" class="elsewhere" role="note">
            <Icon name="INFO" />{{ t('nj.webui.desktopOnly') }}
        </p>
        <SettingRow
            :title="t('nj.webui.enabled')"
            for="web-enabled"
            :changed="web?.enabled !== WEB_DEFAULTS.enabled"
            :default-text="yesNo(WEB_DEFAULTS.enabled)"
            @reset="setEnabled(WEB_DEFAULTS.enabled)"
        >
            <Switch
                id="web-enabled"
                :model-value="enabled()"
                :label="t('nj.webui.enabled')"
                :disabled="!changeable"
                @update:model-value="setEnabled"
            />
        </SettingRow>
        <SettingRow
            :title="t('nj.webui.port')"
            for="web-port"
            :changed="web !== undefined && web.port !== WEB_DEFAULTS.port"
            :default-text="String(WEB_DEFAULTS.port)"
            :error="errorOf('port')"
            @reset="resetField('port')"
        >
            <FieldInput
                id="web-port"
                :model-value="shown('port')"
                :allowed="portRules.allowed"
                :max="portRules.max"
                :tip="tipOf('port')"
                :invalid="wrong('port')"
                inputmode="numeric"
                dir="ltr"
                :style="PORT_SIZE"
                :disabled="!changeable"
                @update:model-value="type('port', $event)"
                @commit="commit('port')"
            />
        </SettingRow>
        <SettingRow
            :title="t('nj.webui.lan')"
            for="web-lan"
            :changed="web?.lan === true"
            :default-text="yesNo(WEB_DEFAULTS.lan)"
            @reset="change({ lan: WEB_DEFAULTS.lan })"
        >
            <Switch
                id="web-lan"
                :model-value="web?.lan ?? false"
                :label="t('nj.webui.lan')"
                :disabled="!changeable"
                @update:model-value="(on: boolean) => change({ lan: on })"
            />
        </SettingRow>
        <SettingRow
            v-for="field in ['hosts', 'proxies'] as const"
            :key="field"
            :title="t(TITLES[field])"
            :hint="t(HINTS[field])"
            :for="`web-${field}`"
            :changed="(web?.[field].length ?? 0) > 0"
            :error="errorOf(field)"
            wide
            @reset="resetField(field)"
        >
            <FieldInput
                :id="`web-${field}`"
                :model-value="shown(field)"
                :allowed="asciiChar"
                :max="MAX_NAMES"
                :tip="tipOf(field)"
                :invalid="wrong(field)"
                :placeholder="field === 'hosts' ? 'nas.local, njmule.example.com' : '172.17.0.1, 10.0.0.0/8'"
                dir="ltr"
                :style="NAMES_SIZE"
                :disabled="!changeable"
                @update:model-value="type(field, $event)"
                @commit="commit(field)"
            />
        </SettingRow>
        <SettingRow :title="t('nj.webui.password')" for="web-password" :error="passwordError">
            <FieldInput
                id="web-password"
                v-model="password"
                type="password"
                autocomplete="new-password"
                :allowed="printable"
                :max="MAX_PASSWORD"
                :tip="t('nj.input.password', MIN_PASSWORD)"
                :invalid="!passwordValid(password)"
                :style="PASSWORD_SIZE"
                :disabled="!changeable"
                @commit="savePassword"
            />
            <FieldInput
                v-model="again"
                type="password"
                autocomplete="new-password"
                :allowed="printable"
                :max="MAX_PASSWORD"
                :tip="t('nj.input.passwordAgain')"
                :invalid="again !== password"
                :aria-label="t('nj.input.passwordAgain')"
                :style="PASSWORD_SIZE"
                :disabled="!changeable"
                @commit="savePassword"
            />
        </SettingRow>
        <SettingRow
            :title="t('nj.webui.guestPassword')"
            :hint="t('nj.webui.guestHint')"
            for="web-guest"
            :error="guestError"
        >
            <FieldInput
                id="web-guest"
                v-model="guest"
                type="password"
                autocomplete="new-password"
                :allowed="printable"
                :max="MAX_PASSWORD"
                :tip="t('nj.input.password', MIN_PASSWORD)"
                :invalid="!passwordValid(guest)"
                :style="PASSWORD_SIZE"
                :disabled="!changeable"
                @commit="saveGuest"
            />
            <FieldInput
                v-model="guestAgain"
                type="password"
                autocomplete="new-password"
                :allowed="printable"
                :max="MAX_PASSWORD"
                :tip="t('nj.input.passwordAgain')"
                :invalid="guestAgain !== guest"
                :aria-label="t('nj.input.passwordAgain')"
                :style="PASSWORD_SIZE"
                :disabled="!changeable"
                @commit="saveGuest"
            />
            <button type="button" class="button" :disabled="!changeable || !web?.hasGuest" @click="removeGuest">
                <Icon name="DELETE" />{{ t('nj.webui.noGuest') }}
            </button>
        </SettingRow>
        <SettingRow
            :title="t('nj.webui.api')"
            for="web-api"
            :changed="web?.api === true"
            :default-text="yesNo(WEB_DEFAULTS.api)"
            @reset="change({ api: WEB_DEFAULTS.api })"
        >
            <Switch
                id="web-api"
                :model-value="web?.api ?? false"
                :label="t('nj.webui.api')"
                :disabled="!changeable"
                @update:model-value="(on: boolean) => change({ api: on })"
            />
        </SettingRow>
        <SettingRow title="">
            <button type="button" class="button" :disabled="!changeable" @click="copyToken(false)">
                <Icon name="COPY" />{{ t('nj.webui.copyToken') }}
            </button>
            <button type="button" class="button" :disabled="!changeable" @click="copyToken(true)">
                <Icon name="REFRESH" />{{ t('nj.webui.newToken') }}
            </button>
        </SettingRow>
    </section>
</template>

<style scoped>
.elsewhere {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0;
    padding: 10px 0 0;
    color: var(--color-fg-muted);
    --nj-icon-color: var(--color-accent-fg);
}
</style>
