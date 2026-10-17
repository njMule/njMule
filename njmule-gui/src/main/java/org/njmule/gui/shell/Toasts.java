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

import atlantafx.base.controls.Notification;
import java.util.List;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.njmule.gui.ui.Icon;
import org.njmule.gui.ui.Icons;

/**
 * Short messages over the bottom of the window, centred above the status strip: at most two at once, so they never
 * cover the lists; a problem stays until closed, and the same message again counts up on the one shown.
 */
final class Toasts {

    /** The messages shown at once: a new one pushes the oldest out. */
    static final int MAX = 2;

    private static final Duration MESSAGE_TIME = Duration.seconds(6);
    // Within the time the engine keeps what an undo needs.
    private static final Duration OFFER_TIME = Duration.seconds(10);
    private static final String SHOWN = "toast-shown";
    private static final double WIDTH = 400;

    private final VBox stack = new VBox(8);

    Toasts() {
        this.stack.getStyleClass().add("toasts");
        this.stack.setAlignment(Pos.BOTTOM_CENTER);
        this.stack.setPickOnBounds(false);
        this.stack.setPadding(new Insets(16, 16, 40, 16));
        this.stack.setMaxWidth(WIDTH + 32);
        this.stack.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(this.stack, Pos.BOTTOM_CENTER);
    }

    Node view() {
        return this.stack;
    }

    List<Node> shown() {
        return this.stack.getChildren();
    }

    /**
     * Shows {@code text} with {@code glyph}, styled as {@code style} (none for a mere message); it goes after a while
     * when it {@code fades}. The same text in the same style already shown counts up instead.
     */
    Notification show(String text, Icons glyph, String style, boolean fades) {
        for (Node node : this.stack.getChildren()) {
            if (node.getProperties().get(SHOWN) instanceof Shown same
                    && same.text.equals(text)
                    && Objects.equals(same.style, style)) {
                Notification note = (Notification) node;
                same.count++;
                note.setMessage(text + "  ×" + same.count);
                // The latest goes last, nearest the eye, and stays its whole time again.
                this.stack.getChildren().remove(note);
                this.stack.getChildren().add(note);
                if (same.fade != null) {
                    same.fade.playFromStart();
                }
                return note;
            }
        }
        Notification note = add(text, glyph, style);
        Shown shown = new Shown(text, style);
        note.getProperties().put(SHOWN, shown);
        if (fades) {
            shown.fade = fadeLater(note, MESSAGE_TIME);
        }
        return note;
    }

    /** Shows {@code text} with a button {@code action} that runs {@code run}, long enough to find it. */
    void offer(String text, String action, Runnable run) {
        Notification note = add(text, Icons.INFO, null);
        Button button = new Button(action);
        button.setOnAction(e -> {
            this.stack.getChildren().remove(note);
            run.run();
        });
        note.setPrimaryActions(button);
        fadeLater(note, OFFER_TIME);
    }

    private Notification add(String text, Icons glyph, String style) {
        Notification note = new Notification(text, new Icon(glyph));
        note.getStyleClass().add("elevated-1");
        if (style != null) {
            note.getStyleClass().add(style);
        }
        note.setPrefWidth(WIDTH);
        note.setMaxWidth(WIDTH);
        note.setOnClose(e -> this.stack.getChildren().remove(note));
        this.stack.getChildren().add(note);
        while (this.stack.getChildren().size() > MAX) {
            this.stack.getChildren().removeFirst();
        }
        return note;
    }

    private PauseTransition fadeLater(Notification note, Duration time) {
        PauseTransition later = new PauseTransition(time);
        later.setOnFinished(e -> this.stack.getChildren().remove(note));
        later.play();
        return later;
    }

    /** What a message shown says, and how many times it came. */
    private static final class Shown {
        private final String text;
        private final String style;
        private int count = 1;
        private PauseTransition fade;

        private Shown(String text, String style) {
            this.text = text;
            this.style = style;
        }
    }
}
