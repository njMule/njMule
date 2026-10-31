// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's desktop shell: the window around the interface (njmule-ui), the tray icon, eD2K links handed over by the
//! system, and the engine itself, started as a process of its own that the interface reaches through its API.

// A window program on Windows, with no console of its own.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tauri::ipc::Response;
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{
    AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, RunEvent, State, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_deep_link::DeepLinkExt;
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_window_state::StateFlags;

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
/// The small windows of the icon: its menu, and MiniMule, njMule at a glance.
const TRAY_MENU: &str = "tray";
const MINIMULE: &str = "minimule";
/// How far MiniMule keeps from the corner of the screen.
const CORNER_GAP: f64 = 8.0;
/// A notification sound lasts a few seconds: a larger file is not one.
const MAX_SOUND: u64 = 16 * 1024 * 1024;
/// The options only the shell applies, some before the window's page runs.
const SETTINGS_FILE: &str = "desktop.json";
/// How often the clipboard is looked at while the options ask for it.
const CLIPBOARD_EVERY: Duration = Duration::from_secs(1);
/// The start of an eD2K file link.
const FILE_LINK: &str = "ed2k://|file|";

/// The options of the desktop app that only the shell applies, as the Options page sets them.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct DesktopSettings {
    start_minimized: bool,
    minimize_to_tray: bool,
    confirm_exit: bool,
    watch_clipboard: bool,
}

impl Default for DesktopSettings {
    fn default() -> Self {
        Self { start_minimized: false, minimize_to_tray: true, confirm_exit: true, watch_clipboard: false }
    }
}

/// Where a small window of the icon opens once its page told its size.
#[derive(Clone, Copy)]
enum Place {
    /// The menu: a corner at the point clicked, on the side of it that fits the screen.
    At(PhysicalPosition<f64>),
    /// MiniMule: the corner of the screen next to the notification area.
    Corner,
}

/// What the shell knows of its windows and its icon in the notification area.
#[derive(Default)]
struct Shell {
    // Set once the window listens: closing it then asks first, in the window's language; until then it just closes.
    asks: AtomicBool,
    settings: Mutex<DesktopSettings>,
    settings_file: OnceLock<PathBuf>,
    // njMule at a glance, as the window last told it, for the icon's small windows.
    glance: Mutex<serde_json::Value>,
    // The small windows asked for, by label, which open once their page told their size.
    asked: Mutex<HashMap<String, Place>>,
    // Each click on the icon counts: a single one waits to see whether a second one makes it double.
    clicks: AtomicU64,
    // The button let go after a double click, which is no click of its own.
    after_double: AtomicBool,
}

impl Shell {
    fn settings(&self) -> DesktopSettings {
        *self.settings.lock().unwrap()
    }
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

/// Where the shell keeps its options: beside the engine's with NJMULE_CONFIG_DIR, otherwise in its own folder.
fn settings_file(app: &AppHandle) -> Option<PathBuf> {
    std::env::var_os("NJMULE_CONFIG_DIR")
        .map(PathBuf::from)
        .or_else(|| app.path().app_config_dir().ok())
        .map(|dir| dir.join(SETTINGS_FILE))
}

/// The options kept, their defaults for those missing or unreadable.
fn load_settings(file: &Path) -> DesktopSettings {
    std::fs::read(file).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default()
}

/// Writes the options whole through a file beside, so that a crash never leaves half of them.
fn save_settings(file: &Path, settings: &DesktopSettings) -> std::io::Result<()> {
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let next = file.with_extension("json.tmp");
    std::fs::write(&next, serde_json::to_vec_pretty(settings)?)?;
    std::fs::rename(next, file)
}

/// Brings the window back, from the notification area too, whose icon then goes with what it opened.
fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
    if let Some(tray) = app.tray_by_id(TRAY) {
        let _ = tray.set_visible(false);
    }
    for label in [TRAY_MENU, MINIMULE] {
        if let Some(window) = app.get_webview_window(label) {
            let _ = window.hide();
        }
    }
}

