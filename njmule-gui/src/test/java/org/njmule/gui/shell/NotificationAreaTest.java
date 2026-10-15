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
    private NotificationArea area;

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
    void shouldHideAMinimisedWindowInTheAreaAndBringItBack() throws Exception {
        givenTheWindowWithTheNotificationArea(true);
        givenTheAreaToldWhileTheWindowShows("not hidden");
        givenTheWindowMinimisedIntoTheArea();
        givenTheAreaToldWhileTheWindowIsHidden("Download finished", "njMule - Download: 0 B/s");
        givenTheWindowBroughtBack();
        givenTheWindowBroughtBackOnceMore();
        givenTheOptionOff();
        givenTheWindowMinimisedAsUsual();
        givenTheWindowHiddenAtOnce();

        whenTheAreaCloses();

        thenTheWindowIsHidden(false);
        thenTheWorkForTheIconIs(6);
    }

    @Test
    void shouldMinimiseTheWindowAsUsualWithoutANotificationArea() throws Exception {
        givenTheWindowWithTheNotificationArea(false);
        givenTheWindowMinimised();

        whenTheWindowIsHiddenAtOnce();

        thenTheWindowIsHidden(false);
        thenThereIsNoWorkForTheIcon();
        thenTheAreaIsSupportedOnlyOnWindows();
    }

    @Test
    void shouldWaitForASecondClickBeforeOpeningTheStatusWindow() throws Exception {
        givenTheWindowWithTheNotificationArea(true);
        givenAClickHandled();

        whenDoubleClicked();

        thenAfterAWhileTheHandledAre("click");
    }

    private void givenTheWindowWithTheNotificationArea(boolean supported) throws Exception {
        this.area = attach(supported);
    }

    private void givenTheAreaToldWhileTheWindowShows(String text) throws Exception {
        FxTesting.onFx(() -> {
            this.area.notify(text);
            this.area.tooltip(text);
        });
        assertThat(this.awt).as("no icon while the window shows").isEmpty();
    }

    private void givenTheWindowMinimised() throws Exception {
        FxTesting.onFx(() -> this.stage.setIconified(true));
    }

    private void givenTheWindowMinimisedIntoTheArea() throws Exception {
        givenTheWindowMinimised();
        assertThat(this.area.isHidden()).isTrue();
        assertThat(FxTesting.onFx(this.stage::isShowing)).isFalse();
        assertThat(this.awt).as("the icon comes").hasSize(1);
    }

    private void givenTheAreaToldWhileTheWindowIsHidden(String message, String tooltip) throws Exception {
        FxTesting.onFx(() -> {
            this.area.notify(message);
            this.area.tooltip(tooltip);
            this.area.hideNow();
        });
        assertThat(this.awt).as("a message and a tooltip for the icon").hasSize(3);
        // Without an icon yet they change nothing.
        this.awt.get(1).run();
        this.awt.get(2).run();
    }

    private void givenTheWindowBroughtBack() throws Exception {
        FxTesting.onFx(() -> {
            this.area.restore();
            Platform.setImplicitExit(false);
        });
        assertThat(this.area.isHidden()).isFalse();
        assertThat(FxTesting.onFx(this.stage::isShowing)).isTrue();
        assertThat(FxTesting.onFx(this.stage::isIconified)).isFalse();
        assertThat(this.handled).containsExactly("restored");
        assertThat(this.awt).as("the icon goes").hasSize(4);
        this.awt.get(3).run();
    }

    private void givenTheWindowBroughtBackOnceMore() throws Exception {
        FxTesting.onFx(this.area::restore);
        assertThat(this.handled).as("shown already").containsExactly("restored");
    }

    private void givenTheOptionOff() {
        this.enabled.set(false);
    }

    private void givenTheWindowMinimisedAsUsual() throws Exception {
        givenTheWindowMinimised();
        assertThat(this.area.isHidden()).isFalse();
        FxTesting.onFx(() -> this.stage.setIconified(false));
    }

    private void givenTheWindowHiddenAtOnce() throws Exception {
        whenTheWindowIsHiddenAtOnce();
        assertThat(this.area.isHidden())
                .as("hidden at once, as at a start minimised")
                .isTrue();
    }

    private void givenAClickHandled() throws Exception {
        FxTesting.onFx(this.area::clicked);
        FxTesting.await("the click", () -> this.handled.contains("click"));
    }

    private void whenTheWindowIsHiddenAtOnce() throws Exception {
        FxTesting.onFx(this.area::hideNow);
    }

    private void whenTheAreaCloses() throws Exception {
        FxTesting.onFx(this.area::close);
    }

    // A double click brings the window back instead.
    private void whenDoubleClicked() throws Exception {
        FxTesting.onFx(() -> {
            this.area.clicked();
            this.area.restore();
        });
    }

    private void thenTheWindowIsHidden(boolean hidden) {
        assertThat(this.area.isHidden()).isEqualTo(hidden);
    }

    private void thenTheWorkForTheIconIs(int count) {
        assertThat(this.awt).hasSize(count);
    }

    private void thenThereIsNoWorkForTheIcon() {
        assertThat(this.awt).isEmpty();
    }

    private void thenTheAreaIsSupportedOnlyOnWindows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            assertThat(NotificationArea.isSupported()).as("only on Windows").isFalse();
        }
    }

    private void thenAfterAWhileTheHandledAre(String... handled) throws Exception {
        Thread.sleep(1200);
        assertThat(this.handled).containsExactly(handled);
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
