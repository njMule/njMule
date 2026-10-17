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

import javafx.geometry.Bounds;
import javafx.geometry.NodeOrientation;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.njmule.gui.FxTesting;

@Timeout(30)
class IconTest {

    private Icon icon;
    private Label label;
    private Bounds drawn;

    @BeforeAll
    static void startFx() throws InterruptedException {
        FxTesting.start();
    }

    @Test
    void shouldTakeTheSizeTheStylesheetGives() throws Exception {
        givenAnIcon(Icons.SEARCH);

        whenStyled("-nj-icon-size: 22px;");

        thenItsSideIs(22);
    }

    @Test
    void shouldTakeTheColourOfTheTextBesideIt() throws Exception {
        givenAnIcon(Icons.DOWNLOAD);

        whenPutInALabelWrittenIn(Color.web("#994E55"));

        thenItIsDrawnIn(Color.web("#994E55"));
    }

    @Test
    void shouldKeepAColourOfItsOwnOverTheTextBesideIt() throws Exception {
        givenAnIcon(Icons.CHECK_CIRCLE);
        givenPutInALabelWrittenIn(Color.web("#994E55"));

        whenStyled("-nj-icon-color: #3A6867;");

        thenItIsDrawnIn(Color.web("#3A6867"));
    }

    @Test
    void shouldFollowTheTextWhenItsColourChanges() throws Exception {
        givenAnIcon(Icons.PAUSE);
        givenPutInALabelWrittenIn(Color.web("#2E3440"));

        whenTheLabelIsWrittenIn(Color.web("#476283"));

        thenItIsDrawnIn(Color.web("#476283"));
    }

    @Test
    void shouldBeMirroredInRightToLeftWhenItPointsTheWayTextRuns() throws Exception {
        givenAnIcon(Icons.SEND);

        whenPutInARightToLeftLayout();

        thenItIsDrawn(NodeOrientation.RIGHT_TO_LEFT);
    }

    @Test
    void shouldStayLeftToRightInRightToLeftWhenItPointsNowhere() throws Exception {
        givenAnIcon(Icons.PLAY);

        whenPutInARightToLeftLayout();

        thenItIsDrawn(NodeOrientation.LEFT_TO_RIGHT);
    }

    @Test
    void shouldFillAFilledIcon() throws Exception {
        givenAnIcon(Icons.DOT);

        whenPutInALabelWrittenIn(Color.web("#457B7A"));

        thenItIsFilledIn(Color.web("#457B7A"));
    }

    @Test
    void shouldDrawTheNewGlyphWhenChanged() throws Exception {
        givenAnIcon(Icons.LINK);

        whenTheGlyphBecomes(Icons.POWER);

        thenItsGlyphIs(Icons.POWER);
        thenItDrawsThePathOf(Icons.POWER);
    }

    @ParameterizedTest
    @EnumSource(Icons.class)
    void shouldStayInsideTheGrid(Icons glyph) throws Exception {
        whenThePathIsDrawn(glyph);

        thenItIsInsideTheGrid();
    }

    private void givenAnIcon(Icons glyph) throws Exception {
        this.icon = FxTesting.onFx(() -> new Icon(glyph));
    }

    private void givenPutInALabelWrittenIn(Color color) throws Exception {
        putInALabel(color);
    }

    private void whenPutInARightToLeftLayout() throws Exception {
        FxTesting.onFx(() -> {
            Group root = new Group(this.icon);
            root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
            new Scene(root);
        });
    }

    private void whenStyled(String style) throws Exception {
        FxTesting.onFx(() -> {
            this.icon.setStyle(style);
            Group root = this.label != null ? new Group(this.label) : new Group(this.icon);
            new Scene(root);
            root.applyCss();
            root.layout();
        });
    }

    private void whenPutInALabelWrittenIn(Color color) throws Exception {
        putInALabel(color);
    }

    private void whenTheLabelIsWrittenIn(Color color) throws Exception {
        FxTesting.onFx(() -> this.label.setTextFill(color));
    }

    private void whenTheGlyphBecomes(Icons glyph) throws Exception {
        FxTesting.onFx(() -> this.icon.setGlyph(glyph));
    }

    private void whenThePathIsDrawn(Icons glyph) throws Exception {
        this.drawn = FxTesting.onFx(() -> {
            SVGPath path = new SVGPath();
            path.setContent(glyph.path());
            return path.getLayoutBounds();
        });
    }

    private void thenItsSideIs(double side) throws Exception {
        assertThat(FxTesting.onFx(() -> this.icon.prefWidth(-1))).isEqualTo(side);
        assertThat(FxTesting.onFx(() -> this.icon.prefHeight(-1))).isEqualTo(side);
    }

    private void thenItIsDrawnIn(Color color) throws Exception {
        assertThat(FxTesting.onFx(() -> shape().getStroke())).isEqualTo(color);
    }

    private void thenItIsFilledIn(Color color) throws Exception {
        assertThat(FxTesting.<Paint>onFx(() -> shape().getFill())).isEqualTo(color);
        assertThat(FxTesting.onFx(() -> shape().getStroke())).isNull();
    }

    private void thenItIsDrawn(NodeOrientation orientation) throws Exception {
        assertThat(FxTesting.onFx(this.icon::getEffectiveNodeOrientation)).isEqualTo(orientation);
    }

    private void thenItsGlyphIs(Icons glyph) throws Exception {
        assertThat(FxTesting.onFx(this.icon::getGlyph)).isEqualTo(glyph);
    }

    private void thenItDrawsThePathOf(Icons glyph) throws Exception {
        assertThat(FxTesting.onFx(() -> shape().getContent())).isEqualTo(glyph.path());
    }

    private void thenItIsInsideTheGrid() {
        assertThat(this.drawn.isEmpty()).isFalse();
        assertThat(this.drawn.getMinX()).isGreaterThanOrEqualTo(0);
        assertThat(this.drawn.getMinY()).isGreaterThanOrEqualTo(0);
        assertThat(this.drawn.getMaxX()).isLessThanOrEqualTo(24);
        assertThat(this.drawn.getMaxY()).isLessThanOrEqualTo(24);
    }

    private void putInALabel(Color color) throws Exception {
        // The label's skin puts the graphic among its children once the label is styled in a scene.
        this.label = FxTesting.onFx(() -> {
            Label text = new Label("Download", this.icon);
            text.setTextFill(color);
            Group root = new Group(text);
            new Scene(root);
            root.applyCss();
            root.layout();
            return text;
        });
    }

    private SVGPath shape() {
        return (SVGPath) this.icon.getChildrenUnmodifiable().getFirst();
    }
}
