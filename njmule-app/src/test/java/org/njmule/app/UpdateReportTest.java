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
package org.njmule.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.api.InstallKind;

class UpdateReportTest {

    private static final String RELEASE = "https://github.com/njMule/njMule/releases/download/v0.2.0/";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private UpdateReport report;

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "MSI | njmule-0.2.0-windows-x64.msi | Download and install " + RELEASE + "njmule-0.2.0-windows-x64.msi",
                "DEB | njmule_0.2.0_amd64.deb | sudo apt install ./njmule_0.2.0_amd64.deb",
                "RPM | njmule-0.2.0-1.x86_64.rpm | sudo dnf install ./njmule-0.2.0-1.x86_64.rpm",
                "SYNOLOGY | njmule-0.2.0-synology-x86_64.spk | Package Center with Manual Install",
                "DOCKER | njmule-0.2.0-linux-x64.tar.xz | docker compose pull"
            })
    void shouldTellHowToInstallTheNewVersionByTheWayThisOneWasInstalled(
            InstallKind installation, String file, String advice) {
        givenANewVersionFor(installation, file);

        whenPrinted();

        thenTheOutputSays("njMule 0.2.0 is available (this is 0.1.0).", advice);
    }

    @Test
    void shouldSendACopyBuiltFromTheSourcesToThePageOfTheRelease() {
        givenANewVersionWithoutPackage();

        whenPrinted();

        thenTheOutputSays("Download and install https://github.com/njMule/njMule/releases/tag/v0.2.0");
    }

    @Test
    void shouldSayThatThisIsTheNewestVersion() {
        givenNoNewerVersion(Optional.empty());

        whenPrinted();

        thenTheOutputSays("njMule 0.1.0 is the newest version.");
    }

    @Test
    void shouldSayThatItHasNotLookedYet() {
        givenNoCheckYet();

        whenPrinted();

        thenTheOutputSays("njMule 0.1.0 has not looked for a new version yet.");
    }

    @Test
    void shouldTellWhyTheCheckFailed() {
        givenNoNewerVersion(Optional.of("HTTP 503 from github.com"));

        whenPrinted();

        thenTheErrorsSay("Cannot check for a new version: HTTP 503 from github.com");
    }

    private void givenANewVersionFor(InstallKind installation, String file) {
        this.report = new UpdateReport(
                "0.1.0",
                installation,
                true,
                Optional.empty(),
                Optional.of("0.2.0"),
                Optional.of(URI.create("https://github.com/njMule/njMule/releases/tag/v0.2.0")),
                Optional.of(URI.create(RELEASE + file)));
    }

    private void givenANewVersionWithoutPackage() {
        this.report = new UpdateReport(
                "0.1.0",
                InstallKind.UNKNOWN,
                true,
                Optional.empty(),
                Optional.of("0.2.0"),
                Optional.of(URI.create("https://github.com/njMule/njMule/releases/tag/v0.2.0")),
                Optional.empty());
    }

    private void givenNoNewerVersion(Optional<String> failure) {
        this.report = new UpdateReport(
                "0.1.0", InstallKind.MSI, true, failure, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private void givenNoCheckYet() {
        this.report = new UpdateReport(
                "0.1.0",
                InstallKind.MSI,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private void whenPrinted() {
        this.report.print(new PrintStream(this.out, true, UTF_8), new PrintStream(this.err, true, UTF_8));
    }

    private void thenTheOutputSays(String... texts) {
        assertThat(this.out.toString(UTF_8)).contains(texts);
    }

    private void thenTheErrorsSay(String text) {
        assertThat(this.err.toString(UTF_8)).contains(text);
    }
}
