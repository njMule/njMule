// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The `njmule` executable: runs the engine on a profile. With `NJMULE_CONTROL_ENDPOINT=1` it serves the API on a
//! loopback port, prints where on a `njmule-control` line, and stops when its standard input ends.

use std::io;
use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

use clap::Parser;
use njmule_engine::api::nj_mule_info;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::remote::control_endpoint::ControlEndpoint;
use tokio::io::AsyncReadExt;

/// How long the API may take to finish the calls it is answering when the engine stops.
const API_STOP_WAIT: Duration = Duration::from_secs(5);

#[derive(Parser)]
#[command(name = "njmule", version = nj_mule_info::version(), about = "njMule, an eD2K and Kad client")]
struct Cli {
    /// The profile's folder, which keeps everything under it.
    #[arg(long, value_name = "FOLDER")]
    config_dir: PathBuf,
    /// A switch NAME=VALUE, such as njmule.upnp.disabled=true; the engine ignores those it does not have.
    #[arg(short = 'D', value_name = "NAME=VALUE")]
    switches: Vec<String>,
}

fn main() -> ExitCode {
    tracing_subscriber::fmt().with_writer(io::stderr).with_ansi(false).init();
    let cli = Cli::parse();
    let outcome = tokio::runtime::Runtime::new().and_then(|runtime| runtime.block_on(run(cli)));
    match outcome {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("njmule: {e}");
            ExitCode::FAILURE
        }
    }
}

async fn run(cli: Cli) -> io::Result<()> {
    tracing::debug!("Switches: {:?}", cli.switches);
    let engine = Engine::start(AppDirectories::under_root(&cli.config_dir)?).await?;
    let control = std::env::var("NJMULE_CONTROL_ENDPOINT").is_ok_and(|value| value == "1");
    let endpoint = if control {
        let endpoint = ControlEndpoint::start(engine.clone()).await?;
        // Printed once the API answers: the starting process calls it right away.
        println!("{}", endpoint.control_line());
        Some(endpoint)
    } else {
        None
    };
    tokio::select! {
        () = end_of_input(), if control => {}
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
