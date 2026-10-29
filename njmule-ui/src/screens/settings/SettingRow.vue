<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { t } from '../../i18n';
import Icon from '../../ui/Icon.vue';

// One option: its name, what it does, whether it differs from its default with a way back to it, what is wrong with
// the value typed, and its control at the end side, or under the name for paths, lists and command lines.

defineProps<{
    title: string;
    hint?: string;
    /** The id of the control the name is for. */
    for?: string;
    changed?: boolean;
    /** The default in words, empty for none. */
    defaultText?: string;
    error?: string;
    wide?: boolean;
}>();
const emit = defineEmits<{ reset: [] }>();
</script>

<template>
    <div class="setting-row" :class="{ wide }">
        <div class="words">
            <div v-if="title" class="head">
                <label class="setting-title" :for="$props.for">{{ title }}</label>
                <template v-if="changed">
                    <span class="changed-mark">{{ t('nj.settings.changed') }}</span>
                    <button type="button" class="reset-link" @click="emit('reset')">
                        {{ defaultText ? t('nj.settings.resetTo', defaultText) : t('IDS_PW_RESET') }}
                    </button>
                </template>
            </div>
            <p v-if="hint" class="setting-description">{{ hint }}</p>
            <p v-if="error" class="setting-error" role="alert"><Icon name="ERROR" />{{ error }}</p>
        </div>
        <div class="control"><slot /></div>
    </div>
</template>

<style scoped>
.setting-row {
    display: flex;
    align-items: center;
    gap: 16px;
    padding: 12px 0;
}

.setting-row + .setting-row {
    border-top: 1px solid var(--color-border-muted);
}

.setting-row.wide {
    flex-direction: column;
    align-items: stretch;
    gap: 8px;
}

.words {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
}

.head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 2px 8px;
}

.changed-mark {
    padding: 1px 8px;
    border-radius: 4px;
    background: var(--color-bg-subtle);
    font-size: 0.9em;
}

/* A link back to the default, in the theme's link colour. */
.reset-link {
    padding: 0;
    border: 0;
    background: none;
    color: var(--color-accent-fg);
    text-decoration: underline;
    cursor: pointer;
}

.setting-description {
    margin: 0;
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.setting-error {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    color: var(--color-danger-fg);
    --nj-icon-color: var(--color-danger-fg);
}

.control {
    display: flex;
    flex: none;
    align-items: center;
    gap: 8px;
}

.wide .control {
    flex: auto;
}
</style>
