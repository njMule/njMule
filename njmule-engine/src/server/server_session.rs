// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The session with an eD2K server: the connection attempts, the login and what the server says afterwards.
//!
//! A first pass asks for obfuscation where it may be taken: servers known to take it, on their obfuscation port, and
//! servers never pinged, whose support is unknown, on their main port (a probe, whose failure is no failure of the
//! server). A second pass, unless obfuscation is required, goes plain. Only the engine's task uses the session; the
//! connection tasks and timers report back through [`Outcome`]s.

use std::collections::{HashSet, VecDeque};
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::config::obfuscation_mode::ObfuscationMode;
use crate::config::preferences::Preferences;
use crate::net::routes::Routes;
use crate::net::tcp_connector;
use crate::persist::format::server_met::{self, Server};
use crate::protocol::ed2k::server_datagrams;
use crate::protocol::ed2k::server_messages::{
    self, CallbackRequested, Endpoint, FoundSources, IdChange, Login, SearchResults, ServerIdent, ServerStatus,
};
use crate::protocol::packet::opcodes::server;
use crate::protocol::packet::{Packet, frame_limits, packing, protocol};
use crate::protocol::tag::{Tag, TagName, server_tag};
use crate::server::server_list::{self, Address, ServerList};
use crate::util::white_space::strip;

pub const LOGIN_TIMEOUT: Duration = Duration::from_secs(25);
/// How long a server that answered with id 0 has to change its mind before the next is tried.
pub const REFUSAL_GRACE: Duration = Duration::from_secs(3);
/// How long a session may stay silent before it sends a keep-alive.
pub const KEEP_ALIVE: Duration = Duration::from_secs(15 * 60);
/// Servers tried at once when any will do.
pub const ATTEMPTS_AT_ONCE: usize = 2;
/// The wait before trying every server again, or after a reconnect that followed another too soon.
pub const RETRY_AFTER: Duration = Duration::from_secs(30);

const KEEP_ALIVE_CHECK: Duration = Duration::from_secs(60);
const VERSION_LINE: &str = "server version";
const MAX_VERSION: usize = 64;

/// What the connection tasks and timers report to the session.
#[derive(Debug)]
pub enum Outcome {
    Resolved { attempt: u64, ip: Result<u32, String> },
    Opened { attempt: u64, packets: mpsc::UnboundedSender<Packet>, obfuscated: bool },
    Received { attempt: u64, packet: Packet },
    Closed { attempt: u64, reason: String },
    Failed { attempt: u64, reason: String },
    Retry,
    ReconnectPauseOver,
    KeepAliveCheck,
}

/// What the session tells the engine.
#[derive(Debug, Clone)]
pub enum Notice {
    Connecting(Server),
    Connected { server: Server, id: IdChange, obfuscated: bool },
    IdChanged { server: Server, id: IdChange },
    Disconnected { server: Option<Server>, reason: String },
    AttemptFailed { server: Server, reason: String },
    Retrying(Duration),
    Message { server: Server, line: String },
    Updated(Server),
    CallbackRequested(CallbackRequested),
    SearchResults { server: Server, results: SearchResults },
    FoundSources(FoundSources),
    ServerList { server: Server, servers: Vec<Endpoint> },
}

/// What the session works with, lent by the engine for each call.
pub struct Context<'a> {
    pub servers: &'a mut ServerList,
    pub preferences: &'a Preferences,
    pub routes: &'a Routes,
    pub user_hash: [u8; 16],
    pub listen_port: u16,
    pub keep_connected: bool,
    pub notices: &'a mut Vec<Notice>,
}

// One connection attempt, from the address lookup to the id; the session once logged in.
struct Attempt {
    number: u64,
    server: Server,
    obfuscated: bool,
    // Obfuscation tried on a server not known to take it: its failure is no failure of the server.
    probe: bool,
    packets: Option<mpsc::UnboundedSender<Packet>>,
    obfuscated_link: bool,
    work: Option<JoinHandle<()>>,
    timeout: Option<JoinHandle<()>>,
    last_sent: Instant,
    id: Option<IdChange>,
    refused: bool,
}

