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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The macOS tools as a test plays them: hdiutil mounts an image holding what the test says. */
class DiskImagesTest {

    @TempDir
    Path dir;

    private final List<String> run = new ArrayList<>();
    private final List<String> apps = new ArrayList<>(List.of("njMule.app"));
    private int signature;
    private Path bundle;
    private Exception failure;

    @Test
    void shouldCopyTheAppOutOfTheImageAndCheckItsSignature() {
        whenExtracted();

        thenNoFailureOccurred();
        thenTheBundleIs("njMule.app");
        thenTheToolsRanInOrder("hdiutil attach", "ditto", "hdiutil detach", "codesign --verify");
    }

    @Test
    void shouldRefuseAnAppWhoseSignatureFails() {
        givenTheSignatureFails();

        whenExtracted();

        thenItFailedWith("codesign");
    }

    @Test
    void shouldRefuseAnImageWithoutTheApp() {
        givenTheImageHolds("Other.app");

        whenExtracted();

        thenItFailedWith("no njMule.app");
        thenTheToolsRanInOrder("hdiutil attach", "hdiutil detach");
    }

    @Test
    void shouldTakeTheAppBesideItsUninstaller() {
        givenTheImageHolds("Uninstall njMule.app", "njMule.app");

        whenExtracted();

        thenNoFailureOccurred();
        thenTheBundleIs("njMule.app");
    }

    private void givenTheSignatureFails() {
        this.signature = 1;
    }

    private void givenTheImageHolds(String... names) {
        this.apps.clear();
        this.apps.addAll(List.of(names));
    }

    private void whenExtracted() {
        try {
            this.bundle = DiskImages.extract(
                    this.dir.resolve("njmule-0.2.0-macos-arm64.dmg"), this.dir.resolve("files"), this::tool);
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private Commands.Result tool(List<String> command, Duration timeout) throws IOException {
        String tool = command.get(0).equals("ditto") ? "ditto" : command.get(0) + " " + command.get(1);
        this.run.add(tool);
        switch (tool) {
            case "hdiutil attach" -> {
                Path mount = Path.of(command.get(command.indexOf("-mountpoint") + 1));
                for (String app : this.apps) {
                    Files.createDirectories(mount.resolve(app).resolve("Contents"));
                }
            }
            case "hdiutil detach" -> {
                Path mount = Path.of(command.get(2));
                UpdateFiles.deleteTree(mount);
                Files.createDirectories(mount);
            }
            case "codesign --verify" -> {
                return new Commands.Result(this.signature, this.signature == 0 ? "" : "invalid signature");
            }
            default -> Swap.FILES.copy(Path.of(command.get(1)), Path.of(command.get(2)));
        }
        return new Commands.Result(0, "");
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenItFailedWith(String words) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(words);
    }

    private void thenTheBundleIs(String name) {
        assertThat(this.bundle).isEqualTo(this.dir.resolve("files").resolve(name));
        assertThat(this.bundle.resolve("Contents")).isDirectory();
    }

    private void thenTheToolsRanInOrder(String... tools) {
        assertThat(this.run).containsExactly(tools);
    }
}
