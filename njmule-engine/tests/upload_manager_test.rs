// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UploadManagerTest, under its names: clients on the loopback address asking for our files, the
//! uploads served by a loop of their own with a clock the tests move.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::files::shared_files::SharedFile;
use njmule_engine::net::tcp_connection::{TcpConnection, read_packet, write_packet};
use njmule_engine::peer::peer_link::{self, LinkEvent, LinkHandle, LinkId};
use njmule_engine::protocol::ed2k::client_messages::{self, Range, RequestParts, SendingPart};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use njmule_engine::upload::credits::Credits;
use njmule_engine::upload::upload_manager::{self, BlockRead, Settings, UploadManager};
use njmule_engine::upload::upload_session::MAX_BLOCK;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};

const WAIT: Duration = Duration::from_secs(20);
const OURS: [u8; 16] = [0xAB; 16];

type Job = Box<dyn FnOnce(&mut Loop) + Send>;

// The uploads and what they serve, on their own task.
struct Loop {
    manager: UploadManager,
    links: HashMap<LinkId, (LinkHandle, Option<Hello>)>,
    shared: Arc<Mutex<HashMap<[u8; 16], SharedFile>>>,
    settings: Arc<Mutex<Settings>>,
    base: Instant,
    offset: Arc<Mutex<Duration>>,
    ours: Hello,
    reads: Option<mpsc::UnboundedReceiver<BlockRead>>,
}

impl Loop {
    fn now(&self) -> Instant {
        self.base + *self.offset.lock().unwrap()
    }

    fn event(&mut self, event: LinkEvent) {
        let now = self.now();
        match event {
            LinkEvent::Packet(link, packet) => {
                let Some((handle, hello)) = self.links.get_mut(&link) else {
                    return;
                };
                let Some(peer) = hello.clone() else {
                    if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::HELLO {
                        *hello = Some(Hello::decode(&packet).unwrap());
                        handle.send(self.ours.encode(true).unwrap());
                    }
                    return;
                };
                let handle = handle.clone();
                let shared = Arc::clone(&self.shared);
                let find = move |hash: &[u8; 16]| shared.lock().unwrap().get(hash).cloned();
                let ours = self.ours.clone();
                self.manager.received(&handle, &peer, true, &packet, &ours, &find, &|| None, now);
            }
            LinkEvent::Closed(link, _) => {
                self.links.remove(&link);
                self.manager.closed(link);
            }
        }
    }

    fn tick(&mut self) {
        let now = self.now();
        let settings = *self.settings.lock().unwrap();
        let shared = Arc::clone(&self.shared);
        self.manager.tick(settings, &move |hash: &[u8; 16]| shared.lock().unwrap().get(hash).cloned(), now);
    }
}

async fn serve(listener: TcpListener, mut state: Loop, mut jobs: mpsc::UnboundedReceiver<Job>) {
    let (events_in, mut events) = mpsc::unbounded_channel();
    let mut reads = state.reads.take().unwrap();
    let mut next: LinkId = 1;
    let mut every = tokio::time::interval(upload_manager::TICK);
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let Ok((stream, remote)) = accepted else {
                    return;
                };
                let local = stream.local_addr().ok().map(|address| address.ip());
                let (input, output) = stream.into_split();
                let connection = TcpConnection::over(input, output, remote, local, frame_limits::client_handshake);
                let handle = peer_link::start(next, connection, events_in.clone());
                state.links.insert(next, (handle, None));
                next += 1;
            }
            Some(event) = events.recv() => state.event(event),
            Some(read) = reads.recv() => {
                let now = state.now();
                state.manager.block_read(read, now);
            }
            _ = every.tick() => state.tick(),
            job = jobs.recv() => match job {
                Some(job) => job(&mut state),
                None => {
                    state.manager.close();
                    return;
                }
            },
        }
    }
}

// A client on the loopback address that says hello to us and then says what the test makes it say.
struct Downloader {
    input: OwnedReadHalf,
    output: OwnedWriteHalf,
}