impl Attempt {
    fn drop_all(&mut self) {
        for task in [self.work.take(), self.timeout.take()].into_iter().flatten() {
            task.abort();
        }
        self.packets = None;
    }
}

pub struct ServerSession {
    outcomes: mpsc::UnboundedSender<Outcome>,
    // The servers left to try in this pass.
    pending: VecDeque<Server>,
    attempts: Vec<Attempt>,
    // Servers not known to take obfuscation that were tried obfuscated once; not again until they say they take it.
    probed: HashSet<Address>,
    session: Option<Attempt>,
    // The server asked for, or none for any.
    target: Option<Server>,
    looking: bool,
    obfuscated_pass: bool,
    attempted: bool,
    last: Option<Server>,
    last_failure: String,
    last_client_id: u32,
    retry: Option<JoinHandle<()>>,
    // A reconnect less than RETRY_AFTER ago: a server that takes us and drops us at once is not hammered.
    reconnected_lately: bool,
    keep_alive: Option<JoinHandle<()>>,
    next_number: u64,
}

impl ServerSession {
    pub fn new(outcomes: mpsc::UnboundedSender<Outcome>) -> Self {
        Self {
            outcomes,
            pending: VecDeque::new(),
            attempts: Vec::new(),
            probed: HashSet::new(),
            session: None,
            target: None,
            looking: false,
            obfuscated_pass: false,
            attempted: false,
            last: None,
            last_failure: String::new(),
            last_client_id: 0,
            retry: None,
            reconnected_lately: false,
            keep_alive: None,
            next_number: 0,
        }
    }

    /// Connects to `target`, or to the known servers one after the other until one accepts us. An open session or
    /// attempt is dropped first.
    pub fn connect(&mut self, target: Option<Server>, ctx: &mut Context<'_>) {
        self.stop("reconnecting", ctx);
        self.look(target, ctx);
    }

    /// Ends the session or the attempt; no further server is tried.
    pub fn disconnect(&mut self, ctx: &mut Context<'_>) {
        self.stop("disconnected", ctx);
    }

    pub fn is_connected(&self) -> bool {
        self.session.is_some()
    }

    pub fn is_obfuscated(&self) -> bool {
        self.session.as_ref().is_some_and(|session| session.obfuscated_link)
    }

    /// The id of the current session.
    pub fn id(&self) -> Option<IdChange> {
        self.session.as_ref().and_then(|session| session.id)
    }

    /// The server connected to, or the first being tried.
    pub fn server(&self) -> Option<&Server> {
        self.session.as_ref().or(self.attempts.first()).map(|attempt| &attempt.server)
    }

    /// Sends a packet to the connected server; false when there is no session.
    pub fn send(&mut self, packet: Packet) -> bool {
        let Some(session) = &mut self.session else {
            return false;
        };
        if session.packets.as_ref().is_none_or(|packets| packets.send(packet).is_err()) {
            return false;
        }
        session.last_sent = Instant::now();
        true
    }

    pub fn handle(&mut self, outcome: Outcome, ctx: &mut Context<'_>) {
        match outcome {
            Outcome::Resolved { attempt, ip } => match ip {
                Ok(ip) => self.resolved(attempt, ip, ctx),
                Err(reason) => self.fail(attempt, &reason, ctx),
            },
            Outcome::Opened { attempt, packets, obfuscated } => self.opened(attempt, packets, obfuscated, ctx),
            Outcome::Received { attempt, packet } => self.received(attempt, &packet, ctx),
            Outcome::Closed { attempt, reason } => self.closed(attempt, &reason, ctx),
            Outcome::Failed { attempt, reason } => self.fail(attempt, &reason, ctx),
            Outcome::Retry => {
                self.retry = None;
                if self.looking && self.session.is_none() && self.attempts.is_empty() {
                    self.look(None, ctx);
                }
            }
            Outcome::ReconnectPauseOver => self.reconnected_lately = false,
            Outcome::KeepAliveCheck => self.keep_alive_if_idle(Instant::now()),
        }
    }