/// The window hides in the notification area, next to the clock, where its icon brings it back. Only on Windows;
/// elsewhere there is no icon and the window stays as it is.
fn hide_to_tray(app: &AppHandle) {
    let (Some(tray), Some(window)) = (app.tray_by_id(TRAY), app.get_webview_window("main")) else {
        return;
    };
    if tray.set_visible(true).is_ok() {
        let _ = window.hide();
        // Made now, so that they open at once when the icon is clicked; never on the thread of the windows' events.
        let app = app.clone();
        std::thread::spawn(move || {
            for label in [TRAY_MENU, MINIMULE] {
                let _ = popup(&app, label);
            }
        });
    }
}

/// The menu or MiniMule, made once and hidden between uses: the interface's page showing only that view.
fn popup(app: &AppHandle, label: &str) -> tauri::Result<WebviewWindow> {
    if let Some(window) = app.get_webview_window(label) {
        return Ok(window);
    }
    let builder = WebviewWindowBuilder::new(app, label, WebviewUrl::App(format!("index.html?view={label}").into()))
        .visible(false)
        .always_on_top(true)
        .skip_taskbar(true)
        .resizable(false)
        .maximizable(false)
        .minimizable(false);
    if label == TRAY_MENU {
        // A menu of its own drawing, with its border and no frame.
        builder.title("njMule").decorations(false).shadow(false).inner_size(320.0, 360.0).build()
    } else {
        builder.title("MiniMule").inner_size(300.0, 260.0).build()
    }
}

/// Asks for the menu or MiniMule to open `at` a place: its page draws what it shows now, then tells its size.
fn open_popup(app: &AppHandle, label: &str, at: Place) {
    app.state::<Shell>().asked.lock().unwrap().insert(label.to_owned(), at);
    // A page still loading tells its size once loaded.
    if popup(app, label).is_ok() {
        let _ = app.emit_to(label, "popup-show", ());
    }
}

/// `value` within `low` and `high`, at `low` when there is no room between them.
fn within(value: i32, low: i32, high: i32) -> i32 {
    value.clamp(low, high.max(low))
}

/// Sizes `window` to its page of `width` × `height` CSS pixels, places it on the screen's work area and shows it.
fn place_popup(window: &WebviewWindow, at: Place, width: f64, height: f64) -> tauri::Result<()> {
    let monitor = match at {
        Place::At(point) => window.monitor_from_point(point.x, point.y)?,
        Place::Corner => None,
    };
    let Some(monitor) = monitor.or(window.primary_monitor()?) else {
        return window.show();
    };
    let scale = monitor.scale_factor();
    let (outer, inner) = (window.outer_size()?, window.inner_size()?);
    let size = PhysicalSize::new((width * scale).ceil() as u32, (height * scale).ceil() as u32);
    // The frame around the page: none around the menu.
    let w = (size.width + outer.width.saturating_sub(inner.width)) as i32;
    let h = (size.height + outer.height.saturating_sub(inner.height)) as i32;
    let area = monitor.work_area();
    let (left, top) = (area.position.x, area.position.y);
    let (right, bottom) = (left + area.size.width as i32, top + area.size.height as i32);
    let position = match at {
        Place::At(point) => PhysicalPosition::new(
            within(point.x as i32 - w, left, right - w),
            within(point.y as i32 - h, top, bottom - h),
        ),
        Place::Corner => {
            let gap = (CORNER_GAP * scale).round() as i32;
            PhysicalPosition::new(within(right - w - gap, left, right - w), within(bottom - h - gap, top, bottom - h))
        }
    };
    // Placed before and after sizing: moved to a screen of another scale, the window is sized again for it.
    window.set_position(position)?;
    window.set_size(size)?;
    window.set_position(position)?;
    window.show()?;
    window.set_focus()
}

/// The system's longest time between the two clicks of a double click.
fn double_click_time() -> Duration {
    #[cfg(windows)]
    // SAFETY: a plain query of a system setting.
    let millis = unsafe { windows_sys::Win32::UI::Input::KeyboardAndMouse::GetDoubleClickTime() };
    #[cfg(not(windows))]
    let millis = 500;
    Duration::from_millis(u64::from(millis))
}

