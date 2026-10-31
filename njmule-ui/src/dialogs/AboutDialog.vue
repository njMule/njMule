<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    DialogClose,
    DialogContent,
    DialogOverlay,
    DialogPortal,
    DialogRoot,
    DialogTitle,
    VisuallyHidden,
} from 'reka-ui';
import { shallowRef, watch } from 'vue';
import { aboutOpen, COPYRIGHT, LICENSE_ID, TAGLINE, versions } from '../about';
import { answer, api, type Schemas } from '../api/client';
import logo from '../assets/njmule-icon.svg';
import { t } from '../i18n';
import { message, problem } from '../toasts';
import Icon from '../ui/Icon.vue';

// Name, version, copyright and licence; the version and the system are the engine's, read as the dialog opens.
const info = shallowRef<Schemas['ApiInfo'] | null>(null);

watch(aboutOpen, async (now) => {
    if (now) {
        try {
            info.value = await answer(api().GET('/'));
        } catch {
            // The name alone until the engine answers.
        }
    }
});

async function copyVersion(): Promise<void> {
    try {
        await navigator.clipboard.writeText(versions(info.value ?? { name: 'njMule', version: '', api: 0 }));
        message(t('nj.about.copied'));
    } catch (e) {
        problem((e as Error).message);
    }
}
</script>

<template>
    <DialogRoot v-model:open="aboutOpen">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog about" :aria-describedby="undefined">
                <VisuallyHidden>
                    <DialogTitle>{{ t('nj.about.title') }}</DialogTitle>
                </VisuallyHidden>
                <div class="about-body">
                    <!-- The logo is never mirrored; the name, the tagline and the copyright are English. -->
                    <div class="about-header" dir="ltr">
                        <img :src="logo" alt="" width="72" height="72" />
                        <div class="about-names">
                            <span class="about-name display">njMule {{ info?.version ?? '' }}</span>
                            <span class="muted">{{ TAGLINE }}</span>
                        </div>
                    </div>
                    <p dir="ltr">{{ COPYRIGHT }}</p>
                    <p>{{ t('nj.about.license', LICENSE_ID) }}</p>
                    <div>
                        <button id="about-copy" type="button" class="button flat" @click="copyVersion">
                            <Icon name="COPY" />{{ t('nj.about.copyVersion') }}
                        </button>
                    </div>
                    <p class="about-emule muted">{{ t('nj.about.emule') }}</p>
                </div>
                <div class="about-footer">
                    <DialogClose class="button"><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
/* The body on its own padding and the band of the buttons across the whole width. */
.about {
    width: min(520px, calc(100vw - 32px));
    gap: 0;
    padding: 0;
    overflow: hidden;
}

.about-body {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 18px 18px 16px;
    overflow-y: auto;
}

.about-body p {
    margin: 0;
}

.about-header {
    display: flex;
    align-items: center;
    gap: 16px;
}

.about-names {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.about-name {
    font-size: 1.75em;
}

.about-emule {
    font-size: 0.8em;
}

.about-footer {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    padding: 12px 18px;
    border-top: 1px solid var(--color-border-muted);
    background: var(--color-bg-subtle);
}

.about-footer .button {
    min-width: 64px;
    justify-content: center;
}
</style>
