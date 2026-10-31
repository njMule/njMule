// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's desktop shell: the window around the interface (njmule-ui), the tray icon, eD2K links handed over by the
//! system, and the engine itself, started as a process of its own that the interface reaches through its API.

// A window program on Windows, with no console of its own.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod verbs;

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tauri::ipc::Response;
use tauri::menu::MenuItem;
#[cfg(target_os = "macos")]
use tauri::menu::{Menu, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::utils::config::{Csp, CspDirectiveSources};
use tauri::{
    AppHandle, Emitter, Manager, PhysicalPosition, PhysicalSize, RunEvent, State, WebviewUrl, WebviewWindow,
    WebviewWindowBuilder, WindowEvent, Wry,
};
use tauri_plugin_deep_link::DeepLinkExt;
use tauri_plugin_dialog::{DialogExt, MessageDialogKind};
use tauri_plugin_notification::NotificationExt;
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_window_state::StateFlags;

/// The line of the engine's output that tells where its API answers and the token of this run.
const CONTROL_LINE: &str = "njmule-control ";
/// How long the engine may take to start before the window tells it could not.
const START_WAIT: Duration = Duration::from_secs(120);
/// How long the engine may take to save and stop once the shell closes.
const STOP_WAIT: Duration = Duration::from_secs(30);

/// The option that opens the window on another computer's njMule, at the address of its API.
const REMOTE_OPTION: &str = "--remote-url";
/// The option that names the profile, as the start at login passes it.
const CONFIG_OPTION: &str = "--config-dir";
/// The id Windows groups the window, its shortcuts and its notifications by, as the installer names it.
#[cfg(windows)]
const APP_USER_MODEL_ID: &str = "org.njmule.njMule";
/// Where the token of the other njMule's API comes from, else from a connection remembered.
const TOKEN_VARIABLE: &str = "NJMULE_API_TOKEN";
/// Set for a window on another njMule opened from the dialog of this one, which waits to hear that it reached it.
const CHECK_VARIABLE: &str = "NJMULE_REMOTE_CHECK";
/// The line such a window writes once it reached the other njMule, followed by null or, as JSON, why it could not.
const CHECKED_LINE: &str = "njmule-remote ";
/// How long the dialog waits for the new window to reach the other njMule.
const OPEN_WAIT: Duration = Duration::from_secs(60);
/// The connections remembered, address and token, in the desktop app's folder of the profile (a Java properties file).
const REMOTES_FILE: &str = "remotes.properties";

/// Where the engine's API answers and the token that proves the window to it.
#[derive(Clone, Serialize)]
struct Endpoint {
    url: String,
    token: String,
    // The engine of another computer, which the window reaches first before it shows.
    remote: bool,
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
    // The API of the other computer's njMule the window shows, if it does.
    remote: Option<String>,
    // Whether the window opened from another one's dialog, which waits to hear that it reached the other njMule.
    checked: bool,
    // About, Settings and Quit in the application menu of macOS.
    app_menu: Mutex<Vec<MenuItem<Wry>>>,
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
    // Set once the window closes: the engine stops because it was asked to.
    stopping: AtomicBool,
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
        self.stopping.store(true, Ordering::Relaxed);
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
    std::env::var("NJMULE_CORE").map(PathBuf::from).unwrap_or_else(|_| verbs::engine_beside())
}

fn start_engine(app: AppHandle, engine: Arc<Engine>) {
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
    // The desktop app's engine: a new version installed now waits for this program to end too, then starts it again
    // rather than the engine alone; the start at login and the eD2K links name this program too.
    command
        .env("NJMULE_CONTROL_ENDPOINT", "1")
        .env("NJMULE_DESKTOP", "1")
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
                                remote: false,
                            })
                        })
                        .ok_or_else(|| "njMule gave no address.".to_owned()),
                );
            }
        }
        if !told {
            engine.found(Err("njMule stopped while starting: see its log.".into()));
            return;
        }
        // The engine ended by itself, as it does to install a new version: the window goes with it, which a failure
        // tells first. Its input closes first when the window asked it to stop, so that waiting here holds nothing up.
        if engine.stopping.load(Ordering::Relaxed) {
            return;
        }
        let ended = engine.child.lock().unwrap().as_mut().map(|child| child.wait());
        if engine.stopping.load(Ordering::Relaxed) {
            return;
        }
        if !matches!(ended, Some(Ok(status)) if status.success()) {
            app.dialog()
                .message("njMule stopped unexpectedly: see its log.")
                .kind(MessageDialogKind::Error)
                .title("njMule")
                .blocking_show();
        }
        app.exit(0);
    });
}

