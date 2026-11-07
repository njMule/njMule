// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's UploadManagerTest, under its names: clients on the loopback address asking for our files, the
//! uploads served by a loop of their own with a clock the tests move.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

mod queue_lab;

use std::collections::HashMap;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::files::hash::ed2k::{self, PARTSIZE};
use njmule_engine::files::part_file;
use njmule_engine::files::shared_files::SharedFile;
use njmule_engine::net::tcp_connection::{TcpConnection, read_packet, write_packet};
use njmule_engine::peer::peer_link::{self, LinkEvent, LinkHandle, LinkId};
use njmule_engine::protocol::ed2k::client_messages::{self, FileStatus, PartStatus, Range, RequestParts, SendingPart};
use njmule_engine::protocol::ed2k::client_reasks::{self, Ack, Ping};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::ed2k::low_id_messages::UdpCheckRequest;
use njmule_engine::protocol::ed2k::source_exchange as exchange;
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, frame_limits, packing, protocol};
use njmule_engine::upload::credits::{self, Credits};
use njmule_engine::upload::upload_manager::{
    self, BlockRead, FileCounts, KadRequest, ReaskAnswer, Settings, UploadManager,
};
use njmule_engine::upload::upload_session::{self, FILES_DIRECTORY, MAX_BLOCK};
use njmule_engine::upload::uploadable::Uploadable;
use queue_lab::QueueLab;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpSocket, TcpStream};
use tokio::sync::{mpsc, oneshot};

const WAIT: Duration = Duration::from_secs(20);
const SILENCE: Duration = Duration::from_millis(500);
const LOW_ID: u32 = 0x00AB_CDEF;
const MANY_BLOCKS: usize = 16;
const SMALL_SOCKET_BUFFER: u32 = 64 * 1024;

// The counts of the files, as events.
struct Counted(Arc<Mutex<Vec<String>>>);

impl FileCounts for Counted {
    fn asked(&mut self, file: &[u8; 16]) {
        self.0.lock().unwrap().push(format!("asked {}", hex(file)));
    }

    fn accepted(&mut self, file: &[u8; 16]) {
        self.0.lock().unwrap().push(format!("accepted {}", hex(file)));
    }

    fn sent(&mut self, file: &[u8; 16], bytes: u64) {
        self.0.lock().unwrap().push(format!("sent {bytes} {}", hex(file)));
    }
}

fn hex(hash: &[u8; 16]) -> String {
    hash.iter().map(|byte| format!("{byte:02x}")).collect()
}
const OURS: [u8; 16] = [0xAB; 16];

type Job = Box<dyn FnOnce(&mut Loop) + Send>;

// The uploads and what they serve, on their own task.
struct Loop {
    manager: UploadManager,
    links: HashMap<LinkId, (LinkHandle, Option<Hello>)>,
    shared: Arc<Mutex<HashMap<[u8; 16], SharedFile>>>,
    // Our downloads with their checked parts, and the sources each of them has.
    downloads: Arc<Mutex<HashMap<[u8; 16], Uploadable>>>,
    download_sources: Arc<Mutex<Vec<exchange::Source>>>,
    visible: Arc<Mutex<Option<Vec<SharedFile>>>>,
    settings: Arc<Mutex<Settings>>,
    base: Instant,
    offset: Arc<Mutex<Duration>>,
    ours: Hello,
    reads: Option<mpsc::UnboundedReceiver<BlockRead>>,
    // The links of our calls, by the user hash called, until the hello answer.
    calling: HashMap<LinkId, [u8; 16]>,
    called: mpsc::UnboundedSender<([u8; 16], std::io::Result<TcpStream>)>,
    // What clients told our Kad node, in order.
    kad: Vec<KadRequest>,
}

impl Loop {
    fn now(&self) -> Instant {
        self.base + *self.offset.lock().unwrap()
    }

    // Our file of a hash: a shared one, else a download.
    fn finder(&self) -> impl Fn(&[u8; 16]) -> Option<Uploadable> + use<> {
        let shared = Arc::clone(&self.shared);
        let downloads = Arc::clone(&self.downloads);
        move |hash: &[u8; 16]| {
            shared
                .lock()
                .unwrap()
                .get(hash)
                .map(Uploadable::of)
                .or_else(|| downloads.lock().unwrap().get(hash).cloned())
        }
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
                let find = self.finder();
                let downloads = Arc::clone(&self.downloads);
                let told = Arc::clone(&self.download_sources);
                let sources = move |hash: &[u8; 16]| {
                    downloads.lock().unwrap().contains_key(hash).then(|| told.lock().unwrap().clone())
                };
                let ours = self.ours.clone();
                let visible = Arc::clone(&self.visible);
                let visible = move || visible.lock().unwrap().clone();
                self.manager.received(&handle, &peer, true, &packet, &ours, &find, &visible, &sources, now);
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
        let find = self.finder();
        self.manager.tick(settings, &find, now);
        self.calls();
    }

