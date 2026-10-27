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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.PortTest;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.upnp.FakeGateway;

/** UPnP through the engine, with a fake router on loopback: the real one is never asked. */
@Timeout(60)
class UpnpTest {

    private static final Pattern EXTERNAL_PORT = Pattern.compile("<NewExternalPort>(\\d+)<");

    private static final String IP = "urn:schemas-upnp-org:service:WANIPConnection:1";

    @TempDir
    Path dir;

    private FakeGateway gateway;
    private AppDirectories dirs;
    private Engine engine;
    private int secondPort;

    @AfterEach
    void stopTheRouter() throws IOException {
        if (this.engine != null) {
            this.engine.close();
        }
        if (this.gateway != null) {
            this.gateway.close();
        }
    }

    @Test
    void shouldTellInTheStatusThatTheRouterForwardsThePorts() throws Exception {
        givenARouter();

        whenAnEngineStartsWithUpnpOn();

        thenTheStatusTellsTheRouterAnswered(PortTest.Forwarding.FORWARDED);
    }

    @Test
    void shouldTellInTheStatusThatUpnpIsOffOnceTurnedOff() throws Exception {
        givenARouter();
        givenAnEngineThatMappedItsPortOnTheRouter();

        whenUpnpIsTurnedOff();

        thenTheStatusTellsTheRouterAnswered(PortTest.Forwarding.OFF);
    }

    @Test
    void shouldSaveTheFilesAtTheEndBeforeTheRouterIsAsked() throws Exception {
        givenARouter();
        // A router that takes long to answer must not hold the saves back.
        givenTheRouterTakesSecondsToDeleteAMapping(8);
        givenAnEngineThatMappedItsPortOnTheRouter();

        whenTheEngineCloses();

        thenTheServerListWasSavedBeforeTheRouterWasAskedToDeleteTheMapping();
    }

    @Test
    void shouldLeaveNoMappingBehindWhenThePortsChangeTwiceInARow() throws Exception {
        givenARouter();
        givenAnEngineThatMappedItsPortOnTheRouter();
        givenTheTcpPortIsChangedTwiceInARow();
        givenTheRouterIsAskedToMapTheSecondPort();

        whenTheEngineClosesAndASecondPasses();

        thenEveryPortMappedIsUnmappedAfterwards();
    }

    private void givenARouter() throws IOException {
        this.gateway = FakeGateway.start(IP, "/ctl");
    }

    private void givenTheRouterTakesSecondsToDeleteAMapping(int seconds) {
        this.gateway.delayDeletes(Duration.ofSeconds(seconds));
    }

    private void givenAnEngineThatMappedItsPortOnTheRouter() throws Exception {
        this.dirs = dirs();
        this.engine = Engine.start(new EngineConfig(this.dirs), () -> false, Optional.of(this.gateway.ssdp()));
        assertThat(await(() -> this.gateway.actions("AddPortMapping").size() == 1))
                .isTrue();
    }

    private void givenTheTcpPortIsChangedTwiceInARow() throws Exception {
        int first = FreePorts.tcp();
        int second = FreePorts.tcp();
        this.secondPort = second;
        CompletableFuture<Preferences> one =
                this.engine.updatePreferences(p -> p.toBuilder().tcpPort(first).build());
        CompletableFuture<Preferences> two =
                this.engine.updatePreferences(p -> p.toBuilder().tcpPort(second).build());
        one.get(10, TimeUnit.SECONDS);
        two.get(10, TimeUnit.SECONDS);
    }

    private void givenTheRouterIsAskedToMapTheSecondPort() throws InterruptedException {
        assertThat(await(() -> this.gateway.actions("AddPortMapping").stream()
                        .anyMatch(a -> a.body().contains(">" + this.secondPort + "<"))))
                .isTrue();
        Thread.sleep(1_000);
    }

    private void whenAnEngineStartsWithUpnpOn() throws IOException {
        this.dirs = dirs();
        this.engine = Engine.start(new EngineConfig(this.dirs), () -> false, Optional.of(this.gateway.ssdp()));
    }

    private void whenUpnpIsTurnedOff() throws Exception {
        this.engine.updatePreferences(p -> p.toBuilder().upnp(false).build()).get(10, TimeUnit.SECONDS);
    }

    private void whenTheEngineCloses() {
        this.engine.close();
    }

    private void whenTheEngineClosesAndASecondPasses() throws InterruptedException {
        this.engine.close();
        Thread.sleep(1_000);
    }

    private void thenTheServerListWasSavedBeforeTheRouterWasAskedToDeleteTheMapping() throws IOException {
        FakeGateway.Action delete = this.gateway.actions("DeletePortMapping").getFirst();
        Instant saved = Files.getLastModifiedTime(this.dirs.config().resolve("server.met"))
                .toInstant();
        assertThat(saved).isBefore(delete.at().plusSeconds(1));
    }

    // The router is named once it answered; never while UPnP is off.
    private void thenTheStatusTellsTheRouterAnswered(PortTest.Forwarding answer) throws InterruptedException {
        assertThat(await(() -> {
                    EngineStatus status = this.engine.status().join();
                    return status.forwarding() == answer
                            && status.router().isEmpty() == (answer == PortTest.Forwarding.OFF);
                }))
                .as("the status tells %s", answer)
                .isTrue();
    }

    private void thenEveryPortMappedIsUnmappedAfterwards() {
        List<FakeGateway.Action> adds = this.gateway.actions("AddPortMapping");
        for (FakeGateway.Action add : adds) {
            String port = externalPort(add.body());
            assertThat(this.gateway.actions("DeletePortMapping"))
                    .as("port %s is unmapped after it was mapped", port)
                    .anyMatch(d -> d.nanos() > add.nanos() && d.body().contains(">" + port + "<"));
        }
    }

    private AppDirectories dirs() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + FreePorts.tcp() + "\nnet.kad.enabled=false\nnet.upnp=true\n");
        return dirs;
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    // The external port an AddPortMapping asked for.
    private static String externalPort(String body) {
        Matcher port = EXTERNAL_PORT.matcher(body);
        assertThat(port.find()).as("an external port in %s", body).isTrue();
        return port.group(1);
    }
}
