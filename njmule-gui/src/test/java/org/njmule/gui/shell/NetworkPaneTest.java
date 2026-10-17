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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Screen;

@Timeout(60)
class NetworkPaneTest {

    private NetworkPane pane;

    @Test
    void shouldCountWhatAPartHoldsAfterItsName() throws Exception {
        givenTheNetworkWith("servers", "kad", "log");

        whenThePartCounts("servers", 8);

        thenTheSwitchReads("Servers 8", "Kad", "Log");
    }

    @Test
    void shouldSayNothingOfAPartWhoseCountIsUnknown() throws Exception {
        givenTheNetworkWith("servers", "kad", "log");
        givenThePartCounted("kad", 1243);

        whenThePartCounts("kad", -1);

        thenTheSwitchReads("Servers", "Kad", "Log");
    }

    private void givenTheNetworkWith(String... ids) throws Exception {
        FxTesting.start();
        List<Screen> parts = List.of(ids).stream().map(NetworkPaneTest::part).toList();
        this.pane = FxTesting.onFx(() -> new NetworkPane(new Region(), parts, id -> {}));
        FxTesting.onFx(() -> this.pane.view(ids[0]));
    }

    private void givenThePartCounted(String id, long count) throws Exception {
        whenThePartCounts(id, count);
    }

    private void whenThePartCounts(String id, long count) throws Exception {
        FxTesting.onFx(() -> this.pane.count(id, title(id), count));
    }

    private void thenTheSwitchReads(String... names) throws Exception {
        assertThat(FxTesting.onFx(this.pane::names)).containsExactly(names);
    }

    private static String title(String id) {
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private static Screen part(String id) {
        Node view = new Region();
        return new Screen() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return NetworkPaneTest.title(id);
            }

            @Override
            public Icons icon() {
                return Icons.NETWORK;
            }

            @Override
            public Node view() {
                return view;
            }
        };
    }
}
