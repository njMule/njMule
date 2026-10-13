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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.Formats;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.model.EngineModel;
import org.njmule.gui.ui.Gui;

/** An engine, its model and the screens' context, for one GUI test; close it at the end. */
public final class TestGui implements AutoCloseable {

    public final NjMuleEngine engine;
    public final EngineModel model;
    public final Gui gui;

    /** What the user was told, by the screens or the model. */
    public final List<String> told;

    private final AtomicInteger relaunches;
    private Stage stage;

    private TestGui(
            NjMuleEngine engine,
            EngineModel model,
            Gui gui,
            List<String> told,
            AtomicInteger relaunches) {
        this.engine = engine;
        this.model = model;
        this.gui = gui;
        this.told = told;
        this.relaunches = relaunches;
    }

    /** Starts the toolkit, an engine under {@code root} and the model, in English. */
    public static TestGui start(Path root) throws Exception {
        FxTesting.start();
        NjMuleEngine engine = FxTesting.engine(root);
        EngineModel model = new EngineModel(engine, Platform::runLater);
        List<String> told = new CopyOnWriteArrayList<>();
        AtomicInteger relaunches = new AtomicInteger();
        Gui gui =
                FxTesting.onFx(
                        () ->
                                new Gui(
                                        I18n.of(Locale.ENGLISH),
                                        new Formats(Locale.ENGLISH),
                                        model,
                                        new Themes(Themes.Family.NORD, GuiSettings.Mode.LIGHT),
                                        GuiSettings.load(engine.directories().gui()),
                                        engine.directories(),
                                        told::add,
                                        relaunches::incrementAndGet));
        model.onError(told::add);
        model.start();
        return new TestGui(engine, model, gui, told, relaunches);
    }

    public int relaunches() {
        return this.relaunches.get();
    }

    /** Shows {@code node} in a window, so it gets a scene, CSS and a size. */
    public void show(Node node) throws Exception {
        FxTesting.onFx(
                () -> {
                    if (this.stage == null) {
                        this.stage = new Stage();
                    }
                    Parent root = node instanceof Parent p ? p : new StackPane(node);
                    Scene scene = new Scene(root, 1100, 720);
                    this.gui.themes().attach(scene);
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
