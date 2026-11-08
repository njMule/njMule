// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A router on the loopback that answers NAT-PMP (RFC 6886) or PCP only (RFC 6887), as the Java engine's FakeNatPmp. It
//! notes each mapping asked for, as "map TCP 4662 3600" or "delete UDP 4672", with the nonce after it in PCP.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::atomic::{AtomicU8, AtomicU16, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};

use tokio::net::UdpSocket;
use tokio::task::JoinHandle;

const OUTSIDE: [u8; 4] = [203, 0, 113, 5];

/// What it speaks.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Speaks {
    NatPmp,
    Pcp,
}

#[derive(Default)]
struct State {
    requests: Mutex<Vec<String>>,
    clients: Mutex<Vec<String>>,
    grants: AtomicU32,
    outside_offset: AtomicU16,
    result: AtomicU8,
}

pub struct FakeNatPmp {
    address: SocketAddr,
    state: Arc<State>,
    serving: JoinHandle<()>,
}

impl FakeNatPmp {
    /// Listening at `address`, such as the IPv6 loopback.
    pub async fn at(speaks: Speaks, address: IpAddr) -> Self {
        let socket = UdpSocket::bind(SocketAddr::new(address, 0)).await.unwrap();
        let address = socket.local_addr().unwrap();
        let state = Arc::new(State { grants: AtomicU32::new(7200), ..State::default() });
        let serving = tokio::spawn(serve(socket, speaks, Arc::clone(&state)));
        Self { address, state, serving }
    }

    pub async fn new(speaks: Speaks) -> Self {
        Self::at(speaks, IpAddr::from([127, 0, 0, 1])).await
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    /// The client address each PCP mapping request gave.
    pub fn clients(&self) -> Vec<String> {
        self.state.clients.lock().unwrap().clone()
    }

    pub fn requests(&self) -> Vec<String> {
        self.state.requests.lock().unwrap().clone()
    }

    /// The longest lease it grants.
    pub fn grants_at_most(&self, seconds: u32) {
        self.state.grants.store(seconds, Ordering::SeqCst);
    }

    /// Maps to the port that many above the one asked for.
    pub fn maps_outside_to(&self, offset: u16) {
        self.state.outside_offset.store(offset, Ordering::SeqCst);
    }

    /// Answers mappings with `code`, 0 being success.
    pub fn answers(&self, code: u8) {
        self.state.result.store(code, Ordering::SeqCst);
    }
}

impl Drop for FakeNatPmp {
    fn drop(&mut self) {
        self.serving.abort();
    }
}

async fn serve(socket: UdpSocket, speaks: Speaks, state: Arc<State>) {
    let mut buffer = [0u8; 1100];
    while let Ok((count, from)) = socket.recv_from(&mut buffer).await {
        let request = &buffer[..count];
        let answer = if speaks == Speaks::NatPmp { nat_pmp(request, &state) } else { pcp(request, &state) };
        if let Some(answer) = answer {
            let _ = socket.send_to(&answer, from).await;
        }
    }
}

fn nat_pmp(request: &[u8], state: &State) -> Option<Vec<u8>> {
    if request.len() < 2 {
        return None;
    }
    let opcode = request[1];
    if request[0] != 0 {
        // A newer version than ours: NAT-PMP's "unsupported version".
        return Some(vec![0, 0x80 | opcode, 0, 1, 0, 0, 0, 0]);
    }
    if opcode == 0 {
        let mut answer = vec![0, 0x80, 0, 0, 0, 0, 0, 1];
        answer.extend_from_slice(&OUTSIDE);
        return Some(answer);
    }
    let port = u16::from_be_bytes([request[4], request[5]]);
    let lease = u32::from_be_bytes([request[8], request[9], request[10], request[11]]);
    let kind = if opcode == 2 { "TCP" } else { "UDP" };
    let noted = if lease == 0 { format!("delete {kind} {port}") } else { format!("map {kind} {port} {lease}") };
    state.requests.lock().unwrap().push(noted);
    let outside = if lease == 0 { 0 } else { port + state.outside_offset.load(Ordering::SeqCst) };
    let mut answer = vec![0, 0x80 | opcode, 0, state.result.load(Ordering::SeqCst), 0, 0, 0, 1];
    answer.extend_from_slice(&port.to_be_bytes());
    answer.extend_from_slice(&outside.to_be_bytes());
    answer.extend_from_slice(&lease.min(state.grants.load(Ordering::SeqCst)).to_be_bytes());
    Some(answer)
}

fn pcp(request: &[u8], state: &State) -> Option<Vec<u8>> {
    if request.len() < 2 {
        return None;
    }
    if request[0] != 2 {
        // An older version than ours: PCP's "unsupported version", in PCP.
        let mut answer = vec![2, 0x80 | (request[1] & 0x7F), 0, 1];
        answer.resize(24, 0);
        return Some(answer);
    }
    if request.len() < 60 {
        return None;
    }
    let lease = u32::from_be_bytes([request[4], request[5], request[6], request[7]]);
    let nonce = &request[24..36];
    let client = Ipv6Addr::from(<[u8; 16]>::try_from(&request[8..24]).unwrap());
    let client = client.to_ipv4_mapped().map_or_else(|| client.to_string(), |v4| v4.to_string());
    state.clients.lock().unwrap().push(client);
    let protocol = request[36];
    let port = u16::from_be_bytes([request[40], request[41]]);
    let kind = if protocol == 6 { "TCP" } else { "UDP" };
    let nonce_text = hex::encode(nonce);
    let noted = if lease == 0 {
        format!("delete {kind} {port} {nonce_text}")
    } else {
        format!("map {kind} {port} {lease} {nonce_text}")
    };
    state.requests.lock().unwrap().push(noted);
    let outside = if lease == 0 { 0 } else { port + state.outside_offset.load(Ordering::SeqCst) };
    let mut answer = vec![2, 0x81, 0, state.result.load(Ordering::SeqCst)];
    answer.extend_from_slice(&lease.min(state.grants.load(Ordering::SeqCst)).to_be_bytes());
    answer.extend_from_slice(&1u32.to_be_bytes());
    answer.extend_from_slice(&[0u8; 12]);
    answer.extend_from_slice(nonce);
    answer.extend_from_slice(&[protocol, 0, 0, 0]);
    answer.extend_from_slice(&port.to_be_bytes());
    answer.extend_from_slice(&outside.to_be_bytes());
    answer.extend_from_slice(&[0u8; 10]);
    answer.extend_from_slice(&[0xFF, 0xFF]);
    answer.extend_from_slice(&OUTSIDE);
    Some(answer)
}