    fn calls(&mut self) {
        self.kad.extend(self.manager.take_kad_requests());
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
                let find = state.finder();
                state.manager.block_read(read, &find, now);
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

    // As connect, with a small receive buffer, set before the connection settles the window it offers.
    async fn connect_with_buffer(port: u16, hello: &Hello, buffer: u32) -> Self {
        let socket = TcpSocket::new_v4().unwrap();
        socket.set_recv_buffer_size(buffer).unwrap();
        let stream = socket.connect(SocketAddr::from((Ipv4Addr::LOCALHOST, port))).await.unwrap();
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
        let packet = tokio::time::timeout(WAIT, read_packet(&mut self.input, &frame_limits::client))
            .await
            .expect("a packet in time")
            .unwrap()
            .expect("the connection open");
        if packet.protocol() == protocol::PACKED {
            packing::unpack(packet, packing::MAX_CLIENT_UNPACKED).unwrap()
        } else {
            packet
        }
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
    downloads: Arc<Mutex<HashMap<[u8; 16], Uploadable>>>,
    download_sources: Arc<Mutex<Vec<exchange::Source>>>,
    visible: Arc<Mutex<Option<Vec<SharedFile>>>>,
    // What a client got up to an answer.
    seen: Vec<Packet>,
    // The downloads' queue, its files kept while the test runs; the download followed and its last part.
    queue: Option<QueueLab>,
    download: [u8; 16],
    last_part: Vec<u8>,
    our_ipv6: Option<Ipv6Addr>,
    // What the client followed was told last: a file's status, a block, sources.
    status: Option<FileStatus>,
    block: Option<SendingPart>,
    told_sources: Vec<exchange::Source>,
    priorities: Arc<Mutex<HashMap<[u8; 16], UploadPriority>>>,
    dir: tempfile::TempDir,
    clients: HashMap<u8, Downloader>,
    files: HashMap<String, SharedFile>,
    file: Option<SharedFile>,
    // The buffers of our sockets and of the clients', when the scenario makes them small.
    socket_buffer: u32,
    counted: Arc<Mutex<Vec<String>>>,
    reask_answer: Option<ReaskAnswer>,
    data_before_answer: u64,
    queued: u64,
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
            downloads: Arc::default(),
            download_sources: Arc::default(),
            visible: Arc::default(),
            seen: Vec::new(),
            queue: None,
            download: [0; 16],
            last_part: Vec::new(),
            our_ipv6: None,
            status: None,
            block: None,
            told_sources: Vec::new(),
            priorities: Arc::default(),
            dir: tempfile::tempdir().unwrap(),
            clients: HashMap::new(),
            files: HashMap::new(),
            file: None,
            socket_buffer: 0,
            counted: Arc::default(),
            reask_answer: None,
            data_before_answer: 0,
            queued: 0,
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
        let socket = TcpSocket::new_v4().unwrap();
        if self.socket_buffer > 0 {
            // Accepted connections take the listener's buffers.
            socket.set_send_buffer_size(self.socket_buffer).unwrap();
        }
        socket.bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).unwrap();
        let listener = socket.listen(16).unwrap();
        self.port = listener.local_addr().unwrap().port();
        let (reads_in, reads) = mpsc::unbounded_channel::<BlockRead>();
        let (called_in, called) = mpsc::unbounded_channel();
        let credits = Credits::load(self.dir.path(), Box::new(|| 1_790_000_000));
        let mut manager = UploadManager::new(reads_in, credits, *self.settings.lock().unwrap());
        let priorities = Arc::clone(&self.priorities);
        manager.set_priorities(move |hash| priorities.lock().unwrap().get(hash).copied().unwrap_or_default());
        manager.count_in(Box::new(Counted(Arc::clone(&self.counted))));
        let plain_ours = Hello::of(OURS, 0, 4662, &plain("test"), 0, 0);
        let ours = match self.our_ipv6 {
            Some(address) => plain_ours.with_ipv6(&address),
            None => plain_ours,
        };
        let state = Loop {
            manager,
            links: HashMap::new(),
            shared: Arc::clone(&self.shared),
            downloads: Arc::clone(&self.downloads),
            download_sources: Arc::clone(&self.download_sources),
            visible: Arc::clone(&self.visible),
            settings: Arc::clone(&self.settings),
            base: Instant::now(),
            offset: Arc::clone(&self.offset),
            ours,
            reads: Some(reads),
            calling: HashMap::new(),
            called: called_in,
            kad: Vec::new(),
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

    // UDP re-asks.

    async fn given_a_client_waiting_that_takes_udp_reasks_on_port(&mut self, udp_port: u16) {
        let ours = Ours {
            nick: "client 3",
            udp_port,
            kad_port: udp_port,
            misc_options1: client_reasks::UDP_VERSION << 24,
            misc_options2: 0,
        };
        let hello = Hello::of([3; 16], 0, 5003, &ours, 0, 0);
        let client = Downloader::connect(self.port, &hello).await;
        self.clients.insert(3, client);
        self.client = 3;
        self.given_the_client_waits_at(3, 1, "behind the busy slots").await;
    }

    async fn when_a_reask_for_the_file_comes_from(&mut self, port: u16, file: [u8; 16]) {
        let ping = Ping { file, parts: PartStatus::COMPLETE, complete_sources: 0 }.encode(client_reasks::UDP_VERSION);
        let ping = ping.unwrap();
        self.reask_answer = self
            .on_loop(move |state| {
                let now = state.now();
                let shared = Arc::clone(&state.shared);
                let find = move |hash: &[u8; 16]| shared.lock().unwrap().get(hash).map(Uploadable::of);
                state.manager.reasked(Ipv4Addr::LOCALHOST.into(), port, &ping, &find, now)
            })
            .await;
    }

    fn then_the_answer_tells_the_place(&self, place: u32) {
        let answer = &self.reask_answer.as_ref().expect("an answer").packet;
        let ack = Ack::decode(answer, client_reasks::UDP_VERSION).unwrap();
        assert_eq!(ack, Ack { parts: Some(PartStatus::COMPLETE), rank: place });
    }

    fn then_there_is_no_answer(&self) {
        assert!(self.reask_answer.is_none());
    }

    fn then_the_answer_is_plain(&self, expected: Packet) {
        assert_eq!(self.reask_answer, Some(ReaskAnswer { packet: expected, obfuscate_for: None }));
    }

    // The counts of a file.

    async fn given_a_client_that_asked_for_the_file_by_name(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        self.client = 1;
        let client = self.client_of(1, 0).await;
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
    }

    async fn when_it_downloads_the_file(&mut self) {
        let file = self.file.clone().unwrap();
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&blocks(file.hash, &[Range { start: 0, end: file.size }])).await;
        let mut received = 0u64;
        while received < file.size {
            let packet = client.next().await;
            if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::SENDINGPART {
                received += SendingPart::decode(&packet).unwrap().data.len() as u64;
            }
        }
        // The uploads count what they handed over just after: the data can reach the client first.
        self.on_loop(|_| ()).await;
    }

    fn then_the_file_was_counted(&self, events: &[&str]) {
        let hash = hex(&self.file.as_ref().unwrap().hash);
        let expected: Vec<String> = events.iter().map(|event| format!("{event} {hash}")).collect();
        assert_eq!(*self.counted.lock().unwrap(), expected);
    }

    // Files that moved or went.

    fn given_the_file_listed_at(&self, name: &str) {
        let mut file = self.file.clone().unwrap();
        file.path = self.dir.path().join(name);
        self.shared.lock().unwrap().insert(file.hash, file);
    }

    fn given_the_file_found_where_it_went(&self) {
        let file = self.file.clone().unwrap();
        self.shared.lock().unwrap().insert(file.hash, file);
    }

    fn given_the_file_no_longer_shared(&self) {
        self.shared.lock().unwrap().remove(&self.file.as_ref().unwrap().hash);
    }

    async fn when_the_client_asks_for_the_block(&mut self, start: u64, end: u64) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&blocks(file, &[Range { start, end }])).await;
    }

    async fn then_the_client_receives_the_block(&mut self, start: u64, end: u64) {
        let file = self.file.clone().unwrap();
        let data = std::fs::read(&file.path).unwrap();
        let client = self.clients.get_mut(&self.client).unwrap();
        let seen = client.until(protocol::EDONKEY, client::SENDINGPART).await;
        let part = SendingPart::decode(seen.last().unwrap()).unwrap();
        assert_eq!(part.data, data[start as usize..end as usize]);
    }

    async fn then_the_connection_ends(&mut self) {
        assert!(self.clients.get_mut(&self.client).unwrap().closed_within(WAIT).await);
    }

    async fn then_the_slot_is_gone(&self) {
        let deadline = Instant::now() + WAIT;
        let mut slots = usize::MAX;
        while slots > 0 && Instant::now() < deadline {
            slots = self.on_loop(|state| state.manager.slot_count()).await;
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert_eq!(slots, 0);
    }

    // A client that reads slowly.

    async fn given_a_client_with_a_slot_that_asks_for_many_blocks_and_reads_nothing(&mut self) {
        self.given_the_shared_file("much.bin", MANY_BLOCKS * MAX_BLOCK as usize, 17);
        self.socket_buffer = SMALL_SOCKET_BUFFER;
        self.given_the_uploads_started().await;
        let hello = Hello::of([1; 16], 0, 5001, &plain("client 1"), 0, 0);
        let client = Downloader::connect_with_buffer(self.port, &hello, SMALL_SOCKET_BUFFER).await;
        self.clients.insert(1, client);
        self.hellos.insert(1, hello);
        self.client = 1;
        let file = self.file.as_ref().unwrap().hash;
        assert_eq!(self.ask(1, file).await.opcode(), client::ACCEPTUPLOADREQ);
        let count = MANY_BLOCKS as u64;
        let client = self.clients.get_mut(&1).unwrap();
        for at in (0..count).step_by(3) {
            let ranges: Vec<Range> = (at..(at + 3).min(count))
                .map(|block| Range { start: block * MAX_BLOCK, end: (block + 1) * MAX_BLOCK })
                .collect();
            client.send(&blocks(file, &ranges)).await;
        }
        // Whatever is not held back is handed over within a few ticks.
        let mut last = u64::MAX;
        let mut sent = self.sent_to_the_slot().await;
        while sent != last {
            tokio::time::sleep(Duration::from_secs(1)).await;
            last = sent;
            sent = self.sent_to_the_slot().await;
        }
        self.queued = sent;
    }

    async fn sent_to_the_slot(&self) -> u64 {
        let uploads = self.on_loop(|state| state.manager.uploads(state.now())).await;
        uploads.iter().filter_map(|upload| upload.sent).sum()
    }

    async fn when_it_asks_for_the_file_name_and_reads(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        tokio::time::sleep(SILENCE).await;
        for packet in client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await {
            if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::SENDINGPART {
                self.data_before_answer += SendingPart::decode(&packet).unwrap().data.len() as u64;
            }
        }
    }

    fn then_little_of_what_it_asked_for_was_queued(&self) {
        assert!(self.queued < self.file.as_ref().unwrap().size / 2, "{}", self.queued);
    }

    fn then_our_answer_came_before_most_of_the_data(&self) {
        assert!(self.data_before_answer < self.file.as_ref().unwrap().size / 2, "{}", self.data_before_answer);
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

    // Downloads shared with their checked parts, and their sources told.

    fn given_a_download_with_only_its_last_part_checked(&mut self, name: &str, last_size: usize, seed: u8) {
        self.last_part = random(last_size, seed);
        let parts = vec![[0xC1; 16], ed2k::md4(&self.last_part)];
        let link = FileLink::new(
            name.to_owned(),
            PARTSIZE + last_size as u64,
            ed2k::file_hash(&parts).unwrap(),
            None,
            parts,
            Vec::new(),
            Vec::new(),
        )
        .unwrap();
        let last = self.last_part.clone();
        self.add_download(&link, Some(&last));
    }

    fn given_a_download_of_whole_parts_with_only_its_second_checked(&mut self, name: &str, seed: u8) {
        let second = random(PARTSIZE as usize, seed);
        let parts = vec![[0xC2; 16], ed2k::md4(&second), ed2k::EMPTY_PART_HASH];
        let hash = ed2k::file_hash(&parts).unwrap();
        let link = FileLink::new(name.to_owned(), 2 * PARTSIZE, hash, None, parts, Vec::new(), Vec::new()).unwrap();
        self.add_download(&link, Some(&second));
    }

    async fn given_a_download_with_nothing_to_share_yet(&mut self) {
        self.add_download(&FileLink::of("pending.bin", 5000, [90; 16]).unwrap(), None);
        self.given_the_shared_file("known.bin", 3000, 24);
        self.given_the_uploads_started().await;
    }

    fn given_the_download_has_sources(&self, count: u8) {
        *self.download_sources.lock().unwrap() =
            (1..=count).map(|at| ipv4_source(Ipv4Addr::new(198, 51, 100, at), [100 + at; 16], None)).collect();
    }

    fn given_the_download_has_an_ipv4_source_and_one_at_its_ipv6_address_alone(&self) {
        *self.download_sources.lock().unwrap() = vec![
            ipv4_source(Ipv4Addr::new(198, 51, 100, 1), [101; 16], Some("2001:db8::60".parse().unwrap())),
            exchange::Source::ipv6_only("2001:db8::61".parse().unwrap(), 4662, Some([102; 16])),
        ];
    }

    fn given_we_have_the_ipv6_address(&mut self, address: &str) {
        self.our_ipv6 = Some(address.parse().unwrap());
    }

    async fn given_a_client_connected(&mut self, seed: u8) {
        self.client = seed;
        self.client_of(seed, 0).await;
    }

    async fn given_a_client_taking_the_extended_source_exchange_connected(&mut self, seed: u8) {
        let ipv6: Ipv6Addr = "2001:db8::1:1".parse().unwrap();
        let hello = Hello::of([seed; 16], 0, 5000 + u16::from(seed), &plain("extended"), 0, 0).with_ipv6(&ipv6);
        let client = Downloader::connect(self.port, &hello).await;
        self.clients.insert(seed, client);
        self.hellos.insert(seed, hello);
        self.client = seed;
    }

    async fn given_the_client_told_the_download_has_only_its_last_part(&mut self) {
        self.when_the_client_asks_for_the_download().await;
        self.then_the_status_tells_parts(2);
        self.then_the_status_lacks_part(0);
        self.then_the_status_has_part(1, "the checked part");
    }

    async fn given_no_blocks_sent_before_the_slot(&mut self) {
        let file = self.download;
        let seen = self.slot_asked_after_blocks(file, Range { start: PARTSIZE, end: PARTSIZE + 10 }).await;
        assert!(!seen.iter().any(is_sending_part), "no blocks before the slot");
    }

    async fn given_the_slot_given_again_with_nothing_of_another_file(&mut self) {
        let seen = self.slot_asked_after_blocks([78; 16], Range { start: 0, end: 10 }).await;
        assert!(!seen.iter().any(is_sending_part), "asked again, the slot is given again; nothing of another file");
    }

    async fn given_only_the_checked_part_sent(&mut self) {
        self.when_the_client_asks_for_blocks_of_both_parts().await;
        self.then_the_block_sent_starts_at(PARTSIZE, "only the checked part");
        self.then_the_block_sent_is_the_start_of_the_last_part(500);
    }

    async fn when_the_client_asks_for_the_download(&mut self) {
        let file = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&client_messages::for_file(client::SETREQFILEID, &file)).await;
        let seen = client.until(protocol::EDONKEY, client::FILESTATUS).await;
        self.status = Some(FileStatus::decode(seen.last().unwrap()).unwrap());
    }

    async fn when_the_client_asks_for_blocks_of_both_parts(&mut self) {
        let file = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        let ranges = [Range { start: 0, end: 1000 }, Range { start: PARTSIZE, end: PARTSIZE + 500 }];
        client.send(&blocks(file, &ranges)).await;
        let seen = client.until(protocol::EDONKEY, client::SENDINGPART).await;
        self.block = Some(SendingPart::decode(seen.last().unwrap()).unwrap());
    }

    async fn when_the_client_asks_for_the_sources_of_the_download(&mut self) {
        let file = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&exchange::request(&file)).await;
        let seen = client.until(protocol::EMULE, extended::ANSWERSOURCES2).await;
        self.told_sources = exchange::Answer::decode(seen.last().unwrap()).unwrap().sources;
    }

    async fn when_the_client_asks_for_the_sources_of_the_download_the_extended_way(&mut self) {
        let file = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&exchange::request_in_version(&file, exchange::EXTENDED_VERSION)).await;
        let seen = client.until(protocol::EMULE, extended::ANSWERSOURCES2).await;
        self.told_sources = exchange::Answer::decode_extended(seen.last().unwrap()).unwrap().sources;
    }

    async fn when_a_client_asks_for_it_and_for_unknown_files(&mut self, unknown: usize) {
        self.client = 30;
        let client = self.client_of(30, 0).await;
        client.send(&client_messages::for_file(client::SETREQFILEID, &[90; 16])).await;
        client.until(protocol::EDONKEY, client::FILEREQANSNOFIL).await;
        for at in 0..unknown {
            client.send(&client_messages::for_file(client::SETREQFILEID, &[100 + at as u8; 16])).await;
            client.until(protocol::EDONKEY, client::FILEREQANSNOFIL).await;
        }
    }

    fn then_the_status_tells_parts(&self, count: usize) {
        assert_eq!(self.status.as_ref().unwrap().part_count, count);
    }

    fn then_the_status_has_part(&self, part: usize, why: &str) {
        assert!(self.status.as_ref().unwrap().has(part), "{why}");
    }

    fn then_the_status_lacks_part(&self, part: usize) {
        assert!(!self.status.as_ref().unwrap().has(part));
    }

    fn then_the_block_sent_starts_at(&self, start: u64, why: &str) {
        assert_eq!(self.block.as_ref().unwrap().start, start, "{why}");
    }

    fn then_the_block_sent_is_the_start_of_the_last_part(&self, length: usize) {
        assert_eq!(self.block.as_ref().unwrap().data, self.last_part[..length]);
    }

    fn then_the_sources_told_are(&self, count: usize, why: &str) {
        assert_eq!(self.told_sources.len(), count, "{why}");
    }

    fn then_one_source_told_is_at(&self, ipv6: &str) {
        let alone: Vec<_> = self.told_sources.iter().filter(|source| source.is_ipv6_only()).collect();
        assert_eq!(alone.len(), 1);
        assert_eq!(alone[0].ipv6, Some(ipv6.parse().unwrap()));
    }

    async fn then_it_still_gets_answers(&mut self) {
        let file = self.files["known.bin"].hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
    }

    // Sources of a shared file: the clients waiting for it or downloading it.

    async fn given_a_file_with_a_waiting_client(&mut self) {
        self.given_the_settings(1, 100);
        self.given_the_shared_file("other.bin", 3000, 19);
        self.given_the_shared_file("sought.bin", 3000, 18);
        self.given_the_uploads_started().await;
        let file = self.files["sought.bin"].hash;
        for seed in 1..=3 {
            self.ask(seed, file).await;
        }
    }

    async fn given_a_client_that_asked_for_another_file(&mut self) {
        self.client = 4;
        let other = self.files["other.bin"].hash;
        let client = self.client_of(4, 0).await;
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &other)).await;
        client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
    }

    async fn given_a_client_told_its_sources(&mut self) {
        self.given_a_client_that_asked_for_another_file().await;
        self.then_it_hears_the_sources_once_it_asked_for_the_file().await;
        self.await_described(", 1 source answers", "the answer remembered").await;
    }

    // A client asking again right away is banned, and with it the address the slot holders share.
    async fn given_their_address_banned_for_a_client_that_asked_too_often(&mut self) {
        self.client = 5;
        self.given_the_client_waits_at(5, 1, "behind the busy slots").await;
        self.given_it_asked_again_right_away(upload_manager::BAD_LIMIT - 1).await;
        let file = self.file.as_ref().unwrap().hash;
        let eager = self.clients.get_mut(&5).unwrap();
        eager.send(&client_messages::for_file(client::STARTUPLOADREQ, &file)).await;
        assert!(eager.closed_within(WAIT).await, "banned");
        self.client = 1;
        self.file = Some(self.files["busy.bin"].clone());
    }

    async fn when_it_asks_for_the_sources_of_the_file(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        self.clients.get_mut(&self.client).unwrap().send(&exchange::request(&file)).await;
    }

    async fn when_a_slot_holder_asks_for_the_sources_of_its_file(&mut self) {
        self.when_it_asks_for_the_sources_of_the_file().await;
    }

    fn when_the_longest_wait_for_sources_passes(&self) {
        self.given_time_passed(Duration::from_secs(161 * 60));
    }

    async fn then_it_hears_no_sources(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        let seen = client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
        assert!(!seen.iter().any(is_source_answer), "no sources");
    }

    async fn then_it_hears_the_sources_once_it_asked_for_the_file(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
        client.send(&exchange::request(&file)).await;
        let seen = client.until(protocol::EMULE, extended::ANSWERSOURCES2).await;
        assert_eq!(exchange::Answer::decode(seen.last().unwrap()).unwrap().sources.len(), 3);
    }

    async fn then_no_source_answer_is_remembered(&self) {
        self.await_described(", 0 source answers", "the answer forgotten").await;
    }

    // A download in a queue of its own, with its second part from `second` checked.
    fn add_download(&mut self, link: &FileLink, second: Option<&[u8]>) {
        let mut lab = QueueLab::load();
        let hash = lab.queue.add(link, 0, DownloadPriority::Auto).unwrap();
        if let Some(data) = second {
            lab.queue.received(&hash, PARTSIZE, data, None);
            let deadline = Instant::now() + WAIT;
            while !part_file::has(lab.queue.get(&hash).unwrap().verified_parts(), 1) {
                assert!(Instant::now() < deadline, "the part checked");
                lab.next(Duration::from_millis(20), |_| None::<()>);
            }
        }
        let download = lab.queue.get(&hash).unwrap();
        let file = &download.file;
        let uploadable = Uploadable::partial(
            hash,
            file.name(),
            file.size(),
            file.part_hashes(),
            download.verified_parts(),
            file.data_file(),
            file.aich_hash(),
        );
        self.downloads.lock().unwrap().insert(hash, uploadable);
        self.download = hash;
        self.queue = Some(lab);
    }

    // Blocks asked for before the slot, then the slot: the packets up to it.
    async fn slot_asked_after_blocks(&mut self, file: [u8; 16], range: Range) -> Vec<Packet> {
        let download = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&blocks(file, &[range])).await;
        client.send(&client_messages::for_file(client::STARTUPLOADREQ, &download)).await;
        client.until(protocol::EDONKEY, client::ACCEPTUPLOADREQ).await
    }

    async fn await_described(&self, part: &str, why: &str) {
        let deadline = Instant::now() + WAIT;
        let mut described = String::new();
        while !described.contains(part) && Instant::now() < deadline {
            tokio::time::sleep(Duration::from_millis(20)).await;
            described = self.on_loop(|state| state.manager.describe()).await;
        }
        assert!(described.contains(part), "{why}: {described}");
    }

    // The other requests of a client.

    fn given_the_file_followed_is(&mut self, name: &str) {
        self.file = Some(self.files[name].clone());
    }

    async fn given_the_address_banned(&self, address: &str) {
        self.when_the_address_is_banned(address).await;
    }

    fn given_no_longer_shared(&self, name: &str) {
        self.shared.lock().unwrap().remove(&self.files[name].hash);
    }

    async fn when_it_asks_for_the_sources_of(&mut self, name: &str) {
        let file = self.files[name].hash;
        self.clients.get_mut(&self.client).unwrap().send(&exchange::request(&file)).await;
    }

    async fn when_it_reasks_for(&mut self, name: &str) {
        let file = self.files[name].hash;
        self.when_a_reask_for_the_file_comes_from(4672, file).await;
    }

    fn given_our_files_visible(&self) {
        *self.visible.lock().unwrap() = Some(self.shared.lock().unwrap().values().cloned().collect());
    }

    fn given_the_aich_hash_of_the_file(&self, aich_hash: [u8; 20]) {
        let mut file = self.file.clone().unwrap();
        file.aich_hash = Some(aich_hash);
        self.shared.lock().unwrap().insert(file.hash, file);
    }

    async fn given_the_files_of_the_directory_listed(&mut self, directory: &str) {
        self.when_the_client_asks_for_the_files_of(directory).await;
        self.then_the_files_listed_are(1);
    }

    async fn when_the_client_sends(&mut self, packet: Packet, answer: (u8, u8)) {
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&packet).await;
        let seen = client.until(answer.0, answer.1).await;
        self.answer = seen.last().cloned();
    }

    async fn when_the_client_asks_for_the_hash_set_of_the_download(&mut self) {
        let file = self.download;
        let packet = client_messages::for_file(client::HASHSETREQUEST, &file);
        self.when_the_client_sends(packet, (protocol::EDONKEY, client::HASHSETANSWER)).await;
    }

    async fn when_the_client_asks_for_our_directories(&mut self) {
        let packet = Packet::empty(protocol::EDONKEY, client::ASKSHAREDDIRS);
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&packet).await;
        self.answer = Some(client.next().await);
    }

    async fn when_the_client_asks_for_the_files_of(&mut self, directory: &str) {
        let mut out = PacketWriter::new();
        out.u16(directory.len() as u16).bytes(directory.as_bytes());
        let packet = Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESDIR, out);
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&packet).await;
        self.answer = Some(client.next().await);
    }

    async fn when_the_client_asks_for_its_public_address(&mut self) {
        let packet = Packet::empty(protocol::EMULE, extended::PUBLICIP_REQ);
        self.when_the_client_sends(packet, (protocol::EMULE, extended::PUBLICIP_ANSWER)).await;
    }

    async fn when_the_client_asks_for_the_aich_hash_of_the_file(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let packet = Packet::of(protocol::EMULE, extended::AICHFILEHASHREQ, file.to_vec());
        self.when_the_client_sends(packet, (protocol::EMULE, extended::AICHFILEHASHANS)).await;
    }

    async fn when_the_client_asks_for_aich_recovery_data(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let mut out = PacketWriter::new();
        out.hash16(&file).u16(0).bytes(&[0xA1; 20]);
        let packet = Packet::written(protocol::EMULE, extended::AICHREQUEST, out);
        self.when_the_client_sends(packet, (protocol::EMULE, extended::AICHANSWER)).await;
    }

    async fn when_the_client_cancels_its_transfer(&mut self) {
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&Packet::empty(protocol::EDONKEY, client::CANCELTRANSFER)).await;
    }

    async fn given_the_sources_of_the_download_told(&mut self, count: usize) {
        self.when_the_client_asks_for_the_sources_of_the_download().await;
        self.then_the_sources_told_are(count, "the download's sources, packed");
    }

    async fn given_the_sources_asked_again_at_once_by_an_old_client_and_for_a_file_we_do_not_have(&mut self) {
        let file = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&exchange::request(&file)).await;
        client.send(&exchange::request_in_version(&file, 0)).await;
        client.send(&exchange::request(&[77; 16])).await;
    }

    async fn given_the_client_confirmed_it_reached_us(&mut self) {
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&Packet::empty(protocol::EMULE, extended::KAD_FWTCPCHECK_ACK)).await;
    }

    async fn given_packets_of_an_unknown_opcode(&mut self, opcode: u8) {
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&Packet::empty(protocol::EMULE, opcode)).await;
        client.send(&Packet::empty(protocol::EDONKEY, opcode)).await;
    }

    // Listed to anybody, as our files are visible.
    async fn given_the_shared_directory_listed_empty(&mut self, directory: &str) {
        self.given_our_files_visible();
        let client = self.clients.get_mut(&self.client).unwrap();
        let mut asked = PacketWriter::new();
        asked.u16(u16::try_from(directory.len()).unwrap()).bytes(directory.as_bytes());
        client.send(&Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESDIR, asked)).await;
        let seen = client.until(protocol::EDONKEY, client::ASKSHAREDFILESDIRANS).await;
        let listing = seen.last().unwrap();
        let mut input = listing.reader();
        let length = usize::from(input.u16().unwrap());
        input.bytes(length).unwrap();
        assert_eq!(input.u32().unwrap(), 0, "no files in another directory");
    }

    async fn given_kad_told_of_the_address_that_reached_us(&self, address: &str) {
        let ip = u32::from(address.parse::<Ipv4Addr>().unwrap());
        self.await_kad_told("the confirmation").await;
        assert_eq!(self.on_loop(|state| state.kad.clone()).await, vec![KadRequest::Reached(ip)]);
    }

    async fn given_no_sources_told_when_the_client_asks_for_the_file_name(&mut self) {
        let file = self.download;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        let seen = client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
        assert!(
            !seen
                .iter()
                .any(|packet| (packet.protocol(), packet.opcode()) == (protocol::EMULE, extended::ANSWERSOURCES2)),
            "no sources told"
        );
    }

    async fn when_it_asks_for_its_udp_test(&mut self, request: UdpCheckRequest) {
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&request.encode()).await;
    }

    async fn then_kad_hears_of_the_udp_test(&self, ip: &str, internal_port: u16, external_port: u16, known: bool) {
        self.await_kad_told("the UDP test request").await;
        let request = UdpCheckRequest { internal_port, external_port, sender_key: 99 };
        let ip = u32::from(ip.parse::<Ipv4Addr>().unwrap());
        let expected = KadRequest::UdpCheck { ip, request, known };
        assert_eq!(self.on_loop(|state| state.kad.clone()).await, vec![expected]);
    }

    async fn then_the_credits_are_saved_within(&self, duration: Duration) {
        self.given_time_passed(duration);
        let saved = self.dir.path().join(credits::FILE_NAME);
        let deadline = Instant::now() + WAIT;
        while !saved.exists() {
            assert!(Instant::now() < deadline, "waited in vain for the credits saved");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn await_kad_told(&self, what: &str) {
        let deadline = Instant::now() + WAIT;
        while self.on_loop(|state| state.kad.is_empty()).await {
            assert!(Instant::now() < deadline, "waited in vain for {what}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn when_the_client_sends_a_request_that_does_not_decode(&mut self) {
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&Packet::of(protocol::EDONKEY, client::REQUESTPARTS, vec![1])).await;
    }

    async fn when_it_asks_again_at_once_as_an_old_client_and_for_a_file_we_do_not_have(&mut self) {
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&exchange::request(&file)).await;
        client.send(&exchange::request_in_version(&file, 0)).await;
        client.send(&exchange::request(&[77; 16])).await;
    }

    async fn when_the_uploads_close(&self) {
        self.on_loop(|state| state.manager.close()).await;
    }

    fn then_the_hash_set_told_has_parts(&self, count: usize) {
        let answer = client_messages::HashSet::decode(self.answer.as_ref().unwrap()).unwrap();
        assert_eq!((answer.file, answer.parts.len()), (self.download, count));
    }

    fn then_the_answer_is(&self, answer: (u8, u8)) {
        let packet = self.answer.as_ref().unwrap();
        assert_eq!((packet.protocol(), packet.opcode()), answer);
    }

    fn then_the_directory_listed_is(&self, directory: &str) {
        self.then_the_answer_is((protocol::EDONKEY, client::ASKSHAREDDIRSANS));
        let mut input = self.answer.as_ref().unwrap().reader();
        assert_eq!(input.u32().unwrap(), 1);
        assert_eq!(input.string16().unwrap(), directory);
    }

    fn then_the_files_listed_are(&self, count: u32) {
        self.then_the_answer_is((protocol::EDONKEY, client::ASKSHAREDFILESDIRANS));
        let mut input = self.answer.as_ref().unwrap().reader();
        let length = usize::from(input.u16().unwrap());
        input.bytes(length).unwrap();
        assert_eq!(input.u32().unwrap(), count);
    }

    // The ask, then a request for the file's name: what comes up to its answer.
    async fn when_the_client_asks_again_for_the_files_of(&mut self, directory: &str) {
        let mut out = PacketWriter::new();
        out.u16(directory.len() as u16).bytes(directory.as_bytes());
        let file = self.file.as_ref().unwrap().hash;
        let client = self.clients.get_mut(&self.client).unwrap();
        client.send(&Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESDIR, out)).await;
        client.send(&client_messages::for_file(client::REQUESTFILENAME, &file)).await;
        self.seen = client.until(protocol::EDONKEY, client::REQFILENAMEANSWER).await;
    }

    fn then_our_files_are_not_listed_again(&self) {
        assert_eq!(self.seen.len(), 1, "only the name");
    }

    fn then_the_answer_carries(&self, payload: &[u8]) {
        assert_eq!(self.answer.as_ref().unwrap().payload(), payload);
    }

    fn then_the_answer_carries_the_file_hash_then(&self, rest: &[u8]) {
        let file = self.file.as_ref().unwrap().hash;
        self.then_the_answer_carries(&[&file[..], rest].concat());
    }

    async fn when_it_reasks_right_away(&mut self, times: u32) {
        let file = self.file.as_ref().unwrap().hash;
        for _ in 0..times {
            self.given_time_passed(Duration::from_secs(1));
            self.when_a_reask_for_the_file_comes_from(4672, file).await;
        }
    }

    async fn then_the_address_is_banned_for_asking_too_often(&self) {
        let bans = self.on_loop(|state| state.manager.bans(state.now())).await;
        assert_eq!(bans.len(), 1);
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

fn random(size: usize, seed: u8) -> Vec<u8> {
    (0..size).map(|at| ((at * 131 + usize::from(seed) * 17) % 251) as u8).collect()
}

fn ipv4_source(ip: Ipv4Addr, user_hash: [u8; 16], ipv6: Option<Ipv6Addr>) -> exchange::Source {
    exchange::Source {
        ip: u32::from(ip),
        low_id: 0,
        port: 4662,
        server_ip: 0,
        server_port: 0,
        user_hash: Some(user_hash),
        crypt_options: 0,
        ipv6,
    }
}

fn is_sending_part(packet: &Packet) -> bool {
    packet.protocol() == protocol::EDONKEY && packet.opcode() == client::SENDINGPART
}

fn is_source_answer(packet: &Packet) -> bool {
    packet.protocol() == protocol::EMULE && packet.opcode() == extended::ANSWERSOURCES2
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

#[tokio::test]
async fn should_tell_a_client_waiting_for_the_file_its_place_over_udp() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_waiting_that_takes_udp_reasks_on_port(4672).await;

    let file = uploads.file.as_ref().unwrap().hash;
    uploads.when_a_reask_for_the_file_comes_from(4672, file).await;

    uploads.then_the_answer_tells_the_place(1);
}

#[tokio::test]
async fn should_answer_nothing_to_a_reask_from_another_udp_port_of_the_address() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_waiting_that_takes_udp_reasks_on_port(4672).await;

    let file = uploads.file.as_ref().unwrap().hash;
    uploads.when_a_reask_for_the_file_comes_from(4673, file).await;

    uploads.then_there_is_no_answer();
}

#[tokio::test]
async fn should_tell_anyone_over_udp_that_we_do_not_share_the_file() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;

    uploads.when_a_reask_for_the_file_comes_from(4672, [0x9D; 16]).await;

    uploads.then_the_answer_is_plain(client_reasks::file_not_found());
}

