// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A router that answers NAT-PMP (RFC 6886) or PCP (RFC 6887), its successor, on port 5351 of the default gateway.
//! Asked in NAT-PMP, a router that speaks PCP only answers in PCP, and is spoken to in it from then on. A mapping counts
//! only with our own port outside: the eD2K and Kad networks are told that one. At the IPv6 gateway, a PCP MAP from our
//! IPv6 address, the same outside, opens the router's IPv6 firewall to it.
//!
//! Written here rather than taken from a crate: the clients found ask port 5351 only and send from any address, while
//! PCP checks that a pinhole's request comes from the address it opens.

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Mutex, PoisonError};
use std::time::Duration;

use rand::Rng;
use tokio::net::UdpSocket;
use tokio::time::Instant;

use crate::upnp::gateway::{GatewayError, Protocol};

/// The port routers answer on.
pub const PORT: u16 = 5351;

// RFC 6886 §3.1: the first try waits 250 ms, each other twice as long as the one before.
const FIRST_WAIT: Duration = Duration::from_millis(250);
const TRIES: u32 = 4;

const NAT_PMP_VERSION: u8 = 0;
const PCP_VERSION: u8 = 2;
const RESPONSE: u8 = 0x80;
const EXTERNAL_ADDRESS: u8 = 0;
const MAP_UDP: u8 = 1;
const MAP_TCP: u8 = 2;
const PCP_MAP: u8 = 1;
const PCP_TCP: u8 = 6;
const PCP_UDP: u8 = 17;
const NONCE: usize = 12;
// Where the nonce lies in a PCP MAP answer.
const NONCE_AT: std::ops::Range<usize> = 24..36;
const PCP_MAP_LENGTH: usize = 60;
const NAT_PMP_MAP_ANSWER: usize = 16;
const LARGEST_ANSWER: usize = 1100;

const NAT_PMP_RESULTS: [&str; 6] =
    ["success", "unsupported version", "not authorized", "network failure", "out of resources", "unsupported opcode"];
const PCP_RESULTS: [&str; 14] = [
    "success",
    "unsupported version",
    "not authorized",
    "malformed request",
    "unsupported opcode",
    "unsupported option",
    "malformed option",
    "network failure",
    "no resources",
    "unsupported protocol",
    "user over quota",
    "cannot provide the external port",
    "address mismatch",
    "excessive remote peers",
];

type Nonce = [u8; NONCE];

pub struct NatPmp {
    server: SocketAddr,
    pcp: bool,
    // PCP: the nonce of each mapping, which renewing or removing it repeats.
    nonces: Mutex<HashMap<(Protocol, u16), Nonce>>,
    // PCP at the IPv6 gateway: the pinholes opened, by id.
    pinholes: Mutex<HashMap<u32, Pinhole>>,
    pinhole_ids: AtomicU32,
}

#[derive(Clone, Copy)]
struct Pinhole {
    protocol: Protocol,
    address: Ipv6Addr,
    port: u16,
    nonce: Nonce,
}

impl NatPmp {
    /// The router at `server`, if it answers NAT-PMP or PCP within about `wait`.
    pub async fn discover(server: SocketAddr, wait: Duration) -> io::Result<Option<Self>> {
        let fits = |answer: &[u8]| match answer {
            [PCP_VERSION, _, ..] => true,
            [NAT_PMP_VERSION, second, ..] => *second == RESPONSE,
            _ => false,
        };
        let request = [NAT_PMP_VERSION, EXTERNAL_ADDRESS];
        let answer = exchange(server, None, |_| request.to_vec(), fits, tries_within(wait)).await?;
        Ok(answer.map(|answer| Self {
            server,
            pcp: answer.first() == Some(&PCP_VERSION),
            nonces: Mutex::default(),
            pinholes: Mutex::default(),
            pinhole_ids: AtomicU32::new(1),
        }))
    }

