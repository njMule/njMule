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
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.core.config.Preferences;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.PortTestPane;
import org.njmule.gui.ui.RateField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first start: the language and the look, the nick, the folders, the connection and an eMule to take over, one page
 * each. Every page starts from the current settings, so Next leaves them as they are; Skip changes nothing.
 */
public final class FirstRunWizard {

    /** What is left to do once the wizard is done, which needs the window around it. */
    public record Outcome(boolean languageChanged, boolean importEmule) {}

    /** A line with its speeds in Mbit/s. */
    record Line(int downMbits, int upMbits) {
        // Bits per second to the KiB/s the settings use.
        static int kib(int mbits) {
            return (int) Math.round(mbits * 1_000_000L / 8.0 / 1024);
        }
    }

    static final List<Line> LINES =
            List.of(new Line(20, 1), new Line(100, 20), new Line(300, 50), new Line(1000, 300), new Line(2500, 1000));

    private enum Page {
        START,
        NICK,
        FOLDERS,
        CONNECTION,
        EMULE
    }

    private static final Logger logger = LoggerFactory.getLogger(FirstRunWizard.class);

    private final Gui gui;
    private final Window owner;
    private final Preferences preferences;
    private final Optional<Path> emule;
    private final Consumer<Outcome> done;
    private final Stage stage = new Stage();
    private final List<Page> pages = new ArrayList<>();
    private final IntegerProperty at = new SimpleIntegerProperty();
    private final StackPane shown = new StackPane();
    private final HBox steps = new HBox(6);
    private final GuiSettings.Mode modeBefore;

    private final ComboBox<Optional<Locale>> language = new ComboBox<>();
    private final ComboBox<GuiSettings.Mode> mode = new ComboBox<>();
    private final TextField nick = new TextField();
    private final Input<String> nickInput;
    private final TextField incoming = new TextField();
    private final Input<Optional<Path>> incomingInput;
    private final ListView<Path> shared = new ListView<>();
    private final TextField tcp = new TextField();
    private final TextField udp = new TextField();
    private final Input<Integer> tcpInput;
    private final Input<Integer> udpInput;
    private final CheckBox upnp = new CheckBox();
    private final PortTestPane portTest;
    private final ComboBox<Line> line = new ComboBox<>();
    private final RateField<Integer> down;
    private final RateField<Integer> up;
    private final Input<Integer> downInput;
    private final Input<Integer> upInput;
    private final CheckBox importEmule = new CheckBox();
    private final Button back;
    private final Button next;
    private boolean finished;

    /**
     * @param preferences the settings the pages start from
     * @param emule the config directory of an eMule on this computer, if there is one
     * @param done what to do after Finish, with the window around: change the language, open the import
     */
    public FirstRunWizard(
            Gui gui, Window owner, Preferences preferences, Optional<Path> emule, Consumer<Outcome> done) {
        this.gui = gui;
        this.owner = owner;
        this.preferences = preferences;
        this.emule = emule;
        this.done = done;
        this.modeBefore = gui.themes().mode();
        Inputs inputs = new Inputs(gui);
        this.nickInput = inputs.nick(this.nick, gui.t("IDS_PW_NICK"), Preferences.MAX_NICK_LENGTH);
        this.incomingInput = inputs.folder(this.incoming, gui.t("IDS_PW_INCOMING"));
        this.tcpInput = inputs.number(this.tcp, "TCP", 1, Preferences.MAX_PORT);
        this.udpInput = inputs.number(this.udp, "UDP", 1, Preferences.MAX_PORT);
        this.down = RateField.line(gui, gui.t("nj.settings.downloadCapacity"));
        this.up = RateField.line(gui, gui.t("nj.settings.uploadCapacity"));
        this.downInput = this.down.input();
        this.upInput = this.up.input();
        this.portTest = new PortTestPane(gui, () -> this::ports);
        this.pages.addAll(List.of(Page.START, Page.NICK, Page.FOLDERS, Page.CONNECTION));
        if (emule.isPresent()) {
            this.pages.add(Page.EMULE);
        }
        this.back = Fx.button(gui.t("nj.wizard.back"), Material2AL.ARROW_BACK, () -> go(-1));
        this.next = Fx.primary(Fx.button(gui.t("nj.wizard.next"), Material2AL.ARROW_FORWARD, this::nextOrFinish));
        load();
        build();
    }

