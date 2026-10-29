<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { t } from '../i18n';
import { close, toasts } from '../toasts';
import Icon from '../ui/Icon.vue';

// Short messages at the bottom centre of the window, over everything.
</script>

<template>
    <div class="toasts" role="status" aria-live="polite">
        <div v-for="toast in toasts" :key="toast.id" class="toast" :class="toast.tone">
            <Icon :name="toast.icon" />
            <span class="toast-text">{{ toast.text }}</span>
            <button
                v-if="toast.action"
                class="button flat"
                @click="
                    toast.action.run();
                    close(toast.id);
                "
            >
                {{ toast.action.label }}
            </button>
            <button class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')" @click="close(toast.id)">
                <Icon name="CLOSE" />
            </button>
        </div>
    </div>
</template>

<style scoped>
.toasts {
    position: fixed;
    bottom: 44px;
    left: 50%;
    transform: translateX(-50%);
    display: flex;
    flex-direction: column;
    gap: 8px;
    z-index: 25;
    pointer-events: none;
}

.toast {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 320px;
    max-width: 560px;
    padding: 4px 4px 4px 14px;
    border: 1px solid var(--color-border-default);
    border-radius: 10px;
    background: var(--color-bg-overlay);
    box-shadow: 0 4px 16px var(--color-shadow-default);
    pointer-events: auto;
    --nj-icon-size: 20px;
}

.toast-text {
    flex: 1;
}

.toast.success {
    --nj-icon-color: var(--color-success-emphasis);
}

.toast.danger {
    --nj-icon-color: var(--color-danger-emphasis);
}

.toast.info {
    --nj-icon-color: var(--color-accent-emphasis);
}
</style>