    /// Offers the server no file, which keeps the connection open, once nothing was sent for [`KEEP_ALIVE`] at `now`.
    pub fn keep_alive_if_idle(&mut self, now: Instant) {
        if self.session.as_ref().is_some_and(|session| now.saturating_duration_since(session.last_sent) >= KEEP_ALIVE) {
            self.send(server_messages::keep_alive());
        }
    }

    fn look(&mut self, target: Option<Server>, ctx: &mut Context<'_>) {
        self.target = target;
        self.looking = true;
        self.attempted = false;
        self.last_failure.clear();
        self.obfuscated_pass =
            matches!(ctx.preferences.obfuscation, ObfuscationMode::Requested | ObfuscationMode::Required);
        self.fill_pass(ctx);
        self.next(ctx);
    }

    fn fill_pass(&mut self, ctx: &Context<'_>) {
        self.pending.clear();
        match &self.target {
            Some(target) => self.pending.push_back(target.clone()),
            None => {
                let statics_only = ctx.preferences.static_servers_only;
                self.pending.extend(
                    ctx.servers
                        .connectable()
                        .into_iter()
                        .filter(|server| !statics_only || ctx.servers.is_static(&server_list::address_of(server))),
                );
            }
        }
    }

    // Starts attempts while there is room; once the pass is over, the plain pass or the end.
    fn next(&mut self, ctx: &mut Context<'_>) {
        let room = if self.target.is_some() { 1 } else { ATTEMPTS_AT_ONCE };
        while self.attempts.len() < room {
            let Some(next) = self.pending.pop_front() else {
                break;
            };
            if next.port == 0 {
                continue;
            }
            let Some(obfuscated) = self.obfuscation_for(&next, ctx.preferences) else {
                continue;
            };
            let probe = obfuscated && !server_list::supports_obfuscation(&next);
            if probe {
                self.probed.insert(server_list::address_of(&next));
            }
            self.start(next, obfuscated, probe, ctx);
        }
        if !self.attempts.is_empty() || !self.pending.is_empty() {
            return;
        }
        if self.obfuscated_pass && ctx.preferences.obfuscation != ObfuscationMode::Required {
            self.obfuscated_pass = false;
            self.fill_pass(ctx);
            self.next(ctx);
            return;
        }
        // Without a server tried, waiting would change nothing.
        if self.target.is_none() && self.attempted && ctx.keep_connected {
            self.retry_later(ctx);
            return;
        }
        let tried = self.last.take();
        self.looking = false;
        // A server asked for by name tells why it failed; for any, each failure was told.
        let reason = if self.target.is_some() && self.attempted {
            self.last_failure.clone()
        } else {
            "no server accepted the connection".to_owned()
        };
        ctx.notices.push(Notice::Disconnected { server: tried, reason });
    }

    fn start(&mut self, server: Server, obfuscated: bool, probe: bool, ctx: &mut Context<'_>) {
        let number = self.next_number;
        self.next_number = self.next_number.wrapping_add(1);
        self.attempted = true;
        self.last = Some(server.clone());
        let timeout = self.after(
            LOGIN_TIMEOUT,
            Outcome::Failed { attempt: number, reason: format!("no answer within {} s", LOGIN_TIMEOUT.as_secs()) },
        );
        let mut attempt = Attempt {
            number,
            server: server.clone(),
            obfuscated,
            probe,
            packets: None,
            obfuscated_link: false,
            work: None,
            timeout: Some(timeout),
            last_sent: Instant::now(),
            id: None,
            refused: false,
        };
        match server.dynamic_ip() {
            Some(host) => {
                // A host name is looked up for each connection: the address may change.
                let outcomes = self.outcomes.clone();
                let query = format!("{host}:{}", server.port);
                attempt.work = Some(tokio::spawn(async move {
                    let found = tokio::net::lookup_host(query).await.map_err(|e| e.to_string()).and_then(|found| {
                        found
                            .filter_map(|address| match address {
                                SocketAddr::V4(v4) => Some(u32::from(*v4.ip())),
                                SocketAddr::V6(_) => None,
                            })
                            .next()
                            .ok_or_else(|| "no IPv4 address".to_owned())
                    });
                    let _ = outcomes.send(Outcome::Resolved { attempt: number, ip: found });
                }));
                self.attempts.push(attempt);
            }
            None if server.ip >> 24 == 0 => {
                self.attempts.push(attempt);
                let _ = self
                    .outcomes
                    .send(Outcome::Failed { attempt: number, reason: "no address to connect to".to_owned() });
            }
            None => {
                self.dial(&mut attempt, ctx);
                self.attempts.push(attempt);
            }
        }
    }

