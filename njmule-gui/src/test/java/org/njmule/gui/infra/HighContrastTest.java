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
package org.njmule.gui.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;

/** The system's high contrast colours in place of the theme's, while it is on. */
@Timeout(60)
class HighContrastTest {

    // A dark scheme and a light one, as Windows has.
    private static final Map<String, Object> DARK_SCHEME = scheme(
            Color.BLACK, Color.WHITE, Color.web("#1aebff"), Color.BLACK, Color.web("#ffff00"), Color.web("#3ff23f"));
    private static final Map<String, Object> LIGHT_SCHEME = scheme(
            Color.WHITE, Color.BLACK, Color.web("#37006e"), Color.WHITE, Color.web("#00009f"), Color.web("#600000"));
    private static final Color NORD_LIGHT_SURFACE = Color.web("#fafafc");

    private final ObservableMap<String, Object> system = FXCollections.observableHashMap();
    private Themes themes;
    private Scene scene;
    private Region surface;
    private Region partWeHave;
    private Region selectedRow;

    @BeforeAll
    static void startFx() throws InterruptedException {
        FxTesting.start();
    }

    @Test
    void shouldShowTheSystemColoursWhileHighContrastIsOn() throws Exception {
        givenHighContrastOn(DARK_SCHEME);

        whenAWindowIsStyledIn(GuiSettings.Mode.LIGHT);

        thenTheSurfaceIs(Color.BLACK);
        thenThePartWeHaveIsDrawnIn(Color.WHITE);
        thenASelectedRowIsDrawnIn(Color.web("#1aebff"));
        thenTheThemeIsDark(true);
    }

    @Test
    void shouldComeBackToTheChosenThemeWhenHighContrastIsTurnedOff() throws Exception {
        givenHighContrastOn(DARK_SCHEME);
        givenAWindowStyledIn(GuiSettings.Mode.LIGHT);

        whenHighContrastIsTurnedOff();

        thenTheSurfaceIs(NORD_LIGHT_SURFACE);
        thenTheThemeIsDark(false);
        thenTheWindowHasContrastSheets(0);
    }

    @Test
    void shouldFollowTheSystemToAnotherHighContrastScheme() throws Exception {
        givenHighContrastOn(DARK_SCHEME);
        givenAWindowStyledIn(GuiSettings.Mode.DARK);

        whenTheSystemSchemeBecomes(LIGHT_SCHEME);

        thenTheSurfaceIs(Color.WHITE);
        thenThePartWeHaveIsDrawnIn(Color.BLACK);
        thenTheThemeIsDark(false);
        thenTheWindowHasContrastSheets(1);
    }

    @Test
    void shouldKeepTheThemeWhenTheSystemTellsNoHighContrastColours() throws Exception {
        givenHighContrastOnWithoutColours();

        whenAWindowIsStyledIn(GuiSettings.Mode.LIGHT);

        thenTheSurfaceIs(NORD_LIGHT_SURFACE);
        thenTheWindowHasContrastSheets(0);
    }

    private static Map<String, Object> scheme(
            Color window, Color text, Color highlight, Color highlightText, Color link, Color gray) {
        return Map.of(
                "Windows.SPI.HighContrast", true,
                "Windows.SysColor.COLOR_WINDOW", window,
                "Windows.SysColor.COLOR_WINDOWTEXT", text,
                "Windows.SysColor.COLOR_3DFACE", window,
                "Windows.SysColor.COLOR_BTNTEXT", text,
                "Windows.SysColor.COLOR_HIGHLIGHT", highlight,
                "Windows.SysColor.COLOR_HIGHLIGHTTEXT", highlightText,
                "Windows.SysColor.COLOR_HOTLIGHT", link,
                "Windows.SysColor.COLOR_GRAYTEXT", gray);
    }

    private void givenHighContrastOn(Map<String, Object> scheme) throws Exception {
        FxTesting.onFx(() -> this.system.putAll(scheme));
    }

    private void givenHighContrastOnWithoutColours() throws Exception {
        FxTesting.onFx(() -> this.system.put("Windows.SPI.HighContrast", true));
    }

    private void givenAWindowStyledIn(GuiSettings.Mode mode) throws Exception {
        styleAWindowIn(mode);
    }

    private void whenAWindowIsStyledIn(GuiSettings.Mode mode) throws Exception {
        styleAWindowIn(mode);
    }

    private void styleAWindowIn(GuiSettings.Mode mode) throws Exception {
        FxTesting.onFx(() -> {
            this.surface = new Region();
            this.surface.setStyle("-fx-background-color: -color-bg-default;");
            this.partWeHave = new Region();
            this.partWeHave.setStyle("-fx-background-color: -nj-bar-have;");
            this.selectedRow = new Region();
            this.selectedRow.setStyle("-fx-background-color: -color-cell-bg-selected;");
            Pane table = new Pane(this.selectedRow);
            table.getStyleClass().add("table-view");
            this.scene = new Scene(new VBox(this.surface, this.partWeHave, table));
            this.themes = new Themes(Themes.Family.NORD, mode, this.system);
            this.themes.attach(this.scene);
        });
    }

    private void whenHighContrastIsTurnedOff() throws Exception {
        FxTesting.onFx(() -> this.system.put("Windows.SPI.HighContrast", false));
    }

    private void whenTheSystemSchemeBecomes(Map<String, Object> scheme) throws Exception {
        FxTesting.onFx(() -> this.system.putAll(scheme));
    }

    private void thenTheSurfaceIs(Color colour) throws Exception {
        assertThat(fill(this.surface)).isEqualTo(colour);
    }

    private void thenThePartWeHaveIsDrawnIn(Color colour) throws Exception {
        assertThat(fill(this.partWeHave)).isEqualTo(colour);
    }

    private void thenASelectedRowIsDrawnIn(Color colour) throws Exception {
        assertThat(fill(this.selectedRow)).isEqualTo(colour);
    }

    private void thenTheThemeIsDark(boolean dark) throws Exception {
        assertThat(FxTesting.onFx(() -> this.themes.darkProperty().get())).isEqualTo(dark);
    }

    private void thenTheWindowHasContrastSheets(int count) throws Exception {
        assertThat(FxTesting.onFx(() -> this.scene.getStylesheets().stream()
                        .filter(s -> s.startsWith("data:text/css"))
                        .count()))
                .isEqualTo(count);
    }

    private Paint fill(Region region) throws Exception {
        return FxTesting.onFx(() -> {
            this.scene.getRoot().applyCss();
            return region.getBackground().getFills().getFirst().getFill();
        });
    }
}