impl Downloader {
    async fn connect(port: u16, hello: &Hello) -> Self {
        let stream = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, port))).await.unwrap();
        let (input, output) = stream.into_split();
        let mut client = Self { input, output };
        client.send(&hello.encode(false).unwrap()).await;
        client.until(protocol::EDONKEY, client::HELLOANSWER).await;
        client
    }

    async fn send(&mut self, packet: &Packet) {
        write_packet(&mut self.output, packet).await.unwrap();
    }

    async fn next(&mut self) -> Packet {
        tokio::time::timeout(WAIT, read_packet(&mut self.input, &frame_limits::client))
            .await
            .expect("a packet in time")
            .unwrap()
            .expect("the connection open")
    }

    // The packets up to the first of `opcode`, that one included.
    async fn until(&mut self, protocol: u8, opcode: u8) -> Vec<Packet> {
        let mut seen = Vec::new();
        loop {
            let packet = self.next().await;
            let found = packet.protocol() == protocol && packet.opcode() == opcode;
            seen.push(packet);
            if found {
                return seen;
            }
        }
    }
}

struct Uploads {
    jobs: mpsc::UnboundedSender<Job>,
    port: u16,
    offset: Arc<Mutex<Duration>>,
    settings: Arc<Mutex<Settings>>,
    shared: Arc<Mutex<HashMap<[u8; 16], SharedFile>>>,
    priorities: Arc<Mutex<HashMap<[u8; 16], UploadPriority>>>,
    dir: tempfile::TempDir,
    clients: HashMap<u8, Downloader>,
    files: HashMap<String, SharedFile>,
    file: Option<SharedFile>,
}

impl Uploads {
    fn new() -> Self {
        let (jobs, _) = mpsc::unbounded_channel();
        Self {
            jobs,
            port: 0,
            offset: Arc::default(),
            settings: Arc::new(Mutex::new(Settings { max_upload_kibps: 0, queue_size: 100 })),
            shared: Arc::default(),
            priorities: Arc::default(),
            dir: tempfile::tempdir().unwrap(),
            clients: HashMap::new(),
            files: HashMap::new(),
            file: None,
        }
    }