    fn resolved(&mut self, number: u64, ip: u32, ctx: &mut Context<'_>) {
        let Some(index) = self.attempts.iter().position(|attempt| attempt.number == number) else {
            return;
        };
        if !ctx.servers.accepts(ip) {
            let reason = format!("{} is not an address to connect to", std::net::Ipv4Addr::from(ip));
            self.fail(number, &reason, ctx);
            return;
        }
        let Some(attempt) = self.attempts.get_mut(index) else {
            return;
        };
        let named = server_list::address_of(&attempt.server);
        ctx.servers.update(&named, |server| Server { ip, port: server.port, tags: server.tags.clone() });
        attempt.server.ip = ip;
        let mut taken = std::mem::replace(attempt, placeholder(number));
        self.dial(&mut taken, ctx);
        if let Some(slot) = self.attempts.get_mut(index) {
            *slot = taken;
        }
    }

    fn dial(&self, attempt: &mut Attempt, ctx: &mut Context<'_>) {
        let address = SocketAddr::from((std::net::Ipv4Addr::from(attempt.server.ip), port_for(attempt)));
        ctx.notices.push(Notice::Connecting(attempt.server.clone()));
        let outcomes = self.outcomes.clone();
        let (number, obfuscated) = (attempt.number, attempt.obfuscated);
        let routes = ctx.routes.clone();
        attempt.work = Some(tokio::spawn(async move {
            let mut random = StdRng::from_rng(&mut rand::rng());
            match tcp_connector::connect_to_server(address, obfuscated, LOGIN_TIMEOUT, &routes, &mut random).await {
                Ok(connection) => run(number, connection, outcomes).await,
                Err(e) => {
                    let _ = outcomes.send(Outcome::Failed { attempt: number, reason: e.to_string() });
                }
            }
        }));
    }

    fn opened(&mut self, number: u64, packets: mpsc::UnboundedSender<Packet>, obfuscated: bool, ctx: &mut Context<'_>) {
        let Some(attempt) = self.attempts.iter_mut().find(|attempt| attempt.number == number) else {
            return;
        };
        let login = Login {
            user_hash: ctx.user_hash,
            client_id: self.last_client_id,
            tcp_port: ctx.listen_port,
            nick: ctx.preferences.nick.clone(),
            capabilities: capabilities(ctx.preferences.obfuscation),
        };
        match login.encode() {
            Ok(packet) => {
                let _ = packets.send(packet);
            }
            Err(e) => tracing::warn!("Cannot write our login: {e}"),
        }
        attempt.packets = Some(packets);
        attempt.obfuscated_link = obfuscated;
        attempt.last_sent = Instant::now();
    }

