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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.gui.FxTesting;

/** The notification area's own logic; the icon's work for the AWT thread is kept, never done on the system. */
@Timeout(60)
class NotificationAreaTest {

    // What the area hands to the AWT thread, in order; the tests run only the parts that leave the system alone.
    private final List<Runnable> awt = new CopyOnWriteArrayList<>();
    private final List<String> handled = new CopyOnWriteArrayList<>();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private Stage stage;

    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @AfterEach
    void close() throws Exception {
        FxTesting.onFx(() -> {
            if (this.stage != null) {
                this.stage.close();
            }
            // Bringing a window back lets the toolkit end with its last window: never in the tests.
            Platform.setImplicitExit(false);
        });
    }

    @Test
    void aMinimisedWindowHidesInTheAreaAndComesBack() throws Exception {
        NotificationArea area = this.attach(true);
        FxTesting.onFx(() -> {
            area.notify("not hidden");
            area.tooltip("not hidden");
        });
        assertThat(this.awt).as("no icon while the window shows").isEmpty();

        FxTesting.onFx(() -> this.stage.setIconified(true));
        assertThat(area.isHidden()).isTrue();
        assertThat(FxTesting.onFx(this.stage::isShowing)).isFalse();
        assertThat(this.awt).as("the icon comes").hasSize(1);

        FxTesting.onFx(() -> {
            area.notify("Download finished");
            area.tooltip("njMule - Download: 0 B/s");
            area.hideNow();
        });
        assertThat(this.awt).as("a message and a tooltip for the icon").hasSize(3);
        // Without an icon yet they change nothing.
        this.awt.get(1).run();
        this.awt.get(2).run();

        FxTesting.onFx(() -> {
            area.restore();
            Platform.setImplicitExit(false);
        });
        assertThat(area.isHidden()).isFalse();
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();
        assertThat(FxTesting.onFx(this.stage::isIconified)).isFalse();
        assertThat(this.handled).containsExactly("restored");
        assertThat(this.awt).as("the icon goes").hasSize(4);
        this.awt.get(3).run();

        FxTesting.onFx(area::restore);
        assertThat(this.handled).as("shown already").containsExactly("restored");

        // With the option off a window minimises as usual.
        this.enabled.set(false);
        FxTesting.onFx(() -> this.stage.setIconified(true));
        assertThat(area.isHidden()).isFalse();
        FxTesting.onFx(() -> this.stage.setIconified(false));

        FxTesting.onFx(area::hideNow);
        assertThat(area.isHidden())
                .as("hidden at once, as at a start minimised")
                .isTrue();
        FxTesting.onFx(area::close);
        assertThat(area.isHidden()).isFalse();
        assertThat(this.awt).hasSize(6);
    }

    @Test
    void withoutANotificationAreaTheWindowMinimisesAsUsual() throws Exception {
        NotificationArea area = this.attach(false);

        FxTesting.onFx(() -> this.stage.setIconified(true));
        FxTesting.onFx(area::hideNow);

        assertThat(area.isHidden()).isFalse();
        assertThat(this.awt).isEmpty();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            assertThat(NotificationArea.isSupported()).as("only on Windows").isFalse();
        }
    }

    @Test
    void aClickWaitsForASecondOneBeforeItOpensTheStatusWindow() throws Exception {
        NotificationArea area = this.attach(true);

        FxTesting.onFx(area::clicked);
        FxTesting.await("the click", () -> this.handled.contains("click"));

        // A double click brings the window back instead.
        FxTesting.onFx(() -> {
            area.clicked();
            area.restore();
        });
        Thread.sleep(1200);
        assertThat(this.handled).containsExactly("click");
    }

    private NotificationArea attach(boolean supported) throws Exception {
        return FxTesting.onFx(() -> {
            this.stage = new Stage();
            this.stage.setScene(new Scene(new StackPane(), 200, 100));
            this.stage.show();
            return NotificationArea.attach(
                    this.stage,
                    this.enabled::get,
                    new NotificationArea.Handlers() {
                        @Override
                        public void click() {
                            NotificationAreaTest.this.handled.add("click");
                        }

                        @Override
                        public void menu(double x, double y) {
                            NotificationAreaTest.this.handled.add("menu");
                        }

                        @Override
                        public void restored() {
                            NotificationAreaTest.this.handled.add("restored");
                        }
                    },
                    () -> supported,
                    this.awt::add);
        });
    }
}
