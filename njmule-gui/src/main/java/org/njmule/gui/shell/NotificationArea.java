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

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import org.njmule.core.api.NjMuleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The njMule icon in the notification area (the Windows tray, next to the clock): a window minimised while the setting
 * is on hides into it. A click opens the small status window, a double click brings the main window back, the right
 * button opens the menu. Only on Windows; elsewhere the window minimises as usual. FX thread, except the icon's own
 * events.
 */
public final class NotificationArea implements AutoCloseable {

    /** What the clicks on the icon do, on the FX thread; {@code menu} gets the screen point clicked. */
    public interface Handlers {
        void click();

        void menu(double x, double y);

        /** The window came back: whatever the icon opened can go. */
        void restored();
    }

    private static final Logger logger = LoggerFactory.getLogger(NotificationArea.class);
    private static final String ICON = "/org/njmule/gui/icons/njmule-32.png";
    private static final int DOUBLE_CLICK_MILLIS = 500;

    private final Stage stage;
    private final BooleanSupplier enabled;
    private final Handlers handlers;
    // A click waits this long for a second one before it counts as single.
    private final PauseTransition single;
    // AWT event thread only.
    private TrayIcon icon;
    private volatile boolean hidden;

    private NotificationArea(Stage stage, BooleanSupplier enabled, Handlers handlers) {
        this.stage = stage;
        this.enabled = enabled;
        this.handlers = handlers;
        this.single = new PauseTransition(Duration.millis(doubleClickMillis()));
        this.single.setOnFinished(e -> this.handlers.click());
    }

    /** Whether this system has a notification area njMule uses. */
    public static boolean isSupported() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        try {
            return SystemTray.isSupported();
        } catch (RuntimeException | LinkageError e) {
            logger.debug("No notification area: {}", e.toString());
            return false;
        }
    }

    /** Watches {@code stage}: minimised while {@code enabled} says so, it hides into the notification area. */
    public static NotificationArea attach(Stage stage, BooleanSupplier enabled, Handlers handlers) {
        NotificationArea area = new NotificationArea(
                Objects.requireNonNull(stage, "stage"),
                Objects.requireNonNull(enabled, "enabled"),
                Objects.requireNonNull(handlers, "handlers"));
        stage.iconifiedProperty().addListener((o, was, now) -> {
            if (now && !area.hidden && area.enabled.getAsBoolean() && isSupported()) {
                area.hide();
            }
        });
        return area;
    }

    /** Whether the window is hidden in the notification area. */
    public boolean isHidden() {
        return this.hidden;
    }

    /** Brings the window back from the notification area, if it is there. */
    public void restore() {
        this.single.stop();
        if (!this.hidden) {
            return;
        }
        this.hidden = false;
        EventQueue.invokeLater(this::removeIcon);
        this.stage.show();
        this.stage.setIconified(false);
        this.stage.toFront();
        Platform.setImplicitExit(true);
        this.handlers.restored();
    }

    /** Shows {@code text} as a notification of the icon while the window is hidden. */
    public void notify(String text) {
        if (this.hidden) {
            EventQueue.invokeLater(() -> {
                if (this.icon != null) {
                    this.icon.displayMessage(NjMuleInfo.NAME, text, TrayIcon.MessageType.INFO);
                }
            });
        }
    }

    /** The text shown when the pointer rests on the icon. */
    public void tooltip(String text) {
        if (this.hidden) {
            EventQueue.invokeLater(() -> {
                if (this.icon != null) {
                    this.icon.setToolTip(text);
                }
            });
        }
    }

    @Override
    public void close() {
        this.hidden = false;
        EventQueue.invokeLater(this::removeIcon);
    }

    // The window goes, the icon comes: without the implicit exit off, hiding the last window would end the app.
    private void hide() {
        this.hidden = true;
        Platform.setImplicitExit(false);
        this.stage.hide();
        EventQueue.invokeLater(this::showIcon);
    }

    private void clicked() {
        this.single.playFromStart();
    }

    private void showIcon() {
        try {
            if (this.icon == null) {
                this.icon = this.create();
            }
            SystemTray.getSystemTray().add(this.icon);
        } catch (AWTException | IOException | RuntimeException e) {
            logger.warn("Cannot show the icon in the notification area: {}", e.toString());
            Platform.runLater(this::restore);
        }
    }

    private void removeIcon() {
        if (this.icon != null) {
            SystemTray.getSystemTray().remove(this.icon);
        }
    }

    private TrayIcon create() throws IOException {
        Image image;
        try (InputStream in = NotificationArea.class.getResourceAsStream(ICON)) {
            if (in == null) {
                throw new IOException("missing " + ICON);
            }
            image = ImageIO.read(in);
        }
        TrayIcon created = new TrayIcon(image, NjMuleInfo.NAME);
        created.setImageAutoSize(true);
        created.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (e.getClickCount() >= 2) {
                    Platform.runLater(NotificationArea.this::restore);
                } else {
                    Platform.runLater(NotificationArea.this::clicked);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    double x = e.getX();
                    double y = e.getY();
                    Platform.runLater(() -> {
                        NotificationArea.this.single.stop();
                        NotificationArea.this.handlers.menu(x, y);
                    });
                }
            }
        });
        return created;
    }

    private static int doubleClickMillis() {
        try {
            return Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") instanceof Integer millis
                    ? millis
                    : DOUBLE_CLICK_MILLIS;
        } catch (RuntimeException | LinkageError e) {
            return DOUBLE_CLICK_MILLIS;
        }
    }
}
