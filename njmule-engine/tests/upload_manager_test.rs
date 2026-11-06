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
use njmule_engine::upload::upload_session::{self, MAX_BLOCK};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};

const WAIT: Duration = Duration::from_secs(20);
const SILENCE: Duration = Duration::from_millis(500);
const LOW_ID: u32 = 0x00AB_CDEF;
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
    // The links of our calls, by the user hash called, until the hello answer.
    calling: HashMap<LinkId, [u8; 16]>,
    called: mpsc::UnboundedSender<([u8; 16], std::io::Result<TcpStream>)>,
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
                    } else if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::HELLOANSWER {
                        let answered = Hello::decode(&packet).unwrap();
                        *hello = Some(answered.clone());
                        let handle = handle.clone();
                        if let Some(user_hash) = self.calling.remove(&link) {
                            self.manager.called(user_hash, &handle, &answered, true, now);
                            self.calls();
                        }
                    }
                    return;
                };
                let handle = handle.clone();
                let shared = Arc::clone(&self.shared);
                let find = move |hash: &[u8; 16]| shared.lock().unwrap().get(hash).cloned();
                let ours = self.ours.clone();
                self.manager.received(&handle, &peer, true, &packet, &ours, &find, &|| None, now);
                self.calls();
            }
            LinkEvent::Closed(link, _) => {
                self.links.remove(&link);
                if let Some(user_hash) = self.calling.remove(&link) {
                    self.manager.call_failed(user_hash, "closed before the hello");
                }
                self.manager.closed(link);
            }
        }
    }

    fn tick(&mut self) {
        let now = self.now();
        let settings = *self.settings.lock().unwrap();
        let shared = Arc::clone(&self.shared);
        self.manager.tick(settings, &move |hash: &[u8; 16]| shared.lock().unwrap().get(hash).cloned(), now);
        self.calls();
    }

    fn calls(&mut self) {
        for call in self.manager.take_calls() {
            let called = self.called.clone();
            tokio::spawn(async move {
                let _ = called.send((call.user_hash, TcpStream::connect(call.address).await));
            });
        }
    }
}

