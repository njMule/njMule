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
import static org.assertj.core.api.Assertions.catchThrowable;

import io.openapiparser.OpenApiParser;
import io.openapiparser.OpenApiResult;
import io.openapiparser.OpenApiVersion;
import io.openapiparser.ValidationErrorTextBuilder;
import io.openapiprocessor.jackson3.JacksonConverter;
import io.openapiprocessor.jsonschema.reader.UriReader;
import io.openapiprocessor.jsonschema.schema.DocumentLoader;
import io.openapiprocessor.jsonschema.schema.DocumentStore;
import io.openapiprocessor.jsonschema.schema.SchemaStore;
import io.openapiprocessor.jsonschema.validator.Validator;
import io.openapiprocessor.jsonschema.validator.ValidatorSettings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SourceInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import tools.jackson.databind.JsonNode;

/** The OpenAPI document of the API: a valid OpenAPI 3.2 description that matches the engine and the code. */
class ApiSpecificationTest {

    private static final ApiSpecification SHIPPED = ApiSpecification.load();

    @TempDir
    Path dir;

    private String yaml = new String(SHIPPED.yaml(), StandardCharsets.UTF_8);
    private List<String> found = List.of();
    private Optional<ApiSpecification.Route> route = Optional.empty();
    private Throwable exception;

    @Test
    void shouldBeAValidOpenApi32Description() throws Exception {
        whenTheDocumentIsValidatedAgainstTheOpenApiSchemas();

        thenItIsVersion("3.2.0");
        thenNothingWasFound();
    }

    @Test
    void shouldHaveAHandlerForEveryOperation() {
        whenAnApiIsMade();

        thenNoExceptionOccurred();
    }

    static Stream<Arguments> enums() {
        return Stream.of(
                Arguments.of("/components/schemas/DownloadPriority/enum", DownloadPriority.class),
                Arguments.of("/components/schemas/UploadPriority/enum", UploadPriority.class),
                Arguments.of("/components/schemas/SearchScope/enum", SearchRequest.Scope.class),
                Arguments.of("/components/schemas/Download/properties/status/enum", DownloadInfo.Status.class),
                Arguments.of("/components/schemas/Server/properties/priority/enum", ServerInfo.Priority.class),
                Arguments.of("/components/schemas/Kad/properties/buddy/enum", KadInfo.Buddy.class),
                Arguments.of("/components/schemas/Source/properties/state/enum", SourceInfo.State.class),
                Arguments.of("/components/schemas/Upload/properties/state/enum", UploadInfo.State.class),
                Arguments.of("/components/schemas/KadSearch/properties/kind/enum", KadSearchInfo.Kind.class),
                Arguments.of("/components/schemas/LogLine/properties/level/enum", LogLine.Level.class));
    }

    @ParameterizedTest
    @MethodSource("enums")
    void shouldListTheValuesOfAnEnumOfTheEngine(String pointer, Class<? extends Enum<?>> type) {
        whenTheValuesAreRead(pointer);

        thenTheyAreTheLowerCaseNamesOf(type);
    }

    @Test
    void shouldDescribeEveryEventOfTheStream() {
        whenTheEventNamesAreRead();

        thenTheyAreTheNamesOfTheSamplesAnd("lost");
    }

    @Test
    void shouldCapTheSpeedLimitsAsThePreferencesDo() {
        whenTheValuesAreRead("/components/schemas/Limits/properties/uploadKiBps/maximum");

        thenTheyAre(String.valueOf(Preferences.MAX_RATE_KIBPS));
    }

    @Test
    void shouldServeUnderThePrefixOfTheApi() {
        whenTheValuesAreRead("/servers/0/url");

        thenTheOnlyValueEndsWith(RemoteApi.PREFIX);
    }

    @Test
    void shouldHaveTheVersionOfTheApi() {
        whenTheValuesAreRead("/info/version");

        thenTheyAre(String.valueOf(RemoteApi.VERSION));
    }

    @Test
    void shouldMatchAConcretePathBeforeATemplatedOne() {
        whenARequestIsRouted("servers", "connect");

        thenItGoesTo("/servers/connect");
    }

    @Test
    void shouldMatchATemplatedPath() {
        whenARequestIsRouted("servers", "203.0.113.10:4661");

        thenItGoesTo("/servers/{address}");
    }

