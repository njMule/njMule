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
package org.njmule.webui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/**
 * The web interface on the example data of {@link CaptureDemo}, for the pictures of the documentation
 * ({@code website/scripts/capture-web.mjs} starts it through {@code ./gradlew :njmule-webui:webCaptureArgs}). It prints
 * one line of JSON with its address and passwords, then serves until its standard input closes.
 */
final class WebCaptureDemo {

    private WebCaptureDemo() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("njmule-web-capture");
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.udpPort=" + FreePorts.udp()
                        + "\nnet.kad.enabled=false\nsecurity.filterLan=false\nsearch.store=true\nuser.nick=Lupo\n"
                        + "limits.uploadKiBps=250\nlimits.downloadKiBps=0\nnet.upnp=false\nnotify.sound=false\n");
        try (NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs))) {
            engine.updateSchedule(CaptureDemo.schedule()).get(30, TimeUnit.SECONDS);
            Map<String, Engines.Command> commands = new HashMap<>(CaptureDemo.commands(root, engine::preferences));
            // Rates that move, so that the graphs drawn from a second's samples are not flat.
            commands.put("status", a -> {
                double t = System.currentTimeMillis() / 1000.0;
                double wave = Math.sin(t / 37.0) * 0.35 + Math.sin(t / 9.0) * 0.1 + 0.6;
                return CompletableFuture.completedFuture(CaptureDemo.status(
                        (long) (Math.max(0, wave) * 1_400_000),
                        (long) (90_000 + Math.cos(t / 21.0) * 25_000),
                        engine.preferences()));
            });
            DemoEvents events = new DemoEvents(engine.events());
            // A search finds the demo's results a moment after it starts.
            commands.put("search", a -> {
                CompletableFuture.delayedExecutor(400, TimeUnit.MILLISECONDS)
                        .execute(() -> events.tell(
                                new EngineEvent.SearchResults(CaptureDemo.LIVE_SEARCH, CaptureDemo.liveResults())));
                return CompletableFuture.completedFuture(CaptureDemo.LIVE_SEARCH);
            });
            NjMuleEngine shown = Engines.withEvents(Engines.overriding(engine, commands), events);
            if (Boolean.getBoolean("njmule.capture.control")) {
                // The desktop shell's endpoint instead, for the interface of njmule-ui: its address and token.
                try (ControlEndpoint control = ControlEndpoint.start(shown, () -> {})) {
                    System.out.println("{\"url\":\"" + control.url() + "\",\"token\":\"" + control.token() + "\"}");
                    System.out.flush();
                    waitForTheEndOfInput(System.in);
                }
                return;
            }
            String password = secret();
            String guest = secret();
            int port = FreePorts.tcp();
            SecureRandom random = new SecureRandom();
            WebUiSettings settings = new WebUiSettings(
                            true, port, false, Passwords.hash(password.toCharArray(), random), true, List.of())
                    .withGuest(Passwords.hash(guest.toCharArray(), random));
            WebUi web = WebUi.start(shown, settings);
            try {
                System.out.println("{\"url\":\"http://127.0.0.1:" + port + "/\",\"password\":\"" + password
                        + "\",\"guest\":\"" + guest + "\"}");
                System.out.flush();
                waitForTheEndOfInput(System.in);
            } finally {
                web.close();
            }
        } finally {
            try (Stream<Path> files = Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static String secret() {
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void waitForTheEndOfInput(InputStream in) throws IOException {
        while (in.read() >= 0) {
            // Read until the caller closes the stream or stops.
        }
    }

    /**
     * The demo's events for the first listener (the web interface's memory of the log and chats, which subscribes when
     * it starts), then the real engine's events but its log lines, which may name this computer's addresses; and the
     * results of a search, told to every listener as the engine would.
     */
    private static final class DemoEvents implements Flow.Publisher<EngineEvent> {

        private final Flow.Publisher<EngineEvent> real;
        private final List<Flow.Subscriber<? super EngineEvent>> listeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean told = new AtomicBoolean();

        DemoEvents(Flow.Publisher<EngineEvent> real) {
            this.real = real;
        }

        // Each listener hears one event at a time, from the engine or from tell.
        void tell(EngineEvent event) {
            for (Flow.Subscriber<? super EngineEvent> listener : this.listeners) {
                synchronized (listener) {
                    listener.onNext(event);
                }
            }
        }

        @Override
        public void subscribe(Flow.Subscriber<? super EngineEvent> subscriber) {
            this.real.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription s) {
                    this.subscription = s;
                    subscriber.onSubscribe(s);
                    if (!DemoEvents.this.told.getAndSet(true)) {
                        CaptureDemo.events().forEach(subscriber::onNext);
                    }
                    DemoEvents.this.listeners.add(subscriber);
                }

                @Override
                public void onNext(EngineEvent event) {
                    if (event instanceof EngineEvent.Log) {
                        this.subscription.request(1);
                    } else {
                        synchronized (subscriber) {
                            subscriber.onNext(event);
                        }
                    }
                }

                @Override
                public void onError(Throwable error) {
                    DemoEvents.this.listeners.remove(subscriber);
                    subscriber.onError(error);
                }

                @Override
                public void onComplete() {
                    DemoEvents.this.listeners.remove(subscriber);
                    subscriber.onComplete();
                }
            });
        }
    }
}