async fn serve(
    listener: TcpListener,
    mut state: Loop,
    mut jobs: mpsc::UnboundedReceiver<Job>,
    mut called: mpsc::UnboundedReceiver<([u8; 16], std::io::Result<TcpStream>)>,
) {
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
            Some((user_hash, stream)) = called.recv() => match stream {
                Ok(stream) => {
                    let remote = stream.peer_addr().unwrap();
                    let local = stream.local_addr().ok().map(|address| address.ip());
                    let (input, output) = stream.into_split();
                    let connection = TcpConnection::over(input, output, remote, local, frame_limits::client_handshake);
                    let handle = peer_link::start(next, connection, events_in.clone());
                    handle.send(state.ours.encode(false).unwrap());
                    state.links.insert(next, (handle, None));
                    state.calling.insert(next, user_hash);
                    next += 1;
                }
                Err(e) => state.manager.call_failed(user_hash, &e.to_string()),
            },
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

    // The next packet, if one comes within `silence`.
    async fn poll(&mut self, silence: Duration) -> Option<Packet> {
        tokio::time::timeout(silence, read_packet(&mut self.input, &frame_limits::client)).await.ok()?.ok()?
    }

    // Whether we close the connection within `wait`, whatever comes before.
    async fn closed_within(&mut self, wait: Duration) -> bool {
        let deadline = Instant::now() + wait;
        while Instant::now() < deadline {
            match tokio::time::timeout(deadline - Instant::now(), read_packet(&mut self.input, &frame_limits::client))
                .await
            {
                Ok(Ok(Some(_))) => {}
                Ok(Ok(None) | Err(_)) => return true,
                Err(_) => return false,
            }
        }
        false
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
    // Where a client waiting for our call listens, and the hellos the clients gave.
    listening: Option<TcpListener>,
    hellos: HashMap<u8, Hello>,
    // The client the scenario follows, and the answer it got last.
    client: u8,
    answer: Option<Packet>,
    dropped: usize,
}

impl Uploads {
    fn new() -> Self {
        let (jobs, _) = mpsc::unbounded_channel();
        Self {
            jobs,
            port: 0,
            offset: Arc::default(),
            settings: Arc::new(Mutex::new(Settings { max_upload_kibps: 0, queue_size: 100, obfuscation: false })),
            shared: Arc::default(),
            priorities: Arc::default(),
            dir: tempfile::tempdir().unwrap(),
            clients: HashMap::new(),
            files: HashMap::new(),
            file: None,
            listening: None,
            hellos: HashMap::new(),
            client: 0,
            answer: None,
            dropped: 0,
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
        *self.settings.lock().unwrap() = Settings { max_upload_kibps, queue_size, obfuscation: false };
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
        let (called_in, called) = mpsc::unbounded_channel();
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
            calling: HashMap::new(),
            called: called_in,
        };
        let (jobs, jobs_out) = mpsc::unbounded_channel();
        self.jobs = jobs;
        tokio::spawn(serve(listener, state, jobs_out, called));
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

    fn when_the_client_leaves(&mut self, seed: u8) {
        self.given_the_client_left(seed);
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

    // Bans and the counting of requests.

    async fn when_the_address_is_banned(&self, address: &str) {
        let ip: std::net::IpAddr = address.parse().unwrap();
        self.on_loop(move |state| {
            let now = state.now();
            state.manager.ban(ip, now, "a test");
        })
        .await;
    }

    async fn when_the_ban_is_lifted_from(&self, address: &str) {
        let ip: std::net::IpAddr = address.parse().unwrap();
        assert!(self.on_loop(move |state| state.manager.unban(ip)).await);
    }

    async fn then_the_address_is_banned(&self, address: &str, banned: bool) {
        let ip: std::net::IpAddr = address.parse().unwrap();
        assert_eq!(self.on_loop(move |state| state.manager.is_banned(ip, state.now())).await, banned, "{address}");
    }

    async fn then_the_only_ban_is_of(&self, address: &str) {
        let bans = self.on_loop(|state| state.manager.bans(state.now())).await;
        assert_eq!(bans.iter().map(|ban| ban.address.to_string()).collect::<Vec<_>>(), [address]);
    }

    async fn then_the_ban_is_over(&self) {
        let (bans, banned) = self
            .on_loop(|state| {
                let now = state.now();
                (state.manager.bans(now), state.manager.is_banned(Ipv4Addr::LOCALHOST.into(), now))
            })
            .await;
        assert!(bans.is_empty() && !banned);
    }

    async fn given_the_client_answered_on_a_hello_again(&mut self, seed: u8) {
        let hello = Hello::of([seed; 16], 0, 5000 + u16::from(seed), &plain("again"), 0, 0);
        let client = self.clients.get_mut(&seed).unwrap();
        client.send(&hello.encode(false).unwrap()).await;
        client.until(protocol::EDONKEY, client::HELLOANSWER).await;
    }

    async fn given_the_client_cut_off_on_a_hello_with_the_user_hash_of(&mut self, seed: u8, other: u8) {
        let hello = Hello::of([other; 16], 0, 5000 + u16::from(seed), &plain("posing"), 0, 0);
        let client = self.clients.get_mut(&seed).unwrap();
        client.send(&hello.encode(false).unwrap()).await;
        assert!(client.closed_within(WAIT).await);
    }

    async fn given_a_new_client_from_the_banned_address_turned_away(&mut self, seed: u8) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.client_of(seed, 0).await;
        client.send(&client_messages::for_file(client::SETREQFILEID, &file)).await;
        assert!(client.poll(SILENCE).await.is_none(), "a banned address gets no answer");
    }

    async fn given_the_client_asking_in_vain(&mut self, seed: u8) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&seed).unwrap();
        client.send(&client_messages::for_file(client::STARTUPLOADREQ, &file)).await;
        assert!(client.poll(SILENCE).await.is_none(), "a banned address gets no slot nor place");
    }

    async fn then_a_new_client_is_told_the_file_status(&mut self, seed: u8) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.client_of(seed, 0).await;
        client.send(&client_messages::for_file(client::SETREQFILEID, &file)).await;
        client.until(protocol::EDONKEY, client::FILESTATUS).await;
    }

    async fn given_no_client_dropped_by_a_filter_that_refuses_none(&self) {
        assert_eq!(self.on_loop(|state| state.manager.drop_clients(&|_| false)).await, 0);
    }

    async fn when_the_clients_at_the_loopback_address_are_dropped(&mut self) {
        self.dropped = self.on_loop(|state| state.manager.drop_clients(&|ip| ip.is_loopback())).await;
    }

    async fn then_the_client_is_cut_off(&mut self, seed: u8) {
        assert!(self.clients.get_mut(&seed).unwrap().closed_within(WAIT).await, "client {seed}");
    }

    async fn then_no_upload_is_left(&self) {
        assert!(self.on_loop(|state| state.manager.uploads(state.now())).await.is_empty());
    }

    // Two slots under the limit of 1 KiB/s, kept busy with a big file; the scenario's file is a small one.
    async fn given_two_slots_busy_under_a_limit(&mut self) {
        self.given_the_settings(1, 100);
        self.given_the_shared_file("busy.bin", MAX_BLOCK as usize, 20);
        self.given_the_shared_file("wanted.bin", 3000, 21);
        self.given_the_uploads_started().await;
        self.given_a_client_keeping_a_slot_busy_with(1, "busy.bin").await;
        self.given_a_client_keeping_a_slot_busy_with(2, "busy.bin").await;
    }

    fn given_other_files_shared(&mut self, count: u32) {
        let wanted = self.file.clone();
        for at in 0..count {
            self.given_the_shared_file(&format!("other-{at}.bin"), 3000, 30 + at as u8);
        }
        self.file = wanted;
    }

    async fn given_a_client_waiting(&mut self) {
        self.client = 6;
        self.given_the_client_waits_at(6, 1, "behind the busy slots").await;
    }

    async fn given_it_asked_again_right_away(&mut self, times: u32) {
        for _ in 0..times {
            self.given_time_passed(Duration::from_secs(1));
            self.given_the_client_waits_at(self.client, 1, "asked again").await;
        }
    }

    async fn given_it_got_a_slot_that_ended_because_it_asked_for_nothing(&mut self) {
        self.given_the_client_waits_at(7, 2, "behind the client followed").await;
        self.given_the_client_left(1);
        let followed = self.client;
        self.clients.get_mut(&followed).unwrap().until(protocol::EDONKEY, client::ACCEPTUPLOADREQ).await;
        self.given_time_passed(upload_manager::FIRST_REQUEST_WAIT + Duration::from_secs(1));
        self.clients.get_mut(&followed).unwrap().until(protocol::EDONKEY, client::OUTOFPARTREQS).await;
    }

    async fn when_it_asks_for_each_other_file_right_away(&mut self) {
        let others: Vec<[u8; 16]> =
            self.files.iter().filter(|(name, _)| name.starts_with("other-")).map(|(_, file)| file.hash).collect();
        for file in others {
            self.given_time_passed(Duration::from_secs(1));
            self.answer = Some(self.ask(self.client, file).await);
        }
    }

    // Ten minutes after its last ask, less the time its slot took.
    async fn when_it_asks_again_as_long_after_its_last_ask_as_allowed(&mut self) {
        self.given_time_passed(upload_manager::MIN_REQUEST_TIME - upload_manager::FIRST_REQUEST_WAIT);
        let file = self.file.as_ref().unwrap().hash;
        self.answer = Some(self.ask(self.client, file).await);
    }

    fn then_it_is_still_told(&self, opcode: u8) {
        let answer = self.answer.as_ref().unwrap();
        assert_eq!((answer.protocol(), answer.opcode()), (protocol::EMULE, opcode));
    }

    // Two slots under a limit with budget for a small block while the other is busy; a firewalled client waits, so an
    // idle slot goes back to the queue and to the same client.
    async fn given_a_slot_for_a_client_while_another_waits(&mut self) {
        self.given_the_settings(5, 100);
        self.given_the_shared_file("busy.bin", MAX_BLOCK as usize, 21);
        self.given_the_shared_file("small.bin", 3000, 22);
        self.given_the_uploads_started().await;
        self.given_a_client_keeping_a_slot_busy_with(1, "busy.bin").await;
        self.client = 2;
        self.given_the_client_got_a_slot(2).await;
        self.client_of(3, LOW_ID).await;
        self.given_the_client_waits_at(3, 1, "both slots taken").await;
        self.given_the_client_left(3);
    }

    async fn given_it_left_its_slot_idle(&mut self, times: u32) {
        self.when_it_leaves_its_slot_idle(times).await;
        self.then_it_gets_its_slot_back().await;
    }

    async fn given_it_then_downloaded_from_its_slot(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&blocks(file, &[Range { start: 0, end: 3000 }])).await;
        client.until(protocol::EDONKEY, client::SENDINGPART).await;
        self.given_time_passed(upload_manager::IDLE_SLOT + Duration::from_secs(1));
        self.clients.get_mut(&self.client).unwrap().until(protocol::EDONKEY, client::OUTOFPARTREQS).await;
        self.then_it_gets_its_slot_back().await;
    }

    async fn when_it_leaves_its_slot_idle(&mut self, times: u32) {
        for at in 0..times {
            if at > 0 {
                self.then_it_gets_its_slot_back().await;
            }
            self.given_time_passed(upload_manager::FIRST_REQUEST_WAIT + Duration::from_secs(1));
            self.clients.get_mut(&self.client).unwrap().until(protocol::EDONKEY, client::OUTOFPARTREQS).await;
        }
    }

    async fn then_it_gets_its_slot_back(&mut self) {
        self.clients.get_mut(&self.client).unwrap().until(protocol::EDONKEY, client::ACCEPTUPLOADREQ).await;
    }

    async fn then_it_is_banned(&mut self) {
        assert!(self.clients.get_mut(&self.client).unwrap().closed_within(WAIT).await);
        assert!(!self.on_loop(|state| state.manager.bans(state.now())).await.is_empty());
    }

    // A connection asks for `count` files we do not have.
    async fn ask_for_unknown_files(&mut self, seed: u8, count: usize) {
        let client = self.client_of(seed, 0).await;
        for at in 0..count {
            client.send(&client_messages::for_file(client::SETREQFILEID, &[0xE0 + at as u8; 16])).await;
        }
    }

    async fn given_a_client_that_was_dropped_for_asking_for_files_we_do_not_have(&mut self) {
        self.given_the_shared_file("ours.bin", 3000, 23);
        self.given_the_uploads_started().await;
        self.ask_for_unknown_files(1, upload_session::UNKNOWN_FILES_LIMIT).await;
        assert!(self.clients.get_mut(&1).unwrap().closed_within(WAIT).await);
    }

    async fn when_another_connection_from_its_address_asks_for_them(&mut self, count: usize) {
        self.client = 2;
        self.ask_for_unknown_files(2, count).await;
    }

    // Helpers.

    async fn client_of(&mut self, seed: u8, client_id: u32) -> &mut Downloader {
        self.client_listening_on(seed, client_id, 5000 + u16::from(seed)).await
    }

    async fn client_listening_on(&mut self, seed: u8, client_id: u32, port: u16) -> &mut Downloader {
        if !self.clients.contains_key(&seed) {
            let nick = format!("client {seed}");
            let hello = Hello::of([seed; 16], client_id, port, &plain(&nick), 0, 0);
            let client = Downloader::connect(self.port, &hello).await;
            self.clients.insert(seed, client);
            self.hellos.insert(seed, hello);
        }
        self.clients.get_mut(&seed).unwrap()
    }

    // Calling clients back.

    async fn given_a_client_listening_for_calls_waiting_at(&mut self, seed: u8, place: u32) {
        let listening = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let port = listening.local_addr().unwrap().port();
        self.listening = Some(listening);
        self.client_listening_on(seed, 0, port).await;
        self.given_the_client_waits_at(seed, place, "waiting for our call").await;
    }

    async fn given_a_client_that_cannot_be_called_waiting_at(&mut self, seed: u8, place: u32) {
        let closed = closed_port().await;
        self.client_listening_on(seed, 0, closed).await;
        self.given_the_client_waits_at(seed, place, "waiting for a call that cannot come").await;
    }

    // Our call, taken by `hello`.
    async fn accept_call(&mut self, hello: &Hello) -> Downloader {
        let listening = self.listening.as_ref().unwrap();
        let (stream, _) = tokio::time::timeout(WAIT, listening.accept()).await.expect("our call").unwrap();
        let (input, output) = stream.into_split();
        let mut called = Downloader { input, output };
        let greeting = called.next().await;
        assert_eq!((greeting.protocol(), greeting.opcode()), (protocol::EDONKEY, client::HELLO));
        called.send(&hello.encode(true).unwrap()).await;
        called
    }

    async fn then_an_impostor_answering_the_call_is_cut_off(&mut self, seed: u8) {
        let impostor = Hello::of([seed; 16], 0, 5000 + u16::from(seed), &plain("impostor"), 0, 0);
        let mut called = self.accept_call(&impostor).await;
        assert!(called.closed_within(WAIT).await);
    }

    async fn then_the_client_answering_the_call_gets_a_slot(&mut self, seed: u8) {
        let hello = self.hellos[&seed].clone();
        let mut called = self.accept_call(&hello).await;
        called.until(protocol::EDONKEY, client::ACCEPTUPLOADREQ).await;
        self.clients.insert(seed, called);
    }

    async fn when_the_called_client_hangs_up_before_its_hello(&mut self) {
        let listening = self.listening.as_ref().unwrap();
        let (stream, _) = tokio::time::timeout(WAIT, listening.accept()).await.expect("our call").unwrap();
        drop(stream);
    }

    async fn given_a_firewalled_client_that_waited_and_left(&mut self) {
        self.client = 3;
        self.client_of(3, LOW_ID).await;
        self.given_the_client_waits_at(3, 1, "behind the busy slots").await;
        self.given_the_client_left(3);
        self.given_time_passed(upload_manager::MIN_REQUEST_TIME + Duration::from_secs(60));
    }

    async fn given_a_slot_freed_while_it_was_away(&mut self) {
        self.given_the_client_left(1);
        let deadline = Instant::now() + WAIT;
        let mut described = String::new();
        while !described.starts_with("waiting 1, 1 called when they ask") && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
            described = self.on_loop(|state| state.manager.describe()).await;
        }
        assert!(described.starts_with("waiting 1, 1 called when they ask"), "{described}");
    }

    async fn when_the_firewalled_client_asks_again(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        self.answer = Some(self.ask(3, file).await);
    }

    fn then_it_gets_a_slot(&self) {
        assert_eq!(self.answer.as_ref().unwrap().opcode(), client::ACCEPTUPLOADREQ);
    }

    async fn given_a_client_that_asked_too_often_almost_to_a_ban_and_cannot_be_called(&mut self) {
        let closed = closed_port().await;
        self.client = 8;
        self.client_listening_on(8, 0, closed).await;
        self.given_the_client_waits_at(8, 1, "behind the busy slots").await;
        self.given_it_asked_again_right_away(upload_manager::BAD_LIMIT - 1).await;
        self.given_the_client_left(8);
    }

    async fn given_it_left_the_queue_when_it_could_not_be_called(&mut self) {
        self.given_the_client_left(1);
        self.then_the_queue_empties("the client left the queue").await;
    }

    async fn when_it_comes_back_with_the_user_hash_of_and_asks_right_away(&mut self, seed: u8) {
        let port = self.hellos[&8].tcp_port;
        let hello = Hello::of([seed; 16], 0, port, &plain("back"), 0, 0);
        let mut client = Downloader::connect(self.port, &hello).await;
        let file = self.file.as_ref().unwrap().hash;
        client.send(&client_messages::for_file(client::SETREQFILEID, &file)).await;
        client.until(protocol::EDONKEY, client::FILESTATUS).await;
        client.send(&client_messages::for_file(client::STARTUPLOADREQ, &file)).await;
        self.client = seed;
        self.clients.insert(seed, client);
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

// A port nobody listens on.
async fn closed_port() -> u16 {
    let probe = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    probe.local_addr().unwrap().port()
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

#[tokio::test]
async fn should_ban_the_whole_ipv6_slash64_of_an_address() {
    let mut uploads = Uploads::new();
    uploads.given_the_uploads_started().await;

    uploads.when_the_address_is_banned("2001:db8:1:2::1").await;

    uploads.then_the_address_is_banned("2001:db8:1:2:ffff:eeee:dddd:9", true).await;
    uploads.then_the_address_is_banned("2001:db8:1:3::1", false).await;
    uploads.then_the_only_ban_is_of("2001:db8:1:2::").await;
}

#[tokio::test]
async fn should_let_the_ban_of_an_ipv6_slash64_be_lifted_from_any_of_its_addresses() {
    let mut uploads = Uploads::new();
    uploads.given_the_uploads_started().await;
    uploads.when_the_address_is_banned("2001:db8:1:2::1").await;

    uploads.when_the_ban_is_lifted_from("2001:db8:1:2::77").await;

    uploads.then_the_address_is_banned("2001:db8:1:2::1", false).await;
}

#[tokio::test]
async fn should_let_bans_run_out() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("kept.txt", 3000, 9);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2]).await;
    uploads.given_the_client_asked_for(1, "kept.txt").await;
    uploads.given_the_client_asked_for(2, "kept.txt").await;
    uploads.given_the_client_asked_for(3, "kept.txt").await;
    uploads.given_the_clients_waiting_are(1).await;
    uploads.given_time_passed(upload_manager::PURGE_AFTER + Duration::from_secs(1));
    uploads.then_the_queue_empties("the silent client forgotten").await;
    // A hello again is answered; one with another user hash is an attack, and the address is banned.
    uploads.given_the_client_answered_on_a_hello_again(1).await;
    uploads.given_the_client_cut_off_on_a_hello_with_the_user_hash_of(1, 8).await;
    uploads.then_the_only_ban_is_of("127.0.0.1").await;
    uploads.given_a_new_client_from_the_banned_address_turned_away(10).await;
    uploads.given_the_client_asking_in_vain(2).await;

    uploads.when_time_passes(upload_manager::BAN_TIME);

    uploads.then_the_ban_is_over().await;
    uploads.then_a_new_client_is_told_the_file_status(11).await;
}

