// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef, watch } from 'vue';
import { rates } from './model/engine';
import { highest, type Peaks } from './stats';

/**
 * The highest download and upload rates since the window opened, bytes per second, the minutes the engine kept before
 * included: followed from the start, as the rates come and go out of the hour kept.
 */
export const peaks = shallowRef<Peaks>({ down: 0, up: 0 });

watch(rates, (now) => (peaks.value = highest(peaks.value, now)));
