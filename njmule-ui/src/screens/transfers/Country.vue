<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { language } from '../../i18n';
import { countryName } from '../../transfers';
import Tip from '../../ui/Tip.vue';

// The flag of a client's country beside its code, the country's name as tooltip: the colours of a flag are never the
// only cue. Nothing when the country is not known.

const props = defineProps<{ code?: string | null }>();
const broken = ref(false);
watch(
    () => props.code,
    () => (broken.value = false),
);
// Two lower-case letters only: nothing else of the flags can be asked for.
const flag = computed(() => (props.code && /^[a-z]{2}$/.test(props.code) && !broken.value ? props.code : null));
</script>

<template>
    <Tip v-if="code" :text="countryName(code, language)">
        <span class="country">
            <img v-if="flag" class="flag" :src="`flags/${flag}.png`" alt="" @error="broken = true" />{{
                code.toUpperCase()
            }}
        </span>
    </Tip>
</template>

<style scoped>
.country {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    direction: ltr;
}

.flag {
    width: 16px;
    height: 12px;
    flex: none;
}
</style>
