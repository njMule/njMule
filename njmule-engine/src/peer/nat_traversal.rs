// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Connections between firewalled clients through their routers, over uTP or QUIC on the client UDP port. As requester
//! we ask the target's Kad buddy for a rendezvous, punch holes towards the target and open the connection once its
//! holes come, over QUIC when the target has it; as target we hear the rendezvous from our buddy, punch holes back and
//! take the connection from the address the requester was seen at, or from near it. A client whose UDP port is
//! reachable takes connections from anyone too.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, PoisonError, Weak};
use std::task::{Context, Poll};
use std::time::Duration;

use data_encoding::HEXLOWER;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::oneshot;
use tokio::time::Instant;

use crate::protocol::ed2k::nat_traversal_messages::{
    self as messages, Caps, FRAME_CAPS, FRAME_QUIC, FRAME_UTP, OPTION_QUIC, OPTION_UTP, Rendezvous, TRANSPORT_QUIC,
    TRANSPORT_UTP,
};
use crate::protocol::packet::{Packet, protocol};
use crate::quic::quic_link::{OurHash, Peers, QuicLink};
use crate::quic::quic_transport::QuicTransport;
use crate::util::ipv4;
use crate::utp::utp_connection::UtpConnection;
use crate::utp::utp_socket::UtpSocket;

/// How plain datagrams leave the client UDP port.
pub type Datagrams = Arc<dyn Fn(SocketAddr, &Packet) + Send + Sync>;

/// Where a connection through the routers goes, and whether this side speaks first.
pub type Connections = Arc<dyn Fn(RoutedConnection, bool) + Send + Sync>;

/// How long a rendezvous partner may take to open the connection.
pub const EXPECTED_FOR: Duration = Duration::from_secs(30);

/// How long a requester waits for the connection.
pub const ATTEMPT: Duration = Duration::from_secs(25);

/// How long a host QUIC failed with is met over uTP instead.
pub const QUIC_COOLDOWN: Duration = Duration::from_secs(120);

// The ports around a punched one that a router may have chosen instead.
const SWEEP: u16 = 16;
const PORT_WINDOW: u16 = 512;
const TARGET_PUNCHES: usize = 12;
const REQUESTER_PUNCHES: usize = 6;
// A requester opens the connection once the target's holes came, or after this.
const CONNECT_AFTER: Duration = Duration::from_millis(500);

/// A connection through the routers, a byte stream over either transport.
pub enum RoutedConnection {
    Utp(UtpConnection),
    Quic(Box<QuicTransport>),
}

/// What a requester asks for: the target and its hash, the target's buddy and its id, the file, our public address
/// when we know it (`our_ip` as [`ipv4`] holds it, 0 otherwise), and whether the target has QUIC.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Request {
    pub target: SocketAddr,
    pub target_hash: [u8; 16],
    pub buddy: SocketAddr,
    pub buddy_id: [u8; 16],
    pub file: [u8; 16],
    pub our_ip: u32,
    pub our_port: u16,
    pub has_quic: bool,
}

/// The connection a rendezvous opened, which went to the connections: where it leads and over which transport.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Opened {
    pub remote: SocketAddr,
    pub over_quic: bool,
}

/// NAT traversal on one client UDP port; closed when dropped.
pub struct NatTraversal {
    inner: Arc<Inner>,
}

struct Inner {
    datagrams: Datagrams,
    connections: Connections,
    our_hash: OurHash,
    our_options: Arc<dyn Fn() -> u8 + Send + Sync>,
    reachable: Arc<dyn Fn() -> bool + Send + Sync>,
    utp: UtpSocket,
    quic: QuicLink,
    books: Mutex<Books>,
}

#[derive(Default)]
struct Books {
    expected: Vec<Expected>,
    quic_failed: HashMap<SocketAddr, Instant>,
    // The hosts a connection is being opened to, and over which transport.
    attempting: HashSet<(SocketAddr, bool)>,
}

// A host whose connection we wait for, near a port; with the requester's answer, when we asked for it.
#[derive(Clone)]
struct Expected {
    at: SocketAddr,
    until: Instant,
    peer: [u8; 16],
    waiting: Option<Arc<Waiting>>,
    over_quic: bool,
}

// The requester's answer, given once.
struct Waiting {
    answer: Mutex<Option<oneshot::Sender<Opened>>>,
}

// The QUIC link's view of the books, which exist only once the link does.
struct QuicPeers {
    inner: OnceLock<Weak<Inner>>,
}

