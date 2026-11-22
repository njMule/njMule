// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Parsed command-line options shared by the headless launcher and its commands.

use std::error::Error;
use std::fmt;
use std::path::{Component, Path, PathBuf};

use clap::error::{ContextKind, ErrorKind};
use clap::parser::ValueSource;
use clap::{Arg, ArgAction, ArgMatches, Command};
use reqwest::Url;

use crate::api::ed2k_link::{Ed2kLink, FileLink};
use crate::api::import_options::ImportOptions;
use crate::config::preferences::LOG_LEVELS;
use crate::remote::{trusted_proxies, web_ui_settings};
use crate::server::server_list::Address;

/// What the program should do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Run,
    Help,
    Version,
    License,
    Warranty,
    Notice,
    Hash,
    WebUiPassword,
    Exit,
    Remote,
    ApiToken,
    NewApiToken,
    RestoreLinks,
    CheckUpdate,
    Update,
    ApplyUpdate,
    Service,
    RemoveProfile,
}

/// What `--service` does with the Windows service of njMule.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServiceAction {
    Install,
    Uninstall,
    Start,
    Stop,
    Status,
}

impl ServiceAction {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Install => "install",
            Self::Uninstall => "uninstall",
            Self::Start => "start",
            Self::Stop => "stop",
            Self::Status => "status",
        }
    }
}

/// A `--service` command; `output` receives its messages too, for the command that asked for it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceTask {
    pub action: ServiceAction,
    pub output: Option<PathBuf>,
}

/// An import of the eMule profile in `dir`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EmuleImport {
    pub dir: PathBuf,
    pub options: ImportOptions,
}

/// Network work for a run: servers to add, a server to connect to, a search, a hello or downloads. With a search, a
/// hello or downloads the run ends once they are done.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NetworkTasks {
    pub server_met: Option<PathBuf>,
    pub server: Option<Address>,
    pub search: Option<String>,
    pub global: bool,
    pub hello: Option<Address>,
    pub downloads: Vec<FileLink>,
    pub shares: Vec<PathBuf>,
    pub kad_bootstrap: Option<Address>,
    pub kad: bool,
    pub emule_import: Option<EmuleImport>,
}

impl NetworkTasks {
    pub fn is_one_shot(&self) -> bool {
        self.search.is_some() || self.hello.is_some() || !self.downloads.is_empty() || self.emule_import.is_some()
    }
}

/// A new password or configuration for the web interface, which it also turns on.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct WebUiSetup {
    pub port: Option<u16>,
    pub lan: bool,
    pub api: bool,
    pub password_file: Option<PathBuf>,
    pub hosts: Vec<String>,
    pub proxies: Vec<String>,
    pub keep_password: bool,
    pub guest_password_file: Option<PathBuf>,
    pub no_guest: bool,
}

/// A command for a running njMule, through its remote-control API.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Remote {
    pub command: Vec<String>,
    pub url: Option<String>,
}

/// Returned for invalid command-line arguments; the message is meant for the user.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsageError(pub String);

impl fmt::Display for UsageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Error for UsageError {}

/// Parsed command-line options of `njmule`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchOptions {
    pub action: Action,
    pub config_dir: Option<PathBuf>,
    pub log_level: Option<String>,
    pub files: Vec<PathBuf>,
    pub headless: bool,
    pub network: NetworkTasks,
    pub web_ui: Option<WebUiSetup>,
    pub links: Vec<Ed2kLink>,
    pub remote: Option<Remote>,
    pub apply_update: Option<PathBuf>,
    pub wait_for: Vec<u64>,
    pub service: Option<ServiceTask>,
    pub confirmed: bool,
    pub switches: Vec<String>,
}

