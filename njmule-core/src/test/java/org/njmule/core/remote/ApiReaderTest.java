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
package org.njmule.core.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.PortTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ApiReaderTest {

    private static final PartMap PARTS = ApiSamples.download().parts();

    private Object read;
    private Optional<EngineEvent> event;

    /** What was written, and how to read it back. */
    record Trip(Object sample, Supplier<Object> back) {}

    static Stream<Arguments> samples() {
        return Stream.of(
                trip(
                        "status",
                        ApiSamples.status(true),
                        (g, s) -> ApiJson.status(g, s, empty(), empty()),
                        ApiReader::status),
                trip(
                        "offline status",
                        ApiSamples.status(false),
                        (g, s) -> ApiJson.status(g, s, empty(), empty()),
                        ApiReader::status),
                trip("server", ApiSamples.server(), ApiJson::server, ApiReader::server),
                trip("kad", ApiSamples.kad(), ApiJson::kad, ApiReader::kad),
                trip(
                        "download",
                        ApiSamples.download(),
                        ApiJson::downloadAndParts,
                        n -> ApiReader.download(n, PartMap.EMPTY)),
                trip("source", ApiSamples.source(), ApiJson::source, ApiReader::source),
                trip("upload", ApiSamples.upload(), ApiJson::upload, ApiReader::upload),
                trip("shared file", ApiSamples.shared(), ApiJson::shared, ApiReader::shared),
                trip("shared directory", ApiSamples.directory(), ApiJson::directory, ApiReader::directory),
                trip("search result", ApiSamples.result(), ApiJson::result, ApiReader::result),
                trip("category", ApiSamples.category(), ApiJson::category, ApiReader::category),
                trip(
                        "schedule",
                        ApiSamples.schedule(),
                        (g, s) -> ApiJson.schedule(g, s, Optional.empty()),
                        ApiReader::schedule),
                trip("port test", ApiSamples.portTest(), ApiJson::portTest, ApiReader::portTest),
                trip("friend", ApiSamples.friend(), ApiJson::friend, ApiReader::friend),
                trip("ban", ApiSamples.ban(), ApiJson::ban, ApiReader::ban),
                trip("kad contact", ApiSamples.kadContact(), ApiJson::kadContact, ApiReader::kadContact),
                trip("kad search", ApiSamples.kadSearch(), ApiJson::kadSearch, ApiReader::kadSearch),
                trip("new version", ApiSamples.newVersion(), ApiJson::newVersion, ApiReader::newVersion),
                trip("update", ApiSamples.updateStatus(), ApiJson::update, ApiReader::update),
                trip("import report", ApiSamples.importReport(), ApiJson::importReport, ApiReader::importReport),
                trip(
                        "peer comment",
                        ApiSamples.peerComment(),
                        (g, c) -> ApiJson.comment(g, c.user(), c.fileName(), c.comment()),
                        ApiReader::peerComment),
                trip(
                        "kad note",
                        ApiSamples.kadNote(),
                        (g, n) -> ApiJson.comment(g, n.publisher(), n.fileName(), n.comment()),
                        ApiReader::kadNote));
    }

    // Those whose data needs another call (the preferences, a CAPTCHA's picture) come by other ways.
    static Stream<EngineEvent> events() {
        return ApiSamples.events().stream()
                .filter(e ->
                        !(e instanceof EngineEvent.PreferencesChanged) && !(e instanceof EngineEvent.CaptchaRequested));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void shouldReadBackWhatTheApiWrites(String what, Trip trip) {
        whenReadBack(trip);

        thenItIs(trip.sample());
    }

    @ParameterizedTest
    @MethodSource("events")
    void shouldReadBackTheEventsOfTheStream(EngineEvent sample) {
        whenTheEventIsReadBack(sample);

        thenTheEventIs(sample);
    }

    @Test
    void shouldTakeTheRouterAsNeverAskedInTheStatusOfAnOlderNjMule() {
        whenTheStatusIsReadWithout("forwarding", "router");

        thenTheRouterIsNeverAsked();
    }

    private void whenTheStatusIsReadWithout(String... names) {
        ObjectNode status =
                (ObjectNode) tree(ApiJson.write(g -> ApiJson.status(g, ApiSamples.status(true), empty(), empty())));
        status.remove(List.of(names));
        this.read = ApiReader.status(status);
    }

    private void whenReadBack(Trip trip) {
        this.read = trip.back().get();
    }

    private void whenTheEventIsReadBack(EngineEvent sample) {
        JsonNode data = tree(ApiJson.write(g -> ApiJson.event(g, sample)));
        this.event = ApiReader.event(ApiJson.eventName(sample), data, PARTS);
    }

    private void thenItIs(Object expected) {
        assertThat(this.read).isEqualTo(expected);
    }

    private void thenTheRouterIsNeverAsked() {
        EngineStatus status = (EngineStatus) this.read;
        assertThat(status.forwarding()).isEqualTo(PortTest.Forwarding.OFF);
        assertThat(status.router()).isEmpty();
    }

    private void thenTheEventIs(EngineEvent expected) {
        assertThat(this.event).hasValue(expected);
    }

    private static <T> Arguments trip(String what, T sample, ApiJson.Writer<T> writer, Function<JsonNode, T> reader) {
        return Arguments.of(
                what, new Trip(sample, () -> reader.apply(tree(ApiJson.write(g -> writer.write(g, sample))))));
    }

    private static JsonNode tree(byte[] json) {
        return JsonMapper.shared().readTree(json);
    }

    private static OptionalLong empty() {
        return OptionalLong.empty();
    }
}
