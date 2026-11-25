// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Shared harness for the black-box flow and wire-transcript tests: a fake eD2K server (TCP + UDP), a fake eD2K peer,
//! the transcript recorder and comparator matching `tests/transcripts/<Class>/<test>.txt`, and the launcher that drives
//! the compiled `njmule` executable strictly through its control endpoint and SSE event stream.

#![allow(
    dead_code,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::api::ed2k_link::{FileLink, Source as LinkSource};
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::files::hash::aich_recovery;
use njmule_engine::files::hash::aich_tree::AichTree;
use njmule_engine::files::hash::file_hasher;
use njmule_engine::net::http_route;
use njmule_engine::protocol::ed2k::client_messages::{
    self, CompressedPart, FileName, FileStatus, HashSet as HashSetMsg, RequestParts, SendingPart,
};
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::hello::{self, Hello, Ours};
use njmule_engine::protocol::ed2k::server_datagrams::{self, Description, Status};
use njmule_engine::protocol::ed2k::server_messages::{
    self, Endpoint, FoundSources, IdChange, Login, SearchResults, ServerStatus, Source,
};
use njmule_engine::protocol::io::{PacketReader, PacketWriter};
use njmule_engine::protocol::packet::frame_limit::FrameLimit;
use njmule_engine::protocol::packet::opcodes::{client as client_op, extended, server as server_op, server_udp};
use njmule_engine::protocol::packet::{Packet, packing, protocol, tcp_framing, udp_framing};
use njmule_engine::protocol::tag::{Tag, TagName, client_tag, file_tag, server_tag, tag_codec};
use njmule_engine::remote::api_client::ApiClient;
use njmule_engine::search::search_expr::SearchExpr;
use njmule_engine::search::search_tree_codec;
use njmule_engine::util::ipv4;
use rand::RngExt;
use reqwest::header::{AUTHORIZATION, CONTENT_TYPE};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::free_port;
use crate::scripted_server::ScriptedServer;

pub const WAIT: Duration = Duration::from_secs(30);
pub const DOWNLOAD_WAIT: Duration = Duration::from_secs(90);
pub const PART: usize = 9_728_000;
const CHUNK: usize = 10_240;
const SLOT_DELAY: Duration = Duration::from_millis(300);
const CONTROL_LINE: &str = "njmule-control ";
const CHANNELS: [&str; 3] = ["tcp", "udp", "udp-obfuscated"];

/// Exact port of `java.util.Random` so generated test files have the same bytes and MD4 hashes as the recorded
/// transcripts.
pub struct JavaRandom {
    seed: u64,
}

impl JavaRandom {
    pub fn new(seed: i64) -> Self {
        Self { seed: ((seed as u64) ^ 0x5DEE_CE66D) & ((1u64 << 48) - 1) }
    }

    fn next(&mut self, bits: u32) -> u32 {
        self.seed = (self.seed.wrapping_mul(0x5DEE_CE66D).wrapping_add(0xB)) & ((1u64 << 48) - 1);
        (self.seed >> (48 - bits)) as u32
    }

    pub fn next_bytes(&mut self, bytes: &mut [u8]) {
        let mut i = 0;
        while i < bytes.len() {
            let mut rnd = self.next(32);
            let n = (bytes.len() - i).min(4);
            for _ in 0..n {
                bytes[i] = (rnd & 0xFF) as u8;
                rnd >>= 8;
                i += 1;
            }
        }
    }
}

/// Random bytes with runs of zeros (matching `DownloadFlowTest.content`), so compression has something to do.
pub fn demo_content(size: usize, seed: i64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    JavaRandom::new(seed).next_bytes(&mut data);
    let mut at = 0usize;
    while at < size {
        let end = size.min(at + 150_000);
        data[at..end].fill(0);
        at += 400_000;
    }
    data
}

/// Deterministic 16-byte hash matching `DownloadFlowTest.hash(seed)`.
pub fn java_hash(seed: i64) -> [u8; 16] {
    let mut bytes = [0u8; 16];
    JavaRandom::new(seed).next_bytes(&mut bytes);
    bytes
}

/// LAN IPv4 address of this machine (same selection as `ScriptedServer::lan_address`).
pub fn lan_address() -> Option<Ipv4Addr> {
    ScriptedServer::lan_address()
}

pub fn high_id(address: Ipv4Addr) -> u32 {
    ipv4::to_ed2k(u32::from(address))
}

// ---- Transcript recording and comparison ----

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Receiver {
    Server,
    Peer,
}

impl Receiver {
    fn label(self) -> &'static str {
        match self {
            Self::Server => "server",
            Self::Peer => "peer",
        }
    }
}

#[derive(Debug, Clone)]
pub struct ReceivedPacket {
    channel: &'static str,
    connection: usize,
    packet: Packet,
}

#[derive(Clone)]
pub struct Transcript {
    receiver: Receiver,
    received: Arc<Mutex<Vec<ReceivedPacket>>>,
    connections: Arc<AtomicUsize>,
}

impl Transcript {
    fn new(receiver: Receiver) -> Self {
        Self { receiver, received: Arc::new(Mutex::new(Vec::new())), connections: Arc::new(AtomicUsize::new(0)) }
    }

    fn new_connection(&self) -> usize {
        self.connections.fetch_add(1, Ordering::SeqCst) + 1
    }

    fn add(&self, channel: &'static str, connection: usize, packet: Packet) {
        self.received.lock().unwrap().push(ReceivedPacket { channel, connection, packet });
    }
}

/// Collects the transcripts of fakes started during one test in start order and compares them against
/// `tests/transcripts/<name>`.
#[derive(Default)]
pub struct Recorder {
    transcripts: Vec<Transcript>,
}

impl Recorder {
    pub fn start(&mut self, receiver: Receiver) -> Transcript {
        let t = Transcript::new(receiver);
        self.transcripts.push(t.clone());
        t
    }

