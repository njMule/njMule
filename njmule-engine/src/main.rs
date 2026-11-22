// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The `njmule` executable: runs the engine on a profile, logging to standard error and to the profile's `logs`
//! folder, with the web interface its settings turn on. With `NJMULE_CONTROL_ENDPOINT=1` it serves the API on a
//! loopback port, prints where on a `njmule-control` line, and stops when its standard input ends.

use std::io;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::Arc;
use std::time::{Duration, Instant};

use njmule_engine::api::ed2k_link::{Ed2kLink, FileLink};
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::list_source::ListSource;
use njmule_engine::api::nj_mule_info;
use njmule_engine::app::desktop_system::DesktopSystem;
use njmule_engine::app::launch_options::{Action, LaunchOptions};
use njmule_engine::app::link_handoff::{self, LinkHandoff};
use njmule_engine::app::web_ui_password::{self, PasswordError, TerminalPasswords};
use njmule_engine::app::{profile_removal, restore_links, updating, windows_service};
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::files::hash::file_hasher;
use njmule_engine::remote::api_token;
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use njmule_engine::remote::desktop_integration::DesktopIntegration;
use njmule_engine::remote::live_web_ui::LiveWebUi;
use njmule_engine::remote::remote_api::Exit;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use njmule_engine::server::server_list::Address;
use njmule_engine::update::hand_over::HandOver;
use njmule_engine::update::update_helper::UpdateHelper;
use tokio::io::AsyncReadExt;
use tokio::sync::{Notify, broadcast, mpsc};
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::fmt::writer::MakeWriterExt;

/// How long the API may take to finish the calls it is answering when the engine stops.
const API_STOP_WAIT: Duration = Duration::from_secs(5);
/// How long `--exit` waits for the running instance to stop.
const EXIT_WAIT: Duration = Duration::from_secs(30);
/// Days of logs kept: one file a day.
const LOG_DAYS: usize = 14;
const EXIT_USAGE: u8 = 2;

fn main() -> ExitCode {
    let raw_args: Vec<String> = std::env::args().skip(1).collect();
    let cli = match LaunchOptions::parse(&raw_args) {
        Ok(cli) => cli,
        Err(error) => {
            eprintln!("{error}");
            eprint!("{}", LaunchOptions::usage());
            return ExitCode::from(EXIT_USAGE);
        }
    };
    match cli.action {
        Action::Help => {
            print!("{}", LaunchOptions::usage());
            ExitCode::SUCCESS
        }
        Action::Version => {
            println!("{} {} ({})", nj_mule_info::NAME, nj_mule_info::version(), nj_mule_info::LICENSE_ID);
            ExitCode::SUCCESS
        }
        Action::License => {
            println!("{}", nj_mule_info::license_text().trim_end());
            ExitCode::SUCCESS
        }
        Action::Warranty => {
            println!("{}", nj_mule_info::warranty_text());
            ExitCode::SUCCESS
        }
        Action::Notice => {
            println!("{}", nj_mule_info::notice_text().trim_end());
            ExitCode::SUCCESS
        }
        Action::Hash => {
            tracing_subscriber::fmt().with_writer(io::stderr).with_ansi(false).init();
            hash(&cli.files)
        }
        Action::ApplyUpdate => {
            tracing_subscriber::fmt().with_writer(io::stderr).with_ansi(false).init();
            let Some(plan) = &cli.apply_update else {
                return ExitCode::FAILURE;
            };
            let wait_for: Vec<u32> = cli.wait_for.iter().filter_map(|&pid| u32::try_from(pid).ok()).collect();
            let code = UpdateHelper::install(plan, &wait_for);
            ExitCode::from(u8::try_from(code).unwrap_or(1))
        }
        Action::Exit => exit_running(&cli),
        Action::RestoreLinks => {
            let dirs = match resolve_dirs(&cli) {
                Ok(dirs) => dirs,
                Err(code) => return code,
            };
            let mut out = io::stdout();
            let mut err = io::stderr();
            let code = restore_links::restore_for_this_system(&dirs.config, &mut out, &mut err);
            ExitCode::from(u8::try_from(code).unwrap_or(1))
        }
        Action::ApiToken | Action::NewApiToken => api_token_action(&cli),
        Action::WebUiPassword => {
            let mut out = io::stdout();
            let mut err = io::stderr();
            let mut passwords = TerminalPasswords;
            let code = web_ui_password::run_command(&cli, &mut out, &mut err, &mut passwords);
            ExitCode::from(u8::try_from(code).unwrap_or(1))
        }
        Action::RemoveProfile => {
            let mut out = io::stdout();
            let mut err = io::stderr();
            let mut passwords = TerminalPasswords;
            let code = profile_removal::run_for_this_system(&cli, &mut out, &mut err, |prompt| {
                web_ui_password::answer(&mut passwords, prompt)
            });
            ExitCode::from(u8::try_from(code).unwrap_or(1))
        }
        Action::Service => {
            let mut out = io::stdout();
            let mut err = io::stderr();
            let mut passwords = TerminalPasswords;
            let code = windows_service::run_for_this_system(&cli, &mut out, &mut err, &mut passwords);
            ExitCode::from(u8::try_from(code).unwrap_or(1))
        }
        Action::Run => run_action(&cli, &raw_args),
        Action::Remote | Action::CheckUpdate | Action::Update => {
            eprintln!("njmule: command not yet wired in this engine build");
            ExitCode::FAILURE
        }
    }
}

