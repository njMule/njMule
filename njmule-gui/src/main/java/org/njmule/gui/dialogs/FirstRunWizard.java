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

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
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
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.config.Preferences;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.ui.Ask;
import org.njmule.gui.ui.DialogFrame;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.PortTestPane;
import org.njmule.gui.ui.RateField;
import org.njmule.gui.ui.Segments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first start: the language and the look, the nick, the folders, the connection and an eMule to take over, one page
 * each beside the list of steps. Every page starts from the current settings, so Next leaves them as they are; Skip
 * changes nothing.
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
    private static final double WIDTH = 800;
    private static final double HEIGHT = 560;
    private static final double STEPS_WIDTH = 220;
    private static final double FIELD_WIDTH = 340;

    private final Gui gui;
    private final Window owner;
    private final Preferences preferences;
    private final Optional<Path> emule;
    private final Consumer<Outcome> done;
    private final Stage stage = new Stage();
    private final List<Page> pages = new ArrayList<>();
    private final IntegerProperty at = new SimpleIntegerProperty();
    private final StackPane shown = new StackPane();
    private final VBox steps = new VBox(4);
    private final Label stepOf = new Label();
    private final GuiSettings.Mode modeBefore;

    private final ComboBox<Optional<Locale>> language = new ComboBox<>();
    private final Segments<GuiSettings.Mode> mode;
    private final TextField nick = new TextField();
    private final Input<String> nickInput;
    private final TextField incoming = new TextField();
    private final Input<Optional<Path>> incomingInput;
    private final ListView<Path> shared = new ListView<>();
    private final TextField tcp = new TextField();
    private final TextField udp = new TextField();
    private final Input<Integer> tcpInput;
    private final Input<Integer> udpInput;
    private final ToggleSwitch upnp = new ToggleSwitch();
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
        this.mode = new Segments<>(
                List.of(GuiSettings.Mode.values()),
                m -> gui.t("nj.mode." + m.name().toLowerCase(Locale.ROOT)));
        Inputs inputs = new Inputs(gui);
        this.nickInput = inputs.nick(this.nick, gui.t("IDS_PW_NICK"), Preferences.MAX_NICK_LENGTH);
        this.incomingInput = inputs.folder(this.incoming, gui.t("IDS_PW_INCOMING"));
        this.tcpInput = inputs.number(this.tcp, "TCP", 1, Preferences.MAX_PORT);
        this.udpInput = inputs.number(this.udp, "UDP", 1, Preferences.MAX_PORT);
        this.down = RateField.line(gui, gui.t("nj.settings.downloadCapacity"));
        this.up = RateField.line(gui, gui.t("nj.settings.uploadCapacity"));
        this.downInput = this.down.input();
        this.upInput = this.up.input();
        this.portTest = new PortTestPane(
                gui, () -> this::ports, this.tcp.textProperty(), this.udp.textProperty(), this.upnp.selectedProperty());
        this.pages.addAll(List.of(Page.START, Page.NICK, Page.FOLDERS, Page.CONNECTION));
        if (emule.isPresent()) {
            this.pages.add(Page.EMULE);
        }
        this.back = Fx.button(gui.t("nj.wizard.back"), Icons.ARROW_BACK, () -> go(-1));
        this.next = Fx.primary(Fx.button(gui.t("nj.wizard.next"), Icons.ARROW_FORWARD, this::nextOrFinish));
        this.next.setContentDisplay(ContentDisplay.RIGHT);
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
        this.mode.setValue(this.modeBefore);
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
        this.upnp.setSelected(this.preferences.upnp());
        this.down.show(this.preferences.downloadCapacityKiBps());
        this.up.show(this.preferences.uploadCapacityKiBps());
        this.line.getItems().addAll(LINES);
        this.line.setPromptText(this.gui.t("nj.wizard.linePick"));
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
        Fx.wrap(this.importEmule);
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
        this.next.disableProperty().bind(pageValid().not());
        this.back.visibleProperty().bind(this.at.greaterThan(0));
        this.back.managedProperty().bind(this.back.visibleProperty());
        this.at.addListener((o, was, now) -> showPage());
        Button skip = Fx.button(this.gui.t("nj.wizard.skip"), Icons.SKIP, this.stage::close);
        skip.getStyleClass().add(Styles.FLAT);
        skip.visibleProperty().bind(this.at.lessThan(this.pages.size() - 1));
        HBox buttons = Fx.toolRow(skip, Fx.spacer(), this.back, this.next);
        buttons.getStyleClass().add("dialog-footer");
        this.stepOf.getStyleClass().add("dialog-label");
        this.shown.setAlignment(Pos.TOP_LEFT);
        this.shown.getStyleClass().add("wizard-page");
        // A page taller than the window scrolls: the buttons below always stay in view.
        ScrollPane page = new ScrollPane(this.shown);
        page.setFitToWidth(true);
        page.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        page.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        VBox main = new VBox(page, buttons);
        VBox.setVgrow(page, Priority.ALWAYS);
        HBox.setHgrow(main, Priority.ALWAYS);
        HBox root = new HBox(side(), main);
        root.getStyleClass().add("wizard");
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        this.gui.attach(scene);
        this.stage.setScene(scene);
        showPage();
    }

    // The logo and what this is, the steps with a check on those done, and that all of it can change later.
    private Node side() {
        Label title = new Label(this.gui.t("nj.wizard.title"));
        title.getStyleClass().addAll("display", "wizard-title");
        Label duration = new Label(this.gui.t("nj.wizard.duration"));
        duration.getStyleClass().add(Styles.TEXT_MUTED);
        HBox brand = new HBox(10, Fx.logo(32), new VBox(title, duration));
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("wizard-brand");
        Label later = new Label(this.gui.t("nj.wizard.later"));
        Fx.wrap(later);
        later.getStyleClass().add("dialog-hint");
        Region gap = new Region();
        VBox.setVgrow(gap, Priority.ALWAYS);
        VBox side = new VBox(4, brand, this.steps, gap, later);
        side.getStyleClass().add("wizard-side");
        side.setMinWidth(STEPS_WIDTH);
        side.setPrefWidth(STEPS_WIDTH);
        side.setMaxWidth(STEPS_WIDTH);
        return side;
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
            this.steps.getChildren().add(step(i));
        }
        this.stepOf.setText(this.gui.f("nj.wizard.stepOf", this.at.get() + 1, this.pages.size()));
        this.shown.getChildren().setAll(page(page));
        boolean last = this.at.get() == this.pages.size() - 1;
        this.next.setText(this.gui.t(last ? "nj.wizard.finish" : "nj.wizard.next"));
        this.next.setGraphic(last ? null : Fx.icon(Icons.ARROW_FORWARD));
    }

    // A step done shows a check and can be gone back to; the one shown stands out.
    private Button step(int index) {
        boolean doneStep = index < this.at.get();
        Node mark = doneStep ? Fx.icon(Icons.CHECK) : new Label(Integer.toString(index + 1));
        StackPane circle = new StackPane(mark);
        circle.getStyleClass().add("step-mark");
        Button step = new Button(stepName(this.pages.get(index)), circle);
        step.getStyleClass().addAll("wizard-step", Styles.FLAT);
        step.setMaxWidth(Double.MAX_VALUE);
        step.setAlignment(Pos.CENTER_LEFT);
        if (doneStep) {
            step.getStyleClass().add("done");
            step.setOnAction(e -> this.at.set(index));
        } else if (index == this.at.get()) {
            step.getStyleClass().add("current");
        } else {
            step.setFocusTraversable(false);
        }
        step.setAccessibleText(
                this.gui.f("nj.wizard.stepOf", index + 1, this.pages.size()) + ", " + stepName(this.pages.get(index)));
        return step;
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
                        named(this.gui.t("IDS_PW_LANG"), narrow(this.language)),
                        named(this.gui.t("nj.settings.theme"), this.mode.node()),
                        hint(this.gui.t("nj.wizard.languageHint")));
            case NICK ->
                page(
                        stepName(page),
                        this.gui.t("nj.wizard.nickHint"),
                        named(this.gui.t("IDS_PW_NICK"), narrow(this.nick)));
            case FOLDERS -> page(stepName(page), this.gui.t("nj.wizard.foldersHint"), folders());
            case CONNECTION -> page(stepName(page), this.gui.t("nj.wizard.portsHint"), connection());
            case EMULE -> page(stepName(page), this.gui.t("nj.wizard.emuleHint"), emulePage());
        };
    }

    private VBox page(String title, String text, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().addAll("display", "wizard-heading");
        Fx.wrap(heading);
        VBox head = new VBox(4, this.stepOf, heading, hint(text));
        VBox box = new VBox(16, head);
        box.getChildren().addAll(content);
        return box;
    }

    private Node folders() {
        Button browse = Fx.button(
                this.gui.t("IDS_PW_BROWSE"),
                Icons.FOLDER_OPEN,
                () -> choose().ifPresent(dir -> this.incoming.setText(dir.toString())));
        Fx.leftToRight(this.incoming);
        HBox incomingRow = new HBox(8, this.incoming, browse);
        HBox.setHgrow(this.incoming, Priority.ALWAYS);
        Button add = Fx.button(this.gui.t("nj.shared.shareFolder"), Icons.FOLDER_ADD, this::addShared);
        this.shared.setCellFactory(l -> new FolderCell());
        this.shared.setPrefHeight(112);
        this.shared.setPlaceholder(new Label(this.gui.t("nj.wizard.noFolders")));
        this.shared.getStyleClass().add(Styles.DENSE);
        HBox addRow = new HBox(add);
        addRow.getStyleClass().add("wizard-folders-add");
        VBox sharedBox = new VBox(this.shared, addRow);
        sharedBox.getStyleClass().add("wizard-folders");
        Label asks = new Label(this.gui.t("nj.wizard.asksFirst"), Fx.icon(Icons.SHIELD));
        Fx.wrap(asks);
        asks.getStyleClass().add("dialog-hint");
        return new VBox(
                16,
                named(this.gui.t("IDS_PW_INCOMING"), incomingRow, this.gui.t("nj.wizard.incomingHint")),
                named(this.gui.t("IDS_SHAREDFOLDERS"), sharedBox),
                asks);
    }

    // A folder where personal or system files may be is asked about first, as in the shared files.
    private void addShared() {
        choose().filter(dir -> !this.shared.getItems().contains(dir))
                .filter(dir -> !SharedDirectory.isSensitive(dir) || shareSensitive(dir))
                .ifPresent(this.shared.getItems()::add);
    }

    private boolean shareSensitive(Path dir) {
        return Ask.about(this.gui, this.gui.t("nj.shared.shareAllTitle"))
                .warning()
                .names(List.of(dir.toString()))
                .says(this.gui.t("nj.shared.sensitiveText"))
                .risky(this.gui.t("nj.shared.shareAll"), this.gui.t("nj.shared.chooseOther"))
                .owner(this.stage)
                .ask();
    }

    private Node connection() {
        this.tcp.setPrefColumnCount(6);
        this.udp.setPrefColumnCount(6);
        // Both capacities side by side, the page then fits without scrolling.
        this.down.field().setPrefColumnCount(5);
        this.up.field().setPrefColumnCount(5);
        FlowPane capacities = new FlowPane(
                24,
                16,
                named(this.gui.t("nj.settings.downloadCapacity"), this.down.node()),
                named(this.gui.t("nj.settings.uploadCapacity"), this.up.node()));
        HBox ports = new HBox(16, named(this.gui.t("IDS_PW_CLIENTPORT") + " (TCP)", this.tcp), named("UDP", this.udp));
        Label upnpName = new Label(this.gui.t("nj.settings.upnp"));
        upnpName.getStyleClass().add("wizard-switch-name");
        upnpName.setLabelFor(this.upnp);
        upnpName.setOnMouseClicked(e -> this.upnp.setSelected(!this.upnp.isSelected()));
        Label upnpHint = new Label(this.gui.t("nj.wizard.upnpHint"));
        Fx.wrap(upnpHint);
        upnpHint.getStyleClass().add("dialog-hint");
        VBox upnpWords = new VBox(2, upnpName, upnpHint);
        HBox.setHgrow(upnpWords, Priority.ALWAYS);
        HBox upnpRow = new HBox(12, this.upnp, upnpWords);
        upnpRow.setAlignment(Pos.CENTER_LEFT);
        return new VBox(
                16,
                named(this.gui.t("nj.wizard.line"), narrow(this.line)),
                capacities,
                ports,
                upnpRow,
                this.portTest.node());
    }

    // What was found, and whether to open the import once done.
    private Node emulePage() {
        Label where = Fx.leftToRight(new Label(this.emule.map(Path::toString).orElse("")));
        Fx.wrap(where);
        where.getStyleClass().add(Styles.TEXT_MUTED);
        VBox words = new VBox(2, new Label(this.gui.t("nj.wizard.emuleFound")), where);
        HBox found = new HBox(10, Fx.icon(Icons.CHECK_CIRCLE), words);
        found.getStyleClass().add("wizard-found");
        this.importEmule.getStyleClass().add("wizard-option");
        this.importEmule.setMaxWidth(Double.MAX_VALUE);
        return new VBox(12, found, this.importEmule, hint(this.gui.t("nj.wizard.emuleNote")));
    }

    // A field with its name above it and, if any, a hint under it.
    private static VBox named(String name, Node field, String hint) {
        VBox box = named(name, field);
        Label under = new Label(hint);
        Fx.wrap(under);
        under.getStyleClass().add("dialog-hint");
        box.getChildren().add(under);
        return box;
    }

    private static VBox named(String name, Node field) {
        return DialogFrame.named(name, field);
    }

    private static <T extends Region> T narrow(T field) {
        field.setMaxWidth(FIELD_WIDTH);
        field.setPrefWidth(FIELD_WIDTH);
        return field;
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

    private static Label hint(String text) {
        Label label = new Label(text);
        Fx.wrap(label);
        label.setMinHeight(Region.USE_PREF_SIZE);
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

    /** A shared folder with its icon and a button that takes it off the list. */
    private final class FolderCell extends ListCell<Path> {

        @Override
        protected void updateItem(Path dir, boolean empty) {
            super.updateItem(dir, empty);
            if (empty || dir == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label path = Fx.leftToRight(new Label(dir.toString(), Fx.icon(Icons.FOLDER_OPEN)));
            path.setMinWidth(0);
            HBox.setHgrow(path, Priority.ALWAYS);
            path.setMaxWidth(Double.MAX_VALUE);
            Button remove = Fx.iconButton(
                    FirstRunWizard.this.gui.f("nj.wizard.unshare", dir.toString()),
                    Icons.CLOSE,
                    () -> FirstRunWizard.this.shared.getItems().remove(dir));
            remove.getStyleClass().addAll(Styles.FLAT, Styles.SMALL);
            HBox row = new HBox(8, path, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }

    /** The place of the page shown among the pages, for tests. */
    int shownPage() {
        return this.at.get();
    }

    int pages() {
        return this.pages.size();
    }

    /** The buttons of the steps, in order. */
    List<Button> stepButtons() {
        return this.steps.getChildren().stream().map(Button.class::cast).toList();
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