    pub fn assert_matches(&self, relative_path: &str) {
        let actual = render_transcripts(&self.transcripts);
        let path = Path::new("tests/transcripts").join(relative_path);
        let expected = fs::read_to_string(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        let diffs = transcript_differences(&actual, &expected);
        assert!(
            diffs.is_empty(),
            "packets against tests/transcripts/{relative_path} differ: {diffs:#?}\nreceived:\n{actual}\nexpected:\n{expected}"
        );
    }
}

fn transcript_differences(received: &str, recorded: &str) -> Vec<String> {
    let came = sections(received);
    let expected = sections(recorded);
    let came_keys: Vec<&String> = came.keys().collect();
    let expected_keys: Vec<&String> = expected.keys().collect();
    if came_keys != expected_keys {
        return vec![format!("fakes {came_keys:?} instead of {expected_keys:?}")];
    }
    let mut differences = Vec::new();
    for (fake, lines) in &expected {
        let got = &came[fake];
        let kinds: HashSet<String> = lines.iter().map(|line| packet_kind(line)).collect();
        for line in lines {
            if !got.contains(line) {
                differences.push(format!("{fake} lacks {line}"));
            }
        }
        for line in got {
            if !lines.contains(line) && kinds.contains(&packet_kind(line)) {
                differences.push(format!("{fake} got {line}"));
            }
        }
    }
    differences
}

fn sections(text: &str) -> BTreeMap<String, BTreeSet<String>> {
    let mut out: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    let mut current: Option<String> = None;
    for line in text.lines() {
        let line = line.trim_end_matches('\r');
        if let Some(name) = line.strip_prefix("# ") {
            let key = name.to_owned();
            out.entry(key.clone()).or_default();
            current = Some(key);
        } else if !line.trim().is_empty()
            && let Some(key) = &current
        {
            out.entry(key.clone()).or_default().insert(line.to_owned());
        }
    }
    out
}

fn packet_kind(line: &str) -> String {
    let fields: Vec<&str> = line.split(' ').collect();
    let count = if fields.first().copied() == Some("tcp") { 4 } else { 3 };
    fields[..count.min(fields.len())].join(" ")
}

fn render_transcripts(transcripts: &[Transcript]) -> String {
    let mut out = String::new();
    let mut counts: BTreeMap<Receiver, usize> = BTreeMap::new();
    for transcript in transcripts {
        let number = counts.entry(transcript.receiver).and_modify(|n| *n += 1).or_insert(1);
        out.push_str(&format!("# {} {number}\n", transcript.receiver.label()));
        for line in transcript_lines(transcript) {
            out.push_str(&line);
            out.push('\n');
        }
    }
    out
}

fn transcript_lines(transcript: &Transcript) -> Vec<String> {
    let mut groups: BTreeMap<(usize, usize), BTreeSet<String>> = BTreeMap::new();
    for received in transcript.received.lock().unwrap().iter() {
        let chan_idx = CHANNELS.iter().position(|c| *c == received.channel).unwrap_or(CHANNELS.len());
        groups
            .entry((chan_idx, received.connection))
            .or_default()
            .insert(format_line(transcript.receiver, received));
    }
    groups.into_values().flatten().collect()
}

fn format_line(receiver: Receiver, received: &ReceivedPacket) -> String {
    let where_str =
        if received.channel == "tcp" { format!("tcp {}", received.connection) } else { received.channel.to_owned() };
    let payload = received.packet.payload();
    let spans = mask_spans(receiver, received.channel, &received.packet);
    format!(
        "{where_str} {:02x} {:02x} {}",
        received.packet.protocol(),
        received.packet.opcode(),
        apply_masks(payload, &spans)
    )
}

struct MaskSpan {
    from: usize,
    to: usize,
    name: String,
}

fn mask_spans(receiver: Receiver, channel: &str, packet: &Packet) -> Vec<MaskSpan> {
    let payload = packet.payload();
    let proto = packet.protocol();
    let op = packet.opcode();
    if receiver == Receiver::Server && channel == "tcp" && proto == protocol::EDONKEY {
        if op == server_op::LOGINREQUEST {
            return login_spans(payload).unwrap_or_default();
        }
    } else if receiver == Receiver::Server && proto == protocol::EDONKEY {
        if op == server_udp::GLOBSERVSTATREQ && payload.len() >= 2 {
            return vec![MaskSpan { from: 0, to: 2, name: "challenge".to_owned() }];
        }
        if op == server_udp::SERVER_DESC_REQ && payload.len() >= 4 {
            return vec![MaskSpan { from: 2, to: 4, name: "challenge".to_owned() }];
        }
    } else if receiver == Receiver::Peer && proto == protocol::EDONKEY {
        if op == client_op::HELLO {
            return hello_spans(payload, 1).unwrap_or_default();
        }
        if op == client_op::HELLOANSWER {
            return hello_spans(payload, 0).unwrap_or_default();
        }
        if op == client_op::REQUESTPARTS && payload.len() >= 16 {
            return vec![MaskSpan { from: 16, to: payload.len(), name: "ranges".to_owned() }];
        }
    } else if receiver == Receiver::Peer
        && proto == protocol::EMULE
        && op == extended::REQUESTPARTS_I64
        && payload.len() >= 16
    {
        return vec![MaskSpan { from: 16, to: payload.len(), name: "ranges".to_owned() }];
    }
    Vec::new()
}

fn login_spans(payload: &[u8]) -> Option<Vec<MaskSpan>> {
    if payload.len() < 26 {
        return None;
    }
    let mut spans = vec![
        MaskSpan { from: 0, to: 16, name: "user".to_owned() },
        MaskSpan { from: 20, to: 22, name: "port".to_owned() },
    ];
    let mut reader = PacketReader::of(payload);
    tag_spans(&mut reader, 22, &mut spans)?;
    Some(spans)
}

fn hello_spans(payload: &[u8], at: usize) -> Option<Vec<MaskSpan>> {
    if payload.len() < at + 26 {
        return None;
    }
    let mut spans = vec![
        MaskSpan { from: at, to: at + 16, name: "user".to_owned() },
        MaskSpan { from: at + 16, to: at + 20, name: "id".to_owned() },
        MaskSpan { from: at + 20, to: at + 22, name: "port".to_owned() },
    ];
    let mut reader = PacketReader::of(payload);
    tag_spans(&mut reader, at + 22, &mut spans)?;
    let pos = payload.len() - reader.remaining();
    spans.push(MaskSpan { from: pos, to: payload.len(), name: "server".to_owned() });
    Some(spans)
}

fn tag_spans(reader: &mut PacketReader<'_>, at: usize, spans: &mut Vec<MaskSpan>) -> Option<()> {
    let total = reader.remaining();
    reader.skip(at).ok()?;
    let count = reader.u32().ok()?;
    for _ in 0..count {
        let from = total - reader.remaining();
        let tag = tag_codec::read_ed2k(reader).ok()?;
        let to = total - reader.remaining();
        match &tag {
            Tag::Int { name, value } if name.as_id() == Some(client_tag::MOD_MISCOPTIONS) => {
                let mask = u64::from(hello::MOD_MISC_UTP_TRAVERSAL | hello::MOD_MISC_QUIC_TRAVERSAL);
                let bits = *value & !mask;
                spans.push(MaskSpan { from, to, name: format!("options {bits:x}") });
            }
            tag => {
                if let Some(id) = tag.name().as_id() {
                    if id == client_tag::EMULE_VERSION || id == client_tag::MOD_VERSION {
                        spans.push(MaskSpan { from, to, name: "version".to_owned() });
                    } else if id == client_tag::MOD_YOUR_IP {
                        spans.push(MaskSpan { from, to, name: "address".to_owned() });
                    }
                }
            }
        }
    }
    Some(())
}

fn apply_masks(payload: &[u8], spans: &[MaskSpan]) -> String {
    let mut out = String::new();
    let mut at = 0usize;
    for span in spans {
        out.push_str(&data_encoding::HEXLOWER.encode(&payload[at..span.from]));
        out.push('{');
        out.push_str(&span.name);
        out.push('}');
        at = span.to;
    }
    out.push_str(&data_encoding::HEXLOWER.encode(&payload[at..]));
    out
}

// ---- Fake eD2K server (TCP + UDP on port + 4) ----

pub struct ServerConfig {
    pub client_id: u32,
    pub welcome: String,
    pub results: Vec<FileEntry>,
}

type SearchResponder = Arc<dyn Fn(&SearchExpr) -> Vec<FileEntry> + Send + Sync>;

struct ServerState {
    config: ServerConfig,
    address: Ipv4Addr,
    port: u16,
    transcript: Transcript,
    logins: Mutex<Vec<Login>>,
    low_ids: Mutex<HashMap<u32, FakePeer>>,
    callback_requests: Mutex<Vec<u32>>,
    known_servers: Mutex<Vec<Endpoint>>,
    searches: Mutex<Vec<SearchExpr>>,
    udp_searches: Mutex<Vec<SearchExpr>>,
    udp_source_requests: Mutex<Vec<[u8; 16]>>,
    description: Mutex<Vec<Tag>>,
    offers: Mutex<Vec<FileEntry>>,
    clients: Mutex<Vec<mpsc::UnboundedSender<Packet>>>,
    sources: Mutex<HashMap<[u8; 16], Vec<Source>>>,
    answers: Mutex<SearchResponder>,
    has_more: AtomicBool,
    more_requests: Mutex<Vec<SearchExpr>>,
}

#[derive(Clone)]
pub struct FakeServer {
    state: Arc<ServerState>,
    tasks: Arc<Mutex<Vec<JoinHandle<()>>>>,
}

impl FakeServer {
    pub const FLAGS: u32 = server_messages::TCP_COMPRESSION
        | server_messages::TCP_NEWTAGS
        | server_messages::TCP_UNICODE
        | server_messages::TCP_LARGEFILES;