fn resolve_dirs(cli: &LaunchOptions) -> Result<AppDirectories, ExitCode> {
    AppDirectories::resolve_current(cli.config_dir.as_deref()).map_err(|e| {
        eprintln!("njmule: {e}");
        ExitCode::FAILURE
    })
}

fn exit_running(cli: &LaunchOptions) -> ExitCode {
    let dirs = match resolve_dirs(cli) {
        Ok(dirs) => dirs,
        Err(code) => return code,
    };
    let config = &dirs.config;
    if !LinkHandoff::deliver(config, &[link_handoff::EXIT]) {
        eprintln!("No njMule runs on {}", config.display());
        return ExitCode::FAILURE;
    }
    // Scripts go on once it has saved and stopped, not as soon as it was asked.
    if !LinkHandoff::await_gone(config, EXIT_WAIT) {
        eprintln!("{} on {} still runs after {} s", nj_mule_info::NAME, config.display(), EXIT_WAIT.as_secs());
        return ExitCode::FAILURE;
    }
    println!("{} on {} has exited.", nj_mule_info::NAME, config.display());
    ExitCode::SUCCESS
}

fn api_token_action(cli: &LaunchOptions) -> ExitCode {
    let dirs = match resolve_dirs(cli) {
        Ok(dirs) => dirs,
        Err(code) => return code,
    };
    let result = if cli.action == Action::NewApiToken {
        api_token::renew(&dirs.config)
    } else {
        api_token::load_or_create(&dirs.config)
    };
    match result {
        Ok(token) => {
            println!("{token}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("Cannot keep the API token: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run_action(cli: &LaunchOptions, raw_args: &[String]) -> ExitCode {
    if cli.remote_desktop().is_some() {
        eprintln!(
            "--remote-url needs --remote here; njmule-gui --remote-url <url> opens the desktop app on that njMule."
        );
        return ExitCode::from(EXIT_USAGE);
    }
    updating::await_end(&cli.wait_for);
    let dirs = match resolve_dirs(cli) {
        Ok(dirs) => dirs,
        Err(code) => return code,
    };
    // Links for the njMule already running go to it, instead of failing on its lock.
    if !cli.links.is_empty() && !cli.network.is_one_shot() {
        let uris: Vec<String> = cli.links.iter().map(Ed2kLink::to_uri).collect();
        if LinkHandoff::deliver(&dirs.config, &uris) {
            println!("The links went to the {} already running.", nj_mule_info::NAME);
            return ExitCode::SUCCESS;
        }
    }
    println!("{}", nj_mule_info::startup_notice("njmule --warranty", "njmule --license"));
    // A password file (a Docker secret, say) is the password of every start.
    match web_ui_password::set_from_file(cli, &dirs.config) {
        Ok(_) => {}
        Err(PasswordError::Usage(e)) => {
            eprintln!("{e}");
            return ExitCode::from(EXIT_USAGE);
        }
        Err(PasswordError::Io(e)) => {
            eprintln!("Cannot set the web interface password: {e}");
            return ExitCode::FAILURE;
        }
    }
    log_to(&dirs);
    let start_arguments = cli.config_dir.as_ref().map_or_else(Vec::new, |dir| {
        let absolute = std::path::absolute(dir).unwrap_or_else(|_| dir.clone());
        vec!["--config-dir".to_owned(), absolute.to_string_lossy().into_owned()]
    });
    let outcome = tokio::runtime::Runtime::new()
        .and_then(|runtime| runtime.block_on(run(dirs, cli.switches.clone(), cli.links.clone(), start_arguments)));
    match outcome {
        Ok(Some(install)) => ExitCode::from(updating::hand_over(&install, raw_args, updating::under_systemd_current())),
        Ok(None) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("Cannot start {}: {e}", nj_mule_info::NAME);
            ExitCode::FAILURE
        }
    }
}

// The link of each file, with its size, its part hashes and the speed it was hashed at.
fn hash(files: &[PathBuf]) -> ExitCode {
    let mut code = ExitCode::SUCCESS;
    for file in files {
        let start = Instant::now();
        match link(file) {
            Ok((link, parts)) => {
                let seconds = start.elapsed().as_secs_f64().max(1e-9);
                println!("{}", link.to_uri());
                #[expect(clippy::cast_precision_loss, reason = "a speed to show")]
                let speed = link.size as f64 / seconds / 1e6;
                println!("  {} bytes, {parts} part hashes, {speed:.1} MB/s", link.size);
            }
            Err(e) => {
                eprintln!("Cannot hash {}: {e}", file.display());
                code = ExitCode::FAILURE;
            }
        }
    }
    code
}

fn link(file: &Path) -> io::Result<(FileLink, usize)> {
    let hashes = file_hasher::hash_file(file)?;
    let name = file.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    let aich = hashes.aich.as_ref().map(|tree| tree.master_hash());
    let link =
        FileLink::new(name, hashes.ed2k.size(), hashes.ed2k.file_hash(), aich, Vec::new(), Vec::new(), Vec::new())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))?;
    Ok((link, hashes.ed2k.part_hashes().len()))
}

// Standard error, and a file a day in the profile's logs folder, where the desktop window points the user; without the
// file, standard error alone.
fn log_to(dirs: &AppDirectories) {
    let file = RollingFileAppender::builder()
        .rotation(Rotation::DAILY)
        .filename_prefix("njmule")
        .filename_suffix("log")
        .max_log_files(LOG_DAYS)
        .build(&dirs.logs);
    match file {
        Ok(file) => tracing_subscriber::fmt().with_writer(io::stderr.and(file)).with_ansi(false).init(),
        Err(e) => {
            tracing_subscriber::fmt().with_writer(io::stderr).with_ansi(false).init();
            tracing::warn!("Cannot log to {}: {e}", dirs.logs.display());
        }
    }
}

async fn run(
    dirs: AppDirectories,
    switches: Vec<String>,
    initial_links: Vec<Ed2kLink>,
    start_arguments: Vec<String>,
) -> io::Result<Option<HandOver>> {
    tracing::debug!("Switches: {switches:?}");
    let config = dirs.config.clone();
    let engine = Engine::start_with(dirs, Switches::of(&switches)).await?;
    let control = std::env::var("NJMULE_CONTROL_ENDPOINT").is_ok_and(|value| value == "1");
    // Asked for by the API, by --exit, or by an update restart, as the end of the input is by the starting process.
    let exit = Arc::new(Notify::new());
    let asked = Arc::clone(&exit);
    let stop: Exit = Arc::new(move || asked.notify_one());
    let mut events = engine.subscribe().live;
    let restarting = Arc::clone(&exit);
    let watcher = tokio::spawn(async move {
        loop {
            match events.recv().await {
                Ok(EngineEvent::UpdateRestart(_)) => {
                    restarting.notify_one();
                    return;
                }
                Ok(_) | Err(broadcast::error::RecvError::Lagged(_)) => {}
                Err(broadcast::error::RecvError::Closed) => return,
            }
        }
    });
    let web = Arc::new(LiveWebUi::new(engine.clone(), Some(Arc::clone(&stop))));
    web.apply(WebUiSettings::load(&config)).await;
    let endpoint = if control {
        // The web interface follows the settings the window saves.
        let (changed, mut changes) = mpsc::unbounded_channel();
        let following = Arc::clone(&web);
        tokio::spawn(async move {
            while let Some(settings) = changes.recv().await {
                following.apply(settings).await;
            }
        });
        let desktop: Option<Arc<dyn DesktopIntegration>> =
            Some(Arc::new(DesktopSystem::for_this_system(config.clone(), &start_arguments)));
        let endpoint = ControlEndpoint::start_with(
            engine.clone(),
            Some(stop),
            move |settings: &WebUiSettings| {
                let _ = changed.send(settings.clone());
            },
            desktop,
        )
        .await?;
        // Printed once the API answers: the starting process calls it right away.
        println!("{}", endpoint.control_line());
        Some(endpoint)
    } else {
        None
    };
    for link in initial_links {
        follow(&engine, link).await;
    }
    let (handed_tx, mut handed_rx) = mpsc::unbounded_channel::<Vec<String>>();
    let handoff = match LinkHandoff::listen_headless(&config, true, move |links| {
        let _ = handed_tx.send(links);
    }) {
        Ok(handoff) => Some(handoff),
        Err(e) => {
            tracing::warn!("Later starts cannot hand their links over: {e}");
            None
        }
    };
    let handoff_engine = engine.clone();
    let handoff_exit = Arc::clone(&exit);
    let handoff_task = tokio::spawn(async move {
        while let Some(links) = handed_rx.recv().await {
            if links.iter().any(|link| link == link_handoff::EXIT) {
                handoff_exit.notify_one();
                return;
            }
            for text in links {
                if let Ok(parsed) = Ed2kLink::parse(&text) {
                    follow(&handoff_engine, parsed).await;
                }
            }
        }
    });
    tokio::select! {
        () = end_of_input(), if control => {}
        () = exit.notified() => {}
        _ = tokio::signal::ctrl_c() => {}
        () = terminate_signal() => {}
    }
    watcher.abort();
    handoff_task.abort();
    drop(handoff);
    web.close().await;
    if let Some(endpoint) = endpoint
        && tokio::time::timeout(API_STOP_WAIT, endpoint.stop()).await.is_err()
    {
        tracing::warn!("The API did not stop in time");
    }
    engine.stop().await;
    Ok(engine.update_hand_over())
}

// A file link downloads, a server link adds the server, a server list or nodes.dat link takes the servers or the Kad
// contacts at its address.
async fn follow(engine: &Engine, link: Ed2kLink) {
    match link {
        Ed2kLink::File(file) => match engine.add_download(file, None).await {
            Ok(info) => tracing::info!("Download added: {}", info.name),
            Err(e) => eprintln!("Cannot add the download: {e}"),
        },
        Ed2kLink::Server(server) => {
            if let Some(address) = Address::parse(&format!("{}:{}", server.host, server.port)) {
                let added = engine.add_server(address).await;
                tracing::info!("Server {}:{} added: {added}", server.host, server.port);
            } else {
                eprintln!("Cannot add the server: {}:{}", server.host, server.port);
            }
        }
        Ed2kLink::ServerList(list) => match engine.add_servers(ListSource::Web(list.url.clone())).await {
            Ok(count) => tracing::info!("{count} new servers from {}", list.url),
            Err(e) => eprintln!("Cannot take the server list: {e}"),
        },
        Ed2kLink::NodesList(list) => match engine.add_kad_contacts(ListSource::Web(list.url.clone())).await {
            Ok(count) => tracing::info!("{count} new Kad contacts from {}", list.url),
            Err(e) => eprintln!("Cannot take the Kad contacts: {e}"),
        },
        other => tracing::info!("Not acting on {}", other.to_uri()),
    }
}

// The starting process closes our standard input to stop us.
async fn end_of_input() {
    let mut input = tokio::io::stdin();
    let mut ignored = [0u8; 256];
    while let Ok(1..) = input.read(&mut ignored).await {}
}

// SIGTERM on Unix stops njMule cleanly so systemd, Docker and uninstallers let it save first.
async fn terminate_signal() {
    #[cfg(unix)]
    {
        if let Ok(mut signal) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            let _ = signal.recv().await;
            return;
        }
    }
    std::future::pending::<()>().await;
}
