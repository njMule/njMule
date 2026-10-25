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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RemoteDialogTest {

    private Optional<URI> api;

    @ParameterizedTest
    @CsvSource({
        "nas:4711, http://nas:4711/api/v1",
        "192.168.1.10:4711/, http://192.168.1.10:4711/api/v1",
        "https://njmule.example.org/api/v1, https://njmule.example.org/api/v1",
        "http://nas:4711/njmule/api/v1, http://nas:4711/njmule/api/v1"
    })
    void shouldCompleteTheAddressOfTheApiAsTyped(String typed, String expected) {
        whenTheAddressIsRead(typed);

        thenTheApiIs(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "ftp://nas/api/v1", "http://"})
    void shouldRefuseWhatIsNoAddressOfAWebInterface(String typed) {
        whenTheAddressIsRead(typed);

        thenThereIsNoApi();
    }

    private void whenTheAddressIsRead(String typed) {
        this.api = RemoteDialog.api(typed);
    }

    private void thenTheApiIs(String expected) {
        assertThat(this.api).hasValue(URI.create(expected));
    }

    private void thenThereIsNoApi() {
        assertThat(this.api).isEmpty();
    }
}
