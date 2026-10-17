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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;
import org.njmule.gui.dialogs.AboutDialog;

@Timeout(60)
class RightToLeftLayoutTest {

    private static final Locale ARABIC = Locale.of("ar");

    @TempDir
    Path dir;

    private TestGui test;
    private MainWindow window;
    private final List<Parent> shown = new ArrayList<>();
    private final List<Stage> opened = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        FxTesting.onFx(() -> this.opened.forEach(Stage::close));
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldRunTheWindowItsDialogsAndTheTrayWindowsRightToLeftInArabic() throws Exception {
        givenTheInterfaceIn(ARABIC);
        givenTheMainWindowShown();
        givenTheAboutDialogOpen();

        whenTheWindowsOfTheNotificationAreaAreBuilt();

        thenEachRuns(NodeOrientation.RIGHT_TO_LEFT);
    }

    @Test
    void shouldRunTheWindowItsDialogsAndTheTrayWindowsLeftToRightInEnglish() throws Exception {
        givenTheInterfaceIn(Locale.ENGLISH);
        givenTheMainWindowShown();
        givenTheAboutDialogOpen();

        whenTheWindowsOfTheNotificationAreaAreBuilt();

        thenEachRuns(NodeOrientation.LEFT_TO_RIGHT);
    }

    @Test
    void shouldKeepTheAddressFieldsLeftToRightInArabic() throws Exception {
        givenTheInterfaceIn(ARABIC);

        whenTheMainWindowShows();

        thenTheAddressFieldOfTheServersRunsLeftToRight();
        thenTheNodeFieldOfKadRunsLeftToRight();
    }

    private void givenTheInterfaceIn(Locale language) throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), language);
    }

    private void givenTheMainWindowShown() throws Exception {
        whenTheMainWindowShows();
    }

    private void givenTheAboutDialogOpen() throws Exception {
        FxTesting.onFx(() -> {
            Window owner = this.window.root().getScene().getWindow();
            new AboutDialog(this.test.gui, owner).show();
            for (Window w : Window.getWindows()) {
                if (w instanceof Stage s
                        && s.isShowing()
                        && s.getTitle() != null
                        && s.getTitle().equals(this.test.gui.t("nj.about.title"))) {
                    this.opened.add(s);
                    this.shown.add(s.getScene().getRoot());
                }
            }
        });
    }

    private void whenTheMainWindowShows() throws Exception {
        this.window = FxTesting.onFx(() -> new MainWindow(this.test.gui));
        this.test.show(this.window.root());
        this.shown.add(this.window.root());
    }

    // Their owner never shows and has no scene of its own, so they have no direction to inherit.
    private void whenTheWindowsOfTheNotificationAreaAreBuilt() throws Exception {
        FxTesting.onFx(() -> {
            Stage owner = new Stage(StageStyle.UTILITY);
            TrayMenu menu = new TrayMenu(this.test.gui, owner, new TrayMenu.Actions() {
                @Override
                public void restore() {}

                @Override
                public void options() {}

                @Override
                public void exit() {}
            });
            MiniMule mini = new MiniMule(this.test.gui, owner, () -> this.dir, new MiniMule.Actions() {
                @Override
                public void openIncoming() {}

                @Override
                public void options() {}

                @Override
                public void restore() {}
            });
            this.shown.add(menu.scene().getRoot());
            this.shown.add(mini.scene().getRoot());
        });
    }

    // The main window, the dialog, the tray menu and the small status window.
    private void thenEachRuns(NodeOrientation orientation) throws Exception {
        List<NodeOrientation> found = FxTesting.onFx(
                () -> this.shown.stream().map(Node::getEffectiveNodeOrientation).toList());
        assertThat(found).hasSize(4).containsOnly(orientation);
    }

    // The prompt is embedded in its own direction, as the field runs the other way; the field is among the ways to
    // add servers, in a popup.
    private void thenTheAddressFieldOfTheServersRunsLeftToRight() throws Exception {
        String prompt = this.test.gui.i18n().embed(this.test.gui.t("nj.servers.addressPrompt"));
        List<NodeOrientation> found = FxTesting.onFx(() -> {
            this.window.show("servers");
            this.window.root().applyCss();
            this.window.root().layout();
            ((Button) this.window.root().lookup("#add-servers")).fire();
            List<NodeOrientation> orientations = new ArrayList<>();
            orientations.add(this.window.screen("servers").orElseThrow().view().getEffectiveNodeOrientation());
            for (Window shown : List.copyOf(Window.getWindows())) {
                if (shown instanceof PopupWindow && shown.getScene() != null) {
                    for (Node node : shown.getScene().getRoot().lookupAll(".text-field")) {
                        if (node instanceof TextField field && prompt.equals(field.getPromptText())) {
                            orientations.add(field.getEffectiveNodeOrientation());
                        }
                    }
                }
            }
            return orientations;
        });
        assertThat(found).containsExactly(NodeOrientation.RIGHT_TO_LEFT, NodeOrientation.LEFT_TO_RIGHT);
    }

    private void thenTheNodeFieldOfKadRunsLeftToRight() throws Exception {
        thenTheFieldPromptingRunsLeftToRightOnTheScreen(
                "kad", this.test.gui.i18n().embed(this.test.gui.t("nj.kad.nodePrompt")));
    }

    private void thenTheFieldPromptingRunsLeftToRightOnTheScreen(String screen, String prompt) throws Exception {
        List<NodeOrientation> found = FxTesting.onFx(() -> {
            this.window.show(screen);
            this.window.root().applyCss();
            this.window.root().layout();
            List<NodeOrientation> orientations = new ArrayList<>();
            orientations.add(this.window.screen(screen).orElseThrow().view().getEffectiveNodeOrientation());
            for (Node node : this.window.root().lookupAll(".text-field")) {
                if (node instanceof TextField field && prompt.equals(field.getPromptText())) {
                    orientations.add(field.getEffectiveNodeOrientation());
                }
            }
            return orientations;
        });
        assertThat(found).containsExactly(NodeOrientation.RIGHT_TO_LEFT, NodeOrientation.LEFT_TO_RIGHT);
    }
}
