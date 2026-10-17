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
package org.njmule.gui.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.Hash16;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

/** The eD2K links pasted, shown before they are downloaded. */
@Timeout(60)
class AddLinksDialogTest {

    private static final Ed2kLink.FileLink FILM =
            Ed2kLink.FileLink.of("film.mkv", 700_000_000, Hash16.parseHex("41".repeat(16)));
    private static final Ed2kLink.FileLink SONG =
            Ed2kLink.FileLink.of("song.mp3", 5_000_000, Hash16.parseHex("42".repeat(16)));

    @TempDir
    Path dir;

    private TestGui test;
    private AddLinksDialog dialog;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldTickTheNewFilesAndLeaveOutThoseDownloadingAlready() throws Exception {
        givenTheFileDownloadingAlready(SONG);

        whenTheLinksAreShown(FILM, SONG);

        thenTheLinkIs(FILM, true, "");
        thenTheLinkIs(SONG, false, "Already downloading");
    }

    @Test
    void shouldAddTheTickedLinksPausedWithoutACategory() throws Exception {
        givenTheLinksShown(FILM, SONG);
        givenTheChoices(Category.NONE, true);
        givenTheLinkLeftOut(SONG);

        whenTheChosenAreAdded();

        thenTheOnlyDownloadIs(FILM, Category.NONE, DownloadInfo.Status.PAUSED);
    }

    @Test
    void shouldKeepTheBoxOfTheLinksLeftToRightInArabic() throws Exception {
        givenTheInterfaceIn(Locale.forLanguageTag("ar"));

        whenTheLinksAreShown(FILM);

        thenTheDialogRuns(NodeOrientation.RIGHT_TO_LEFT);
        thenTheBoxOfTheLinksRuns(NodeOrientation.LEFT_TO_RIGHT);
    }

    private void givenTheInterfaceIn(Locale language) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), language);
    }

    private void givenTheFileDownloadingAlready(Ed2kLink.FileLink link) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.test.engine.addDownload(link).get(10, TimeUnit.SECONDS);
        FxTesting.onFx(() -> {
            this.test.model.addDownload(link);
            return null;
        });
        FxTesting.await("the download", () -> this.test.model.downloads().contains(link.hash()));
    }

    private void givenTheLinksShown(Ed2kLink.FileLink... links) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        whenTheLinksAreShown(links);
    }

    private void givenTheChoices(int category, boolean paused) throws Exception {
        FxTesting.onFx(() -> {
            this.dialog.category().setValue(category);
            this.dialog.paused().setSelected(paused);
            return null;
        });
    }

    private void givenTheLinkLeftOut(Ed2kLink.FileLink link) throws Exception {
        FxTesting.onFx(() -> {
            this.dialog.links().get(link).setSelected(false);
            return null;
        });
    }

    private void whenTheLinksAreShown(Ed2kLink.FileLink... links) throws Exception {
        this.dialog =
                FxTesting.onFx(() -> new AddLinksDialog(this.test.gui, null, List.of(links), OptionalInt.empty()));
    }

    private void whenTheChosenAreAdded() throws Exception {
        FxTesting.onFx(() -> {
            this.dialog.addChosen();
            return null;
        });
    }

    private void thenTheDialogRuns(NodeOrientation orientation) throws Exception {
        assertThat(FxTesting.onFx(() -> this.dialog.alert().getDialogPane().getNodeOrientation()))
                .isEqualTo(orientation);
    }

    private void thenTheBoxOfTheLinksRuns(NodeOrientation orientation) throws Exception {
        assertThat(FxTesting.onFx(() -> this.dialog.text().getEffectiveNodeOrientation()))
                .isEqualTo(orientation);
    }

    // Its name and size, then what is known of it.
    private void thenTheLinkIs(Ed2kLink.FileLink link, boolean ticked, String note) throws Exception {
        CheckBox box = FxTesting.onFx(() -> this.dialog.links().get(link));
        assertThat(FxTesting.onFx(box::isSelected)).isEqualTo(ticked);
        assertThat(FxTesting.onFx(box::getText)).isEqualTo(link.name());
        assertThat(FxTesting.onFx(() -> this.dialog.noteOf(link))).isEqualTo(note);
    }

    private void thenTheOnlyDownloadIs(Ed2kLink.FileLink link, int category, DownloadInfo.Status status)
            throws Exception {
        FxTesting.await("the download", () -> this.test.model.downloads().contains(link.hash()));
        assertThat(this.test.engine.downloads().get(10, TimeUnit.SECONDS))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.hash()).isEqualTo(link.hash());
                    assertThat(d.category()).isEqualTo(category);
                    assertThat(d.status()).isEqualTo(status);
                });
    }
}
