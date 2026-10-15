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
    void finishAppliesTheAnswersOfEveryPage() throws Exception {
        this.givenTheWizard();
        this.whenTheNickIs("Tester");
        this.whenNextIsPressed(3);
        this.thenTheButtonReads("Finish");
        this.whenNextIsPressed(1);
        this.thenTheNickBecomes("Tester");
        this.thenTheOutcomeIs(new FirstRunWizard.Outcome(false, false));
    }

    @Test
    void aNickThatIsNotRightHoldsNextBack() throws Exception {
        this.givenTheWizard();
        this.whenNextIsPressed(1);
        this.whenTheNickIs("");
        this.thenNextIsOff(true);
        this.whenTheNickIs("Someone");
        this.thenNextIsOff(false);
    }

    @Test
    void skippingChangesNothing() throws Exception {
        this.givenTheWizard();
        this.whenTheNickIs("Tester");
        this.whenTheWindowIsClosed();
        this.thenTheNickStays("njMule");
        this.thenThereIsNoOutcome();
    }

    @Test
    void anEmuleOnThisComputerAddsItsPageAndMovesThePorts() throws Exception {
        this.givenAnEmuleOnPorts(4662, 4672);
        this.givenTheWizard();
        this.thenThePagesAre(5);
        this.thenThePortsAreNot(4662, 4672);
    }

    @Test
    void aLineFillsTheCapacitiesOfTheLine() throws Exception {
        this.givenTheWizard();
        this.whenTheLineIs(1);
        this.thenTheCapacitiesAre("100", "20");
    }

    @Test
    void theFoldersAddedAreSharedWithTheirSubfolders() throws Exception {
        Path music = Files.createDirectories(this.dir.resolve("music"));
        this.givenTheWizard();
        this.whenTheFolderIsAdded(music);
        this.whenFinished();
        this.thenTheSharedFoldersInclude(music);
    }

    private void givenAnEmuleOnPorts(int tcp, int udp) throws Exception {
        Path config = Files.createDirectories(this.dir.resolve("emule-config"));
        Files.writeString(config.resolve("preferences.ini"), "[eMule]\r\nPort=" + tcp + "\r\nUDPPort=" + udp + "\r\n");
        this.emule = Optional.of(config);
    }

    private void givenTheWizard() throws Exception {
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

    private void whenTheNickIs(String nick) throws Exception {
        FxTesting.onFx(() -> this.wizard.nickField().setText(nick));
    }

    private void whenNextIsPressed(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            FxTesting.onFx(() -> this.wizard.nextButton().fire());
        }
    }

    private void whenTheWindowIsClosed() throws Exception {
        FxTesting.onFx(() -> this.wizard.stage().close());
    }

    private void whenTheLineIs(int index) throws Exception {
        FxTesting.onFx(() -> this.wizard.lineBox().setValue(FirstRunWizard.LINES.get(index)));
    }

    private void whenTheFolderIsAdded(Path folder) throws Exception {
        FxTesting.onFx(() -> this.wizard.sharedList().getItems().add(folder));
    }

    private void whenFinished() throws Exception {
        FxTesting.onFx(this.wizard::finish);
    }

    private void thenTheButtonReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.wizard.nextButton().getText())).isEqualTo(text);
    }

    private void thenNextIsOff(boolean off) throws Exception {
        assertThat(FxTesting.onFx(() -> this.wizard.nextButton().isDisabled())).isEqualTo(off);
    }

    private void thenTheNickBecomes(String nick) throws Exception {
        FxTesting.await("the nick", () -> this.test.engine.preferences().nick().equals(nick));
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

    private void thenThePortsAreNot(int tcp, int udp) throws Exception {
        assertThat(FxTesting.onFx(() -> this.wizard.tcpField().getText())).isNotEqualTo(Integer.toString(tcp));
        assertThat(FxTesting.onFx(() -> this.wizard.udpField().getText())).isNotEqualTo(Integer.toString(udp));
    }

    private void thenTheCapacitiesAre(String down, String up) throws Exception {
        assertThat(FxTesting.onFx(() -> List.of(
                        this.wizard.downField().getText(), this.wizard.upField().getText())))
                .containsExactly(down, up);
    }

    private void thenTheSharedFoldersInclude(Path folder) throws Exception {
        FxTesting.await(
                "the shared folder",
                () -> this.test.engine.sharedDirectories().join().stream()
                        .map(SharedDirectory::path)
                        .anyMatch(folder::equals));
    }
}
