<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, nextTick, ref, watch } from 'vue';
import { answer, api, ApiError, endpoint } from '../api/client';
import { ask } from '../ask';
import logo from '../assets/njmule-icon.svg';
import { rate } from '../format';
import { emuleProfile } from '../import';
import { keptLanguage, language, LANGUAGES, t } from '../i18n';
import { preferences } from '../model/engine';
import { engineHere, type Platform } from '../platform';
import FieldInput from '../screens/settings/FieldInput.vue';
import { isFullPath, nickRules, numberRules, rateKib, rateRules, rateText } from '../screens/settings/inputs';
import RateField from '../screens/settings/RateField.vue';
import { LANGUAGE_NAMES } from '../settings';
import {
    changedPreferences,
    finishSetup,
    LINES,
    lineKib,
    portPreferences,
    wizardPages,
    type Answers,
    type WizardPage,
} from '../setup';
import { mayHoldPersonalFiles } from '../shared';
import { family, mode, setTheme, type Mode } from '../theme';
import { message, problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import PortTestPane from '../ui/PortTestPane.vue';
import Segments from '../ui/Segments.vue';
import Switch from '../ui/Switch.vue';

// The first start: the language and the look, the nick, the folders, the connection and an eMule to take over, one page
// each beside the list of steps. Every page starts from the current settings, so Next leaves them as they are; Skip
// changes nothing.

/** What is left to do once the wizard is done, which needs the window around it. */
export interface WizardOutcome {
    /** The language chosen when it changed, empty for the system's. */
    language?: string;
    importEmule: boolean;
}

const props = defineProps<{ platform: Platform }>();
const open = defineModel<boolean>('open', { required: true });
const emit = defineEmits<{ done: [outcome: WizardOutcome] }>();

const MAX_NICK = 50;
const MAX_PORT = 65535;
// The width of the fields that need not the page's, and of a port's: six columns of a wide letter.
const NARROW = '340px';
const PORT_WIDTH = 'calc(6 * 0.94em + 18px)';
const MODES: { value: Mode; title: string }[] = [
    { value: 'system', title: 'nj.mode.system' },
    { value: 'light', title: 'nj.mode.light' },
    { value: 'dark', title: 'nj.mode.dark' },
];
// What each page is for, under its title.
const HINTS: Record<WizardPage, string> = {
    start: 'nj.wizard.intro',
    nick: 'nj.wizard.nickHint',
    folders: 'nj.wizard.foldersHint',
    connection: 'nj.wizard.portsHint',
    emule: 'nj.wizard.emuleHint',
};
// The names of the steps; eMule's is its own.
const STEPS: Record<Exclude<WizardPage, 'emule'>, string> = {
    start: 'nj.wizard.stepStart',
    nick: 'nj.wizard.stepNick',
    folders: 'nj.wizard.stepFolders',
    connection: 'nj.wizard.stepConnection',
};

// The pages show once the settings they start from are read.
const ready = ref(false);
const pages = ref<WizardPage[]>(wizardPages(false));
const at = ref(0);
const page = ref<HTMLElement | null>(null);
let saved: Record<string, string> = {};
let shownIncoming = '';
let languageBefore = '';
let modeBefore: Mode = 'system';

const languageChoice = ref('');
const modeChoice = ref<Mode>('system');
const nick = ref('');
const incoming = ref('');
const shared = ref<{ path: string; everything: boolean }[]>([]);
const line = ref('');
const down = ref('');
const up = ref('');
const tcp = ref('');
const udp = ref('');
const upnp = ref(false);
const emule = ref<string | null>(null);
const importEmule = ref(true);

const nickInput = nickRules(MAX_NICK, t);
const portInput = numberRules(1, MAX_PORT, t);
const lineInput = computed(() => rateRules('MBIT', 1, false, t, rate));

const nickValid = computed(() => nickInput.valid(nick.value.trim()));
const incomingValid = computed(() => isFullPath(incoming.value.trim()));
const tcpValid = computed(() => portInput.valid(tcp.value.trim()));
const udpValid = computed(() => portInput.valid(udp.value.trim()));
const downValid = computed(() => lineInput.value.valid(down.value));
const upValid = computed(() => lineInput.value.valid(up.value));

const current = computed(() => pages.value[at.value] ?? 'start');
const last = computed(() => at.value === pages.value.length - 1);
// Next works when the fields of the page shown are right; the other pages keep theirs.
const pageValid = computed(() => {
    switch (current.value) {
        case 'nick':
            return nickValid.value;
        case 'folders':
            return incomingValid.value;
        case 'connection':
            return tcpValid.value && udpValid.value && downValid.value && upValid.value;
        default:
            return true;
    }
});
// A folder of the engine's computer is chosen from the system's dialog only in the desktop app on that computer.
const canChoose = computed(() => props.platform.desktop && engineHere(endpoint().url));
const portChoice = computed(() => `${tcp.value},${udp.value},${upnp.value}`);

function stepName(p: WizardPage): string {
    return p === 'emule' ? 'eMule' : t(STEPS[p]);
}

function stepOf(index: number): string {
    return t('nj.wizard.stepOf', index + 1, pages.value.length);
}

// The speed of a line in bytes too, as it is typed.
function about(text: string): string {
    const kib = rateKib(text, 'MBIT', 0);
    return kib === null || kib === undefined ? '' : `≈ ${rate(kib * 1024)}`;
}

function lineText(kib: number): string {
    return rateText(kib, 'MBIT', language.value);
}

// The pages begin with the settings as they are.
watch(open, async (now) => {
    ready.value = false;
    if (!now) {
        return;
    }
    try {
        const [prefs, dirs, found] = await Promise.all([
            answer(api().GET('/preferences')),
            answer(api().GET('/folders')),
            emuleProfile(),
        ]);
        saved = prefs;
        shownIncoming = dirs.incoming;
        emule.value = found;
    } catch (e) {
        problem((e as Error).message);
        open.value = false;
        return;
    }
    pages.value = wizardPages(emule.value !== null);
    at.value = 0;
    languageBefore = keptLanguage();
    languageChoice.value = languageBefore;
    modeBefore = mode.value;
    modeChoice.value = mode.value;
    nick.value = saved['user.nick'] ?? '';
    incoming.value = shownIncoming;
    shared.value = [];
    line.value = '';
    down.value = lineText(Number(saved['limits.downloadCapacityKiBps'] ?? 0));
    up.value = lineText(Number(saved['limits.uploadCapacityKiBps'] ?? 0));
    tcp.value = saved['net.tcpPort'] ?? '';
    udp.value = saved['net.udpPort'] ?? '';
    upnp.value = saved['net.upnp'] === 'true';
    importEmule.value = true;
    ready.value = open.value;
});

// The look shows at once, on this window too.
watch(modeChoice, (now) => {
    if (ready.value && now !== mode.value) {
        setTheme(family.value, now);
    }
});

// A line chosen gives both capacities.
watch(line, (now) => {
    const chosen = LINES[Number(now)];
    if (now !== '' && chosen !== undefined) {
        down.value = lineText(lineKib(chosen.down));
        up.value = lineText(lineKib(chosen.up));
    }
});

// Another page shows from its top.
watch(at, async () => {
    await nextTick();
    if (page.value) {
        page.value.scrollTop = 0;
    }
});

// Skipping changes nothing: the look comes back. Either way the first start is over. Done here, not once the window
// is closed: a language chosen draws the window again at once, this wizard too.
function skip(): void {
    if (mode.value !== modeBefore) {
        setTheme(family.value, modeBefore);
    }
    void finishSetup();
    open.value = false;
}

function go(by: number): void {
    at.value = Math.max(0, Math.min(pages.value.length - 1, at.value + by));
}

function nextOrFinish(): void {
    if (!pageValid.value) {
        return;
    }
    if (last.value) {
        finish();
    } else {
        go(1);
    }
}

// The first field of the page has the focus, not the step shown.
function opened(e: Event): void {
    e.preventDefault();
    page.value?.querySelector<HTMLElement>('input, select')?.focus();
}

// Enter does what Next or Finish does, unless a button has the focus.
function enter(e: KeyboardEvent): void {
    if (e.target instanceof HTMLButtonElement || e.target instanceof HTMLSelectElement || e.isComposing) {
        return;
    }
    e.preventDefault();
    nextOrFinish();
}

async function browseIncoming(): Promise<void> {
    const folder = await props.platform.chooseFolder(t('IDS_PW_INCOMING'));
    if (folder !== null) {
        incoming.value = folder;
    }
}

// A folder where personal or system files may be is asked about first, as in the shared files.
async function addShared(): Promise<void> {
    let folder: string | null;
    if (canChoose.value) {
        folder = await props.platform.chooseFolder(t('nj.shared.shareFolder'));
    } else {
        // A page reaches no folder: the path of the engine's computer is typed.
        const typed = await ask({
            title: t('nj.shared.shareTitle'),
            text: t('nj.shared.shareText'),
            field: { label: t('nj.shared.shareFolder'), value: '' },
            yes: t('nj.shared.share'),
            no: t('IDS_CANCEL'),
        });
        folder = typed === null || typed.field === '' ? null : typed.field;
    }
    if (folder === null || shared.value.some((s) => s.path === folder)) {
        return;
    }
    const everything = mayHoldPersonalFiles(folder);
    if (everything && !(await shareAll(folder))) {
        return;
    }
    shared.value = [...shared.value, { path: folder, everything }];
}

function shareAll(folder: string): Promise<boolean> {
    return ask({
        title: t('nj.shared.shareAllTitle'),
        text: t('nj.shared.sensitiveText'),
        names: [folder],
        namesFirst: true,
        warning: true,
        yes: t('nj.shared.shareAll'),
        no: t('nj.shared.chooseOther'),
        risky: true,
    }).then((a) => a !== null);
}

function unshare(path: string): void {
    shared.value = shared.value.filter((s) => s.path !== path);
}

// The settings as the pages leave them; a field that is wrong keeps the setting it had.
function answers(): Answers {
    return {
        nick: nickValid.value ? nick.value.trim() : null,
        incoming: incomingValid.value ? incoming.value.trim() : null,
        tcpPort: tcpValid.value ? Number(tcp.value.trim()) : null,
        udpPort: udpValid.value ? Number(udp.value.trim()) : null,
        downloadKiBps: downValid.value ? (rateKib(down.value, 'MBIT', 1) ?? null) : null,
        uploadKiBps: upValid.value ? (rateKib(up.value, 'MBIT', 1) ?? null) : null,
        upnp: upnp.value,
    };
}

// The ports and UPnP as the page gives them, applied before a test of the ports.
async function applyPorts(): Promise<void> {
    const ports = portPreferences(changedPreferences(answers(), saved, shownIncoming));
    if (Object.keys(ports).length > 0) {
        saved = await answer(api().PATCH('/preferences', { body: ports }));
        preferences.value = saved;
    }
}

/** Applies the pages and closes; what follows needs the window around. */
function finish(): void {
    const changes = changedPreferences(answers(), saved, shownIncoming);
    const folders = shared.value;
    const outcome: WizardOutcome = {
        language: languageChoice.value === languageBefore ? undefined : languageChoice.value,
        importEmule: emule.value !== null && importEmule.value,
    };
    void finishSetup();
    open.value = false;
    void apply(changes, folders);
    emit('done', outcome);
}

async function apply(changes: Record<string, string>, folders: { path: string; everything: boolean }[]): Promise<void> {
    try {
        if (Object.keys(changes).length > 0) {
            preferences.value = await answer(api().PATCH('/preferences', { body: changes }));
        }
        message(t('nj.settings.saved'));
    } catch (e) {
        problem((e as Error).message);
    }
    for (const folder of folders) {
        await share(folder.path, folder.everything);
    }
}

// A folder the engine finds personal, though this window did not, is asked about then.
async function share(path: string, everything: boolean): Promise<void> {
    try {
        await answer(api().POST('/shared/directories', { body: { path, subdirectories: true, everything } }));
    } catch (e) {
        if (e instanceof ApiError && e.status === 409 && !everything) {
            if (await shareAll(path)) {
                await share(path, true);
            }
        } else {
            problem((e as Error).message);
        }
    }
}
</script>

<template>
    <DialogRoot :open="open && ready" @update:open="(now: boolean) => (open = now)">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent
                class="dialog wizard"
                @escape-key-down.prevent
                @pointer-down-outside.prevent
                @interact-outside.prevent
                @keydown.enter="enter"
                @open-auto-focus="opened"
            >
                <!-- The logo and what this is, the steps with a check on those done, and that all of it can change. -->
                <aside class="side">
                    <div class="brand">
                        <img :src="logo" alt="" width="32" height="32" />
                        <div>
                            <DialogTitle class="title display">{{ t('nj.wizard.title') }}</DialogTitle>
                            <DialogDescription class="muted">{{ t('nj.wizard.duration') }}</DialogDescription>
                        </div>
                    </div>
                    <nav class="steps">
                        <button
                            v-for="(p, i) in pages"
                            :key="p"
                            type="button"
                            class="step"
                            :class="{ done: i < at, current: i === at }"
                            :tabindex="i <= at ? 0 : -1"
                            :aria-current="i === at ? 'step' : undefined"
                            :aria-label="`${stepOf(i)}, ${stepName(p)}`"
                            @click="i < at && (at = i)"
                        >
                            <span class="mark"
                                ><Icon v-if="i < at" name="CHECK" /><template v-else>{{ i + 1 }}</template></span
                            >{{ stepName(p) }}
                        </button>
                    </nav>
                    <span class="hint later">{{ t('nj.wizard.later') }}</span>
                </aside>
                <div class="main">
                    <!-- A page taller than the window scrolls: the buttons below always stay in view. -->
                    <div ref="page" class="page">
                        <div class="head">
                            <span class="dialog-label">{{ stepOf(at) }}</span>
                            <h2 class="heading display">
                                {{ current === 'start' ? t('nj.wizard.welcome') : stepName(current) }}
                            </h2>
                            <p class="page-hint">{{ t(HINTS[current]) }}</p>
                        </div>
                        <template v-if="current === 'start'">
                            <div class="named">
                                <label class="dialog-label" for="wizard-language">{{ t('IDS_PW_LANG') }}</label>
                                <select id="wizard-language" v-model="languageChoice" class="field narrow">
                                    <option value="">{{ t('nj.settings.systemLanguage') }}</option>
                                    <option v-for="tag in LANGUAGES" :key="tag" :value="tag">
                                        {{ LANGUAGE_NAMES[tag] ?? tag }}
                                    </option>
                                </select>
                            </div>
                            <div class="named">
                                <span class="dialog-label">{{ t('nj.settings.theme') }}</span>
                                <Segments
                                    v-model="modeChoice"
                                    class="modes"
                                    :items="MODES.map((m) => ({ value: m.value, title: t(m.title) }))"
                                    :label="t('nj.settings.theme')"
                                />
                            </div>
                            <p class="page-hint">{{ t('nj.wizard.languageHint') }}</p>
                        </template>
                        <div v-else-if="current === 'nick'" class="named">
                            <label class="dialog-label" for="wizard-nick">{{ t('IDS_PW_NICK') }}</label>
                            <FieldInput
                                id="wizard-nick"
                                v-model="nick"
                                :style="{ width: NARROW, maxWidth: '100%' }"
                                :allowed="nickInput.allowed"
                                :max="nickInput.max"
                                :tip="nickInput.hint"
                                :invalid="!nickValid"
                                autocomplete="off"
                            />
                        </div>
                        <template v-else-if="current === 'folders'">
                            <div class="named">
                                <label class="dialog-label" for="wizard-incoming">{{ t('IDS_PW_INCOMING') }}</label>
                                <div class="row">
                                    <input
                                        id="wizard-incoming"
                                        v-model="incoming"
                                        class="field grow"
                                        dir="ltr"
                                        spellcheck="false"
                                        autocomplete="off"
                                        :aria-invalid="!incomingValid"
                                    />
                                    <button type="button" class="button" :disabled="!canChoose" @click="browseIncoming">
                                        <Icon name="FOLDER_OPEN" />{{ t('IDS_PW_BROWSE') }}
                                    </button>
                                </div>
                                <span class="hint">{{ t('nj.wizard.incomingHint') }}</span>
                            </div>
                            <div class="named">
                                <span class="dialog-label">{{ t('IDS_SHAREDFOLDERS') }}</span>
                                <div class="folders">
                                    <ul class="folder-list" :aria-label="t('IDS_SHAREDFOLDERS')">
                                        <li v-for="s in shared" :key="s.path" class="folder">
                                            <Icon name="FOLDER_OPEN" />
                                            <span class="ellipsis grow" dir="ltr">{{ s.path }}</span>
                                            <button
                                                type="button"
                                                class="button flat small icon-only"
                                                :aria-label="t('nj.wizard.unshare', s.path)"
                                                @click="unshare(s.path)"
                                            >
                                                <Icon name="CLOSE" />
                                            </button>
                                        </li>
                                        <li v-if="shared.length === 0" class="no-folders">
                                            {{ t('nj.wizard.noFolders') }}
                                        </li>
                                    </ul>
                                    <div class="folders-add">
                                        <button type="button" class="button" @click="addShared">
                                            <Icon name="FOLDER_ADD" />{{ t('nj.shared.shareFolder') }}
                                        </button>
                                    </div>
                                </div>
                            </div>
                            <span class="hint asks"><Icon name="SHIELD" />{{ t('nj.wizard.asksFirst') }}</span>
                        </template>
                        <template v-else-if="current === 'connection'">
                            <div class="named">
                                <label class="dialog-label" for="wizard-line">{{ t('nj.wizard.line') }}</label>
                                <select
                                    id="wizard-line"
                                    v-model="line"
                                    class="field narrow"
                                    :class="{ prompt: line === '' }"
                                >
                                    <option value="" disabled hidden>{{ t('nj.wizard.linePick') }}</option>
                                    <option v-for="(l, i) in LINES" :key="i" :value="String(i)">
                                        {{ t('nj.wizard.mbits', String(l.down), String(l.up)) }}
                                    </option>
                                </select>
                            </div>
                            <!-- Both capacities side by side, the page then fits without scrolling. -->
                            <div class="capacities">
                                <div class="named">
                                    <label class="dialog-label" for="wizard-down">{{
                                        t('nj.settings.downloadCapacity')
                                    }}</label>
                                    <RateField
                                        id="wizard-down"
                                        :text="down"
                                        unit="MBIT"
                                        :units="['MBIT']"
                                        :none="undefined"
                                        :about="about(down)"
                                        :tip="lineInput.hint"
                                        :invalid="!downValid"
                                        :label="t('nj.settings.downloadCapacity')"
                                        @text="down = $event"
                                    />
                                </div>
                                <div class="named">
                                    <label class="dialog-label" for="wizard-up">{{
                                        t('nj.settings.uploadCapacity')
                                    }}</label>
                                    <RateField
                                        id="wizard-up"
                                        :text="up"
                                        unit="MBIT"
                                        :units="['MBIT']"
                                        :none="undefined"
                                        :about="about(up)"
                                        :tip="lineInput.hint"
                                        :invalid="!upValid"
                                        :label="t('nj.settings.uploadCapacity')"
                                        @text="up = $event"
                                    />
                                </div>
                            </div>
                            <div class="ports">
                                <div class="named">
                                    <label class="dialog-label" for="wizard-tcp"
                                        >{{ t('IDS_PW_CLIENTPORT') }} (TCP)</label
                                    >
                                    <FieldInput
                                        id="wizard-tcp"
                                        v-model="tcp"
                                        :style="{ width: PORT_WIDTH }"
                                        dir="ltr"
                                        inputmode="numeric"
                                        :allowed="portInput.allowed"
                                        :max="portInput.max"
                                        :tip="portInput.hint"
                                        :invalid="!tcpValid"
                                    />
                                </div>
                                <div class="named">
                                    <label class="dialog-label" for="wizard-udp">UDP</label>
                                    <FieldInput
                                        id="wizard-udp"
                                        v-model="udp"
                                        :style="{ width: PORT_WIDTH }"
                                        dir="ltr"
                                        inputmode="numeric"
                                        :allowed="portInput.allowed"
                                        :max="portInput.max"
                                        :tip="portInput.hint"
                                        :invalid="!udpValid"
                                    />
                                </div>
                            </div>
                            <div class="upnp">
                                <Switch id="wizard-upnp" v-model="upnp" :label="t('nj.settings.upnp')" />
                                <div class="upnp-words">
                                    <span class="switch-name" @click="upnp = !upnp">{{ t('nj.settings.upnp') }}</span>
                                    <span class="hint">{{ t('nj.wizard.upnpHint') }}</span>
                                </div>
                            </div>
                            <PortTestPane :apply="applyPorts" :ports="portChoice" />
                        </template>
                        <template v-else>
                            <div class="found">
                                <Icon name="CHECK_CIRCLE" />
                                <div class="found-words">
                                    <span>{{ t('nj.wizard.emuleFound') }}</span>
                                    <span class="muted" dir="ltr">{{ emule }}</span>
                                </div>
                            </div>
                            <label class="option"
                                ><input v-model="importEmule" type="checkbox" /><span>{{
                                    t('nj.wizard.emuleImport')
                                }}</span></label
                            >
                            <p class="page-hint">{{ t('nj.wizard.emuleNote') }}</p>
                        </template>
                    </div>
                    <div class="footer">
                        <button
                            type="button"
                            class="button flat skip"
                            :class="{ unseen: last }"
                            :tabindex="last ? -1 : undefined"
                            :aria-hidden="last ? 'true' : undefined"
                            @click="skip"
                        >
                            <Icon name="SKIP" />{{ t('nj.wizard.skip') }}
                        </button>
                        <span class="grow" />
                        <button v-if="at > 0" type="button" class="button" @click="go(-1)">
                            <Icon name="ARROW_BACK" />{{ t('nj.wizard.back') }}
                        </button>
                        <button type="button" class="button accent" :disabled="!pageValid" @click="nextOrFinish">
                            {{ t(last ? 'nj.wizard.finish' : 'nj.wizard.next')
                            }}<Icon v-if="!last" name="ARROW_FORWARD" />
                        </button>
                    </div>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.wizard {
    flex-direction: row;
    width: min(800px, calc(100vw - 32px));
    height: min(560px, calc(100vh - 32px));
    padding: 0;
    gap: 0;
    overflow: hidden;
}

/* The steps down a tinted side, a number in a ring or a check once done, the one shown lifted. */
.side {
    display: flex;
    flex-direction: column;
    gap: 4px;
    flex: 0 0 220px;
    padding: 20px 12px;
    border-inline-end: 1px solid var(--color-border-muted);
    background: var(--color-bg-subtle);
}

.brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 6px 18px;
}

