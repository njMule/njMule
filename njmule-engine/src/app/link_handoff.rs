// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Hands ed2k links to the njMule that runs with the same configuration, so that a second start (a link clicked in a
//! browser) passes them on instead of failing on the instance lock. The running one listens on a loopback port and
//! writes it, with a random secret, into the configuration directory; only who can read that file can hand over links.

use std::fs::{self, OpenOptions, TryLockError};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use rand::Rng;
use subtle::ConstantTimeEq;

use crate::engine::instance_lock;

/// What a later start hands over, instead of links, to have the running njMule exit.
pub const EXIT: &str = "njmule:exit";

/// The file in the configuration directory with the port and the secret.
pub const FILE_NAME: &str = "handoff";

/// Maximum links accepted from a single later start.
pub const MAX_LINKS: usize = 256;

/// Maximum length of a single line in a handoff exchange.
pub const MAX_LINE: usize = 64 * 1024;

/// How long a later start may take to hand over its links.
pub const DEADLINE: Duration = Duration::from_secs(10);

const POLL: Duration = Duration::from_millis(100);
const TIMEOUT: Duration = Duration::from_secs(5);
const OK: &str = "OK";
// After OK, from a receiver without a window; an older start reads the first line only.
const WINDOWLESS: &str = "headless";

/// Who took what a later start handed over.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Taker {
    /// Nobody: no njMule runs with the configuration, and the later start may.
    None,
    /// A njMule with a window, which shows itself.
    Window,
    /// A njMule without a window, which has nothing to show.
    Headless,
}

type Receiver = Arc<dyn Fn(Vec<String>) + Send + Sync>;

/// A running handoff listener for a profile's configuration directory.
pub struct LinkHandoff {
    address: SocketAddr,
    file: PathBuf,
    content: String,
    closed: Arc<AtomicBool>,
    accept: Option<JoinHandle<()>>,
}

impl LinkHandoff {
    /// Gives `links`, maybe none, to the instance running with `config_dir`, which shows itself; `false` when none
    /// listens, and then this process may start.
    pub fn deliver<S: AsRef<str>>(config_dir: &Path, links: &[S]) -> bool {
        Self::hand_over(config_dir, links) != Taker::None
    }

    /// As [`LinkHandoff::deliver`], telling who took the links: a later desktop start has nothing to show for a
    /// headless one.
    pub fn hand_over<S: AsRef<str>>(config_dir: &Path, links: &[S]) -> Taker {
        let Ok(raw) = fs::read_to_string(config_dir.join(FILE_NAME)) else {
            return Taker::None;
        };
        let fields: Vec<&str> = raw.trim().split(' ').collect();
        let [port_text, secret] = fields.as_slice() else {
            return Taker::None;
        };
        let Ok(port) = port_text.parse::<u16>() else {
            return Taker::None;
        };
        if port == 0 {
            return Taker::None;
        }
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
        let Ok(mut client) = TcpStream::connect_timeout(&target, TIMEOUT) else {
            // A file left by a crash: nobody listens there any more.
            return Taker::None;
        };
        if client.set_read_timeout(Some(TIMEOUT)).is_err() || client.set_write_timeout(Some(TIMEOUT)).is_err() {
            return Taker::None;
        }
        let mut payload = format!("{secret}\n");
        for link in links {
            let line = link.as_ref().trim();
            // One link per line: an empty one would end the list, a line break would add a line of its own.
            if line.is_empty() || line.contains(['\n', '\r']) {
                tracing::warn!("Not handing over an empty link or one with a line break");
                continue;
            }
            payload.push_str(line);
            payload.push('\n');
        }
        payload.push('\n');
        if client.write_all(payload.as_bytes()).is_err() || client.flush().is_err() {
            return Taker::None;
        }
        let mut reader = BufReader::new(client);
        let mut first = String::new();
        if reader.read_line(&mut first).is_err() || first.trim_end_matches(['\r', '\n']) != OK {
            return Taker::None;
        }
        let mut second = String::new();
        let _ = reader.read_line(&mut second);
        if second.trim_end_matches(['\r', '\n']) == WINDOWLESS { Taker::Headless } else { Taker::Window }
    }

    /// Waits until the instance running with `config_dir` has stopped; `false` if it still runs.
    pub fn await_gone(config_dir: &Path, timeout: Duration) -> bool {
        let deadline = Instant::now().checked_add(timeout).unwrap_or_else(Instant::now);
        while !gone(config_dir) {
            if Instant::now() >= deadline {
                return false;
            }
            thread::sleep(POLL);
        }
        true
    }

    /// Listens for later starts; `receiver` gets the links of each, maybe none, or [`EXIT`], on a thread of each.
    pub fn listen<F>(config_dir: &Path, receiver: F) -> io::Result<Self>
    where
        F: Fn(Vec<String>) + Send + Sync + 'static,
    {
        Self::listen_headless(config_dir, false, receiver)
    }

