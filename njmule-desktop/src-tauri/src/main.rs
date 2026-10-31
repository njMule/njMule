// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's desktop shell: the window around the interface (njmule-ui), the tray icon, eD2K links handed over by the
//! system, and the engine itself, started as a process of its own that the interface reaches through its API.

// A window program on Windows, with no console of its own.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};
use tauri::ipc::Response;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, RunEvent, State, Window, WindowEvent, Wry};
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

/// The id of njMule's icon in the notification area.
const TRAY: &str = "main";
/// A notification sound lasts a few seconds: a larger file is not one.
const MAX_SOUND: u64 = 16 * 1024 * 1024;

/// What the shell knows of its window and its icon in the notification area.
#[derive(Default)]
struct Shell {
    // Set once the window listens: closing it then asks first, in the window's language; until then it just closes.
    asks: AtomicBool,
    tray: Mutex<Option<TrayMenu>>,
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
        // The desktop app's engine, as the launcher this program replaces marks it: a new version installed now waits
        // for this program to end too, then starts it again rather than the engine alone.
        command.env("NJMULE_DESKTOP", "1");
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

/// The window's title: the name and version, then the other computer's address when the engine runs there.
fn window_title(version: &str, remote: Option<&str>) -> String {
    match remote.and_then(|url| url.split_once("://")).and_then(|(_, rest)| rest.split('/').next()) {
        Some(authority) if !authority.is_empty() => format!("njMule {version} - {authority}"),
        _ => format!("njMule {version}"),
    }
}

/// Brings the window back, from the notification area too, whose icon then goes.
fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
    if let Some(tray) = app.tray_by_id(TRAY) {
        let _ = tray.set_visible(false);
    }
}