const ID_CONFIG_DIR: &str = "config-dir";
const ID_LOG_LEVEL: &str = "log-level";
const ID_HEADLESS: &str = "headless";
const ID_ADD_SERVERS: &str = "add-servers";
const ID_CONNECT_SERVER: &str = "connect-server";
const ID_SEARCH: &str = "search";
const ID_GLOBAL: &str = "global";
const ID_KAD: &str = "kad";
const ID_KAD_BOOTSTRAP: &str = "kad-bootstrap";
const ID_HELLO: &str = "hello";
const ID_DOWNLOAD: &str = "download";
const ID_SHARE: &str = "share";
const ID_IMPORT: &str = "import-emule";
const ID_DRY_RUN: &str = "dry-run";
const ID_IDENTITY: &str = "adopt-emule-identity";
const ID_IMPORT_DOWNLOADS: &str = "import-downloads";
const ID_WEBUI_PASSWORD: &str = "webui-password";
const ID_WEBUI_PASSWORD_FILE: &str = "webui-password-file";
const ID_WEBUI_PORT: &str = "webui-port";
const ID_WEBUI_LAN: &str = "webui-lan";
const ID_WEBUI_API: &str = "webui-api";
const ID_WEBUI_HOST: &str = "webui-host";
const ID_WEBUI_PROXY: &str = "webui-proxy";
const ID_WEBUI_GUEST_PASSWORD_FILE: &str = "webui-guest-password-file";
const ID_WEBUI_NO_GUEST: &str = "webui-no-guest";
const ID_EXIT: &str = "exit";
const ID_RESTORE_LINKS: &str = "restore-links";
const ID_REMOTE: &str = "remote";
const ID_REMOTE_URL: &str = "remote-url";
const ID_API_TOKEN: &str = "api-token";
const ID_NEW_API_TOKEN: &str = "new-api-token";
const ID_CHECK_UPDATE: &str = "check-update";
const ID_UPDATE: &str = "update";
const ID_APPLY_UPDATE: &str = "apply-update";
const ID_SERVICE: &str = "service";
const ID_SERVICE_OUTPUT: &str = "service-output";
const ID_REMOVE_PROFILE: &str = "remove-profile";
const ID_YES: &str = "yes";
const ID_WAIT: &str = "wait";
const ID_VERSION: &str = "version";
const ID_LICENSE: &str = "license";
const ID_WARRANTY: &str = "warranty";
const ID_NOTICE: &str = "notice";
const ID_HASH: &str = "hash";
const ID_HELP: &str = "help";
const ID_SWITCHES: &str = "switch";
const ID_LINKS: &str = "links";

const WEBUI_SETUP: &str = "--webui-password, --webui-password-file or --service=install";

const ALONE_OPTIONS: [&str; 6] =
    [ID_EXIT, ID_RESTORE_LINKS, ID_CHECK_UPDATE, ID_UPDATE, ID_APPLY_UPDATE, ID_REMOVE_PROFILE];

const ALONG: [&str; 6] = [ID_CONFIG_DIR, ID_LOG_LEVEL, ID_HEADLESS, ID_WAIT, ID_YES, ID_SWITCHES];

const SERVICE_ALONG: [&str; 4] = [ID_SERVICE, ID_SERVICE_OUTPUT, ID_LOG_LEVEL, ID_SWITCHES];
const SERVICE_INSTALL_ALONG: [&str; 13] = [
    ID_SERVICE,
    ID_SERVICE_OUTPUT,
    ID_LOG_LEVEL,
    ID_SWITCHES,
    ID_WEBUI_PASSWORD,
    ID_WEBUI_PASSWORD_FILE,
    ID_WEBUI_PORT,
    ID_WEBUI_LAN,
    ID_WEBUI_API,
    ID_WEBUI_HOST,
    ID_WEBUI_PROXY,
    ID_WEBUI_GUEST_PASSWORD_FILE,
    ID_WEBUI_NO_GUEST,
];

impl LaunchOptions {
    /// Parses command-line arguments (without the program name).
    pub fn parse<I, S>(args: I) -> Result<Self, UsageError>
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let mut argv = vec!["njmule".to_owned()];
        argv.extend(args.into_iter().map(|arg| arg.as_ref().to_owned()));
        let matches = command().try_get_matches_from(argv).map_err(translate_clap_error)?;