    public void show() {
        this.stage.show();
    }

    // The pages begin with the settings as they are, and with the ports eMule leaves free.
    private void load() {
        this.language.getItems().add(Optional.empty());
        I18n.LANGUAGES.forEach(l -> this.language.getItems().add(Optional.of(l)));
        this.language.setValue(this.gui.settings().language());
        this.language.setConverter(named(l -> l.map(I18n::nameOf).orElse(this.gui.t("nj.settings.systemLanguage"))));
        this.mode.getItems().addAll(GuiSettings.Mode.values());
        this.mode.setValue(this.modeBefore);
        this.mode.setConverter(named(m -> this.gui.t("nj.mode." + m.name().toLowerCase(Locale.ROOT))));
        // The look shows at once, on this window too.
        this.mode
                .valueProperty()
                .addListener(
                        (o, was, now) -> this.gui.themes().set(this.gui.themes().family(), now));
        this.nick.setText(this.preferences.nick());
        this.incoming.setText(
                this.preferences.effectiveIncomingDir(this.gui.directories()).toString());
        this.tcp.setText(Integer.toString(this.preferences.tcpPort()));
        this.udp.setText(Integer.toString(this.preferences.udpPort()));
        this.upnp.setText(this.gui.t("nj.settings.upnp"));
        this.upnp.setSelected(this.preferences.upnp());
        this.down.show(this.preferences.downloadCapacityKiBps());
        this.up.show(this.preferences.uploadCapacityKiBps());
        this.line.getItems().addAll(LINES);
        this.line.setConverter(named(
                l -> this.gui.f("nj.wizard.mbits", Integer.toString(l.downMbits()), Integer.toString(l.upMbits()))));
        this.line.valueProperty().addListener((o, was, now) -> {
            if (now != null) {
                this.down.show(Line.kib(now.downMbits()));
                this.up.show(Line.kib(now.upMbits()));
            }
        });
        this.importEmule.setText(this.gui.t("nj.wizard.emuleImport"));
        this.importEmule.setSelected(true);
        this.importEmule.setWrapText(true);
    }

    private void build() {
        this.stage.initOwner(this.owner);
        this.stage.initModality(Modality.WINDOW_MODAL);
        this.stage.setTitle(this.gui.t("nj.wizard.title"));
        // Closing the window is skipping: nothing changes.
        this.stage.setOnHidden(e -> {
            if (!this.finished) {
                this.gui.themes().set(this.gui.themes().family(), this.modeBefore);
            }
        });
        Button skip = Fx.button(this.gui.t("nj.wizard.skip"), Material2MZ.SKIP_NEXT, this.stage::close);
        skip.getStyleClass().add(Styles.FLAT);
        this.back.disableProperty().bind(this.at.isEqualTo(0));
        this.next.disableProperty().bind(pageValid().not());
        this.at.addListener((o, was, now) -> showPage());
        HBox buttons = Fx.toolRow(skip, Fx.spacer(), this.back, this.next);
        this.steps.getStyleClass().add("wizard-steps");
        this.shown.setAlignment(Pos.TOP_LEFT);
        VBox root = new VBox(16, this.steps, this.shown, buttons);
        VBox.setVgrow(this.shown, Priority.ALWAYS);
        root.setPadding(new Insets(18));
        root.getStyleClass().add("wizard");
        Scene scene = new Scene(root, 680, 500);
        this.gui.attach(scene);
        this.stage.setScene(scene);
        showPage();
    }

    // Next works when the fields of the page shown are right; the other pages keep theirs.
    private BooleanBinding pageValid() {
        return Bindings.createBooleanBinding(
                () -> switch (this.pages.get(this.at.get())) {
                    case NICK -> this.nickInput.isValid();
                    case FOLDERS -> this.incomingInput.isValid();
                    case CONNECTION ->
                        this.tcpInput.isValid()
                                && this.udpInput.isValid()
                                && this.downInput.isValid()
                                && this.upInput.isValid();
                    case START, EMULE -> true;
                },
                this.at,
                this.nickInput.validProperty(),
                this.incomingInput.validProperty(),
                this.tcpInput.validProperty(),
                this.udpInput.validProperty(),
                this.downInput.validProperty(),
                this.upInput.validProperty());
    }

