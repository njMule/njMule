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
package org.njmule.gui.screens;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.app.LinkAssociation;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.shell.NotificationArea;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.Screen;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUiSettings;

/** The engine's preferences and the look of the interface, applied together. */
public final class SettingsScreen implements Screen {

    private static final int MAX_PROXY_USER = 255;
    private static final int MAX_PASSWORD = 1024;

    private final Gui gui;
    private Node view;
    private final TextField nick = new TextField();
    private final TextField tcpPort = new TextField();
    private final TextField udpPort = new TextField();
    private final TextField upload = new TextField();
    private final TextField download = new TextField();
    private final TextField downloadCapacity = new TextField();
    private final TextField uploadCapacity = new TextField();
    private final TextField connections = new TextField();
    private final TextField sources = new TextField();
    private final ComboBox<ObfuscationMode> obfuscation = new ComboBox<>();
    private final CheckBox ed2k = new CheckBox();
    private final CheckBox ed2kLinks = new CheckBox();
    private final CheckBox tray = new CheckBox();
    // Whether ed2k links opened in njMule when the form was loaded; empty while unknown.
    private Optional<Boolean> linksWereOurs = Optional.empty();
    private Optional<LinkAssociation> links = Optional.empty();
    private final CheckBox kad = new CheckBox();
    private final CheckBox autoConnect = new CheckBox();
    private final CheckBox reconnect = new CheckBox();
    private final CheckBox secureIdent = new CheckBox();
    private final CheckBox credits = new CheckBox();
    private final CheckBox upnp = new CheckBox();
    private final ComboBox<ProxySettings.Type> proxyType = new ComboBox<>();
    private final TextField proxyHost = new TextField();
    private final TextField proxyPort = new TextField();
    private final TextField proxyUser = new TextField();
    private final PasswordField proxyPassword = new PasswordField();
    private final TextField incoming = new TextField();
    private final TextField temp = new TextField();
    private final ComboBox<Themes.Family> theme = new ComboBox<>();
    private final ComboBox<GuiSettings.Mode> mode = new ComboBox<>();
    private final ComboBox<Optional<Locale>> language = new ComboBox<>();
    private final CheckBox webUi = new CheckBox();
    private final TextField webUiPort = new TextField();
    private final CheckBox webUiLan = new CheckBox();
    private final PasswordField webUiPassword = new PasswordField();
    private final PasswordField webUiAgain = new PasswordField();
    private SchedulerPane scheduler;
    private Input<String> nickInput;
    private Input<Integer> tcpPortInput;
    private Input<Integer> udpPortInput;
    private Input<Integer> uploadInput;
    private Input<Integer> downloadInput;
    private Input<Integer> downloadCapacityInput;
    private Input<Integer> uploadCapacityInput;
    private Input<Integer> connectionsInput;
    private Input<Integer> sourcesInput;
    private Input<String> proxyHostInput;
    private Input<Integer> proxyPortInput;
    private Input<String> proxyUserInput;
    private Input<Optional<Path>> incomingInput;
    private Input<Optional<Path>> tempInput;
    private Input<Integer> webUiPortInput;
    private Input<String> webUiPasswordInput;
    private Input<String> webUiAgainInput;

    public SettingsScreen(Gui gui) {
        this.gui = gui;
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public String title() {
        return this.gui.t("IDS_EM_PREFS");
    }

    @Override
    public Ikon icon() {
        return Material2MZ.SETTINGS;
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = this.build();
        }
        return this.view;
    }

    @Override
    public void shown() {
        this.load(this.gui.model().preferencesProperty().get());
    }