        let help = matches.get_flag(ID_HELP);
        let version = matches.get_flag(ID_VERSION);
        if !help && !version {
            for alone_id in ALONE_OPTIONS {
                if is_matched(&matches, alone_id) {
                    check_alone(&matches, alone_id)?;
                }
            }
        }
        let service = parse_service(&matches)?;
        if matches.get_flag(ID_YES) && !matches.get_flag(ID_REMOVE_PROFILE) {
            return Err(UsageError("--yes applies to --remove-profile".to_owned()));
        }
        let config_dir = matches.get_one::<String>(ID_CONFIG_DIR).map(|value| to_directory(value)).transpose()?;
        let log_level = matches.get_one::<String>(ID_LOG_LEVEL).map(|value| to_level(value)).transpose()?;
        let files =
            collect_values(&matches, ID_HASH).into_iter().map(|file| to_file(&file)).collect::<Result<_, _>>()?;
        let server_met = matches.get_one::<String>(ID_ADD_SERVERS).map(|value| to_file(value)).transpose()?;
        let network = NetworkTasks {
            server_met,
            server: parse_address(&matches, ID_CONNECT_SERVER, "--connect-server")?,
            search: matches.get_one::<String>(ID_SEARCH).cloned(),
            global: matches.get_flag(ID_GLOBAL),
            hello: parse_address(&matches, ID_HELLO, "--hello")?,
            downloads: parse_file_links(&collect_values(&matches, ID_DOWNLOAD))?,
            shares: collect_values(&matches, ID_SHARE)
                .into_iter()
                .map(|dir| to_directory(&dir))
                .collect::<Result<_, _>>()?,
            kad_bootstrap: parse_address(&matches, ID_KAD_BOOTSTRAP, "--kad-bootstrap")?,
            kad: matches.get_flag(ID_KAD),
            emule_import: parse_emule_import(&matches)?,
        };
        if network.global && network.search.is_none() {
            return Err(UsageError("--global applies to --search".to_owned()));
        }
        if network.kad && (network.search.is_none() || network.global) {
            return Err(UsageError("--kad applies to --search, without --global".to_owned()));
        }
        if network.emule_import.is_none()
            && (matches.get_flag(ID_DRY_RUN) || matches.get_flag(ID_IDENTITY) || matches.get_flag(ID_IMPORT_DOWNLOADS))
        {
            return Err(UsageError(
                "--dry-run, --adopt-emule-identity and --import-downloads apply to --import-emule".to_owned(),
            ));
        }
        let web_ui = parse_web_ui(&matches, service.as_ref())?;
        let links = parse_links(&collect_values(&matches, ID_LINKS))?;
        let remote = parse_remote(&matches)?;
        let apply_update = matches.get_one::<String>(ID_APPLY_UPDATE).map(|value| to_file(value)).transpose()?;
        let wait_for = parse_waits(&collect_values(&matches, ID_WAIT))?;
        let switches = collect_values(&matches, ID_SWITCHES);

        Ok(Self {
            action: choose_action(&matches),
            config_dir,
            log_level,
            files,
            headless: matches.get_flag(ID_HEADLESS),
            network,
            web_ui,
            links,
            remote,
            apply_update,
            wait_for,
            service,
            confirmed: matches.get_flag(ID_YES),
            switches,
        })
    }

    /// The help text, without colours.
    pub fn usage() -> String {
        command().render_help().to_string()
    }

    /// The njMule whose API the desktop app shows instead of an engine of its own, if any.
    pub fn remote_desktop(&self) -> Option<&str> {
        self.remote.as_ref().filter(|remote| remote.command.is_empty()).and_then(|remote| remote.url.as_deref())
    }
}

fn choose_action(matches: &ArgMatches) -> Action {
    if matches.get_flag(ID_HELP) {
        return Action::Help;
    }
    if matches.get_flag(ID_VERSION) {
        return Action::Version;
    }
    if matches.get_flag(ID_LICENSE) {
        return Action::License;
    }
    if matches.get_flag(ID_WARRANTY) {
        return Action::Warranty;
    }
    if matches.get_flag(ID_NOTICE) {
        return Action::Notice;
    }
    if is_matched(matches, ID_SERVICE) {
        return Action::Service;
    }
    if matches.get_flag(ID_WEBUI_PASSWORD) {
        return Action::WebUiPassword;
    }
    if matches.get_flag(ID_EXIT) {
        return Action::Exit;
    }
    if is_matched(matches, ID_REMOTE) {
        return Action::Remote;
    }
    if matches.get_flag(ID_NEW_API_TOKEN) {
        return Action::NewApiToken;
    }
    if matches.get_flag(ID_API_TOKEN) {
        return Action::ApiToken;
    }
    if matches.get_flag(ID_RESTORE_LINKS) {
        return Action::RestoreLinks;
    }
    if matches.get_flag(ID_REMOVE_PROFILE) {
        return Action::RemoveProfile;
    }
    if matches.get_flag(ID_CHECK_UPDATE) {
        return Action::CheckUpdate;
    }
    if matches.get_flag(ID_UPDATE) {
        return Action::Update;
    }
    if is_matched(matches, ID_APPLY_UPDATE) {
        return Action::ApplyUpdate;
    }
    if is_matched(matches, ID_HASH) { Action::Hash } else { Action::Run }
}

