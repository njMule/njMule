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
package org.njmule.gui;

import javafx.application.Application;
import javafx.stage.Stage;
import org.njmule.app.LaunchOptions;

/** The JavaFX application: hands over to {@link DesktopApp}. */
public final class NjMuleFxApp extends Application {

    private DesktopApp app;

    @Override
    public void init() throws Exception {
        this.app =
                DesktopApp.open(
                        LaunchOptions.parse(this.getParameters().getRaw().toArray(String[]::new)));
    }

    @Override
    public void start(Stage stage) {
        this.app.show(stage);
    }

    @Override
    public void stop() {
        if (this.app != null) {
            this.app.close();
        }
    }
}