#[tokio::test]
async fn should_drop_the_clients_the_ip_filter_refuses() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("filtered.txt", 3000, 13);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2, 3]).await;
    uploads.given_the_client_asked_for(1, "filtered.txt").await;
    uploads.given_the_client_asked_for(2, "filtered.txt").await;
    uploads.given_the_client_asked_for(3, "filtered.txt").await;
    uploads.given_no_client_dropped_by_a_filter_that_refuses_none().await;

    uploads.when_the_clients_at_the_loopback_address_are_dropped().await;

    assert_eq!(uploads.dropped, 3);
    uploads.then_the_client_is_cut_off(1).await;
    uploads.then_the_client_is_cut_off(3).await;
    uploads.then_no_upload_is_left().await;
}

#[tokio::test]
async fn should_not_count_asks_for_other_files_as_asking_too_often() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_other_files_shared(upload_manager::BAD_LIMIT);
    uploads.given_a_client_waiting().await;

    uploads.when_it_asks_for_each_other_file_right_away().await;

    uploads.then_it_is_still_told(extended::QUEUERANKING);
}

#[tokio::test]
async fn should_not_count_the_end_of_a_slot_as_an_ask_of_the_client() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_waiting().await;
    uploads.given_it_asked_again_right_away(upload_manager::BAD_LIMIT - 1).await;
    uploads.given_it_got_a_slot_that_ended_because_it_asked_for_nothing().await;

    uploads.when_it_asks_again_as_long_after_its_last_ask_as_allowed().await;

    uploads.then_it_is_still_told(extended::QUEUERANKING);
}

