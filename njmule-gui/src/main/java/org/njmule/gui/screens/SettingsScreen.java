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

import atlantafx.base.controls.ToggleSwitch;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.njmule.app.Autostart;
import org.njmule.app.LinkAssociation;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.BindInterface;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.NextDownload;
import org.njmule.core.config.NightHours;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;
import org.njmule.core.remote.ApiToken;
import org.njmule.gui.dialogs.UpdateDialog;
import org.njmule.gui.i18n.I18n;
import org.njmule.gui.infra.GuiSettings;
import org.njmule.gui.infra.Themes;
import org.njmule.gui.shell.NotificationArea;
import org.njmule.gui.ui.Ask;
import org.njmule.gui.ui.ClipboardWatch;
import org.njmule.gui.ui.Fx;
import org.njmule.gui.ui.Gui;
import org.njmule.gui.ui.Icons;
import org.njmule.gui.ui.Input;
import org.njmule.gui.ui.Inputs;
import org.njmule.gui.ui.PortTestPane;
import org.njmule.gui.ui.RateField;
import org.njmule.gui.ui.Screen;
import org.njmule.webui.Passwords;
import org.njmule.webui.WebUiSettings;

/**
 * The engine's preferences and the look of the interface, a page per subject. Each option applies as soon as it is set;
 * the ports, the proxy and the network interface wait for Apply and reconnect, the language for a restart.
 */
public final class SettingsScreen implements Screen {

    private static final int MAX_PROXY_USER = 255;
    private static final int MAX_NIGHT_HOURS = 13;
    private static final int MAX_PASSWORD = 1024;
    // Room for the most lists a filter takes, each a long address.
    private static final int MAX_IP_FILTER_LISTS_TEXT = Preferences.MAX_IP_FILTER_LISTS * 2_049;
    private static final int MAX_WEBUI_HOSTS = 4096;
    private static final Preferences DEFAULTS = Preferences.defaults();

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
    private final ToggleSwitch ed2k = new ToggleSwitch();
    private final ToggleSwitch ed2kLinks = new ToggleSwitch();
    private final ToggleSwitch tray = new ToggleSwitch();
    private final ToggleSwitch confirmExit = new ToggleSwitch();
    private final ToggleSwitch startMinimized = new ToggleSwitch();
    private final ToggleSwitch autostart = new ToggleSwitch();
    // Whether njMule started at login when the form was loaded; empty while unknown.
    private Optional<Boolean> autostartWasOn = Optional.empty();
    // The questions to the system asked so far; FX thread.
    private int autostartLoads;
    private final ToggleSwitch watchClipboard = new ToggleSwitch();
    private final ToggleSwitch sound = new ToggleSwitch();
    private final TextField soundFile = new TextField();
    private final ToggleSwitch filterLan = new ToggleSwitch();
    private final TextArea whitelist = new TextArea();
    private final TextArea ipFilterLists = new TextArea();
    private final TextField ipFilterDays = new TextField();
    private final TextField messageFilter = new TextField();
    private final ToggleSwitch friendsOnly = new ToggleSwitch();
    private final ToggleSwitch spamFilter = new ToggleSwitch();
    private final ToggleSwitch searchSpamFilter = new ToggleSwitch();
    // Whether ed2k links opened in njMule when the form was loaded; empty while unknown.
    private Optional<Boolean> linksWereOurs = Optional.empty();
    private Optional<LinkAssociation> links = Optional.empty();
    private final ToggleSwitch kad = new ToggleSwitch();
    private final ToggleSwitch autoConnect = new ToggleSwitch();
    private final ToggleSwitch reconnect = new ToggleSwitch();
    private final ToggleSwitch secureIdent = new ToggleSwitch();
    private final ToggleSwitch credits = new ToggleSwitch();
    private final ToggleSwitch upnp = new ToggleSwitch();
    private PortTestPane portTest;
    private final TextField deadRetries = new TextField();
    private final TextField backups = new TextField();
    private final TextField filterLevel = new TextField();
    private final ToggleSwitch fromServer = new ToggleSwitch();
    private final ToggleSwitch fromClients = new ToggleSwitch();
    private final ToggleSwitch staticOnly = new ToggleSwitch();
    private final ToggleSwitch manualHigh = new ToggleSwitch();
    private final ToggleSwitch updateAtStart = new ToggleSwitch();
    private final ToggleSwitch rememberDownloaded = new ToggleSwitch();
    private final ToggleSwitch rememberCancelled = new ToggleSwitch();
    private final ToggleSwitch storeSearches = new ToggleSwitch();
    private final ToggleSwitch previewFirst = new ToggleSwitch();
    private final TextField videoPlayer = new TextField();
    private final TextField videoPlayerArgs = new TextField();
    private Button videoPlayerBrowse;
    private final ComboBox<SharedFilesVisibility> visibility = new ComboBox<>();
    private final ToggleSwitch addPaused = new ToggleSwitch();
    private final ToggleSwitch cleanUpNewNames = new ToggleSwitch();
    private final TextField nameCleanups = new TextField();
    private final ToggleSwitch startNext = new ToggleSwitch();
    private final ComboBox<NextDownload> nextCategory = new ComboBox<>();
    private final ComboBox<ProxySettings.Type> proxyType = new ComboBox<>();
    private final ComboBox<UpdateMode> updateMode = new ComboBox<>();
    private final ComboBox<UpdateChannel> updateChannel = new ComboBox<>();
    private final ComboBox<UpdateInstallTime> updateInstallTime = new ComboBox<>();
    private final TextField nightHours = new TextField();
    private Input<NightHours> nightHoursInput;
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
    private final ToggleSwitch webUi = new ToggleSwitch();
    private final TextField webUiPort = new TextField();
    private final ToggleSwitch webUiLan = new ToggleSwitch();
    private final TextField webUiHosts = new TextField();
    private final TextField webUiProxies = new TextField();
    private final ToggleSwitch webUiApi = new ToggleSwitch();
    private final PasswordField webUiPassword = new PasswordField();
    private final PasswordField webUiAgain = new PasswordField();
    private SchedulerPane scheduler;
    private Input<String> nickInput;
    private Input<Integer> tcpPortInput;
    private Input<Integer> udpPortInput;
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
    private Input<String> ipFilterListsInput;
    private Input<Integer> ipFilterDaysInput;
    private Input<Integer> webUiPortInput;
    private Input<List<String>> webUiHostsInput;
    private Input<List<String>> webUiProxiesInput;
    private Input<String> webUiPasswordInput;
    private Input<String> webUiAgainInput;
    private final Map<EventCommands.Event, ToggleSwitch> eventSwitches = new EnumMap<>(EventCommands.Event.class);
    private final Map<EventCommands.Event, Input<String>> eventInputs = new EnumMap<>(EventCommands.Event.class);
    // Every option of the pages, and how each shows its saved value and saves what the user set.
    private final List<SettingRow> rows = new ArrayList<>();
    private final List<Shown> shows = new ArrayList<>();
    private final List<Runnable> commits = new ArrayList<>();
    private final List<Runnable> webCommits = new ArrayList<>();
    // What waits for Apply and reconnect, by the preference it sets.
    private final Map<String, Consumer<Preferences.Builder>> held = new LinkedHashMap<>();
    private boolean languageHeld;
    // Set while the controls show saved values: their changes are not the user's.
    private boolean loading;
    private SettingsPages pages;
    private final Label pendingText = new Label();
    private final Button applyHeld = new Button();
    private final HBox bar = new HBox(10);
    // The preferences as saved: an option differs from its default against them.
    private Preferences loaded = DEFAULTS;
    private WebUiSettings web = WebUiSettings.DEFAULTS;

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
    public Icons icon() {
        return Icons.OPTIONS;
    }

    @Override
    public ObservableValue<String> summary() {
        return new SimpleStringProperty(this.gui.t("nj.settings.subtitle"));
    }

    @Override
    public Node view() {
        if (this.view == null) {
            this.view = build();
        }
        return this.view;
    }

    // Options set elsewhere meanwhile show; what waits for Apply and reconnect stays as the user left it.
    @Override
    public void shown() {
        if (this.held.isEmpty() && !this.languageHeld) {
            load(this.gui.model().preferencesProperty().get());
        } else {
            refresh();
        }
    }

