// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's desktop shell: the window around the interface (njmule-ui), the tray icon, eD2K links handed over by the
//! system, and the engine itself, started as a process of its own that the interface reaches through its API.

// A window program on Windows, with no console of its own.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, WindowEvent};
use tauri_plugin_deep_link::DeepLinkExt;
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

/// The line of the engine's output that tells where its API answers and the token of this run.
const CONTROL_LINE: &str = "njmule-control ";
/// How long the engine may take to start before the window tells it could not.
const START_WAIT: Duration = Duration::from_secs(120);
/// How long the engine may take to save and stop once the shell closes.
const STOP_WAIT: Duration = Duration::from_secs(30);

/// Where the engine's API answers and the token that proves the window to it.
#[derive(Clone, Serialize)]
struct Endpoint {
    url: String,
    token: String,
}

/// The engine the shell started, or the one it reaches elsewhere.
#[derive(Default)]
struct Engine {
    endpoint: Mutex<Option<Result<Endpoint, String>>>,
    ready: Condvar,
    child: Mutex<Option<Child>>,
    // Closing it stops the engine, which saves everything first.
    input: Mutex<Option<ChildStdin>>,
    // eD2K links received before the window asked for them.
    links: Mutex<Vec<String>>,
}

impl Engine {
    fn found(&self, endpoint: Result<Endpoint, String>) {
        *self.endpoint.lock().unwrap() = Some(endpoint);
        self.ready.notify_all();
    }

    fn wait(&self) -> Result<Endpoint, String> {
        let deadline = Instant::now() + START_WAIT;
        let mut endpoint = self.endpoint.lock().unwrap();
        while endpoint.is_none() {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err("njMule did not start in time.".into());
            }
            endpoint = self.ready.wait_timeout(endpoint, left).unwrap().0;
        }
        endpoint.clone().unwrap()
    }

    /// Stops the engine started here: its input closes, then it gets the time to save.
    fn stop(&self) {
        self.input.lock().unwrap().take();
        if let Some(mut child) = self.child.lock().unwrap().take() {
            let deadline = Instant::now() + STOP_WAIT;
            while Instant::now() < deadline {
                if let Ok(Some(_)) = child.try_wait() {
                    return;
                }
                std::thread::sleep(Duration::from_millis(100));
            }
            let _ = child.kill();
        }
    }
}

/// The engine to start: NJMULE_CORE in development, otherwise the njmule program beside the shell.
fn engine_program() -> PathBuf {
    if let Ok(path) = std::env::var("NJMULE_CORE") {
        return PathBuf::from(path);
    }
    let name = if cfg!(windows) { "njmule.exe" } else { "njmule" };
    std::env::current_exe().ok().and_then(|exe| exe.parent().map(|dir| dir.join(name))).unwrap_or(name.into())
}

fn start_engine(engine: Arc<Engine>) {
    // A remote njMule, as the desktop app's --remote-url: nothing to start.
    if let (Ok(url), Ok(token)) = (std::env::var("NJMULE_REMOTE_URL"), std::env::var("NJMULE_API_TOKEN")) {
        engine.found(Ok(Endpoint { url, token }));
        return;
    }
    let program = engine_program();
    let mut command = if program.extension().is_some_and(|e| e.eq_ignore_ascii_case("bat")) {
        let mut c = Command::new("cmd");
        c.arg("/C").arg(&program);
        c
    } else {
        Command::new(&program)
    };
    if let Ok(dir) = std::env::var("NJMULE_CONFIG_DIR") {
        command.arg("--config-dir").arg(dir);
    }
    command
        .env("NJMULE_CONTROL_ENDPOINT", "1")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // No console window for the engine.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(e) => {
            engine.found(Err(format!("Cannot start {}: {e}", program.display())));
            return;
        }
    };
    let output = child.stdout.take().expect("piped output");
    *engine.input.lock().unwrap() = child.stdin.take();
    *engine.child.lock().unwrap() = Some(child);
    std::thread::spawn(move || {
        let mut told = false;
        // Read to the end: an output nobody reads would stop the engine once its pipe is full.
        for line in BufReader::new(output).lines() {
            let Ok(line) = line else { break };
            if let Some(json) = line.strip_prefix(CONTROL_LINE).filter(|_| !told) {
                told = true;
                engine.found(
                    serde_json::from_str::<serde_json::Value>(json)
                        .ok()
                        .and_then(|v| {
                            Some(Endpoint {
                                url: v.get("url")?.as_str()?.to_owned(),
                                token: v.get("token")?.as_str()?.to_owned(),
                            })
                        })
                        .ok_or_else(|| "njMule gave no address.".to_owned()),
                );
            }
        }
        if !told {
            engine.found(Err("njMule stopped while starting: see its log.".into()));
        }
    });
}

