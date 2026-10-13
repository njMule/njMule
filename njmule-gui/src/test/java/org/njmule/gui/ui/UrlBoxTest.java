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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UrlBoxTest {

    @Test
    void eMuleHistoriesAreUtf16AfterAByteOrderMark() {
        byte[] data = "\uFEFFhttp://a.example/server.met\r\nhttp://b.example/server.met\r\n\r\n"
                .concat("http://a.example/server.met\r\n")
                .getBytes(StandardCharsets.UTF_16LE);

        assertThat(UrlBox.emuleHistory(data))
                .containsExactly("http://a.example/server.met", "http://b.example/server.met");
        assertThat(UrlBox.emuleHistory("http://a.example/".getBytes(StandardCharsets.US_ASCII)))
                .isEmpty();
    }
}