    private Node build() {
        this.obfuscation.getItems().addAll(ObfuscationMode.values());
        this.obfuscation.setCellFactory(l -> new NamedCell<>(this::obfuscationName));
        this.obfuscation.setButtonCell(new NamedCell<>(this::obfuscationName));
        this.bindInterface.setCellFactory(l -> new NamedCell<>(this::interfaceName));
        this.bindInterface.setButtonCell(new NamedCell<>(this::interfaceName));
        this.bindInterface.setPrefWidth(260);
        this.bindInterface.setMinWidth(140);
        this.theme.getItems().addAll(Themes.Family.values());
        this.theme.setCellFactory(l -> new NamedCell<>(this::themeName));
        this.theme.setButtonCell(new NamedCell<>(this::themeName));
        this.mode.getItems().addAll(GuiSettings.Mode.values());
        this.mode.setCellFactory(l -> new NamedCell<>(this::modeName));
        this.mode.setButtonCell(new NamedCell<>(this::modeName));
        this.language.getItems().add(Optional.empty());
        I18n.LANGUAGES.forEach(l -> this.language.getItems().add(Optional.of(l)));
        this.language.setCellFactory(l -> new NamedCell<>(this::languageName));
        this.language.setButtonCell(new NamedCell<>(this::languageName));
        this.links = LinkAssociation.forThisSystem(this.gui.directories().config());
        this.messageFilter.setPrefColumnCount(30);
        this.videoPlayer.setPrefColumnCount(26);
        this.videoPlayerArgs.setPrefColumnCount(14);
        // A program's path and arguments, and IP addresses, read left to right.
        Fx.leftToRight(this.videoPlayer);
        Fx.leftToRight(this.videoPlayerArgs);
        Fx.leftToRight(this.whitelist);
        Fx.leftToRight(this.webUiHosts);
        Fx.leftToRight(this.webUiProxies);
        this.videoPlayerArgs.setPromptText(this.gui.t("nj.preview.args"));
        this.visibility.getItems().addAll(SharedFilesVisibility.values());
        this.visibility.setCellFactory(l -> new NamedCell<>(this::visibilityName));
        this.visibility.setButtonCell(new NamedCell<>(this::visibilityName));
        this.nameCleanups.setPrefColumnCount(30);
        // Any category, the same first, or the same only.
        this.nextCategory
                .getItems()
                .addAll(NextDownload.ANY, NextDownload.SAME_CATEGORY_FIRST, NextDownload.SAME_CATEGORY);
        this.nextCategory.setCellFactory(l -> new NamedCell<>(this::nextName));
        this.nextCategory.setButtonCell(new NamedCell<>(this::nextName));
        this.nextCategory
                .disableProperty()
                .bind(this.startNext.selectedProperty().not());
        this.proxyType.getItems().addAll(ProxySettings.Type.values());
        this.proxyType.setCellFactory(l -> new NamedCell<>(t -> this.gui.t("nj.proxy." + key(t))));
        this.proxyType.setButtonCell(new NamedCell<>(t -> this.gui.t("nj.proxy." + key(t))));
        this.updateMode.getItems().addAll(UpdateMode.values());
        this.updateMode.setCellFactory(l -> new NamedCell<>(m -> this.gui.t("nj.update.mode." + key(m))));
        this.updateMode.setButtonCell(new NamedCell<>(m -> this.gui.t("nj.update.mode." + key(m))));
        this.updateChannel.getItems().addAll(UpdateChannel.values());
        this.updateChannel.setCellFactory(l -> new NamedCell<>(c -> this.gui.t("nj.update.channel." + key(c))));
        this.updateChannel.setButtonCell(new NamedCell<>(c -> this.gui.t("nj.update.channel." + key(c))));
        this.updateInstallTime.getItems().addAll(UpdateInstallTime.values());
        this.updateInstallTime.setCellFactory(l -> new NamedCell<>(this::installTimeName));
        this.updateInstallTime.setButtonCell(new NamedCell<>(this::installTimeName));
        // When to install matters only to what installs on its own; the night hours only to the night.
        this.updateInstallTime
                .disableProperty()
                .bind(this.updateMode.valueProperty().isNotEqualTo(UpdateMode.INSTALL));
        this.nightHours
                .disableProperty()
                .bind(this.updateInstallTime
                        .disableProperty()
                        .or(this.updateInstallTime.valueProperty().isNotEqualTo(UpdateInstallTime.EXIT_OR_NIGHT)));
        this.nightHours.setPrefColumnCount(9);
        Fx.leftToRight(this.nightHours);
        // Without a proxy there is nothing to fill in.
        for (Control c : List.of(this.proxyHost, this.proxyPort, this.proxyUser, this.proxyPassword)) {
            c.disableProperty().bind(this.proxyType.valueProperty().isEqualTo(ProxySettings.Type.NONE));
        }
        // Numbers keep to narrow fields.
        for (TextField f : List.of(
                this.tcpPort,
                this.udpPort,
                this.connections,
                this.newConnections,
                this.queueSize,
                this.sources,
                this.minFreeSpace,
                this.deadRetries,
                this.backups,
                this.filterLevel,
                this.ipFilterDays,
                this.webUiPort,
                this.proxyPort)) {
            f.setPrefColumnCount(6);
        }
        this.nick.setPrefColumnCount(16);
        inputs();
        this.portTest = new PortTestPane(
                this.gui,
                () -> this::ports,
                this.tcpPort.textProperty(),
                this.udpPort.textProperty(),
                this.upnp.selectedProperty());
        this.pages = new SettingsPages(this.gui, pages());
        this.rows.forEach(r -> r.inputs().forEach(i -> i.validProperty().addListener((o, was, now) -> showBar())));
        // Under the page, while something waits: what, and the two ways out.
        Fx.wrap(this.pendingText);
        this.pendingText.setMinWidth(0);
        this.pendingText.setMinHeight(Region.USE_PREF_SIZE);
        this.pendingText.setGraphic(Fx.icon(Icons.INFO));
        HBox.setHgrow(this.pendingText, Priority.ALWAYS);
        this.applyHeld.setOnAction(e -> apply());
        this.applyHeld.setId("settings-apply");
        Fx.primary(this.applyHeld);
        Button discard = Fx.button(this.gui.t("nj.settings.discard"), Icons.CLOSE, this::discard);
        discard.getStyleClass().add("flat");
        discard.setId("settings-discard");
        Fx.whole(discard);
        Fx.whole(this.applyHeld);
        this.bar.getChildren().setAll(this.pendingText, discard, this.applyHeld);
        this.bar.setAlignment(Pos.CENTER_LEFT);
        this.bar.getStyleClass().add("settings-bar");
        this.bar.managedProperty().bind(this.bar.visibleProperty());
        VBox right = new VBox(8, this.pages.page(), this.bar);
        VBox.setVgrow(this.pages.page(), Priority.ALWAYS);
        HBox body = new HBox(16, this.pages.side(), right);
        HBox.setHgrow(right, Priority.ALWAYS);
        this.gui.subscriptions().onChange(this.gui.model().preferencesProperty(), (was, now) -> outside(now));
        load(this.gui.model().preferencesProperty().get());
        return Fx.screen(body);
    }

    // The twelve pages, each option in the section it belongs to.
    private List<SettingsPages.Page> pages() {
        return List.of(
                page("general", "IDS_PW_GENERAL", "nj.settings.page.general", Icons.OPTIONS, general()),
                page("connection", "nj.settings.connection", "nj.settings.page.connection", Icons.LINK, connection()),
                page("bandwidth", "nj.settings.bandwidth", "nj.settings.page.bandwidth", Icons.SPEED, bandwidth()),
                page("servers", "IDS_PW_SERVER", "nj.settings.page.servers", Icons.SERVER, servers()),
                page("proxy", "IDS_PW_PROXY", "nj.settings.page.proxy", Icons.SWAP, proxy()),
                page("folders", "IDS_PW_DIR", "nj.settings.page.folders", Icons.FOLDER_OPEN, folders()),
                page("ipfilter", "IDS_IPFILTER", "nj.settings.page.ipfilter", Icons.BLOCK, ipFilter()),
                page("messages", "IDS_EM_MESSAGES", "nj.settings.page.messages", Icons.MESSAGE, messages()),
                page("scheduler", "nj.scheduler.title", "nj.settings.page.scheduler", Icons.CLOCK, schedulerPage()),
                page("events", "nj.settings.events", "nj.settings.page.events", Icons.PLAY, events()),
                page("web", "IDS_PW_WS", "nj.webui.note", Icons.NETWORK, webInterface()),
                page("advanced", "IDS_PW_TWEAK", "nj.settings.page.advanced", Icons.TOOLS, advanced()));
    }

    private SettingsPages.Page page(
            String id, String title, String about, Icons icon, List<SettingsPages.Section> sections) {
        return new SettingsPages.Page(id, this.gui.t(title), this.gui.t(about), icon, sections);
    }

    private SettingsPages.Section section(String title, SettingRow... rows) {
        List<SettingRow> kept = Arrays.stream(rows).filter(Objects::nonNull).toList();
        this.rows.addAll(kept);
        return new SettingsPages.Section(title.isEmpty() ? "" : this.gui.t(title), kept);
    }

