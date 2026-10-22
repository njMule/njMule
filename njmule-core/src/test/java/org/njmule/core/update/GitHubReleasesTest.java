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

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.njmule.core.config.UpdateChannel;
import org.semver4j.Semver;

class GitHubReleasesTest {

    private static final String TEST_VERSION = FakeWeb.REPOSITORY + "/releases/download/v0.2.0-beta.1/";

    private final FakeWeb web = new FakeWeb();
    private final TestSigning signer = new TestSigning();
    private GitHubReleases.Signed fetched;
    private URI file;
    private URI page;

    @Test
    void shouldTakeTheNewestReleaseOnTheStableChannel() throws IOException {
        givenTheNewestReleaseIs("0.1.0");

        whenTheNewestIsFetched(UpdateChannel.STABLE);

        thenTheManifestOfIs("0.1.0");
        thenTheSignatureCame();
        thenTheListOfReleasesWasNotAsked();
    }

    @Test
    void shouldTakeTheNewestSignedTestVersionOnTheTestChannel() throws IOException {
        givenTheNewestReleaseIs("0.1.0");
        givenTheListOfReleases("""
                [
                  {"tag_name": "v0.3.0-alpha.1", "draft": false, "assets": [{"name": "njmule-0.3.0-alpha.1.msi"}]},
                  {"tag_name": "v0.2.0", "draft": true, "assets": %s},
                  {"tag_name": "v0.2.0-beta.1", "draft": false, "assets": %s},
                  {"tag_name": "v0.1.0", "draft": false, "assets": %s},
                  {"tag_name": "nightly", "draft": false, "assets": %s}
                ]
                """.formatted(signedAssets(), signedAssets(), signedAssets(), signedAssets()));
        givenTheRelease(TEST_VERSION, "0.2.0-beta.1");

        whenTheNewestIsFetched(UpdateChannel.PRERELEASE);

        thenTheManifestOfIs("0.2.0-beta.1");
    }

    @Test
    void shouldTakeTheNewestReleaseWhenTheListOfReleasesCannotBeHad() throws IOException {
        givenTheNewestReleaseIs("0.1.0");

        whenTheNewestIsFetched(UpdateChannel.PRERELEASE);

        thenTheManifestOfIs("0.1.0");
    }

    @Test
    void shouldNameTheFilesAndThePageOfARelease() {
        whenTheAddressesOfTheReleaseAreNamed("0.2.0", "njmule-0.2.0-windows-x64.msi");

        thenTheFileIsAt(FakeWeb.REPOSITORY + "/releases/download/v0.2.0/njmule-0.2.0-windows-x64.msi");
        thenThePageIsAt(FakeWeb.REPOSITORY + "/releases/tag/v0.2.0");
    }

    private void givenTheNewestReleaseIs(String version) {
        givenTheRelease(FakeWeb.LATEST, version);
    }

    private void givenTheRelease(String base, String version) {
        this.web.serveRelease(base, UpdateManifestTest.manifest(version, false), this.signer);
    }

    private void givenTheListOfReleases(String json) {
        this.web.serve(FakeWeb.LISTING, json);
    }

    private void whenTheNewestIsFetched(UpdateChannel channel) throws IOException {
        this.fetched = this.web.releases().latest(channel);
    }

    private void whenTheAddressesOfTheReleaseAreNamed(String version, String file) {
        GitHubReleases releases = this.web.releases();
        this.file = releases.download(Semver.parse(version), file);
        this.page = releases.page(Semver.parse(version));
    }

    private void thenTheManifestOfIs(String version) throws IOException {
        assertThat(UpdateManifest.parse(this.fetched.manifest().toByteArray())
                        .version()
                        .getVersion())
                .isEqualTo(version);
    }

    private void thenTheSignatureCame() {
        assertThat(this.fetched.signature()).startsWith("untrusted comment:");
    }

    private void thenTheListOfReleasesWasNotAsked() {
        assertThat(this.web.asked()).doesNotContain(URI.create(FakeWeb.LISTING));
    }

    private void thenTheFileIsAt(String expected) {
        assertThat(this.file).hasToString(expected);
    }

    private void thenThePageIsAt(String expected) {
        assertThat(this.page).hasToString(expected);
    }

    private static String signedAssets() {
        return "[{\"name\": \"njmule-0.2.0-windows-x64.msi\"}, {\"name\": \"" + UpdateManifest.FILE_NAME
                + "\"}, {\"name\": \"" + UpdateManifest.FILE_NAME + GitHubReleases.SIGNATURE_SUFFIX + "\"}]";
    }
}