#[tokio::test]
async fn should_ban_a_client_that_keeps_taking_slots_without_asking_for_data() {
    let mut uploads = Uploads::new();
    uploads.given_a_slot_for_a_client_while_another_waits().await;

    uploads.when_it_leaves_its_slot_idle(upload_manager::IDLE_SLOTS_LIMIT).await;

    uploads.then_it_is_banned().await;
}

#[tokio::test]
async fn should_forgive_the_idle_slots_once_a_slot_sent_data() {
    let mut uploads = Uploads::new();
    uploads.given_a_slot_for_a_client_while_another_waits().await;
    uploads.given_it_left_its_slot_idle(upload_manager::IDLE_SLOTS_LIMIT - 1).await;
    uploads.given_it_then_downloaded_from_its_slot().await;

    uploads.when_it_leaves_its_slot_idle(1).await;

    uploads.then_it_gets_its_slot_back().await;
}

#[tokio::test]
async fn should_drop_a_client_asking_for_many_files_we_do_not_have_and_ban_it_the_second_time() {
    let mut uploads = Uploads::new();
    uploads.given_a_client_that_was_dropped_for_asking_for_files_we_do_not_have().await;

    uploads.when_another_connection_from_its_address_asks_for_them(upload_session::UNKNOWN_FILES_LIMIT).await;

    uploads.then_it_is_banned().await;
}

