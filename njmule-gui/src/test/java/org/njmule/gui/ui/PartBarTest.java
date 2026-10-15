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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.njmule.core.api.PartMap;
import org.njmule.gui.FxTesting;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;

class PartBarTest {

    private static final long PART = PartMap.PART_SIZE;

    private PartMap map;
    private GuiSettings.Mode mode;
    private PartBar bar;
    private Stage stage;
    private Color have;
    private Color requested;
    private Color fewSources;
    private Color manySources;
    private Color none;
    private Color strip;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.stage != null) {
            FxTesting.onFx(this.stage::close);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = GuiSettings.Mode.class,
            names = {"LIGHT", "DARK"})
    void shouldDrawWhatWeHaveWhatIsMissingAndWhatIsRequested(GuiSettings.Mode theme) throws Exception {
        givenTenPartsWithTheFirstTwoDoneAndPartTwoRequested(0, 0, 3, 1, 5, 12, 0, 0, 0, 0);
        givenABarShownInTheTheme(theme);

        whenTheBarShowsTheMapWithCompletedBytes(2 * PART);

        thenWhatWeHaveLooksUnlikeTheOtherParts();
        thenFewSourcesLookUnlikeMany();
        thenNoSourcesLookUnlikeMany();
        thenTheCompletedStripLooksUnlikeWhatWeHave();
    }

    // Ten parts: the first two done, part 2 requested, parts 3-5 with sources, the rest with none.
    private void givenTenPartsWithTheFirstTwoDoneAndPartTwoRequested(Integer... sources) {
        this.map = new PartMap(
                10 * PART,
                List.of(new PartMap.Span(2 * PART, 10 * PART)),
                List.of(new PartMap.Span(2 * PART, 3 * PART)),
                List.of(sources));
    }

    private void givenABarShownInTheTheme(GuiSettings.Mode theme) throws Exception {
        this.mode = theme;
        FxTesting.onFx(() -> {
            this.bar = new PartBar();
            this.bar.setPrefSize(400, 16);
            VBox box = new VBox(this.bar);
            Scene scene = new Scene(box, 400, 16);
            Themes themes = new Themes(Themes.Family.NORD, theme);
            themes.attach(scene);
            this.stage = new Stage();
            this.stage.setScene(scene);
            this.stage.show();
            box.applyCss();
            box.layout();
        });
    }

    private void whenTheBarShowsTheMapWithCompletedBytes(long completed) throws Exception {
        FxTesting.onFx(() -> {
            this.bar.set(this.map, completed);
            PixelReader pixels = this.bar.snapshot(null, null).getPixelReader();
            FxTesting.snapshot(this.bar, "partbar-" + this.mode.name().toLowerCase(Locale.ROOT));
            this.have = pixels.getColor(20, 10);
            this.requested = pixels.getColor(100, 10);
            this.fewSources = pixels.getColor(140, 10);
            this.manySources = pixels.getColor(220, 10);
            this.none = pixels.getColor(350, 10);
            this.strip = pixels.getColor(20, 1);
            return null;
        });
    }

    private void thenWhatWeHaveLooksUnlikeTheOtherParts() {
        assertThat(List.of(this.requested, this.fewSources, this.manySources, this.none))
                .doesNotContain(this.have);
    }

    private void thenFewSourcesLookUnlikeMany() {
        assertThat(this.fewSources).isNotEqualTo(this.manySources);
    }

    private void thenNoSourcesLookUnlikeMany() {
        assertThat(this.none).isNotEqualTo(this.manySources);
    }

    private void thenTheCompletedStripLooksUnlikeWhatWeHave() {
        assertThat(this.strip).isNotEqualTo(this.have);
    }
}
