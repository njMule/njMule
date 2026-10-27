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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

class RemoteFoldersTest {

    @TempDir
    Path dir;

    private TestGui test;
    private boolean opened;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldTellThatTheFoldersOfARemoteNjmuleAreOnItsComputer() throws Exception {
        givenAWindowOn(false);

        whenTheUserOpensAFolder();

        thenTheFolderOpens(false);
        thenTheUserIsTold("on its own computer");
    }

    @Test
    void shouldOpenTheFoldersOfTheNjmuleOfThisComputer() throws Exception {
        givenAWindowOn(true);

        whenTheUserOpensAFolder();

        thenTheFolderOpens(true);
        thenTheUserIsToldNothing();
    }

    // The engine of this computer, which answers as a remote one would where asked.
    private void givenAWindowOn(boolean thisComputer) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), engine -> (NjMuleEngine) Proxy.newProxyInstance(
                NjMuleEngine.class.getClassLoader(), new Class<?>[] {NjMuleEngine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("onThisComputer")) {
                        return thisComputer;
                    }
                    try {
                        return method.invoke(engine, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
    }

    private void whenTheUserOpensAFolder() throws Exception {
        this.opened = FxTesting.onFx(() -> this.test.gui.opensFoldersHere());
    }

    private void thenTheFolderOpens(boolean expected) {
        assertThat(this.opened).isEqualTo(expected);
    }

    private void thenTheUserIsTold(String part) {
        assertThat(this.test.told).anyMatch(text -> text.contains(part));
    }

    private void thenTheUserIsToldNothing() {
        assertThat(this.test.told).isEmpty();
    }
}
