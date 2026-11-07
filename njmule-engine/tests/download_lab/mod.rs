// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The downloads as the engine runs them, for the Java engine's download tests: a manager on a loop of its own that
//! connects where it asks to, hands it the links, their hellos and packets, the disk work and a tick every 100 ms, on a
//! clock the tests move. The server is a stand-in that records what it is asked.

#![allow(
    dead_code,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::{DownloadInfo, SourceInfo};
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::download::download_manager::{Action, DownloadEvent, DownloadManager, Hooks, ServerLink, Settings};
use njmule_engine::download::download_session;
use njmule_engine::files::part_file_storage::DiskDone;
use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connector::{self, Connection};
use njmule_engine::peer::peer_link::{self, LinkEvent, LinkHandle, LinkId};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{frame_limits, protocol};
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::sync::{mpsc, oneshot};

pub const TICK: Duration = Duration::from_millis(100);
pub const WAIT: Duration = Duration::from_secs(20);
pub const PORT: u16 = 4662;

/// The IP filter the downloads see, and the links of downloads deleted for receiving nothing.
#[derive(Default)]
pub struct Engine {
    pub blocked: Vec<u32>,
    pub keeps_links: bool,
    pub kept_links: Vec<String>,
}

fn hooks(engine: &Arc<Mutex<Engine>>) -> Hooks {
    let filter = Arc::clone(engine);
    let keeper = Arc::clone(engine);
    Hooks {
        blocked: Box::new(move |ip| filter.lock().unwrap().blocked.contains(&ip)),
        keep_link: Box::new(move |link| {
            let mut engine = keeper.lock().unwrap();
            if engine.keeps_links {
                engine.kept_links.push(link.to_uri());
            }
            engine.keeps_links
        }),
    }
}

/// What the downloads asked of the server, and what it answers.
#[derive(Default)]
pub struct FakeServer {
    pub client_id: u32,
    pub server_ip: u32,
    pub connected: bool,
    pub asked: Vec<[u8; 16]>,
    pub callbacks: Vec<u32>,
}

struct Server(Arc<Mutex<FakeServer>>);

impl ServerLink for Server {
    fn client_id(&self) -> u32 {
        self.0.lock().unwrap().client_id
    }

    fn server_ip(&self) -> u32 {
        self.0.lock().unwrap().server_ip
    }

    fn ask_sources(&mut self, file: &[u8; 16], _size: u64) -> bool {
        let mut server = self.0.lock().unwrap();
        if server.connected {
            server.asked.push(*file);
        }
        server.connected
    }

    fn request_callback(&mut self, low_id: u32) -> bool {
        let mut server = self.0.lock().unwrap();
        if server.connected {
            server.callbacks.push(low_id);
        }
        server.connected
    }
}

/// What the loop owns: the manager and what it works with.
pub struct Lab {
    pub manager: DownloadManager,
    pub ours: Hello,
    pub settings: Settings,
    pub events: Vec<DownloadEvent>,
    server: Server,
    links: HashMap<LinkId, LinkHandle>,
    greeted: HashMap<LinkId, bool>,
    next_link: LinkId,
    link_events: mpsc::UnboundedSender<LinkEvent>,
    connected: mpsc::UnboundedSender<(SocketAddr, std::io::Result<Connection>)>,
}

pub type Job = Box<dyn FnOnce(&mut Lab) + Send>;

impl Lab {
    /// Adds the download of `link`.
    pub fn add(&mut self, link: &FileLink) -> DownloadInfo {
        let ours = self.ours.clone();
        let info = self.manager.add(link, &self.settings, &ours).unwrap();
        self.after();
        info
    }

    /// The files the server was asked for the sources of, in order.
    pub fn server_asked(&self) -> Vec<[u8; 16]> {
        self.server.0.lock().unwrap().asked.clone()
    }

    pub fn tick(&mut self) {
        let ours = self.ours.clone();
        self.manager.tick(&mut self.server, &self.settings, &ours);
        self.after();
    }