    pub async fn start(address: Ipv4Addr, config: ServerConfig, recorder: &mut Recorder) -> Self {
        let mut rng = rand::rng();
        let (tcp, udp) = loop {
            let port: u16 = rng.random_range(20_000..40_000);
            let Ok(tcp) = TcpListener::bind((address, port)).await else {
                continue;
            };
            let Ok(udp) = UdpSocket::bind((address, port + 4)).await else {
                drop(tcp);
                continue;
            };
            break (tcp, udp);
        };
        let port = tcp.local_addr().unwrap().port();
        let fixed_results = config.results.clone();
        let transcript = recorder.start(Receiver::Server);
        let state = Arc::new(ServerState {
            config,
            address,
            port,
            transcript,
            logins: Mutex::new(Vec::new()),
            low_ids: Mutex::new(HashMap::new()),
            callback_requests: Mutex::new(Vec::new()),
            known_servers: Mutex::new(Vec::new()),
            searches: Mutex::new(Vec::new()),
            udp_searches: Mutex::new(Vec::new()),
            udp_source_requests: Mutex::new(Vec::new()),
            description: Mutex::new(Vec::new()),
            offers: Mutex::new(Vec::new()),
            clients: Mutex::new(Vec::new()),
            sources: Mutex::new(HashMap::new()),
            answers: Mutex::new(Arc::new(move |_| fixed_results.clone())),
            has_more: AtomicBool::new(false),
            more_requests: Mutex::new(Vec::new()),
        });
        let tcp_state = Arc::clone(&state);
        let tcp_task = tokio::spawn(async move {
            while let Ok((stream, peer_addr)) = tcp.accept().await {
                let s = Arc::clone(&tcp_state);
                tokio::spawn(async move { serve_server_client(s, stream, peer_addr).await });
            }
        });
        let udp_state = Arc::clone(&state);
        let udp_task = tokio::spawn(async move {
            let mut buf = vec![0u8; 8192];
            while let Ok((len, from)) = udp.recv_from(&mut buf).await {
                if let Ok(packet) = udp_framing::decode(&buf[..len]) {
                    udp_state.transcript.add("udp", 0, packet.clone());
                    if let Some(answer) = answer_server_udp(&udp_state, &packet) {
                        let bytes = udp_framing::encode(&answer);
                        let _ = udp.send_to(&bytes, from).await;
                    }
                }
            }
        });
        Self { state, tasks: Arc::new(Mutex::new(vec![tcp_task, udp_task])) }
    }

