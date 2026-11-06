// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! An eD2K server a test scripts, as the Java engine's tests have it: it answers each login with the id it was given,
//! sends what the test asks to the last client, and records what it receives. A connection that opens with an
//! obfuscation handshake is closed at once, as by a server that cannot obfuscate, unless the server was started to take
//! them. With it, the session the tests drive and the notices it tells.

#![allow(
    dead_code,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use njmule_engine::config::obfuscation_mode::ObfuscationMode;
use njmule_engine::config::preferences::Preferences;
use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connection::{FrameReader, FrameWriter};
use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::crypt::stream_obfuscation;
use njmule_engine::protocol::ed2k::server_messages::IdChange;
use njmule_engine::protocol::packet::opcodes::server;
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use njmule_engine::server::server_list::ServerList;
use njmule_engine::server::server_session::{Context, Notice, Outcome, ServerSession};
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio::task::{AbortHandle, JoinHandle};

pub const WAIT: Duration = Duration::from_secs(10);
pub const USER_HASH: [u8; 16] = [1; 16];
/// The id the scripted servers give, 1.2.3.4 as a HighID.
pub const HIGH_ID: u32 = 0x0403_0201;

pub struct ScriptedServer {
    address: SocketAddr,
    accepting: JoinHandle<()>,
    shared: Arc<Shared>,
    received: tokio::sync::Mutex<mpsc::UnboundedReceiver<Packet>>,
}

struct Shared {
    client_id: u32,
    flags: u32,
    obfuscates: bool,
    connections: AtomicU32,
    obfuscated_logins: AtomicU32,
    clients: Mutex<Vec<Client>>,
    received: mpsc::UnboundedSender<Packet>,
}

struct Client {
    outgoing: mpsc::UnboundedSender<Packet>,
    tasks: Vec<AbortHandle>,
}

impl ScriptedServer {
    /// A server on the loopback that logs clients in with `client_id` and announces `flags`; 0 for no answer.
    pub async fn start(client_id: u32, flags: u32) -> Self {
        Self::listen(Ipv4Addr::LOCALHOST, client_id, flags, false).await
    }

    /// As [`Self::start`], taking obfuscated connections too.
    pub async fn start_obfuscating(client_id: u32, flags: u32) -> Self {
        Self::listen(Ipv4Addr::LOCALHOST, client_id, flags, true).await
    }

    /// As [`Self::start`], on another address of this computer: one the server list takes, unlike the loopback.
    pub async fn start_at(address: Ipv4Addr, client_id: u32, flags: u32) -> Self {
        Self::listen(address, client_id, flags, false).await
    }

    /// This computer's address on its network, which the server list takes; none without one, when the test passes
    /// over. Asking the system for its route to a documentation address sends nothing.
    pub fn lan_address() -> Option<Ipv4Addr> {
        let probe = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
        let found = probe.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok().and_then(|()| probe.local_addr().ok());
        match found.map(|address| address.ip()) {
            Some(IpAddr::V4(v4)) if !v4.is_loopback() && !v4.is_unspecified() && !v4.is_link_local() => Some(v4),
            _ => {
                eprintln!("no address on a network: the test passes over");
                None
            }
        }
    }