/// The clicks on the icon: a single one opens MiniMule, once no second one followed; a double one brings the window
/// back; the right button opens the menu at the point clicked.
fn tray_clicked(app: &AppHandle, event: TrayIconEvent) {
    let shell = app.state::<Shell>();
    match event {
        TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } => {
            if shell.after_double.swap(false, Ordering::Relaxed) {
                return;
            }
            let click = shell.clicks.fetch_add(1, Ordering::Relaxed) + 1;
            let app = app.clone();
            std::thread::spawn(move || {
                std::thread::sleep(double_click_time());
                if app.state::<Shell>().clicks.load(Ordering::Relaxed) == click {
                    open_popup(&app, MINIMULE, Place::Corner);
                }
            });
        }
        TrayIconEvent::DoubleClick { button: MouseButton::Left, .. } => {
            shell.clicks.fetch_add(1, Ordering::Relaxed);
            shell.after_double.store(true, Ordering::Relaxed);
            show_window(app);
        }
        TrayIconEvent::Click { button: MouseButton::Right, button_state: MouseButtonState::Up, position, .. } => {
            shell.clicks.fetch_add(1, Ordering::Relaxed);
            let app = app.clone();
            std::thread::spawn(move || open_popup(&app, TRAY_MENU, Place::At(position)));
        }
        _ => {}
    }
}

/// What was chosen in the menu or MiniMule: the window shows itself; the rest is the window's to do, the options over
/// the window shown again, quitting too when it asks first.
fn tray_chosen(app: &AppHandle, id: &str) {
    let shell = app.state::<Shell>();
    match id {
        "show" => show_window(app),
        "quit" if !shell.asks.load(Ordering::Relaxed) || !shell.settings().confirm_exit => app.exit(0),
        "options" | "quit" => {
            show_window(app);
            let _ = app.emit_to("main", "tray", id);
        }
        _ => {
            let _ = app.emit_to("main", "tray", id);
        }
    }
}

/// Whether `text` starts with an eD2K file link, as copied from a page.
fn starts_with_file_link(text: &str) -> bool {
    text.trim_start().get(..FILE_LINK.len()).is_some_and(|start| start.eq_ignore_ascii_case(FILE_LINK))
}

