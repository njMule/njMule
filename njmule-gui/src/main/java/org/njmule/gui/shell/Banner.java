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

import java.util.Objects;
import java.util.Optional;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;

/**
 * A line above the screens for a state that lasts and wants something done: no network at all, or a network others
 * cannot reach us on. It waits a moment before it shows, so a reconnection does not make it flicker, and once closed it
 * stays away until the state changes.
 */
final class Banner {

    /** What the banner tells of. */
    enum Kind {
        NONE,
        OFFLINE,
        UNREACHABLE
    }

    static final Duration SETTLE = Duration.seconds(2);

    private final Gui gui;
    private final HBox box = new HBox();
    private final FontIcon icon = new FontIcon();
    private final Label text = new Label();
    private final Button action = new Button();
    private final PauseTransition settling = new PauseTransition(SETTLE);
    private final Runnable connect;
    private final Runnable options;
    private Kind wanted = Kind.NONE;
    private Kind shown = Kind.NONE;
    private Kind closed = Kind.NONE;
    private int port;

    Banner(Gui gui, Runnable connect, Runnable options) {
        this.gui = gui;
        this.connect = connect;
        this.options = options;
        this.text.setWrapText(true);
        this.text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(this.text, Priority.ALWAYS);
        this.icon.getStyleClass().add("banner-icon");
        this.action.setOnAction(e -> (this.shown == Kind.OFFLINE ? this.connect : this.options).run());
        Fx.whole(this.action);
        var close = Fx.iconButton(gui.t("IDS_FD_CLOSE"), Material2AL.CLOSE, () -> {
            this.closed = this.shown;
            show(Kind.NONE);
        });
        close.getStyleClass().add("flat");
        this.box.getChildren().setAll(this.icon, this.text, this.action, close);
        this.box.setAlignment(Pos.CENTER_LEFT);
        this.box.getStyleClass().add("banner");
        this.settling.setOnFinished(e -> show(this.wanted == this.closed ? Kind.NONE : this.wanted));
        show(Kind.NONE);
    }

    HBox view() {
        return this.box;
    }

    Kind shown() {
        return this.shown;
    }

    /** Looks at the state again; a change shows after a moment. */
    void update(EngineModel.Connection connection, EngineStatus status) {
        Kind kind = kindOf(connection, status);
        this.port = status.tcpPort();
        if (kind != this.wanted) {
            this.wanted = kind;
            if (kind != this.closed) {
                this.closed = Kind.NONE;
            }
            this.settling.playFromStart();
        }
    }

    // Offline when no server is connected or tried and Kad does not run; unreachable when a network is up but
    // others cannot connect to us.
    static Kind kindOf(EngineModel.Connection connection, EngineStatus status) {
        Optional<KadInfo> kad = status.kad();
        if (connection == EngineModel.Connection.DISCONNECTED && kad.isEmpty()) {
            return Kind.OFFLINE;
        }
        boolean lowId = connection == EngineModel.Connection.CONNECTED && status.lowId();
        boolean kadFirewalled =
                kad.isPresent() && kad.get().connected() && kad.get().firewalled();
        boolean reachable = (connection == EngineModel.Connection.CONNECTED && !status.lowId())
                || (kad.isPresent() && kad.get().connected() && !kad.get().firewalled());
        return (lowId || kadFirewalled) && !reachable ? Kind.UNREACHABLE : Kind.NONE;
    }

    private void show(Kind kind) {
        this.shown = Objects.requireNonNull(kind, "kind");
        this.box.getStyleClass().removeAll("offline", "unreachable");
        boolean on = kind != Kind.NONE;
        this.box.setVisible(on);
        this.box.setManaged(on);
        switch (kind) {
            case OFFLINE -> {
                this.box.getStyleClass().add("offline");
                this.icon.setIconCode(Material2AL.CLOUD_OFF);
                this.text.setText(this.gui.t("nj.banner.offline"));
                this.action.setText(this.gui.t("IDS_MAIN_BTN_CONNECT"));
            }
            case UNREACHABLE -> {
                this.box.getStyleClass().add("unreachable");
                this.icon.setIconCode(Material2MZ.WARNING);
                this.text.setText(this.gui.f("nj.banner.unreachable", this.port));
                this.action.setText(this.gui.t("IDS_EM_PREFS"));
            }
            case NONE -> this.text.setText("");
        }
    }
}
