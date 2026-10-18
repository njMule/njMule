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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.njmule.app.LaunchOptions;
import org.njmule.core.api.NjMuleInfo;

/** The JavaFX application: hands over to {@link DesktopApp}. */
public final class NjMuleFxApp extends Application {

    // The app whose window closed, for the installation of a new version once the toolkit is gone.
    private static final AtomicReference<DesktopApp> CLOSED = new AtomicReference<>();

    private DesktopApp app;
    private IOException failure;

    @Override
    public void init() throws Exception {
        try {
            this.app =
                    DesktopApp.open(LaunchOptions.parse(getParameters().getRaw().toArray(String[]::new)));
        } catch (IOException e) {
            // Shown once the toolkit runs, for instance when njMule is already open.
            this.failure = e;
        }
    }

    @Override
    public void start(Stage stage) {
        if (this.failure != null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, this.failure.getMessage());
            alert.setTitle(NjMuleInfo.NAME);
            alert.setHeaderText(null);
            alert.showAndWait();
            Platform.exit();
            return;
        }
        this.app.show(stage);
    }

    @Override
    public void stop() {
        if (this.app != null) {
            this.app.close();
            CLOSED.set(this.app);
        }
    }

    /** Once the window has closed: hands a new version over to its installer when one is due; the exit code. */
    static int handOver() {
        DesktopApp app = CLOSED.get();
        return app == null ? 0 : app.handOver();
    }
}