    private Node build() {
        this.obfuscation.getItems().addAll(ObfuscationMode.values());
        this.obfuscation.setCellFactory(l -> new NamedCell<>(this::obfuscationName));
        this.obfuscation.setButtonCell(new NamedCell<>(this::obfuscationName));
        this.theme.getItems().addAll(Themes.Family.values());
        this.theme.setCellFactory(l -> new NamedCell<>(f -> this.gui.t("nj.theme." + key(f))));
        this.theme.setButtonCell(new NamedCell<>(f -> this.gui.t("nj.theme." + key(f))));
        this.mode.getItems().addAll(GuiSettings.Mode.values());
        this.mode.setCellFactory(l -> new NamedCell<>(m -> this.gui.t("nj.mode." + key(m))));
        this.mode.setButtonCell(new NamedCell<>(m -> this.gui.t("nj.mode." + key(m))));
        this.language.getItems().add(Optional.empty());
        I18n.LANGUAGES.forEach(l -> this.language.getItems().add(Optional.of(l)));
        this.language.setCellFactory(l -> new NamedCell<>(this::languageName));
        this.language.setButtonCell(new NamedCell<>(this::languageName));
        this.ed2k.setText(this.gui.t("nj.settings.ed2k"));
        this.ed2kLinks.setText(this.gui.t("nj.settings.ed2kLinks"));
        this.tray.setText(this.gui.t("nj.settings.tray"));
        this.links = LinkAssociation.forThisSystem(this.gui.directories().config());
        this.kad.setText(this.gui.t("nj.settings.kad"));
        this.autoConnect.setText(this.gui.t("IDS_PW_AUTOCON"));
        this.reconnect.setText(this.gui.t("IDS_PW_RECON"));
        this.secureIdent.setText(this.gui.t("nj.settings.secureIdent"));
        this.credits.setText(this.gui.t("nj.settings.credits"));
        this.upnp.setText(this.gui.t("nj.settings.upnp"));
        this.webUi.setText(this.gui.t("nj.webui.enabled"));
        this.webUiLan.setText(this.gui.t("nj.webui.lan"));
        this.proxyType.getItems().addAll(ProxySettings.Type.values());
        this.proxyType.setCellFactory(l -> new NamedCell<>(t -> this.gui.t("nj.proxy." + key(t))));
        this.proxyType.setButtonCell(new NamedCell<>(t -> this.gui.t("nj.proxy." + key(t))));
        this.inputs();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        int row = 0;
        row = section(grid, row, this.gui.t("IDS_PW_GENERAL"));
        row = field(grid, row, this.gui.t("IDS_PW_NICK"), this.nick);
        row = field(grid, row, this.gui.t("IDS_PW_LANG"), this.language);
        row = field(grid, row, this.gui.t("nj.settings.theme"), new HBox(8, this.theme, this.mode));
        if (NotificationArea.isSupported()) {
            grid.add(this.tray, 1, row++);
        }
        if (this.links.isPresent()) {
            grid.add(this.ed2kLinks, 1, row++);
        }
        row = section(grid, row, this.gui.t("nj.settings.connection"));
        row = field(grid, row, this.gui.t("IDS_PW_CLIENTPORT") + " (TCP)", this.tcpPort);
        row = field(grid, row, "UDP", this.udpPort);
        row = field(grid, row, this.gui.t("nj.settings.upload"), this.upload);
        row = field(grid, row, this.gui.t("nj.settings.download"), this.download);
        row = field(grid, row, this.gui.t("nj.settings.downloadCapacity"), this.downloadCapacity);
        row = field(grid, row, this.gui.t("nj.settings.uploadCapacity"), this.uploadCapacity);
        row = field(grid, row, this.gui.t("IDS_PW_MAXC"), this.connections);
        row = field(grid, row, this.gui.t("IDS_PW_MAXSOURCES"), this.sources);
        row = field(grid, row, this.gui.t("nj.settings.obfuscation"), this.obfuscation);
        grid.add(this.ed2k, 1, row++);
        grid.add(this.kad, 1, row++);
        grid.add(this.autoConnect, 1, row++);
        grid.add(this.reconnect, 1, row++);
        grid.add(this.secureIdent, 1, row++);
        grid.add(this.credits, 1, row++);
        grid.add(this.upnp, 1, row++);
        row = section(grid, row, this.gui.t("IDS_PW_PROXY"));
        row = field(grid, row, this.gui.t("nj.proxy.type"), this.proxyType);
        row = field(grid, row, this.gui.t("nj.proxy.host"), new HBox(8, this.proxyHost, this.proxyPort));
        row = field(grid, row, this.gui.t("nj.proxy.user"), new HBox(8, this.proxyUser, this.proxyPassword));
        row = section(grid, row, this.gui.t("IDS_PW_DIR"));
        row = field(grid, row, this.gui.t("IDS_PW_INCOMING"), this.directory(this.incoming));
        row = field(grid, row, this.gui.t("IDS_PW_TEMP"), this.directory(this.temp));
        row = section(grid, row, this.gui.t("nj.scheduler.title"));
        grid.add(this.scheduler(), 0, row++, 2, 1);
        row = section(grid, row, this.gui.t("IDS_PW_WS"));
        grid.add(this.webUi, 1, row++);
        row = field(grid, row, this.gui.t("nj.webui.port"), this.webUiPort);
        grid.add(this.webUiLan, 1, row++);
        row = field(grid, row, this.gui.t("nj.webui.password"), new HBox(8, this.webUiPassword, this.webUiAgain));
        Label webNote = new Label(this.gui.t("nj.webui.note"));
        webNote.setWrapText(true);
        webNote.getStyleClass().add("text-muted");
        grid.add(webNote, 0, row++, 2, 1);
        Label note = new Label(this.gui.t("nj.settings.restartNote"));
        note.setWrapText(true);
        note.getStyleClass().add("text-muted");
        grid.add(note, 0, row, 2, 1);

        Button apply = Fx.primary(Fx.button(this.gui.t("IDS_PW_APPLY"), Material2AL.DONE, this::apply));
        apply.disableProperty()
                .bind(Input.allValid(List.of(
                                this.nickInput,
                                this.tcpPortInput,
                                this.udpPortInput,
                                this.uploadInput,
                                this.downloadInput,
                                this.downloadCapacityInput,
                                this.uploadCapacityInput,
                                this.connectionsInput,
                                this.sourcesInput,
                                this.proxyHostInput,
                                this.proxyPortInput,
                                this.proxyUserInput,
                                this.incomingInput,
                                this.tempInput,
                                this.webUiPortInput,
                                this.webUiPasswordInput,
                                this.webUiAgainInput))
                        .not());
        var tools = Fx.toolRow(apply, Fx.button(this.gui.t("IDS_PW_RESET"), Material2MZ.REFRESH, this::shown));
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        this.load(this.gui.model().preferencesProperty().get());
        return Fx.screen(this.title(), tools, scroll);
    }