#[tokio::test]
async fn should_count_the_request_by_name_the_slot_and_the_bytes_sent_of_a_file() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("counted.bin", 3000, 41);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_that_asked_for_the_file_by_name().await;
    uploads.given_the_client_got_a_slot(1).await;

    uploads.when_it_downloads_the_file().await;

    uploads.then_the_file_was_counted(&["asked", "accepted", "sent 3000"]);
}

#[tokio::test]
async fn should_read_a_block_where_its_file_went() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("moved.txt", 4000, 12);
    uploads.given_the_file_listed_at("gone.txt");
    uploads.given_the_uploads_started().await;
    uploads.client = 1;
    uploads.given_the_client_got_a_slot(1).await;
    uploads.given_the_file_found_where_it_went();

    uploads.when_the_client_asks_for_the_block(0, 1000).await;

    uploads.then_the_client_receives_the_block(0, 1000).await;
}

#[tokio::test]
async fn should_end_the_slot_once_its_file_is_gone() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("moved.txt", 4000, 12);
    uploads.given_the_file_listed_at("gone.txt");
    uploads.given_the_uploads_started().await;
    uploads.client = 1;
    uploads.given_the_client_got_a_slot(1).await;
    uploads.given_the_file_found_where_it_went();
    uploads.when_the_client_asks_for_the_block(0, 1000).await;
    uploads.then_the_client_receives_the_block(0, 1000).await;
    uploads.given_the_file_no_longer_shared();

    uploads.when_the_client_asks_for_the_block(1000, 2000).await;

    uploads.then_the_connection_ends().await;
    uploads.then_the_slot_is_gone().await;
}