impl NatTraversal {
    /// `our_options` are our connect options, which a capability answer tells; `reachable` tells whether our UDP port
    /// takes datagrams from anyone, so that connections from hosts not expected are taken too.
    pub fn new(
        datagrams: Datagrams,
        connections: Connections,
        our_hash: OurHash,
        our_options: Arc<dyn Fn() -> u8 + Send + Sync>,
        reachable: Arc<dyn Fn() -> bool + Send + Sync>,
    ) -> io::Result<Self> {
        let out = datagrams.clone();
        let utp = UtpSocket::new(Arc::new(move |to, packet: &[u8]| out(to, &messages::utp_frame(packet))));
        let out = datagrams.clone();
        let peers = Arc::new(QuicPeers { inner: OnceLock::new() });
        let quic = QuicLink::new(
            Arc::new(move |to, datagram: &[u8]| out(to, &messages::quic_frame(datagram))),
            our_hash.clone(),
            peers.clone(),
        )?;
        let inner = Arc::new(Inner {
            datagrams,
            connections,
            our_hash,
            our_options,
            reachable,
            utp,
            quic,
            books: Mutex::new(Books::default()),
        });
        let _ = peers.inner.set(Arc::downgrade(&inner));
        let (expected, accepted) = (Arc::downgrade(&inner), Arc::downgrade(&inner));
        inner.utp.accept(
            move |from| expected.upgrade().is_some_and(|inner| inner.may_connect(from)),
            move |connection| {
                if let Some(inner) = accepted.upgrade() {
                    inner.accepted(RoutedConnection::Utp(connection));
                }
            },
        );
        Ok(Self { inner })
    }

    /// A frame of protocol 0xB2 from `from`.
    pub fn frame(&self, from: SocketAddr, packet: &Packet) {
        if packet.protocol() != protocol::UDP_RESERVED_2 {
            return;
        }
        match packet.opcode() {
            FRAME_UTP => self.inner.utp.received(from, packet.payload()),
            FRAME_QUIC => self.inner.quic.received(from, packet.payload()),
            FRAME_CAPS => self.inner.answer_caps(from, packet),
            // Capability answers and keys: nothing we need.
            _ => {}
        }
    }

    /// A hole punch from `from`: a requester connects there now if it waited for the target's holes.
    pub fn hole_punched(&self, from: SocketAddr) {
        for awaited in self.inner.live() {
            if awaited.at.ip() == from.ip() && awaited.waiting.as_ref().is_some_and(|waiting| !waiting.is_done()) {
                self.inner.connect_to(from, &awaited);
            }
        }
    }

    /// As target: our buddy passed on a rendezvous from the requester it saw at `observed`. Its connection is awaited
    /// from there or from the address it gave, and holes are punched towards both.
    pub fn rendezvous_forwarded(&self, observed: SocketAddr, rendezvous: &Rendezvous) {
        if rendezvous.options & (OPTION_UTP | OPTION_QUIC) == 0 {
            tracing::debug!("A rendezvous from {observed} without uTP or QUIC");
            return;
        }
        let mut ats = Vec::new();
        if !observed.ip().is_unspecified() && observed.port() != 0 {
            ats.push(observed);
        }
        if rendezvous.requester_ip != 0 && rendezvous.requester_port != 0 {
            let told = ipv4::socket_address(rendezvous.requester_ip, rendezvous.requester_port);
            if !ats.contains(&told) {
                ats.push(told);
            }
        }
        let until = in_time(EXPECTED_FOR);
        for &at in &ats {
            let awaited = Expected { at, until, peer: rendezvous.requester, waiting: None, over_quic: false };
            self.inner.books().expected.push(awaited);
            self.inner.punch(at, TARGET_PUNCHES);
        }
        tracing::debug!("Rendezvous with {} at {ats:?}", HEXLOWER.encode(&rendezvous.requester));
    }