    private List<SettingsPages.Section> general() {
        return List.of(
                section(
                        "",
                        engine(
                                        "IDS_PW_NICK",
                                        this.nick,
                                        Preferences::nick,
                                        Preferences.Builder::nick,
                                        this.nickInput::value,
                                        this.nick::setText,
                                        v -> v)
                                .checkedBy(this.nickInput)),
                section("IDS_PW_DISPLAY", languageRow(), themeRow()),
                section(
                        "IDS_STARTUP",
                        autostartRow(),
                        look(
                                "IDS_PREF_STARTMIN",
                                this.startMinimized,
                                () -> this.gui.settings().startMinimized(),
                                false,
                                v -> this.gui.settings().setStartMinimized(v)),
                        NotificationArea.isSupported()
                                ? look(
                                        "nj.settings.tray",
                                        this.tray,
                                        () -> this.gui.settings().minimizeToTray(),
                                        true,
                                        v -> this.gui.settings().setMinimizeToTray(v))
                                : null),
                section(
                        "nj.update.title",
                        engine(
                                        "nj.update.mode",
                                        this.updateMode,
                                        Preferences::updateMode,
                                        Preferences.Builder::updateMode,
                                        this.updateMode::getValue,
                                        this.updateMode::setValue,
                                        m -> this.gui.t("nj.update.mode." + key(m)))
                                .describedAs(updateNote()),
                        engine(
                                "nj.update.channel",
                                this.updateChannel,
                                Preferences::updateChannel,
                                Preferences.Builder::updateChannel,
                                this.updateChannel::getValue,
                                this.updateChannel::setValue,
                                c -> this.gui.t("nj.update.channel." + key(c))),
                        engine(
                                "nj.update.installAt",
                                this.updateInstallTime,
                                Preferences::updateInstallTime,
                                Preferences.Builder::updateInstallTime,
                                this.updateInstallTime::getValue,
                                this.updateInstallTime::setValue,
                                this::installTimeName),
                        engine(
                                        "nj.update.nightHours",
                                        this.nightHours,
                                        Preferences::nightHours,
                                        Preferences.Builder::nightHours,
                                        this.nightHoursInput::value,
                                        v -> this.nightHours.setText(v.toString()),
                                        NightHours::toString)
                                .checkedBy(this.nightHoursInput)
                                .describedAs(this.gui.t("nj.update.nightHours.hint")),
                        checkUpdateRow()),
                section(
                        "IDS_PW_MISC",
                        look(
                                "IDS_PW_PROMPT",
                                this.confirmExit,
                                () -> this.gui.settings().confirmExit(),
                                true,
                                v -> this.gui.settings().setConfirmExit(v)),
                        look(
                                "IDS_PF_WATCHCB",
                                this.watchClipboard,
                                () -> this.gui.settings().watchClipboard(),
                                false,
                                v -> this.gui.settings().setWatchClipboard(v)),
                        this.links.isPresent() ? linksRow() : null),
                section(
                        "IDS_PW_EKDEV_OPTIONS",
                        toggle(
                                "nj.settings.sound",
                                this.sound,
                                Preferences::notificationSound,
                                (b, v) -> b.notificationSound(v)),
                        engine(
                                        "nj.settings.soundFile",
                                        soundRow(),
                                        Preferences::notificationSoundFile,
                                        Preferences.Builder::notificationSoundFile,
                                        this.soundFileInput::value,
                                        v -> this.soundFile.setText(
                                                v.map(Path::toString).orElse("")),
                                        v -> "")
                                .checkedBy(this.soundFileInput)
                                .wide()));
    }

    private List<SettingsPages.Section> connection() {
        return List.of(
                section(
                        "nj.network.ports",
                        held(
                                        "tcp",
                                        this.gui.t("IDS_PW_CLIENTPORT") + " (TCP)",
                                        this.tcpPort,
                                        Preferences::tcpPort,
                                        Preferences.Builder::tcpPort,
                                        this.tcpPortInput::value,
                                        v -> this.tcpPort.setText(String.valueOf(v)),
                                        String::valueOf)
                                .checkedBy(this.tcpPortInput),
                        held(
                                        "udp",
                                        "UDP",
                                        this.udpPort,
                                        Preferences::udpPort,
                                        Preferences.Builder::udpPort,
                                        this.udpPortInput::value,
                                        v -> this.udpPort.setText(String.valueOf(v)),
                                        String::valueOf)
                                .checkedBy(this.udpPortInput),
                        held(
                                "upnp",
                                switchTitle(this.upnp, "nj.settings.upnp"),
                                this.upnp,
                                Preferences::upnp,
                                Preferences.Builder::upnp,
                                this.upnp::isSelected,
                                this.upnp::setSelected,
                                this::yesNo),
                        new SettingRow(this.gui, this.gui.t("nj.network.testPorts"), this.portTest.node()).wide()),
                section(
                        "IDS_NETWORK",
                        toggle(
                                "nj.settings.ed2k",
                                this.ed2k,
                                Preferences::ed2kEnabled,
                                Preferences.Builder::ed2kEnabled),
                        toggle("nj.settings.kad", this.kad, Preferences::kadEnabled, Preferences.Builder::kadEnabled),
                        toggle(
                                "IDS_PW_AUTOCON",
                                this.autoConnect,
                                Preferences::autoConnect,
                                Preferences.Builder::autoConnect),
                        toggle("IDS_PW_RECON", this.reconnect, Preferences::reconnect, Preferences.Builder::reconnect),
                        engine(
                                "nj.settings.obfuscation",
                                this.obfuscation,
                                Preferences::obfuscation,
                                Preferences.Builder::obfuscation,
                                this.obfuscation::getValue,
                                this.obfuscation::setValue,
                                this::obfuscationName),
                        held(
                                        "bind",
                                        this.gui.t("nj.settings.bindInterface"),
                                        this.bindInterface,
                                        Preferences::bindInterface,
                                        Preferences.Builder::bindInterface,
                                        () -> Objects.requireNonNullElse(this.bindInterface.getValue(), ""),
                                        this::showInterface,
                                        this::interfaceName)
                                .describedAs(this.gui.t("nj.settings.bindInterface.hint"))),
                section(
                        "IDS_PW_CON_LIMITFRM",
                        number(
                                "IDS_PW_MAXC",
                                this.connections,
                                this.connectionsInput,
                                Preferences::maxConnections,
                                Preferences.Builder::maxConnections),
                        number(
                                "IDS_MAXCON5SECLABEL",
                                this.newConnections,
                                this.newConnectionsInput,
                                Preferences::newConnectionsPerFiveSeconds,
                                Preferences.Builder::newConnectionsPerFiveSeconds),
                        number(
                                "IDS_PW_MAXSOURCES",
                                this.sources,
                                this.sourcesInput,
                                Preferences::maxSourcesPerFile,
                                Preferences.Builder::maxSourcesPerFile),
                        number(
                                "IDS_QUEUESIZE",
                                this.queueSize,
                                this.queueSizeInput,
                                Preferences::uploadQueueSize,
                                Preferences.Builder::uploadQueueSize)));
    }

    private List<SettingsPages.Section> bandwidth() {
        return List.of(
                section(
                        "IDS_PW_CON_CAPFRM",
                        rate(
                                "nj.settings.downloadCapacity",
                                this.downloadCapacity,
                                Preferences::downloadCapacityKiBps,
                                Preferences.Builder::downloadCapacityKiBps),
                        rate(
                                "nj.settings.uploadCapacity",
                                this.uploadCapacity,
                                Preferences::uploadCapacityKiBps,
                                Preferences.Builder::uploadCapacityKiBps)),
                section(
                        "IDS_PW_CON_LIMITFRM",
                        rate(
                                "nj.settings.download",
                                this.download,
                                Preferences::maxDownloadKiBps,
                                Preferences.Builder::maxDownloadKiBps),
                        rate(
                                "nj.settings.upload",
                                this.upload,
                                Preferences::maxUploadKiBps,
                                Preferences.Builder::maxUploadKiBps)));
    }

