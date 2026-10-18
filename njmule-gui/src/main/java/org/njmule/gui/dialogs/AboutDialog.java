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
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.gui.ui.DialogFrame;
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
        ImageView logo = Fx.logo(72);
        Label name = Fx.leftToRight(new Label(NjMuleInfo.NAME + " " + NjMuleInfo.version()));
        name.getStyleClass().addAll(Styles.TITLE_2, "display");
        Label tagline = Fx.leftToRight(new Label(NjMuleInfo.TAGLINE));
        tagline.getStyleClass().add(Styles.TEXT_MUTED);
        Label copyright = Fx.leftToRight(new Label(NjMuleInfo.COPYRIGHT));
        Label license = new Label(gui.f("nj.about.license", NjMuleInfo.LICENSE_ID));
        license.setWrapText(true);
        Label emule = new Label(gui.t("nj.about.emule"));
        emule.setWrapText(true);
        emule.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        HBox header = new HBox(16, logo, new VBox(4, name, tagline));
        header.setAlignment(Pos.CENTER_LEFT);
        Button copy = Fx.button(gui.t("nj.about.copyVersion"), Icons.COPY, () -> copyVersion(gui));
        copy.getStyleClass().add(Styles.FLAT);
        VBox body = new VBox(12, header, copyright, license, new HBox(copy), emule);
        body.getStyleClass().add("about-body");
        Button close = Fx.button(gui.t("IDS_FD_CLOSE"), Icons.CLOSE, this.stage::close);
        close.setCancelButton(true);
        VBox root = new VBox(
                body, DialogFrame.footer(new Region(), DialogFrame.role(close, ButtonBar.ButtonData.CANCEL_CLOSE)));
        root.setPrefWidth(520);
        Scene scene = new Scene(root);
        gui.attach(scene);
        this.stage.setScene(scene);
    }

    public void show() {
        this.stage.show();
    }

    // What a report of a problem needs: njMule's version, Java's and the system's.
    private static void copyVersion(Gui gui) {
        ClipboardContent content = new ClipboardContent();
        content.putString(versions());
        Clipboard.getSystemClipboard().setContent(content);
        gui.inform().accept(gui.t("nj.about.copied"));
    }

    static String versions() {
        return NjMuleInfo.NAME + " " + NjMuleInfo.version() + " · Java " + System.getProperty("java.version") + " · "
                + System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch");
    }

    Stage stage() {
        return this.stage;
    }
}