/// Looks at the clipboard once a second while the options ask for it and tells the window each change: the text when
/// it starts with an eD2K file link, nothing otherwise. The first look after the option is turned on only notes it.
fn watch_clipboard(app: AppHandle) {
    std::thread::spawn(move || {
        let mut clipboard = None;
        let mut last: Option<String> = None;
        loop {
            std::thread::sleep(CLIPBOARD_EVERY);
            if !app.state::<Shell>().settings().watch_clipboard {
                last = None;
                continue;
            }
            if clipboard.is_none() {
                clipboard = arboard::Clipboard::new().ok();
            }
            let Some(board) = clipboard.as_mut() else { continue };
            let now = match board.get_text() {
                Ok(text) => text,
                Err(arboard::Error::ContentNotAvailable) => String::new(),
                // Held by another program for a moment: looked at again next time.
                Err(_) => continue,
            };
            if last.as_ref().is_some_and(|before| *before != now) {
                let told = if starts_with_file_link(&now) { now.clone() } else { String::new() };
                let _ = app.emit_to("main", "clipboard", told);
            }
            last = Some(now);
        }
    });
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

/// A file of this computer named `kind`, with the extension `extension`, chosen with the system's dialog.
#[tauri::command]
async fn choose_file(app: AppHandle, title: String, kind: String, extension: String) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title(title)
            .add_filter(kind, &[extension.as_str()])
            .blocking_pick_file()
            .map(|file| file.to_string())
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

/// Brings the window back, from the notification area too.
#[tauri::command]
fn show_main(app: AppHandle) {
    show_window(&app);
}

/// The icon's tooltip, and njMule at a glance for the icon's small windows, as the window tells them at each change.
#[tauri::command]
fn tray_state(app: AppHandle, shell: State<'_, Shell>, tooltip: String, glance: serde_json::Value) {
    if let Some(tray) = app.tray_by_id(TRAY) {
        let _ = tray.set_tooltip(Some(tooltip));
    }
    for label in [TRAY_MENU, MINIMULE] {
        if app.get_webview_window(label).is_some_and(|w| w.is_visible().unwrap_or(false)) {
            let _ = app.emit_to(label, "glance", &glance);
        }
    }
    *shell.glance.lock().unwrap() = glance;
}

/// njMule at a glance, as the window last told it; null until it did.
#[tauri::command]
fn glance(shell: State<'_, Shell>) -> serde_json::Value {
    shell.glance.lock().unwrap().clone()
}

/// The page of a small window drew what it shows now, `width` × `height` CSS pixels: it opens where it was asked to.
#[tauri::command]
fn popup_ready(window: WebviewWindow, shell: State<'_, Shell>, width: f64, height: f64) -> Result<(), String> {
    let at = shell.asked.lock().unwrap().remove(window.label());
    at.map_or(Ok(()), |at| place_popup(&window, at, width, height).map_err(|e| e.to_string()))
}

/// The small window is done with: Escape, or an item chosen.
#[tauri::command]
fn popup_done(window: WebviewWindow) {
    let _ = window.hide();
}

/// What was chosen in the menu, which then goes, or in MiniMule.
#[tauri::command]
fn tray_choice(app: AppHandle, window: WebviewWindow, choice: String) {
    if window.label() == TRAY_MENU {
        let _ = window.hide();
    }
    tray_chosen(&app, &choice);
}

/// Shows `text` as a notification of the system while the window hides in the notification area, where its own
/// messages are not seen.
#[tauri::command]
fn notify(app: AppHandle, text: String) {
    let hidden = app.get_webview_window("main").is_some_and(|w| !w.is_visible().unwrap_or(true));
    if hidden {
        let _ = app.notification().builder().title("njMule").body(text).show();
    }
}

/// The options only the shell applies, and whether this system has the notification area they use.
#[derive(Serialize)]
struct DesktopOptions {
    settings: DesktopSettings,
    tray: bool,
}

#[tauri::command]
fn desktop_settings(shell: State<'_, Shell>) -> DesktopOptions {
    DesktopOptions { settings: shell.settings(), tray: cfg!(windows) }
}

/// Keeps the options set on the Options page, applied from now on and at the next start.
#[tauri::command]
fn set_desktop_settings(shell: State<'_, Shell>, settings: DesktopSettings) -> Result<(), String> {
    *shell.settings.lock().unwrap() = settings;
    shell.settings_file.get().map_or(Ok(()), |file| save_settings(file, &settings).map_err(|e| e.to_string()))
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
        .plugin(tauri_plugin_notification::init())
        // The window shows as the options say, and the small windows of the icon where they are asked for.
        .plugin(
            tauri_plugin_window_state::Builder::default()
                .with_state_flags(StateFlags::all() - StateFlags::VISIBLE)
                .with_denylist(&[TRAY_MENU, MINIMULE])
                .build(),
        )
        .manage(engine.clone())
        .manage(Shell::default())
        .invoke_handler(tauri::generate_handler![
            endpoint,
            pending_links,
            choose_folder,
            choose_file,
            open_folder,
            tray_state,
            sound,
            window_ready,
            quit,
            show_main,
            glance,
            popup_ready,
            popup_done,
            tray_choice,
            notify,
            desktop_settings,
            set_desktop_settings
        ])
        .setup(move |app| {
            start_engine(engine.clone());
            let shell = app.state::<Shell>();
            if let Some(file) = settings_file(app.handle()) {
                *shell.settings.lock().unwrap() = load_settings(&file);
                let _ = shell.settings_file.set(file);
            }
            let window = app.get_webview_window("main").expect("njMule's window");
            let remote = std::env::var("NJMULE_REMOTE_URL").ok();
            window.set_title(&window_title(&app.package_info().version.to_string(), remote.as_deref()))?;
            // macOS hands links over as events rather than arguments.
            let handle = app.handle().clone();
            app.deep_link().on_open_url(move |event| {
                hand_over(&handle, event.urls().iter().map(|u| u.to_string()).collect());
                show_window(&handle);
            });
            // The notification area, as the desktop app uses it: only on Windows, its icon there while the window hides.
            if cfg!(windows) {
                let tray = TrayIconBuilder::with_id(TRAY)
                    .icon(app.default_window_icon().cloned().expect("the window's icon"))
                    .tooltip("njMule")
                    .on_tray_icon_event(|tray, event| tray_clicked(tray.app_handle(), event))
                    .build(app)?;
                tray.set_visible(false)?;
            }
            let settings = shell.settings();
            if !settings.start_minimized {
                window.show()?;
            } else if cfg!(windows) && settings.minimize_to_tray {
                hide_to_tray(app.handle());
            } else {
                window.show()?;
                window.minimize()?;
            }
            watch_clipboard(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| match (window.label(), event) {
            // Closing the window ends njMule, once the window asked whether to quit, when the options want it asked.
            ("main", WindowEvent::CloseRequested { api, .. }) => {
                let shell = window.state::<Shell>();
                if shell.asks.load(Ordering::Relaxed) && shell.settings().confirm_exit {
                    api.prevent_close();
                    let _ = window.emit_to("main", "close-requested", ());
                }
            }
            // The icon's small windows, hidden, would keep the shell running.
            ("main", WindowEvent::Destroyed) => window.app_handle().exit(0),
            ("main", WindowEvent::Resized(_))
                if window.is_minimized().unwrap_or(false) && window.state::<Shell>().settings().minimize_to_tray =>
            {
                hide_to_tray(window.app_handle())
            }
            (TRAY_MENU, WindowEvent::Focused(false)) => {
                let _ = window.hide();
            }
            (MINIMULE, WindowEvent::CloseRequested { api, .. }) => {
                api.prevent_close();
                let _ = window.hide();
            }
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

#[cfg(test)]
mod tests {
    use super::*;

    const LINK: &str = "ed2k://|file|a.iso|1000|0123456789ABCDEF0123456789ABCDEF|/";

    #[test]
    fn should_take_a_copied_file_link_whatever_its_case_and_the_spaces_before() {
        let text = format!("  {}", LINK.to_uppercase());
        then_it_starts_with_a_file_link(&text, true);
    }

    #[test]
    fn should_leave_a_text_that_only_holds_a_link_further_on() {
        let text = format!("see {LINK}");
        then_it_starts_with_a_file_link(&text, false);
    }

    #[test]
    fn should_take_the_defaults_without_a_file() {
        let folder = given_a_folder("missing");
        let file = folder.0.join(SETTINGS_FILE);
        let settings = load_settings(&file);
        then_the_settings_are(settings, DesktopSettings::default());
    }

    #[test]
    fn should_read_the_settings_as_they_were_saved() {
        let folder = given_a_folder("saved");
        let file = folder.0.join(SETTINGS_FILE);
        let saved = DesktopSettings { start_minimized: true, watch_clipboard: true, ..DesktopSettings::default() };
        save_settings(&file, &saved).unwrap();
        then_the_settings_are(load_settings(&file), saved);
    }

    #[test]
    fn should_take_the_defaults_of_the_settings_a_file_leaves_out() {
        let folder = given_a_folder("partial");
        let file = folder.0.join(SETTINGS_FILE);
        std::fs::write(&file, r#"{ "confirmExit": false }"#).unwrap();
        then_the_settings_are(
            load_settings(&file),
            DesktopSettings { confirm_exit: false, ..DesktopSettings::default() },
        );
    }

    #[test]
    fn should_keep_a_window_wider_than_the_screen_at_its_start() {
        then_the_place_is(within(-200, 0, 1000 - 1200), 0);
    }

    #[test]
    fn should_move_a_window_past_the_end_of_the_screen_back_on_it() {
        then_the_place_is(within(1700, 0, 1920 - 320), 1600);
    }

    /// A folder of a test's own, removed after it.
    struct Folder(PathBuf);

    impl Drop for Folder {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn given_a_folder(name: &str) -> Folder {
        let dir = std::env::temp_dir().join(format!("njmule-desktop-test-{}-{name}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        Folder(dir)
    }

    fn then_it_starts_with_a_file_link(text: &str, expected: bool) {
        assert_eq!(starts_with_file_link(text), expected);
    }

    fn then_the_settings_are(settings: DesktopSettings, expected: DesktopSettings) {
        assert_eq!(settings, expected);
    }

    fn then_the_place_is(place: i32, expected: i32) {
        assert_eq!(place, expected);
    }
}