    private List<SettingsPages.Section> servers() {
        Label retries = new Label(this.gui.t("IDS_PW_RETRIES"));
        HBox dead = new HBox(8, this.deadRetries, retries);
        dead.setAlignment(Pos.CENTER_LEFT);
        Button addresses = Fx.button(this.gui.t("IDS_EDITLIST"), Icons.EDIT, this::editServerListAddresses);
        HBox update = new HBox(12, addresses, this.updateAtStart);
        update.setAlignment(Pos.CENTER_LEFT);
        return List.of(section(
                "",
                engine(
                                "IDS_PW_RDEAD",
                                dead,
                                Preferences::deadServerRetries,
                                Preferences.Builder::deadServerRetries,
                                this.deadRetriesInput::value,
                                v -> this.deadRetries.setText(String.valueOf(v)),
                                String::valueOf)
                        .checkedBy(this.deadRetriesInput),
                toggle(
                        "IDS_PW_USC",
                        this.fromServer,
                        Preferences::addServersFromServer,
                        Preferences.Builder::addServersFromServer),
                toggle(
                        "IDS_PW_UCC",
                        this.fromClients,
                        Preferences::addServersFromClients,
                        Preferences.Builder::addServersFromClients),
                toggle(
                        "IDS_PW_AUTOCONNECTSTATICONLY",
                        this.staticOnly,
                        Preferences::staticServersOnly,
                        Preferences.Builder::staticServersOnly),
                toggle(
                        "IDS_MANUALSERVERHIGHPRIO",
                        this.manualHigh,
                        Preferences::manualServersHighPriority,
                        Preferences.Builder::manualServersHighPriority),
                engine(
                        "IDS_PW_USS",
                        update,
                        Preferences::updateServersAtStart,
                        Preferences.Builder::updateServersAtStart,
                        this.updateAtStart::isSelected,
                        this.updateAtStart::setSelected,
                        this::yesNo)));
    }

    // The proxy is one preference: each of its rows sets all of it.
    private List<SettingsPages.Section> proxy() {
        Supplier<ProxySettings> read = () -> new ProxySettings(
                this.proxyType.getValue(),
                this.proxyHostInput.value(),
                this.proxyPortInput.value(),
                this.proxyUserInput.value(),
                this.proxyPassword.getText());
        Consumer<ProxySettings> show = p -> {
            this.proxyType.setValue(p.type());
            this.proxyHost.setText(p.host());
            this.proxyPort.setText(String.valueOf(p.port()));
            this.proxyUser.setText(p.user());
            this.proxyPassword.setText(p.password());
        };
        HBox where = new HBox(8, this.proxyHost, this.proxyPort);
        HBox who = new HBox(8, this.proxyUser, this.proxyPassword);
        SettingRow type = held(
                "proxy",
                this.gui.t("nj.proxy.type"),
                this.proxyType,
                Preferences::proxy,
                Preferences.Builder::proxy,
                read,
                show,
                p -> this.gui.t("nj.proxy." + key(p.type())));
        SettingRow host = held(
                        "proxy",
                        this.gui.t("nj.proxy.host"),
                        where,
                        Preferences::proxy,
                        Preferences.Builder::proxy,
                        read,
                        show,
                        p -> "")
                .checkedBy(this.proxyHostInput)
                .defaultIs(() -> false, () -> {}, () -> "");
        SettingRow user = held(
                        "proxy",
                        this.gui.t("nj.proxy.user"),
                        who,
                        Preferences::proxy,
                        Preferences.Builder::proxy,
                        read,
                        show,
                        p -> "")
                .checkedBy(this.proxyUserInput)
                .defaultIs(() -> false, () -> {}, () -> "");
        return List.of(section("", type, host, user));
    }

    private List<SettingsPages.Section> folders() {
        return List.of(
                section(
                        "",
                        folder(
                                "IDS_PW_INCOMING",
                                this.incoming,
                                this.incomingInput,
                                Preferences::incomingDir,
                                (b, v) -> b.incomingDir(v)),
                        folder("IDS_PW_TEMP", this.temp, this.tempInput, Preferences::tempDir, (b, v) -> b.tempDir(v))
                                .describedAs(this.gui.t("nj.settings.nextStart")),
                        number(
                                "IDS_MINFREEDISKSPACE",
                                this.minFreeSpace,
                                this.minFreeSpaceInput,
                                Preferences::minFreeSpaceMiB,
                                Preferences.Builder::minFreeSpaceMiB)),
                section(
                        "IDS_TW_DOWNLOADS",
                        toggle(
                                "IDS_ADDNEWFILESPAUSED",
                                this.addPaused,
                                Preferences::addDownloadsPaused,
                                Preferences.Builder::addDownloadsPaused),
                        nextRow(),
                        toggle(
                                "IDS_AUTOCLEANUPFN",
                                this.cleanUpNewNames,
                                Preferences::cleanUpNewNames,
                                Preferences.Builder::cleanUpNewNames),
                        engine(
                                        "IDS_FILTERFILENAMEWORD",
                                        this.nameCleanups,
                                        Preferences::nameCleanups,
                                        Preferences.Builder::nameCleanups,
                                        () -> this.nameCleanups.getText().strip(),
                                        this.nameCleanups::setText,
                                        v -> "")
                                .wide(),
                        toggle(
                                "IDS_PW_REMEMBERDOWNLOADED",
                                this.rememberDownloaded,
                                Preferences::rememberDownloadedFiles,
                                Preferences.Builder::rememberDownloadedFiles),
                        toggle(
                                "IDS_PW_REMEMBERCANCELLED",
                                this.rememberCancelled,
                                Preferences::rememberCancelledFiles,
                                Preferences.Builder::rememberCancelledFiles)),
                section(
                        "IDS_DL_PREVIEW",
                        toggle(
                                "IDS_DOWNLOADMOVIECHUNKS",
                                this.previewFirst,
                                Preferences::previewFirst,
                                Preferences.Builder::previewFirst),
                        engine(
                                        "IDS_PW_VIDEOPLAYER",
                                        videoPlayerRow(),
                                        p -> List.of(p.videoPlayer(), p.videoPlayerArgs()),
                                        (b, v) -> b.videoPlayer(v.get(0)).videoPlayerArgs(v.get(1)),
                                        () -> List.of(this.videoPlayer.getText(), this.videoPlayerArgs.getText()),
                                        v -> {
                                            this.videoPlayer.setText(v.get(0));
                                            this.videoPlayerArgs.setText(v.get(1));
                                        },
                                        v -> "")
                                .wide()),
                section(
                        "IDS_SHAREDFILES",
                        engine(
                                "IDS_PW_SHARE",
                                this.visibility,
                                Preferences::sharedFilesVisibility,
                                Preferences.Builder::sharedFilesVisibility,
                                this.visibility::getValue,
                                this.visibility::setValue,
                                this::visibilityName)));
    }

    private List<SettingsPages.Section> ipFilter() {
        return List.of(
                section(
                        "",
                        engine(
                                        "nj.settings.ipFilterLists",
                                        ipFilterRow(),
                                        Preferences::ipFilterLists,
                                        Preferences.Builder::ipFilterLists,
                                        this.ipFilterListsInput::value,
                                        this.ipFilterLists::setText,
                                        v -> "")
                                .checkedBy(this.ipFilterListsInput)
                                .describedAs(this.gui.t("nj.settings.ipFilterListsHint"))
                                .wide(),
                        number(
                                "nj.settings.ipFilterUpdateDays",
                                this.ipFilterDays,
                                this.ipFilterDaysInput,
                                Preferences::ipFilterUpdateDays,
                                Preferences.Builder::ipFilterUpdateDays),
                        number(
                                "IDS_FILTERLEVEL",
                                this.filterLevel,
                                this.filterLevelInput,
                                Preferences::ipFilterLevel,
                                Preferences.Builder::ipFilterLevel),
                        toggle(
                                "IDS_PW_FILTER",
                                this.filterLan,
                                Preferences::filterLanAddresses,
                                Preferences.Builder::filterLanAddresses),
                        engine(
                                        "nj.settings.whitelist",
                                        this.whitelist,
                                        Preferences::ipFilterWhitelist,
                                        Preferences.Builder::ipFilterWhitelist,
                                        this.whitelistInput::value,
                                        this.whitelist::setText,
                                        v -> "")
                                .checkedBy(this.whitelistInput)
                                .describedAs(this.gui.t("nj.settings.whitelistHint"))
                                .wide()),
                section(
                        "IDS_SPAM",
                        toggle(
                                "IDS_SEARCHSPAMFILTER",
                                this.searchSpamFilter,
                                Preferences::searchSpamFilter,
                                Preferences.Builder::searchSpamFilter)));
    }

    private List<SettingsPages.Section> messages() {
        return List.of(section(
                "",
                engine(
                                "IDS_FILTERLABEL",
                                this.messageFilter,
                                Preferences::messageFilter,
                                Preferences.Builder::messageFilter,
                                () -> this.messageFilter.getText().strip(),
                                this.messageFilter::setText,
                                v -> "")
                        .wide(),
                toggle(
                        "IDS_MSGONLYFRIENDS",
                        this.friendsOnly,
                        Preferences::messagesFromFriendsOnly,
                        Preferences.Builder::messagesFromFriendsOnly),
                toggle(
                        "IDS_ADVSPAMFILTER",
                        this.spamFilter,
                        Preferences::advancedSpamFilter,
                        Preferences.Builder::advancedSpamFilter)));
    }