#[tokio::test]
async fn should_queue_little_for_a_client_that_reads_nothing_and_let_our_answers_pass_its_data() {
    let mut uploads = Uploads::new();
    uploads.given_a_client_with_a_slot_that_asks_for_many_blocks_and_reads_nothing().await;

    uploads.when_it_asks_for_the_file_name_and_reads().await;

    uploads.then_little_of_what_it_asked_for_was_queued();
    uploads.then_our_answer_came_before_most_of_the_data();
}

#[tokio::test]
async fn should_share_only_the_checked_parts_of_a_download() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_only_its_last_part_checked("partial.bin", 1000, 11);
    uploads.given_the_download_has_sources(20);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;
    uploads.given_the_client_told_the_download_has_only_its_last_part().await;
    uploads.given_no_blocks_sent_before_the_slot().await;
    uploads.given_the_slot_given_again_with_nothing_of_another_file().await;

    uploads.when_the_client_asks_for_blocks_of_both_parts().await;

    uploads.then_the_block_sent_starts_at(PARTSIZE, "only the checked part");
    uploads.then_the_block_sent_is_the_start_of_the_last_part(500);
}

#[tokio::test]
async fn should_share_ipv6_sources_the_extended_way_with_a_client_taking_it() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_only_its_last_part_checked("six.bin", 1000, 11);
    uploads.given_the_download_has_an_ipv4_source_and_one_at_its_ipv6_address_alone();
    uploads.given_we_have_the_ipv6_address("2001:db8::99");
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_taking_the_extended_source_exchange_connected(1).await;
    uploads.given_the_client_told_the_download_has_only_its_last_part().await;
    uploads.given_no_blocks_sent_before_the_slot().await;
    uploads.given_the_slot_given_again_with_nothing_of_another_file().await;
    uploads.given_only_the_checked_part_sent().await;

    uploads.when_the_client_asks_for_the_sources_of_the_download_the_extended_way().await;

    uploads
        .then_the_sources_told_are(2, "the IPv4 source with its IPv6 address, and the one at its IPv6 address alone");
    uploads.then_one_source_told_is_at("2001:db8::61");
}