    /// As requester: asks the target's buddy for a rendezvous about the file; the connection is QUIC when the target
    /// has it and QUIC did not fail with it lately, else uTP. The packets leave at once; the future ends once the
    /// connection went to the connections, or fails when none opens in time.
    pub fn rendezvous(&self, request: Request) -> impl Future<Output = io::Result<Opened>> + Send + 'static {
        let inner = &self.inner;
        let over_quic = request.has_quic && !inner.quic_failed_lately(request.target);
        let (answered, answer) = oneshot::channel();
        let waiting = Arc::new(Waiting { answer: Mutex::new(Some(answered)) });
        let awaited = Expected {
            at: request.target,
            until: in_time(EXPECTED_FOR),
            peer: request.target_hash,
            waiting: Some(waiting.clone()),
            over_quic,
        };
        inner.books().expected.push(awaited.clone());
        // Our router lets the target's holes and connection in once ours went out towards it.
        inner.punch(request.target, REQUESTER_PUNCHES);
        let asked = Rendezvous {
            requester: (inner.our_hash)(),
            options: inner.our_traversal_options(),
            file: request.file,
            requester_ip: request.our_ip,
            requester_port: request.our_port,
            transport: if over_quic { TRANSPORT_QUIC } else { TRANSPORT_UTP },
        };
        (inner.datagrams)(request.buddy, &asked.encode_through_buddy(&request.buddy_id));
        (inner.datagrams)(request.target, &messages::hole_punch());
        let later = Arc::downgrade(inner);
        let target = request.target;
        tokio::spawn(async move {
            tokio::time::sleep(CONNECT_AFTER).await;
            if let Some(inner) = later.upgrade() {
                inner.connect_to(target, &awaited);
            }
        });
        let forget = Forgetting { inner: Arc::downgrade(inner), waiting };
        async move {
            let opened = tokio::time::timeout(ATTEMPT, answer).await;
            drop(forget);
            match opened {
                Ok(Ok(opened)) => Ok(opened),
                Ok(Err(_)) => Err(io::Error::other("NAT traversal closed")),
                Err(_) => Err(io::Error::new(io::ErrorKind::TimedOut, format!("no connection with {target} in time"))),
            }
        }
    }

    /// The hosts of the connections open now through routers.
    pub fn connections(&self) -> Vec<SocketAddr> {
        let mut all = self.inner.utp.connections();
        all.extend(self.inner.quic.connections());
        all
    }

    pub fn close(&self) {
        self.inner.utp.close();
        self.inner.quic.close();
        self.inner.books().expected.clear();
    }
}

impl Drop for NatTraversal {
    fn drop(&mut self) {
        self.close();
    }
}

impl Inner {
    fn books(&self) -> MutexGuard<'_, Books> {
        self.books.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn our_traversal_options(&self) -> u8 {
        (self.our_options)() | OPTION_UTP | OPTION_QUIC
    }

    fn quic_failed_lately(&self, at: SocketAddr) -> bool {
        let mut books = self.books();
        books.quic_failed.retain(|_, failed| failed.elapsed() < QUIC_COOLDOWN);
        books.quic_failed.contains_key(&at)
    }

    // The hosts we wait for, those past their time dropped.
    fn live(&self) -> Vec<Expected> {
        let now = Instant::now();
        let mut books = self.books();
        books.expected.retain(|awaited| awaited.until >= now);
        books.expected.clone()
    }

    // The host we wait for nearest the port, on the same address.
    fn find(&self, from: SocketAddr) -> Option<Expected> {
        self.live()
            .into_iter()
            .filter(|awaited| awaited.at.ip() == from.ip() && awaited.at.port().abs_diff(from.port()) <= PORT_WINDOW)
            .min_by_key(|awaited| awaited.at.port().abs_diff(from.port()))
    }

    // A connection from an expected host, near the port awaited, or from anyone when our port is reachable.
    fn may_connect(&self, from: SocketAddr) -> bool {
        self.find(from).is_some() || (self.reachable)()
    }

    // Connects once over the transport chosen; a connection the target opened first wins.
    fn connect_to(self: &Arc<Self>, at: SocketAddr, awaited: &Expected) {
        let Some(waiting) = awaited.waiting.clone() else {
            return;
        };
        if waiting.is_done() {
            return;
        }
        let over_quic = awaited.over_quic;
        // Neither link is asked under our lock: they ask us back under theirs.
        let busy = if over_quic { self.quic.connecting(at) } else { self.utp.connections().contains(&at) };
        if busy || !self.books().attempting.insert((at, over_quic)) {
            return;
        }
        let inner = Arc::clone(self);
        let (peer, expected_at) = (awaited.peer, awaited.at);
        tokio::spawn(async move {
            let opened = if over_quic {
                inner.quic.connect(at, Some(peer)).await.map(|c| RoutedConnection::Quic(Box::new(c)))
            } else {
                inner.utp.connect(at).await.map(RoutedConnection::Utp)
            };
            inner.books().attempting.remove(&(at, over_quic));
            match opened {
                Ok(connection) => inner.opened(connection, &waiting),
                Err(e) if over_quic => {
                    tracing::debug!("No QUIC connection with {at}: {e}");
                    inner.books().quic_failed.insert(expected_at, Instant::now());
                }
                Err(e) => tracing::debug!("No uTP connection with {at}: {e}"),
            }
        });
    }

    fn opened(&self, connection: RoutedConnection, waiting: &Waiting) {
        if waiting.answer(connection.opened()) {
            (self.connections)(connection, false);
        }
    }

    // The side that takes a connection says hello first; the side that opened it answers.
    fn accepted(&self, connection: RoutedConnection) {
        let by = self.find(connection.remote_address());
        if let Some(awaited) = &by {
            match &awaited.waiting {
                // The one we opened won.
                Some(waiting) if !waiting.answer(connection.opened()) => return,
                Some(_) => {}
                None => self.books().expected.retain(|other| !other.same(awaited)),
            }
        }
        (self.connections)(connection, true);
    }