/// The profile --config-dir names, if it does.
fn config_dir(args: &[String]) -> Option<String> {
    let mut words = args.iter().skip(1);
    while let Some(word) = words.next() {
        if word == CONFIG_OPTION {
            return words.next().cloned();
        }
        if let Some(dir) = word.strip_prefix(CONFIG_OPTION).and_then(|w| w.strip_prefix('=')) {
            return Some(dir.to_owned());
        }
    }
    None
}

/// Groups the window with the installer's shortcuts and notifications on Windows.
fn set_app_user_model_id() {
    #[cfg(windows)]
    {
        let id: Vec<u16> = APP_USER_MODEL_ID.encode_utf16().chain(std::iter::once(0)).collect();
        // SAFETY: the id ends with a null and outlives the call, which copies it.
        unsafe { windows_sys::Win32::UI::Shell::SetCurrentProcessExplicitAppUserModelID(id.as_ptr()) };
    }
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

/// The API of another computer's njMule this start shows, as --remote-url names it, or else NJMULE_REMOTE_URL.
fn remote_url(args: &[String]) -> Option<String> {
    let mut words = args.iter().skip(1);
    let named = loop {
        let Some(word) = words.next() else { break None };
        if word == REMOTE_OPTION {
            break words.next().cloned();
        }
        if let Some(url) = word.strip_prefix(REMOTE_OPTION).and_then(|w| w.strip_prefix('=')) {
            break Some(url.to_owned());
        }
    };
    named.or_else(|| std::env::var("NJMULE_REMOTE_URL").ok()).filter(|url| !url.trim().is_empty())
}

/// The scheme and authority of an address, as a page's origin.
fn origin(url: &str) -> Option<String> {
    let (scheme, rest) = url.split_once("://")?;
    let authority = rest.split(['/', '?', '#']).next().filter(|a| !a.is_empty())?;
    Some(format!("{}://{authority}", scheme.to_ascii_lowercase()))
}

/// Lets the window's page reach the API at `url` too: the policy of its pages names the engines it may call.
fn allow_api(config: &mut tauri::Config, url: &str) {
    if let (Some(origin), Some(csp)) = (origin(url), config.app.security.csp.take()) {
        let mut directives: HashMap<String, CspDirectiveSources> = csp.into();
        directives.entry("connect-src".into()).or_default().push(origin);
        config.app.security.csp = Some(Csp::DirectiveMap(directives));
    }
}

/// The desktop app's folder of the profile the engine uses, as the engine finds it: the one --config-dir names, the
/// portable one beside the engine, or the system's place for the user's settings.
fn gui_dir(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(root) = std::env::var("NJMULE_CONFIG_DIR") {
        return Some(PathBuf::from(root).join("gui"));
    }
    let core = engine_program();
    if let Some(dir) = core.parent().filter(|d| d.join("portable.flag").is_file()) {
        return Some(dir.join("data").join("gui"));
    }
    let name = if cfg!(target_os = "linux") { "njmule" } else { "njMule" };
    app.path().config_dir().ok().map(|dir| dir.join(name).join("gui"))
}

/// The connections remembered in `file`, the token of each address of an API; none when it cannot be read.
fn read_remotes(file: &Path) -> HashMap<String, String> {
    let mut all = HashMap::new();
    if let Ok(input) = std::fs::File::open(file) {
        let read = java_properties::PropertiesIter::new_with_encoding(BufReader::new(input), encoding_rs::UTF_8)
            .read_into(|address, token| {
                all.insert(address, token);
            });
        if read.is_err() {
            all.clear();
        }
    }
    all
}

/// Replaces the connections remembered in `file` with `all`, in a file that only its owner may read where the system
/// says so.
fn write_remotes(file: &Path, all: &HashMap<String, String>) -> Result<(), String> {
    let mut addresses: Vec<_> = all.keys().collect();
    addresses.sort();
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let mut tmp = file.as_os_str().to_owned();
    tmp.push(".tmp");
    let _ = std::fs::remove_file(&tmp);
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    let output = options.open(&tmp).map_err(|e| e.to_string())?;
    let mut out = java_properties::PropertiesWriter::new_with_encoding(output, encoding_rs::UTF_8);
    out.write_comment("njMule remote connections").map_err(|e| e.to_string())?;
    for address in addresses {
        out.write(address, &all[address]).map_err(|e| e.to_string())?;
    }
    out.finish().map_err(|e| e.to_string())?;
    drop(out);
    std::fs::rename(&tmp, file).map_err(|e| e.to_string())
}

/// The connections remembered on this computer.
fn remembered(app: &AppHandle) -> HashMap<String, String> {
    gui_dir(app).map(|dir| read_remotes(&dir.join(REMOTES_FILE))).unwrap_or_default()
}

/// Remembers the token of the API at `url` for the next connections.
fn remember_connection(app: &AppHandle, url: &str, token: &str) -> Result<(), String> {
    let file = gui_dir(app).ok_or("no folder for the desktop app's files")?.join(REMOTES_FILE);
    let mut all = read_remotes(&file);
    all.insert(url.to_owned(), token.to_owned());
    write_remotes(&file, &all)
}

/// The other njMule's API and its token: the environment's, else the one remembered for that address.
fn remote_endpoint(app: &AppHandle, url: &str) -> Result<Endpoint, String> {
    let token = std::env::var(TOKEN_VARIABLE)
        .ok()
        .map(|t| t.trim().to_owned())
        .filter(|t| !t.is_empty())
        .or_else(|| remembered(app).remove(url))
        .ok_or_else(|| format!("No token for {url}: set {TOKEN_VARIABLE} or connect from the menu."))?;
    Ok(Endpoint { url: url.to_owned(), token, remote: true })
}

/// Starts this program again on the API at `url`, the token in its environment rather than on its command line, and
/// waits until its window reached the other njMule; otherwise why it could not.
fn open_window_on(url: &str, token: &str) -> Result<(), String> {
    let program = std::env::current_exe().map_err(|e| e.to_string())?;
    let mut child = Command::new(program)
        .arg(REMOTE_OPTION)
        .arg(url)
        .env(TOKEN_VARIABLE, token)
        .env(CHECK_VARIABLE, "1")
        .env_remove("NJMULE_REMOTE_URL")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;
    let output = child.stdout.take().expect("piped output");
    let (told, heard) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let answer = BufReader::new(output).lines().map_while(Result::ok).find_map(|line| {
            let json = line.strip_prefix(CHECKED_LINE)?.to_owned();
            Some(serde_json::from_str::<Option<String>>(&json).unwrap_or(Some(json)))
        });
        let _ = told.send(answer);
    });
    let answer = heard.recv_timeout(OPEN_WAIT);
    if !matches!(answer, Ok(Some(None))) {
        let _ = child.kill();
    }
    // Waited for, so that it leaves nothing behind once it ends.
    std::thread::spawn(move || child.wait());
    match answer {
        Ok(Some(None)) => Ok(()),
        Ok(Some(Some(problem))) => Err(problem),
        Ok(None) => Err("The new window ended before it reached the other njMule.".into()),
        Err(_) => Err("The other njMule did not answer in time.".into()),
    }
}

