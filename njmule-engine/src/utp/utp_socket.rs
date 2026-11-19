// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! uTP (BEP 29) over datagrams that the caller carries: the connections to and from other hosts, told apart by address
//! and connection id. Incoming connections are taken only from the hosts the acceptor expects.

use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};
use std::time::Duration;

use tokio::sync::oneshot;
use tokio::time::{Instant, MissedTickBehavior};

use super::utp_connection::{Shared, UtpConnection};
use super::utp_packet::{Type, UtpPacket};

/// Carries a uTP packet to an address; the framing around it and the socket are the caller's.
pub type Sender = Arc<dyn Fn(SocketAddr, &[u8]) + Send + Sync>;

type Expected = Arc<dyn Fn(SocketAddr) -> bool + Send + Sync>;
type Accepted = Arc<dyn Fn(UtpConnection) + Send + Sync>;
type Key = (SocketAddr, u16);

const TICK: Duration = Duration::from_millis(50);

/// The uTP connections of one datagram socket; closed when dropped.
pub struct UtpSocket {
    link: Arc<Link>,
}

pub(crate) struct Link {
    datagrams: Arc<Datagrams>,
    books: Mutex<Books>,
}

/// What the connections share with their socket: the way out and the clock.
pub(crate) struct Datagrams {
    sender: Sender,
    origin: Instant,
    closed: AtomicBool,
}

struct Books {
    connections: HashMap<Key, Arc<Shared>>,
    expected: Expected,
    accepted: Accepted,
}

impl UtpSocket {
    /// A socket sending through `sender`; its connections keep time on the tokio runtime it is made on.
    pub fn new(sender: Sender) -> Self {
        let link = Arc::new(Link {
            datagrams: Arc::new(Datagrams { sender, origin: Instant::now(), closed: AtomicBool::new(false) }),
            books: Mutex::new(Books {
                connections: HashMap::new(),
                expected: Arc::new(|_| false),
                accepted: Arc::new(|connection: UtpConnection| drop(connection)),
            }),
        });
        tokio::spawn(tick(Arc::downgrade(&link)));
        Self { link }
    }

    /// Takes the connections that hosts `expected` open, handing each to `accepted`; the others are ignored.
    pub fn accept(
        &self,
        expected: impl Fn(SocketAddr) -> bool + Send + Sync + 'static,
        accepted: impl Fn(UtpConnection) + Send + Sync + 'static,
    ) {
        let mut books = self.link.books();
        books.expected = Arc::new(expected);
        books.accepted = Arc::new(accepted);
    }

    /// A datagram's uTP packet from `from`; one that is not uTP is ignored.
    pub fn received(&self, from: SocketAddr, data: &[u8]) {
        let packet = match UtpPacket::decode(data) {
            Ok(packet) => packet,
            Err(e) => {
                tracing::debug!("Not uTP from {from}: {e}");
                return;
            }
        };
        if packet.kind == Type::Syn {
            self.syn_received(from, packet);
            return;
        }
        let known = self.link.books().connections.get(&(from, packet.connection_id)).cloned();
        if let Some(connection) = known {
            connection.received(packet);
        }
    }

    // A new connection from an expected host; a SYN sent again finds the connection it made.
    fn syn_received(&self, from: SocketAddr, syn: UtpPacket) {
        let key = (from, syn.connection_id.wrapping_add(1));
        let (known, expected, accepted) = {
            let books = self.link.books();
            (books.connections.get(&key).cloned(), books.expected.clone(), books.accepted.clone())
        };
        if let Some(connection) = known {
            connection.received(syn);
            return;
        }
        if self.link.datagrams.is_closed() || !expected(from) {
            tracing::debug!("uTP: a connection from {from} nobody expects");
            return;
        }
        let connection = Arc::new(Shared::incoming(&self.link, from, &syn, rand::random()));
        {
            let mut books = self.link.books();
            if books.connections.contains_key(&key) {
                return;
            }
            books.connections.insert(key, connection.clone());
        }
        connection.start();
        accepted(UtpConnection::new(connection));
    }

    /// Connects to `remote`, once it answered; fails when it does not.
    pub async fn connect(&self, remote: SocketAddr) -> io::Result<UtpConnection> {
        if self.link.datagrams.is_closed() {
            return Err(io::Error::other("uTP closed"));
        }
        let (answered, answer) = oneshot::channel();
        let connection = {
            let mut books = self.link.books();
            // An id that no connection to the host uses, either way.
            let receive_id = loop {
                let id: u16 = rand::random();
                if !books.connections.contains_key(&(remote, id))
                    && !books.connections.contains_key(&(remote, id.wrapping_add(1)))
                {
                    break id;
                }
            };
            let connection = Arc::new(Shared::outgoing(&self.link, remote, receive_id, answered));
            books.connections.insert((remote, receive_id), connection.clone());
            connection
        };
        connection.start();
        // Dropped with the future, it closes the attempt.
        let opened = UtpConnection::new(connection);
        match answer.await {
            Ok(Ok(())) => Ok(opened),
            Ok(Err(e)) => Err(e),
            Err(_) => Err(io::Error::other("uTP closed")),
        }
    }

    /// The hosts of the connections open now.
    pub fn connections(&self) -> Vec<SocketAddr> {
        self.link.books().connections.keys().map(|&(remote, _)| remote).collect()
    }

    /// Resets every connection and stops.
    pub fn close(&self) {
        let all: Vec<Arc<Shared>> = self.link.books().connections.values().cloned().collect();
        for connection in all {
            connection.abort("uTP closed");
        }
        self.link.datagrams.closed.store(true, Ordering::SeqCst);
        self.link.books().connections.clear();
    }
}

impl Drop for UtpSocket {
    fn drop(&mut self) {
        self.close();
    }
}

impl Link {
    fn books(&self) -> MutexGuard<'_, Books> {
        self.books.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub(crate) fn datagrams(&self) -> Arc<Datagrams> {
        self.datagrams.clone()
    }

    pub(crate) fn forget(&self, remote: SocketAddr, receive_id: u16, connection: &Shared) {
        let mut books = self.books();
        if books
            .connections
            .get(&(remote, receive_id))
            .is_some_and(|known| std::ptr::eq(Arc::as_ptr(known), connection))
        {
            books.connections.remove(&(remote, receive_id));
        }
    }
}

impl Datagrams {
    pub(crate) fn send(&self, to: SocketAddr, packet: &UtpPacket) {
        if !self.is_closed() {
            (self.sender)(to, &packet.encode());
        }
    }

    fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// The clock of the timestamps, in microseconds, wrapping.
    pub(crate) fn micros(&self) -> u32 {
        // Truncation is the wrapping the timestamps want.
        self.origin.elapsed().as_micros() as u32
    }
}

// Times the connections out and sends what their windows allow, until the socket goes.
async fn tick(link: Weak<Link>) {
    let mut ticks = tokio::time::interval(TICK);
    ticks.set_missed_tick_behavior(MissedTickBehavior::Delay);
    loop {
        ticks.tick().await;
        let Some(link) = link.upgrade() else {
            return;
        };
        if link.datagrams.is_closed() {
            return;
        }
        let all: Vec<Arc<Shared>> = link.books().connections.values().cloned().collect();
        drop(link);
        for connection in all {
            connection.tick();
        }
    }
}
