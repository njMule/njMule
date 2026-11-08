// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The `njmule` executable: runs the engine on a profile, logging to standard error and to the profile's `logs`
//! folder. With `NJMULE_CONTROL_ENDPOINT=1` it serves the API on a loopback port, prints where on a `njmule-control`
//! line, and stops when its standard input ends. With `--hash` it prints the eD2K links of files instead.

use std::io;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::sync::Arc;
use std::time::{Duration, Instant};

use clap::Parser;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::nj_mule_info;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::files::hash::file_hasher;
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use tokio::io::AsyncReadExt;
use tokio::sync::Notify;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::fmt::writer::MakeWriterExt;

/// How long the API may take to finish the calls it is answering when the engine stops.
const API_STOP_WAIT: Duration = Duration::from_secs(5);
/// Days of logs kept: one file a day.
const LOG_DAYS: usize = 14;

#[derive(Parser)]
#[command(name = "njmule", version = nj_mule_info::version(), about = "njMule, an eD2K and Kad client")]
struct Cli {
    /// The profile's folder, which keeps everything under it; else the system's places, or beside the executable in
    /// portable mode.
    #[arg(long, value_name = "FOLDER")]
    config_dir: Option<PathBuf>,
    /// A switch NAME=VALUE, such as njmule.upnp.disabled=true; the engine ignores those it does not have.
    #[arg(short = 'D', value_name = "NAME=VALUE")]
    switches: Vec<String>,
    /// Prints the ed2k link (with AICH hash) of each file and exits.
    #[arg(long, value_name = "FILE", num_args = 1..)]
    hash: Vec<PathBuf>,
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    if !cli.hash.is_empty() {
        tracing_subscriber::fmt().with_writer(io::stderr).with_ansi(false).init();
        return hash(&cli.hash);
    }
    let dirs = match AppDirectories::resolve_current(cli.config_dir.as_deref()) {
        Ok(dirs) => dirs,
        Err(e) => {
            eprintln!("njmule: {e}");
            return ExitCode::FAILURE;
        }
    };
    log_to(&dirs);
    let switches = cli.switches;
    let outcome = tokio::runtime::Runtime::new().and_then(|runtime| runtime.block_on(run(dirs, switches)));
    match outcome {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("njmule: {e}");
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

async fn run(dirs: AppDirectories, switches: Vec<String>) -> io::Result<()> {
    tracing::debug!("Switches: {switches:?}");
    let engine = Engine::start_with(dirs, Switches::of(&switches)).await?;
    let control = std::env::var("NJMULE_CONTROL_ENDPOINT").is_ok_and(|value| value == "1");
    // Asked for by the API, as the end of the input is by the starting process.
    let exit = Arc::new(Notify::new());
    let endpoint = if control {
        let asked = Arc::clone(&exit);
        let endpoint = ControlEndpoint::start(engine.clone(), Some(Arc::new(move || asked.notify_one()))).await?;
        // Printed once the API answers: the starting process calls it right away.
        println!("{}", endpoint.control_line());
        Some(endpoint)
    } else {
        None
    };
    tokio::select! {
        () = end_of_input(), if control => {}
        () = exit.notified() => {}
        _ = tokio::signal::ctrl_c() => {}
    }
    engine.stop().await;
    if let Some(endpoint) = endpoint
        && tokio::time::timeout(API_STOP_WAIT, endpoint.stop()).await.is_err()
    {
        tracing::warn!("The API did not stop in time");
    }
    Ok(())
}

// The starting process closes our standard input to stop us.
async fn end_of_input() {
    let mut input = tokio::io::stdin();
    let mut ignored = [0u8; 256];
    while let Ok(1..) = input.read(&mut ignored).await {}
}