    async fn on_loop<T: Send + 'static>(&self, work: impl FnOnce(&mut Loop) -> T + Send + 'static) -> T {
        let (answer, answered) = oneshot::channel();
        let job: Job = Box::new(move |state| {
            let _ = answer.send(work(state));
        });
        assert!(self.jobs.send(job).is_ok(), "the loop running");
        answered.await.unwrap()
    }

    // Steps.

    fn given_the_settings(&self, max_upload_kibps: u32, queue_size: usize) {
        *self.settings.lock().unwrap() = Settings { max_upload_kibps, queue_size };
    }

    fn given_the_shared_file(&mut self, name: &str, size: usize, seed: u8) {
        let path = self.dir.path().join(name);
        let data: Vec<u8> = (0..size).map(|at| (at % 251) as u8 ^ seed).collect();
        std::fs::write(&path, data).unwrap();
        let file = SharedFile {
            hash: [0xF0 ^ seed; 16],
            name: name.to_owned(),
            size: size as u64,
            path,
            mtime: 0,
            part_hashes: Vec::new(),
            aich_hash: None,
        };
        self.shared.lock().unwrap().insert(file.hash, file.clone());
        self.files.insert(name.to_owned(), file.clone());
        self.file = Some(file);
    }

    fn given_the_priority_of(&self, name: &str, priority: UploadPriority) {
        self.priorities.lock().unwrap().insert(self.files[name].hash, priority);
    }

    async fn given_the_uploads_started(&mut self) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        self.port = listener.local_addr().unwrap().port();
        let (reads_in, reads) = mpsc::unbounded_channel::<BlockRead>();
        let credits = Credits::load(self.dir.path(), Box::new(|| 1_790_000_000));
        let mut manager = UploadManager::new(reads_in, credits, *self.settings.lock().unwrap());
        let priorities = Arc::clone(&self.priorities);
        manager.set_priorities(move |hash| priorities.lock().unwrap().get(hash).copied().unwrap_or_default());
        let ours = Hello::of(OURS, 0, 4662, &plain("test"), 0, 0);
        let state = Loop {
            manager,
            links: HashMap::new(),
            shared: Arc::clone(&self.shared),
            settings: Arc::clone(&self.settings),
            base: Instant::now(),
            offset: Arc::clone(&self.offset),
            ours,
            reads: Some(reads),
        };
        let (jobs, jobs_out) = mpsc::unbounded_channel();
        self.jobs = jobs;
        tokio::spawn(serve(listener, state, jobs_out));
    }

    async fn given_the_clients_connected(&mut self, seeds: &[u8]) {
        for seed in seeds {
            self.client_of(*seed, 0).await;
        }
    }

    async fn given_a_firewalled_client_connected(&mut self, seed: u8) {
        self.client_of(seed, 0x00AB_CDEF).await;
    }

    async fn given_the_client_got_a_slot(&mut self, seed: u8) {
        let file = self.file.as_ref().unwrap().hash;
        assert_eq!(self.ask(seed, file).await.opcode(), client::ACCEPTUPLOADREQ);
    }

    async fn given_the_client_waits_at(&mut self, seed: u8, place: u32, why: &str) {
        let file = self.file.as_ref().unwrap().hash;
        self.given_the_client_waits_for_at(seed, file, place, why).await;
    }

    async fn given_the_client_waits_for_at(&mut self, seed: u8, file: [u8; 16], place: u32, why: &str) {
        let answer = self.ask(seed, file).await;
        assert_eq!(answer.opcode(), extended::QUEUERANKING, "{why}");
        assert_eq!(client_messages::decode_queue_rank(&answer).unwrap(), place, "{why}");
    }

    async fn given_the_client_asked_for(&mut self, seed: u8, name: &str) {
        let file = self.files[name].hash;
        self.ask(seed, file).await;
    }

    // Slots kept busy by the limit, so they are never idle while the clock moves.
    async fn given_a_client_keeping_a_slot_busy_with(&mut self, seed: u8, name: &str) {
        let file = self.files[name].hash;
        self.ask(seed, file).await;
        let request = blocks(file, &[Range { start: 0, end: MAX_BLOCK }]);
        self.clients.get_mut(&seed).unwrap().send(&request).await;
    }

    async fn given_the_queue_described_as(&self, start: &str, part: &str) {
        let described = self.on_loop(|state| state.manager.describe()).await;
        assert!(described.starts_with(start) && described.contains(part), "{described}");
    }

    async fn given_the_clients_waiting_are(&self, count: usize) {
        assert_eq!(self.on_loop(|state| state.manager.waiting_count()).await, count);
    }

    async fn given_the_files_waited_for_are(&self, names: &[&str]) {
        assert_eq!(self.waiting_files().await, names);
    }

    fn given_the_client_left(&mut self, seed: u8) {
        self.clients.remove(&seed);
    }

    fn given_time_passed(&self, duration: Duration) {
        *self.offset.lock().unwrap() += duration;
    }

    // Actions.

    async fn when_the_client_downloads_the_whole_file(&mut self, seed: u8) {
        let file = self.file.clone().unwrap();
        let count = file.size / MAX_BLOCK;
        let client = self.clients.get_mut(&seed).unwrap();
        for at in (0..count).step_by(3) {
            let ranges: Vec<Range> = (at..(at + 3).min(count))
                .map(|block| Range { start: block * MAX_BLOCK, end: (block + 1) * MAX_BLOCK })
                .collect();
            client.send(&blocks(file.hash, &ranges)).await;
        }
        let mut received = 0u64;
        while received < file.size {
            let packet = client.next().await;
            if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::SENDINGPART {
                received += SendingPart::decode(&packet).unwrap().data.len() as u64;
            }
        }
    }

    async fn when_the_client_asks_for(&mut self, seed: u8, name: &str) -> Packet {
        let file = self.files[name].hash;
        self.ask(seed, file).await
    }

    fn when_time_passes(&self, duration: Duration) {
        self.given_time_passed(duration);
    }

    // Outcomes.

    async fn then_the_client_is_told_in_turn(&mut self, seed: u8, why: &str, told: &[&str]) {
        let seen = self.clients.get_mut(&seed).unwrap().until(protocol::EDONKEY, client::ACCEPTUPLOADREQ).await;
        let names: Vec<&str> = seen.iter().filter_map(name).collect();
        let mut wanted = told.iter().peekable();
        for name in &names {
            if wanted.peek() == Some(&name) {
                wanted.next();
            }
        }
        assert!(wanted.peek().is_none(), "{why}: {names:?}");
    }

    async fn then_the_bytes_uploaded_are(&self, expected: u64) {
        assert_eq!(self.on_loop(|state| state.manager.uploaded()).await, expected);
    }

    async fn then_the_clients_waiting_are(&self, count: usize) {
        self.given_the_clients_waiting_are(count).await;
    }

    async fn then_the_queue_empties(&self, what: &str) {
        let deadline = Instant::now() + WAIT;
        let mut waiting = usize::MAX;
        while waiting > 0 && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
            waiting = self.on_loop(|state| state.manager.waiting_count()).await;
        }
        assert_eq!(waiting, 0, "{what}");
    }

    fn then_it_waits_at(answer: &Packet, place: u32) {
        assert_eq!(answer.opcode(), extended::QUEUERANKING);
        assert_eq!(client_messages::decode_queue_rank(answer).unwrap(), place);
    }

    async fn then_the_files_waited_for_are(&self, names: &[&str]) {
        self.given_the_files_waited_for_are(names).await;
    }

    // Helpers.

    async fn client_of(&mut self, seed: u8, client_id: u32) -> &mut Downloader {
        if !self.clients.contains_key(&seed) {
            let nick = format!("client {seed}");
            let hello = Hello::of([seed; 16], client_id, 5000 + u16::from(seed), &plain(&nick), 0, 0);
            let client = Downloader::connect(self.port, &hello).await;
            self.clients.insert(seed, client);
        }
        self.clients.get_mut(&seed).unwrap()
    }

    // The file asked for, then an upload of it: a slot or a place in the queue.
    async fn ask(&mut self, seed: u8, file: [u8; 16]) -> Packet {
        let client = self.client_of(seed, 0).await;
        client.send(&client_messages::for_file(client::SETREQFILEID, &file)).await;
        client.until(protocol::EDONKEY, client::FILESTATUS).await;
        client.send(&client_messages::for_file(client::STARTUPLOADREQ, &file)).await;
        loop {
            let packet = client.next().await;
            let slot = packet.protocol() == protocol::EDONKEY && packet.opcode() == client::ACCEPTUPLOADREQ;
            let rank = packet.protocol() == protocol::EMULE && packet.opcode() == extended::QUEUERANKING;
            if slot || rank {
                return packet;
            }
        }
    }

    async fn waiting_files(&self) -> Vec<String> {
        let names: HashMap<[u8; 16], String> = self.files.values().map(|file| (file.hash, file.name.clone())).collect();
        let now = self.on_loop(|state| state.manager.uploads(state.now())).await;
        now.into_iter().filter(|upload| upload.sent.is_none()).map(|upload| names[&upload.file].clone()).collect()
    }
}