fn command() -> Command {
    Command::new("njmule")
        .disable_help_flag(true)
        .disable_version_flag(true)
        .color(clap::ColorChoice::Never)
        .arg(valued(ID_CONFIG_DIR, "dir", "keep all data below <dir> instead of the system locations"))
        .arg(valued(ID_LOG_LEVEL, "level", "ERROR, WARN, INFO, DEBUG or TRACE (overrides the preferences)"))
        .arg(flag(ID_HEADLESS, "accepted for older start commands: njmule never opens a window"))
        .arg(valued(ID_ADD_SERVERS, "server.met", "add the servers of another client's server.met"))
        .arg(valued(
            ID_CONNECT_SERVER,
            "host:port",
            "connect to this server (any known one when a search needs a server)",
        ))
        .arg(valued(ID_SEARCH, "expression", "search, print the results and exit"))
        .arg(flag(ID_GLOBAL, "search the other known servers too, over UDP"))
        .arg(flag(ID_KAD, "search on Kad instead of the servers"))
        .arg(valued(ID_KAD_BOOTSTRAP, "host:port", "join Kad through this node (its UDP port), e.g. a running eMule"))
        .arg(valued(ID_HELLO, "host:port", "say hello to a client, print its answer and exit"))
        .arg(
            Arg::new(ID_DOWNLOAD)
                .long(ID_DOWNLOAD)
                .value_name("ed2k link")
                .num_args(1..)
                .action(ArgAction::Append)
                .help("download the files of these links (from their sources and, with --connect-server, the server's), then exit"),
        )
        .arg(
            Arg::new(ID_SHARE)
                .long(ID_SHARE)
                .value_name("dir")
                .num_args(1..)
                .action(ArgAction::Append)
                .help("share the files of these directories too (remembered), besides the incoming one"),
        )
        .arg(valued(
            ID_IMPORT,
            "dir",
            "take servers, Kad contacts, known files, shared directories and preferences from eMule's config directory (only read), then exit",
        ))
        .arg(flag(ID_DRY_RUN, "with --import-emule: only report"))
        .arg(flag(
            ID_IDENTITY,
            "with --import-emule: also take eMule's user hash, key and credits, from the next start (never run both with one identity)",
        ))
        .arg(flag(ID_IMPORT_DOWNLOADS, "with --import-emule: also copy eMule's downloads (paused ones while it runs)"))
        .arg(flag(
            ID_WEBUI_PASSWORD,
            "ask for a password for the web interface, turn the interface on (from the next start) and exit",
        ))
        .arg(valued(
            ID_WEBUI_PASSWORD_FILE,
            "file",
            "the web interface password is the first line of <file> (a Docker or systemd secret, for example): set at each start, turning the interface on and keeping its other options as saved unless given too, or with --webui-password set without asking",
        ))
        .arg(valued(ID_WEBUI_PORT, "port", format!("with {WEBUI_SETUP}: the port of the web interface")))
        .arg(flag(
            ID_EXIT,
            "ask the njMule running on this configuration to save and exit, and wait for it (30 s at most)",
        ))
        .arg(flag(
            ID_RESTORE_LINKS,
            "give ed2k links back to what opened them before the desktop app took them, if it still has them, stop it from starting at login, and exit (the uninstallers run it)",
        ))
        .arg(flag(
            ID_WEBUI_LAN,
            format!("with {WEBUI_SETUP}: let other computers connect (otherwise only this one)"),
        ))
        .arg(flag(
            ID_WEBUI_API,
            format!("with {WEBUI_SETUP}: let the remote-control API answer too, to holders of its token"),
        ))
        .arg(
            Arg::new(ID_WEBUI_HOST)
                .long(ID_WEBUI_HOST)
                .value_name("name")
                .value_delimiter(',')
                .action(ArgAction::Append)
                .help(format!(
                    "with {WEBUI_SETUP}: a host name the web interface answers to, such as a LAN name or a reverse proxy's (repeatable); otherwise only localhost and, with --webui-lan, IP addresses"
                )),
        )
        .arg(
            Arg::new(ID_WEBUI_PROXY)
                .long(ID_WEBUI_PROXY)
                .value_name("address")
                .value_delimiter(',')
                .action(ArgAction::Append)
                .help(format!(
                    "with {WEBUI_SETUP}: a reverse proxy trusted to name its client in X-Forwarded-For, an address, a CIDR block or a range (repeatable); otherwise every request is its own client"
                )),
        )
        .arg(valued(
            ID_WEBUI_GUEST_PASSWORD_FILE,
            "file",
            format!("with {WEBUI_SETUP}: a guest, who may only look, logs in with the first line of <file>, which is not the web interface password"),
        ))
        .arg(flag(ID_WEBUI_NO_GUEST, format!("with {WEBUI_SETUP}: let no guest log in any more")))
        .arg(
            Arg::new(ID_REMOTE)
                .long(ID_REMOTE)
                .value_name("command")
                .num_args(1..)
                .action(ArgAction::Append)
                .help("run a command in the njMule running on this configuration (or at --remote-url), through its remote-control API: --remote help lists them"),
        )
        .arg(valued(
            ID_REMOTE_URL,
            "url",
            "the API of another njMule, as http://host:4711/api/v1, where the command of --remote runs; the token comes from the NJMULE_API_TOKEN environment variable (njmule-gui --remote-url <url> shows that njMule in the desktop app)",
        ))
        .arg(flag(
            ID_CHECK_UPDATE,
            "look for a new version of njMule on GitHub, print it and exit: 0 when this one is the newest, 100 when a newer one is out, 1 when the check failed",
        ))
        .arg(flag(
            ID_UPDATE,
            "look for a new version of njMule, download it, check it against its signed information and install it where njMule may replace itself (the Windows installer and zip, the macOS app, the Linux archives; elsewhere it tells how), then exit; stop njMule first (njmule --exit)",
        ))
        .arg(valued(
            ID_SERVICE,
            "action",
            "install, uninstall, start, stop or status of njMule as a Windows service, which runs it headless with its web interface on the profile in %ProgramData%\\njMule as the virtual account NT SERVICE\\njMule (Windows asks for the administrator's rights); install takes the web interface options, asks for its password when none is set, and restarts a service that runs",
        ))
        .arg(flag(
            ID_REMOVE_PROFILE,
            "delete this njMule's settings, lists, identity, unfinished downloads, caches and logs, after asking, and exit; the completed downloads stay (stop njMule first)",
        ))
        .arg(flag(ID_YES, "with --remove-profile: do not ask"))
        .arg(
            Arg::new(ID_SERVICE_OUTPUT)
                .long(ID_SERVICE_OUTPUT)
                .value_name("file")
                .hide(true)
                .help("also write the messages of --service to <file>"),
        )
        .arg(
            Arg::new(ID_APPLY_UPDATE)
                .long(ID_APPLY_UPDATE)
                .value_name("plan")
                .hide(true)
                .help("install the new version that a stopping njMule handed over in <plan>"),
        )
        .arg(
            Arg::new(ID_WAIT)
                .long(ID_WAIT)
                .value_name("pid")
                .action(ArgAction::Append)
                .allow_hyphen_values(true)
                .hide(true)
                .help("wait (60 s at most) for the process <pid> to end first, as after an update"),
        )
        .arg(flag(ID_API_TOKEN, "print the token of the remote-control API, making one if needed"))
        .arg(flag(ID_NEW_API_TOKEN, "replace the token of the remote-control API, print the new one and exit"))
        .arg(Arg::new(ID_VERSION).short('V').long(ID_VERSION).action(ArgAction::SetTrue).help("print the version and exit"))
        .arg(flag(ID_LICENSE, "print the GNU General Public License and exit"))
        .arg(flag(ID_WARRANTY, "print the warranty disclaimer and exit"))
        .arg(flag(ID_NOTICE, "print copyright, attribution and third-party notices"))
        .arg(
            Arg::new(ID_HASH)
                .long(ID_HASH)
                .value_name("file")
                .num_args(1..)
                .action(ArgAction::Append)
                .help("print the ed2k link (with AICH hash) of each file and exit"),
        )
        .arg(Arg::new(ID_HELP).short('h').long(ID_HELP).action(ArgAction::SetTrue).help("print this help and exit"))
        .arg(
            Arg::new(ID_SWITCHES)
                .short('D')
                .value_name("NAME=VALUE")
                .action(ArgAction::Append)
                .hide(true),
        )
        .arg(
            Arg::new(ID_LINKS)
                .value_name("ed2k link")
                .num_args(0..)
                .help("add these downloads, handing them to the njMule already running with the same configuration, if any (as a browser does with a clicked link)"),
        )
}