    /// The protocol it speaks, as the log names it: "NAT-PMP" or "PCP".
    pub fn protocol(&self) -> &'static str {
        if self.pcp { "PCP" } else { "NAT-PMP" }
    }

    /// Its host, as the reports name it.
    pub fn host(&self) -> String {
        self.server.ip().to_string()
    }

    /// Forwards `port` to our address towards the router for `lease` seconds; returns the seconds it granted.
    pub async fn add_mapping(&self, protocol: Protocol, port: u16, lease: u32) -> Result<u32, GatewayError> {
        if self.pcp { self.pcp_map(protocol, port, lease).await } else { self.nat_pmp_map(protocol, port, lease).await }
    }

    /// Removes the forwarding of `port`.
    pub async fn delete_mapping(&self, protocol: Protocol, port: u16) -> Result<(), GatewayError> {
        if self.pcp {
            self.pcp_request(protocol, port, 0).await?;
            self.nonces().remove(&(protocol, port));
        } else {
            self.nat_pmp_request(protocol, port, 0, 0).await?;
        }
        Ok(())
    }

    /// Opens the router's IPv6 firewall to `port` at our `address`; none when the router has no IPv6 firewall to open:
    /// only a router at an IPv6 gateway that speaks PCP opens it.
    pub async fn open_pinhole(
        &self,
        protocol: Protocol,
        address: Ipv6Addr,
        port: u16,
        lease: u32,
    ) -> Result<Option<u32>, GatewayError> {
        if !self.pcp || !self.server.is_ipv6() {
            return Ok(None);
        }
        let mut nonce = [0u8; NONCE];
        rand::rng().fill_bytes(&mut nonce);
        let pinhole = Pinhole { protocol, address, port, nonce };
        self.pcp_pinhole(pinhole, lease).await?;
        let id = self.pinhole_ids.fetch_add(1, Ordering::Relaxed);
        self.pinholes().insert(id, pinhole);
        Ok(Some(id))
    }

    /// Keeps the pinhole `id` open for `lease` seconds more.
    pub async fn renew_pinhole(&self, id: u32, lease: u32) -> Result<(), GatewayError> {
        let pinhole = self.pinholes().get(&id).copied();
        let pinhole = pinhole.ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("no pinhole {id}")))?;
        self.pcp_pinhole(pinhole, lease).await
    }

    pub async fn close_pinhole(&self, id: u32) -> Result<(), GatewayError> {
        let pinhole = self.pinholes().remove(&id);
        match pinhole {
            Some(pinhole) => self.pcp_pinhole(pinhole, 0).await,
            None => Ok(()),
        }
    }

    async fn nat_pmp_map(&self, protocol: Protocol, port: u16, lease: u32) -> Result<u32, GatewayError> {
        let (mapped, granted) = self.nat_pmp_request(protocol, port, lease, port).await?;
        if lease > 0 && mapped != port {
            self.nat_pmp_request(protocol, port, 0, 0).await?;
            return Err(GatewayError::Refused(format!("the router gave outside port {mapped} for {port}")));
        }
        Ok(granted)
    }

    // The outside port and the lease of the answer.
    async fn nat_pmp_request(
        &self,
        protocol: Protocol,
        port: u16,
        lease: u32,
        outside: u16,
    ) -> Result<(u16, u32), GatewayError> {
        let opcode = if protocol == Protocol::Tcp { MAP_TCP } else { MAP_UDP };
        let mut request = vec![NAT_PMP_VERSION, opcode, 0, 0];
        request.extend_from_slice(&port.to_be_bytes());
        request.extend_from_slice(&outside.to_be_bytes());
        request.extend_from_slice(&lease.to_be_bytes());
        let fits = |answer: &[u8]| {
            answer.len() >= NAT_PMP_MAP_ANSWER
                && answer.first() == Some(&NAT_PMP_VERSION)
                && answer.get(1) == Some(&(RESPONSE | opcode))
                && u16_at(answer, 8) == Some(port)
        };
        let answer = self.answer(|_| request.clone(), fits).await?;
        let result = u16_at(&answer, 2).unwrap_or_default();
        if result != 0 {
            let reason = NAT_PMP_RESULTS
                .get(usize::from(result))
                .map_or_else(|| format!("result {result}"), |r| (*r).to_owned());
            return Err(if matches!(result, 2 | 4 | 5) {
                GatewayError::Refused(reason)
            } else {
                GatewayError::Failed(io::Error::other(format!("NAT-PMP: {reason}")))
            });
        }
        Ok((u16_at(&answer, 10).unwrap_or_default(), u32_at(&answer, 12).unwrap_or_default()))
    }

    async fn pcp_map(&self, protocol: Protocol, port: u16, lease: u32) -> Result<u32, GatewayError> {
        let (mapped, granted) = self.pcp_request(protocol, port, lease).await?;
        if lease > 0 && mapped != port {
            self.pcp_request(protocol, port, 0).await?;
            self.nonces().remove(&(protocol, port));
            return Err(GatewayError::Refused(format!("the router gave outside port {mapped} for {port}")));
        }
        Ok(granted)
    }

    // A MAP of our address towards the router, any address outside; the outside port and the lease of the answer.
    async fn pcp_request(&self, protocol: Protocol, port: u16, lease: u32) -> Result<(u16, u32), GatewayError> {
        let nonce = *self.nonces().entry((protocol, port)).or_insert_with(|| {
            let mut fresh = [0u8; NONCE];
            rand::rng().fill_bytes(&mut fresh);
            fresh
        });
        let request = |local: IpAddr| {
            let ours = match local {
                IpAddr::V4(local) => local.to_ipv6_mapped(),
                IpAddr::V6(local) => local,
            };
            pcp_map_request(lease, ours, nonce, protocol, port, Ipv4Addr::UNSPECIFIED.to_ipv6_mapped())
        };
        let answer = self.answer(request, |answer| pcp_answer_fits(answer, &nonce)).await?;
        pcp_result(&answer)?;
        Ok((u16_at(&answer, 42).unwrap_or_default(), u32_at(&answer, 4).unwrap_or_default()))
    }

    // The same address and port outside as inside: only the firewall opens.
    async fn pcp_pinhole(&self, pinhole: Pinhole, lease: u32) -> Result<(), GatewayError> {
        let request =
            |_| pcp_map_request(lease, pinhole.address, pinhole.nonce, pinhole.protocol, pinhole.port, pinhole.address);
        let fits = |answer: &[u8]| pcp_answer_fits(answer, &pinhole.nonce);
        let from = Some(IpAddr::V6(pinhole.address));
        let answer = exchange(self.server, from, request, fits, TRIES).await?.ok_or_else(|| {
            io::Error::new(io::ErrorKind::TimedOut, format!("PCP: the router at {} did not answer", self.host()))
        })?;
        pcp_result(&answer)
    }

    async fn answer(
        &self,
        request: impl Fn(IpAddr) -> Vec<u8>,
        fits: impl Fn(&[u8]) -> bool,
    ) -> Result<Vec<u8>, GatewayError> {
        let answer = exchange(self.server, None, request, fits, TRIES).await?;
        answer.ok_or_else(|| {
            let text = format!("{}: the router at {} did not answer", self.protocol(), self.host());
            GatewayError::Failed(io::Error::new(io::ErrorKind::TimedOut, text))
        })
    }

    fn nonces(&self) -> std::sync::MutexGuard<'_, HashMap<(Protocol, u16), Nonce>> {
        self.nonces.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn pinholes(&self) -> std::sync::MutexGuard<'_, HashMap<u32, Pinhole>> {
        self.pinholes.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