    // Acts on what the downloads asked, as the network does.
    fn after(&mut self) {
        for action in self.manager.take_actions() {
            match action {
                Action::Connect { address, obfuscate_for } => {
                    let connected = self.connected.clone();
                    tokio::spawn(async move {
                        let mut random = StdRng::from_rng(&mut rand::rng());
                        let result = tcp_connector::connect_to_client(
                            address,
                            obfuscate_for,
                            download_session::HANDSHAKE_TIMEOUT,
                            &Routes::direct(),
                            &mut random,
                        )
                        .await;
                        let _ = connected.send((address, result));
                    });
                }
                Action::Resolve { file, hosts } => {
                    let found: Vec<SocketAddr> =
                        hosts.iter().filter_map(|host| format!("{}:{}", host.host, host.port).parse().ok()).collect();
                    let settings = self.settings.clone();
                    self.manager.resolved(&file, &found, &settings);
                }
            }
        }
        self.events.extend(self.manager.take_events());
    }

    fn connection(&mut self, address: SocketAddr, result: std::io::Result<Connection>) {
        let ours = self.ours.clone();
        match result {
            Ok(connection) => {
                let id = self.next_link;
                self.next_link += 1;
                let handle = peer_link::start(
                    id,
                    connection.into_tcp_connection(frame_limits::client_handshake),
                    self.link_events.clone(),
                );
                self.links.insert(id, handle.clone());
                self.manager.connected(address, Ok(handle), &ours);
            }
            Err(e) => self.manager.connected(address, Err(e.to_string()), &ours),
        }
        self.after();
    }

    fn event(&mut self, event: LinkEvent) {
        let ours = self.ours.clone();
        match event {
            LinkEvent::Packet(link, packet) => {
                if !self.greeted.contains_key(&link)
                    && (packet.protocol(), packet.opcode()) == (protocol::EDONKEY, client::HELLOANSWER)
                {
                    self.greeted.insert(link, true);
                    if let Ok(hello) = Hello::decode(&packet) {
                        self.manager.greeted(link, hello, &ours);
                    }
                } else if self.manager.has_link(link) {
                    self.manager.received(link, &packet, &ours);
                }
            }
            LinkEvent::Closed(link, reason) => {
                self.links.remove(&link);
                self.greeted.remove(&link);
                self.manager.closed(link, reason.as_deref(), &ours);
            }
        }
        self.after();
    }

    fn disk(&mut self, done: DiskDone) {
        let ours = self.ours.clone();
        self.manager.disk_done(done, &ours);
        self.after();
    }
}

/// The downloads on their loop, and what the tests reach them through.
pub struct Downloads {
    pub dir: tempfile::TempDir,
    pub server: Arc<Mutex<FakeServer>>,
    pub engine: Arc<Mutex<Engine>>,
    offset: Arc<AtomicU64>,
    jobs: mpsc::UnboundedSender<Job>,
    serving: Option<tokio::task::JoinHandle<()>>,
    user_hash: [u8; 16],
    our_id: u32,
}

impl Downloads {
    /// The downloads of a client with `our_id` (an IP in the order the network gives it) with `settings`.
    pub fn start(user_hash: [u8; 16], our_id: u32, settings: Settings) -> Self {
        Self::start_with(user_hash, our_id, settings, Engine::default())
    }

    /// As [`start`](Self::start), with the IP filter and the keeping of links of `engine`.
    pub fn start_with(user_hash: [u8; 16], our_id: u32, settings: Settings, engine: Engine) -> Self {
        let dir = tempfile::tempdir().unwrap();
        let server = Arc::new(Mutex::new(FakeServer { client_id: our_id, ..FakeServer::default() }));
        let engine = Arc::new(Mutex::new(engine));
        let offset = Arc::new(AtomicU64::new(0));
        let (jobs, serving) = open(dir.path(), user_hash, our_id, settings, &server, &engine, &offset);
        Self { dir, server, engine, offset, jobs, serving: Some(serving), user_hash, our_id }
    }

    /// Closes the downloads, which saves them, and opens them again from their files.
    pub async fn restart(&mut self) {
        let settings = self.run(|lab| lab.settings.clone()).await;
        // The loop closes the downloads once it has no job left to wait for.
        drop(std::mem::replace(&mut self.jobs, mpsc::unbounded_channel().0));
        if let Some(old) = self.serving.take() {
            old.await.unwrap();
        }
        let (jobs, serving) =
            open(self.dir.path(), self.user_hash, self.our_id, settings, &self.server, &self.engine, &self.offset);
        self.jobs = jobs;
        self.serving = Some(serving);
    }