/// The player of the options: a path copied from a file manager may come in quotes, which are no part of its name.
fn player_program(player: &str) -> &str {
    let program = player.trim();
    for quote in ['"', '\''] {
        if program.len() >= 2 && program.starts_with(quote) && program.ends_with(quote) {
            return program[1..program.len() - 1].trim();
        }
    }
    program
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

/// The icon Windows shows for files with `extension`, which the program that opens them gives (a film shows the
/// player's), as for a file that does not exist yet: its width and height as two 32-bit numbers, then four bytes a
/// pixel (blue, green, red, alpha), row by row from the top. An older icon with no alpha takes it from its mask.
#[cfg(windows)]
fn system_icon(extension: &str) -> Option<Vec<u8>> {
    use std::ffi::c_void;
    use windows_sys::Win32::Graphics::Gdi::{
        DeleteObject, GetDC, GetDIBits, GetObjectW, ReleaseDC, BITMAP, BITMAPINFO, BITMAPINFOHEADER, BI_RGB,
        DIB_RGB_COLORS, HBITMAP,
    };
    use windows_sys::Win32::Storage::FileSystem::FILE_ATTRIBUTE_NORMAL;
    use windows_sys::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_APARTMENTTHREADED};
    use windows_sys::Win32::UI::Shell::{
        SHGetFileInfoW, SHFILEINFOW, SHGFI_ICON, SHGFI_LARGEICON, SHGFI_USEFILEATTRIBUTES,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{DestroyIcon, GetIconInfo, ICONINFO};

    /// The pixels of `bitmap`, at its own size, top row first.
    unsafe fn pixels(bitmap: HBITMAP) -> Option<(i32, i32, Vec<u8>)> {
        let mut size: BITMAP = std::mem::zeroed();
        if GetObjectW(bitmap, std::mem::size_of::<BITMAP>() as i32, &mut size as *mut BITMAP as *mut c_void) == 0 {
            return None;
        }
        let (width, height) = (size.bmWidth, size.bmHeight.abs());
        let mut header: BITMAPINFO = std::mem::zeroed();
        header.bmiHeader = BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: width,
            // Negative: from the top row down.
            biHeight: -height,
            biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB,
            ..std::mem::zeroed()
        };
        let mut out = vec![0u8; (width * height * 4) as usize];
        let dc = GetDC(std::ptr::null_mut());
        let lines = GetDIBits(dc, bitmap, 0, height as u32, out.as_mut_ptr().cast(), &mut header, DIB_RGB_COLORS);
        ReleaseDC(std::ptr::null_mut(), dc);
        (lines == height).then_some((width, height, out))
    }

    let name: Vec<u16> = format!("file.{extension}").encode_utf16().chain(Some(0)).collect();
    // SAFETY: calls of the shell and of GDI on structures and buffers of their own sizes; every icon and bitmap got is
    // freed, and the thread's COM is ended as it was begun.
    unsafe {
        let com = CoInitializeEx(std::ptr::null(), COINIT_APARTMENTTHREADED as u32);
        let mut info: SHFILEINFOW = std::mem::zeroed();
        let found = SHGetFileInfoW(
            name.as_ptr(),
            FILE_ATTRIBUTE_NORMAL,
            &mut info,
            std::mem::size_of::<SHFILEINFOW>() as u32,
            SHGFI_ICON | SHGFI_LARGEICON | SHGFI_USEFILEATTRIBUTES,
        );
        let mut icon = None;
        if found != 0 && !info.hIcon.is_null() {
            let mut parts: ICONINFO = std::mem::zeroed();
            if GetIconInfo(info.hIcon, &mut parts) != 0 {
                icon = pixels(parts.hbmColor).map(|(width, height, mut bgra)| {
                    if bgra.chunks(4).all(|p| p[3] == 0) {
                        if let Some((_, _, mask)) = pixels(parts.hbmMask) {
                            for (pixel, masked) in bgra.chunks_mut(4).zip(mask.chunks(4)) {
                                pixel[3] = if masked[0] == 0 { 255 } else { 0 };
                            }
                        }
                    }
                    let mut out = Vec::with_capacity(8 + bgra.len());
                    out.extend_from_slice(&(width as u32).to_le_bytes());
                    out.extend_from_slice(&(height as u32).to_le_bytes());
                    out.extend(bgra);
                    out
                });
                DeleteObject(parts.hbmColor);
                DeleteObject(parts.hbmMask);
            }
            DestroyIcon(info.hIcon);
        }
        if com >= 0 {
            CoUninitialize();
        }
        icon
    }
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

/// Whether quitting asks first: once the window listens, while the options want it asked.
fn asks_to_quit(shell: &Shell) -> bool {
    shell.asks.load(Ordering::Relaxed) && shell.settings().confirm_exit
}

/// What was chosen in the menu or MiniMule: the window shows itself; the rest is the window's to do, the options over
/// the window shown again, quitting too when it asks first.
fn tray_chosen(app: &AppHandle, id: &str) {
    let shell = app.state::<Shell>();
    match id {
        "show" => show_window(app),
        "quit" if !asks_to_quit(&shell) => app.exit(0),
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

/// A file of this computer chosen with the system's dialog, among those named `kind` with one of the `extensions`.
#[tauri::command]
async fn choose_file(
    app: AppHandle,
    title: String,
    kind: String,
    extensions: Vec<String>,
) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let extensions: Vec<&str> = extensions.iter().map(String::as_str).collect();
        let dialog = app.dialog().file().set_title(title);
        // Without extensions any file will do.
        let dialog = if extensions.is_empty() { dialog } else { dialog.add_filter(kind, &extensions) };
        dialog.blocking_pick_file().map(|file| file.to_string())
    })
    .await
    .map_err(|e| e.to_string())
}