/// The eD2K links among the words of a command line.
fn links(args: &[String]) -> Vec<String> {
    args.iter().filter(|a| a.to_ascii_lowercase().starts_with("ed2k://")).cloned().collect()
}

fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn hand_over(app: &AppHandle, found: Vec<String>) {
    if found.is_empty() {
        return;
    }
    // The window takes them once it listens; until then they wait.
    if app.emit("links", found.clone()).is_err() || app.get_webview_window("main").is_none() {
        app.state::<Arc<Engine>>().links.lock().unwrap().extend(found);
    }
}

#[tauri::command]
async fn endpoint(engine: State<'_, Arc<Engine>>) -> Result<Endpoint, String> {
    let engine = engine.inner().clone();
    tauri::async_runtime::spawn_blocking(move || engine.wait()).await.map_err(|e| e.to_string())?
}

#[tauri::command]
fn pending_links(engine: State<'_, Arc<Engine>>) -> Vec<String> {
    std::mem::take(&mut *engine.links.lock().unwrap())
}

#[tauri::command]
async fn choose_folder(app: AppHandle, title: String) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.dialog().file().set_title(title).blocking_pick_folder().map(|folder| folder.to_string())
    })
    .await
    .map_err(|e| e.to_string())
}

#[tauri::command]
fn open_folder(app: AppHandle, path: String) -> bool {
    app.opener().open_path(path, None::<&str>).is_ok()
}

/// The words of the tray's menu, in the interface's language.
#[tauri::command]
fn tray_texts(app: AppHandle, show: String, exit: String) -> Result<(), String> {
    let menu = tray_menu(&app, &show, &exit).map_err(|e| e.to_string())?;
    if let Some(tray) = app.tray_by_id("main") {
        tray.set_menu(Some(menu)).map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn tray_menu(app: &AppHandle, show: &str, exit: &str) -> tauri::Result<Menu<tauri::Wry>> {
    let show = MenuItem::with_id(app, "show", show, true, None::<&str>)?;
    let exit = MenuItem::with_id(app, "exit", exit, true, None::<&str>)?;
    Menu::with_items(app, &[&show, &exit])
}

fn main() {
    let engine = Arc::new(Engine::default());
    engine.links.lock().unwrap().extend(links(&std::env::args().collect::<Vec<_>>()));
    let app = tauri::Builder::default()
        // First: a second start hands its links to this window and ends.
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            hand_over(app, links(&args));
            show_window(app);
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .manage(engine.clone())
        .invoke_handler(tauri::generate_handler![endpoint, pending_links, choose_folder, open_folder, tray_texts])
        .setup(move |app| {
            start_engine(engine.clone());
            // macOS hands links over as events rather than arguments.
            let handle = app.handle().clone();
            app.deep_link().on_open_url(move |event| {
                hand_over(&handle, event.urls().iter().map(|u| u.to_string()).collect());
            });
            TrayIconBuilder::with_id("main")
                .icon(app.default_window_icon().cloned().expect("the window's icon"))
                .tooltip("njMule")
                .menu(&tray_menu(app.handle(), "njMule", "Exit")?)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show_window(app),
                    "exit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } =
                        event
                    {
                        show_window(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        // Closing the window keeps njMule running in the tray; Exit in the tray's menu ends it.
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("njMule's window");
    app.run(|app, event| {
        if let RunEvent::Exit = event {
            app.state::<Arc<Engine>>().stop();
        }
    });
}
