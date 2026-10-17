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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FileKindTest {

    private FileKind kind;

    @ParameterizedTest
    @CsvSource({
        "Sintel.2010.1080p.MKV, VIDEO",
        "NASA_Apollo11_restored_audio.flac, AUDIO",
        "cover.png, IMAGE",
        "Divina_Commedia.epub, DOCUMENT",
        "blender-4.5.3-windows-x64.zip, ARCHIVE",
        "ubuntu-24.04.1-desktop-amd64.iso, DISC_IMAGE",
        "LibreOffice_25.8.1_Win_x86-64.msi, PROGRAM",
        "osm-italy-latest.osm.pbf, OTHER",
        "README, OTHER"
    })
    void shouldTellTheKindOfAFileFromItsExtension(String name, FileKind expected) {
        whenTheKindOfIsAsked(name);

        thenTheKindIs(expected);
    }

    private void whenTheKindOfIsAsked(String name) {
        this.kind = FileKind.of(name);
    }

    private void thenTheKindIs(FileKind expected) {
        assertThat(this.kind).isEqualTo(expected);
    }
}