    fn received(&mut self, number: u64, packet: &Packet, ctx: &mut Context<'_>) {
        let in_session = self.session.as_ref().is_some_and(|session| session.number == number);
        let Some(target) = self.attempt(number).map(|attempt| attempt.server.clone()) else {
            return;
        };
        if packet.protocol() != protocol::EDONKEY {
            tracing::debug!("Dropping a packet of protocol 0x{:02X} from a server", packet.protocol());
            return;
        }
        let outcome = match packet.opcode() {
            server::IDCHANGE => IdChange::decode(packet).map(|change| self.id_changed(number, change, ctx)),
            server::SERVERMESSAGE => server_messages::decode_message(packet).map(|lines| {
                for raw in lines {
                    // Shown and logged: no terminal escapes, bells or other control characters.
                    let line = strip(&server_list::without_control(&raw)).to_owned();
                    if line.is_empty() {
                        continue;
                    }
                    if let Some(version) = version(&line) {
                        self.update(number, &[string(server_tag::VERSION, &version)], ctx);
                    }
                    let server = self.attempt(number).map_or_else(|| target.clone(), |a| a.server.clone());
                    ctx.notices.push(Notice::Message { server, line });
                }
            }),
            server::SERVERSTATUS => ServerStatus::decode(packet).map(|status| {
                if let Some(status) = status {
                    let users = Tag::Int { name: TagName::of_bytes(server_tag::USERS), value: u64::from(status.users) };
                    let files = Tag::Int { name: TagName::of_bytes(server_tag::FILES), value: u64::from(status.files) };
                    self.update(number, &[users, files], ctx);
                }
            }),
            server::SERVERIDENT => ServerIdent::decode(packet).map(|ident| {
                let mut tags = Vec::new();
                if let Some(name) = &ident.name {
                    tags.push(string(server_tag::SERVERNAME, &server_list::without_control(name)));
                }
                if let Some(description) = &ident.description {
                    tags.push(string(server_tag::DESCRIPTION, &server_list::without_control(description)));
                }
                self.update(number, &tags, ctx);
            }),
            // What only the server we are logged in to may ask or answer.
            server::CALLBACKREQUESTED if in_session => {
                CallbackRequested::decode(packet).map(|request| ctx.notices.push(Notice::CallbackRequested(request)))
            }
            server::SEARCHRESULT if in_session => {
                // Answers carry no search id, so one that cannot be read still answers the oldest search, as empty.
                let results = SearchResults::decode(packet).unwrap_or_else(|e| {
                    tracing::warn!("Malformed search answer from a server: {e}");
                    SearchResults { files: Vec::new(), more: false }
                });
                ctx.notices.push(Notice::SearchResults { server: target.clone(), results });
                Ok(())
            }
            server::FOUNDSOURCES | server::FOUNDSOURCES_OBFU if in_session => {
                FoundSources::decode(packet).map(|sources| ctx.notices.push(Notice::FoundSources(sources)))
            }
            server::SERVERLIST if in_session => server_messages::decode_server_list(packet)
                .map(|servers| ctx.notices.push(Notice::ServerList { server: target.clone(), servers })),
            server::REJECT => {
                tracing::info!("{} rejected our last request", server_list::address_of(&target));
                Ok(())
            }
            other => {
                tracing::debug!("Ignoring server opcode 0x{other:02X}");
                Ok(())
            }
        };
        if let Err(e) = outcome {
            tracing::warn!("Malformed packet 0x{:02X} from {}: {e}", packet.opcode(), server_list::address_of(&target));
        }
    }

    fn id_changed(&mut self, number: u64, change: IdChange, ctx: &mut Context<'_>) {
        let in_session = self.session.as_ref().is_some_and(|session| session.number == number);
        if change.client_id == 0 {
            let refusing = !in_session && self.attempts.iter().any(|a| a.number == number && !a.refused);
            if refusing {
                // A refusal: the next server need not wait for the whole login timeout.
                let reason = "the server answered with id 0".to_owned();
                let grace = self.after(REFUSAL_GRACE, Outcome::Failed { attempt: number, reason });
                if let Some(attempt) = self.attempts.iter_mut().find(|a| a.number == number) {
                    attempt.refused = true;
                    if let Some(timeout) = attempt.timeout.replace(grace) {
                        timeout.abort();
                    }
                }
            }
            return;
        }
        self.last_client_id = change.client_id;
        if in_session {
            if let Some(session) = &mut self.session {
                let changed = session.id.as_ref().is_none_or(|id| id.client_id != change.client_id);
                session.id = Some(change);
                if changed {
                    ctx.notices.push(Notice::IdChanged { server: session.server.clone(), id: change });
                }
            }
            return;
        }
        if let Some(index) = self.attempts.iter().position(|attempt| attempt.number == number) {
            let mut attempt = self.attempts.remove(index);
            attempt.id = Some(change);
            self.logged_in(attempt, change, ctx);
        }
    }

