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

import java.util.concurrent.CompletableFuture;
import javafx.application.Application;
import org.njmule.app.LaunchOptions;
import org.njmule.app.Main;

/**
 * Desktop entry point. Informational options (--version, --license, ...), {@code --headless} runs
 * and one-shot commands (a search, a download, an import) go to the headless launcher; a plain
 * start opens the window.
 */
public final class GuiMain {

    private GuiMain() {}

    public static void main(String[] args) {
        LaunchOptions cli;
        try {
            cli = LaunchOptions.parse(args);
        } catch (LaunchOptions.UsageException e) {
            System.exit(Main.run(args, System.out, System.err, new CompletableFuture<>()));
            return;
        }
        if (cli.action() != LaunchOptions.Action.RUN
                || cli.headless()
                || cli.network().isOneShot()) {
            System.exit(Main.run(args, System.out, System.err, new CompletableFuture<>()));
        }
        if (MacMainThread.needed()) {
            MacMainThread.run(() -> Application.launch(NjMuleFxApp.class, args));
            return;
        }
        Application.launch(NjMuleFxApp.class, args);
    }
}