    /// Settings as most Java tests give them: obfuscation supported, 100 sources a file, LAN sources left out.
    pub fn settings() -> Settings {
        Settings {
            obfuscation: ObfuscationMode::Supported,
            max_sources_per_file: 100,
            listen_port: PORT,
            filter_lan: true,
            clean_up_new_names: false,
            name_cleanups: String::new(),
            auto_delete_days: 0,
        }
    }

    /// Moves the downloads' clock on by `by`.
    pub fn advance(&self, by: Duration) {
        self.offset.fetch_add(u64::try_from(by.as_millis()).unwrap(), Ordering::Relaxed);
    }

    pub async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        let (answer, answered) = oneshot::channel();
        let job: Job = Box::new(move |lab| {
            let _ = answer.send(work(lab));
        });
        self.jobs.send(job).ok().unwrap();
        answered.await.unwrap()
    }

    pub async fn add(&self, link: FileLink) -> DownloadInfo {
        self.run(move |lab| lab.add(&link)).await
    }

    pub async fn sources(&self) -> Vec<SourceInfo> {
        self.run(|lab| lab.manager.sources()).await
    }

    /// Waits until `condition` holds of the downloads.
    pub async fn wait_for(&self, what: &str, condition: impl Fn(&mut Lab) -> bool + Send + Sync + Copy + 'static) {
        let deadline = Instant::now() + WAIT;
        while !self.run(condition).await {
            assert!(Instant::now() < deadline, "waited in vain for {what}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

// A manager on the files of `dir`, on a loop of its own.
fn open(
    dir: &std::path::Path,
    user_hash: [u8; 16],
    our_id: u32,
    settings: Settings,
    server: &Arc<Mutex<FakeServer>>,
    engine: &Arc<Mutex<Engine>>,
    offset: &Arc<AtomicU64>,
) -> (mpsc::UnboundedSender<Job>, tokio::task::JoinHandle<()>) {
    let base = Instant::now();
    let ticks = Arc::clone(offset);
    let clock = Box::new(move || base + Duration::from_millis(ticks.load(Ordering::Relaxed)));
    let (disk_in, disk) = mpsc::unbounded_channel();
    let manager =
        DownloadManager::open(&dir.join("temp"), &dir.join("incoming"), Box::new(|| 0), disk_in, clock, hooks(engine))
            .unwrap();
    let ours = Ours { nick: "test", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    let (link_events, links) = mpsc::unbounded_channel();
    let (connected_in, connected) = mpsc::unbounded_channel();
    let (jobs_in, jobs) = mpsc::unbounded_channel();
    let lab = Lab {
        manager,
        ours: Hello::of(user_hash, our_id, settings.listen_port, &ours, 0, 0),
        settings,
        events: Vec::new(),
        server: Server(Arc::clone(server)),
        links: HashMap::new(),
        greeted: HashMap::new(),
        next_link: 1,
        link_events,
        connected: connected_in,
    };
    (jobs_in, tokio::spawn(serve(lab, jobs, links, connected, disk)))
}

async fn serve(
    mut lab: Lab,
    mut jobs: mpsc::UnboundedReceiver<Job>,
    mut links: mpsc::UnboundedReceiver<LinkEvent>,
    mut connected: mpsc::UnboundedReceiver<(SocketAddr, std::io::Result<Connection>)>,
    mut disk: mpsc::UnboundedReceiver<DiskDone>,
) {
    let mut every = tokio::time::interval(TICK);
    loop {
        tokio::select! {
            Some(event) = links.recv() => lab.event(event),
            Some((address, result)) = connected.recv() => lab.connection(address, result),
            Some(done) = disk.recv() => lab.disk(done),
            _ = every.tick() => lab.tick(),
            job = jobs.recv() => match job {
                // What the job had the downloads ask for is done, and what they told is kept.
                Some(job) => {
                    job(&mut lab);
                    lab.after();
                }
                None => {
                    let ours = lab.ours.clone();
                    lab.manager.close(&ours);
                    return;
                }
            },
        }
    }
}