    fn forget(&self, waiting: &Arc<Waiting>) {
        self.books()
            .expected
            .retain(|awaited| !awaited.waiting.as_ref().is_some_and(|other| Arc::ptr_eq(other, waiting)));
    }

    // A capability frame from a host we wait for, or that knows us, gets our answer: uTP and QUIC.
    fn answer_caps(&self, from: SocketAddr, packet: &Packet) {
        let caps = match Caps::decode(packet) {
            Ok(caps) => caps,
            Err(e) => {
                tracing::debug!("Bad capabilities from {from}: {e}");
                return;
            }
        };
        let ours = (self.our_hash)();
        if caps.expected != ours && (caps.expected != [0; 16] || self.find(from).is_none()) {
            return;
        }
        let answer = Caps {
            answer: true,
            options: self.our_traversal_options(),
            sender: ours,
            expected: caps.sender,
            file: caps.file,
        };
        (self.datagrams)(from, &answer.encode());
    }

    // A burst towards the port, then one punch at each port near it, as the router may have chosen one of those.
    fn punch(&self, at: SocketAddr, count: usize) {
        let punch = messages::hole_punch();
        for _ in 0..count {
            (self.datagrams)(at, &punch);
        }
        for d in 1..=SWEEP {
            for port in [at.port().checked_sub(d), at.port().checked_add(d)].into_iter().flatten() {
                if port != 0 {
                    (self.datagrams)(SocketAddr::new(at.ip(), port), &punch);
                }
            }
        }
    }
}

// The instant `wait` from now.
fn in_time(wait: Duration) -> Instant {
    let now = Instant::now();
    now.checked_add(wait).unwrap_or(now)
}

impl Expected {
    fn same(&self, other: &Self) -> bool {
        self.at == other.at && self.until == other.until && self.peer == other.peer && self.waiting.is_none()
    }
}

impl Waiting {
    fn is_done(&self) -> bool {
        self.answer.lock().unwrap_or_else(PoisonError::into_inner).as_ref().is_none_or(oneshot::Sender::is_closed)
    }

    // Whether the requester took this answer, the first.
    fn answer(&self, opened: Opened) -> bool {
        let answered = self.answer.lock().unwrap_or_else(PoisonError::into_inner).take();
        answered.is_some_and(|answered| answered.send(opened).is_ok())
    }
}

// Forgets the requester's host once its answer is no longer awaited.
struct Forgetting {
    inner: Weak<Inner>,
    waiting: Arc<Waiting>,
}

impl Drop for Forgetting {
    fn drop(&mut self) {
        if let Some(inner) = self.inner.upgrade() {
            inner.forget(&self.waiting);
        }
    }
}

impl Peers for QuicPeers {
    fn may_connect(&self, from: SocketAddr) -> bool {
        self.inner.get().and_then(Weak::upgrade).is_some_and(|inner| inner.may_connect(from))
    }

    fn expected(&self, from: SocketAddr) -> Option<[u8; 16]> {
        self.inner.get().and_then(Weak::upgrade).and_then(|inner| inner.find(from)).map(|awaited| awaited.peer)
    }

    fn accepted(&self, connection: QuicTransport) {
        if let Some(inner) = self.inner.get().and_then(Weak::upgrade) {
            inner.accepted(RoutedConnection::Quic(Box::new(connection)));
        }
    }
}

impl RoutedConnection {
    pub fn remote_address(&self) -> SocketAddr {
        match self {
            Self::Utp(connection) => connection.remote_address(),
            Self::Quic(connection) => connection.remote_address(),
        }
    }

    fn opened(&self) -> Opened {
        Opened { remote: self.remote_address(), over_quic: matches!(self, Self::Quic(_)) }
    }
}

impl std::fmt::Debug for RoutedConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let over = if matches!(self, Self::Quic(_)) { "QUIC" } else { "uTP" };
        write!(f, "{over} {}", self.remote_address())
    }
}

impl AsyncRead for RoutedConnection {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            Self::Utp(connection) => Pin::new(connection).poll_read(cx, buf),
            Self::Quic(connection) => Pin::new(connection.as_mut()).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for RoutedConnection {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        match self.get_mut() {
            Self::Utp(connection) => Pin::new(connection).poll_write(cx, buf),
            Self::Quic(connection) => Pin::new(connection.as_mut()).poll_write(cx, buf),
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            Self::Utp(connection) => Pin::new(connection).poll_flush(cx),
            Self::Quic(connection) => Pin::new(connection.as_mut()).poll_flush(cx),
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.get_mut() {
            Self::Utp(connection) => Pin::new(connection).poll_shutdown(cx),
            Self::Quic(connection) => Pin::new(connection.as_mut()).poll_shutdown(cx),
        }
    }
}
