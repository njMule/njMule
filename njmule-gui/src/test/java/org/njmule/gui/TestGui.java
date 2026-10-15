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
package org.njmule.gui;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.njmule.app.Autostart;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Subscriptions;

/** An engine, its model and the screens' context, for one GUI test; close it at the end. */
public final class TestGui implements AutoCloseable {

    public final NjMuleEngine engine;
    public final EngineModel model;
    public final Gui gui;

    /** What the user was told, by the screens or the model. */
    public final List<String> told;

    /** The sound files played, in order; no sound is ever heard. */
    public final List<Path> played;

    /** The actions offered with a message, such as an undo, in order. */
    public final List<Runnable> offered;

    private final AtomicInteger relaunches;
    private Stage stage;

    private TestGui(
            NjMuleEngine engine,
            EngineModel model,
            Gui gui,
            List<String> told,
            List<Path> played,
            List<Runnable> offered,
            AtomicInteger relaunches) {
        this.engine = engine;
        this.model = model;
        this.gui = gui;
        this.told = told;
        this.played = played;
        this.offered = offered;
        this.relaunches = relaunches;
    }

    /** Starts the toolkit, an engine under {@code root} and the model, in English. */
    public static TestGui start(Path root) throws Exception {
        return start(root, UnaryOperator.identity());
    }

    /**
     * As {@link #start(Path)}, with the model seeing the engine through {@code wrap}; {@link #engine} is the real one.
     */
    public static TestGui start(Path root, UnaryOperator<NjMuleEngine> wrap) throws Exception {
        return start(root, wrap, Optional.empty());
    }

    /** As {@link #start(Path)}, with {@code autostart} as the start at login: a pretend one, never the system's. */
    public static TestGui start(Path root, Optional<Autostart> autostart) throws Exception {
        return start(root, UnaryOperator.identity(), autostart);
    }

    /** As {@link #start(Path)}, with the interface in {@code language}. */
    public static TestGui start(Path root, Locale language) throws Exception {
        return start(root, UnaryOperator.identity(), Optional.empty(), I18n.of(language));
    }

    private static TestGui start(Path root, UnaryOperator<NjMuleEngine> wrap, Optional<Autostart> autostart)
            throws Exception {
        return start(root, wrap, autostart, I18n.of(Locale.ENGLISH));
    }

    private static TestGui start(Path root, UnaryOperator<NjMuleEngine> wrap, Optional<Autostart> autostart, I18n i18n)
            throws Exception {
        FxTesting.start();
        NjMuleEngine engine = FxTesting.engine(root);
        EngineModel model = new EngineModel(wrap.apply(engine), Platform::runLater);
        List<String> told = new CopyOnWriteArrayList<>();
        List<Path> played = new CopyOnWriteArrayList<>();
        List<Runnable> offered = new CopyOnWriteArrayList<>();
        AtomicInteger relaunches = new AtomicInteger();
        Gui gui = FxTesting.onFx(() -> new Gui(
                i18n,
                Formats.of(i18n),
                model,
                new Themes(Themes.Family.NORD, GuiSettings.Mode.LIGHT),
                GuiSettings.load(engine.directories().gui()),
                engine.directories(),
                told::add,
                told::add,
                (text, action, run) -> {
                    told.add(text);
                    offered.add(run);
                },
                relaunches::incrementAndGet,
                new Subscriptions(),
                played::add,
                autostart));
        model.onError(told::add);
        model.start();
        return new TestGui(engine, model, gui, told, played, offered, relaunches);
    }

    public int relaunches() {
        return this.relaunches.get();
    }

    /** Shows {@code node} in a window, so it gets a scene, CSS and a size. */
    public void show(Node node) throws Exception {
        FxTesting.onFx(() -> {
            if (this.stage == null) {
                this.stage = new Stage();
            }
            Parent root = node instanceof Parent p ? p : new StackPane(node);
            Scene scene = new Scene(root, 1100, 720);
            this.gui.attach(scene);
            this.stage.setScene(scene);
            this.stage.show();
        });
    }

    @Override
    public void close() {
        try {
            if (this.stage != null) {
                FxTesting.onFx(this.stage::close);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            this.model.close();
            this.engine.close();
        }
    }
}