#[tokio::test]
async fn should_leave_sources_at_an_ipv6_address_alone_out_for_a_client_not_taking_the_extended_way() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_only_its_last_part_checked("six.bin", 1000, 11);
    uploads.given_the_download_has_an_ipv4_source_and_one_at_its_ipv6_address_alone();
    uploads.given_we_have_the_ipv6_address("2001:db8::99");
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;
    uploads.given_the_client_told_the_download_has_only_its_last_part().await;
    uploads.given_no_blocks_sent_before_the_slot().await;
    uploads.given_the_slot_given_again_with_nothing_of_another_file().await;
    uploads.given_only_the_checked_part_sent().await;

    uploads.when_the_client_asks_for_the_sources_of_the_download().await;

    uploads.then_the_sources_told_are(1, "only the IPv4 source");
}

#[tokio::test]
async fn should_share_the_sources_of_a_download() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_only_its_last_part_checked("partial.bin", 1000, 11);
    uploads.given_the_download_has_sources(20);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;
    uploads.given_the_client_told_the_download_has_only_its_last_part().await;
    uploads.given_no_blocks_sent_before_the_slot().await;
    uploads.given_the_slot_given_again_with_nothing_of_another_file().await;
    uploads.given_only_the_checked_part_sent().await;

    uploads.when_the_client_asks_for_the_sources_of_the_download().await;

    uploads.then_the_sources_told_are(20, "the download's sources, packed");
}