fn valued(id: &'static str, label: &'static str, help: impl Into<clap::builder::StyledStr>) -> Arg {
    Arg::new(id).long(id).value_name(label).help(help.into())
}

fn flag(id: &'static str, help: impl Into<clap::builder::StyledStr>) -> Arg {
    Arg::new(id).long(id).action(ArgAction::SetTrue).help(help.into())
}

fn translate_clap_error(error: clap::Error) -> UsageError {
    if error.kind() == ErrorKind::UnknownArgument {
        if let Some(arg) = error.get(ContextKind::InvalidArg) {
            return UsageError(format!("Unknown option: {arg}"));
        }
        return UsageError(format!("Unknown option: {error}"));
    }
    UsageError(error.render().to_string())
}

fn is_matched(matches: &ArgMatches, id: &str) -> bool {
    matches.value_source(id) == Some(ValueSource::CommandLine)
}

fn collect_values(matches: &ArgMatches, id: &str) -> Vec<String> {
    matches.get_many::<String>(id).map(|values| values.cloned().collect()).unwrap_or_default()
}

fn matched_options(matches: &ArgMatches) -> Vec<&'static str> {
    const ALL_OPTIONS: [&str; 46] = [
        ID_CONFIG_DIR,
        ID_LOG_LEVEL,
        ID_HEADLESS,
        ID_ADD_SERVERS,
        ID_CONNECT_SERVER,
        ID_SEARCH,
        ID_GLOBAL,
        ID_KAD,
        ID_KAD_BOOTSTRAP,
        ID_HELLO,
        ID_DOWNLOAD,
        ID_SHARE,
        ID_IMPORT,
        ID_DRY_RUN,
        ID_IDENTITY,
        ID_IMPORT_DOWNLOADS,
        ID_WEBUI_PASSWORD,
        ID_WEBUI_PASSWORD_FILE,
        ID_WEBUI_PORT,
        ID_WEBUI_LAN,
        ID_WEBUI_API,
        ID_WEBUI_HOST,
        ID_WEBUI_PROXY,
        ID_WEBUI_GUEST_PASSWORD_FILE,
        ID_WEBUI_NO_GUEST,
        ID_EXIT,
        ID_RESTORE_LINKS,
        ID_REMOTE,
        ID_REMOTE_URL,
        ID_API_TOKEN,
        ID_NEW_API_TOKEN,
        ID_CHECK_UPDATE,
        ID_UPDATE,
        ID_APPLY_UPDATE,
        ID_SERVICE,
        ID_SERVICE_OUTPUT,
        ID_REMOVE_PROFILE,
        ID_YES,
        ID_WAIT,
        ID_VERSION,
        ID_LICENSE,
        ID_WARRANTY,
        ID_NOTICE,
        ID_HASH,
        ID_HELP,
        ID_SWITCHES,
    ];
    ALL_OPTIONS.into_iter().filter(|id| is_matched(matches, id)).collect()
}

