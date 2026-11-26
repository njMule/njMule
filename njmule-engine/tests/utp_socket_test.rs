// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Two sockets on a wire that may lose, reorder and repeat packets.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::SocketAddr;
use std::ops::RangeInclusive;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use njmule_engine::utp::utp_connection::{MSS, UtpConnection};
use njmule_engine::utp::utp_socket::UtpSocket;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

const WAIT: Duration = Duration::from_secs(20);

#[tokio::test]
async fn should_connect_and_carry_bytes_both_ways() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(true);
    test.given_the_near_side_connected().await;

    test.when_each_side_sends_a_message().await;

    test.then_each_side_reads_the_other().await;
}

#[tokio::test]
async fn should_carry_megabytes_intact_over_a_lossy_reordering_wire() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(true);
    test.given_the_wire_loses_reorders_and_duplicates(0.05, 15);
    test.given_the_near_side_connected().await;

    test.when_the_near_side_sends_bytes(4 << 20).await;

    test.then_the_far_side_gets_them_all();
}

#[tokio::test]
async fn should_recover_quickly_from_the_loss_of_many_packets_at_the_end() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(true);
    // The connecting side's data starts at sequence number 2: the last 100 of 200 packets.
    test.given_the_wire_loses_once_the_data_packets(102..=201);
    test.given_the_near_side_connected().await;

    test.when_the_near_side_sends_bytes_read_within(200 * MSS as usize, Duration::from_secs(10)).await;

    test.then_the_far_side_gets_them_all();
}

#[tokio::test]
async fn should_end_the_stream_once_the_other_side_closes_after_its_data() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(true);
    test.given_the_near_side_connected().await;

    test.when_the_near_side_sends_and_closes(300_000).await;

    test.then_the_far_side_gets_them_all_then_the_end();
}

#[tokio::test]
async fn should_take_no_connection_from_a_host_nobody_expects() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(false);

    test.when_the_near_side_tries_to_connect();

    test.then_nothing_is_accepted_within(Duration::from_secs(2)).await;
}

#[tokio::test]
async fn should_fail_the_reads_of_the_other_side_once_one_side_aborts() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(true);
    test.given_the_near_side_connected().await;

    test.when_the_near_side_aborts();

    test.then_a_read_on_the_far_side_fails().await;
}

#[tokio::test]
async fn should_time_out_a_read_that_waits_too_long() {
    let mut test = Wire::new();
    test.given_two_sockets_on_a_wire(true);
    test.given_the_near_side_connected().await;

    test.when_the_far_side_reads_with_a_timeout_of(Duration::from_millis(300)).await;

    test.then_the_read_timed_out();
}

/// The wire: a delay of a few milliseconds, some loss and jitter, and now and then a packet twice.
struct Wire {
    sockets: Arc<Mutex<HashMap<SocketAddr, Arc<UtpSocket>>>>,
    conditions: Arc<Mutex<Conditions>>,
    accepted: mpsc::UnboundedReceiver<UtpConnection>,
    taken: mpsc::UnboundedSender<UtpConnection>,
    near_side: Option<UtpConnection>,
    far_side: Option<UtpConnection>,
    attempt: Option<JoinHandle<io::Result<UtpConnection>>>,
    sent: Vec<u8>,
    got: Vec<u8>,
    timed_out: bool,
}

struct Conditions {
    chance: StdRng,
    loss: f64,
    jitter_millis: u64,
    duplicates: bool,
    lost_once: Option<RangeInclusive<u16>>,
    lost: HashSet<u16>,
}

impl Wire {
    fn new() -> Self {
        let (taken, accepted) = mpsc::unbounded_channel();
        Self {
            sockets: Arc::default(),
            conditions: Arc::new(Mutex::new(Conditions {
                chance: StdRng::seed_from_u64(7),
                loss: 0.0,
                jitter_millis: 0,
                duplicates: false,
                lost_once: None,
                lost: HashSet::new(),
            })),
            accepted,
            taken,
            near_side: None,
            far_side: None,
            attempt: None,
            sent: Vec::new(),
            got: Vec::new(),
            timed_out: false,
        }
    }