/// Opens a folder in the system's file manager, or tells the system's reason it could not.
#[tauri::command]
fn open_folder(app: AppHandle, path: String) -> Result<(), String> {
    app.opener().open_path(path, None::<&str>).map_err(|e| e.to_string())
}

/// A WAV or MP3 file the window plays with a notification, read whole; a huge one is never read into memory.
#[tauri::command]
async fn sound(path: String) -> Result<Response, String> {
    let path = PathBuf::from(path);
    if !path.extension().is_some_and(|e| e.eq_ignore_ascii_case("wav") || e.eq_ignore_ascii_case("mp3")) {
        return Err(format!("{} is not a WAV or MP3 file", path.display()));
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

/// The addresses of the remote connections remembered, in order.
#[tauri::command]
fn remote_connections(app: AppHandle) -> Vec<String> {
    let mut addresses: Vec<_> = remembered(&app).into_keys().collect();
    addresses.sort();
    addresses
}

/// Opens njMule on another computer in a window of its own, once that window reached the other njMule's API, and
/// remembers the connection if asked; otherwise why it could not. An empty token is the one remembered.
#[tauri::command]
async fn open_remote(app: AppHandle, url: String, token: String, remember: bool) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let given = token.trim();
        let token = match given {
            "" => remembered(&app).remove(&url).ok_or_else(|| format!("No token for {url}."))?,
            _ => given.to_owned(),
        };
        open_window_on(&url, &token)?;
        if remember {
            remember_connection(&app, &url, &token)?;
        }
        Ok(())
    })
    .await
    .map_err(|e| e.to_string())?
}

