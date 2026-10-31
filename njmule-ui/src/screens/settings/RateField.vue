<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { t } from '../../i18n';
import FieldInput from './FieldInput.vue';
import { MAX_RATE_TEXT, rateChar, type RateUnit } from './inputs';

// A speed typed in the unit beside it, KB/s or MB/s with decimals, or the speed of a line in Mbit/s, as the desktop
// app's RateField; a limit can also be no limit at all, which leaves the number and the unit aside.

defineProps<{
    id?: string;
    text: string;
    unit: RateUnit;
    /** The units it is typed in: one shows as a word, more as a list. */
    units: RateUnit[];
    /** Whether "No limit" is ticked; no box without. */
    none?: boolean;
    /** The speed in bytes too, for a line sold in bits. */
    about?: string;
    tip: string;
    invalid: boolean;
    label: string;
}>();
const emit = defineEmits<{ text: [text: string]; unit: [unit: RateUnit]; none: [on: boolean]; commit: [] }>();

function unitName(unit: RateUnit): string {
    return unit === 'MBIT' ? t('nj.rate.mbits') : t(unit === 'MB' ? 'IDS_MBYTESPERSEC' : 'IDS_KBYTESPERSEC');
}
</script>

<template>
    <span class="rate">
        <FieldInput
            :id="id"
            class="amount"
            dir="ltr"
            inputmode="decimal"
            :model-value="text"
            :allowed="rateChar"
            :max="MAX_RATE_TEXT"
            :tip="tip"
            :invalid="invalid"
            :disabled="none === true"
            :aria-label="label"
            @update:model-value="emit('text', $event)"
            @commit="emit('commit')"
        />
        <select
            v-if="units.length > 1"
            class="field unit"
            :value="unit"
            :disabled="none === true"
            :aria-label="label"
            @change="emit('unit', ($event.target as HTMLSelectElement).value as RateUnit)"
        >
            <option v-for="u in units" :key="u" :value="u">{{ unitName(u) }}</option>
        </select>
        <span v-else>{{ unitName(unit) }}</span>
        <label v-if="none !== undefined" class="check">
            <input
                type="checkbox"
                :checked="none"
                @change="emit('none', ($event.target as HTMLInputElement).checked)"
            />{{ t('nj.rate.noLimit') }}
        </label>
        <span v-if="about" class="about">{{ about }}</span>
    </span>
</template>

<style scoped>
.rate {
    display: inline-flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

/* Seven columns, the figures at the end, as the desktop app's. */
.rate :deep(.amount) {
    width: calc(7 * 0.94em + 18px);
    text-align: end;
}

.unit {
    width: auto;
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

/* A line is sold in bits; what njMule shows is bytes: the same speed in both. */
.about {
    color: var(--color-fg-muted);
}
</style>
