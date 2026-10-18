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
package org.njmule.core.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UpdateManifestTest {

    static final String SHA256 = "0123456789abcdef".repeat(4);

    private UpdateManifest manifest;
    private Optional<UpdateManifest.Asset> asset;
    private Throwable exception;

    @Test
    void shouldReadTheReleaseAndItsPackages() {
        whenRead(manifest("0.2.0", true, msi("njmule-0.2.0-windows-x64.msi")));

        thenTheVersionIs("0.2.0");
        thenItWasPublished("2026-11-02T10:00:00Z");
        thenItIsCritical(true);
        thenTheNotesAre("### Added");
        thenThereArePackages(1);
    }

    @Test
    void shouldFindThePackageOfAPlatformAndKind() {
        givenRead(manifest(
                "0.2.0",
                false,
                msi("njmule-0.2.0-windows-x64.msi"),
                asset("linux-x64", "deb", "njmule_0.2.0_amd64.deb")));

        whenThePackageIsLookedUp("linux-x64", "deb");

        thenThePackageIs("njmule_0.2.0_amd64.deb");
    }

    @Test
    void shouldFindNoPackageForAnotherPlatform() {
        givenRead(manifest("0.2.0", false, msi("njmule-0.2.0-windows-x64.msi")));

        whenThePackageIsLookedUp("windows-arm64", "msi");

        thenThereIsNoPackage();
    }

    @Test
    void shouldLeaveOutPackagesOfAKindThisVersionDoesNotKnowTheFormOf() {
        whenRead(manifest(
                "0.2.0",
                false,
                msi("njmule-0.2.0-windows-x64.msi"),
                asset("windows-x64", "Some New Kind", "njmule-0.2.0.msix")));

        thenThereArePackages(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../evil.exe", "a/b.msi", "..", "name with spaces.msi", ""})
    void shouldRefuseAPackageNameThatIsNoPlainFileName(String name) {
        whenRead(manifest("0.2.0", false, asset("windows-x64", "msi", name)));

        thenExceptionOccurred("asset");
    }

    @Test
    void shouldRefuseAPackageWithoutAValidHash() {
        whenRead(manifest("0.2.0", false, msi("njmule.msi").replace(SHA256, "not-a-hash")));

        thenExceptionOccurred("asset");
    }

    @Test
    void shouldRefuseInformationOfAnotherSchema() {
        whenRead(manifest("0.2.0", false).replace("\"schema\": 1", "\"schema\": 2"));

        thenExceptionOccurred("schema");
    }

    @Test
    void shouldRefuseInformationWithoutAValidVersion() {
        whenRead(manifest("two", false));

        thenExceptionOccurred("version");
    }

    @Test
    void shouldRefuseTextThatIsNoJson() {
        whenRead("<html>Not Found</html>");

        thenExceptionOccurred("malformed");
    }

    @Test
    void shouldRefuseInformationLargerThanAnyRelease() {
        whenRead(manifest("0.2.0", false).replace("### Added", "x".repeat(UpdateManifest.MAX_SIZE)));

        thenExceptionOccurred("too large");
    }

    private void givenRead(String json) {
        whenRead(json);
        assertThat(this.exception).isNull();
    }

    private void whenRead(String json) {
        this.exception =
                catchThrowable(() -> this.manifest = UpdateManifest.parse(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void whenThePackageIsLookedUp(String platform, String kind) {
        this.asset = this.manifest.asset(platform, kind);
    }

    private void thenTheVersionIs(String expected) {
        assertThat(this.manifest.version().getVersion()).isEqualTo(expected);
    }

    private void thenItWasPublished(String expected) {
        assertThat(this.manifest.published()).isEqualTo(Instant.parse(expected));
    }

    private void thenItIsCritical(boolean expected) {
        assertThat(this.manifest.critical()).isEqualTo(expected);
    }

    private void thenTheNotesAre(String expected) {
        assertThat(this.manifest.notes()).isEqualTo(expected);
    }

    private void thenThereArePackages(int count) {
        assertThat(this.manifest.assets()).hasSize(count);
    }

    private void thenThePackageIs(String name) {
        assertThat(this.asset).map(UpdateManifest.Asset::name).contains(name);
    }

    private void thenThereIsNoPackage() {
        assertThat(this.asset).isEmpty();
    }

    private void thenExceptionOccurred(String message) {
        assertThat(this.exception).isInstanceOf(IOException.class).hasMessageContaining(message);
    }

    /** The manifest of a release with {@code assets}, as {@link #asset} writes them. */
    static String manifest(String version, boolean critical, String... assets) {
        return """
                {
                  "schema": 1,
                  "version": "%s",
                  "published": "2026-11-02T10:00:00Z",
                  "critical": %s,
                  "notes": "### Added",
                  "assets": [%s]
                }
                """.formatted(version, critical, String.join(",", assets));
    }

    static String asset(String platform, String kind, String name) {
        return """
                {"platform": "%s", "kind": "%s", "name": "%s", "size": 31719424, "sha256": "%s"}
                """.formatted(platform, kind, name, SHA256);
    }

    static String msi(String name) {
        return asset("windows-x64", "msi", name);
    }
}