#[tokio::test]
async fn should_tell_the_empty_last_part_of_a_download_of_whole_parts_too() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_of_whole_parts_with_only_its_second_checked("whole.bin", 15);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_the_download().await;

    uploads.then_the_status_tells_parts(3);
    uploads.then_the_status_lacks_part(0);
    uploads.then_the_status_has_part(1, "the checked part");
    uploads.then_the_status_has_part(2, "the empty part a whole number of parts ends with");
}

#[tokio::test]
async fn should_not_count_files_we_download_as_unknown() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_nothing_to_share_yet().await;

    uploads.when_a_client_asks_for_it_and_for_unknown_files(upload_session::UNKNOWN_FILES_LIMIT - 1).await;

    uploads.then_it_still_gets_answers().await;
}

#[tokio::test]
async fn should_tell_sources_only_for_a_file_the_client_asked_for_on_its_connection() {
    let mut uploads = Uploads::new();
    uploads.given_a_file_with_a_waiting_client().await;
    uploads.given_a_client_that_asked_for_another_file().await;

    uploads.when_it_asks_for_the_sources_of_the_file().await;

    uploads.then_it_hears_no_sources().await;
    uploads.then_it_hears_the_sources_once_it_asked_for_the_file().await;
}

#[tokio::test]
async fn should_tell_no_sources_to_a_client_at_a_banned_address() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_their_address_banned_for_a_client_that_asked_too_often().await;

    uploads.when_a_slot_holder_asks_for_the_sources_of_its_file().await;

    uploads.then_it_hears_no_sources().await;
}