    private List<SettingsPages.Section> schedulerPage() {
        return List.of(section("", new SettingRow(this.gui, "", scheduler()).wide()));
    }

    private List<SettingsPages.Section> events() {
        List<SettingRow> rows = new ArrayList<>();
        for (EventCommands.Event event : EventCommands.Event.values()) {
            ToggleSwitch on = this.eventSwitches.get(event);
            Input<String> line = this.eventInputs.get(event);
            HBox control = new HBox(12, on, line.field());
            control.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(line.field(), Priority.ALWAYS);
            rows.add(engine(
                            this.gui.t("nj.events." + event.id()),
                            control,
                            p -> p.eventCommands().get(event),
                            (b, command) -> b.eventEnabled(event, command.enabled())
                                    .eventCommandLine(event, command.commandLine()),
                            () -> new EventCommands.Command(on.isSelected(), line.value()),
                            command -> {
                                on.setSelected(command.enabled());
                                line.field().setText(command.commandLine());
                            },
                            command -> "")
                    .checkedBy(line)
                    .wide());
        }
        return List.of(section("", rows.toArray(SettingRow[]::new)));
    }

    private List<SettingsPages.Section> webInterface() {
        HBox tokens = new HBox(
                8,
                Fx.button(this.gui.t("nj.webui.copyToken"), Icons.COPY, this::copyApiToken),
                Fx.button(this.gui.t("nj.webui.newToken"), Icons.REFRESH, this::newApiToken));
        this.webUiHosts.setPromptText("nas.local, njmule.example.com");
        this.webUiProxies.setPromptText("172.17.0.1, 10.0.0.0/8");
        HBox passwords = new HBox(8, this.webUiPassword, this.webUiAgain);
        SettingRow password = new SettingRow(this.gui, this.gui.t("nj.webui.password"), passwords)
                .checkedBy(this.webUiPasswordInput, this.webUiAgainInput);
        watch(passwords, this::savePassword);
        this.commits.add(this::savePassword);
        return List.of(section(
                "",
                web(
                        switchTitle(this.webUi, "nj.webui.enabled"),
                        this.webUi,
                        WebUiSettings::enabled,
                        (w, v) -> new WebUiSettings(
                                v, w.port(), w.lan(), w.passwordHash(), w.api(), w.hosts(), w.proxies()),
                        this.webUi::isSelected,
                        this.webUi::setSelected,
                        this::yesNo),
                web(
                                this.gui.t("nj.webui.port"),
                                this.webUiPort,
                                WebUiSettings::port,
                                (w, v) -> new WebUiSettings(
                                        w.enabled(), v, w.lan(), w.passwordHash(), w.api(), w.hosts(), w.proxies()),
                                this.webUiPortInput::value,
                                v -> this.webUiPort.setText(String.valueOf(v)),
                                String::valueOf)
                        .checkedBy(this.webUiPortInput),
                web(
                        switchTitle(this.webUiLan, "nj.webui.lan"),
                        this.webUiLan,
                        WebUiSettings::lan,
                        (w, v) -> new WebUiSettings(
                                w.enabled(), w.port(), v, w.passwordHash(), w.api(), w.hosts(), w.proxies()),
                        this.webUiLan::isSelected,
                        this.webUiLan::setSelected,
                        this::yesNo),
                web(
                                this.gui.t("nj.webui.hosts"),
                                this.webUiHosts,
                                WebUiSettings::hosts,
                                (w, v) -> new WebUiSettings(
                                        w.enabled(), w.port(), w.lan(), w.passwordHash(), w.api(), v, w.proxies()),
                                this.webUiHostsInput::value,
                                v -> this.webUiHosts.setText(String.join(", ", v)),
                                v -> "")
                        .checkedBy(this.webUiHostsInput)
                        .describedAs(this.gui.t("nj.webui.hostsHint"))
                        .wide(),
                web(
                                this.gui.t("nj.webui.proxies"),
                                this.webUiProxies,
                                WebUiSettings::proxies,
                                (w, v) -> new WebUiSettings(
                                        w.enabled(), w.port(), w.lan(), w.passwordHash(), w.api(), w.hosts(), v),
                                this.webUiProxiesInput::value,
                                v -> this.webUiProxies.setText(String.join(", ", v)),
                                v -> "")
                        .checkedBy(this.webUiProxiesInput)
                        .describedAs(this.gui.t("nj.webui.proxiesHint"))
                        .wide(),
                password,
                web(
                        switchTitle(this.webUiApi, "nj.webui.api"),
                        this.webUiApi,
                        WebUiSettings::api,
                        (w, v) -> new WebUiSettings(
                                w.enabled(), w.port(), w.lan(), w.passwordHash(), v, w.hosts(), w.proxies()),
                        this.webUiApi::isSelected,
                        this.webUiApi::setSelected,
                        this::yesNo),
                new SettingRow(this.gui, "", tokens)));
    }

    private List<SettingsPages.Section> advanced() {
        return List.of(
                section(
                        "IDS_SECURITY",
                        toggle(
                                "nj.settings.secureIdent",
                                this.secureIdent,
                                Preferences::secureIdent,
                                Preferences.Builder::secureIdent),
                        toggle(
                                "nj.settings.credits",
                                this.credits,
                                Preferences::creditSystem,
                                Preferences.Builder::creditSystem)),
                section(
                        "IDS_PW_MISC",
                        toggle(
                                "IDS_STORESEARCHES",
                                this.storeSearches,
                                Preferences::storeSearches,
                                Preferences.Builder::storeSearches),
                        number(
                                "nj.settings.backups",
                                this.backups,
                                this.backupsInput,
                                Preferences::backupCopies,
                                Preferences.Builder::backupCopies)));
    }

    // ------------------------------------------------------------------ the kinds of option

    // An engine preference saved as soon as it is set.
    private <T> SettingRow engine(
            String title,
            Node control,
            Function<Preferences, T> get,
            BiConsumer<Preferences.Builder, T> set,
            Supplier<T> read,
            Consumer<T> show,
            Function<T, String> name) {
        return option(Optional.empty(), text(title), control, get, set, read, show, name);
    }

    // An engine preference that waits for Apply and reconnect, by the key of what it sets.
    private <T> SettingRow held(
            String key,
            String title,
            Node control,
            Function<Preferences, T> get,
            BiConsumer<Preferences.Builder, T> set,
            Supplier<T> read,
            Consumer<T> show,
            Function<T, String> name) {
        return option(Optional.of(key), title, control, get, set, read, show, name);
    }

    private <T> SettingRow option(
            Optional<String> key,
            String title,
            Node control,
            Function<Preferences, T> get,
            BiConsumer<Preferences.Builder, T> set,
            Supplier<T> read,
            Consumer<T> show,
            Function<T, String> name) {
        SettingRow row = new SettingRow(this.gui, title, control);
        Runnable commit = () -> {
            if (this.loading) {
                return;
            }
            T value;
            try {
                value = read.get();
            } catch (IllegalArgumentException e) {
                return;
            }
            boolean differs = !Objects.equals(get.apply(this.loaded), value);
            if (key.isPresent()) {
                if (differs) {
                    this.held.put(key.get(), b -> set.accept(b, value));
                } else {
                    this.held.remove(key.get());
                }
                showBar();
            } else if (differs) {
                save(b -> set.accept(b, value));
            }
        };
        watch(control, commit);
        this.commits.add(commit);
        this.shows.add(new Shown(row, key, p -> show.accept(get.apply(p))));
        return row.defaultIs(
                () -> !Objects.equals(get.apply(this.loaded), get.apply(DEFAULTS)),
                () -> {
                    quietly(() -> show.accept(get.apply(DEFAULTS)));
                    commit.run();
                },
                () -> name.apply(get.apply(DEFAULTS)));
    }

    private SettingRow toggle(
            String title,
            ToggleSwitch box,
            Function<Preferences, Boolean> get,
            BiConsumer<Preferences.Builder, Boolean> set) {
        return option(
                Optional.empty(),
                switchTitle(box, title),
                box,
                get,
                set,
                box::isSelected,
                box::setSelected,
                this::yesNo);
    }

    private SettingRow number(
            String title,
            TextField field,
            Input<Integer> input,
            Function<Preferences, Integer> get,
            BiConsumer<Preferences.Builder, Integer> set) {
        return engine(title, field, get, set, input::value, v -> field.setText(String.valueOf(v)), String::valueOf)
                .checkedBy(input);
    }

    private SettingRow rate(
            String title,
            RateField<Integer> field,
            Function<Preferences, Integer> get,
            BiConsumer<Preferences.Builder, Integer> set) {
        return engine(title, field.node(), get, set, field.input()::value, field::show, this::rateName)
                .checkedBy(field.input());
    }