    pub fn file(name: &str, size: u64, sources: u64) -> FileEntry {
        let digest = Sha256::digest(name.as_bytes());
        let mut hash = [0u8; 16];
        hash.copy_from_slice(&digest[..16]);
        let tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size },
            Tag::Int { name: TagName::id(file_tag::SOURCES), value: sources },
            Tag::Int { name: TagName::id(file_tag::COMPLETE_SOURCES), value: sources },
        ];
        FileEntry { hash, client_id: 0, port: 0, tags }
    }

    pub fn port(&self) -> u16 {
        self.state.port
    }

    pub fn address(&self) -> Ipv4Addr {
        self.state.address
    }

    pub fn logins(&self) -> Vec<Login> {
        self.state.logins.lock().unwrap().clone()
    }

    pub fn searches(&self) -> Vec<SearchExpr> {
        self.state.searches.lock().unwrap().clone()
    }

    pub fn udp_searches(&self) -> Vec<SearchExpr> {
        self.state.udp_searches.lock().unwrap().clone()
    }

    pub fn more_requests(&self) -> Vec<SearchExpr> {
        self.state.more_requests.lock().unwrap().clone()
    }

    pub fn udp_source_requests(&self) -> Vec<[u8; 16]> {
        self.state.udp_source_requests.lock().unwrap().clone()
    }

    pub fn offers(&self) -> Vec<FileEntry> {
        self.state.offers.lock().unwrap().clone()
    }

    pub fn callback_requests(&self) -> Vec<u32> {
        self.state.callback_requests.lock().unwrap().clone()
    }

    pub fn answer_searches(&self, f: impl Fn(&SearchExpr) -> Vec<FileEntry> + Send + Sync + 'static) {
        *self.state.answers.lock().unwrap() = Arc::new(f);
    }

    pub fn announce_more_results(&self, more: bool) {
        self.state.has_more.store(more, Ordering::SeqCst);
    }

    pub fn describe_as(&self, name: &str, text: &str, version: &str) {
        *self.state.description.lock().unwrap() = vec![
            Tag::String { name: TagName::id(server_tag::SERVERNAME), value: name.to_owned() },
            Tag::String { name: TagName::id(server_tag::DESCRIPTION), value: text.to_owned() },
            Tag::String { name: TagName::id(server_tag::VERSION), value: version.to_owned() },
        ];
    }

    pub fn add_source(&self, file: [u8; 16], peer: SocketAddrV4) {
        let ip = u32::from(*peer.ip());
        self.state.sources.lock().unwrap().entry(file).or_default().push(Source {
            client_id: ipv4::to_ed2k(ip),
            port: peer.port(),
            crypt_options: 0,
            user_hash: None,
        });
    }

    pub fn add_low_id_source(&self, file: [u8; 16], low_id: u32, peer: FakePeer) {
        let port = peer.port();
        self.state.low_ids.lock().unwrap().insert(low_id, peer);
        self.state.sources.lock().unwrap().entry(file).or_default().push(Source {
            client_id: low_id,
            port,
            crypt_options: 0,
            user_hash: None,
        });
    }

    pub fn knows_servers(&self, servers: Vec<Endpoint>) {
        *self.state.known_servers.lock().unwrap() = servers;
    }

    pub fn broadcast(&self, packet: Packet) {
        for tx in self.state.clients.lock().unwrap().iter() {
            let _ = tx.send(packet.clone());
        }
    }

    pub fn close(&self) {
        for task in self.tasks.lock().unwrap().drain(..) {
            task.abort();
        }
        self.state.clients.lock().unwrap().clear();
    }
}

async fn read_framed_tcp(stream: &mut tokio::net::tcp::OwnedReadHalf, max_len: usize) -> Option<Packet> {
    let mut header = [0u8; 5];
    stream.read_exact(&mut header).await.ok()?;
    let proto = header[0];
    let len = u32::from_le_bytes([header[1], header[2], header[3], header[4]]) as usize;
    if len == 0 || len > max_len {
        return None;
    }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).await.ok()?;
    Some(Packet::of(proto, body[0], body[1..].to_vec()))
}

async fn serve_server_client(state: Arc<ServerState>, stream: TcpStream, peer_addr: SocketAddr) {
    let connection = state.transcript.new_connection();
    let (mut input, mut output) = stream.into_split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Packet>();
    state.clients.lock().unwrap().push(tx.clone());
    let writer = tokio::spawn(async move {
        while let Some(pkt) = rx.recv().await {
            if let Ok(bytes) = tcp_framing::encode(&pkt)
                && output.write_all(&bytes).await.is_err()
            {
                break;
            }
        }
    });
    while let Some(raw) = read_framed_tcp(&mut input, 1 << 20).await {
        let Ok(request) = packing::unpack(raw, 1 << 22) else {
            break;
        };
        state.transcript.add("tcp", connection, request.clone());
        for answer in answer_server_tcp(&state, &request, peer_addr) {
            let _ = tx.send(answer);
        }
    }
    writer.abort();
}

fn answer_server_tcp(state: &ServerState, request: &Packet, peer_addr: SocketAddr) -> Vec<Packet> {
    let op = request.opcode();
    if op == server_op::LOGINREQUEST {
        if let Ok(login) = Login::decode(request) {
            state.logins.lock().unwrap().push(login);
        }
        let seen = match peer_addr.ip() {
            IpAddr::V4(v4) => u32::from(v4),
            IpAddr::V6(_) => 0,
        };
        let id = IdChange { client_id: state.config.client_id, server_flags: FakeServer::FLAGS, reported_ip: seen };
        return vec![
            id.encode(),
            server_messages::message(&state.config.welcome).unwrap(),
            ServerStatus { users: 10, files: 20 }.encode(),
        ];
    }
    if op == server_op::GETSERVERLIST {
        let servers = state.known_servers.lock().unwrap().clone();
        return vec![server_messages::server_list(&servers).unwrap()];
    }
    if op == server_op::OFFERFILES {
        let mut reader = request.reader();
        if let Ok(count) = reader.u32() {
            let mut offers = state.offers.lock().unwrap();
            for _ in 0..count {
                if let Ok(entry) = FileEntry::read(&mut reader) {
                    offers.push(entry);
                }
            }
        }
        return Vec::new();
    }
    if op == server_op::GETSOURCES || op == server_op::GETSOURCES_OBFU {
        if let Ok(file) = request.reader().hash16() {
            let known = state.sources.lock().unwrap().get(&file).cloned().unwrap_or_default();
            if !known.is_empty() {
                return vec![FoundSources { file, sources: known }.encode().unwrap()];
            }
        }
        return Vec::new();
    }
    if op == server_op::CALLBACKREQUEST {
        if let Ok(low_id) = request.reader().u32() {
            state.callback_requests.lock().unwrap().push(low_id);
            let peer = state.low_ids.lock().unwrap().get(&low_id).cloned();
            let port = state.logins.lock().unwrap().last().map_or(0, |l| l.tcp_port);
            if let (Some(peer), IpAddr::V4(ip)) = (peer, peer_addr.ip())
                && port != 0
            {
                peer.call_as_low_id(SocketAddrV4::new(ip, port), low_id, u32::from(state.address), state.port);
            }
        }
        return Vec::new();
    }
    if op == server_op::SEARCHREQUEST
        && let Ok(expr) = search_tree_codec::read(&mut request.reader())
    {
        state.searches.lock().unwrap().push(expr.clone());
        let responder = Arc::clone(&state.answers.lock().unwrap());
        let files = responder(&expr);
        let more = state.has_more.load(Ordering::SeqCst);
        return vec![SearchResults { files, more }.encode().unwrap()];
    }
    if op == server_op::QUERY_MORE_RESULT {
        let last = state.searches.lock().unwrap().last().cloned();
        if let Some(expr) = last {
            state.more_requests.lock().unwrap().push(expr.clone());
            let responder = Arc::clone(&state.answers.lock().unwrap());
            let files = responder(&expr);
            return vec![SearchResults { files, more: false }.encode().unwrap()];
        }
    }
    Vec::new()
}