fn option_flag(id: &str) -> String {
    if id == ID_SWITCHES { "-D".to_owned() } else { format!("--{id}") }
}

fn check_alone(matches: &ArgMatches, name_id: &str) -> Result<(), UsageError> {
    let name = option_flag(name_id);
    for option in matched_options(matches) {
        if option != name_id && !ALONG.contains(&option) {
            return Err(UsageError(format!("{name} cannot be combined with {}", option_flag(option))));
        }
    }
    if !collect_values(matches, ID_LINKS).is_empty() {
        return Err(UsageError(format!("{name} cannot be combined with links")));
    }
    Ok(())
}

fn parse_service(matches: &ArgMatches) -> Result<Option<ServiceTask>, UsageError> {
    let Some(value) = matches.get_one::<String>(ID_SERVICE) else {
        if is_matched(matches, ID_SERVICE_OUTPUT) {
            return Err(UsageError("--service-output applies to --service".to_owned()));
        }
        return Ok(None);
    };
    let action = match value.to_ascii_lowercase().as_str() {
        "install" => ServiceAction::Install,
        "uninstall" => ServiceAction::Uninstall,
        "start" => ServiceAction::Start,
        "stop" => ServiceAction::Stop,
        "status" => ServiceAction::Status,
        _ => {
            return Err(UsageError(format!("Invalid --service: {value} (install, uninstall, start, stop or status)")));
        }
    };
    let along: &[&str] = if action == ServiceAction::Install { &SERVICE_INSTALL_ALONG } else { &SERVICE_ALONG };
    for option in matched_options(matches) {
        if !along.contains(&option) {
            return Err(UsageError(format!("--service={value} cannot be combined with {}", option_flag(option))));
        }
    }
    if !collect_values(matches, ID_LINKS).is_empty() {
        return Err(UsageError("--service cannot be combined with links".to_owned()));
    }
    let output = matches.get_one::<String>(ID_SERVICE_OUTPUT).map(|file| to_file(file)).transpose()?;
    Ok(Some(ServiceTask { action, output }))
}