fn plain(nick: &str) -> Ours<'_> {
    Ours { nick, udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 }
}

fn blocks(file: [u8; 16], ranges: &[Range]) -> Packet {
    let mut three = [Range::default(); 3];
    three[..ranges.len()].copy_from_slice(ranges);
    RequestParts { file, ranges: three }.encode(false).unwrap()
}

fn name(packet: &Packet) -> Option<&'static str> {
    match (packet.protocol(), packet.opcode()) {
        (protocol::EDONKEY, client::OUTOFPARTREQS) => Some("OUTOFPARTREQS"),
        (protocol::EMULE, extended::QUEUERANKING) => Some("QUEUERANKING"),
        (protocol::EDONKEY, client::ACCEPTUPLOADREQ) => Some("ACCEPTUPLOADREQ"),
        _ => None,
    }
}

#[tokio::test]
async fn should_give_a_slot_back_to_the_queue_after_about_a_part_while_others_wait() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("big.bin", 18 * MAX_BLOCK as usize, 1);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2]).await;
    uploads.given_a_firewalled_client_connected(3).await;
    uploads.given_the_client_got_a_slot(1).await;
    uploads.given_the_client_got_a_slot(2).await;
    uploads.given_the_client_waits_at(3, 1, "both slots taken").await;
    uploads.given_the_queue_described_as("waiting 1", "slot").await;
    // The firewalled client leaves the queue's connection: we cannot call it, so it waits however many slots a limit
    // lifted gives.
    uploads.given_the_client_left(3);
    uploads.given_the_settings(0, 100);

    uploads.when_the_client_downloads_the_whole_file(1).await;

    uploads
        .then_the_client_is_told_in_turn(
            1,
            "about a part sent, the slot goes to the queue and comes back, as no one else can take it",
            &["OUTOFPARTREQS", "QUEUERANKING", "ACCEPTUPLOADREQ"],
        )
        .await;
    uploads.then_the_bytes_uploaded_are(18 * MAX_BLOCK).await;
    uploads.then_the_clients_waiting_are(1).await;
}

