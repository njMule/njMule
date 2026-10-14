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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/** The JavaFX toolkit on the headless platform, and helpers to drive it from tests. */
public final class FxTesting {

    private static final Object LOCK = new Object();
    private static boolean started;

    private FxTesting() {}

    /** Starts the toolkit once for all tests. */
    public static void start() throws InterruptedException {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            CountDownLatch ready = new CountDownLatch(1);
            Platform.startup(ready::countDown);
            Platform.setImplicitExit(false);
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the JavaFX toolkit did not start");
            }
            started = true;
        }
    }

    /** Runs {@code task} on the FX thread and waits for its result. */
    public static <T> T onFx(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Exception | AssertionError e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw (Error) e.getCause();
        }
    }

    public static void onFx(Runnable task) throws Exception {
        onFx(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Answers the next dialog that shows with {@code button}, after {@code fill} saw its pane; FX thread, called just
     * before the code that waits for the dialog.
     */
    public static void answer(ButtonType button, Consumer<DialogPane> fill) {
        Platform.runLater(new Runnable() {
            private int tries;

            @Override
            public void run() {
                Optional<DialogPane> pane = Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .map(Window::getScene)
                        .filter(Objects::nonNull)
                        .map(scene -> scene.getRoot().lookup(".dialog-pane"))
                        .filter(DialogPane.class::isInstance)
                        .map(DialogPane.class::cast)
                        .findFirst();
                if (pane.isEmpty()) {
                    if (this.tries++ < 200) {
                        Platform.runLater(this);
                    }
                    return;
                }
                fill.accept(pane.get());
                ((Button) pane.get().lookupButton(button)).fire();
            }
        });
    }

    public static void answer(ButtonType button) {
        answer(button, pane -> {});
    }

    /** The texts the cells of the tables or lists under {@code node} show now; FX thread. */
    public static List<String> cellTexts(Node node) {
        return node.lookupAll(".indexed-cell").stream()
                .map(n -> ((Labeled) n).getText())
                .filter(Objects::nonNull)
                .toList();
    }

    /** Double-clicks the shown row of {@code table} whose item {@code which} accepts; FX thread. */
    public static void doubleClick(TableView<?> table, Predicate<Object> which) {
        for (Node node : table.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && row.getItem() != null && which.test(row.getItem())) {
                row.fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        0,
                        0,
                        MouseButton.PRIMARY,
                        2,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null));
                return;
            }
        }
        throw new AssertionError("no such row shown");
    }

    /** A key pressed, with the shortcut key of the platform (Ctrl, or Cmd on macOS) when {@code shortcut}. */
    public static KeyEvent key(KeyCode code, boolean shortcut) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac);
    }

    /** Waits until {@code condition}, checked on the FX thread, holds. */
    public static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!onFx(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    /** An engine in {@code root} on free ports, without Kad until asked: never on the ports of a running njMule. */
    public static NjMuleEngine engine(Path root) throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        int udp;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            udp = probe.getLocalPort();
        }
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                // Fake servers listen on this machine's LAN address, which must be let in.
                "net.tcpPort=" + port + "\nnet.udpPort=" + udp + "\nnet.kad.enabled=false\nsecurity.filterLan=false\n");
        return NjMuleEngine.start(new EngineConfig(dirs));
    }

    /** Saves a picture of {@code node} as {@code name}.png under target/gui-snapshots. */
    public static void snapshot(Node node, String name) throws IOException {
        String dir = System.getProperty("njmule.snapshots");
        if (dir == null) {
            return;
        }
        WritableImage image = node.snapshot(null, null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        Path target = Files.createDirectories(Path.of(dir)).resolve(name + ".png");
        ImageIO.write(out, "png", target.toFile());
    }
}