    @Test
    void shouldRefuseADocumentWithParametersItCannotCheck() {
        givenTheDocumentChanged("in: query", "in: header");

        whenTheDocumentIsRead();

        thenExceptionOccurred(IllegalStateException.class, "not supported");
    }

    @Test
    void shouldRefuseAnOperationWithoutId() {
        givenTheDocumentChanged("operationId: getStatus", "summary: no id");

        whenTheDocumentIsRead();

        thenExceptionOccurred(IllegalStateException.class, "has no operationId");
    }

    @Test
    void shouldRefuseAnOperationIdUsedTwice() {
        givenTheDocumentChanged("operationId: getLimits", "operationId: getStatus");

        whenTheDocumentIsRead();

        thenExceptionOccurred(IllegalStateException.class, "used twice");
    }

    // Given

    private void givenTheDocumentChanged(String from, String to) {
        assertThat(this.yaml).contains(from);
        this.yaml = this.yaml.replace(from, to);
    }

    // When

    private void whenTheDocumentIsValidatedAgainstTheOpenApiSchemas() throws Exception {
        DocumentLoader loader = new DocumentLoader(new UriReader(), new JacksonConverter());
        OpenApiParser parser = new OpenApiParser(new DocumentStore(), loader);
        OpenApiResult result = parser.parse(
                URI.create("https://njmule.invalid/api/v1/openapi.yaml"), new JacksonConverter().convert(this.yaml));
        assertThat(result.getVersion()).isEqualTo(OpenApiVersion.V32);
        result.validate(new Validator(new ValidatorSettings()), new SchemaStore(loader));
        ValidationErrorTextBuilder text = new ValidationErrorTextBuilder();
        this.found = result.getValidationErrors().stream().map(text::getText).toList();
    }

    private void whenAnApiIsMade() {
        FakeEngine fake = new FakeEngine();
        fake.value("directories", AppDirectories.underRoot(this.dir, true));
        this.exception = catchThrowable(() -> {
            try (RemoteApi api = new RemoteApi(
                    fake.engine(), RecentEvents.follow(fake.engine()), client -> true, Optional.empty())) {
                assertThat(api).isNotNull();
            }
        });
    }

    private void whenTheValuesAreRead(String pointer) {
        JsonNode node = SHIPPED.document().at(pointer);
        assertThat(node.isMissingNode()).as(pointer).isFalse();
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.values().forEach(v -> values.add(v.asString()));
        } else {
            values.add(node.asString());
        }
        this.found = values;
    }

    private void whenTheEventNamesAreRead() {
        List<String> names = new ArrayList<>();
        for (JsonNode kind :
                SHIPPED.document().at("/components/schemas/EngineEvent/oneOf").values()) {
            String target = kind.get("$ref").asString().substring(1);
            names.add(SHIPPED.document().at(target + "/properties/event/const").asString());
        }
        this.found = names;
    }

    private void whenARequestIsRouted(String... segments) {
        this.route = SHIPPED.route(List.of(segments));
    }

    private void whenTheDocumentIsRead() {
        this.exception = catchThrowable(() -> ApiSpecification.parse(this.yaml.getBytes(StandardCharsets.UTF_8)));
    }

    // Then

    private void thenItIsVersion(String version) {
        assertThat(SHIPPED.document().path("openapi").asString()).isEqualTo(version);
    }

    private void thenNothingWasFound() {
        assertThat(this.found).isEmpty();
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheyAreTheLowerCaseNamesOf(Class<? extends Enum<?>> type) {
        assertThat(this.found)
                .containsExactlyElementsOf(Arrays.stream(type.getEnumConstants())
                        .map(e -> e.name().toLowerCase(Locale.ROOT))
                        .toList());
    }

    private void thenTheyAreTheNamesOfTheSamplesAnd(String more) {
        List<String> names = new ArrayList<>(
                ApiSamples.events().stream().map(ApiJson::eventName).toList());
        names.add(more);
        assertThat(this.found).containsExactlyInAnyOrderElementsOf(names);
    }

    private void thenTheyAre(String... values) {
        assertThat(this.found).containsExactly(values);
    }

    private void thenTheOnlyValueEndsWith(String end) {
        assertThat(this.found).singleElement().asString().endsWith(end);
    }

    private void thenItGoesTo(String path) {
        assertThat(this.route).map(ApiSpecification.Route::path).hasValue(path);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String message) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(message);
    }
}