    private SettingRow folder(
            String title,
            TextField field,
            Input<Optional<Path>> input,
            Function<Preferences, Optional<Path>> get,
            BiConsumer<Preferences.Builder, Optional<Path>> set) {
        return engine(
                        title,
                        directory(field),
                        get,
                        set,
                        input::value,
                        v -> field.setText(v.map(Path::toString).orElse("")),
                        v -> this.gui.t("nj.settings.defaultDir"))
                .checkedBy(input)
                .wide();
    }

    // A setting of the interface, kept in its own file.
    private SettingRow look(
            String title, ToggleSwitch box, Supplier<Boolean> saved, boolean byDefault, Consumer<Boolean> store) {
        SettingRow row = new SettingRow(this.gui, switchTitle(box, title), box);
        Runnable commit = () -> {
            if (!this.loading && box.isSelected() != saved.get()) {
                store.accept(box.isSelected());
                saveLook();
            }
        };
        watch(box, commit);
        this.commits.add(commit);
        this.shows.add(new Shown(row, Optional.empty(), p -> box.setSelected(saved.get())));
        return row.defaultIs(
                () -> saved.get() != byDefault,
                () -> {
                    quietly(() -> box.setSelected(byDefault));
                    commit.run();
                },
                () -> yesNo(byDefault));
    }

    // A setting of the web interface, kept in its own file and followed from the next start.
    private <T> SettingRow web(
            String title,
            Node control,
            Function<WebUiSettings, T> get,
            BiFunction<WebUiSettings, T, WebUiSettings> with,
            Supplier<T> read,
            Consumer<T> show,
            Function<T, String> name) {
        SettingRow row = new SettingRow(this.gui, title, control);
        Runnable commit = () -> {
            if (this.loading) {
                return;
            }
            T value;
            try {
                value = read.get();
            } catch (IllegalArgumentException e) {
                return;
            }
            WebUiSettings now = WebUiSettings.load(this.gui.directories().config());
            if (Objects.equals(get.apply(now), value)) {
                return;
            }
            WebUiSettings next = with.apply(now, value);
            // Turned on before it has a password: it stays on screen, and is saved once a password is.
            if (next.enabled() && next.passwordHash().isEmpty()) {
                this.gui.warn().accept(this.gui.f("nj.settings.invalid", this.gui.t("nj.webui.needsPassword")));
                return;
            }
            saveWeb(next);
        };
        watch(control, commit);
        this.commits.add(commit);
        this.webCommits.add(commit);
        this.shows.add(new Shown(row, Optional.empty(), p -> show.accept(get.apply(this.web))));
        return row.defaultIs(
                () -> !Objects.equals(get.apply(this.web), get.apply(WebUiSettings.DEFAULTS)),
                () -> {
                    quietly(() -> show.accept(get.apply(WebUiSettings.DEFAULTS)));
                    commit.run();
                },
                () -> name.apply(get.apply(WebUiSettings.DEFAULTS)));
    }

    // The language takes effect at a restart: it waits for Apply and restart.
    private SettingRow languageRow() {
        SettingRow row = new SettingRow(this.gui, this.gui.t("IDS_PW_LANG"), this.language);
        Runnable commit = () -> {
            if (!this.loading) {
                this.languageHeld = !Objects.equals(
                        this.language.getValue(), this.gui.settings().language());
                showBar();
            }
        };
        watch(this.language, commit);
        this.commits.add(commit);
        this.shows.add(new Shown(
                row,
                Optional.of("language"),
                p -> this.language.setValue(this.gui.settings().language())));
        return row.defaultIs(
                () -> this.gui.settings().language().isPresent(),
                () -> {
                    quietly(() -> this.language.setValue(Optional.empty()));
                    commit.run();
                },
                () -> languageName(Optional.empty()));
    }

    private SettingRow themeRow() {
        HBox both = new HBox(8, this.theme, this.mode);
        SettingRow row = new SettingRow(this.gui, this.gui.t("nj.settings.theme"), both);
        Runnable commit = () -> {
            if (this.loading || this.theme.getValue() == null || this.mode.getValue() == null) {
                return;
            }
            if (this.theme.getValue() != this.gui.themes().family()
                    || this.mode.getValue() != this.gui.themes().mode()) {
                this.gui.themes().set(this.theme.getValue(), this.mode.getValue());
                this.gui.settings().setTheme(this.theme.getValue());
                this.gui.settings().setMode(this.mode.getValue());
                saveLook();
            }
        };
        watch(both, commit);
        this.commits.add(commit);
        this.shows.add(new Shown(row, Optional.empty(), p -> {
            this.theme.setValue(this.gui.themes().family());
            this.mode.setValue(this.gui.themes().mode());
        }));
        return row.defaultIs(
                () -> this.gui.themes().family() != Themes.Family.NORD
                        || this.gui.themes().mode() != GuiSettings.Mode.SYSTEM,
                () -> {
                    quietly(() -> {
                        this.theme.setValue(Themes.Family.NORD);
                        this.mode.setValue(GuiSettings.Mode.SYSTEM);
                    });
                    commit.run();
                },
                () -> themeName(Themes.Family.NORD) + ", " + modeName(GuiSettings.Mode.SYSTEM));
    }

    // Start the next download: none, or one of any category, of the same first, or of the same only.
    private SettingRow nextRow() {
        HBox control = new HBox(12, this.nextCategory, this.startNext);
        control.setAlignment(Pos.CENTER_LEFT);
        return engine(
                switchTitle(this.startNext, "IDS_STARTNEXTFILE"),
                control,
                Preferences::nextDownload,
                Preferences.Builder::nextDownload,
                () -> this.startNext.isSelected() ? this.nextCategory.getValue() : NextDownload.NONE,
                v -> {
                    this.startNext.setSelected(v != NextDownload.NONE);
                    this.nextCategory.setValue(v == NextDownload.NONE ? NextDownload.ANY : v);
                },
                v -> "");
    }

    // What the check sends; where a package manager owns the files, that njMule only downloads.
    private String updateNote() {
        String note = this.gui.t("nj.update.note");
        return this.gui.model().updateProperty().get().installer() == UpdateStatus.Installer.NONE
                ? note + " " + this.gui.t("nj.update.cannotInstall")
                : note;
    }

    private String installTimeName(UpdateInstallTime time) {
        return this.gui.t("nj.update.installAt." + key(time));
    }

    // Looks now, whatever the mode: the answer opens in its own window.
    private SettingRow checkUpdateRow() {
        Button check = Fx.button(
                this.gui.t("nj.import.check"),
                Icons.REFRESH,
                () -> UpdateDialog.checkNow(this.gui, this.view.getScene().getWindow()));
        return new SettingRow(this.gui, this.gui.t("IDS_CHECK4UPDATE"), check);
    }

    // Only the installed program can start at login; elsewhere the switch says why it is off. A disabled control shows
    // no tooltip, so its row carries it.
    private SettingRow autostartRow() {
        HBox box = new HBox(this.autostart);
        box.setAlignment(Pos.CENTER_RIGHT);
        if (this.gui.autostart().isEmpty()) {
            this.autostart.setDisable(true);
            Tooltip why = new Tooltip(this.gui.t("nj.settings.autostartUnavailable"));
            this.autostart.setTooltip(why);
            Tooltip.install(box, why);
        }
        SettingRow row = new SettingRow(this.gui, switchTitle(this.autostart, "nj.settings.autostart"), box);
        watch(box, () -> {
            if (!this.loading) {
                applyAutostart();
            }
        });
        return row;
    }

    private SettingRow linksRow() {
        SettingRow row = new SettingRow(this.gui, switchTitle(this.ed2kLinks, "nj.settings.ed2kLinks"), this.ed2kLinks);
        watch(this.ed2kLinks, () -> {
            if (!this.loading) {
                applyLinks();
            }
        });
        return row;
    }

    // A key of the translations, or a text already translated.
    private String text(String keyOrText) {
        return keyOrText.startsWith("nj.") || keyOrText.startsWith("IDS_") ? this.gui.t(keyOrText) : keyOrText;
    }

    // The name of a switch is its row's; the switch keeps it for screen readers.
    private String switchTitle(ToggleSwitch box, String key) {
        String title = text(key);
        box.setText(null);
        box.setAccessibleText(title);
        return title;
    }

