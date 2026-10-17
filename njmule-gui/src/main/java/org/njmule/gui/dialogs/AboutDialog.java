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

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;

/** Name, version, copyright and licence. */
public final class AboutDialog {

    private final Stage stage = new Stage();

    public AboutDialog(Gui gui, Window owner) {
        this.stage.initOwner(owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(gui.t("nj.about.title"));
        // The logo is never mirrored; the name, the tagline and the copyright are English.
        ImageView logo = Fx.leftToRight(new ImageView());
        var icon = AboutDialog.class.getResource("/org/njmule/gui/icons/njmule-128.png");
        if (icon != null) {
            logo.setImage(new Image(icon.toExternalForm()));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
        }
        Label name = Fx.leftToRight(new Label(NjMuleInfo.NAME + " " + NjMuleInfo.version()));
        name.getStyleClass().addAll(Styles.TITLE_2, "display");
        Label tagline = Fx.leftToRight(new Label(NjMuleInfo.TAGLINE));
        tagline.getStyleClass().add(Styles.TEXT_MUTED);
        Label copyright = Fx.leftToRight(new Label(NjMuleInfo.COPYRIGHT));
        Label license = new Label(gui.f("nj.about.license", NjMuleInfo.LICENSE_ID));
        license.setWrapText(true);
        Label emule = new Label(gui.t("nj.about.emule"));
        emule.setWrapText(true);
        emule.getStyleClass().add(Styles.TEXT_SMALL);
        HBox header = new HBox(16, logo, new VBox(4, name, tagline));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox root = new VBox(
                12,
                header,
                copyright,
                license,
                emule,
                Fx.toolRow(Fx.spacer(), Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close)));
        root.setPadding(new Insets(18));
        root.setPrefWidth(520);
        Scene scene = new Scene(root);
        gui.attach(scene);
        this.stage.setScene(scene);
    }

    public void show() {
        this.stage.show();
    }

    Stage stage() {
        return this.stage;
    }
}