fn answer_server_udp(state: &ServerState, request: &Packet) -> Option<Packet> {
    let op = request.opcode();
    let mut reader = request.reader();
    if op == server_udp::GLOBSERVSTATREQ {
        let challenge = reader.u32().ok()?;
        return Some(
            Status {
                challenge,
                users: 10,
                files: 20,
                max_users: 100,
                soft_files: 1000,
                hard_files: 2000,
                udp_flags: server_datagrams::UDP_EXT_GETSOURCES | server_datagrams::UDP_EXT_GETSOURCES2,
                low_id_users: 0,
                obfuscated_udp_port: 0,
                obfuscated_tcp_port: 0,
                udp_key: 0,
            }
            .encode(),
        );
    }
    if op == server_udp::SERVER_DESC_REQ {
        let tags = state.description.lock().unwrap().clone();
        if tags.is_empty() {
            return None;
        }
        let challenge = reader.u32().ok()?;
        return Description { challenge, tags }.encode().ok();
    }
    if op == server_udp::GLOBGETSOURCES || op == server_udp::GLOBGETSOURCES2 {
        let sized = op == server_udp::GLOBGETSOURCES2;
        let mut found = Vec::new();
        while reader.remaining() > 0 {
            let file = reader.hash16().ok()?;
            if sized && reader.u32().ok()? == 0 {
                let _ = reader.u64().ok()?;
            }
            state.udp_source_requests.lock().unwrap().push(file);
            let known: Vec<Source> = state
                .sources
                .lock()
                .unwrap()
                .get(&file)
                .cloned()
                .unwrap_or_default()
                .into_iter()
                .filter(|s| !s.is_low_id())
                .collect();
            if !known.is_empty() {
                found.push(FoundSources { file, sources: known });
            }
        }
        return if found.is_empty() { None } else { server_datagrams::found_sources(&found).ok() };
    }
    if op == server_udp::GLOBSEARCHREQ3 {
        let count = reader.u32().ok()?;
        tag_codec::read_ed2k_list(&mut reader, u64::from(count), 16).ok()?;
    } else if op != server_udp::GLOBSEARCHREQ && op != server_udp::GLOBSEARCHREQ2 {
        return None;
    }
    let expr = search_tree_codec::read(&mut reader).ok()?;
    state.udp_searches.lock().unwrap().push(expr);
    if state.config.results.is_empty() { None } else { server_datagrams::search_results(&state.config.results).ok() }
}

// ---- Fake eD2K peer ----

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SlotMode {
    Now,
    AfterQueue,
    CallBack,
}

#[derive(Clone)]
pub struct PeerConfig {
    pub user_hash: [u8; 16],
    pub compress: bool,
    pub slot: SlotMode,
    pub session_limit: u64,
    pub corrupt_parts: HashSet<usize>,
}

impl PeerConfig {
    pub fn simple(user_hash: [u8; 16]) -> Self {
        Self { user_hash, compress: true, slot: SlotMode::Now, session_limit: 0, corrupt_parts: HashSet::new() }
    }
}

struct PeerState {
    file: PathBuf,
    name: String,
    size: u64,
    hash: [u8; 16],
    part_hashes: Vec<[u8; 16]>,
    aich: Option<AichTree>,
    config: PeerConfig,
    address: Ipv4Addr,
    port: u16,
    transcript: Transcript,
    low_id: Mutex<u32>,
    wanted: Mutex<Option<[u8; 16]>>,
    server_ip: Mutex<u32>,
    server_port: Mutex<u16>,
    received: Mutex<Vec<String>>,
    corrupted: Mutex<HashSet<usize>>,
    sent: AtomicU64,
    closed: AtomicBool,
}