#[tokio::test]
async fn should_rank_waiting_clients_by_their_wait_and_their_files_priority() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("slotted.txt", MAX_BLOCK as usize, 3);
    uploads.given_the_shared_file("very-low.txt", 3000, 4);
    uploads.given_the_shared_file("normal.txt", 3000, 5);
    uploads.given_the_shared_file("release.txt", 3000, 6);
    uploads.given_the_shared_file("low.txt", 3000, 7);
    uploads.given_the_shared_file("high.txt", 3000, 8);
    uploads.given_the_priority_of("very-low.txt", UploadPriority::VeryLow);
    uploads.given_the_priority_of("normal.txt", UploadPriority::Normal);
    uploads.given_the_priority_of("release.txt", UploadPriority::Release);
    uploads.given_the_priority_of("low.txt", UploadPriority::Low);
    uploads.given_the_priority_of("high.txt", UploadPriority::High);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_keeping_a_slot_busy_with(1, "slotted.txt").await;
    uploads.given_a_client_keeping_a_slot_busy_with(2, "slotted.txt").await;
    uploads.given_the_clients_connected(&[3, 4, 5]).await;
    uploads.given_the_client_asked_for(3, "very-low.txt").await;
    uploads.given_time_passed(Duration::from_secs(60));
    uploads.given_the_client_asked_for(4, "normal.txt").await;
    uploads.given_time_passed(Duration::from_secs(60));
    let release = uploads.files["release.txt"].hash;
    uploads.given_the_client_waits_for_at(5, release, 3, "it has waited for nothing yet").await;
    uploads.given_time_passed(Duration::from_secs(120));
    // Waited 240 s at 2, 180 s at 7 and 120 s at 18.
    uploads.given_the_files_waited_for_are(&["release.txt", "normal.txt", "very-low.txt"]).await;
    uploads.given_time_passed(Duration::from_secs(600));
    let low = uploads.files["low.txt"].hash;
    uploads.given_the_client_waits_for_at(5, low, 2, "720 s at 6 against 780 s at 7").await;

    let answer = uploads.when_the_client_asks_for(4, "high.txt").await;

    Uploads::then_it_waits_at(&answer, 1);
    uploads.then_the_files_waited_for_are(&["high.txt", "low.txt", "very-low.txt"]).await;
}

#[tokio::test]
async fn should_forget_clients_that_stop_asking() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("wanted.txt", 5000, 2);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2]).await;
    uploads.given_the_client_asked_for(1, "wanted.txt").await;
    uploads.given_the_client_asked_for(2, "wanted.txt").await;
    uploads.given_the_client_asked_for(3, "wanted.txt").await;
    uploads.given_the_clients_waiting_are(1).await;

    uploads.when_time_passes(upload_manager::PURGE_AFTER + Duration::from_secs(1));

    uploads.then_the_queue_empties("the silent client forgotten").await;
}
