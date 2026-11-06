// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The UDP socket for server queries, on a random port. A task receives; datagrams too short for a header or not eDonkey
//! ones are dropped before the receiver gets them. A datagram from a server whose UDP key we hold is decoded with it.

use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex, PoisonError};

use rand::CryptoRng;
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::net::traffic;
use crate::net::udp_route::UdpRoute;
use crate::protocol::crypt::datagram_obfuscation;
use crate::protocol::packet::{Packet, protocol, udp_framing};
use crate::server::server_route::ServerRoute;

/// What the socket receives: each plausible datagram with its sender.
pub type Receiver = mpsc::UnboundedSender<(SocketAddr, Packet)>;

const MAX_DATAGRAM: usize = 8 * 1024;

type Keys = Box<dyn Fn(SocketAddr) -> u32 + Send + Sync>;

struct Shared {
    route: Mutex<UdpRoute>,
    // The UDP key of the server at an address, 0 for none.
    keys: Mutex<Keys>,
}

pub struct ServerUdp {
    socket: Arc<UdpSocket>,
    shared: Arc<Shared>,
    reader: Option<JoinHandle<()>>,
}

impl ServerUdp {
    /// Binds `port` (0 picks one) on every interface and starts receiving.
    pub async fn open(port: u16, receiver: Receiver) -> io::Result<Self> {
        let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, port))).await?;
        Ok(Self::start(socket, receiver))
    }

    /// A random port other than `kept`, the port Kad and clients use, on `local` (none: every interface).
    pub async fn bind_beside(kept: u16, local: Option<Ipv4Addr>) -> io::Result<UdpSocket> {
        let address = SocketAddr::from((local.unwrap_or(Ipv4Addr::UNSPECIFIED), 0));
        let first = UdpSocket::bind(address).await?;
        if first.local_addr()?.port() != kept {
            return Ok(first);
        }
        // Picked while the first still holds the port, so it is another one.
        UdpSocket::bind(address).await
    }

    /// Starts receiving on `socket`.
    pub fn start(socket: UdpSocket, receiver: Receiver) -> Self {
        let socket = Arc::new(socket);
        let shared = Arc::new(Shared { route: Mutex::new(UdpRoute::Direct), keys: Mutex::new(Box::new(|_| 0)) });
        let reader = tokio::spawn(receive(Arc::clone(&socket), Arc::clone(&shared), receiver));
        Self { socket, shared, reader: Some(reader) }
    }

    /// Sends `packet` to `to`, unless the route holds it.
    pub fn send(&self, to: SocketAddr, packet: &Packet) -> io::Result<()> {
        self.transmit(to, &udp_framing::encode(packet))
    }

    /// Sends `packet` along `route`: obfuscated with the server's key when the route has one.
    pub fn send_routed(&self, route: &ServerRoute, packet: &Packet, random: &mut impl CryptoRng) -> io::Result<()> {
        let mut datagram = udp_framing::encode(packet);
        if route.is_obfuscated() {
            datagram = datagram_obfuscation::encrypt_for_server(&datagram, route.key, random);
        }
        self.transmit(route.target, &datagram)
    }

    /// Sends and receives along `route` from now on.
    pub fn route(&self, route: UdpRoute) {
        *self.shared.route.lock().unwrap_or_else(PoisonError::into_inner) = route;
    }

    /// What puts the socket on a route, for a keeper of the route that outlives a call.
    pub fn router(&self) -> impl Fn(UdpRoute) + Send + Sync + 'static {
        let shared = Arc::clone(&self.shared);
        move |route| *shared.route.lock().unwrap_or_else(PoisonError::into_inner) = route
    }

    /// Datagrams from now on are decoded with the UDP key `keys` gives for their sender (0: none).
    pub fn decode_with(&self, keys: impl Fn(SocketAddr) -> u32 + Send + Sync + 'static) {
        *self.shared.keys.lock().unwrap_or_else(PoisonError::into_inner) = Box::new(keys);
    }

    pub fn port(&self) -> u16 {
        self.socket.local_addr().map_or(0, |address| address.port())
    }

    /// Stops receiving; the port is free once this returns, as when Kad takes it at once.
    pub async fn close(mut self) {
        if let Some(reader) = self.reader.take() {
            reader.abort();
            let _ = reader.await;
        }
    }

    fn transmit(&self, to: SocketAddr, datagram: &[u8]) -> io::Result<()> {
        let route = self.shared.route.lock().unwrap_or_else(PoisonError::into_inner).clone();
        let Some((target, data)) = route.outgoing(to, datagram) else {
            return Ok(());
        };
        match self.socket.try_send_to(&data, target) {
            Ok(_) => {}
            // Not known writable yet, as a socket just opened, or its buffer full: sent once it is.
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                let socket = Arc::clone(&self.socket);
                tokio::spawn(async move {
                    if let Err(e) = socket.send_to(&data, target).await {
                        tracing::debug!("Cannot send a datagram to {target}: {e}");
                    }
                });
            }
            Err(e) => return Err(e),
        }
        traffic::sent(datagram.len(), false);
        Ok(())
    }
}

impl Drop for ServerUdp {
    fn drop(&mut self) {
        if let Some(reader) = self.reader.take() {
            reader.abort();
        }
    }
}

async fn receive(socket: Arc<UdpSocket>, shared: Arc<Shared>, receiver: Receiver) {
    let mut buffer = vec![0u8; MAX_DATAGRAM];
    loop {
        let (length, from) = match socket.recv_from(&mut buffer).await {
            Ok(received) => received,
            // On Windows an ICMP error for an earlier datagram fails a receive: harmless.
            Err(e) => {
                tracing::debug!("Server UDP: {e}");
                continue;
            }
        };
        traffic::received(length);
        let Some(data) = buffer.get(..length) else {
            continue;
        };
        let route = shared.route.lock().unwrap_or_else(PoisonError::into_inner).clone();
        let Some((sender, payload)) = route.incoming(from, data) else {
            continue;
        };
        let key = (shared.keys.lock().unwrap_or_else(PoisonError::into_inner))(sender);
        match datagram_obfuscation::decode_from_server(payload, key) {
            Ok(received) if received.packet.protocol() == protocol::EDONKEY => {
                if receiver.send((sender, received.packet)).is_err() {
                    return;
                }
            }
            Ok(_) => {}
            Err(_) => tracing::debug!("Dropping a malformed datagram from {sender}"),
        }
    }
}