#[derive(Clone)]
pub struct FakePeer {
    state: Arc<PeerState>,
    accepting: Arc<Mutex<Option<JoinHandle<()>>>>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum PeerAnswer {
    None,
    Slot,
    HelloOnly,
}

impl FakePeer {
    pub async fn start(address: Ipv4Addr, file: &Path, config: PeerConfig, recorder: &mut Recorder) -> Self {
        let hashes = file_hasher::hash_file(file).unwrap();
        let name = file.file_name().unwrap().to_string_lossy().into_owned();
        let listener = TcpListener::bind((address, 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let transcript = recorder.start(Receiver::Peer);
        let state = Arc::new(PeerState {
            file: file.to_path_buf(),
            name,
            size: hashes.ed2k.size(),
            hash: hashes.ed2k.file_hash(),
            part_hashes: hashes.ed2k.part_hashes().to_vec(),
            aich: hashes.aich,
            config,
            address,
            port,
            transcript,
            low_id: Mutex::new(0),
            wanted: Mutex::new(None),
            server_ip: Mutex::new(0),
            server_port: Mutex::new(0),
            received: Mutex::new(Vec::new()),
            corrupted: Mutex::new(HashSet::new()),
            sent: AtomicU64::new(0),
            closed: AtomicBool::new(false),
        });
        let accept_state = Arc::clone(&state);
        let accepting = tokio::spawn(async move {
            while let Ok((stream, addr)) = listener.accept().await {
                let s = Arc::clone(&accept_state);
                tokio::spawn(async move { serve_peer_client(s, stream, addr, PeerAnswer::None).await });
            }
        });
        Self { state, accepting: Arc::new(Mutex::new(Some(accepting))) }
    }

    pub fn port(&self) -> u16 {
        self.state.port
    }

    pub fn hash(&self) -> [u8; 16] {
        self.state.hash
    }

    pub fn link(&self) -> FileLink {
        FileLink {
            name: self.state.name.clone(),
            size: self.state.size,
            hash: self.state.hash,
            aich_hash: self.state.aich.as_ref().map(AichTree::master_hash),
            part_hashes: Vec::new(),
            sources: vec![LinkSource { host: self.state.address.to_string(), port: self.state.port }],
            http_sources: Vec::new(),
        }
    }

    pub fn also_ask_for(&self, file: [u8; 16]) {
        *self.state.wanted.lock().unwrap() = Some(file);
    }

    pub fn received(&self) -> Vec<String> {
        self.state.received.lock().unwrap().clone()
    }

    pub fn bytes_sent(&self) -> u64 {
        self.state.sent.load(Ordering::SeqCst)
    }

    pub fn call_as_low_id(&self, downloader: SocketAddrV4, low_id: u32, server_ip: u32, server_port: u16) {
        *self.state.low_id.lock().unwrap() = low_id;
        *self.state.server_ip.lock().unwrap() = server_ip;
        *self.state.server_port.lock().unwrap() = server_port;
        let s = Arc::clone(&self.state);
        tokio::spawn(async move {
            if let Ok(stream) = TcpStream::connect(downloader).await {
                serve_peer_client(s, stream, SocketAddr::V4(downloader), PeerAnswer::HelloOnly).await;
            }
        });
    }

    pub fn close(&self) {
        self.state.closed.store(true, Ordering::SeqCst);
        if let Some(handle) = self.accepting.lock().unwrap().take() {
            handle.abort();
        }
    }
}

fn peer_opcode_name(packet: &Packet) -> &'static str {
    if packet.protocol() == protocol::EMULE {
        match packet.opcode() {
            extended::EMULEINFO => "EMULEINFO",
            extended::EMULEINFOANSWER => "EMULEINFOANSWER",
            extended::COMPRESSEDPART => "COMPRESSEDPART",
            extended::QUEUERANKING => "QUEUERANKING",
            extended::AICHREQUEST => "AICHREQUEST",
            extended::AICHANSWER => "AICHANSWER",
            extended::REQUESTPARTS_I64 => "REQUESTPARTS_I64",
            extended::PUBLICIP_REQ => "PUBLICIP_REQ",
            extended::PUBLICIP_ANSWER => "PUBLICIP_ANSWER",
            _ => "?",
        }
    } else {
        match packet.opcode() {
            client_op::HELLO => "HELLO",
            client_op::HELLOANSWER => "HELLOANSWER",
            client_op::SENDINGPART => "SENDINGPART",
            client_op::REQUESTPARTS => "REQUESTPARTS",
            client_op::FILEREQANSNOFIL => "FILEREQANSNOFIL",
            client_op::END_OF_DOWNLOAD => "END_OF_DOWNLOAD",
            client_op::SETREQFILEID => "SETREQFILEID",
            client_op::FILESTATUS => "FILESTATUS",
            client_op::HASHSETREQUEST => "HASHSETREQUEST",
            client_op::HASHSETANSWER => "HASHSETANSWER",
            client_op::STARTUPLOADREQ => "STARTUPLOADREQ",
            client_op::ACCEPTUPLOADREQ => "ACCEPTUPLOADREQ",
            client_op::CANCELTRANSFER => "CANCELTRANSFER",
            client_op::OUTOFPARTREQS => "OUTOFPARTREQS",
            client_op::REQUESTFILENAME => "REQUESTFILENAME",
            client_op::REQFILENAMEANSWER => "REQFILENAMEANSWER",
            _ => "?",
        }
    }
}

fn peer_hello(state: &PeerState) -> Hello {
    let ours = Ours {
        nick: "fake peer",
        udp_port: 0,
        kad_port: 0,
        misc_options1: (1 << hello::MISC1_AICH_SHIFT) | hello::MISC1_UNICODE,
        misc_options2: hello::MISC2_LARGE_FILES,
    };
    Hello::of(
        state.config.user_hash,
        *state.low_id.lock().unwrap(),
        state.port,
        &ours,
        *state.server_ip.lock().unwrap(),
        *state.server_port.lock().unwrap(),
    )
}

fn serve_peer_client(
    state: Arc<PeerState>,
    stream: TcpStream,
    peer_addr: SocketAddr,
    answer: PeerAnswer,
) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()> + Send>> {
    Box::pin(async move {
        let called_back = answer == PeerAnswer::Slot;
        let connection = state.transcript.new_connection();
        let (mut input, mut output) = stream.into_split();
        let (tx, mut rx) = mpsc::unbounded_channel::<Packet>();
        let writer = tokio::spawn(async move {
            while let Some(pkt) = rx.recv().await {
                if let Ok(bytes) = tcp_framing::encode(&pkt)
                    && output.write_all(&bytes).await.is_err()
                {
                    break;
                }
            }
        });
        if answer != PeerAnswer::None {
            let _ = tx.send(peer_hello(&state).encode(false).unwrap());
        }
        let mut downloader_port: u16 = 0;
        let mut session_bytes: u64 = 0;
        let mut session_ended = false;
        let mut asked_for_ours = false;
        let mut data_file = File::open(&state.file).unwrap();

        while !state.closed.load(Ordering::SeqCst) {
            let Some(packet) = read_framed_tcp(&mut input, 1 << 20).await else {
                break;
            };
            state.transcript.add("tcp", connection, packet.clone());
            state.received.lock().unwrap().push(peer_opcode_name(&packet).to_owned());

            let proto = packet.protocol();
            let op = packet.opcode();
            if proto == protocol::EMULE && op == extended::REQUESTPARTS_I64 {
                if let Ok(req) = RequestParts::decode(&packet) {
                    send_peer_blocks(&state, &mut data_file, &req, &tx, &mut session_bytes, &mut session_ended);
                }
                continue;
            }
            if proto == protocol::EMULE && op == extended::AICHREQUEST {
                handle_aich_request(&state, &packet, &tx);
                continue;
            }
            if proto == protocol::EMULE && op == extended::PUBLICIP_ANSWER && session_ended {
                session_ended = false;
                let _ = tx.send(Packet::empty(protocol::EDONKEY, client_op::ACCEPTUPLOADREQ));
                continue;
            }
            if proto != protocol::EDONKEY {
                continue;
            }
            match op {
                client_op::HELLO => {
                    if let Ok(h) = Hello::decode(&packet) {
                        downloader_port = h.tcp_port;
                    }
                    let _ = tx.send(peer_hello(&state).encode(true).unwrap());
                }
                client_op::HELLOANSWER => {
                    if called_back {
                        let _ = tx.send(Packet::empty(protocol::EDONKEY, client_op::ACCEPTUPLOADREQ));
                    }
                }
                client_op::REQUESTFILENAME => {
                    let _ = tx.send(FileName { file: state.hash, name: state.name.clone() }.encode().unwrap());
                    let wanted = *state.wanted.lock().unwrap();
                    if let Some(wanted) = wanted
                        && !asked_for_ours
                    {
                        asked_for_ours = true;
                        let _ = tx.send(client_messages::for_file(client_op::SETREQFILEID, &wanted));
                        let _ = tx.send(client_messages::for_file(client_op::STARTUPLOADREQ, &wanted));
                    }
                }
                client_op::SETREQFILEID => {
                    let _ =
                        tx.send(FileStatus { file: state.hash, part_count: 0, parts: Vec::new() }.encode().unwrap());
                }
                client_op::HASHSETREQUEST => {
                    let _ =
                        tx.send(HashSetMsg { file: state.hash, parts: state.part_hashes.clone() }.encode().unwrap());
                }
                client_op::STARTUPLOADREQ => {
                    let slot = if called_back { SlotMode::Now } else { state.config.slot };
                    if slot != SlotMode::Now {
                        let mut out = PacketWriter::new();
                        out.u16(1).bytes(&[0u8; 10]);
                        let _ = tx.send(Packet::written(protocol::EMULE, extended::QUEUERANKING, out));
                    }
                    match slot {
                        SlotMode::Now => {
                            let _ = tx.send(Packet::empty(protocol::EDONKEY, client_op::ACCEPTUPLOADREQ));
                        }
                        SlotMode::AfterQueue => {
                            let delayed = tx.clone();
                            tokio::spawn(async move {
                                tokio::time::sleep(SLOT_DELAY).await;
                                let _ = delayed.send(Packet::empty(protocol::EDONKEY, client_op::ACCEPTUPLOADREQ));
                            });
                        }
                        SlotMode::CallBack => {
                            let cb_state = Arc::clone(&state);
                            let target = SocketAddr::new(peer_addr.ip(), downloader_port);
                            tokio::spawn(async move {
                                tokio::time::sleep(SLOT_DELAY).await;
                                if let Ok(cb_stream) = TcpStream::connect(target).await {
                                    serve_peer_client(cb_state, cb_stream, target, PeerAnswer::Slot).await;
                                }
                            });
                            // Drop writer after flushing QUEUERANKING, then break to close the connection.
                            tokio::time::sleep(Duration::from_millis(20)).await;
                            break;
                        }
                    }
                }
                client_op::REQUESTPARTS => {
                    if let Ok(req) = RequestParts::decode(&packet) {
                        send_peer_blocks(&state, &mut data_file, &req, &tx, &mut session_bytes, &mut session_ended);
                    }
                }
                _ => {}
            }
        }
        drop(tx);
        let _ = writer.await;
    })
}

fn handle_aich_request(state: &PeerState, packet: &Packet, tx: &mpsc::UnboundedSender<Packet>) {
    let mut reader = packet.reader();
    let (Ok(file), Ok(part), Ok(master_slice)) = (reader.hash16(), reader.u16(), reader.bytes(20)) else {
        return;
    };
    let Ok(master) = <[u8; 20]>::try_from(master_slice) else {
        return;
    };
    let mut out = PacketWriter::new();
    out.hash16(&file);
    if file == state.hash
        && let Some(tree) = state.aich.as_ref().filter(|t| t.master_hash() == master)
        && aich_recovery::has_recovery(tree.size(), u32::from(part))
        && let Ok(recovery) = aich_recovery::create(tree, u32::from(part))
    {
        out.u16(part).hash20(&master).bytes(&recovery);
    }
    let _ = tx.send(Packet::written(protocol::EMULE, extended::AICHANSWER, out));
}

fn send_peer_blocks(
    state: &PeerState,
    data_file: &mut File,
    request: &RequestParts,
    tx: &mpsc::UnboundedSender<Packet>,
    session_bytes: &mut u64,
    session_ended: &mut bool,
) {
    if *session_ended {
        return;
    }
    for range in request.ranges {
        if range.is_empty() {
            continue;
        }
        let len = usize::try_from(range.end - range.start).unwrap();
        let mut block = vec![0u8; len];
        data_file.seek(SeekFrom::Start(range.start)).unwrap();
        data_file.read_exact(&mut block).unwrap();
        let part = usize::try_from(range.start / (PART as u64)).unwrap();
        if state.config.corrupt_parts.contains(&part) && state.corrupted.lock().unwrap().insert(part) {
            block[0] ^= 0x55;
        }
        if state.config.compress {
            let stream = packing::deflate(&block, 1);
            let mut at = 0usize;
            while at < stream.len() {
                let end = stream.len().min(at + CHUNK);
                let pkt = CompressedPart {
                    file: state.hash,
                    block_start: range.start,
                    compressed_size: u32::try_from(stream.len()).unwrap(),
                    chunk: stream[at..end].to_vec(),
                }
                .encode(false)
                .unwrap();
                let _ = tx.send(pkt);
                at = end;
            }
        } else {
            let mut at = 0usize;
            while at < block.len() {
                let end = block.len().min(at + CHUNK);
                let pkt = SendingPart {
                    file: state.hash,
                    start: range.start + (at as u64),
                    end: range.start + (end as u64),
                    data: block[at..end].to_vec(),
                }
                .encode(false)
                .unwrap();
                let _ = tx.send(pkt);
                at = end;
            }
        }
        state.sent.fetch_add(block.len() as u64, Ordering::SeqCst);
        *session_bytes += block.len() as u64;
        if state.config.session_limit > 0 && *session_bytes >= state.config.session_limit {
            *session_bytes = 0;
            *session_ended = true;
            let _ = tx.send(Packet::empty(protocol::EDONKEY, client_op::OUTOFPARTREQS));
            let _ = tx.send(Packet::empty(protocol::EMULE, extended::PUBLICIP_REQ));
            return;
        }
    }
}

// ---- Black-box njmule executable driven strictly via the control endpoint ----

pub struct BlackBoxEngine {
    pub dirs: AppDirectories,
    pub tcp_port: u16,
    base: String,
    token: String,
    api: ApiClient,
    child: Option<Child>,
    // Keep stdin open while running: with NJMULE_CONTROL_ENDPOINT=1, EOF on stdin triggers shutdown.
    stdin: Option<ChildStdin>,
    events_rx: mpsc::UnboundedReceiver<(String, Value)>,
    sse_task: JoinHandle<()>,
    log_path: PathBuf,
}

impl BlackBoxEngine {
    pub async fn start(root: &Path) -> Self {
        let dirs = AppDirectories::under_root(root).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let tcp_port = free_port::free_port();
        fs::write(dirs.config.join("njmule.properties"), format!("net.tcpPort={tcp_port}\nsecurity.filterLan=false\n"))
            .unwrap();
        let log_path = root.join("engine.log");
        let mut child = Command::new(env!("CARGO_BIN_EXE_njmule"))
            .args([
                "--config-dir",
                &root.to_string_lossy(),
                "-Dnjmule.upnp.disabled=true",
                "-Dnjmule.defaultlists.disabled=true",
                "-Dnjmule.countries.disabled=true",
                "-Dnjmule.update.disabled=true",
                "-Dnjmule.ipv6.disabled=true",
            ])
            .env("NJMULE_CONTROL_ENDPOINT", "1")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take();
        let stdout = child.stdout.take().unwrap();
        let mut reader = BufReader::new(stdout);
        let mut log_file = File::create(&log_path).unwrap();
        let mut line = String::new();
        let endpoint_json = loop {
            line.clear();
            let n = reader.read_line(&mut line).unwrap();
            assert!(
                n > 0,
                "njmule stopped before printing control line:\n{}",
                fs::read_to_string(&log_path).unwrap_or_default()
            );
            if let Some(rest) = line.strip_prefix(CONTROL_LINE) {
                break rest.trim().to_owned();
            }
            let _ = log_file.write_all(line.as_bytes());
        };
        // Drain stdout in the background so the pipe never fills up.
        std::thread::spawn(move || {
            let mut buf = [0u8; 4096];
            while let Ok(n) = reader.read(&mut buf) {
                if n == 0 {
                    break;
                }
                let _ = log_file.write_all(&buf[..n]);
            }
        });
        let parsed: Value = serde_json::from_str(&endpoint_json).unwrap();
        let base = parsed["url"].as_str().unwrap().trim_end_matches('/').to_owned();
        let token = parsed["token"].as_str().unwrap().to_owned();
        let api = ApiClient::new(base.clone(), token.clone());

        let (tx, events_rx) = mpsc::unbounded_channel();
        let sse_api = api.clone();
        let (ready_tx, ready_rx) = tokio::sync::oneshot::channel();
        let sse_task = tokio::spawn(async move {
            let mut ready_tx = Some(ready_tx);
            let _ = sse_api
                .events("", |name, payload| {
                    if let Some(done) = ready_tx.take() {
                        let _ = done.send(());
                    }
                    let _ = tx.send((name.to_owned(), payload));
                    Ok(true)
                })
                .await;
        });
        // Give the SSE stream a moment to attach (it sends missed events immediately, or heartbeat/started).
        let _ = tokio::time::timeout(Duration::from_millis(500), ready_rx).await;

        Self { dirs, tcp_port, base, token, api, child: Some(child), stdin, events_rx, sse_task, log_path }
    }