    fn socket(&self, at: SocketAddr) -> Arc<UtpSocket> {
        self.sockets.lock().unwrap()[&at].clone()
    }

    fn given_two_sockets_on_a_wire(&self, far_expects_near: bool) {
        for at in [near(), far()] {
            let sockets = self.sockets.clone();
            let conditions = self.conditions.clone();
            let socket = UtpSocket::new(Arc::new(move |to, packet: &[u8]| {
                carry(&sockets, &conditions, at, to, packet);
            }));
            self.sockets.lock().unwrap().insert(at, Arc::new(socket));
        }
        let taken = self.taken.clone();
        self.socket(far()).accept(
            move |from| far_expects_near && from == near(),
            move |connection| {
                let _ = taken.send(connection);
            },
        );
    }

    fn given_the_wire_loses_reorders_and_duplicates(&self, loss: f64, jitter_millis: u64) {
        let mut conditions = self.conditions.lock().unwrap();
        conditions.loss = loss;
        conditions.jitter_millis = jitter_millis;
        conditions.duplicates = true;
    }

    fn given_the_wire_loses_once_the_data_packets(&self, seqs: RangeInclusive<u16>) {
        self.conditions.lock().unwrap().lost_once = Some(seqs);
    }

    async fn given_the_near_side_connected(&mut self) {
        let near = self.socket(near());
        self.near_side = Some(tokio::time::timeout(WAIT, near.connect(far())).await.unwrap().unwrap());
        self.far_side = Some(tokio::time::timeout(WAIT, self.accepted.recv()).await.unwrap().unwrap());
    }

    async fn when_each_side_sends_a_message(&mut self) {
        write(self.near_side.as_mut().unwrap(), b"hello far").await;
        write(self.far_side.as_mut().unwrap(), b"hello near").await;
    }

    async fn when_the_near_side_sends_bytes(&mut self, size: usize) {
        self.when_the_near_side_sends_bytes_read_within(size, Duration::from_secs(100)).await;
    }

    async fn when_the_near_side_sends_bytes_read_within(&mut self, size: usize, wait: Duration) {
        self.sent = random_bytes(3, size);
        let mut far_side = self.far_side.take().unwrap();
        let reading = tokio::spawn(async move {
            let mut got = vec![0; size];
            far_side.read_exact(&mut got).await.map(|_| got)
        });
        write(self.near_side.as_mut().unwrap(), &self.sent).await;
        self.got = tokio::time::timeout(wait, reading).await.expect("read in time").unwrap().unwrap();
    }

    async fn when_the_near_side_sends_and_closes(&mut self, size: usize) {
        self.sent = random_bytes(4, size);
        let mut far_side = self.far_side.take().unwrap();
        let reading = tokio::spawn(async move {
            let mut got = Vec::new();
            far_side.read_to_end(&mut got).await.map(|_| got)
        });
        let mut near_side = self.near_side.take().unwrap();
        write(&mut near_side, &self.sent).await;
        drop(near_side);
        self.got = tokio::time::timeout(Duration::from_secs(60), reading).await.unwrap().unwrap().unwrap();
    }

    fn when_the_near_side_tries_to_connect(&mut self) {
        let near = self.socket(near());
        self.attempt = Some(tokio::spawn(async move { near.connect(far()).await }));
    }

    fn when_the_near_side_aborts(&self) {
        self.near_side.as_ref().unwrap().abort();
    }

    async fn when_the_far_side_reads_with_a_timeout_of(&mut self, timeout: Duration) {
        let mut one = [0; 1];
        let read = tokio::time::timeout(timeout, self.far_side.as_mut().unwrap().read(&mut one)).await;
        self.timed_out = read.is_err();
    }