    // Each box takes only its kind of value, within the limits the engine accepts.
    private void inputs() {
        Inputs inputs = new Inputs(this.gui);
        this.nickInput = inputs.text(this.nick, this.gui.t("IDS_PW_NICK"), Preferences.MAX_NICK_LENGTH, true);
        this.tcpPortInput = inputs.number(this.tcpPort, "TCP", 1, Preferences.MAX_PORT);
        this.udpPortInput = inputs.number(this.udpPort, "UDP", 1, Preferences.MAX_PORT);
        this.uploadInput = inputs.number(this.upload, this.gui.t("nj.settings.upload"), 0, Preferences.MAX_RATE_KIBPS);
        this.downloadInput =
                inputs.number(this.download, this.gui.t("nj.settings.download"), 0, Preferences.MAX_RATE_KIBPS);
        this.downloadCapacityInput = inputs.number(
                this.downloadCapacity, this.gui.t("nj.settings.downloadCapacity"), 1, Preferences.MAX_RATE_KIBPS);
        this.uploadCapacityInput = inputs.number(
                this.uploadCapacity, this.gui.t("nj.settings.uploadCapacity"), 1, Preferences.MAX_RATE_KIBPS);
        this.connectionsInput =
                inputs.number(this.connections, this.gui.t("IDS_PW_MAXC"), 1, Preferences.MAX_CONNECTIONS);
        this.sourcesInput =
                inputs.number(this.sources, this.gui.t("IDS_PW_MAXSOURCES"), 1, Preferences.MAX_SOURCES_PER_FILE);
        this.proxyHostInput = inputs.host(
                this.proxyHost,
                this.gui.t("nj.proxy.host"),
                () -> this.proxyType.getValue() != ProxySettings.Type.NONE);
        this.proxyType.valueProperty().addListener((o, was, now) -> this.proxyHostInput.check());
        this.proxyPortInput = inputs.number(this.proxyPort, this.gui.t("nj.proxy.host"), 1, Preferences.MAX_PORT);
        this.proxyUserInput = inputs.text(this.proxyUser, this.gui.t("nj.proxy.user"), MAX_PROXY_USER, false);
        this.incomingInput = inputs.folder(this.incoming, this.gui.t("IDS_PW_INCOMING"));
        this.tempInput = inputs.folder(this.temp, this.gui.t("IDS_PW_TEMP"));
        this.webUiPortInput = inputs.number(this.webUiPort, this.gui.t("nj.webui.port"), 1, Preferences.MAX_PORT);
        this.webUiPasswordInput = Input.of(
                        this.webUiPassword,
                        this.gui.t("nj.webui.password"),
                        this.gui.f("nj.input.password", Passwords.MIN_LENGTH),
                        c -> !Character.isISOControl(c),
                        MAX_PASSWORD,
                        t -> {
                            if (!t.isEmpty() && t.length() < Passwords.MIN_LENGTH) {
                                throw new IllegalArgumentException(t);
                            }
                            return t;
                        })
                .keepSpaces();
        this.webUiAgainInput = Input.of(
                        this.webUiAgain,
                        this.gui.t("nj.webui.password"),
                        this.gui.t("nj.input.passwordAgain"),
                        c -> !Character.isISOControl(c),
                        MAX_PASSWORD,
                        t -> {
                            if (!t.equals(this.webUiPassword.getText())) {
                                throw new IllegalArgumentException(t);
                            }
                            return t;
                        })
                .keepSpaces();
        this.webUiPassword.textProperty().addListener((o, was, now) -> this.webUiAgainInput.check());
    }