    private void showPage() {
        Page page = this.pages.get(this.at.get());
        this.steps.getChildren().clear();
        for (int i = 0; i < this.pages.size(); i++) {
            Label step = new Label((i + 1) + "  " + stepName(this.pages.get(i)));
            step.getStyleClass().add("wizard-step");
            if (i == this.at.get()) {
                step.getStyleClass().add("current");
            }
            this.steps.getChildren().add(step);
        }
        this.shown.getChildren().setAll(page(page));
        boolean last = this.at.get() == this.pages.size() - 1;
        this.next.setText(this.gui.t(last ? "nj.wizard.finish" : "nj.wizard.next"));
    }

    private String stepName(Page page) {
        return switch (page) {
            case START -> this.gui.t("nj.wizard.stepStart");
            case NICK -> this.gui.t("nj.wizard.stepNick");
            case FOLDERS -> this.gui.t("nj.wizard.stepFolders");
            case CONNECTION -> this.gui.t("nj.wizard.stepConnection");
            case EMULE -> "eMule";
        };
    }

    private Node page(Page page) {
        return switch (page) {
            case START ->
                page(
                        this.gui.t("nj.wizard.welcome"),
                        this.gui.t("nj.wizard.intro"),
                        grid(
                                List.of(this.gui.t("IDS_PW_LANG"), this.gui.t("nj.settings.theme")),
                                List.of(this.language, this.mode)),
                        hint(this.gui.t("nj.wizard.languageHint")));
            case NICK ->
                page(
                        stepName(page),
                        this.gui.t("nj.wizard.nickHint"),
                        grid(List.of(this.gui.t("IDS_PW_NICK")), List.of(this.nick)));
            case FOLDERS -> page(stepName(page), this.gui.t("nj.wizard.foldersHint"), folders());
            case CONNECTION -> page(stepName(page), this.gui.t("nj.wizard.portsHint"), connection());
            case EMULE ->
                page(
                        stepName(page),
                        this.gui.t("nj.wizard.emuleHint"),
                        new Label(this.emule.map(Path::toString).orElse("")),
                        this.importEmule);
        };
    }

    private VBox page(String title, String text, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add(Styles.TITLE_3);
        VBox box = new VBox(12, heading, wrapped(text));
        box.getChildren().addAll(content);
        return box;
    }

    private Node folders() {
        Button browse = Fx.button(
                this.gui.t("IDS_PW_BROWSE"),
                Material2AL.FOLDER_OPEN,
                () -> choose().ifPresent(dir -> this.incoming.setText(dir.toString())));
        HBox incomingRow = new HBox(8, this.incoming, browse);
        HBox.setHgrow(this.incoming, Priority.ALWAYS);
        Button add = Fx.button(
                this.gui.t("nj.shared.share"),
                Material2AL.CREATE_NEW_FOLDER,
                () -> choose().filter(dir -> !this.shared.getItems().contains(dir))
                        .ifPresent(this.shared.getItems()::add));
        Button remove = Fx.button(
                this.gui.t("IDS_REMOVE"),
                Material2AL.DELETE,
                () -> this.shared
                        .getItems()
                        .removeAll(List.copyOf(this.shared.getSelectionModel().getSelectedItems())));
        remove.disableProperty()
                .bind(Bindings.isEmpty(this.shared.getSelectionModel().getSelectedItems()));
        this.shared.setPrefHeight(120);
        VBox sharedBox = new VBox(6, this.shared, new HBox(8, add, remove));
        return grid(
                List.of(this.gui.t("IDS_PW_INCOMING"), this.gui.t("IDS_SHAREDFOLDERS")),
                List.of(incomingRow, sharedBox));
    }

    private Node connection() {
        this.tcp.setPrefColumnCount(6);
        this.udp.setPrefColumnCount(6);
        HBox ports = new HBox(8, this.tcp, Fx.whole(new Label("UDP")), this.udp);
        ports.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(
                12,
                grid(
                        List.of(
                                this.gui.t("IDS_PW_CLIENTPORT") + " (TCP)",
                                this.gui.t("nj.wizard.line"),
                                this.gui.t("nj.settings.downloadCapacity"),
                                this.gui.t("nj.settings.uploadCapacity")),
                        List.of(ports, this.line, this.down.node(), this.up.node())),
                this.upnp,
                this.portTest.node());
        return box;
    }