    fn logged_in(&mut self, mut attempt: Attempt, id: IdChange, ctx: &mut Context<'_>) {
        if let Some(timeout) = attempt.timeout.take() {
            timeout.abort();
        }
        for mut other in self.attempts.drain(..) {
            other.drop_all();
        }
        self.pending.clear();
        self.looking = false;
        self.last = None;
        attempt.last_sent = Instant::now();
        self.keep_alive = Some(self.every(KEEP_ALIVE_CHECK, || Outcome::KeepAliveCheck));
        let address = server_list::address_of(&attempt.server);
        let learnt = attempt.obfuscated && !server_list::supports_obfuscation(&attempt.server);
        let port = port_for(&attempt);
        let change = |server: &Server| {
            let logged = server.with(int(server_tag::FAIL, 0));
            // It took the obfuscation it was not known to take: from now on it is tried so, on that port.
            if learnt {
                let flags = server.udp_flags().unwrap_or(0) | u64::from(server_datagrams::UDP_TCP_OBFUSCATION);
                logged.with(int(server_tag::UDPFLAGS, flags)).with(int(server_tag::TCPPORTOBFUSCATION, u64::from(port)))
            } else {
                logged
            }
        };
        if learnt {
            self.probed.remove(&address);
        }
        ctx.servers.update(&address, change);
        attempt.server = ctx.servers.get(&address).cloned().unwrap_or_else(|| change(&attempt.server));
        let (server, obfuscated) = (attempt.server.clone(), attempt.obfuscated_link);
        self.session = Some(attempt);
        ctx.notices.push(Notice::Connected { server, id, obfuscated });
    }

    // A server not in the list (one given by hand that the list refuses) still shows what it said.
    fn update(&mut self, number: u64, tags: &[Tag], ctx: &mut Context<'_>) {
        let Some(attempt) = self.attempt_mut(number) else {
            return;
        };
        let change = |server: &Server| tags.iter().fold(server.clone(), |updated, tag| updated.with(tag.clone()));
        let address = server_list::address_of(&attempt.server);
        ctx.servers.update(&address, change);
        attempt.server = ctx.servers.get(&address).cloned().unwrap_or_else(|| change(&attempt.server));
        ctx.notices.push(Notice::Updated(attempt.server.clone()));
    }

    fn fail(&mut self, number: u64, reason: &str, ctx: &mut Context<'_>) {
        let Some(index) = self.attempts.iter().position(|attempt| attempt.number == number) else {
            return;
        };
        let mut attempt = self.attempts.remove(index);
        let address = server_list::address_of(&attempt.server);
        tracing::info!("Cannot connect to {address}{}: {reason}", if attempt.obfuscated { " obfuscated" } else { "" });
        if !attempt.probe {
            ctx.servers.update(&address, |server| {
                server.with(int(server_tag::FAIL, server.failed_count().unwrap_or(0).saturating_add(1)))
            });
        }
        attempt.drop_all();
        reason.clone_into(&mut self.last_failure);
        ctx.notices.push(Notice::AttemptFailed { server: attempt.server, reason: reason.to_owned() });
        self.next(ctx);
    }

