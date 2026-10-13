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
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.PartMap;
import org.njmule.gui.FxTesting;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;

class PartBarTest {

    private static final long PART = PartMap.PART_SIZE;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @Test
    void drawsWhatWeHaveWhatIsMissingAndWhatIsRequested() throws Exception {
        // Ten parts: the first two done, part 2 requested, parts 3-5 with sources, the rest with
        // none.
        PartMap map = new PartMap(
                10 * PART,
                List.of(new PartMap.Span(2 * PART, 10 * PART)),
                List.of(new PartMap.Span(2 * PART, 3 * PART)),
                List.of(0, 0, 3, 1, 5, 12, 0, 0, 0, 0));
        for (GuiSettings.Mode mode : List.of(GuiSettings.Mode.LIGHT, GuiSettings.Mode.DARK)) {
            FxTesting.onFx(() -> {
                PartBar bar = new PartBar();
                bar.setPrefSize(400, 16);
                VBox box = new VBox(bar);
                Scene scene = new Scene(box, 400, 16);
                Themes themes = new Themes(Themes.Family.NORD, mode);
                themes.attach(scene);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                box.applyCss();
                box.layout();
                bar.set(map, 2 * PART);
                WritableImage image = bar.snapshot(null, null);
                FxTesting.snapshot(bar, "partbar-" + mode.name().toLowerCase(Locale.ROOT));
                Color have = image.getPixelReader().getColor(20, 10);
                Color requested = image.getPixelReader().getColor(100, 10);
                Color fewSources = image.getPixelReader().getColor(140, 10);
                Color manySources = image.getPixelReader().getColor(220, 10);
                Color none = image.getPixelReader().getColor(350, 10);
                Color strip = image.getPixelReader().getColor(20, 1);
                assertThat(List.of(requested, fewSources, manySources, none)).doesNotContain(have);
                assertThat(fewSources).isNotEqualTo(manySources);
                assertThat(none).isNotEqualTo(manySources);
                assertThat(strip).isNotEqualTo(have);
                stage.close();
                return null;
            });
        }
    }
}