    private SchedulerPane scheduler() {
        if (this.scheduler == null) {
            this.scheduler = new SchedulerPane(this.gui);
        }
        return this.scheduler;
    }

    SchedulerPane schedulerPane() {
        return this.scheduler();
    }

    private HBox directory(TextField field) {
        field.setPrefColumnCount(32);
        field.setPromptText(this.gui.t("nj.settings.defaultDir"));
        return new HBox(6, field, Fx.button(this.gui.t("IDS_PW_BROWSE"), Material2AL.FOLDER_OPEN, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            var dir = chooser.showDialog(this.view.getScene().getWindow());
            if (dir != null) {
                field.setText(dir.getAbsolutePath());
            }
        }));
    }

    void load(Preferences p) {
        this.nick.setText(p.nick());
        this.tcpPort.setText(String.valueOf(p.tcpPort()));
        this.udpPort.setText(String.valueOf(p.udpPort()));
        this.upload.setText(String.valueOf(p.maxUploadKiBps()));
        this.download.setText(String.valueOf(p.maxDownloadKiBps()));
        this.downloadCapacity.setText(String.valueOf(p.downloadCapacityKiBps()));
        this.uploadCapacity.setText(String.valueOf(p.uploadCapacityKiBps()));
        this.connections.setText(String.valueOf(p.maxConnections()));
        this.sources.setText(String.valueOf(p.maxSourcesPerFile()));
        this.obfuscation.setValue(p.obfuscation());
        this.ed2k.setSelected(p.ed2kEnabled());
        this.kad.setSelected(p.kadEnabled());
        this.autoConnect.setSelected(p.autoConnect());
        this.reconnect.setSelected(p.reconnect());
        this.secureIdent.setSelected(p.secureIdent());
        this.credits.setSelected(p.creditSystem());
        this.upnp.setSelected(p.upnp());
        this.proxyType.setValue(p.proxy().type());
        this.proxyHost.setText(p.proxy().host());
        this.proxyPort.setText(String.valueOf(p.proxy().port()));
        this.proxyUser.setText(p.proxy().user());
        this.proxyPassword.setText(p.proxy().password());
        this.incoming.setText(p.incomingDir().map(Path::toString).orElse(""));
        this.temp.setText(p.tempDir().map(Path::toString).orElse(""));
        this.theme.setValue(this.gui.themes().family());
        this.mode.setValue(this.gui.themes().mode());
        this.language.setValue(this.gui.settings().language());
        this.tray.setSelected(this.gui.settings().minimizeToTray());
        WebUiSettings web = WebUiSettings.load(this.gui.directories().config());
        this.webUi.setSelected(web.enabled());
        this.webUiPort.setText(String.valueOf(web.port()));
        this.webUiLan.setSelected(web.lan());
        this.webUiPassword.clear();
        this.webUiAgain.clear();
        this.loadLinks();
    }

    // Asking the system takes a moment: off the FX thread, and the box waits disabled meanwhile.
    private void loadLinks() {
        this.links.ifPresent(association -> {
            this.ed2kLinks.setDisable(true);
            Thread.ofVirtual().name("njmule-ed2k-links").start(() -> {
                boolean ours;
                try {
                    ours = association.isOurs();
                } catch (IOException e) {
                    ours = false;
                }
                boolean now = ours;
                Platform.runLater(() -> {
                    this.linksWereOurs = Optional.of(now);
                    this.ed2kLinks.setSelected(now);
                    this.ed2kLinks.setDisable(false);
                });
            });
        });
    }

    // Taking or giving back the links is a change to the system, done only when the box changed.
    private void applyLinks() {
        if (this.links.isEmpty() || this.linksWereOurs.isEmpty()) {
            return;
        }
        boolean wanted = this.ed2kLinks.isSelected();
        if (wanted == this.linksWereOurs.get()) {
            return;
        }
        LinkAssociation association = this.links.get();
        this.linksWereOurs = Optional.of(wanted);
        Thread.ofVirtual().name("njmule-ed2k-links").start(() -> {
            try {
                if (wanted) {
                    association.take();
                } else {
                    association.giveBack();
                }
            } catch (IOException e) {
                Platform.runLater(() -> {
                    this.gui.inform().accept(this.gui.f("nj.settings.ed2kLinksFailed", e.getMessage()));
                    this.loadLinks();
                });
            }
        });
    }

    /** Validates the form, then saves the preferences and the look; errors are shown. */
    void apply() {
        Preferences changed;
        WebUiSettings web;
        try {
            changed = this.read(this.gui.model().preferencesProperty().get());
            web = this.readWebUi(WebUiSettings.load(this.gui.directories().config()));
        } catch (IllegalArgumentException e) {
            this.gui.inform().accept(this.gui.f("nj.settings.invalid", e.getMessage()));
            return;
        }
        this.gui
                .model()
                .updatePreferences(p -> changed, p -> this.gui.inform().accept(this.gui.t("nj.settings.saved")));
        boolean relaunch =
                !Objects.equals(this.language.getValue(), this.gui.settings().language());
        this.gui.themes().set(this.theme.getValue(), this.mode.getValue());
        this.gui.settings().setTheme(this.theme.getValue());
        this.gui.settings().setMode(this.mode.getValue());
        this.gui.settings().setLanguage(this.language.getValue());
        this.gui.settings().setMinimizeToTray(this.tray.isSelected());
        try {
            this.gui.settings().save();
            web.save(this.gui.directories().config());
            this.webUiPassword.clear();
            this.webUiAgain.clear();
        } catch (IOException e) {
            this.gui.inform().accept(e.getMessage());
        }
        this.applyLinks();
        if (relaunch) {
            this.gui.relaunch().run();
        }
    }

    private Preferences read(Preferences current) {
        return current.toBuilder()
                .nick(this.nickInput.value())
                .tcpPort(this.tcpPortInput.value())
                .udpPort(this.udpPortInput.value())
                .maxUploadKiBps(this.uploadInput.value())
                .maxDownloadKiBps(this.downloadInput.value())
                .downloadCapacityKiBps(this.downloadCapacityInput.value())
                .uploadCapacityKiBps(this.uploadCapacityInput.value())
                .maxConnections(this.connectionsInput.value())
                .maxSourcesPerFile(this.sourcesInput.value())
                .obfuscation(this.obfuscation.getValue())
                .ed2kEnabled(this.ed2k.isSelected())
                .kadEnabled(this.kad.isSelected())
                .autoConnect(this.autoConnect.isSelected())
                .reconnect(this.reconnect.isSelected())
                .secureIdent(this.secureIdent.isSelected())
                .creditSystem(this.credits.isSelected())
                .upnp(this.upnp.isSelected())
                .proxy(new ProxySettings(
                        this.proxyType.getValue(),
                        this.proxyHostInput.value(),
                        this.proxyPortInput.value(),
                        this.proxyUserInput.value(),
                        this.proxyPassword.getText()))
                .incomingDir(this.incomingInput.value())
                .tempDir(this.tempInput.value())
                .build();
    }

    // A new password replaces the stored hash; an empty one keeps it.
    private WebUiSettings readWebUi(WebUiSettings current) {
        int port = this.webUiPortInput.value();
        String hash = current.passwordHash();
        char[] password = this.webUiPassword.getText().toCharArray();
        char[] again = this.webUiAgain.getText().toCharArray();
        try {
            if (password.length > 0 || again.length > 0) {
                if (password.length < Passwords.MIN_LENGTH) {
                    throw new IllegalArgumentException(this.gui.f("nj.webui.passwordShort", Passwords.MIN_LENGTH));
                }
                if (!Arrays.equals(password, again)) {
                    throw new IllegalArgumentException(this.gui.t("nj.webui.passwordsDiffer"));
                }
                hash = Passwords.hash(password, new SecureRandom());
            }
        } finally {
            Passwords.wipe(password);
            Passwords.wipe(again);
        }
        if (this.webUi.isSelected() && hash.isEmpty()) {
            throw new IllegalArgumentException(this.gui.t("nj.webui.needsPassword"));
        }
        return new WebUiSettings(this.webUi.isSelected(), port, this.webUiLan.isSelected(), hash);
    }

    private String obfuscationName(ObfuscationMode m) {
        return this.gui.t("nj.obfuscation." + key(m));
    }

    private String languageName(Optional<Locale> l) {
        return l.map(locale -> locale.getDisplayLanguage(locale)).orElse(this.gui.t("nj.settings.systemLanguage"));
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static int section(GridPane grid, int row, String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-section");
        grid.add(label, 0, row, 2, 1);
        return row + 1;
    }

    private static int field(GridPane grid, int row, String name, Node control) {
        grid.add(new Label(name), 0, row);
        grid.add(control, 1, row);
        return row + 1;
    }

    TextField nickField() {
        return this.nick;
    }

    TextField tcpPortField() {
        return this.tcpPort;
    }

    ComboBox<ProxySettings.Type> proxyTypeBox() {
        return this.proxyType;
    }

    TextField proxyHostField() {
        return this.proxyHost;
    }

    ComboBox<GuiSettings.Mode> modeBox() {
        return this.mode;
    }

    ComboBox<Optional<Locale>> languageBox() {
        return this.language;
    }

    CheckBox webUiBox() {
        return this.webUi;
    }

    TextField webUiPortField() {
        return this.webUiPort;
    }

    PasswordField webUiPasswordField() {
        return this.webUiPassword;
    }

    PasswordField webUiAgainField() {
        return this.webUiAgain;
    }

    /** A cell showing a name for its item. */
    private static final class NamedCell<T> extends ListCell<T> {
        private final Function<T, String> name;

        NamedCell(Function<T, String> name) {
            this.name = name;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            this.setText(empty || item == null ? null : this.name.apply(item));
        }
    }
}