#[tokio::test]
async fn should_call_a_client_that_left_the_queue_for_its_slot() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("called.txt", 3000, 10);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2]).await;
    uploads.given_the_client_asked_for(1, "called.txt").await;
    uploads.given_the_client_asked_for(2, "called.txt").await;
    uploads.given_a_client_listening_for_calls_waiting_at(3, 1).await;
    uploads.given_the_client_left(3);

    uploads.when_the_client_leaves(1);

    uploads.then_an_impostor_answering_the_call_is_cut_off(7).await;
    uploads.then_the_client_answering_the_call_gets_a_slot(3).await;
}

#[tokio::test]
async fn should_drop_from_the_queue_a_client_whose_port_is_closed() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("called.txt", 3000, 10);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2]).await;
    uploads.given_the_client_asked_for(1, "called.txt").await;
    uploads.given_the_client_asked_for(2, "called.txt").await;
    uploads.given_a_client_listening_for_calls_waiting_at(3, 1).await;
    uploads.given_the_client_left(3);
    uploads.given_the_client_left(1);
    uploads.then_an_impostor_answering_the_call_is_cut_off(7).await;
    uploads.then_the_client_answering_the_call_gets_a_slot(3).await;
    uploads.given_a_client_that_cannot_be_called_waiting_at(4, 1).await;
    uploads.given_the_client_left(4);

    uploads.when_the_client_leaves(2);

    uploads.then_the_queue_empties("the unreachable client dropped").await;
}

