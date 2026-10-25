<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { language } from '../i18n';

// A client's country: its flag beside its code, and its name, as the browser writes it in the page's language, as the
// tooltip; the colours of a flag are never the only cue.
const props = defineProps<{ code?: string | null }>();

const name = computed(() => {
    const code = (props.code ?? '').toUpperCase();
    try {
        return new Intl.DisplayNames([language.value], { type: 'region' }).of(code) ?? code;
    } catch {
        return code;
    }
});
</script>

<template>
    <span v-if="code && /^[a-z]{2}$/.test(code)" class="country" :title="name">
        <img :src="`flags/${code}.png`" alt="" width="16" height="12" />{{ code.toUpperCase() }}
    </span>
</template>

<style scoped>
.country {
    display: inline-flex;
    align-items: center;
    gap: 4px;
}
.country img {
    border-radius: 2px;
}
</style>