    fn closed(&mut self, number: u64, reason: &str, ctx: &mut Context<'_>) {
        if self.attempts.iter().any(|attempt| attempt.number == number) {
            self.fail(number, reason, ctx);
            return;
        }
        if !self.session.as_ref().is_some_and(|session| session.number == number) {
            return;
        }
        if let Some(mut session) = self.session.take() {
            session.drop_all();
            ctx.notices.push(Notice::Disconnected { server: Some(session.server), reason: reason.to_owned() });
        }
        if let Some(keep_alive) = self.keep_alive.take() {
            keep_alive.abort();
        }
        if !self.looking && ctx.keep_connected {
            self.reconnect(ctx);
        }
    }

    // Any server will do; at once, unless the last reconnect was too recent.
    fn reconnect(&mut self, ctx: &mut Context<'_>) {
        if self.reconnected_lately {
            self.target = None;
            self.looking = true;
            self.retry_later(ctx);
            return;
        }
        self.reconnected_lately = true;
        drop(self.after(RETRY_AFTER, Outcome::ReconnectPauseOver));
        self.look(None, ctx);
    }

    fn retry_later(&mut self, ctx: &mut Context<'_>) {
        self.retry = Some(self.after(RETRY_AFTER, Outcome::Retry));
        ctx.notices.push(Notice::Retrying(RETRY_AFTER));
    }

    // Drops the session and the attempts; tells it when there was one.
    fn stop(&mut self, reason: &str, ctx: &mut Context<'_>) {
        let was = self.server().cloned().or_else(|| self.last.clone());
        let active = self.session.is_some() || self.looking;
        if let Some(retry) = self.retry.take() {
            retry.abort();
        }
        for mut attempt in self.attempts.drain(..) {
            attempt.drop_all();
        }
        self.pending.clear();
        if let Some(mut session) = self.session.take() {
            session.drop_all();
        }
        if let Some(keep_alive) = self.keep_alive.take() {
            keep_alive.abort();
        }
        self.looking = false;
        self.last = None;
        if active {
            ctx.notices.push(Notice::Disconnected { server: was, reason: reason.to_owned() });
        }
    }

    /// Whether to obfuscate with `server` in this pass; none when it is left out of it. The obfuscated pass takes the
    /// servers known to take obfuscation, and those never pinged, whose support is unknown; the plain pass takes every
    /// server, unless obfuscation is required.
    fn obfuscation_for(&self, server: &Server, preferences: &Preferences) -> Option<bool> {
        if !self.obfuscated_pass {
            return (preferences.obfuscation != ObfuscationMode::Required).then_some(false);
        }
        let unknown = server.udp_flags().is_none() && !self.probed.contains(&server_list::address_of(server));
        (server_list::supports_obfuscation(server) || unknown).then_some(true)
    }

    fn attempt(&self, number: u64) -> Option<&Attempt> {
        self.session.iter().chain(self.attempts.iter()).find(|attempt| attempt.number == number)
    }

    fn attempt_mut(&mut self, number: u64) -> Option<&mut Attempt> {
        self.session.iter_mut().chain(self.attempts.iter_mut()).find(|attempt| attempt.number == number)
    }

    fn after(&self, delay: Duration, outcome: Outcome) -> JoinHandle<()> {
        let outcomes = self.outcomes.clone();
        tokio::spawn(async move {
            tokio::time::sleep(delay).await;
            let _ = outcomes.send(outcome);
        })
    }

    fn every(&self, period: Duration, outcome: impl Fn() -> Outcome + Send + 'static) -> JoinHandle<()> {
        let outcomes = self.outcomes.clone();
        tokio::spawn(async move {
            let mut ticks = tokio::time::interval(period);
            // The first tick is at once.
            ticks.tick().await;
            loop {
                ticks.tick().await;
                if outcomes.send(outcome()).is_err() {
                    break;
                }
            }
        })
    }
}

impl Drop for ServerSession {
    fn drop(&mut self) {
        for mut attempt in self.attempts.drain(..).chain(self.session.take()) {
            attempt.drop_all();
        }
        for task in [self.retry.take(), self.keep_alive.take()].into_iter().flatten() {
            task.abort();
        }
    }
}