    pub fn api(&self) -> &ApiClient {
        &self.api
    }

    pub async fn patch(&self, path: &str, body: Value) -> Value {
        let client = http_route::lan_client(WAIT).unwrap();
        let url = format!("{}{path}", self.base);
        let resp = client
            .patch(&url)
            .header(AUTHORIZATION, format!("Bearer {}", self.token))
            .header(CONTENT_TYPE, "application/json; charset=utf-8")
            .body(serde_json::to_vec(&body).unwrap())
            .send()
            .await
            .unwrap();
        assert!(resp.status().is_success(), "PATCH {path} failed: {}", resp.status());
        let bytes = resp.bytes().await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    pub async fn next_event(&mut self, wait: Duration, mut pred: impl FnMut(&str, &Value) -> bool) -> (String, Value) {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            assert!(!remaining.is_zero(), "timed out waiting for SSE event");
            let next = tokio::time::timeout(remaining, self.events_rx.recv()).await;
            match next {
                Ok(Some((name, payload))) => {
                    if pred(&name, &payload) {
                        return (name, payload);
                    }
                }
                Ok(None) => panic!("SSE stream ended before expected event"),
                Err(_) => panic!("timed out waiting for SSE event"),
            }
        }
    }

    pub fn engine_log(&self) -> String {
        fs::read_to_string(&self.log_path).unwrap_or_default()
    }

    pub async fn stop(mut self) {
        let _ = self.api.post("/shutdown", json!({})).await;
        drop(self.stdin.take());
        self.sse_task.abort();
        if let Some(mut child) = self.child.take() {
            let start = Instant::now();
            loop {
                if child.try_wait().unwrap().is_some() {
                    break;
                }
                if start.elapsed() > WAIT {
                    let _ = child.kill();
                    let _ = child.wait();
                    break;
                }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        }
    }
}

impl Drop for BlackBoxEngine {
    fn drop(&mut self) {
        self.sse_task.abort();
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

pub async fn wait_until(timeout: Duration, mut cond: impl FnMut() -> bool, what: &str) {
    let deadline = tokio::time::Instant::now() + timeout;
    while !cond() {
        assert!(tokio::time::Instant::now() < deadline, "timed out waiting for {what}");
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
}

pub fn read_tcp_frame_sync(stream: &mut std::net::TcpStream) -> Packet {
    struct Fixed(usize);
    impl FrameLimit for Fixed {
        fn max_payload(&self, _protocol: u8, _opcode: u8) -> Option<usize> {
            Some(self.0)
        }
    }
    tcp_framing::read(stream, &Fixed(4096)).unwrap().unwrap()
}