fn pcp_map_request(
    lease: u32,
    ours: Ipv6Addr,
    nonce: Nonce,
    protocol: Protocol,
    port: u16,
    outside: Ipv6Addr,
) -> Vec<u8> {
    let mut request = Vec::with_capacity(PCP_MAP_LENGTH);
    request.extend_from_slice(&[PCP_VERSION, PCP_MAP, 0, 0]);
    request.extend_from_slice(&lease.to_be_bytes());
    request.extend_from_slice(&ours.octets());
    request.extend_from_slice(&nonce);
    request.extend_from_slice(&[if protocol == Protocol::Tcp { PCP_TCP } else { PCP_UDP }, 0, 0, 0]);
    request.extend_from_slice(&port.to_be_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    request.extend_from_slice(&outside.octets());
    request
}

fn pcp_answer_fits(answer: &[u8], nonce: &Nonce) -> bool {
    answer.len() >= PCP_MAP_LENGTH
        && answer.first() == Some(&PCP_VERSION)
        && answer.get(1) == Some(&(RESPONSE | PCP_MAP))
        && answer.get(NONCE_AT) == Some(nonce.as_slice())
}

fn pcp_result(answer: &[u8]) -> Result<(), GatewayError> {
    let result = answer.get(3).copied().unwrap_or_default();
    if result == 0 {
        return Ok(());
    }
    let reason = PCP_RESULTS.get(usize::from(result)).map_or_else(|| format!("result {result}"), |r| (*r).to_owned());
    Err(if result == 2 || (8..=11).contains(&result) {
        GatewayError::Refused(reason)
    } else {
        GatewayError::Failed(io::Error::other(format!("PCP: {reason}")))
    })
}

// As many tries as fit in `wait`, at least one.
fn tries_within(wait: Duration) -> u32 {
    let mut tries = 1;
    let mut total = FIRST_WAIT;
    while tries < TRIES {
        let next = FIRST_WAIT.saturating_mul(2u32.saturating_pow(tries));
        if total.saturating_add(next) > wait {
            break;
        }
        total = total.saturating_add(next);
        tries = tries.saturating_add(1);
    }
    tries
}

// Sends the request again after 250 ms, 500 ms, ...; only an answer from the router that fits counts. A request about
// an address of ours goes from it, which PCP checks.
async fn exchange(
    server: SocketAddr,
    from: Option<IpAddr>,
    request: impl Fn(IpAddr) -> Vec<u8>,
    fits: impl Fn(&[u8]) -> bool,
    tries: u32,
) -> io::Result<Option<Vec<u8>>> {
    let any: IpAddr = if server.is_ipv6() { Ipv6Addr::UNSPECIFIED.into() } else { Ipv4Addr::UNSPECIFIED.into() };
    let socket = UdpSocket::bind(SocketAddr::new(from.unwrap_or(any), 0)).await?;
    socket.connect(server).await?;
    let local = socket.local_addr()?.ip();
    if from.is_none() && !local.is_ipv4() && !server.is_ipv6() {
        return Err(io::Error::new(io::ErrorKind::AddrNotAvailable, format!("no IPv4 address towards {server}")));
    }
    let bytes = request(local);
    let mut buffer = vec![0u8; LARGEST_ANSWER];
    let mut wait = FIRST_WAIT;
    for _ in 0..tries {
        socket.send(&bytes).await?;
        let deadline = Instant::now().checked_add(wait).unwrap_or_else(Instant::now);
        loop {
            let received = match tokio::time::timeout_at(deadline, socket.recv(&mut buffer)).await {
                Err(_) => break,
                Ok(received) => received,
            };
            match received {
                Ok(count) => {
                    let got = buffer.get(..count).unwrap_or_default();
                    if fits(got) {
                        return Ok(Some(got.to_vec()));
                    }
                }
                // Nothing listens there.
                Err(e) if matches!(e.kind(), io::ErrorKind::ConnectionRefused | io::ErrorKind::ConnectionReset) => {
                    return Ok(None);
                }
                Err(e) => return Err(e),
            }
        }
        wait = wait.saturating_mul(2);
    }
    Ok(None)
}

fn u16_at(bytes: &[u8], at: usize) -> Option<u16> {
    bytes.get(at..at.checked_add(2)?)?.try_into().ok().map(u16::from_be_bytes)
}

fn u32_at(bytes: &[u8], at: usize) -> Option<u32> {
    bytes.get(at..at.checked_add(4)?)?.try_into().ok().map(u32::from_be_bytes)
}