    /// As [`LinkHandoff::listen`], for a njMule that runs `headless`, which later starts are told.
    pub fn listen_headless<F>(config_dir: &Path, headless: bool, receiver: F) -> io::Result<Self>
    where
        F: Fn(Vec<String>) + Send + Sync + 'static,
    {
        let mut secret = [0u8; 32];
        rand::rng().fill_bytes(&mut secret);
        let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))?;
        let address = listener.local_addr()?;
        let content = format!("{} {}\n", address.port(), data_encoding::HEXLOWER.encode(&secret));
        let file = config_dir.join(FILE_NAME);
        write_owner_only(&file, content.as_bytes())?;
        let closed = Arc::new(AtomicBool::new(false));
        let accept_closed = Arc::clone(&closed);
        let receiver: Receiver = Arc::new(receiver);
        let accept = thread::Builder::new().name("njmule-handoff".to_owned()).spawn(move || {
            accept_loop(listener, secret, headless, receiver, accept_closed);
        })?;
        Ok(Self { address, file, content, closed, accept: Some(accept) })
    }

    /// Stops listening and removes the handoff file if it still belongs to this listener.
    pub fn close(&mut self) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        // Wake the blocking accept() loop so the listener closes right away.
        let _ = TcpStream::connect_timeout(&self.address, Duration::from_millis(200));
        if let Some(handle) = self.accept.take() {
            let _ = handle.join();
        }
        // A later instance may have written its own file meanwhile.
        if fs::read_to_string(&self.file).ok().as_deref() == Some(self.content.as_str()) {
            let _ = fs::remove_file(&self.file);
        }
    }
}

impl Drop for LinkHandoff {
    fn drop(&mut self) {
        self.close();
    }
}

// An instance holds the engine's lock of its configuration until it has saved and stopped.
fn gone(config_dir: &Path) -> bool {
    let lock = config_dir.join(instance_lock::FILE_NAME);
    match OpenOptions::new().write(true).open(&lock) {
        Ok(file) => match file.try_lock() {
            Ok(()) => {
                let _ = file.unlock();
                true
            }
            Err(TryLockError::WouldBlock) => false,
            Err(TryLockError::Error(_)) => !config_dir.join(FILE_NAME).exists(),
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => true,
        // The handoff file goes first when an instance stops.
        Err(_) => !config_dir.join(FILE_NAME).exists(),
    }
}

fn accept_loop(listener: TcpListener, secret: [u8; 32], headless: bool, receiver: Receiver, closed: Arc<AtomicBool>) {
    while !closed.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((client, _)) => {
                if closed.load(Ordering::SeqCst) {
                    break;
                }
                let receiver = Arc::clone(&receiver);
                let closed = Arc::clone(&closed);
                let _ = thread::Builder::new().name("njmule-handoff-client".to_owned()).spawn(move || {
                    if let Err(e) = serve(client, &secret, headless, &receiver)
                        && !closed.load(Ordering::SeqCst)
                    {
                        tracing::debug!("Handoff connection failed: {e}");
                    }
                });
            }
            Err(e) => {
                if !closed.load(Ordering::SeqCst) {
                    tracing::debug!("Handoff accept failed: {e}");
                }
            }
        }
    }
}

fn serve(mut client: TcpStream, secret: &[u8; 32], headless: bool, receiver: &Receiver) -> io::Result<()> {
    let deadline = Instant::now().checked_add(DEADLINE).unwrap_or_else(Instant::now);
    let token = read_line(&mut client, deadline)?;
    let presented = token
        .as_deref()
        .and_then(|hex| data_encoding::HEXLOWER_PERMISSIVE.decode(hex.as_bytes()).ok())
        .unwrap_or_default();
    if !bool::from(presented.as_slice().ct_eq(secret.as_slice())) {
        tracing::warn!("Refused links from a local program without the handoff secret");
        return Ok(());
    }
    let mut links = Vec::new();
    let mut lines: usize = 0;
    while let Some(line) = read_line(&mut client, deadline)? {
        if line.is_empty() {
            break;
        }
        lines = lines.saturating_add(1);
        let lower = line.to_ascii_lowercase();
        if links.len() < MAX_LINKS && (line == EXIT || lower.starts_with("ed2k://") || lower.starts_with("magnet:?")) {
            links.push(line);
        }
    }
    // Only a start that hands over nothing else asks this instance to exit.
    if lines > 1 {
        links.retain(|link| link != EXIT);
    }
    if catch_unwind(AssertUnwindSafe(|| receiver(links))).is_err() {
        // The links reached this instance all the same: the later start must not run beside it.
        tracing::warn!("Cannot follow the links handed over");
    }
    let reply = if headless { format!("{OK}\n{WINDOWLESS}\n") } else { format!("{OK}\n") };
    client.write_all(reply.as_bytes())?;
    client.flush()
}

// A line of at most MAX_LINE bytes before the deadline; longer or later ones end the exchange.
fn read_line(client: &mut TcpStream, deadline: Instant) -> io::Result<Option<String>> {
    let mut bytes = Vec::new();
    let mut single = [0u8; 1];
    loop {
        let Some(left) = deadline.checked_duration_since(Instant::now()) else {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "handoff too slow"));
        };
        if left.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "handoff too slow"));
        }
        client.set_read_timeout(Some(left.max(Duration::from_millis(1))))?;
        let count = client.read(&mut single)?;
        if count == 0 {
            if bytes.is_empty() {
                return Ok(None);
            }
            return Ok(Some(String::from_utf8_lossy(&bytes).trim().to_owned()));
        }
        let [byte] = single;
        if byte == b'\n' {
            return Ok(Some(String::from_utf8_lossy(&bytes).trim().to_owned()));
        }
        if bytes.len() >= MAX_LINE {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "handoff line too long"));
        }
        bytes.push(byte);
    }
}

// Only its owner may read the secret: owner-only permissions where the file system has them.
fn write_owner_only(file: &Path, content: &[u8]) -> io::Result<()> {
    let absolute = std::path::absolute(file)?;
    if let Some(dir) = absolute.parent() {
        fs::create_dir_all(dir)?;
    }
    let tmp = file.with_file_name(format!("{FILE_NAME}.tmp"));
    let _ = fs::remove_file(&tmp);
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut out = options.open(&tmp)?;
    out.write_all(content)?;
    out.flush()?;
    drop(out);
    fs::rename(&tmp, file)
}
