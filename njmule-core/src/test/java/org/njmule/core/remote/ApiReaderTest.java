/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.remote;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.njmule.core.api.SavedSearch;
import tools.jackson.databind.json.JsonMapper;

class ApiReaderTest {

    private SavedSearch read;

    @Test
    void shouldReadBackTheSavedSearchTheApiWrites() {
        whenTheSavedSearchIsReadBack(ApiSamples.savedSearch());

        thenItIs(ApiSamples.savedSearch());
    }

    private void whenTheSavedSearchIsReadBack(SavedSearch sample) {
        byte[] json = ApiJson.write(g -> ApiJson.savedSearch(g, sample));
        this.read = ApiReader.savedSearch(JsonMapper.shared().readTree(json));
    }

    private void thenItIs(SavedSearch expected) {
        assertThat(this.read).isEqualTo(expected);
    }
}
