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
package org.njmule.gui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.gui.FxTesting;

/** JavaFX's own texts, as the buttons of dialogs, speak the language of the interface, not the system's. */
@Timeout(60)
class JavaFxTextsTest {

    // The JVM's languages before the test, which changes them.
    private final Locale language = Locale.getDefault();
    private final Locale display = Locale.getDefault(Locale.Category.DISPLAY);
    private final Locale format = Locale.getDefault(Locale.Category.FORMAT);

    private Stage stage;
    private TextField field;
    private List<String> menu;

    @AfterEach
    void restoreTheLanguagesOfTheJvm() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
        Locale.setDefault(this.language);
        Locale.setDefault(Locale.Category.DISPLAY, this.display);
        Locale.setDefault(Locale.Category.FORMAT, this.format);
    }

    @ParameterizedTest
    @CsvSource({
        "it, en, Cancel",
        "en, it, Annulla",
        "it, de, Abbrechen",
        "fr, pt-BR, Cancelar",
        // JavaFX has no Catalan: English, not the system's language.
        "de, ca, Cancel"
    })
    void shouldWriteTheButtonsOfDialogsInTheLanguageOfTheInterface(String system, String chosen, String cancel) {
        givenTheSystemLanguage(Locale.forLanguageTag(system));

        whenTheLanguageOfTheInterfaceIsMadeTheDefault(Locale.forLanguageTag(chosen));

        thenTheCancelButtonReads(cancel);
    }

    @Test
    void shouldWriteTheMenuOfTextFieldsInTheLanguageOfTheInterface() throws Exception {
        givenTheSystemLanguage(Locale.ITALIAN);
        givenTheLanguageOfTheInterfaceMadeTheDefault(Locale.ENGLISH);
        givenATextFieldInAWindow();

        whenTheMenuOfTheTextFieldOpens();

        thenTheMenuReads("Undo", "Redo", "Cut", "Copy", "Paste", "Delete", "Select All");
    }

    @Test
    void shouldKeepTheFormatOfTheSystem() {
        givenTheSystemLanguage(Locale.ITALY);

        whenTheLanguageOfTheInterfaceIsMadeTheDefault(Locale.ENGLISH);

        thenTheLanguageIs(Locale.ENGLISH);
        thenTheFormatIs(Locale.ITALY);
    }

    private void givenTheSystemLanguage(Locale system) {
        Locale.setDefault(system);
    }

    private void givenTheLanguageOfTheInterfaceMadeTheDefault(Locale chosen) {
        I18n.makeDefault(I18n.of(chosen).locale());
    }

    private void givenATextFieldInAWindow() throws Exception {
        FxTesting.start();
        FxTesting.onFx(() -> {
            this.field = new TextField("ed2k://|file|a.bin|1|0123456789ABCDEF0123456789ABCDEF|/");
            this.stage = new Stage();
            this.stage.setScene(new Scene(new StackPane(this.field), 400, 100));
            this.stage.show();
            this.field.requestFocus();
            this.field.selectAll();
        });
    }

    // As DesktopApp does when it builds the window.
    private void whenTheLanguageOfTheInterfaceIsMadeTheDefault(Locale chosen) {
        I18n.makeDefault(I18n.of(chosen).locale());
    }

    private void whenTheMenuOfTheTextFieldOpens() throws Exception {
        this.menu = FxTesting.onFx(() -> {
            Bounds b = this.field.localToScreen(this.field.getBoundsInLocal());
            this.field.fireEvent(new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED, 20, 10, b.getMinX() + 20, b.getMinY() + 10, false, null));
            List<String> texts = Window.getWindows().stream()
                    .filter(ContextMenu.class::isInstance)
                    .map(ContextMenu.class::cast)
                    .filter(ContextMenu::isShowing)
                    .flatMap(m -> m.getItems().stream())
                    .map(MenuItem::getText)
                    .filter(Objects::nonNull)
                    .toList();
            Window.getWindows().stream()
                    .filter(ContextMenu.class::isInstance)
                    .toList()
                    .forEach(Window::hide);
            return texts;
        });
    }

    private void thenTheCancelButtonReads(String text) {
        assertThat(ButtonType.CANCEL.getText()).isEqualTo(text);
    }

    private void thenTheMenuReads(String... texts) {
        assertThat(this.menu).containsExactly(texts);
    }

    private void thenTheLanguageIs(Locale language) {
        assertThat(Locale.getDefault(Locale.Category.DISPLAY)).isEqualTo(language);
        assertThat(Locale.getDefault()).isEqualTo(language);
    }

    private void thenTheFormatIs(Locale format) {
        assertThat(Locale.getDefault(Locale.Category.FORMAT)).isEqualTo(format);
    }
}