.title {
    margin: 0;
    font-size: 1.15em;
}

.steps {
    display: flex;
    flex-direction: column;
    gap: 4px;
    flex: 1;
}

.step {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 8px 10px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--color-fg-muted);
    text-align: start;
    cursor: default;
}

.step.done {
    color: var(--color-fg-default);
    cursor: pointer;
}

.step.done:hover {
    background: var(--color-bg-inset);
}

.step.current {
    background: var(--color-bg-default);
    color: var(--color-fg-default);
    font-weight: bold;
    box-shadow: 0 1px 3px var(--nj-scrim);
}

.step:focus-visible {
    outline: 2px solid var(--color-accent-emphasis);
    outline-offset: 1px;
}

.mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: none;
    width: 24px;
    height: 24px;
    border: 1.5px solid var(--color-border-default);
    border-radius: 50%;
    background: var(--color-bg-default);
    color: var(--color-fg-muted);
    font-size: 0.9em;
    font-weight: bold;
}

.step.done .mark {
    border-color: var(--color-success-muted);
    background: var(--color-success-muted);
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-success-fg);
}

.step.current .mark {
    border-color: var(--color-accent-emphasis);
    color: var(--color-accent-fg);
}

.later {
    padding: 0 8px;
}

.main {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
}

/* The page beside the steps, its fields named above them. */
.page {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
    flex: 1;
    min-height: 0;
    padding: 28px 28px 16px;
    overflow-x: hidden;
    overflow-y: auto;
}

