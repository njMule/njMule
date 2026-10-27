// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';

// As wide as a phone at most: the width under which the app stacks its parts (App.vue).
const PHONE = window.matchMedia('(max-width: 760px)');

/** Whether the window is as narrow as a phone's. */
export const narrow = ref(PHONE.matches);

PHONE.addEventListener('change', (e) => {
    narrow.value = e.matches;
});