/// The capabilities our login announces.
pub fn capabilities(mode: ObfuscationMode) -> u32 {
    let caps = server_messages::CAP_ZLIB
        | server_messages::CAP_NEWTAGS
        | server_messages::CAP_UNICODE
        | server_messages::CAP_LARGEFILES;
    match mode {
        ObfuscationMode::Disabled => caps,
        ObfuscationMode::Supported => caps | server_messages::CAP_SUPPORTS_CRYPT,
        ObfuscationMode::Requested => caps | server_messages::CAP_SUPPORTS_CRYPT | server_messages::CAP_REQUESTS_CRYPT,
        ObfuscationMode::Required => {
            caps | server_messages::CAP_SUPPORTS_CRYPT
                | server_messages::CAP_REQUESTS_CRYPT
                | server_messages::CAP_REQUIRES_CRYPT
        }
    }
}

/// The server software's version in a message line such as "server version 17.15 (lugdunum)".
pub fn version(line: &str) -> Option<String> {
    let head = line.get(..VERSION_LINE.len())?;
    if !head.eq_ignore_ascii_case(VERSION_LINE) {
        return None;
    }
    let mut version = strip(line.get(VERSION_LINE.len()..)?).to_owned();
    if let Some((major, minor)) = version.split_once('.')
        && is_number(major)
        && is_number(minor)
        && let (Ok(major), Ok(minor)) = (major.parse(), minor.parse())
    {
        version = server_met::version_of(major, minor);
    }
    let kept: String = version.chars().take(MAX_VERSION).collect();
    (!kept.is_empty()).then_some(kept)
}

fn is_number(text: &str) -> bool {
    (1..=5).contains(&text.len()) && text.bytes().all(|b| b.is_ascii_digit())
}

// A server not known to take obfuscation is tried on its main port, where servers take it too.
fn port_for(attempt: &Attempt) -> u16 {
    if attempt.obfuscated && !attempt.probe {
        attempt
            .server
            .obfuscated_tcp_port()
            .and_then(|port| u16::try_from(port).ok())
            .unwrap_or(attempt.server.port)
    } else {
        attempt.server.port
    }
}

// Reads the connection's packets and writes ours until either side ends it.
async fn run(number: u64, connection: tcp_connector::Connection, outcomes: mpsc::UnboundedSender<Outcome>) {
    let tcp_connector::Connection { mut reader, mut writer, obfuscated, .. } = connection;
    let (packets, mut outgoing) = mpsc::unbounded_channel::<Packet>();
    if outcomes.send(Outcome::Opened { attempt: number, packets, obfuscated }).is_err() {
        return;
    }
    let reason = loop {
        tokio::select! {
            read = reader.read_packet(&frame_limits::server) => match read {
                Ok(Some(frame)) => {
                    // A packet that does not inflate is one bad packet, not a broken connection: it stays packed.
                    let packet = if frame.protocol() == protocol::PACKED {
                        packing::unpack_from_server(frame.clone()).unwrap_or(frame)
                    } else {
                        frame
                    };
                    if outcomes.send(Outcome::Received { attempt: number, packet }).is_err() {
                        return;
                    }
                }
                Ok(None) => break "closed by the server".to_owned(),
                Err(e) => break e.to_string(),
            },
            next = outgoing.recv() => match next {
                Some(packet) => {
                    if let Err(e) = writer.write_packet(&packet).await {
                        break e.to_string();
                    }
                }
                None => return,
            },
        }
    };
    let _ = outcomes.send(Outcome::Closed { attempt: number, reason });
}

fn placeholder(number: u64) -> Attempt {
    Attempt {
        number,
        server: Server { ip: 0, port: 0, tags: Vec::new() },
        obfuscated: false,
        probe: false,
        packets: None,
        obfuscated_link: false,
        work: None,
        timeout: None,
        last_sent: Instant::now(),
        id: None,
        refused: false,
    }
}

fn int(id: u8, value: u64) -> Tag {
    server_list::int(id, value)
}

fn string(id: u8, value: &str) -> Tag {
    server_list::string(id, value)
}