    // Applies when a control is set: a switch or a list at once, a field when left or on Enter.
    private void watch(Node node, Runnable commit) {
        switch (node) {
            case ButtonBase b when !(b instanceof CheckBox) -> {
                // Buttons act; they set nothing.
            }
            case ToggleSwitch box -> box.selectedProperty().addListener((o, was, now) -> commit.run());
            case CheckBox box -> box.selectedProperty().addListener((o, was, now) -> commit.run());
            case ComboBox<?> box -> box.valueProperty().addListener((o, was, now) -> commit.run());
            case TextInputControl field -> {
                field.focusedProperty().addListener((o, was, now) -> {
                    if (!now) {
                        commit.run();
                    }
                });
                if (field instanceof TextField) {
                    field.addEventHandler(ActionEvent.ACTION, e -> commit.run());
                }
            }
            case Control c -> {
                // A label or a list sets nothing here.
            }
            case Parent parent -> parent.getChildrenUnmodifiable().forEach(child -> watch(child, commit));
            default -> {
                // Nothing to set.
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private void save(Consumer<Preferences.Builder> edit) {
        Preferences.Builder local = this.loaded.toBuilder();
        edit.accept(local);
        this.loaded = local.build();
        refresh();
        // Only this option: a limit the tray or the scheduler set meanwhile stays.
        this.gui
                .model()
                .updatePreferences(
                        p -> {
                            Preferences.Builder b = p.toBuilder();
                            edit.accept(b);
                            return b.build();
                        },
                        p -> {
                            this.loaded = p;
                            refresh();
                        });
    }

    private void saveLook() {
        try {
            this.gui.settings().save();
        } catch (IOException e) {
            this.gui.warn().accept(e.getMessage());
        }
        refresh();
    }

    private void saveWeb(WebUiSettings next) {
        try {
            next.save(this.gui.directories().config());
            this.web = next;
        } catch (IOException e) {
            this.gui.warn().accept(e.getMessage());
        }
        refresh();
    }

    // A new password replaces the stored hash once both fields agree; they empty then.
    private void savePassword() {
        if (this.loading
                || this.webUiPassword.getText().isEmpty()
                || !this.webUiPasswordInput.isValid()
                || !this.webUiAgainInput.isValid()) {
            return;
        }
        char[] password = this.webUiPassword.getText().toCharArray();
        try {
            if (password.length < Passwords.MIN_LENGTH) {
                return;
            }
            WebUiSettings now = WebUiSettings.load(this.gui.directories().config());
            saveWeb(new WebUiSettings(
                    now.enabled(),
                    now.port(),
                    now.lan(),
                    Passwords.hash(password, new SecureRandom()),
                    now.api(),
                    now.hosts(),
                    now.proxies()));
        } finally {
            Passwords.wipe(password);
        }
        quietly(() -> {
            this.webUiPassword.clear();
            this.webUiAgain.clear();
        });
        this.gui.inform().accept(this.gui.t("nj.settings.saved"));
        this.webCommits.forEach(Runnable::run);
    }

    /** Sets every option as the controls give it, then applies what waits; a wrong value is shown instead. */
    void apply() {
        this.commits.forEach(Runnable::run);
        Optional<SettingRow> wrong = wrongRow();
        if (wrong.isPresent()) {
            reveal(wrong.get());
            wrong.get().input().ifPresent(i -> {
                try {
                    i.value();
                } catch (IllegalArgumentException e) {
                    this.gui.warn().accept(this.gui.f("nj.settings.invalid", e.getMessage()));
                }
            });
            return;
        }
        if (!this.held.isEmpty()) {
            List<Consumer<Preferences.Builder>> edits = List.copyOf(this.held.values());
            this.held.clear();
            save(b -> edits.forEach(e -> e.accept(b)));
            this.gui.inform().accept(this.gui.t("nj.settings.saved"));
        }
        if (this.languageHeld) {
            this.languageHeld = false;
            this.gui.settings().setLanguage(this.language.getValue());
            saveLook();
            this.gui.relaunch().run();
        }
        showBar();
    }

    // Back to what is saved, for the options that wait.
    void discard() {
        load(this.loaded);
    }

    private Optional<SettingRow> wrongRow() {
        return this.rows.stream().filter(SettingRow::isWrong).findFirst();
    }

    // The page of the option, and the cursor in it.
    private void reveal(SettingRow row) {
        this.pages.reveal(row);
        row.control().requestFocus();
        row.input().ifPresent(i -> i.field().requestFocus());
    }

    // What waits, with the button that applies it: reconnecting for the network, restarting for the language.
    private void showBar() {
        if (this.pages == null) {
            return;
        }
        boolean network = !this.held.isEmpty();
        List<String> words = new ArrayList<>();
        if (network) {
            words.add(this.gui.t("nj.settings.pendingReconnect"));
        }
        if (this.languageHeld) {
            words.add(this.gui.t("nj.settings.pendingRestart"));
        }
        Optional<SettingRow> wrong = wrongRow();
        wrong.ifPresent(r -> words.add(this.gui.f("nj.settings.fixFirst", r.title())));
        this.pendingText.setText(String.join(" ", words));
        this.applyHeld.setText(
                this.gui.t(this.languageHeld ? "nj.settings.applyRestart" : "nj.settings.applyReconnect"));
        this.applyHeld.setDisable(wrong.isPresent());
        this.bar.setVisible(network || this.languageHeld);
    }

    private void refresh() {
        if (this.pages != null) {
            this.pages.refresh();
        }
        showBar();
    }

    private void quietly(Runnable change) {
        boolean was = this.loading;
        this.loading = true;
        try {
            change.run();
        } finally {
            this.loading = was;
        }
    }

    /** Shows {@code p} and the settings of the interface and of the web interface, dropping what waited. */
    void load(Preferences p) {
        this.loaded = p;
        this.web = WebUiSettings.load(this.gui.directories().config());
        quietly(() -> {
            this.interfaces = BindInterface.choices();
            this.bindInterface.getItems().setAll(interfaceChoices(p.bindInterface()));
            this.shows.forEach(s -> s.show().accept(p));
            this.webUiPassword.clear();
            this.webUiAgain.clear();
        });
        this.held.clear();
        this.languageHeld = false;
        loadLinks();
        loadAutostart();
        refresh();
    }

    // Preferences changed elsewhere, as by the tray: the options show them, except one being edited or waiting.
    private void outside(Preferences now) {
        if (now == null || now.equals(this.loaded)) {
            return;
        }
        this.loaded = now;
        Node focused = this.view == null || this.view.getScene() == null
                ? null
                : this.view.getScene().getFocusOwner();
        quietly(() -> {
            for (Shown s : this.shows) {
                boolean waiting = s.key()
                        .map(k -> this.held.containsKey(k) || k.equals("language"))
                        .orElse(false);
                if (!waiting && !holds(s.row().control(), focused)) {
                    s.show().accept(now);
                }
            }
        });
        refresh();
    }

    private static boolean holds(Node node, Node focused) {
        for (Node at = focused; at != null; at = at.getParent()) {
            if (node.equals(at)) {
                return true;
            }
        }
        return false;
    }

    private void showInterface(String chosen) {
        if (!this.bindInterface.getItems().contains(chosen)) {
            this.bindInterface.getItems().setAll(interfaceChoices(chosen));
        }
        this.bindInterface.setValue(chosen);
    }

    /** An option and how it shows a saved value; {@code key} names what it waits for, if it waits. */
    private record Shown(SettingRow row, Optional<String> key, Consumer<Preferences> show) {}

    // ------------------------------------------------------------------ the inputs

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
        this.nightHoursInput = Input.of(
                this.nightHours,
                this.gui.t("nj.update.nightHours"),
                this.gui.t("nj.update.nightHours.hint"),
                c -> Character.isDigit(c) || c == ':' || c == '-' || c == ' ',
                MAX_NIGHT_HOURS,
                NightHours::parse);
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
        this.whitelist.setPrefRowCount(3);
        this.whitelist.setPrefColumnCount(32);
        this.whitelist.setPromptText("1.2.3.4\n10.0.0.0/8");
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
        String listsHint = this.gui.i18n().embed(this.gui.t("nj.settings.ipFilterListsHint"));
        this.ipFilterListsInput = Input.of(
                this.ipFilterLists,
                this.gui.t("nj.settings.ipFilterLists"),
                listsHint,
                c -> c == '\n' || !Character.isISOControl(c),
                MAX_IP_FILTER_LISTS_TEXT,
                t -> {
                    if (Preferences.invalidListEntry(t).isPresent() || !Preferences.fitsListCount(t)) {
                        throw new IllegalArgumentException(t);
                    }
                    return t;
                });
        // The tooltip names the line to fix.
        this.ipFilterLists
                .textProperty()
                .addListener((o, was, now) -> this.ipFilterLists
                        .getTooltip()
                        .setText(Preferences.invalidListEntry(now)
                                .map(entry -> listsHint
                                        + "\n"
                                        + this.gui.i18n().embed(this.gui.f("nj.settings.ipFilterListInvalid", entry)))
                                .orElse(listsHint)));
        this.ipFilterDaysInput = inputs.number(
                this.ipFilterDays,
                this.gui.t("nj.settings.ipFilterUpdateDays"),
                0,
                Preferences.MAX_IP_FILTER_UPDATE_DAYS);
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
        this.webUiProxiesInput = Input.of(
                this.webUiProxies,
                this.gui.t("nj.webui.proxies"),
                this.gui.i18n().embed(this.gui.t("nj.webui.proxiesHint")),
                c -> c < 0x80 && !Character.isISOControl(c),
                MAX_WEBUI_HOSTS,
                WebUiSettings::proxies);
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
            ToggleSwitch on = new ToggleSwitch();
            on.setAccessibleText(name);
            this.eventSwitches.put(event, on);
            TextField line = new TextField();
            line.setPrefColumnCount(30);
            line.setPromptText(String.join(" ", event.placeholders()));
            line.setAccessibleText(name);
            this.eventInputs.put(event, inputs.commandLine(line, name, event.placeholders()));
        }
    }

    // ------------------------------------------------------------------ the rows with buttons

    // The IP filter lists from the web, joined into one filter; Update fetches the lists typed.
    private Node ipFilterRow() {
        this.ipFilterLists.setPrefRowCount(2);
        this.ipFilterLists.setPrefColumnCount(40);
        this.ipFilterLists.setPromptText("https://…/ipfilter.zip\nhttps://…/level1.p2p.gz");
        Fx.leftToRight(this.ipFilterLists);
        Button update = Fx.button(this.gui.t("IDS_SV_UPDATE"), Icons.REFRESH, this::updateIpFilter);
        update.disableProperty()
                .bind(this.ipFilterListsInput
                        .validProperty()
                        .not()
                        .or(this.ipFilterLists.textProperty().isEmpty()));
        HBox.setHgrow(this.ipFilterLists, Priority.ALWAYS);
        HBox row = new HBox(6, this.ipFilterLists, update);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    void updateIpFilter() {
        List<URI> urls = this.ipFilterListsInput.isValid()
                ? Preferences.listAddresses(this.ipFilterListsInput.value())
                : List.of();
        if (urls.isEmpty()) {
            return;
        }
        this.gui.inform().accept(this.gui.t("IDS_DWL_IPFILTERFILE"));
        this.gui
                .model()
                .updateIpFilter(urls, ranges -> this.gui.inform().accept(this.gui.f("IDS_IPFILTERLOADED", ranges)));
    }

    TextArea ipFilterListsArea() {
        return this.ipFilterLists;
    }

    Input<String> ipFilterListsInput() {
        return this.ipFilterListsInput;
    }

    TextField ipFilterDaysField() {
        return this.ipFilterDays;
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

    // Asking the system takes a moment: off the FX thread, and the switch waits disabled meanwhile. Only the latest
    // answer counts, so that an older one never undoes what the user set since.
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
                    quietly(() -> this.autostart.setSelected(now));
                    this.autostart.setDisable(false);
                });
            });
        });
    }

    // Starting at login is a change to the system, made only when the switch changed.
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
        Button browse = Fx.button(this.gui.t("IDS_PW_BROWSE"), Icons.FOLDER_OPEN, this::chooseSound);
        Button test = Fx.button(this.gui.t("IDS_TEST"), Icons.VOLUME, this::testSound);
        test.disableProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> !this.soundFileInput.isValid()
                                || this.soundFile.getText().isBlank(),
                        this.soundFileInput.validProperty(),
                        this.soundFile.textProperty()));
        HBox.setHgrow(this.soundFile, Priority.ALWAYS);
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
            this.soundFile.fireEvent(new ActionEvent());
        }
    }

    /** Plays the sound file of the form, applied or not. */
    void testSound() {
        if (this.soundFileInput.isValid()) {
            this.soundFileInput.value().ifPresent(this.gui.sound()::play);
        }
    }

    // The player's program, typed or chosen, then its arguments.
    private Node videoPlayerRow() {
        this.videoPlayerBrowse = Fx.button(this.gui.t("IDS_PW_BROWSE"), Icons.FOLDER_OPEN, this::chooseVideoPlayer);
        HBox.setHgrow(this.videoPlayer, Priority.ALWAYS);
        HBox row = new HBox(6, this.videoPlayer, this.videoPlayerBrowse, this.videoPlayerArgs);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void chooseVideoPlayer() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(this.gui.t("IDS_PW_VIDEOPLAYER"));
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            chooser.getExtensionFilters()
                    .addAll(
                            new FileChooser.ExtensionFilter("*.exe", "*.exe"),
                            new FileChooser.ExtensionFilter("*.*", "*.*"));
        }
        try {
            Optional.ofNullable(Path.of(this.videoPlayer.getText().strip()).getParent())
                    .filter(Files::isDirectory)
                    .ifPresent(dir -> chooser.setInitialDirectory(dir.toFile()));
        } catch (InvalidPathException e) {
            // A path typed wrong: the chooser starts where the system wants.
        }
        File chosen = chooser.showOpenDialog(this.view.getScene().getWindow());
        if (chosen != null) {
            videoPlayerChosen(chosen);
        }
    }

    // A program chosen applies as one typed and confirmed.
    void videoPlayerChosen(File chosen) {
        this.videoPlayer.setText(chosen.getAbsolutePath());
        this.videoPlayer.fireEvent(new ActionEvent());
    }

    private HBox directory(TextField field) {
        field.setPrefColumnCount(32);
        field.setPromptText(this.gui.t("nj.settings.defaultDir"));
        HBox.setHgrow(field, Priority.ALWAYS);
        return new HBox(6, field, Fx.button(this.gui.t("IDS_PW_BROWSE"), Icons.FOLDER_OPEN, () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            var dir = chooser.showDialog(this.view.getScene().getWindow());
            if (dir != null) {
                field.setText(dir.getAbsolutePath());
                field.fireEvent(new ActionEvent());
            }
        }));
    }

    // Asking the system takes a moment: off the FX thread, and the switch waits disabled meanwhile.
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
                    quietly(() -> this.ed2kLinks.setSelected(now));
                    this.ed2kLinks.setDisable(false);
                });
            });
        });
    }

    // Taking or giving back the links is a change to the system, done only when the switch changed.
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

    // The ports and UPnP as the boxes give them, tried before they are applied; a wrong port stays as it was.
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

    /** The pages, for tests. */
    SettingsPages pagesView() {
        return this.pages;
    }

    /** The bar of what waits, for tests. */
    HBox pendingBar() {
        return this.bar;
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

    private String themeName(Themes.Family f) {
        return this.gui.t("nj.theme." + key(f));
    }

    private String modeName(GuiSettings.Mode m) {
        return this.gui.t("nj.mode." + key(m));
    }

    private String yesNo(boolean on) {
        return this.gui.t(on ? "IDS_YES" : "IDS_NO");
    }

    private String rateName(int kib) {
        return kib == 0 ? this.gui.t("nj.rate.noLimit") : this.gui.formats().rate(kib * 1024L);
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
            text.setPromptText("https://…/server.met");
            boolean yes = Ask.about(this.gui, this.gui.t("nj.settings.addressesTitle"))
                    .says(this.gui.t("nj.settings.serverListAddresses"))
                    .with(text)
                    .answers(this.gui.t("IDS_SAVE"), this.gui.t("IDS_CANCEL"))
                    .owner(this.view.getScene().getWindow())
                    .ask();
            if (yes) {
                this.gui.model().setServerListAddresses(addresses(text.getText()));
            }
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

    TextField videoPlayerField() {
        return this.videoPlayer;
    }

    Button videoPlayerBrowseButton() {
        return this.videoPlayerBrowse;
    }

    TextField nickField() {
        return this.nick;
    }

    TextField tcpPortField() {
        return this.tcpPort;
    }

    ComboBox<UpdateMode> updateModeBox() {
        return this.updateMode;
    }

    ComboBox<UpdateChannel> updateChannelBox() {
        return this.updateChannel;
    }

    ComboBox<UpdateInstallTime> updateInstallTimeBox() {
        return this.updateInstallTime;
    }

    TextField nightHoursField() {
        return this.nightHours;
    }

    ToggleSwitch searchSpamFilterBox() {
        return this.searchSpamFilter;
    }

    ToggleSwitch cleanUpNewNamesBox() {
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

    ToggleSwitch webUiBox() {
        return this.webUi;
    }

    ToggleSwitch webUiApiBox() {
        return this.webUiApi;
    }

    TextField webUiPortField() {
        return this.webUiPort;
    }

    TextField webUiProxiesField() {
        return this.webUiProxies;
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

    ToggleSwitch autostartBox() {
        return this.autostart;
    }

    ToggleSwitch soundBox() {
        return this.sound;
    }

    TextField soundFileField() {
        return this.soundFile;
    }

    ToggleSwitch eventSwitch(EventCommands.Event event) {
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
