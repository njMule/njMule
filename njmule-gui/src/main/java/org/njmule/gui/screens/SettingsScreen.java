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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.material2.Material2AL;
import org.kordamp.ikonli.material2.Material2MZ;
import org.njmule.app.Autostart;
import org.njmule.app.LinkAssociation;
import org.njmule.core.config.BindInterface;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.shell.NotificationArea;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.PortTestPane;
import org.njmule.gui.ui.RateField;
import org.njmule.gui.ui.Screen;
import org.njmule.gui.ui.UrlBox;
import org.njmule.webui.ApiToken;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUiSettings;

/** The engine's preferences and the look of the interface, applied together. */
public final class SettingsScreen implements Screen {

    private static final int MAX_PROXY_USER = 255;
    private static final int MAX_PASSWORD = 1024;
    private static final int MAX_WEBUI_HOSTS = 4096;

    private final Gui gui;
    private Node view;
    private final TextField nick = new TextField();
    private final TextField tcpPort = new TextField();
    private final TextField udpPort = new TextField();
    private RateField<Integer> upload;
    private RateField<Integer> download;
    private RateField<Integer> downloadCapacity;
    private RateField<Integer> uploadCapacity;
    private final TextField connections = new TextField();
    private final TextField newConnections = new TextField();
    private final TextField queueSize = new TextField();
    private final TextField sources = new TextField();
    private final ComboBox<ObfuscationMode> obfuscation = new ComboBox<>();
    // The interface every connection goes through: "" for any, else a name or an address.
    private final ComboBox<String> bindInterface = new ComboBox<>();
    // The interfaces up when the options were read, for the names of the choices.
    private List<BindInterface.Candidate> interfaces = List.of();
    private final CheckBox ed2k = new CheckBox();
    private final CheckBox ed2kLinks = new CheckBox();
    private final CheckBox tray = new CheckBox();
    private final CheckBox confirmExit = new CheckBox();
    private final CheckBox startMinimized = new CheckBox();
    private final CheckBox autostart = new CheckBox();
    // Whether njMule started at login when the form was loaded; empty while unknown.
    private Optional<Boolean> autostartWasOn = Optional.empty();
    // The questions to the system asked so far; FX thread.
    private int autostartLoads;
    private final CheckBox watchClipboard = new CheckBox();
    private final CheckBox sound = new CheckBox();
    private final TextField soundFile = new TextField();
    private final CheckBox filterLan = new CheckBox();
    private final TextArea whitelist = new TextArea();
    private final TextField messageFilter = new TextField();
    private final CheckBox friendsOnly = new CheckBox();
    private final CheckBox spamFilter = new CheckBox();
    private final CheckBox searchSpamFilter = new CheckBox();
    // Whether ed2k links opened in njMule when the form was loaded; empty while unknown.
    private Optional<Boolean> linksWereOurs = Optional.empty();
    private Optional<LinkAssociation> links = Optional.empty();
    private final CheckBox kad = new CheckBox();
    private final CheckBox autoConnect = new CheckBox();
    private final CheckBox reconnect = new CheckBox();
    private final CheckBox secureIdent = new CheckBox();
    private final CheckBox credits = new CheckBox();
    private final CheckBox upnp = new CheckBox();
    private PortTestPane portTest;
    private final TextField deadRetries = new TextField();
    private final TextField backups = new TextField();
    private final TextField filterLevel = new TextField();
    private final CheckBox fromServer = new CheckBox();
    private final CheckBox fromClients = new CheckBox();
    private final CheckBox staticOnly = new CheckBox();
    private final CheckBox manualHigh = new CheckBox();
    private final CheckBox updateAtStart = new CheckBox();
    private final CheckBox rememberDownloaded = new CheckBox();
    private final CheckBox rememberCancelled = new CheckBox();
    private final CheckBox storeSearches = new CheckBox();
    private final CheckBox previewFirst = new CheckBox();
    private final TextField videoPlayer = new TextField();
    private final TextField videoPlayerArgs = new TextField();
    private final ComboBox<SharedFilesVisibility> visibility = new ComboBox<>();
    private final CheckBox addPaused = new CheckBox();
    private final CheckBox cleanUpNewNames = new CheckBox();
    private final TextField nameCleanups = new TextField();
    private final CheckBox startNext = new CheckBox();
    private final ComboBox<NextDownload> nextCategory = new ComboBox<>();
    private final ComboBox<ProxySettings.Type> proxyType = new ComboBox<>();
    private final TextField proxyHost = new TextField();
    private final TextField proxyPort = new TextField();
    private final TextField proxyUser = new TextField();
    private final PasswordField proxyPassword = new PasswordField();
    private final TextField incoming = new TextField();
    private final TextField temp = new TextField();
    private final TextField minFreeSpace = new TextField();
    private final ComboBox<Themes.Family> theme = new ComboBox<>();
    private final ComboBox<GuiSettings.Mode> mode = new ComboBox<>();
    private final ComboBox<Optional<Locale>> language = new ComboBox<>();
    private final CheckBox webUi = new CheckBox();
    private final TextField webUiPort = new TextField();
    private final CheckBox webUiLan = new CheckBox();
    private final TextField webUiHosts = new TextField();
    private final CheckBox webUiApi = new CheckBox();
    private final PasswordField webUiPassword = new PasswordField();
    private final PasswordField webUiAgain = new PasswordField();
    private SchedulerPane scheduler;
    private UrlBox ipFilter;
    private Input<String> nickInput;
    private Input<Integer> tcpPortInput;
    private Input<Integer> udpPortInput;
    private Input<Integer> uploadInput;
    private Input<Integer> downloadInput;
    private Input<Integer> downloadCapacityInput;
    private Input<Integer> uploadCapacityInput;
    private Input<Integer> connectionsInput;
    private Input<Integer> newConnectionsInput;
    private Input<Integer> queueSizeInput;
    private Input<Integer> sourcesInput;
    private Input<String> proxyHostInput;
    private Input<Integer> proxyPortInput;
    private Input<String> proxyUserInput;
    private Input<Optional<Path>> incomingInput;
    private Input<Optional<Path>> tempInput;
    private Input<Optional<Path>> soundFileInput;
    private Input<Integer> minFreeSpaceInput;
    private Input<Integer> deadRetriesInput;
    private Input<Integer> backupsInput;
    private Input<Integer> filterLevelInput;
    private Input<String> whitelistInput;
    private Input<Integer> webUiPortInput;
    private Input<List<String>> webUiHostsInput;
    private Input<String> webUiPasswordInput;
    private Input<String> webUiAgainInput;
    private final Map<EventCommands.Event, CheckBox> eventSwitches = new EnumMap<>(EventCommands.Event.class);
    private final Map<EventCommands.Event, Input<String>> eventInputs = new EnumMap<>(EventCommands.Event.class);
    // The inputs Apply checks, in the order of the page.
    private final List<Input<?>> checked = new ArrayList<>();
    private final ScrollPane scroll = new ScrollPane();
    private SettingsIndex index;
    // The preferences the form shows, as loaded or last applied: what differs from them is the user's change.
    private Preferences loaded;

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
            this.view = build();
        }
        return this.view;
    }

    @Override
    public void shown() {
        load(this.gui.model().preferencesProperty().get());
    }

    private Node build() {
        this.obfuscation.getItems().addAll(ObfuscationMode.values());
        this.obfuscation.setCellFactory(l -> new NamedCell<>(this::obfuscationName));
        this.obfuscation.setButtonCell(new NamedCell<>(this::obfuscationName));
        this.bindInterface.setCellFactory(l -> new NamedCell<>(this::interfaceName));
        this.bindInterface.setButtonCell(new NamedCell<>(this::interfaceName));
        this.bindInterface.setTooltip(new Tooltip(this.gui.t("nj.settings.bindInterface.hint")));
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
        this.confirmExit.setText(this.gui.t("IDS_PW_PROMPT"));
        this.startMinimized.setText(this.gui.t("IDS_PREF_STARTMIN"));
        this.autostart.setText(this.gui.t("nj.settings.autostart"));
        this.watchClipboard.setText(this.gui.t("IDS_PF_WATCHCB"));
        this.sound.setText(this.gui.t("nj.settings.sound"));
        this.filterLan.setText(this.gui.t("IDS_PW_FILTER"));
        this.friendsOnly.setText(this.gui.t("IDS_MSGONLYFRIENDS"));
        this.spamFilter.setText(this.gui.t("IDS_ADVSPAMFILTER"));
        this.searchSpamFilter.setText(this.gui.t("IDS_SEARCHSPAMFILTER"));
        this.messageFilter.setPrefColumnCount(30);
        this.links = LinkAssociation.forThisSystem(this.gui.directories().config());
        this.kad.setText(this.gui.t("nj.settings.kad"));
        this.autoConnect.setText(this.gui.t("IDS_PW_AUTOCON"));
        this.reconnect.setText(this.gui.t("IDS_PW_RECON"));
        this.fromServer.setText(this.gui.t("IDS_PW_USC"));
        this.fromClients.setText(this.gui.t("IDS_PW_UCC"));
        this.staticOnly.setText(this.gui.t("IDS_PW_AUTOCONNECTSTATICONLY"));
        this.manualHigh.setText(this.gui.t("IDS_MANUALSERVERHIGHPRIO"));
        this.updateAtStart.setText(this.gui.t("IDS_PW_USS"));
        this.rememberDownloaded.setText(this.gui.t("IDS_PW_REMEMBERDOWNLOADED"));
        this.rememberCancelled.setText(this.gui.t("IDS_PW_REMEMBERCANCELLED"));
        this.storeSearches.setText(this.gui.t("IDS_STORESEARCHES"));
        this.previewFirst.setText(this.gui.t("IDS_DOWNLOADMOVIECHUNKS"));
        this.videoPlayer.setPrefColumnCount(26);
        this.videoPlayerArgs.setPrefColumnCount(14);
        // A program's path and arguments, and IP addresses, read left to right.
        Fx.leftToRight(this.videoPlayer);
        Fx.leftToRight(this.videoPlayerArgs);
        Fx.leftToRight(this.whitelist);
        Fx.leftToRight(this.webUiHosts);
        this.videoPlayerArgs.setPromptText(this.gui.t("nj.preview.args"));
        this.visibility.getItems().addAll(SharedFilesVisibility.values());
        this.visibility.setCellFactory(l -> new NamedCell<>(this::visibilityName));
        this.visibility.setButtonCell(new NamedCell<>(this::visibilityName));
        this.addPaused.setText(this.gui.t("IDS_ADDNEWFILESPAUSED"));
        this.cleanUpNewNames.setText(this.gui.t("IDS_AUTOCLEANUPFN"));
        this.nameCleanups.setPrefColumnCount(30);
        this.startNext.setText(this.gui.t("IDS_STARTNEXTFILE"));
        // Any category, the same first, or the same only.
        this.nextCategory
                .getItems()
                .addAll(NextDownload.ANY, NextDownload.SAME_CATEGORY_FIRST, NextDownload.SAME_CATEGORY);
        this.nextCategory.setCellFactory(l -> new NamedCell<>(this::nextName));
        this.nextCategory.setButtonCell(new NamedCell<>(this::nextName));
        this.nextCategory
                .disableProperty()
                .bind(this.startNext.selectedProperty().not());
        this.deadRetries.setPrefColumnCount(4);
        this.secureIdent.setText(this.gui.t("nj.settings.secureIdent"));
        this.credits.setText(this.gui.t("nj.settings.credits"));
        this.upnp.setText(this.gui.t("nj.settings.upnp"));
        this.webUi.setText(this.gui.t("nj.webui.enabled"));
        this.webUiLan.setText(this.gui.t("nj.webui.lan"));
        this.webUiApi.setText(this.gui.t("nj.webui.api"));
        this.proxyType.getItems().addAll(ProxySettings.Type.values());
        this.proxyType.setCellFactory(l -> new NamedCell<>(t -> this.gui.t("nj.proxy." + key(t))));
        this.proxyType.setButtonCell(new NamedCell<>(t -> this.gui.t("nj.proxy." + key(t))));
        inputs();

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
        grid.add(this.startMinimized, 1, row++);
        grid.add(autostartRow(), 1, row++);
        grid.add(this.confirmExit, 1, row++);
        grid.add(this.watchClipboard, 1, row++);
        if (this.links.isPresent()) {
            grid.add(this.ed2kLinks, 1, row++);
        }
        row = section(grid, row, this.gui.t("IDS_PW_EKDEV_OPTIONS"));
        grid.add(this.sound, 1, row++);
        row = field(grid, row, this.gui.t("nj.settings.soundFile"), soundRow());
        row = section(grid, row, this.gui.t("nj.settings.connection"));
        row = field(grid, row, this.gui.t("IDS_PW_CLIENTPORT") + " (TCP)", this.tcpPort);
        row = field(grid, row, "UDP", this.udpPort);
        row = field(grid, row, this.gui.t("nj.settings.upload"), this.upload.node());
        row = field(grid, row, this.gui.t("nj.settings.download"), this.download.node());
        row = field(grid, row, this.gui.t("nj.settings.downloadCapacity"), this.downloadCapacity.node());
        row = field(grid, row, this.gui.t("nj.settings.uploadCapacity"), this.uploadCapacity.node());
        row = field(grid, row, this.gui.t("IDS_PW_MAXC"), this.connections);
        this.newConnections.setPrefColumnCount(5);
        row = field(grid, row, this.gui.t("IDS_MAXCON5SECLABEL"), this.newConnections);
        this.queueSize.setPrefColumnCount(7);
        row = field(grid, row, this.gui.t("IDS_QUEUESIZE"), this.queueSize);
        row = field(grid, row, this.gui.t("IDS_PW_MAXSOURCES"), this.sources);
        row = field(grid, row, this.gui.t("nj.settings.obfuscation"), this.obfuscation);
        row = field(grid, row, this.gui.t("nj.settings.bindInterface"), this.bindInterface);
        grid.add(this.ed2k, 1, row++);
        grid.add(this.kad, 1, row++);
        grid.add(this.autoConnect, 1, row++);
        grid.add(this.reconnect, 1, row++);
        grid.add(this.secureIdent, 1, row++);
        grid.add(this.credits, 1, row++);
        grid.add(this.upnp, 1, row++);
        this.portTest = new PortTestPane(this.gui, () -> this::ports);
        grid.add(this.portTest.node(), 1, row++);
        row = section(grid, row, this.gui.t("IDS_PW_SERVER"));
        Label retries = new Label(this.gui.t("IDS_PW_RETRIES"));
        HBox dead = new HBox(8, this.deadRetries, retries);
        dead.setAlignment(Pos.CENTER_LEFT);
        row = field(grid, row, this.gui.t("IDS_PW_RDEAD"), dead);
        grid.add(this.fromServer, 1, row++);
        grid.add(this.fromClients, 1, row++);
        grid.add(this.staticOnly, 1, row++);
        grid.add(this.manualHigh, 1, row++);
        Button addresses = Fx.button(this.gui.t("IDS_EDITLIST"), Material2AL.EDIT, this::editServerListAddresses);
        HBox update = new HBox(8, this.updateAtStart, addresses);
        update.setAlignment(Pos.CENTER_LEFT);
        grid.add(update, 1, row++);
        row = section(grid, row, this.gui.t("IDS_PW_PROXY"));
        row = field(grid, row, this.gui.t("nj.proxy.type"), this.proxyType);
        row = field(grid, row, this.gui.t("nj.proxy.host"), new HBox(8, this.proxyHost, this.proxyPort));
        row = field(grid, row, this.gui.t("nj.proxy.user"), new HBox(8, this.proxyUser, this.proxyPassword));
        row = section(grid, row, this.gui.t("IDS_PW_DIR"));
        row = field(grid, row, this.gui.t("IDS_PW_INCOMING"), directory(this.incoming));
        row = field(grid, row, this.gui.t("IDS_PW_TEMP"), directory(this.temp));
        row = field(grid, row, this.gui.t("IDS_MINFREEDISKSPACE"), this.minFreeSpace);
        this.backups.setPrefColumnCount(4);
        row = field(grid, row, this.gui.t("nj.settings.backups"), this.backups);
        grid.add(this.rememberDownloaded, 1, row++);
        grid.add(this.rememberCancelled, 1, row++);
        grid.add(this.storeSearches, 1, row++);
        grid.add(this.previewFirst, 1, row++);
        row = field(grid, row, this.gui.t("IDS_PW_VIDEOPLAYER"), new HBox(8, this.videoPlayer, this.videoPlayerArgs));
        row = field(grid, row, this.gui.t("IDS_PW_SHARE"), this.visibility);
        grid.add(this.addPaused, 1, row++);
        grid.add(this.cleanUpNewNames, 1, row++);
        row = field(grid, row, this.gui.t("IDS_FILTERFILENAMEWORD"), this.nameCleanups);
        HBox next = new HBox(8, this.startNext, this.nextCategory);
        next.setAlignment(Pos.CENTER_LEFT);
        grid.add(next, 1, row++);
        row = section(grid, row, this.gui.t("IDS_IPFILTER"));
        row = field(grid, row, this.gui.t("IDS_UPDATEFROM"), ipFilterRow());
        this.filterLevel.setPrefColumnCount(4);
        row = field(grid, row, this.gui.t("IDS_FILTERLEVEL"), this.filterLevel);
        grid.add(this.filterLan, 1, row++);
        this.whitelist.setPrefRowCount(3);
        this.whitelist.setPrefColumnCount(32);
        this.whitelist.setPromptText("1.2.3.4\n10.0.0.0/8");
        row = field(grid, row, this.gui.t("nj.settings.whitelist"), this.whitelist);
        grid.add(this.searchSpamFilter, 1, row++);
        row = section(grid, row, this.gui.t("IDS_EM_MESSAGES"));
        row = field(grid, row, this.gui.t("IDS_FILTERLABEL"), this.messageFilter);
        grid.add(this.friendsOnly, 1, row++);
        grid.add(this.spamFilter, 1, row++);
        row = section(grid, row, this.gui.t("nj.scheduler.title"));
        grid.add(scheduler(), 0, row++, 2, 1);
        row = section(grid, row, this.gui.t("nj.settings.events"));
        for (EventCommands.Event event : EventCommands.Event.values()) {
            grid.add(this.eventSwitches.get(event), 0, row);
            grid.add(this.eventInputs.get(event).field(), 1, row++);
        }
        row = section(grid, row, this.gui.t("IDS_PW_WS"));
        grid.add(this.webUi, 1, row++);
        row = field(grid, row, this.gui.t("nj.webui.port"), this.webUiPort);
        grid.add(this.webUiLan, 1, row++);
        this.webUiHosts.setPrefColumnCount(32);
        this.webUiHosts.setPromptText("nas.local, njmule.example.com");
        row = field(grid, row, this.gui.t("nj.webui.hosts"), this.webUiHosts);
        row = field(grid, row, this.gui.t("nj.webui.password"), new HBox(8, this.webUiPassword, this.webUiAgain));
        grid.add(this.webUiApi, 1, row++);
        grid.add(
                new HBox(
                        8,
                        Fx.button(this.gui.t("nj.webui.copyToken"), Material2AL.CONTENT_COPY, this::copyApiToken),
                        Fx.button(this.gui.t("nj.webui.newToken"), Material2MZ.REFRESH, this::newApiToken)),
                1,
                row++);
        Label webNote = new Label(this.gui.t("nj.webui.note"));
        webNote.setWrapText(true);
        webNote.getStyleClass().add("text-muted");
        grid.add(webNote, 0, row++, 2, 1);
        Label note = new Label(this.gui.t("nj.settings.restartNote"));
        note.setWrapText(true);
        note.getStyleClass().add("text-muted");
        grid.add(note, 0, row, 2, 1);

        Button apply = Fx.primary(Fx.button(this.gui.t("IDS_PW_APPLY"), Material2AL.DONE, this::apply));
        List<Input<?>> checked = this.checked;
        checked.addAll(List.of(
                this.nickInput,
                this.tcpPortInput,
                this.udpPortInput,
                this.uploadInput,
                this.downloadInput,
                this.downloadCapacityInput,
                this.uploadCapacityInput,
                this.connectionsInput,
                this.newConnectionsInput,
                this.queueSizeInput,
                this.sourcesInput,
                this.proxyHostInput,
                this.proxyPortInput,
                this.proxyUserInput,
                this.incomingInput,
                this.tempInput,
                this.soundFileInput,
                this.minFreeSpaceInput,
                this.deadRetriesInput,
                this.backupsInput,
                this.filterLevelInput,
                this.whitelistInput,
                this.webUiPortInput,
                this.webUiHostsInput,
                this.webUiPasswordInput,
                this.webUiAgainInput));
        checked.addAll(this.eventInputs.values());
        // Apply stays enabled: pressed with a wrong value, it shows that field and says what it takes.
        var tools = Fx.toolRow(apply, Fx.button(this.gui.t("IDS_PW_RESET"), Material2MZ.REFRESH, this::shown));
        this.scroll.setContent(grid);
        this.scroll.setFitToWidth(true);
        load(this.gui.model().preferencesProperty().get());
        // Beside the long page, its sections and a box that finds an option by its words.
        this.index = new SettingsIndex(this.gui, grid, this.scroll);
        HBox body = new HBox(12, this.index.view(), this.scroll);
        HBox.setHgrow(this.scroll, Priority.ALWAYS);
        return Fx.screen(title(), tools, body);
    }

    // Each box takes only its kind of value, within the limits the engine accepts.
    private void inputs() {
        Inputs inputs = new Inputs(this.gui);
        this.nickInput = inputs.nick(this.nick, this.gui.t("IDS_PW_NICK"), Preferences.MAX_NICK_LENGTH);
        this.tcpPortInput = inputs.number(this.tcpPort, "TCP", 1, Preferences.MAX_PORT);
        this.udpPortInput = inputs.number(this.udpPort, "UDP", 1, Preferences.MAX_PORT);
        // Limits in KB/s or MB/s or none, the line in Mbit/s as it is sold; all kept in KiB/s.
        this.upload = RateField.limit(this.gui, this.gui.t("nj.settings.upload"));
        this.download = RateField.limit(this.gui, this.gui.t("nj.settings.download"));
        this.downloadCapacity = RateField.line(this.gui, this.gui.t("nj.settings.downloadCapacity"));
        this.uploadCapacity = RateField.line(this.gui, this.gui.t("nj.settings.uploadCapacity"));
        this.uploadInput = this.upload.input();
        this.downloadInput = this.download.input();
        this.downloadCapacityInput = this.downloadCapacity.input();
        this.uploadCapacityInput = this.uploadCapacity.input();
        this.connectionsInput =
                inputs.number(this.connections, this.gui.t("IDS_PW_MAXC"), 1, Preferences.MAX_CONNECTIONS);
        this.newConnectionsInput = inputs.number(
                this.newConnections, this.gui.t("IDS_MAXCON5SECLABEL"), 1, Preferences.MAX_NEW_CONNECTIONS);
        this.queueSizeInput = inputs.number(
                this.queueSize,
                this.gui.t("IDS_QUEUESIZE"),
                Preferences.MIN_UPLOAD_QUEUE,
                Preferences.MAX_UPLOAD_QUEUE);
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
        this.soundFileInput = inputs.soundFile(this.soundFile, this.gui.t("nj.settings.soundFile"));
        this.minFreeSpaceInput =
                inputs.number(this.minFreeSpace, this.gui.t("IDS_MINFREEDISKSPACE"), 0, Preferences.MAX_MIN_FREE_SPACE);
        this.filterLevelInput =
                inputs.number(this.filterLevel, this.gui.t("IDS_FILTERLEVEL"), 0, Preferences.MAX_IP_FILTER_LEVEL);
        // The area runs left to right: its hint keeps the direction of the interface's language.
        String whitelistHint = this.gui.i18n().embed(this.gui.t("nj.settings.whitelistHint"));
        this.whitelistInput = Input.of(
                this.whitelist,
                this.gui.t("nj.settings.whitelist"),
                whitelistHint,
                c -> c == '\n' || !Character.isISOControl(c),
                Preferences.MAX_IP_FILTER_WHITELIST,
                t -> {
                    if (Preferences.invalidWhitelistEntry(t).isPresent()) {
                        throw new IllegalArgumentException(t);
                    }
                    return t;
                });
        // The tooltip names the line to fix.
        this.whitelist
                .textProperty()
                .addListener((o, was, now) -> this.whitelist
                        .getTooltip()
                        .setText(Preferences.invalidWhitelistEntry(now)
                                .map(entry -> whitelistHint
                                        + "\n"
                                        + this.gui.i18n().embed(this.gui.f("nj.settings.whitelistInvalid", entry)))
                                .orElse(whitelistHint)));
        this.backupsInput =
                inputs.number(this.backups, this.gui.t("nj.settings.backups"), 0, Preferences.MAX_BACKUP_COPIES);
        this.deadRetriesInput =
                inputs.number(this.deadRetries, this.gui.t("IDS_PW_RDEAD"), 1, Preferences.MAX_DEAD_SERVER_RETRIES);
        this.webUiPortInput = inputs.number(this.webUiPort, this.gui.t("nj.webui.port"), 1, Preferences.MAX_PORT);
        // Browsers send host names in ASCII, an international one in its xn-- form.
        this.webUiHostsInput = Input.of(
                this.webUiHosts,
                this.gui.t("nj.webui.hosts"),
                this.gui.i18n().embed(this.gui.t("nj.webui.hostsHint")),
                c -> c < 0x80 && !Character.isISOControl(c),
                MAX_WEBUI_HOSTS,
                WebUiSettings::hostNames);
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
        for (EventCommands.Event event : EventCommands.Event.values()) {
            String name = this.gui.t("nj.events." + event.id());
            this.eventSwitches.put(event, new CheckBox(name));
            TextField line = new TextField();
            line.setPrefColumnCount(40);
            line.setPromptText(String.join(" ", event.placeholders()));
            line.setAccessibleText(name);
            this.eventInputs.put(event, inputs.commandLine(line, name, event.placeholders()));
        }
    }

    // An IP filter from the web, from an address typed or used before.
    private Node ipFilterRow() {
        this.ipFilter = new UrlBox(this.gui, "ipFilter", this.gui.t("IDS_IPFILTER"));
        this.ipFilter.node().setPromptText("https://\u2026/ipfilter.zip");
        this.ipFilter.node().setPrefWidth(420);
        Button update = Fx.button(this.gui.t("IDS_SV_UPDATE"), Material2MZ.REFRESH, this::updateIpFilter);
        update.disableProperty().bind(this.ipFilter.input().validProperty().not());
        return new HBox(6, this.ipFilter.node(), update);
    }

    void updateIpFilter() {
        if (!this.ipFilter.input().isValid()) {
            return;
        }
        URI url = this.ipFilter.input().value();
        this.gui.inform().accept(this.gui.t("IDS_DWL_IPFILTERFILE"));
        this.gui.model().updateIpFilter(url, ranges -> {
            this.ipFilter.worked(url);
            this.gui.inform().accept(this.gui.f("IDS_IPFILTERLOADED", ranges));
        });
    }

    UrlBox ipFilterBox() {
        return this.ipFilter;
    }

    private SchedulerPane scheduler() {
        if (this.scheduler == null) {
            this.scheduler = new SchedulerPane(this.gui);
        }
        return this.scheduler;
    }

    SchedulerPane schedulerPane() {
        return scheduler();
    }

    // Only the installed program can start at login; elsewhere the box says why it is off. A disabled control shows
    // no tooltip, so its row carries it.
    private Node autostartRow() {
        HBox row = new HBox(this.autostart);
        if (this.gui.autostart().isEmpty()) {
            this.autostart.setDisable(true);
            Tooltip why = new Tooltip(this.gui.t("nj.settings.autostartUnavailable"));
            this.autostart.setTooltip(why);
            Tooltip.install(row, why);
        }
        return row;
    }

    // Asking the system takes a moment: off the FX thread, and the box waits disabled meanwhile. Only the latest
    // answer counts, so that an older one never undoes what the user ticked since.
    private void loadAutostart() {
        this.gui.autostart().ifPresent(system -> {
            this.autostart.setDisable(true);
            int load = ++this.autostartLoads;
            Thread.ofVirtual().name("njmule-autostart").start(() -> {
                boolean on;
                try {
                    on = system.isOn();
                } catch (IOException e) {
                    on = false;
                }
                boolean now = on;
                Platform.runLater(() -> {
                    if (load != this.autostartLoads) {
                        return;
                    }
                    this.autostartWasOn = Optional.of(now);
                    this.autostart.setSelected(now);
                    this.autostart.setDisable(false);
                });
            });
        });
    }

    // Starting at login is a change to the system, made only when the box changed.
    private void applyAutostart() {
        if (this.gui.autostart().isEmpty() || this.autostartWasOn.isEmpty()) {
            return;
        }
        boolean wanted = this.autostart.isSelected();
        if (wanted == this.autostartWasOn.get()) {
            return;
        }
        Autostart system = this.gui.autostart().get();
        this.autostartWasOn = Optional.of(wanted);
        Thread.ofVirtual().name("njmule-autostart").start(() -> {
            try {
                if (wanted) {
                    system.turnOn();
                } else {
                    system.turnOff();
                }
            } catch (IOException e) {
                Platform.runLater(() -> {
                    this.gui.warn().accept(this.gui.f("nj.settings.autostartFailed", e.getMessage()));
                    loadAutostart();
                });
            }
        });
    }

    // A WAV file, chosen or typed, and a button to hear it as the notifications would play it.
    private Node soundRow() {
        this.soundFile.setPrefColumnCount(32);
        Button browse = Fx.button(this.gui.t("IDS_PW_BROWSE"), Material2AL.FOLDER_OPEN, this::chooseSound);
        Button test = Fx.button(this.gui.t("IDS_TEST"), Material2MZ.VOLUME_UP, this::testSound);
        test.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> !this.soundFileInput.isValid()
                                || this.soundFile.getText().isBlank(),
                        this.soundFileInput.validProperty(),
                        this.soundFile.textProperty()));
        HBox row = new HBox(6, this.soundFile, browse, test);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void chooseSound() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(this.gui.t("nj.settings.soundFile"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV", "*.wav", "*.WAV"));
        if (this.soundFileInput.isValid()) {
            this.soundFileInput
                    .value()
                    .map(Path::getParent)
                    .filter(Files::isDirectory)
                    .ifPresent(dir -> chooser.setInitialDirectory(dir.toFile()));
        }
        File chosen = chooser.showOpenDialog(this.view.getScene().getWindow());
        if (chosen != null) {
            this.soundFile.setText(chosen.getAbsolutePath());
        }
    }

    /** Plays the sound file of the form, applied or not. */
    void testSound() {
        if (this.soundFileInput.isValid()) {
            this.soundFileInput.value().ifPresent(this.gui.sound()::play);
        }
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
        this.loaded = p;
        this.nick.setText(p.nick());
        this.tcpPort.setText(String.valueOf(p.tcpPort()));
        this.udpPort.setText(String.valueOf(p.udpPort()));
        this.upload.show(p.maxUploadKiBps());
        this.download.show(p.maxDownloadKiBps());
        this.downloadCapacity.show(p.downloadCapacityKiBps());
        this.uploadCapacity.show(p.uploadCapacityKiBps());
        this.connections.setText(String.valueOf(p.maxConnections()));
        this.newConnections.setText(String.valueOf(p.newConnectionsPerFiveSeconds()));
        this.queueSize.setText(String.valueOf(p.uploadQueueSize()));
        this.sources.setText(String.valueOf(p.maxSourcesPerFile()));
        this.obfuscation.setValue(p.obfuscation());
        this.interfaces = BindInterface.choices();
        this.bindInterface.getItems().setAll(interfaceChoices(p.bindInterface()));
        this.bindInterface.setValue(p.bindInterface());
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
        this.minFreeSpace.setText(String.valueOf(p.minFreeSpaceMiB()));
        this.deadRetries.setText(String.valueOf(p.deadServerRetries()));
        this.backups.setText(String.valueOf(p.backupCopies()));
        this.filterLevel.setText(String.valueOf(p.ipFilterLevel()));
        this.filterLan.setSelected(p.filterLanAddresses());
        this.whitelist.setText(p.ipFilterWhitelist());
        this.messageFilter.setText(p.messageFilter());
        this.friendsOnly.setSelected(p.messagesFromFriendsOnly());
        this.spamFilter.setSelected(p.advancedSpamFilter());
        this.searchSpamFilter.setSelected(p.searchSpamFilter());
        this.fromServer.setSelected(p.addServersFromServer());
        this.fromClients.setSelected(p.addServersFromClients());
        this.staticOnly.setSelected(p.staticServersOnly());
        this.manualHigh.setSelected(p.manualServersHighPriority());
        this.updateAtStart.setSelected(p.updateServersAtStart());
        this.rememberDownloaded.setSelected(p.rememberDownloadedFiles());
        this.rememberCancelled.setSelected(p.rememberCancelledFiles());
        this.storeSearches.setSelected(p.storeSearches());
        this.previewFirst.setSelected(p.previewFirst());
        this.videoPlayer.setText(p.videoPlayer());
        this.videoPlayerArgs.setText(p.videoPlayerArgs());
        this.visibility.setValue(p.sharedFilesVisibility());
        this.addPaused.setSelected(p.addDownloadsPaused());
        this.cleanUpNewNames.setSelected(p.cleanUpNewNames());
        this.nameCleanups.setText(p.nameCleanups());
        this.startNext.setSelected(p.nextDownload() != NextDownload.NONE);
        this.nextCategory.setValue(p.nextDownload() == NextDownload.NONE ? NextDownload.ANY : p.nextDownload());
        this.theme.setValue(this.gui.themes().family());
        this.mode.setValue(this.gui.themes().mode());
        this.language.setValue(this.gui.settings().language());
        this.tray.setSelected(this.gui.settings().minimizeToTray());
        this.confirmExit.setSelected(this.gui.settings().confirmExit());
        this.startMinimized.setSelected(this.gui.settings().startMinimized());
        this.watchClipboard.setSelected(this.gui.settings().watchClipboard());
        this.sound.setSelected(p.notificationSound());
        this.soundFile.setText(p.notificationSoundFile().map(Path::toString).orElse(""));
        WebUiSettings web = WebUiSettings.load(this.gui.directories().config());
        this.webUi.setSelected(web.enabled());
        this.webUiPort.setText(String.valueOf(web.port()));
        this.webUiLan.setSelected(web.lan());
        this.webUiHosts.setText(String.join(", ", web.hosts()));
        this.webUiApi.setSelected(web.api());
        this.webUiPassword.clear();
        this.webUiAgain.clear();
        for (EventCommands.Event event : EventCommands.Event.values()) {
            EventCommands.Command command = p.eventCommands().get(event);
            this.eventSwitches.get(event).setSelected(command.enabled());
            this.eventInputs.get(event).field().setText(command.commandLine());
        }
        loadLinks();
        loadAutostart();
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
                    this.gui.warn().accept(this.gui.f("nj.settings.ed2kLinksFailed", e.getMessage()));
                    loadLinks();
                });
            }
        });
    }

    /** Validates the form, then saves the preferences and the look; errors are shown. */
    // The ports and UPnP as the boxes give them, tried before the rest is applied; a wrong port stays as it was.
    private Preferences ports(Preferences was) {
        Preferences.Builder now = was.toBuilder();
        if (this.tcpPortInput.isValid()) {
            now.tcpPort(this.tcpPortInput.value());
        }
        if (this.udpPortInput.isValid()) {
            now.udpPort(this.udpPortInput.value());
        }
        return now.upnp(this.upnp.isSelected()).build();
    }

    void apply() {
        Optional<Input<?>> wrong =
                this.checked.stream().filter(i -> !i.isValid()).findFirst();
        wrong.ifPresent(i -> reveal(i.field()));
        Changes changes;
        Preferences shown;
        WebUiSettings web;
        try {
            changes = read();
            shown = changes.over(this.loaded);
            web = readWebUi(WebUiSettings.load(this.gui.directories().config()));
        } catch (IllegalArgumentException e) {
            this.gui.warn().accept(this.gui.f("nj.settings.invalid", e.getMessage()));
            return;
        }
        if (wrong.isPresent()) {
            try {
                wrong.get().value();
            } catch (IllegalArgumentException e) {
                this.gui.warn().accept(this.gui.f("nj.settings.invalid", e.getMessage()));
            }
            return;
        }
        // Only what the user changed: a limit the tray or the scheduler set meanwhile stays.
        this.gui
                .model()
                .updatePreferences(changes::over, p -> this.gui.inform().accept(this.gui.t("nj.settings.saved")));
        this.loaded = shown;
        boolean relaunch =
                !Objects.equals(this.language.getValue(), this.gui.settings().language());
        this.gui.themes().set(this.theme.getValue(), this.mode.getValue());
        this.gui.settings().setTheme(this.theme.getValue());
        this.gui.settings().setMode(this.mode.getValue());
        this.gui.settings().setLanguage(this.language.getValue());
        this.gui.settings().setMinimizeToTray(this.tray.isSelected());
        this.gui.settings().setConfirmExit(this.confirmExit.isSelected());
        this.gui.settings().setStartMinimized(this.startMinimized.isSelected());
        this.gui.settings().setWatchClipboard(this.watchClipboard.isSelected());
        try {
            this.gui.settings().save();
            web.save(this.gui.directories().config());
            this.webUiPassword.clear();
            this.webUiAgain.clear();
        } catch (IOException e) {
            this.gui.warn().accept(e.getMessage());
        }
        applyLinks();
        applyAutostart();
        if (relaunch) {
            this.gui.relaunch().run();
        }
    }

    // Each field of the form, against the preferences it was loaded from.
    private Changes read() {
        Changes changes = readEngine();
        for (EventCommands.Event event : EventCommands.Event.values()) {
            changes.set(
                    p -> p.eventCommands().get(event),
                    (b, command) ->
                            b.eventEnabled(event, command.enabled()).eventCommandLine(event, command.commandLine()),
                    new EventCommands.Command(
                            this.eventSwitches.get(event).isSelected(),
                            this.eventInputs.get(event).value()));
        }
        return changes;
    }

    private Changes readEngine() {
        return new Changes(this.loaded)
                .set(Preferences::nick, Preferences.Builder::nick, this.nickInput.value())
                .set(Preferences::tcpPort, Preferences.Builder::tcpPort, this.tcpPortInput.value())
                .set(Preferences::udpPort, Preferences.Builder::udpPort, this.udpPortInput.value())
                .set(Preferences::maxUploadKiBps, Preferences.Builder::maxUploadKiBps, this.uploadInput.value())
                .set(Preferences::maxDownloadKiBps, Preferences.Builder::maxDownloadKiBps, this.downloadInput.value())
                .set(
                        Preferences::downloadCapacityKiBps,
                        Preferences.Builder::downloadCapacityKiBps,
                        this.downloadCapacityInput.value())
                .set(
                        Preferences::uploadCapacityKiBps,
                        Preferences.Builder::uploadCapacityKiBps,
                        this.uploadCapacityInput.value())
                .set(Preferences::maxConnections, Preferences.Builder::maxConnections, this.connectionsInput.value())
                .set(
                        Preferences::newConnectionsPerFiveSeconds,
                        Preferences.Builder::newConnectionsPerFiveSeconds,
                        this.newConnectionsInput.value())
                .set(Preferences::uploadQueueSize, Preferences.Builder::uploadQueueSize, this.queueSizeInput.value())
                .set(Preferences::maxSourcesPerFile, Preferences.Builder::maxSourcesPerFile, this.sourcesInput.value())
                .set(Preferences::obfuscation, Preferences.Builder::obfuscation, this.obfuscation.getValue())
                .set(
                        Preferences::bindInterface,
                        Preferences.Builder::bindInterface,
                        Objects.requireNonNullElse(this.bindInterface.getValue(), ""))
                .set(Preferences::ed2kEnabled, Preferences.Builder::ed2kEnabled, this.ed2k.isSelected())
                .set(Preferences::kadEnabled, Preferences.Builder::kadEnabled, this.kad.isSelected())
                .set(Preferences::autoConnect, Preferences.Builder::autoConnect, this.autoConnect.isSelected())
                .set(Preferences::reconnect, Preferences.Builder::reconnect, this.reconnect.isSelected())
                .set(Preferences::secureIdent, Preferences.Builder::secureIdent, this.secureIdent.isSelected())
                .set(Preferences::creditSystem, Preferences.Builder::creditSystem, this.credits.isSelected())
                .set(Preferences::upnp, Preferences.Builder::upnp, this.upnp.isSelected())
                .set(
                        Preferences::proxy,
                        Preferences.Builder::proxy,
                        new ProxySettings(
                                this.proxyType.getValue(),
                                this.proxyHostInput.value(),
                                this.proxyPortInput.value(),
                                this.proxyUserInput.value(),
                                this.proxyPassword.getText()))
                .set(Preferences::incomingDir, Preferences.Builder::incomingDir, this.incomingInput.value())
                .set(Preferences::tempDir, Preferences.Builder::tempDir, this.tempInput.value())
                .set(Preferences::minFreeSpaceMiB, Preferences.Builder::minFreeSpaceMiB, this.minFreeSpaceInput.value())
                .set(
                        Preferences::deadServerRetries,
                        Preferences.Builder::deadServerRetries,
                        this.deadRetriesInput.value())
                .set(Preferences::backupCopies, Preferences.Builder::backupCopies, this.backupsInput.value())
                .set(Preferences::ipFilterLevel, Preferences.Builder::ipFilterLevel, this.filterLevelInput.value())
                .set(
                        Preferences::filterLanAddresses,
                        Preferences.Builder::filterLanAddresses,
                        this.filterLan.isSelected())
                .set(
                        Preferences::ipFilterWhitelist,
                        Preferences.Builder::ipFilterWhitelist,
                        this.whitelistInput.value())
                .set(
                        Preferences::messageFilter,
                        Preferences.Builder::messageFilter,
                        this.messageFilter.getText().strip())
                .set(
                        Preferences::messagesFromFriendsOnly,
                        Preferences.Builder::messagesFromFriendsOnly,
                        this.friendsOnly.isSelected())
                .set(
                        Preferences::advancedSpamFilter,
                        Preferences.Builder::advancedSpamFilter,
                        this.spamFilter.isSelected())
                .set(
                        Preferences::searchSpamFilter,
                        Preferences.Builder::searchSpamFilter,
                        this.searchSpamFilter.isSelected())
                .set(
                        Preferences::addServersFromServer,
                        Preferences.Builder::addServersFromServer,
                        this.fromServer.isSelected())
                .set(
                        Preferences::addServersFromClients,
                        Preferences.Builder::addServersFromClients,
                        this.fromClients.isSelected())
                .set(
                        Preferences::staticServersOnly,
                        Preferences.Builder::staticServersOnly,
                        this.staticOnly.isSelected())
                .set(
                        Preferences::manualServersHighPriority,
                        Preferences.Builder::manualServersHighPriority,
                        this.manualHigh.isSelected())
                .set(
                        Preferences::updateServersAtStart,
                        Preferences.Builder::updateServersAtStart,
                        this.updateAtStart.isSelected())
                .set(
                        Preferences::rememberDownloadedFiles,
                        Preferences.Builder::rememberDownloadedFiles,
                        this.rememberDownloaded.isSelected())
                .set(
                        Preferences::rememberCancelledFiles,
                        Preferences.Builder::rememberCancelledFiles,
                        this.rememberCancelled.isSelected())
                .set(Preferences::storeSearches, Preferences.Builder::storeSearches, this.storeSearches.isSelected())
                .set(Preferences::previewFirst, Preferences.Builder::previewFirst, this.previewFirst.isSelected())
                .set(Preferences::videoPlayer, Preferences.Builder::videoPlayer, this.videoPlayer.getText())
                .set(Preferences::videoPlayerArgs, Preferences.Builder::videoPlayerArgs, this.videoPlayerArgs.getText())
                .set(
                        Preferences::sharedFilesVisibility,
                        Preferences.Builder::sharedFilesVisibility,
                        this.visibility.getValue())
                .set(
                        Preferences::addDownloadsPaused,
                        Preferences.Builder::addDownloadsPaused,
                        this.addPaused.isSelected())
                .set(
                        Preferences::cleanUpNewNames,
                        Preferences.Builder::cleanUpNewNames,
                        this.cleanUpNewNames.isSelected())
                .set(
                        Preferences::nameCleanups,
                        Preferences.Builder::nameCleanups,
                        this.nameCleanups.getText().strip())
                .set(
                        Preferences::nextDownload,
                        Preferences.Builder::nextDownload,
                        this.startNext.isSelected() ? this.nextCategory.getValue() : NextDownload.NONE)
                .set(Preferences::notificationSound, Preferences.Builder::notificationSound, this.sound.isSelected())
                .set(
                        Preferences::notificationSoundFile,
                        Preferences.Builder::notificationSoundFile,
                        this.soundFileInput.value());
    }

    /** What the form changed, set on whichever preferences are current when they are written. */
    private static final class Changes {
        private final Preferences loaded;
        private final List<Consumer<Preferences.Builder>> edits = new ArrayList<>();

        Changes(Preferences loaded) {
            this.loaded = loaded;
        }

        <T> Changes set(Function<Preferences, T> field, BiConsumer<Preferences.Builder, T> setter, T value) {
            if (!Objects.equals(field.apply(this.loaded), value)) {
                this.edits.add(b -> setter.accept(b, value));
            }
            return this;
        }

        Preferences over(Preferences current) {
            Preferences.Builder builder = current.toBuilder();
            this.edits.forEach(e -> e.accept(builder));
            return builder.build();
        }
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
        return new WebUiSettings(
                this.webUi.isSelected(),
                port,
                this.webUiLan.isSelected(),
                hash,
                this.webUiApi.isSelected(),
                this.webUiHostsInput.value());
    }

    // Scrolls the page to show field a third of the way down, and puts the cursor in it.
    private void reveal(Node field) {
        Node content = this.scroll.getContent();
        Bounds at = content.sceneToLocal(field.localToScene(field.getBoundsInLocal()));
        double view = this.scroll.getViewportBounds().getHeight();
        double free = content.getLayoutBounds().getHeight() - view;
        if (free > 0) {
            this.scroll.setVvalue(Math.clamp((at.getMinY() - view / 3) / free, 0.0, 1.0));
        }
        field.requestFocus();
    }

    /** The index beside the page, for tests. */
    SettingsIndex index() {
        return this.index;
    }

    /** The scroll pane of the page, for tests. */
    ScrollPane scrollPane() {
        return this.scroll;
    }

    // The token callers of the remote-control API need, made on first use.
    void copyApiToken() {
        try {
            ClipboardWatch.copy(ApiToken.loadOrCreate(this.gui.directories().config(), new SecureRandom()));
            this.gui.inform().accept(this.gui.t("nj.webui.tokenCopied"));
        } catch (IOException e) {
            this.gui.warn().accept(this.gui.f("nj.webui.tokenFailed", e.getMessage()));
        }
    }

    // Whoever holds the old token is refused from now on.
    void newApiToken() {
        try {
            ClipboardWatch.copy(ApiToken.renew(this.gui.directories().config(), new SecureRandom()));
            this.gui.inform().accept(this.gui.t("nj.webui.tokenRenewed"));
        } catch (IOException e) {
            this.gui.warn().accept(this.gui.f("nj.webui.tokenFailed", e.getMessage()));
        }
    }

    // Any, the interfaces up now, and the one chosen before even while it is not there.
    private List<String> interfaceChoices(String chosen) {
        List<String> choices = new ArrayList<>();
        choices.add("");
        this.interfaces.forEach(c -> choices.add(c.name()));
        if (!chosen.isBlank() && !choices.contains(chosen)) {
            choices.add(chosen);
        }
        return choices;
    }

    private String interfaceName(String choice) {
        if (choice.isBlank()) {
            return this.gui.t("nj.settings.bindInterface.any");
        }
        for (BindInterface.Candidate c : this.interfaces) {
            if (c.name().equals(choice)) {
                return c.displayName() + " ("
                        + String.join(
                                ", ",
                                c.addresses().stream()
                                        .map(a -> a.getHostAddress())
                                        .toList()) + ")";
            }
            if (c.addresses().stream().anyMatch(a -> a.getHostAddress().equals(choice))) {
                return choice + " (" + c.displayName() + ")";
            }
        }
        return this.gui.f("nj.settings.bindInterface.missing", choice);
    }

    private String obfuscationName(ObfuscationMode m) {
        return this.gui.t("nj.obfuscation." + key(m));
    }

    private String visibilityName(SharedFilesVisibility v) {
        return this.gui.t(
                switch (v) {
                    case EVERYBODY -> "IDS_PW_EVER";
                    case FRIENDS -> "IDS_PW_FRIENDS";
                    case NOBODY -> "IDS_PW_NOONE";
                });
    }

    private String nextName(NextDownload next) {
        return switch (next) {
            case SAME_CATEGORY_FIRST -> this.gui.t("IDS_PREF_STARTNEXTFILECAT");
            case SAME_CATEGORY -> this.gui.t("IDS_PREF_STARTNEXTFILECATONLY");
            case ANY, NONE -> this.gui.t("nj.settings.anyCategory");
        };
    }

    private String languageName(Optional<Locale> l) {
        return l.map(I18n::nameOf).orElse(this.gui.t("nj.settings.systemLanguage"));
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    // eMule's addresses.dat: the server.met addresses the update at start takes, one per line.
    void editServerListAddresses() {
        this.gui.model().serverListAddresses(addresses -> {
            TextArea text = Fx.leftToRight(new TextArea(
                    String.join("\n", addresses.stream().map(URI::toString).toList())));
            text.setPrefRowCount(8);
            text.setPrefColumnCount(50);
            text.setPromptText("https://\u2026/server.met");
            Dialog<ButtonType> dialog = this.gui.dialog(new Dialog<>());
            dialog.setTitle(this.gui.t("IDS_PW_USS"));
            dialog.setHeaderText(this.gui.t("nj.settings.serverListAddresses"));
            dialog.getDialogPane().setContent(text);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.initOwner(this.view.getScene().getWindow());
            dialog.showAndWait()
                    .filter(b -> b == ButtonType.OK)
                    .ifPresent(b -> this.gui.model().setServerListAddresses(addresses(text.getText())));
        });
    }

    /** The http and https addresses among the lines of {@code text}. */
    static List<URI> addresses(String text) {
        List<URI> addresses = new ArrayList<>();
        for (String line :
                text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
            try {
                URI url = new URI(line);
                if (("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                        && url.getHost() != null) {
                    addresses.add(url);
                }
            } catch (URISyntaxException e) {
                // Not an address: left out.
            }
        }
        return addresses;
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

    CheckBox searchSpamFilterBox() {
        return this.searchSpamFilter;
    }

    CheckBox cleanUpNewNamesBox() {
        return this.cleanUpNewNames;
    }

    TextField nameCleanupsField() {
        return this.nameCleanups;
    }

    ComboBox<String> bindInterfaceBox() {
        return this.bindInterface;
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

    CheckBox webUiApiBox() {
        return this.webUiApi;
    }

    TextField webUiPortField() {
        return this.webUiPort;
    }

    TextField webUiHostsField() {
        return this.webUiHosts;
    }

    PasswordField webUiPasswordField() {
        return this.webUiPassword;
    }

    PasswordField webUiAgainField() {
        return this.webUiAgain;
    }

    TextArea whitelistArea() {
        return this.whitelist;
    }

    Input<String> whitelistInput() {
        return this.whitelistInput;
    }

    CheckBox autostartBox() {
        return this.autostart;
    }

    CheckBox soundBox() {
        return this.sound;
    }

    TextField soundFileField() {
        return this.soundFile;
    }

    CheckBox eventSwitch(EventCommands.Event event) {
        return this.eventSwitches.get(event);
    }

    TextInputControl eventField(EventCommands.Event event) {
        return this.eventInputs.get(event).field();
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
            setText(empty || item == null ? null : this.name.apply(item));
        }
    }
}
