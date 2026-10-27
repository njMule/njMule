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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Application;
import org.njmule.app.LaunchOptions;
import org.njmule.app.LinkHandoff;
import org.njmule.app.Main;
import org.njmule.app.Updating;
import org.njmule.core.api.Ed2kLink;

/**
 * Desktop entry point. Informational options (--version, --license, ...), {@code --headless} runs and one-shot commands
 * (a search, a download, an import) go to the headless launcher; a plain start opens the window.
 */
public final class GuiMain {

    private GuiMain() {}

    public static void main(String[] args) {
        // On Windows the native app is njmule.exe: run from a console, not by njmule-gui.exe, it is the command line.
        boolean windows = WindowsDesktop.applies();
        if (windows && !WindowsDesktop.startedForDesktop(System.getenv())) {
            Main.main(args);
            return;
        }
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
        // A start after an update waits for the njMule it replaces: its lock and its links are free only then.
        Updating.awaitEnd(cli.waitFor());
        // A running njMule takes the links, or just shows its window, instead of failing on its lock.
        List<String> links = cli.links().stream().map(Ed2kLink::toUri).toList();
        LinkHandoff.Taker taker = cli.remoteDesktop().isPresent()
                ? LinkHandoff.Taker.NONE
                : LinkHandoff.handOver(DesktopApp.configDir(cli), links);
        if (taker == LinkHandoff.Taker.WINDOW) {
            System.exit(0);
        }
        // A headless one has no window to show: the user is told instead of seeing nothing happen.
        if (taker == LinkHandoff.Taker.HEADLESS) {
            NjMuleFxApp.headlessRuns(!links.isEmpty());
        }
        if (LinuxProcessName.applies()) {
            LinuxProcessName.nameThisThread();
        }
        if (windows) {
            WindowsDesktop.useAppId();
        }
        // On macOS links come as events; the handler is set first, on the thread that starts JavaFX.
        if (MacMainThread.needed()) {
            MacMainThread.run(() -> {
                MacLinks.install();
                Application.launch(NjMuleFxApp.class, args);
                NjMuleFxApp.handOver();
            });
            return;
        }
        MacLinks.install();
        Application.launch(NjMuleFxApp.class, args);
        // The window has closed and the engine stopped: a new version goes in place now, if one is due. The
        // notification area keeps AWT threads alive.
        System.exit(NjMuleFxApp.handOver());
    }
}
