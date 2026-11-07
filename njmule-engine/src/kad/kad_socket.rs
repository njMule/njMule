// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The client UDP socket, which Kad shares: datagrams are opened with any of the keys they may be obfuscated with,
//! unpacked, and their Kad and client packets go to the receiver. Kad stays on IPv4; over IPv6 only client packets come,
//! plain.

use std::io;
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Arc, Mutex, PoisonError, Weak};

use socket2::{Domain, Protocol as SocketProtocol, Socket, Type};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::kad::kad_identity::KadIdentity;
use crate::kad::kad_node::Transport;
use crate::kad::lookup::AddressTest;
use crate::kad::uint128::UInt128;
use crate::net::traffic;
use crate::net::udp_route::{self, UdpRoute};
use crate::protocol::crypt::datagram_obfuscation;
use crate::protocol::packet::{Packet, packing, protocol, udp_framing};

const MAX_DATAGRAM: usize = 8 * 1024;
const MAX_UNPACKED: usize = 250_000;
const PACK_ABOVE: usize = 200;

/// A packet the socket received.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Incoming {
    /// A Kad packet, with the verify keys its datagram carried (0: none).
    Kad { ip: u32, port: u16, packet: Packet, receiver_key: u32, sender_key: u32 },
    /// A client packet of the extended protocol, such as a callback request or a re-ask.
    Client { ip: u32, port: u16, packet: Packet },
    /// A client packet from an IPv6 address.
    ClientOverIpv6 { from: SocketAddr, packet: Packet },
}

/// Where the socket's packets go.
pub type Receiver = mpsc::UnboundedSender<Incoming>;

/// What opens the datagrams: our user hash and Kad identity give the keys, and the IP filter drops some unread.
#[derive(Clone)]
pub struct Keys {
    pub user_hash: [u8; 16],
    pub identity: KadIdentity,
    pub blocked: AddressTest,
}

struct Shared {
    route: Mutex<UdpRoute>,
}

impl Shared {
    fn route(&self) -> UdpRoute {
        self.route.lock().unwrap_or_else(PoisonError::into_inner).clone()
    }
}

pub struct KadSocket {
    socket: Arc<UdpSocket>,
    shared: Arc<Shared>,
    reader: Option<JoinHandle<()>>,
}

impl KadSocket {
    /// Binds `port` (0 picks one) on `local`, or on every interface, IPv6 included where there is IPv6, and starts
    /// receiving.
    pub fn open(local: Option<Ipv4Addr>, port: u16, keys: Keys, receiver: Receiver) -> io::Result<Self> {
        let socket = Arc::new(UdpSocket::from_std(bind(local, port)?)?);
        let shared = Arc::new(Shared { route: Mutex::new(UdpRoute::Direct) });
        let reader = tokio::spawn(receive(Arc::clone(&socket), Arc::clone(&shared), keys, receiver));
        Ok(Self { socket, shared, reader: Some(reader) })
    }

    /// What sends the node's packets; it stops sending once the socket is closed.
    pub fn transport(&self) -> KadTransport {
        KadTransport { socket: Arc::downgrade(&self.socket), shared: Arc::clone(&self.shared) }
    }

    /// Sends a client packet, obfuscated for `receiver_user_hash` when given; `our_ip` is part of that key.
    pub fn send_client(&self, ip: u32, port: u16, packet: &Packet, receiver_user_hash: Option<[u8; 16]>, our_ip: u32) {
        let mut datagram = udp_framing::encode(packet);
        if let Some(user_hash) = receiver_user_hash
            && our_ip != 0
        {
            datagram =
                datagram_obfuscation::encrypt_for_client(&datagram, &user_hash, our_ip.to_be_bytes(), &mut rand::rng());
        }
        self.transmit(SocketAddr::from((Ipv4Addr::from(ip), port)), &datagram);
    }

    /// Sends a client packet plain to an IPv6 address: clients do not obfuscate their datagrams over IPv6.
    pub fn send_client_to(&self, to: SocketAddr, packet: &Packet) {
        self.transmit(to, &udp_framing::encode(packet));
    }