#[tokio::test]
async fn should_forget_the_source_answers_given_once_they_can_be_asked_again() {
    let mut uploads = Uploads::new();
    uploads.given_a_file_with_a_waiting_client().await;
    uploads.given_a_client_told_its_sources().await;

    uploads.when_the_longest_wait_for_sources_passes();

    uploads.then_no_source_answer_is_remembered().await;
}

// Beyond the Java engine's twins: requests its engine tests send through a whole engine, and the edges of the queue.

#[tokio::test]
async fn should_tell_a_client_the_hash_set_of_a_download() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_only_its_last_part_checked("partial.bin", 1000, 11);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_the_hash_set_of_the_download().await;

    uploads.then_the_hash_set_told_has_parts(2);
}

#[tokio::test]
async fn should_deny_our_directories_when_nobody_may_see_our_files() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("seen.bin", 3000, 1);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_our_directories().await;

    uploads.then_the_answer_is((protocol::EDONKEY, client::ASKSHAREDDENIEDANS));
}

#[tokio::test]
async fn should_list_one_pseudo_directory_when_our_files_may_be_seen() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("seen.bin", 3000, 1);
    uploads.given_our_files_visible();
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_our_directories().await;

    uploads.then_the_directory_listed_is(FILES_DIRECTORY);
}

#[tokio::test]
async fn should_deny_the_files_of_a_directory_when_nobody_may_see_them() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("seen.bin", 3000, 1);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_the_files_of(FILES_DIRECTORY).await;

    uploads.then_the_answer_is((protocol::EDONKEY, client::ASKSHAREDDENIEDANS));
}

#[tokio::test]
async fn should_list_our_files_in_the_pseudo_directory() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("seen.bin", 3000, 1);
    uploads.given_our_files_visible();
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_the_files_of(FILES_DIRECTORY).await;

    uploads.then_the_files_listed_are(1);
}

#[tokio::test]
async fn should_list_no_file_in_another_directory() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("seen.bin", 3000, 1);
    uploads.given_our_files_visible();
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_the_files_of("Films").await;

    uploads.then_the_files_listed_are(0);
}

