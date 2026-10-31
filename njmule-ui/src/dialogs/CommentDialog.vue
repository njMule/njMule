<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot } from 'reka-ui';
import { computed, ref, shallowRef, watch } from 'vue';
import { answer, api, type Schemas } from '../api/client';
import { t } from '../i18n';
import { problem } from '../toasts';
import { actsFirst, rating, RATINGS } from '../transfers';
import DialogHeader from '../ui/DialogHeader.vue';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// Our comment and rating of a file, which its downloaders hear, and what others said of it: the sources of a download,
// and the Kad nodes that hold notes on the file. For a download and for a shared file alike.

/** The file commented; {@code download} when it is a download, whose sources may have said something too. */
const props = defineProps<{ file: { hash: string; name: string } | null; download: boolean }>();
const open = defineModel<boolean>('open', { required: true });

// The longest comment clients take.
const MAX_LENGTH = 128;

type Comment = Schemas['Comment'];

const stars = ref(0);
const text = ref('');
const peers = shallowRef<Comment[]>([]);
const notes = shallowRef<Comment[]>([]);
const kadState = ref('');
const searching = ref(false);
const first = actsFirst();
// The stored comment may come after the user began to type: what the user changed stays.
let starsChanged = false;
let textChanged = false;

const heard = computed(() => [
    ...peers.value.map((c) => ({ ...c, kad: false })),
    ...notes.value.map((c) => ({ ...c, who: t('IDS_KADEMLIA'), kad: true })),
]);

watch(open, (now) => {
    const file = props.file;
    if (!now || file === null) {
        return;
    }
    stars.value = 0;
    text.value = '';
    starsChanged = false;
    textChanged = false;
    peers.value = [];
    notes.value = [];
    kadState.value = '';
    searching.value = false;
    const path = { params: { path: { hash: file.hash } } };
    if (props.download) {
        void answer(api().GET('/downloads/{hash}/comments', path)).then(
            (found) => (peers.value = found),
            () => {},
        );
    }
    void answer(api().GET('/files/{hash}/notes', path)).then(
        (found) => (notes.value = found),
        () => {},
    );
    void answer(api().GET('/files/{hash}/comment', path)).then(
        (own) => {
            if (!starsChanged) {
                stars.value = own.rating;
            }
            if (!textChanged) {
                text.value = own.text ?? '';
            }
        },
        () => {},
    );
});

// The button waits for the end of the search: pressed again it would only join it.
async function searchKad(): Promise<void> {
    const file = props.file;
    if (file === null) {
        return;
    }
    searching.value = true;
    kadState.value = t('IDS_KADSEARCHACTIVE');
    try {
        const found = await answer(api().POST('/files/{hash}/notes', { params: { path: { hash: file.hash } } }));
        notes.value = found;
        kadState.value = found.length === 0 ? t('nj.comments.kadNone') : t('nj.comments.kadCount', found.length);
    } catch (e) {
        kadState.value = t('nj.comments.kadFailed', (e as Error).message);
    } finally {
        searching.value = false;
    }
}

async function save(): Promise<void> {
    const file = props.file;
    if (file === null) {
        return;
    }
    try {
        await answer(
            api().PUT('/files/{hash}/comment', {
                params: { path: { hash: file.hash } },
                body: { rating: stars.value, text: text.value.trim() },
            }),
        );
        open.value = false;
    } catch (e) {
        problem((e as Error).message);
    }
}

function line(c: Comment): string {
    return `${c.who}: ${c.rating === 0 ? '' : `[${t(rating(c.rating).key)}] `}${c.text}`;
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog comments" :aria-describedby="undefined">
                <DialogHeader :title="t('nj.comments.title')" />
                <span class="file muted"
                    ><Icon name="FILE" /><span class="ellipsis" dir="auto">{{ file?.name }}</span></span
                >
                <label class="named">
                    <span>{{ t('IDS_CMT_RATEQUEST') }}</span>
                    <select v-model.number="stars" class="field" @change="starsChanged = true">
                        <option v-for="(r, value) in RATINGS" :key="r.key" :value="value">{{ t(r.key) }}</option>
                    </select>
                </label>
                <label class="named">
                    <span>{{ t('IDS_CMT_READ') }}</span>
                    <span class="field">
                        <input
                            v-model="text"
                            :maxlength="MAX_LENGTH"
                            :placeholder="t('IDS_CMT_QUEST')"
                            @input="textChanged = true"
                        />
                    </span>
                </label>
                <div class="heard-head">
                    <strong>{{ t('IDS_CMT_READALL') }}</strong>
                    <span class="spacer" />
                    <span class="muted">{{ kadState }}</span>
                    <button class="button flat" :disabled="searching" @click="searchKad">
                        <Icon name="SEARCH" />{{ t('IDS_SEARCHKAD') }}
                    </button>
                </div>
                <ul class="heard">
                    <li v-if="heard.length === 0" class="muted">{{ t('IDS_CMT_NONE') }}</li>
                    <li v-for="(c, i) in heard" :key="i">
                        <Tip :text="c.fileName === '' ? '' : `${t('IDS_DL_FILENAME')}: ${c.fileName}`">
                            <span class="heard-line">
                                <Icon v-if="c.kad" name="KAD" /><span dir="auto">{{ line(c) }}</span>
                            </span>
                        </Tip>
                    </li>
                </ul>
                <div class="dialog-buttons">
                    <button v-if="first" class="button accent" @click="save">
                        <Icon name="SAVE" />{{ t('nj.categories.save') }}
                    </button>
                    <DialogClose class="button"><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose>
                    <button v-if="!first" class="button accent" @click="save">
                        <Icon name="SAVE" />{{ t('nj.categories.save') }}
                    </button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.comments {
    width: min(620px, calc(100vw - 32px));
    height: min(520px, calc(100vh - 64px));
}

.file {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
}

.named {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.heard-head {
    display: flex;
    align-items: center;
    gap: 8px;
}

.spacer {
    flex: 1;
}

.heard {
    flex: 1;
    min-height: 0;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
}

.heard li {
    padding: 6px 10px;
    border-bottom: 1px solid var(--color-border-muted);
}

.heard-line {
    display: inline-flex;
    align-items: center;
    gap: 8px;
}
</style>