.page > * {
    align-self: stretch;
}

.head {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.heading {
    margin: 0;
    font-size: 1.6em;
}

.page-hint {
    margin: 0;
    color: var(--color-fg-muted);
}

.named {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
}

.named > .row,
.named > .folders {
    align-self: stretch;
}

.dialog-label {
    font-size: 0.92em;
    font-weight: bold;
    color: var(--color-fg-muted);
}

.hint {
    font-size: 0.92em;
    color: var(--color-fg-muted);
}

.narrow {
    width: 340px;
    max-width: 100%;
}

select.prompt {
    color: var(--color-fg-muted);
}

.row {
    display: flex;
    gap: 8px;
}

.grow {
    flex: 1;
    min-width: 0;
}

.field[aria-invalid='true'] {
    border-color: var(--color-danger-emphasis);
}

.folders {
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
}

.folder-list {
    display: flex;
    flex-direction: column;
    height: 112px;
    margin: 0;
    padding: 2px 0;
    border-bottom: 1px solid var(--color-border-muted);
    list-style: none;
    overflow-y: auto;
}

.folder {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 28px;
    padding: 0 6px 0 10px;
    --nj-icon-color: var(--color-fg-muted);
}

.no-folders {
    margin: auto;
}

.folders-add {
    padding: 8px 10px;
}

.asks {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    --nj-icon-size: 14px;
}

.asks .nj-icon {
    margin-top: 1px;
}

.capacities {
    display: flex;
    flex-wrap: wrap;
    gap: 16px 24px;
}

.capacities :deep(.amount) {
    width: calc(5 * 0.94em + 18px);
}

.ports {
    display: flex;
    gap: 16px;
}

.upnp {
    display: flex;
    align-items: center;
    gap: 12px;
}

.upnp-words {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
}

.switch-name {
    font-weight: bold;
    cursor: default;
}

.found {
    display: flex;
    align-items: flex-start;
    gap: 10px;
    padding: 10px 12px;
    border-radius: 8px;
    background: var(--color-success-subtle);
    --nj-icon-color: var(--color-success-fg);
}

.found .nj-icon {
    margin-top: 1px;
}

.found-words {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    overflow-wrap: anywhere;
}

.option {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 10px 12px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
}

.option input {
    flex: none;
    width: 16px;
    height: 16px;
    margin: 1px 0 0;
}

.footer {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 14px 28px;
    border-top: 1px solid var(--color-border-muted);
    background: var(--color-bg-subtle);
}

.unseen {
    visibility: hidden;
}
</style>