#[tokio::test]
async fn should_list_our_files_once_a_connection() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("seen.bin", 3000, 1);
    uploads.given_our_files_visible();
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;
    uploads.given_the_files_of_the_directory_listed(FILES_DIRECTORY).await;

    uploads.when_the_client_asks_again_for_the_files_of(FILES_DIRECTORY).await;

    uploads.then_our_files_are_not_listed_again();
}

#[tokio::test]
async fn should_tell_a_client_its_public_address() {
    let mut uploads = Uploads::new();
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_its_public_address().await;

    uploads.then_the_answer_carries(&[127, 0, 0, 1]);
}

#[tokio::test]
async fn should_tell_the_aich_hash_of_a_file_that_has_one() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("hashed.bin", 3000, 1);
    uploads.given_the_aich_hash_of_the_file([0xA1; 20]);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_the_aich_hash_of_the_file().await;

    uploads.then_the_answer_carries_the_file_hash_then(&[0xA1; 20]);
}

#[tokio::test]
async fn should_answer_a_request_for_aich_recovery_data_with_the_file_hash_alone() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("hashed.bin", 3000, 1);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_asks_for_aich_recovery_data().await;

    uploads.then_the_answer_carries_the_file_hash_then(&[]);
}

#[tokio::test]
async fn should_end_the_slot_of_a_client_that_cancels_its_transfer() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("wanted.bin", 3000, 1);
    uploads.given_the_uploads_started().await;
    uploads.client = 1;
    uploads.given_the_client_got_a_slot(1).await;

    uploads.when_the_client_cancels_its_transfer().await;

    uploads.then_the_slot_is_gone().await;
}

#[tokio::test]
async fn should_close_the_connection_of_a_client_whose_request_does_not_decode() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("wanted.bin", 3000, 1);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;

    uploads.when_the_client_sends_a_request_that_does_not_decode().await;

    uploads.then_the_connection_ends().await;
}

#[tokio::test]
async fn should_tell_no_sources_again_at_once_nor_to_an_old_client_nor_for_a_file_we_do_not_have() {
    let mut uploads = Uploads::new();
    uploads.given_a_file_with_a_waiting_client().await;
    uploads.given_a_client_told_its_sources().await;

    uploads.when_it_asks_again_at_once_as_an_old_client_and_for_a_file_we_do_not_have().await;

    uploads.then_it_hears_no_sources().await;
}

#[tokio::test]
async fn should_tell_no_sources_of_a_file_nobody_else_takes() {
    let mut uploads = Uploads::new();
    uploads.given_a_file_with_a_waiting_client().await;
    uploads.given_a_client_that_asked_for_another_file().await;
    uploads.given_the_file_followed_is("other.bin");

    uploads.when_it_asks_for_the_sources_of_the_file().await;

    uploads.then_it_hears_no_sources().await;
}

#[tokio::test]
async fn should_end_the_slot_and_the_connection_whose_block_cannot_be_read() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("moved.txt", 4000, 12);
    uploads.given_the_file_listed_at("gone.txt");
    uploads.given_the_uploads_started().await;
    uploads.client = 1;
    uploads.given_the_client_got_a_slot(1).await;

    uploads.when_the_client_asks_for_the_block(0, 1000).await;

    uploads.then_the_connection_ends().await;
    uploads.then_the_slot_is_gone().await;
}

#[tokio::test]
async fn should_end_the_slots_when_the_uploads_close() {
    let mut uploads = Uploads::new();
    uploads.given_the_shared_file("wanted.bin", 3000, 1);
    uploads.given_the_uploads_started().await;
    uploads.client = 1;
    uploads.given_the_client_got_a_slot(1).await;

    uploads.when_the_uploads_close().await;

    uploads.then_the_connection_ends().await;
}

#[tokio::test]
async fn should_answer_no_reask_from_a_banned_address() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_waiting_that_takes_udp_reasks_on_port(4672).await;
    uploads.given_the_address_banned("127.0.0.1").await;

    uploads.when_it_reasks_right_away(1).await;

    uploads.then_there_is_no_answer();
}

#[tokio::test]
async fn should_ban_a_client_reasking_too_often_over_udp() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_waiting_that_takes_udp_reasks_on_port(4672).await;

    uploads.when_it_reasks_right_away(upload_manager::BAD_LIMIT).await;

    uploads.then_there_is_no_answer();
    uploads.then_the_address_is_banned_for_asking_too_often().await;
}

#[tokio::test]
async fn should_tell_no_sources_of_a_file_no_longer_shared() {
    let mut uploads = Uploads::new();
    uploads.given_a_file_with_a_waiting_client().await;
    uploads.given_a_client_that_asked_for_another_file().await;
    uploads.given_no_longer_shared("other.bin");

    uploads.when_it_asks_for_the_sources_of("other.bin").await;

    uploads.then_it_hears_no_sources().await;
}

#[tokio::test]
async fn should_answer_nothing_to_a_reask_for_another_file_than_the_one_awaited() {
    let mut uploads = Uploads::new();
    uploads.given_two_slots_busy_under_a_limit().await;
    uploads.given_a_client_waiting_that_takes_udp_reasks_on_port(4672).await;

    uploads.when_it_reasks_for("busy.bin").await;

    uploads.then_there_is_no_answer();
}

#[tokio::test]
async fn should_end_the_connection_on_a_request_that_does_not_decode_and_save_the_credits() {
    let mut uploads = Uploads::new();
    uploads.given_a_download_with_only_its_last_part_checked("partial.bin", 1000, 11);
    uploads.given_the_download_has_sources(20);
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(1).await;
    uploads.given_the_client_told_the_download_has_only_its_last_part().await;
    uploads.given_no_blocks_sent_before_the_slot().await;
    uploads.given_the_slot_given_again_with_nothing_of_another_file().await;
    uploads.given_only_the_checked_part_sent().await;
    uploads.given_the_sources_of_the_download_told(20).await;
    uploads.given_the_sources_asked_again_at_once_by_an_old_client_and_for_a_file_we_do_not_have().await;
    uploads.given_the_client_confirmed_it_reached_us().await;
    uploads.given_packets_of_an_unknown_opcode(0x7F).await;
    uploads.given_the_shared_directory_listed_empty("Films").await;
    uploads.given_kad_told_of_the_address_that_reached_us("127.0.0.1").await;
    // Asked again at once, or by an old client, or for a file we do not have: no answer.
    uploads.given_no_sources_told_when_the_client_asks_for_the_file_name().await;

    uploads.when_the_client_sends_a_request_that_does_not_decode().await;

    uploads.then_the_connection_ends().await;
    uploads.then_the_credits_are_saved_within(Duration::from_secs(13 * 60)).await;
}

#[tokio::test]
async fn should_pass_a_clients_udp_test_request_to_kad_with_its_address() {
    let mut uploads = Uploads::new();
    uploads.given_the_uploads_started().await;
    uploads.given_a_client_connected(21).await;

    uploads
        .when_it_asks_for_its_udp_test(UdpCheckRequest { internal_port: 4673, external_port: 5000, sender_key: 99 })
        .await;

    uploads.then_kad_hears_of_the_udp_test("127.0.0.1", 4673, 5000, false).await;
}
