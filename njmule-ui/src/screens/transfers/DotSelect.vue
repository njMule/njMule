<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    DropdownMenuContent,
    DropdownMenuItemIndicator,
    DropdownMenuPortal,
    DropdownMenuRadioGroup,
    DropdownMenuRadioItem,
    DropdownMenuRoot,
    DropdownMenuTrigger,
} from 'reka-ui';
import { computed } from 'vue';
import Icon from '../../ui/Icon.vue';

// A choice among categories or their tints, each with its dot beside its name: the tint is never the only cue. An item
// without a colour has no dot, such as "All categories".

export interface DotItem {
    value: number;
    label: string;
    /** The tint of the dot, 0 for an outline; none for no dot. */
    color?: number;
}

const props = defineProps<{ items: DotItem[]; label: string }>();
const value = defineModel<number>({ required: true });
const current = computed(() => props.items.find((i) => i.value === value.value));

function tint(color: number): string {
    return color > 0 ? `cat-${color}` : '';
}
</script>

<template>
    <div class="dot-select">
        <DropdownMenuRoot>
            <DropdownMenuTrigger class="field trigger" :aria-label="label">
                <span v-if="current?.color !== undefined" class="category-dot" :class="tint(current.color)" />
                <span class="ellipsis" dir="auto">{{ current?.label ?? '' }}</span>
                <Icon class="end" name="CHEVRON_DOWN" />
            </DropdownMenuTrigger>
            <DropdownMenuPortal>
                <DropdownMenuContent class="menu" align="start">
                    <DropdownMenuRadioGroup :model-value="value" @update:model-value="(v) => (value = Number(v))">
                        <DropdownMenuRadioItem
                            v-for="item in items"
                            :key="item.value"
                            class="menu-item"
                            :value="item.value"
                        >
                            <span class="menu-check"
                                ><DropdownMenuItemIndicator><Icon name="CHECK" /></DropdownMenuItemIndicator
                            ></span>
                            <span v-if="item.color !== undefined" class="category-dot" :class="tint(item.color)" />
                            <span class="ellipsis" dir="auto">{{ item.label }}</span>
                        </DropdownMenuRadioItem>
                    </DropdownMenuRadioGroup>
                </DropdownMenuContent>
            </DropdownMenuPortal>
        </DropdownMenuRoot>
    </div>
</template>

<style scoped>
.dot-select {
    display: flex;
    min-width: 0;
}

.trigger {
    flex: 1;
    min-width: 0;
    color: inherit;
    font: inherit;
    cursor: pointer;
}

.end {
    margin-inline-start: auto;
}
</style>