    private GridPane grid(List<String> names, List<? extends Node> fields) {
        GridPane grid = new GridPane(12, 10);
        for (int i = 0; i < names.size(); i++) {
            Label name = Fx.whole(new Label(names.get(i)));
            name.setLabelFor(fields.get(i));
            grid.addRow(i, name, fields.get(i));
        }
        ColumnConstraints grows = new ColumnConstraints();
        grows.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(new ColumnConstraints(), grows);
        return grid;
    }

    private Optional<Path> choose() {
        DirectoryChooser chooser = new DirectoryChooser();
        File dir = chooser.showDialog(this.stage);
        return Optional.ofNullable(dir).map(File::toPath);
    }

    private void go(int by) {
        this.at.set(Math.clamp(this.at.get() + by, 0, this.pages.size() - 1));
    }

    private void nextOrFinish() {
        if (this.at.get() < this.pages.size() - 1) {
            go(1);
        } else {
            finish();
        }
    }

    /** Applies the pages and closes. */
    void finish() {
        this.finished = true;
        this.gui
                .model()
                .updatePreferences(this::changed, p -> this.gui.inform().accept(this.gui.t("nj.settings.saved")));
        this.shared.getItems().forEach(dir -> this.gui.model().shareDirectory(dir, true));
        Optional<Locale> chosen = this.language.getValue();
        boolean languageChanged = !chosen.equals(this.gui.settings().language());
        this.gui.settings().setLanguage(chosen);
        this.gui.settings().setMode(this.mode.getValue());
        try {
            this.gui.settings().save();
        } catch (IOException e) {
            logger.warn("Cannot save the interface settings: {}", e.toString());
        }
        this.stage.close();
        this.done.accept(new Outcome(languageChanged, this.emule.isPresent() && this.importEmule.isSelected()));
    }

    // The ports and UPnP as the page gives them, tried before Finish; a field that is wrong keeps the port it had.
    private Preferences ports(Preferences was) {
        Preferences.Builder now = was.toBuilder();
        if (this.tcpInput.isValid()) {
            now.tcpPort(this.tcpInput.value());
        }
        if (this.udpInput.isValid()) {
            now.udpPort(this.udpInput.value());
        }
        return now.upnp(this.upnp.isSelected()).build();
    }

    PortTestPane portTest() {
        return this.portTest;
    }

    // The settings as the pages leave them; a field that is wrong keeps the setting it had.
    private Preferences changed(Preferences was) {
        Preferences.Builder now = was.toBuilder();
        if (this.nickInput.isValid()) {
            now.nick(this.nickInput.value());
        }
        // The folder as it was shown stays the default one, which follows the data directory.
        if (this.incomingInput.isValid()
                && !this.incomingInput.value().equals(Optional.of(was.effectiveIncomingDir(this.gui.directories())))) {
            now.incomingDir(this.incomingInput.value());
        }
        if (this.tcpInput.isValid()) {
            now.tcpPort(this.tcpInput.value());
        }
        if (this.udpInput.isValid()) {
            now.udpPort(this.udpInput.value());
        }
        if (this.downInput.isValid()) {
            now.downloadCapacityKiBps(this.downInput.value());
        }
        if (this.upInput.isValid()) {
            now.uploadCapacityKiBps(this.upInput.value());
        }
        return now.upnp(this.upnp.isSelected()).build();
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Label hint(String text) {
        Label label = wrapped(text);
        label.getStyleClass().add("wizard-hint");
        return label;
    }

    private static <T> StringConverter<T> named(Function<T, String> name) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : name.apply(value);
            }

            @Override
            public T fromString(String text) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** The place of the page shown among the pages, for tests. */
    int shownPage() {
        return this.at.get();
    }

    int pages() {
        return this.pages.size();
    }

    Button nextButton() {
        return this.next;
    }

    Stage stage() {
        return this.stage;
    }

    TextField nickField() {
        return this.nick;
    }

    TextField tcpField() {
        return this.tcp;
    }

    TextField udpField() {
        return this.udp;
    }

    TextField downField() {
        return this.down.field();
    }

    TextField upField() {
        return this.up.field();
    }

    ComboBox<Line> lineBox() {
        return this.line;
    }

    ListView<Path> sharedList() {
        return this.shared;
    }
}