/// A minimised window hides in the notification area, next to the clock, where its icon brings it back. Only on
/// Windows; elsewhere the window minimises as usual.
fn hide_to_tray(window: &Window) {
    if let Some(tray) = window.app_handle().tray_by_id(TRAY) {
        if tray.set_visible(true).is_ok() {
            let _ = window.hide();
        }
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

/// Opens a folder in the system's file manager, or tells the system's reason it could not.
#[tauri::command]
fn open_folder(app: AppHandle, path: String) -> Result<(), String> {
    app.opener().open_path(path, None::<&str>).map_err(|e| e.to_string())
}

/// A WAV file the window plays with a notification, read whole; a huge one is never read into memory.
#[tauri::command]
async fn sound(path: String) -> Result<Response, String> {
    let path = PathBuf::from(path);
    if !path.extension().is_some_and(|e| e.eq_ignore_ascii_case("wav")) {
        return Err(format!("{} is not a WAV file", path.display()));
    }
    if std::fs::metadata(&path).map_err(|e| e.to_string())?.len() > MAX_SOUND {
        return Err(format!("{} is larger than {MAX_SOUND} bytes", path.display()));
    }
    std::fs::read(&path).map(Response::new).map_err(|e| e.to_string())
}

/// The window listens: closing it asks whether to quit from now on.
#[tauri::command]
fn window_ready(shell: State<'_, Shell>) {
    shell.asks.store(true, Ordering::Relaxed);
}

/// Ends njMule, as the window decided: the engine then saves and stops.
#[tauri::command]
fn quit(app: AppHandle) {
    app.exit(0);
}

/// The words of the tray's menu, in the interface's language.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TrayWords {
    full_speed: String,
    slow_down: String,
    show: String,
    options: String,
    quit: String,
}

#[tauri::command]
fn tray_texts(shell: State<'_, Shell>, words: TrayWords) -> Result<(), String> {
    if let Some(menu) = shell.tray.lock().unwrap().as_ref() {
        for (item, text) in [
            (&menu.full_speed, &words.full_speed),
            (&menu.slow_down, &words.slow_down),
            (&menu.show, &words.show),
            (&menu.options, &words.options),
            (&menu.quit, &words.quit),
        ] {
            item.set_text(text).map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// njMule's state and rates at the head of the tray's menu and on its icon, and what the connection item does now.
#[tauri::command]
fn tray_state(app: AppHandle, shell: State<'_, Shell>, state: String, connect: String, tooltip: String) {
    if let Some(menu) = shell.tray.lock().unwrap().as_ref() {
        let _ = menu.state.set_text(state);
        let _ = menu.connect.set_text(connect);
    }
    if let Some(tray) = app.tray_by_id(TRAY) {
        let _ = tray.set_tooltip(Some(tooltip));
    }
}

/// The items of the tray's menu whose words change.
struct TrayMenu {
    state: MenuItem<Wry>,
    full_speed: MenuItem<Wry>,
    slow_down: MenuItem<Wry>,
    show: MenuItem<Wry>,
    connect: MenuItem<Wry>,
    options: MenuItem<Wry>,
    quit: MenuItem<Wry>,
}

/// The tray's menu, as the desktop app's: njMule's state, the two speed presets, the window (its default), connecting
/// or disconnecting, the options and quitting. In English until the window gives its words.
fn tray_menu(app: &AppHandle) -> tauri::Result<(Menu<Wry>, TrayMenu)> {
    let item = |id: &str, text: &str, enabled: bool| MenuItem::with_id(app, id, text, enabled, None::<&str>);
    let items = TrayMenu {
        state: item("state", "njMule", false)?,
        full_speed: item("full-speed", "Full speed", true)?,
        slow_down: item("slow-down", "Slow down", true)?,
        show: item("show", "Show njMule", true)?,
        connect: item("connect", "Connect", true)?,
        options: item("options", "Options", true)?,
        quit: item("quit", "Quit njMule", true)?,
    };
    let menu = Menu::with_items(
        app,
        &[
            &items.state,
            &PredefinedMenuItem::separator(app)?,
            &items.full_speed,
            &items.slow_down,
            &PredefinedMenuItem::separator(app)?,
            &items.show,
            &items.connect,
            &items.options,
            &PredefinedMenuItem::separator(app)?,
            &items.quit,
        ],
    )?;
    Ok((menu, items))
}

/// What an item of the tray's menu does: the window shows itself; the rest is the window's to do, the options and
/// quitting over the window shown again (quitting asks first).
fn tray_chosen(app: &AppHandle, id: &str) {
    match id {
        "show" => show_window(app),
        "quit" if !app.state::<Shell>().asks.load(Ordering::Relaxed) => app.exit(0),
        "options" | "quit" => {
            show_window(app);
            let _ = app.emit("tray", id);
        }
        _ => {
            let _ = app.emit("tray", id);
        }
    }
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
        .manage(Shell::default())
        .invoke_handler(tauri::generate_handler![
            endpoint,
            pending_links,
            choose_folder,
            open_folder,
            tray_texts,
            tray_state,
            sound,
            window_ready,
            quit
        ])
        .setup(move |app| {
            start_engine(engine.clone());
            if let Some(window) = app.get_webview_window("main") {
                let remote = std::env::var("NJMULE_REMOTE_URL").ok();
                window.set_title(&window_title(&app.package_info().version.to_string(), remote.as_deref()))?;
            }
            // macOS hands links over as events rather than arguments.
            let handle = app.handle().clone();
            app.deep_link().on_open_url(move |event| {
                hand_over(&handle, event.urls().iter().map(|u| u.to_string()).collect());
                show_window(&handle);
            });
            // The notification area, as the desktop app uses it: only on Windows, its icon there while the window hides.
            if cfg!(windows) {
                let (menu, items) = tray_menu(app.handle())?;
                let tray = TrayIconBuilder::with_id(TRAY)
                    .icon(app.default_window_icon().cloned().expect("the window's icon"))
                    .tooltip("njMule")
                    .menu(&menu)
                    .show_menu_on_left_click(false)
                    .on_menu_event(|app, event| tray_chosen(app, event.id.as_ref()))
                    .on_tray_icon_event(|tray, event| {
                        if let TrayIconEvent::Click {
                            button: MouseButton::Left, button_state: MouseButtonState::Up, ..
                        } = event
                        {
                            show_window(tray.app_handle());
                        }
                    })
                    .build(app)?;
                tray.set_visible(false)?;
                *app.state::<Shell>().tray.lock().unwrap() = Some(items);
            }
            Ok(())
        })
        .on_window_event(|window, event| match event {
            // Closing the window ends njMule, once the window asked whether to quit.
            WindowEvent::CloseRequested { api, .. } => {
                if window.state::<Shell>().asks.load(Ordering::Relaxed) {
                    api.prevent_close();
                    let _ = window.emit("close-requested", ());
                }
            }
            WindowEvent::Resized(_) if window.is_minimized().unwrap_or(false) => hide_to_tray(window),
            _ => {}
        })
        .build(tauri::generate_context!())
        .expect("njMule's window");
    app.run(|app, event| {
        if let RunEvent::Exit = event {
            app.state::<Arc<Engine>>().stop();
        }
    });
}
