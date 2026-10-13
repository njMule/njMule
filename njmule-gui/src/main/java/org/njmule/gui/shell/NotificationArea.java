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
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.njmule.core.api.NjMuleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The njMule icon in the notification area (the Windows tray, next to the clock): a window minimised while the setting
 * is on hides into it, and a click on the icon, or Open in its menu, brings the window back. Only on Windows; elsewhere
 * the window minimises as usual. FX thread, except the icon's own events.
 */
public final class NotificationArea implements AutoCloseable {

    /** The texts of the icon's menu. */
    public record Texts(String open, String exit) {}

    private static final Logger logger = LoggerFactory.getLogger(NotificationArea.class);
    private static final String ICON = "/org/njmule/gui/icons/njmule-32.png";

    private final Stage stage;
    private final BooleanSupplier enabled;
    private final Texts texts;
    // AWT event thread only.
    private TrayIcon icon;
    private volatile boolean hidden;

    private NotificationArea(Stage stage, BooleanSupplier enabled, Texts texts) {
        this.stage = stage;
        this.enabled = enabled;
        this.texts = texts;
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
    public static NotificationArea attach(Stage stage, BooleanSupplier enabled, Texts texts) {
        NotificationArea area = new NotificationArea(
                Objects.requireNonNull(stage, "stage"),
                Objects.requireNonNull(enabled, "enabled"),
                Objects.requireNonNull(texts, "texts"));
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
        if (!this.hidden) {
            return;
        }
        this.hidden = false;
        EventQueue.invokeLater(this::removeIcon);
        this.stage.show();
        this.stage.setIconified(false);
        this.stage.toFront();
        Platform.setImplicitExit(true);
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
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem(this.texts.open());
        open.addActionListener(e -> Platform.runLater(this::restore));
        MenuItem exit = new MenuItem(this.texts.exit());
        exit.addActionListener(e -> Platform.runLater(Platform::exit));
        menu.add(open);
        menu.addSeparator();
        menu.add(exit);
        TrayIcon created = new TrayIcon(image, NjMuleInfo.NAME, menu);
        created.setImageAutoSize(true);
        created.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    Platform.runLater(NotificationArea.this::restore);
                }
            }
        });
        return created;
    }
}