/// The window on another njMule reached it, or could not: it shows, or njMule ends, telling why to the dialog that
/// opened it, or else to the user. The window on this computer's own engine has nothing to tell.
#[tauri::command]
fn connected(app: AppHandle, shell: State<'_, Shell>, problem: Option<String>) {
    if shell.remote.is_none() {
        return;
    }
    if shell.checked {
        let mut out = std::io::stdout();
        let _ = writeln!(out, "{CHECKED_LINE}{}", serde_json::to_string(&problem).unwrap_or_default());
        let _ = out.flush();
    }
    match problem {
        None => show_window(&app),
        Some(_) if shell.checked => app.exit(1),
        Some(problem) => {
            let handle = app.clone();
            app.dialog().message(problem).title("njMule").kind(MessageDialogKind::Error).show(move |_| handle.exit(1));
        }
    }
}

/// Starts the player of the options on the data of a download so far, its arguments apart by spaces, then the file;
/// the player runs on its own.
#[tauri::command]
fn preview(player: String, args: String, file: String) -> Result<(), String> {
    let program = player_program(&player);
    let mut child = Command::new(program)
        .args(args.split_whitespace())
        .arg(file)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("{program}: {e}"))?;
    std::thread::spawn(move || child.wait());
    Ok(())
}

/// The icon of the program that opens files with `extension` (see `system_icon`), on a thread of its own, which the
/// shell's COM wants; none but on Windows.
#[tauri::command]
async fn file_icon(extension: String) -> Result<Response, String> {
    if extension.is_empty() || extension.len() > 16 || !extension.chars().all(char::is_alphanumeric) {
        return Err(format!("{extension} is no extension"));
    }
    #[cfg(windows)]
    {
        let icon = tauri::async_runtime::spawn_blocking(move || {
            std::thread::spawn(move || system_icon(&extension)).join().ok().flatten()
        })
        .await
        .map_err(|e| e.to_string())?;
        icon.map(Response::new).ok_or_else(|| "The system has no icon for it.".into())
    }
    #[cfg(not(windows))]
    Err("The system's icons are read on Windows only.".into())
}

