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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.SharedDirectory;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class FirstRunWizardTest {

    @TempDir
    Path dir;

    private TestGui test;
    private FirstRunWizard wizard;
    private Optional<Path> emule = Optional.empty();
    private final List<FirstRunWizard.Outcome> outcomes = new ArrayList<>();
    private Path folder;

    @AfterEach
    void close() throws Exception {
        if (this.wizard != null) {
            FxTesting.onFx(() -> this.wizard.stage().hide());
        }
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldApplyTheAnswersOfEveryPageOnFinish() throws Exception {
        givenTheWizard();
        givenTheNick("Tester");
        givenNextPressed(3);
        givenTheButtonReads("Finish");

        whenNextIsPressed(1);

        thenTheNickBecomes("Tester");
        thenTheOutcomeIs(new FirstRunWizard.Outcome(false, false));
    }

    @Test
    void shouldHoldNextBackUntilTheNickIsRight() throws Exception {
        givenTheWizard();
        givenNextPressed(1);
        givenTheNick("");
        givenNextIsOff(true);

        whenTheNickIs("Someone");

        thenNextIsOff(false);
    }

    @Test
    void shouldChangeNothingWhenSkipped() throws Exception {
        givenTheWizard();
        givenTheNick("Tester");

        whenTheWindowIsClosed();

        thenTheNickStays("njMule");
        thenThereIsNoOutcome();
    }

    @Test
    void shouldAddThePageOfAnEmuleOnThisComputer() throws Exception {
        givenAnEmule();

        whenTheWizardShows();

        thenThePagesAre(5);
    }

    @Test
    void shouldShowThePortsOfTheSettings() throws Exception {
        givenAnEmule();

        whenTheWizardShows();

        thenThePortsAreThoseOfTheSettings();
    }

    @Test
    void shouldFillTheCapacitiesOfTheLine() throws Exception {
        givenTheWizard();

        whenTheLineIs(1);

        thenTheCapacitiesAre("100", "20");
    }

    @Test
    void shouldShareTheFoldersAddedWithTheirSubfolders() throws Exception {
        givenAFolder("music");
        givenTheWizard();
        givenTheFolderAdded();

        whenFinished();

        thenTheFolderIsShared();
    }

    private void givenAnEmule() throws Exception {
        this.emule = Optional.of(Files.createDirectories(this.dir.resolve("emule-config")));
    }

    private void givenAFolder(String name) throws Exception {
        this.folder = Files.createDirectories(this.dir.resolve(name));
    }

    private void givenTheWizard() throws Exception {
        showTheWizard();
    }

    private void givenTheNick(String nick) throws Exception {
        typeTheNick(nick);
    }

    private void givenNextPressed(int times) throws Exception {
        pressNext(times);
    }

    private void givenTheButtonReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.wizard.nextButton().getText())).isEqualTo(text);
    }

    private void givenNextIsOff(boolean off) throws Exception {
        assertThat(FxTesting.onFx(() -> this.wizard.nextButton().isDisabled())).isEqualTo(off);
    }

    private void givenTheFolderAdded() throws Exception {
        FxTesting.onFx(() -> this.wizard.sharedList().getItems().add(this.folder));
    }

    private void whenTheWizardShows() throws Exception {
        showTheWizard();
    }

    private void whenTheNickIs(String nick) throws Exception {
        typeTheNick(nick);
    }

    private void whenNextIsPressed(int times) throws Exception {
        pressNext(times);
    }

    private void whenTheWindowIsClosed() throws Exception {
        FxTesting.onFx(() -> this.wizard.stage().close());
    }

    private void whenTheLineIs(int index) throws Exception {
        FxTesting.onFx(() -> this.wizard.lineBox().setValue(FirstRunWizard.LINES.get(index)));
    }

    private void whenFinished() throws Exception {
        FxTesting.onFx(this.wizard::finish);
    }

    private void thenNextIsOff(boolean off) throws Exception {
        assertThat(FxTesting.onFx(() -> this.wizard.nextButton().isDisabled())).isEqualTo(off);
    }

    private void thenTheNickBecomes(String nick) throws Exception {
        FxTesting.assertEventually(
                "the nick", () -> this.test.engine.preferences().nick().equals(nick));
    }

    private void thenTheNickStays(String nick) {
        assertThat(this.test.engine.preferences().nick()).isEqualTo(nick);
    }

    private void thenTheOutcomeIs(FirstRunWizard.Outcome outcome) {
        assertThat(this.outcomes).containsExactly(outcome);
    }

    private void thenThereIsNoOutcome() {
        assertThat(this.outcomes).isEmpty();
    }

    private void thenThePagesAre(int pages) throws Exception {
        assertThat(FxTesting.onFx(this.wizard::pages)).isEqualTo(pages);
    }

    private void thenThePortsAreThoseOfTheSettings() throws Exception {
        int tcp = this.test.engine.preferences().tcpPort();
        int udp = this.test.engine.preferences().udpPort();
        assertThat(FxTesting.onFx(() -> this.wizard.tcpField().getText())).isEqualTo(Integer.toString(tcp));
        assertThat(FxTesting.onFx(() -> this.wizard.udpField().getText())).isEqualTo(Integer.toString(udp));
    }

    private void thenTheCapacitiesAre(String down, String up) throws Exception {
        assertThat(FxTesting.onFx(() -> List.of(
                        this.wizard.downField().getText(), this.wizard.upField().getText())))
                .containsExactly(down, up);
    }

    private void thenTheFolderIsShared() throws Exception {
        FxTesting.assertEventually(
                "the shared folder",
                () -> this.test.engine.sharedDirectories().join().stream()
                        .map(SharedDirectory::path)
                        .anyMatch(this.folder::equals));
    }

    private void showTheWizard() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        StackPane window = new StackPane();
        this.test.show(window);
        this.wizard = FxTesting.onFx(() -> {
            FirstRunWizard w = new FirstRunWizard(
                    this.test.gui,
                    window.getScene().getWindow(),
                    this.test.engine.preferences(),
                    this.emule,
                    this.outcomes::add);
            w.show();
            return w;
        });
    }

    private void typeTheNick(String nick) throws Exception {
        FxTesting.onFx(() -> this.wizard.nickField().setText(nick));
    }

    private void pressNext(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            FxTesting.onFx(() -> this.wizard.nextButton().fire());
        }
    }
}