fn parse_address(matches: &ArgMatches, id: &str, option: &str) -> Result<Option<Address>, UsageError> {
    let Some(value) = matches.get_one::<String>(id) else {
        return Ok(None);
    };
    Address::parse(value).map(Some).ok_or_else(|| UsageError(format!("Invalid address for {option}: {value}")))
}

fn parse_emule_import(matches: &ArgMatches) -> Result<Option<EmuleImport>, UsageError> {
    let Some(dir) = matches.get_one::<String>(ID_IMPORT) else {
        return Ok(None);
    };
    Ok(Some(EmuleImport {
        dir: to_directory(dir)?,
        options: ImportOptions {
            dry_run: matches.get_flag(ID_DRY_RUN),
            identity: matches.get_flag(ID_IDENTITY),
            downloads: matches.get_flag(ID_IMPORT_DOWNLOADS),
        },
    }))
}

fn parse_web_ui(matches: &ArgMatches, service: Option<&ServiceTask>) -> Result<Option<WebUiSetup>, UsageError> {
    let port_given = is_matched(matches, ID_WEBUI_PORT);
    let lan = matches.get_flag(ID_WEBUI_LAN);
    let api = matches.get_flag(ID_WEBUI_API);
    let host_given = is_matched(matches, ID_WEBUI_HOST);
    let proxy_given = is_matched(matches, ID_WEBUI_PROXY);
    let no_guest = matches.get_flag(ID_WEBUI_NO_GUEST);
    let password_file = matches.get_one::<String>(ID_WEBUI_PASSWORD_FILE).map(|file| to_file(file)).transpose()?;
    let guest_password_file =
        matches.get_one::<String>(ID_WEBUI_GUEST_PASSWORD_FILE).map(|file| to_file(file)).transpose()?;
    if no_guest && guest_password_file.is_some() {
        return Err(UsageError("--webui-guest-password-file cannot be combined with --webui-no-guest".to_owned()));
    }
    let keep_password = !matches.get_flag(ID_WEBUI_PASSWORD) && password_file.is_none();
    if keep_password
        && !(port_given || lan || api || host_given || proxy_given || no_guest || guest_password_file.is_some())
    {
        return Ok(None);
    }
    if keep_password && service.is_none_or(|task| task.action != ServiceAction::Install) {
        return Err(UsageError(format!(
            "--webui-port, --webui-lan, --webui-api, --webui-host, --webui-proxy, \
             --webui-guest-password-file and --webui-no-guest apply to {WEBUI_SETUP}"
        )));
    }
    let mut hosts = Vec::new();
    for name in collect_values(matches, ID_WEBUI_HOST) {
        let normalized = web_ui_settings::host_name(&name)
            .map_err(|_| UsageError(format!("Invalid host name for --webui-host: {name}")))?;
        hosts.push(normalized);
    }
    let mut proxies = Vec::new();
    for address in collect_values(matches, ID_WEBUI_PROXY) {
        let normalized = trusted_proxies::entry(&address)
            .map_err(|_| UsageError(format!("Invalid proxy for --webui-proxy: {address}")))?;
        proxies.push(normalized);
    }
    let port = if let Some(value) = matches.get_one::<String>(ID_WEBUI_PORT) {
        let parsed = value.trim().parse::<u16>().ok().filter(|&port| port >= 1);
        Some(parsed.ok_or_else(|| UsageError(format!("Invalid port for --webui-port: {value}")))?)
    } else {
        None
    };
    Ok(Some(WebUiSetup { port, lan, api, password_file, hosts, proxies, keep_password, guest_password_file, no_guest }))
}