/// The text on the clipboard, empty when it holds none: read by the shell, as the web view asks the user before a page
/// reads it.
#[tauri::command]
async fn read_clipboard() -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(|| match arboard::Clipboard::new().and_then(|mut board| board.get_text()) {
        Ok(text) => Ok(text),
        Err(arboard::Error::ContentNotAvailable) => Ok(String::new()),
        Err(e) => Err(e.to_string()),
    })
    .await
    .map_err(|e| e.to_string())?
}

/// Opens a file with the program the system opens its kind with: false when the file is not there, or the system's
/// reason it could not.
#[tauri::command]
fn open_file(app: AppHandle, path: String) -> Result<bool, String> {
    if !Path::new(&path).is_file() {
        return Ok(false);
    }
    app.opener().open_path(path, None::<&str>).map(|_| true).map_err(|e| e.to_string())
}

/// Shows the folder of a file in the system's file manager, the file selected where it can: false when the file is
/// not there, or the system's reason it could not.
#[tauri::command]
fn reveal_file(app: AppHandle, path: String) -> Result<bool, String> {
    if !Path::new(&path).exists() {
        return Ok(false);
    }
    app.opener().reveal_item_in_dir(path).map(|_| true).map_err(|e| e.to_string())
}

/// The words of the application menu's own items (macOS), in the interface's language.
#[tauri::command]
fn app_menu_texts(shell: State<'_, Shell>, about: String, settings: String, quit: String) -> Result<(), String> {
    for (item, text) in shell.app_menu.lock().unwrap().iter().zip([about, settings, quit]) {
        item.set_text(text).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// The application menu of macOS: About, Settings and Quit are the window's to do; the system's items stay, with the
/// Edit menu whose shortcuts the fields need. In English until the window gives its words.
#[cfg(target_os = "macos")]
fn app_menu(app: &AppHandle) -> tauri::Result<(Menu<Wry>, Vec<MenuItem<Wry>>)> {
    use tauri::menu::Submenu;
    let name = app.package_info().name.clone();
    let about = MenuItem::with_id(app, "app-about", format!("About {name}"), true, None::<&str>)?;
    let settings = MenuItem::with_id(app, "app-settings", "Settings…", true, Some("CmdOrCtrl+,"))?;
    let quit = MenuItem::with_id(app, "app-quit", format!("Quit {name}"), true, Some("CmdOrCtrl+Q"))?;
    let separator = || PredefinedMenuItem::separator(app);
    let own = Submenu::with_items(
        app,
        &name,
        true,
        &[
            &about,
            &separator()?,
            &settings,
            &separator()?,
            &PredefinedMenuItem::services(app, None)?,
            &separator()?,
            &PredefinedMenuItem::hide(app, None)?,
            &PredefinedMenuItem::hide_others(app, None)?,
            &PredefinedMenuItem::show_all(app, None)?,
            &separator()?,
            &quit,
        ],
    )?;
    let edit = Submenu::with_items(
        app,
        "Edit",
        true,
        &[
            &PredefinedMenuItem::undo(app, None)?,
            &PredefinedMenuItem::redo(app, None)?,
            &separator()?,
            &PredefinedMenuItem::cut(app, None)?,
            &PredefinedMenuItem::copy(app, None)?,
            &PredefinedMenuItem::paste(app, None)?,
            &PredefinedMenuItem::select_all(app, None)?,
        ],
    )?;
    let window = Submenu::with_items(
        app,
        "Window",
        true,
        &[
            &PredefinedMenuItem::minimize(app, None)?,
            &PredefinedMenuItem::maximize(app, None)?,
            &separator()?,
            &PredefinedMenuItem::close_window(app, None)?,
        ],
    )?;
    Ok((Menu::with_items(app, &[&own, &edit, &window])?, vec![about, settings, quit]))
}

/// What an item of the application menu does, over the window shown: the window's to do (quitting asks first, once
/// the window listens).
#[cfg_attr(not(target_os = "macos"), allow(dead_code))]
fn app_menu_chosen(app: &AppHandle, id: &str) {
    match id {
        "quit" if !asks_to_quit(&app.state::<Shell>()) => app.exit(0),
        "about" | "settings" | "quit" => {
            show_window(app);
            let _ = app.emit_to("main", "app-menu", id);
        }
        _ => {}
    }
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

/// The page of a small window drew what it shows now, `width` × `height` CSS pixels each `ratio` pixels of the screen:
/// it opens where it was asked to.
#[tauri::command]
fn popup_ready(
    window: WebviewWindow,
    shell: State<'_, Shell>,
    width: f64,
    height: f64,
    ratio: f64,
) -> Result<(), String> {
    let at = shell.asked.lock().unwrap().remove(window.label());
    // What the web view grows the page by besides the screen's scale, such as the system's text size.
    let zoom =
        window.scale_factor().ok().filter(|scale| *scale > 0.0 && ratio > 0.0).map_or(1.0, |scale| ratio / scale);
    at.map_or(Ok(()), |at| place_popup(&window, at, width * zoom, height * zoom).map_err(|e| e.to_string()))
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

/// The plugins of the window on this computer's njMule alone: a second start hands its links to it and ends (first,
/// before anything else starts), and the system's links come to it. A window on another computer's njMule is a start
/// of its own, which leaves them to this computer's njMule.
fn single_window(builder: tauri::Builder<Wry>, local: bool) -> tauri::Builder<Wry> {
    if !local {
        return builder;
    }
    builder
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            hand_over(app, links(&args));
            show_window(app);
        }))
        .plugin(tauri_plugin_deep_link::init())
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let mut context = tauri::generate_context!();
    // A command, from the installer or the engine, runs before anything else: a window already open would take it.
    if let Some(verb) = verbs::verb(&args) {
        let code = match verb {
            Ok(verb) => verbs::run(verb, &context.package_info().version.to_string()),
            Err(e) => {
                verbs::fail(&format!("njMule was started wrongly: {e}"));
                2
            }
        };
        std::process::exit(code);
    }
    if let Some(dir) = config_dir(&args) {
        std::env::set_var("NJMULE_CONFIG_DIR", dir);
    }
    set_app_user_model_id();
    let remote = remote_url(&args);
    let checked = remote.is_some() && std::env::var_os(CHECK_VARIABLE).is_some();
    let engine = Arc::new(Engine::default());
    if remote.is_none() {
        engine.links.lock().unwrap().extend(links(&args));
    }
    if let Some(url) = &remote {
        allow_api(context.config_mut(), url);
    }
    // The window shows as the options say, and the small windows of the icon where they are asked for; a window on
    // another computer's njMule keeps a place and size of its own, apart from the one on this computer's.
    let window_state = match remote {
        None => tauri_plugin_window_state::Builder::default(),
        Some(_) => tauri_plugin_window_state::Builder::default().with_filename("remote-window-state.json"),
    }
    .with_state_flags(StateFlags::all() - StateFlags::VISIBLE)
    .with_denylist(&[TRAY_MENU, MINIMULE]);
    let shell = Shell { remote: remote.clone(), checked, ..Shell::default() };
    let app = single_window(tauri::Builder::default(), remote.is_none())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(window_state.build())
        .manage(engine.clone())
        .manage(shell)
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
            set_desktop_settings,
            remote_connections,
            open_remote,
            connected,
            preview,
            open_file,
            reveal_file,
            app_menu_texts,
            read_clipboard,
            file_icon
        ])
        .setup(move |app| {
            match &remote {
                Some(url) => engine.found(remote_endpoint(app.handle(), url)),
                None => start_engine(app.handle().clone(), engine.clone()),
            }
            let shell = app.state::<Shell>();
            if let Some(file) = settings_file(app.handle()) {
                *shell.settings.lock().unwrap() = load_settings(&file);
                let _ = shell.settings_file.set(file);
            }
            let window = app.get_webview_window("main").expect("njMule's window");
            window.set_title(&window_title(&app.package_info().version.to_string(), remote.as_deref()))?;
            // macOS hands links over as events rather than arguments.
            if remote.is_none() {
                let handle = app.handle().clone();
                app.deep_link().on_open_url(move |event| {
                    hand_over(&handle, event.urls().iter().map(|u| u.to_string()).collect());
                    show_window(&handle);
                });
            }
            #[cfg(target_os = "macos")]
            {
                let (menu, items) = app_menu(app.handle())?;
                app.set_menu(menu)?;
                *app.state::<Shell>().app_menu.lock().unwrap() = items;
                app.on_menu_event(|app, event| {
                    if let Some(id) = event.id.as_ref().strip_prefix("app-") {
                        app_menu_chosen(app, id);
                    }
                });
            }
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
            if checked {
                // Opened from a dialog, it shows once it reached the other njMule; the dialog waits for that.
            } else if !settings.start_minimized {
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
                if asks_to_quit(&shell) {
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
        .build(context)
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

    fn words(all: &[&str]) -> Vec<String> {
        all.iter().map(|w| w.to_string()).collect()
    }

    #[test]
    fn should_take_the_remote_api_from_the_command_line() {
        let named = remote_url(&words(&["njmule-gui", "--remote-url", "http://nas.local:4711/api/v1"]));
        let joined = remote_url(&words(&["njmule-gui", "--remote-url=https://nas.example.org/api/v1"]));

        assert_eq!(named.as_deref(), Some("http://nas.local:4711/api/v1"));
        assert_eq!(joined.as_deref(), Some("https://nas.example.org/api/v1"));
    }

    #[test]
    fn should_let_the_page_reach_the_other_api_besides_this_computers() {
        let mut config = tauri::Config::default();
        config.app.security.csp = Some(Csp::Policy("default-src 'self'; connect-src 'self' http://127.0.0.1:*".into()));

        allow_api(&mut config, "HTTPS://nas.example.org:8443/njmule/api/v1");

        let policy: HashMap<String, CspDirectiveSources> = config.app.security.csp.unwrap().into();
        assert_eq!(
            Vec::<String>::from(policy["connect-src"].clone()),
            ["'self'", "http://127.0.0.1:*", "https://nas.example.org:8443"]
        );
        assert_eq!(Vec::<String>::from(policy["default-src"].clone()), ["'self'"]);
    }

    #[test]
    fn should_take_a_player_copied_in_quotes_as_its_path() {
        assert_eq!(player_program(r#" "C:\Program Files\VLC\vlc.exe" "#), r"C:\Program Files\VLC\vlc.exe");
        assert_eq!(player_program("/usr/bin/mpv"), "/usr/bin/mpv");
    }

    #[test]
    fn should_read_the_connections_the_desktop_app_remembered() {
        let dir = std::env::temp_dir().join(format!("njmule-remotes-read-{}", std::process::id()));
        let file = dir.join(REMOTES_FILE);
        std::fs::create_dir_all(&dir).unwrap();
        let java = "#njMule remote connections\n#Sat Oct 31 15:00:00 CET 2026\n";
        std::fs::write(&file, format!("{java}{}\n", r"http\://nas.local\:4711/api/v1=Tok_en-1")).unwrap();

        let all = read_remotes(&file);

        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(all, HashMap::from([("http://nas.local:4711/api/v1".to_owned(), "Tok_en-1".to_owned())]));
    }

    #[test]
    fn should_remember_connections_as_the_desktop_app_reads_them() {
        let dir = std::env::temp_dir().join(format!("njmule-remotes-write-{}", std::process::id()));
        let file = dir.join("gui").join(REMOTES_FILE);
        let all = HashMap::from([
            ("http://nas.local:4711/api/v1".to_owned(), "first".to_owned()),
            ("https://nas.example.org/api/v1".to_owned(), "second".to_owned()),
        ]);

        write_remotes(&file, &all).unwrap();

        let text = std::fs::read_to_string(&file).unwrap();
        let again = read_remotes(&file);
        std::fs::remove_dir_all(&dir).unwrap();
        assert!(text.contains(r"http\://nas.local\:4711/api/v1=first"), "{text}");
        assert_eq!(again, all);
    }

    #[cfg(windows)]
    #[test]
    fn should_read_the_icon_windows_shows_for_a_kind_of_file() {
        let icon = system_icon("txt").expect("an icon for text files");
        then_the_icon_holds_its_pixels(&icon);
    }

    #[cfg(windows)]
    fn then_the_icon_holds_its_pixels(icon: &[u8]) {
        let width = u32::from_le_bytes(icon[0..4].try_into().unwrap()) as usize;
        let height = u32::from_le_bytes(icon[4..8].try_into().unwrap()) as usize;
        assert!(width > 0 && height > 0);
        assert_eq!(icon.len(), 8 + width * height * 4);
        assert!(icon[8..].chunks(4).any(|pixel| pixel[3] != 0), "a transparent icon");
    }
}
