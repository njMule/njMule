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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
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
