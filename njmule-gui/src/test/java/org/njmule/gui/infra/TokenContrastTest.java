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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.gui.FxTesting;

/**
 * The colours we draw with stay readable in every theme: text at 4.5:1 at least against what is behind it, lines and
 * marks that carry meaning at 3:1 (WCAG 2.2, success criteria 1.4.3 and 1.4.11).
 */
@Timeout(60)
class TokenContrastTest {

    private static final double TEXT = 4.5;
    private static final double GRAPHIC = 3;

    /** What is drawn, in front of what, and the ratio it needs. */
    private record Pair(String what, String front, String back, double ratio) {}

    private static final List<Pair> PAIRS = List.of(
            new Pair("the words of the main action", "-nj-on-accent", "-nj-brand-accent", TEXT),
            new Pair("the name on the brand's blue", "-nj-brand-mark", "-nj-brand", TEXT),
            new Pair("a chip in the accent tone", "-color-fg-default", "-color-accent-muted", TEXT),
            new Pair("a chip in the success tone", "-color-fg-default", "-color-success-muted", TEXT),
            new Pair("a chip in the warning tone", "-color-fg-default", "-color-warning-muted", TEXT),
            new Pair("a chip in the danger tone", "-color-fg-default", "-color-danger-muted", TEXT),
            new Pair("a neutral chip", "-color-fg-default", "-color-neutral-muted", TEXT),
            new Pair("a risky answer on the band of a dialog", "-color-danger-fg", "-color-bg-subtle", TEXT),
            new Pair("muted words on the band of a dialog", "-color-fg-muted", "-color-bg-subtle", TEXT),
            new Pair("the download line of the graphs", "-nj-graph-down", "-color-bg-default", GRAPHIC),
            new Pair("the upload line of the graphs", "-nj-graph-up", "-color-bg-default", GRAPHIC),
            new Pair("the ring of the safe answer", "-color-accent-fg", "-color-bg-subtle", GRAPHIC),
            new Pair("the dot of a connection", "-color-success-fg", "-color-bg-default", GRAPHIC),
            new Pair("muted words on a card", "-color-fg-muted", "-color-bg-subtle", TEXT),
            new Pair("the sign of a warning", "-color-warning-fg", "-color-bg-default", GRAPHIC),
            new Pair("the parts we have", "-nj-bar-have", "-nj-bar-bg", GRAPHIC));

    private Themes themes;
    private Scene scene;
    private Region surface;
    private final List<Region> fronts = new ArrayList<>();
    private final List<Region> backs = new ArrayList<>();
    private List<String> tooFaint = List.of();

    @BeforeAll
    static void startFx() throws InterruptedException {
        FxTesting.start();
    }

    static Stream<Arguments> themes() {
        return Stream.of(
                Arguments.of(Themes.Family.NORD, GuiSettings.Mode.LIGHT),
                Arguments.of(Themes.Family.NORD, GuiSettings.Mode.DARK),
                Arguments.of(Themes.Family.PRIMER, GuiSettings.Mode.LIGHT),
                Arguments.of(Themes.Family.PRIMER, GuiSettings.Mode.DARK),
                Arguments.of(Themes.Family.CUPERTINO, GuiSettings.Mode.LIGHT),
                Arguments.of(Themes.Family.CUPERTINO, GuiSettings.Mode.DARK),
                Arguments.of(Themes.Family.DRACULA, GuiSettings.Mode.DARK));
    }

    @ParameterizedTest
    @MethodSource("themes")
    void shouldKeepTextAndMarksReadableInEveryTheme(Themes.Family family, GuiSettings.Mode mode) throws Exception {
        givenAWindowIn(family, mode);

        whenItsColoursAreRead();

        thenNothingIsTooFaint();
    }

    private void givenAWindowIn(Themes.Family family, GuiSettings.Mode mode) throws Exception {
        FxTesting.onFx(() -> {
            VBox root = new VBox();
            this.surface = probe("-color-bg-default", root);
            for (Pair pair : PAIRS) {
                this.fronts.add(probe(pair.front(), root));
                this.backs.add(probe(pair.back(), root));
            }
            this.scene = new Scene(root);
            this.themes = new Themes(family, mode, FXCollections.observableHashMap());
            this.themes.attach(this.scene);
        });
    }

    private void whenItsColoursAreRead() throws Exception {
        this.tooFaint = FxTesting.onFx(() -> {
            this.scene.getRoot().applyCss();
            List<String> faint = new ArrayList<>();
            for (int i = 0; i < PAIRS.size(); i++) {
                Pair pair = PAIRS.get(i);
                // A tint that lets the window show through, as it is seen on the window.
                Color back = over(fill(this.backs.get(i)), fill(this.surface));
                double ratio = contrast(over(fill(this.fronts.get(i)), back), back);
                if (ratio < pair.ratio()) {
                    faint.add(String.format(Locale.ROOT, "%s: %.2f, needs %.1f", pair.what(), ratio, pair.ratio()));
                }
            }
            return faint;
        });
    }

    private void thenNothingIsTooFaint() {
        assertThat(this.tooFaint).isEmpty();
    }

    private static Region probe(String colour, VBox root) {
        Region region = new Region();
        region.setStyle("-fx-background-color: " + colour + ";");
        root.getChildren().add(region);
        return region;
    }

    private static Color fill(Region region) {
        return (Color) region.getBackground().getFills().getFirst().getFill();
    }

    // A colour that lets the background show through, as it is seen on it.
    private static Color over(Color front, Color back) {
        double a = front.getOpacity();
        return Color.color(
                front.getRed() * a + back.getRed() * (1 - a),
                front.getGreen() * a + back.getGreen() * (1 - a),
                front.getBlue() * a + back.getBlue() * (1 - a));
    }

    // WCAG 2.2's contrast ratio, from the relative luminance of each colour.
    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