    async fn listen(address: Ipv4Addr, client_id: u32, flags: u32, obfuscates: bool) -> Self {
        let listener = TcpListener::bind((address, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        let (received_in, received) = mpsc::unbounded_channel();
        let shared = Arc::new(Shared {
            client_id,
            flags,
            obfuscates,
            connections: AtomicU32::new(0),
            obfuscated_logins: AtomicU32::new(0),
            clients: Mutex::new(Vec::new()),
            received: received_in,
        });
        let serving = Arc::clone(&shared);
        let accepting = tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                serving.connections.fetch_add(1, Ordering::SeqCst);
                tokio::spawn(serve(Arc::clone(&serving), stream));
            }
        });
        Self { address, accepting, shared, received: tokio::sync::Mutex::new(received) }
    }

    pub fn ip(&self) -> u32 {
        match self.address.ip() {
            IpAddr::V4(v4) => u32::from(v4),
            IpAddr::V6(_) => 0,
        }
    }

    pub fn port(&self) -> u16 {
        self.address.port()
    }

    /// The server as a list knows it, never pinged.
    pub fn server(&self) -> Server {
        Server { ip: self.ip(), port: self.port(), tags: Vec::new() }
    }

    /// Connections accepted so far, obfuscated or not.
    pub fn connections(&self) -> u32 {
        self.shared.connections.load(Ordering::SeqCst)
    }

    /// Logins received over obfuscated connections.
    pub fn obfuscated_logins(&self) -> u32 {
        self.shared.obfuscated_logins.load(Ordering::SeqCst)
    }

    /// Sends `packets` to the client that connected last, once one did.
    pub async fn send(&self, packets: &[Packet]) {
        let deadline = tokio::time::Instant::now() + WAIT;
        loop {
            let last = self.shared.clients.lock().unwrap().last().map(|client| client.outgoing.clone());
            if let Some(outgoing) = last {
                for packet in packets {
                    outgoing.send(packet.clone()).unwrap();
                }
                return;
            }
            assert!(tokio::time::Instant::now() < deadline, "no client connected");
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    /// The next packet received with `opcode`, the others before it dropped; none after `wait`.
    pub async fn next(&self, opcode: u8, wait: Duration) -> Option<Packet> {
        let mut received = self.received.lock().await;
        tokio::time::timeout(wait, async {
            loop {
                let packet = received.recv().await?;
                if packet.opcode() == opcode {
                    return Some(packet);
                }
            }
        })
        .await
        .ok()
        .flatten()
    }

    /// Closes the connections of the clients, as a server that goes away; a client may connect again.
    pub fn hang_up(&self) {
        let clients: Vec<Client> = self.shared.clients.lock().unwrap().drain(..).collect();
        for client in clients {
            for task in client.tasks {
                task.abort();
            }
        }
    }

    /// Stops listening and hangs up; once it returns, connections to the server are refused.
    pub async fn close(&self) {
        self.accepting.abort();
        while !self.accepting.is_finished() {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        self.hang_up();
    }
}

impl Drop for ScriptedServer {
    fn drop(&mut self) {
        self.accepting.abort();
        self.hang_up();
    }
}

async fn serve(shared: Arc<Shared>, stream: TcpStream) {
    let mut first = [0u8; 1];
    if stream.peek(&mut first).await.unwrap_or(0) == 0 {
        return;
    }
    let obfuscated = !protocol::is_tcp(first[0]);
    if obfuscated && !shared.obfuscates {
        return;
    }
    let (mut input, mut output) = stream.into_split();
    let (receive, send) = if obfuscated {
        let mut marker = [0u8; 1];
        if input.read_exact(&mut marker).await.is_err() {
            return;
        }
        let mut random = StdRng::from_rng(&mut rand::rng());
        let Ok(ciphers) = stream_obfuscation::accept_as_server(&mut input, &mut output, &mut random).await else {
            return;
        };
        (Some(ciphers.receive), Some(ciphers.send))
    } else {
        (None, None)
    };
    let mut reader = FrameReader::new(input, receive);
    let mut writer = FrameWriter::new(output, send);
    let (outgoing, mut sending) = mpsc::unbounded_channel::<Packet>();
    let writing = tokio::spawn(async move {
        while let Some(packet) = sending.recv().await {
            if writer.write_packet(&packet).await.is_err() {
                return;
            }
        }
    });
    let answers = outgoing.clone();
    let reading_shared = Arc::clone(&shared);
    let reading = tokio::spawn(async move {
        let shared = reading_shared;
        while let Ok(Some(packet)) = reader.read_packet(&frame_limits::server).await {
            let login = packet.opcode() == server::LOGINREQUEST;
            let _ = shared.received.send(packet);
            if login {
                if obfuscated {
                    shared.obfuscated_logins.fetch_add(1, Ordering::SeqCst);
                }
                if shared.client_id != 0 {
                    let id = IdChange { client_id: shared.client_id, server_flags: shared.flags, reported_ip: 0 };
                    let _ = answers.send(id.encode());
                }
            }
        }
    });
    let tasks = vec![writing.abort_handle(), reading.abort_handle()];
    shared.clients.lock().unwrap().push(Client { outgoing, tasks });
}

/// The session a test drives, with what it was given and the notices it told.
pub struct Harness {
    pub session: ServerSession,
    pub servers: ServerList,
    pub preferences: Preferences,
    pub keep_connected: bool,
    outcomes: mpsc::UnboundedReceiver<Outcome>,
    // Notices told but not yet looked at, and every notice told.
    told: VecDeque<Notice>,
    pub seen: Vec<Notice>,
}

impl Harness {
    pub fn new(obfuscation: ObfuscationMode) -> Self {
        let (outcomes_in, outcomes) = mpsc::unbounded_channel();
        Self {
            session: ServerSession::new(outcomes_in),
            servers: ServerList::default(),
            preferences: Preferences { obfuscation, ..Preferences::default() },
            keep_connected: false,
            outcomes,
            told: VecDeque::new(),
            seen: Vec::new(),
        }
    }

    /// Connects to `target`, or to any server of the list.
    pub fn connect(&mut self, target: Option<Server>) {
        self.with(|session, ctx| session.connect(target, ctx));
    }

    pub fn disconnect(&mut self) {
        self.with(ServerSession::disconnect);
    }

    pub fn with<T>(&mut self, work: impl FnOnce(&mut ServerSession, &mut Context<'_>) -> T) -> T {
        let mut notices = Vec::new();
        let routes = Routes::direct();
        let mut ctx = Context {
            servers: &mut self.servers,
            preferences: &self.preferences,
            routes: &routes,
            user_hash: USER_HASH,
            listen_port: 4662,
            keep_connected: self.keep_connected,
            notices: &mut notices,
        };
        let done = work(&mut self.session, &mut ctx);
        self.seen.extend(notices.iter().cloned());
        self.told.extend(notices);
        done
    }

    /// Handles what the session's tasks report until a notice matches, within `wait`.
    pub async fn until_within(&mut self, wait: Duration, wanted: impl Fn(&Notice) -> bool) -> Notice {
        let waiting = async {
            loop {
                while let Some(notice) = self.told.pop_front() {
                    if wanted(&notice) {
                        return notice;
                    }
                }
                let outcome = self.outcomes.recv().await.expect("the session's outcomes");
                self.with(|session, ctx| session.handle(outcome, ctx));
            }
        };
        tokio::time::timeout(wait, waiting).await.unwrap_or_else(|_| panic!("no such notice in {:?}", self.seen))
    }

    pub async fn until(&mut self, wanted: impl Fn(&Notice) -> bool) -> Notice {
        self.until_within(WAIT, wanted).await
    }

    /// Handles what the session's tasks report while `work` runs, until it ends.
    pub async fn during<T>(&mut self, work: impl Future<Output = T>) -> T {
        tokio::pin!(work);
        loop {
            tokio::select! {
                done = &mut work => return done,
                Some(outcome) = self.outcomes.recv() => self.with(|session, ctx| session.handle(outcome, ctx)),
            }
        }
    }

    /// Handles what the session's tasks report for `time`.
    pub async fn run_for(&mut self, time: Duration) {
        let deadline = tokio::time::Instant::now() + time;
        while let Ok(Some(outcome)) = tokio::time::timeout_at(deadline, self.outcomes.recv()).await {
            self.with(|session, ctx| session.handle(outcome, ctx));
        }
    }
}

pub fn connected(notice: &Notice) -> bool {
    matches!(notice, Notice::Connected { .. })
}

pub fn disconnected(notice: &Notice) -> bool {
    matches!(notice, Notice::Disconnected { .. })
}