#[tokio::test]
async fn should_drop_from_the_queue_a_client_that_hangs_up_before_its_hello() {
    let mut uploads = Uploads::new();
    uploads.given_the_settings(1, 100);
    uploads.given_the_shared_file("called.txt", 3000, 10);
    uploads.given_the_uploads_started().await;
    uploads.given_the_clients_connected(&[1, 2]).await;
    uploads.given_the_client_asked_for(1, "called.txt").await;
    uploads.given_the_client_asked_for(2, "called.txt").await;
    uploads.given_a_client_listening_for_calls_waiting_at(3, 1).await;
    uploads.given_the_client_left(3);
    uploads.given_the_client_left(1);
    uploads.then_an_impostor_answering_the_call_is_cut_off(7).await;
    uploads.then_the_client_answering_the_call_gets_a_slot(3).await;
    uploads.given_a_client_that_cannot_be_called_waiting_at(4, 1).await;
    uploads.given_the_client_left(4);
    uploads.given_the_client_left(2);
    uploads.then_the_queue_empties("the unreachable client dropped").await;
    uploads.given_the_client_asked_for(5, "called.txt").await;
    uploads.given_a_client_listening_for_calls_waiting_at(6, 1).await;
    uploads.given_the_client_left(6);
    uploads.given_the_client_left(5);

    uploads.when_the_called_client_hangs_up_before_its_hello().await;

    uploads.then_the_queue_empties("the client that hung up dropped").await;
}

#[tokio::test]
async fn should_give_a_slot_when_it_asks_again_to_a_firewalled_client_whose_turn_came_while_it_was_away() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_firewalled_client_that_waited_and_left().await;
    uploads.given_a_slot_freed_while_it_was_away().await;

    uploads.when_the_firewalled_client_asks_again().await;

    uploads.then_it_gets_a_slot();
}

#[tokio::test]
async fn should_still_count_asking_too_often_after_the_client_left_the_queue() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_that_asked_too_often_almost_to_a_ban_and_cannot_be_called().await;
    uploads.given_it_left_the_queue_when_it_could_not_be_called().await;

    uploads.when_it_comes_back_with_the_user_hash_of_and_asks_right_away(8).await;

    uploads.then_it_is_banned().await;
}

#[tokio::test]
async fn should_still_count_asking_too_often_when_the_client_comes_back_with_another_user_hash() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_that_asked_too_often_almost_to_a_ban_and_cannot_be_called().await;
    uploads.given_it_left_the_queue_when_it_could_not_be_called().await;

    uploads.when_it_comes_back_with_the_user_hash_of_and_asks_right_away(9).await;

    uploads.then_it_is_banned().await;
}