    pub fn port(&self) -> u16 {
        self.socket.local_addr().map_or(0, |address| address.port())
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

    /// Stops receiving; the port is free once this returns, as when Kad starts again at once on the same port.
    pub async fn close(mut self) {
        if let Some(reader) = self.reader.take() {
            reader.abort();
            let _ = reader.await;
        }
    }

    fn transmit(&self, to: SocketAddr, datagram: &[u8]) {
        // A bad address must not end the caller's work.
        if let Err(e) = udp_route::transmit(&self.socket, &self.shared.route(), to, datagram) {
            tracing::debug!("Cannot send to {to}: {e}");
        }
    }
}

impl Drop for KadSocket {
    fn drop(&mut self) {
        if let Some(reader) = self.reader.take() {
            reader.abort();
        }
    }
}

/// Sends the Kad node's packets: packed when large, obfuscated with the receiver's id or verify key when given.
pub struct KadTransport {
    socket: Weak<UdpSocket>,
    shared: Arc<Shared>,
}

impl Transport for KadTransport {
    fn send(
        &mut self,
        ip: u32,
        port: u16,
        packet: &Packet,
        receiver_id: Option<UInt128>,
        receiver_key: u32,
        sender_key: u32,
    ) {
        let Some(socket) = self.socket.upgrade() else {
            return;
        };
        let framed = if packet.length() > PACK_ABOVE {
            packing::pack(packet.clone()).unwrap_or_else(|_| packet.clone())
        } else {
            packet.clone()
        };
        let mut datagram = udp_framing::encode(&framed);
        if receiver_id.is_some() || receiver_key != 0 {
            let id = receiver_id.map(|id| id.to_wire_bytes());
            match datagram_obfuscation::encrypt_for_kad(
                &datagram,
                id.as_ref(),
                receiver_key,
                sender_key,
                &mut rand::rng(),
            ) {
                Ok(sealed) => datagram = sealed,
                Err(e) => {
                    tracing::debug!("Cannot obfuscate a Kad packet: {e}");
                    return;
                }
            }
        }
        let to = SocketAddr::from((Ipv4Addr::from(ip), port));
        // A bad address must not end the caller's work, a lookup or the table's upkeep.
        if let Err(e) = udp_route::transmit(&socket, &self.shared.route(), to, &datagram) {
            tracing::debug!("Cannot send to {to}: {e}");
        }
    }
}

// Every interface: IPv6 and IPv4 at once where there is IPv6, else IPv4 only.
fn bind(local: Option<Ipv4Addr>, port: u16) -> io::Result<std::net::UdpSocket> {
    let socket = match local {
        Some(local) => std::net::UdpSocket::bind((local, port))?,
        None => match free_for_ipv4(port).and_then(|()| bind_dual_stack(port)) {
            Ok(socket) => socket,
            Err(e) => {
                tracing::debug!("No IPv6 for the client UDP port {port}: {e}");
                std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port))?
            }
        },
    };
    socket.set_nonblocking(true)?;
    Ok(socket)
}

// Windows lets an IPv6 socket take a port another program holds for IPv4: the IPv4 datagrams would go to that program.
fn free_for_ipv4(port: u16) -> io::Result<()> {
    if port != 0 {
        std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, port))?;
    }
    Ok(())
}

fn bind_dual_stack(port: u16) -> io::Result<std::net::UdpSocket> {
    let socket = Socket::new(Domain::IPV6, Type::DGRAM, Some(SocketProtocol::UDP))?;
    socket.set_only_v6(false)?;
    socket.bind(&SocketAddr::from((Ipv6Addr::UNSPECIFIED, port)).into())?;
    Ok(socket.into())
}

async fn receive(socket: Arc<UdpSocket>, shared: Arc<Shared>, keys: Keys, receiver: Receiver) {
    let mut buffer = vec![0u8; MAX_DATAGRAM];
    loop {
        let (length, from) = match socket.recv_from(&mut buffer).await {
            Ok(received) => received,
            // On Windows an ICMP error for an earlier datagram fails a receive: harmless.
            Err(e) => {
                tracing::debug!("Kad UDP: {e}");
                continue;
            }
        };
        traffic::received(length);
        let Some(data) = buffer.get(..length) else {
            continue;
        };
        let from = SocketAddr::new(from.ip().to_canonical(), from.port());
        let Some((sender, payload)) = shared.route().incoming(from, data) else {
            continue;
        };
        if sender.port() == 0 {
            continue;
        }
        let incoming = match sender {
            SocketAddr::V4(v4) => open(u32::from(*v4.ip()), v4.port(), payload, &keys),
            SocketAddr::V6(_) => open_over_ipv6(sender, payload),
        };
        if let Some(incoming) = incoming
            && receiver.send(incoming).is_err()
        {
            return;
        }
    }
}

fn open(ip: u32, port: u16, payload: &[u8], keys: &Keys) -> Option<Incoming> {
    if (keys.blocked)(ip) {
        return None;
    }
    let own_id = keys.identity.id().to_wire_bytes();
    let opened = datagram_obfuscation::decode_from_client(
        payload,
        &keys.user_hash,
        ip.to_be_bytes(),
        Some(&own_id),
        keys.identity.verify_key(ip),
    )
    .inspect_err(|e| tracing::debug!("Bad datagram from {}:{port}: {e}", Ipv4Addr::from(ip)))
    .ok()?;
    let mut packet = opened.packet;
    if packet.protocol() == protocol::KAD_PACKED {
        packet = packing::unpack(packet, MAX_UNPACKED)
            .inspect_err(|e| tracing::debug!("Bad datagram from {}:{port}: {e}", Ipv4Addr::from(ip)))
            .ok()?;
    }
    match packet.protocol() {
        protocol::EMULE => Some(Incoming::Client { ip, port, packet }),
        protocol::KAD => Some(Incoming::Kad {
            ip,
            port,
            packet,
            receiver_key: opened.receiver_verify_key,
            sender_key: opened.sender_verify_key,
        }),
        _ => None,
    }
}

fn open_over_ipv6(from: SocketAddr, payload: &[u8]) -> Option<Incoming> {
    let packet = udp_framing::decode(payload).inspect_err(|e| tracing::debug!("Bad datagram from {from}: {e}")).ok()?;
    (packet.protocol() == protocol::EMULE).then_some(Incoming::ClientOverIpv6 { from, packet })
}
