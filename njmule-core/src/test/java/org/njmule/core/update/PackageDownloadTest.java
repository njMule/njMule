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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.NewVersion;

class PackageDownloadTest {

    private static final String URL = "https://github.com/njMule/njMule/releases/download/v0.2.0/njmule.zip";
    private static final byte[] PACKAGE =
            "the package of njMule 0.2.0".repeat(100).getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private final FakeWeb web = new FakeWeb();
    private final List<Long> progress = new CopyOnWriteArrayList<>();
    private NewVersion.Download download;
    private Path file;
    private Exception failure;

    @Test
    void shouldKeepAPackageThatMatchesItsSignedHash() throws Exception {
        givenTheWebServes(PACKAGE);
        givenTheReleaseSays(PACKAGE);

        whenDownloaded();

        thenNoFailureOccurred();
        thenTheFileHolds(PACKAGE);
        thenTheProgressEndedAt(PACKAGE.length);
    }

    @Test
    void shouldGoOnFromWhatAnEarlierTryLeft() throws Exception {
        givenTheWebServes(PACKAGE);
        givenTheReleaseSays(PACKAGE);
        givenAnEarlierTryCutAfter(1000);

        whenDownloaded();

        thenNoFailureOccurred();
        thenTheFileHolds(PACKAGE);
    }

    @Test
    void shouldLeaveWhatCameForTheNextTryWhenTheLineDrops() throws Exception {
        givenTheWebServes(PACKAGE);
        givenTheReleaseSays(PACKAGE);
        givenTheLineDropsAfter(1000);

        whenDownloaded();

        thenItFailedWith("stopped at 1000");
        thenThePartHolds(1000);
    }

    @Test
    void shouldRefuseAPackageThatDoesNotMatchItsSignedHash() throws Exception {
        givenTheWebServes("a package someone changed".getBytes(StandardCharsets.UTF_8));
        givenTheReleaseSays("a package someone sealed".getBytes(StandardCharsets.UTF_8));

        whenDownloaded();

        thenItFailedWith("does not match its signed hash");
        thenNothingIsKept();
    }

    @Test
    void shouldNotDownloadAPackageThatIsThereAlready() throws Exception {
        givenTheReleaseSays(PACKAGE);
        givenThePackageIsThere(PACKAGE);

        whenDownloaded();

        thenNoFailureOccurred();
        thenTheWebWasNotAsked();
    }

    private void givenTheWebServes(byte[] body) {
        this.web.serve(URL, body);
    }

    private void givenTheReleaseSays(byte[] body) throws NoSuchAlgorithmException {
        String sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        this.download = new NewVersion.Download(URI.create(URL), body.length, sha256);
        this.file = this.dir.resolve("0.2.0").resolve("njmule.zip");
    }

    private void givenAnEarlierTryCutAfter(long bytes) {
        givenTheLineDropsAfter(bytes);
        try {
            PackageDownload.fetch(this.web, this.download, this.file, this.progress::add);
        } catch (IOException e) {
            // The line dropped, as it was asked to.
        }
    }

    private void givenTheLineDropsAfter(long bytes) {
        this.web.cutNextDownloadAfter(bytes);
    }

    private void givenThePackageIsThere(byte[] body) throws IOException {
        Files.createDirectories(this.file.getParent());
        Files.write(this.file, body);
    }

    private void whenDownloaded() {
        try {
            PackageDownload.fetch(this.web, this.download, this.file, this.progress::add);
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenItFailedWith(String words) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(words);
    }

    private void thenTheFileHolds(byte[] body) throws IOException {
        assertThat(Files.readAllBytes(this.file)).isEqualTo(body);
        assertThat(part()).doesNotExist();
    }

    private void thenThePartHolds(long bytes) throws IOException {
        assertThat(Files.size(part())).isEqualTo(bytes);
        assertThat(this.file).doesNotExist();
    }

    private void thenNothingIsKept() {
        assertThat(this.file).doesNotExist();
        assertThat(part()).doesNotExist();
    }

    private void thenTheProgressEndedAt(long bytes) {
        assertThat(this.progress).last().isEqualTo(bytes);
    }

    private void thenTheWebWasNotAsked() {
        assertThat(this.web.asked()).isEmpty();
    }

    private Path part() {
        return this.file.resolveSibling(this.file.getFileName() + UpdateFiles.PART);
    }
}