fn parse_waits(values: &[String]) -> Result<Vec<u64>, UsageError> {
    let mut pids = Vec::with_capacity(values.len());
    for value in values {
        let pid = value
            .trim()
            .parse::<i64>()
            .ok()
            .filter(|&pid| pid > 0)
            .and_then(|pid| u64::try_from(pid).ok())
            .ok_or_else(|| UsageError(format!("Invalid process for --wait: {value}")))?;
        pids.push(pid);
    }
    Ok(pids)
}

fn parse_remote(matches: &ArgMatches) -> Result<Option<Remote>, UsageError> {
    if !is_matched(matches, ID_REMOTE) && !is_matched(matches, ID_REMOTE_URL) {
        return Ok(None);
    }
    let url = if let Some(value) = matches.get_one::<String>(ID_REMOTE_URL) {
        let trimmed = value.trim();
        let parsed = Url::parse(trimmed).ok().filter(|uri| matches!(uri.scheme(), "http" | "https"));
        if parsed.is_none() {
            return Err(UsageError(format!("Invalid address for --remote-url: {value}")));
        }
        Some(trimmed.to_owned())
    } else {
        None
    };
    Ok(Some(Remote { command: collect_values(matches, ID_REMOTE), url }))
}

fn parse_file_links(values: &[String]) -> Result<Vec<FileLink>, UsageError> {
    let mut links = Vec::with_capacity(values.len());
    for value in values {
        match Ed2kLink::parse(value) {
            Ok(Ed2kLink::File(link)) => links.push(link),
            _ => return Err(UsageError(format!("Not an ed2k file link: {value}"))),
        }
    }
    Ok(links)
}

fn parse_links(values: &[String]) -> Result<Vec<Ed2kLink>, UsageError> {
    let mut links = Vec::with_capacity(values.len());
    for value in values {
        let link = Ed2kLink::parse(value).map_err(|_| UsageError(format!("Not an ed2k link: {value}")))?;
        links.push(link);
    }
    Ok(links)
}

fn to_directory(value: &str) -> Result<PathBuf, UsageError> {
    if value.contains('\0') {
        return Err(UsageError(format!("Invalid directory: {value}")));
    }
    let absolute =
        std::path::absolute(Path::new(value)).map_err(|_| UsageError(format!("Invalid directory: {value}")))?;
    Ok(normalize(&absolute))
}

fn to_file(value: &str) -> Result<PathBuf, UsageError> {
    if value.contains('\0') {
        return Err(UsageError(format!("Invalid file: {value}")));
    }
    Ok(PathBuf::from(value))
}

fn to_level(value: &str) -> Result<String, UsageError> {
    let level = value.to_ascii_uppercase();
    if !LOG_LEVELS.contains(&level.as_str()) {
        return Err(UsageError(format!("Invalid log level: {value}")));
    }
    Ok(level)
}

// Resolves `.` and `..` lexically, matching Java's Path.normalize().
fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                let _ = out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}