    async fn then_each_side_reads_the_other(&mut self) {
        assert_eq!(read_exactly(self.far_side.as_mut().unwrap(), 9).await, b"hello far");
        assert_eq!(read_exactly(self.near_side.as_mut().unwrap(), 10).await, b"hello near");
    }

    fn then_the_far_side_gets_them_all(&self) {
        assert_eq!(Sha256::digest(&self.got), Sha256::digest(&self.sent));
    }

    fn then_the_far_side_gets_them_all_then_the_end(&self) {
        assert_eq!(self.got.len(), self.sent.len());
        assert_eq!(Sha256::digest(&self.got), Sha256::digest(&self.sent));
    }

    async fn then_nothing_is_accepted_within(&mut self, wait: Duration) {
        assert!(tokio::time::timeout(wait, self.accepted.recv()).await.is_err(), "a connection was accepted");
        assert!(!self.attempt.as_ref().unwrap().is_finished());
    }

    async fn then_a_read_on_the_far_side_fails(&mut self) {
        let mut one = [0; 1];
        let read = tokio::time::timeout(Duration::from_secs(10), self.far_side.as_mut().unwrap().read(&mut one)).await;
        assert!(matches!(read, Ok(Err(_))), "the read failed: {read:?}");
    }

    fn then_the_read_timed_out(&self) {
        assert!(self.timed_out);
    }
}

impl Drop for Wire {
    fn drop(&mut self) {
        // The sockets hold the wire, and the wire them.
        let sockets: Vec<Arc<UtpSocket>> = self.sockets.lock().unwrap().drain().map(|(_, socket)| socket).collect();
        drop(sockets);
    }
}

// A delivery to a socket gone is a packet lost, as on a real wire.
fn carry(
    sockets: &Arc<Mutex<HashMap<SocketAddr, Arc<UtpSocket>>>>,
    conditions: &Mutex<Conditions>,
    from: SocketAddr,
    to: SocketAddr,
    packet: &[u8],
) {
    let delays: Vec<u64> = {
        let mut conditions = conditions.lock().unwrap();
        let loss = conditions.loss;
        if conditions.chance.random::<f64>() < loss {
            return;
        }
        if from == near()
            && packet[0] >> 4 == 0
            && conditions
                .lost_once
                .as_ref()
                .is_some_and(|seqs| seqs.contains(&u16::from_be_bytes([packet[16], packet[17]])))
            && conditions.lost.insert(u16::from_be_bytes([packet[16], packet[17]]))
        {
            return;
        }
        let copies = if conditions.duplicates && conditions.chance.random::<f64>() < 0.01 { 2 } else { 1 };
        (0..copies)
            .map(|_| {
                let jitter = conditions.jitter_millis;
                2 + if jitter > 0 { conditions.chance.random_range(0..jitter) } else { 0 }
            })
            .collect()
    };
    for delay in delays {
        let sockets = sockets.clone();
        let packet = packet.to_vec();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(delay)).await;
            let socket = sockets.lock().unwrap().get(&to).cloned();
            if let Some(socket) = socket {
                socket.received(from, &packet);
            }
        });
    }
}

async fn write(connection: &mut UtpConnection, bytes: &[u8]) {
    connection.write_all(bytes).await.unwrap();
    connection.flush().await.unwrap();
}

async fn read_exactly(connection: &mut UtpConnection, size: usize) -> Vec<u8> {
    let mut read = vec![0; size];
    tokio::time::timeout(WAIT, connection.read_exact(&mut read)).await.unwrap().unwrap();
    read
}

fn random_bytes(seed: u64, size: usize) -> Vec<u8> {
    let mut bytes = vec![0; size];
    StdRng::seed_from_u64(seed).fill(&mut bytes[..]);
    bytes
}

fn near() -> SocketAddr {
    "127.0.0.1:1001".parse().unwrap()
}

fn far() -> SocketAddr {
    "127.0.0.1:1002".parse().unwrap()
}
