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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.gui.infra.GuiSettings;

class UrlBoxTest {

    private byte[] data;
    private List<String> history;

    @Test
    void shouldReadEmuleHistoriesAsUtf16AfterAByteOrderMark() {
        givenAHistoryFile(
                "\uFEFFhttp://a.example/server.met\r\nhttp://b.example/server.met\r\n\r\n"
                        .concat("http://a.example/server.met\r\n"),
                StandardCharsets.UTF_16LE);

        whenTheEmuleHistoryIsRead();

        thenTheHistoryIs("http://a.example/server.met", "http://b.example/server.met");
    }

    @Test
    void shouldReadNothingFromAHistoryWithoutAByteOrderMark() {
        givenAHistoryFile("http://a.example/", StandardCharsets.US_ASCII);

        whenTheEmuleHistoryIsRead();

        thenTheHistoryIsEmpty();
    }

    private void givenAHistoryFile(String text, Charset charset) {
        this.data = text.getBytes(charset);
    }

    private void whenTheEmuleHistoryIsRead() {
        this.history = GuiSettings.emuleHistory(this.data);
    }

    private void thenTheHistoryIs(String... urls) {
        assertThat(this.history).containsExactly(urls);
    }

    private void thenTheHistoryIsEmpty() {
        assertThat(this.history).isEmpty();
    }
}
