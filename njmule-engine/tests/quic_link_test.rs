// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's QuicLinkTest, under its names: two clients whose QUIC datagrams cross a wire of frames.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use njmule_engine::quic::quic_link::{Peers, QuicLink};
use njmule_engine::quic::quic_transport::QuicTransport;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

const NEAR_HASH: [u8; 16] = [0xAA; 16];
const FAR_HASH: [u8; 16] = [0xBB; 16];
const OTHER_HASH: [u8; 16] = [0xCC; 16];
const WAIT: Duration = Duration::from_secs(30);

#[tokio::test]
async fn should_carry_bytes_both_ways_once_both_sides_proved_who_they_are() {
    let mut test = Wire::new();
    test.given_two_clients();
    test.given_the_far_one_expects(NEAR_HASH);

    test.when_the_near_one_connects(FAR_HASH);

    test.then_both_sides_hold_the_connection().await;
    test.then_bytes_cross_from_the_near_side_to_the_far_side("file request").await;
    test.then_bytes_cross_from_the_far_side_to_the_near_side("file answer").await;
}

#[tokio::test]
async fn should_hand_nothing_on_when_the_client_proves_another_hash_than_expected() {
    let mut test = Wire::new();
    test.given_two_clients();
    test.given_the_far_one_expects(OTHER_HASH);

    test.when_the_near_one_connects(FAR_HASH);

    test.then_the_far_one_takes_nothing_within_seconds(3).await;
    test.then_the_near_one_ends_without_a_connection().await;
}

#[tokio::test]
async fn should_fail_when_the_other_side_proves_another_hash_than_expected() {
    let mut test = Wire::new();
    test.given_two_clients();

    test.when_the_near_one_connects(OTHER_HASH);

    test.then_the_near_one_ends_without_a_connection().await;
    test.then_the_far_one_takes_nothing_within_seconds(1).await;
}

#[tokio::test]
async fn should_take_no_connection_from_a_host_it_does_not_wait_for() {
    let mut test = Wire::new();
    test.given_two_clients();
    test.given_the_far_one_takes_no_connections();

    test.when_the_near_one_connects(FAR_HASH);

    test.then_the_far_one_takes_nothing_within_seconds(3).await;
    test.then_the_connection_is_not_open();
}

#[tokio::test]
async fn should_time_out_a_read_that_waits_too_long_and_stay_open() {
    let mut test = Wire::new();
    test.given_two_clients();
    test.given_a_connection().await;
    test.given_the_far_side_waits_at_most(Duration::from_millis(200));

    test.when_the_far_side_reads().await;

    test.then_the_read_timed_out();
    test.then_bytes_cross_from_the_near_side_to_the_far_side("later").await;
}

// The books NAT traversal keeps of the connections, which the Java test leaves to NatTraversalTest.
#[tokio::test]
async fn should_list_a_connection_on_both_sides_while_it_is_open() {
    let mut test = Wire::new();
    test.given_two_clients();

    test.when_the_near_one_connects(FAR_HASH);

    test.then_both_sides_hold_the_connection().await;
    test.then_the_near_one_connects_to_the_far_one(true);
    test.then_each_one_lists_the_other();
}

#[tokio::test]
async fn should_forget_a_connection_once_dropped() {
    let mut test = Wire::new();
    test.given_two_clients();
    test.given_a_connection().await;

    test.when_the_near_side_is_dropped();

    test.then_the_near_one_connects_to_the_far_one(false);
    test.then_the_near_one_lists_nothing();
}

#[tokio::test]
async fn should_refuse_a_second_connection_to_a_host_being_connected() {
    let mut test = Wire::new();
    test.given_two_clients();
    test.given_the_far_one_takes_no_connections();
    test.given_the_near_one_connects(FAR_HASH);

    test.when_the_near_one_connects_again().await;

    test.then_the_second_connection_failed();
}

/// Two clients on a wire that carries their frames in order, the far one taking connections.
struct Wire {
    links: Arc<Mutex<HashMap<SocketAddr, QuicLink>>>,
    frames: mpsc::UnboundedSender<(SocketAddr, SocketAddr, Vec<u8>)>,
    far: Arc<Far>,
    accepted: mpsc::UnboundedReceiver<QuicTransport>,
    connecting: Option<JoinHandle<io::Result<QuicTransport>>>,
    near_side: Option<QuicTransport>,
    far_side: Option<QuicTransport>,
    far_waits: Duration,
    timed_out: bool,
    again: Option<io::Result<QuicTransport>>,
}

impl Wire {
    fn new() -> Self {
        let links: Arc<Mutex<HashMap<SocketAddr, QuicLink>>> = Arc::default();
        let (frames, mut carried) = mpsc::unbounded_channel::<(SocketAddr, SocketAddr, Vec<u8>)>();
        let carrying = links.clone();
        tokio::spawn(async move {
            while let Some((from, to, datagram)) = carried.recv().await {
                let link = carrying.lock().unwrap().get(&to).cloned();
                if let Some(link) = link {
                    link.received(from, &datagram);
                }
            }
        });
        let (taken, accepted) = mpsc::unbounded_channel();
        let far = Arc::new(Far { takes_connections: AtomicBool::new(true), expects: Mutex::new(None), taken });
        Self {
            links,
            frames,
            far,
            accepted,
            connecting: None,
            near_side: None,
            far_side: None,
            far_waits: WAIT,
            timed_out: false,
            again: None,
        }
    }

    fn link_at(&self, at: SocketAddr) -> QuicLink {
        self.links.lock().unwrap()[&at].clone()
    }

    fn given_two_clients(&self) {
        self.link(near(), NEAR_HASH, Arc::new(Near));
        self.link(far(), FAR_HASH, self.far.clone());
    }

    fn given_the_far_one_expects(&self, hash: [u8; 16]) {
        *self.far.expects.lock().unwrap() = Some(hash);
    }

    fn given_the_far_one_takes_no_connections(&self) {
        self.far.takes_connections.store(false, Ordering::SeqCst);
    }

    async fn given_a_connection(&mut self) {
        self.when_the_near_one_connects(FAR_HASH);
        self.then_both_sides_hold_the_connection().await;
    }

    fn given_the_far_side_waits_at_most(&mut self, wait: Duration) {
        self.far_waits = wait;
    }

    fn given_the_near_one_connects(&mut self, expected: [u8; 16]) {
        self.when_the_near_one_connects(expected);
    }

    fn when_the_near_one_connects(&mut self, expected: [u8; 16]) {
        let link = self.link_at(near());
        self.connecting = Some(tokio::spawn(async move { link.connect(far(), Some(expected)).await }));
    }

    async fn when_the_near_one_connects_again(&mut self) {
        let link = self.link_at(near());
        // The first attempt takes the host once its task runs.
        while !link.connecting(far()) {
            tokio::task::yield_now().await;
        }
        self.again = Some(tokio::time::timeout(Duration::from_secs(1), link.connect(far(), None)).await.unwrap());
    }

    fn when_the_near_side_is_dropped(&mut self) {
        self.near_side = None;
    }

    async fn when_the_far_side_reads(&mut self) {
        let far_side = self.far_side.as_mut().unwrap();
        let mut one = [0; 1];
        self.timed_out = tokio::time::timeout(self.far_waits, far_side.read(&mut one)).await.is_err();
    }

    async fn then_both_sides_hold_the_connection(&mut self) {
        let connecting = self.connecting.take().unwrap();
        self.near_side = Some(tokio::time::timeout(WAIT, connecting).await.unwrap().unwrap().unwrap());
        self.far_side = Some(tokio::time::timeout(WAIT, self.accepted.recv()).await.unwrap().unwrap());
        assert_eq!(self.near_side.as_ref().unwrap().remote_address(), far());
        assert_eq!(self.far_side.as_ref().unwrap().remote_address(), near());
    }

    async fn then_bytes_cross_from_the_near_side_to_the_far_side(&mut self, text: &str) {
        cross(self.near_side.as_mut().unwrap(), self.far_side.as_mut().unwrap(), text).await;
    }

    async fn then_bytes_cross_from_the_far_side_to_the_near_side(&mut self, text: &str) {
        cross(self.far_side.as_mut().unwrap(), self.near_side.as_mut().unwrap(), text).await;
    }

    async fn then_the_far_one_takes_nothing_within_seconds(&mut self, seconds: u64) {
        let taken = tokio::time::timeout(Duration::from_secs(seconds), self.accepted.recv()).await;
        assert!(taken.is_err(), "the far one took a connection");
    }

    // Either the connection fails, or it closes once the other side refused the proof.
    async fn then_the_near_one_ends_without_a_connection(&mut self) {
        let connecting = self.connecting.take().unwrap();
        let Ok(Ok(Ok(mut opened))) = tokio::time::timeout(WAIT, connecting).await else {
            return;
        };
        let mut one = [0; 1];
        let read = tokio::time::timeout(Duration::from_secs(20), opened.read(&mut one)).await;
        assert!(matches!(read, Ok(Ok(0)) | Ok(Err(_))), "the connection ended: {read:?}");
    }

    fn then_the_connection_is_not_open(&self) {
        assert!(!self.connecting.as_ref().unwrap().is_finished());
    }

    fn then_the_read_timed_out(&self) {
        assert!(self.timed_out);
    }

    fn then_the_near_one_connects_to_the_far_one(&self, connecting: bool) {
        assert_eq!(self.link_at(near()).connecting(far()), connecting);
    }

    fn then_each_one_lists_the_other(&self) {
        assert_eq!(self.link_at(near()).connections(), vec![far()]);
        assert_eq!(self.link_at(far()).connections(), vec![near()]);
    }

    fn then_the_near_one_lists_nothing(&self) {
        assert!(self.link_at(near()).connections().is_empty());
    }

    fn then_the_second_connection_failed(&self) {
        assert!(matches!(&self.again, Some(Err(e)) if e.to_string().contains("already connecting")));
    }

    fn link(&self, at: SocketAddr, hash: [u8; 16], peers: Arc<dyn Peers>) {
        let frames = self.frames.clone();
        let sender = Arc::new(move |to: SocketAddr, datagram: &[u8]| {
            let _ = frames.send((at, to, datagram.to_vec()));
        });
        let link = QuicLink::new(sender, Arc::new(move || hash), peers).unwrap();
        self.links.lock().unwrap().insert(at, link);
    }
}

impl Drop for Wire {
    fn drop(&mut self) {
        for link in self.links.lock().unwrap().values() {
            link.close();
        }
    }
}

async fn cross(from: &mut QuicTransport, to: &mut QuicTransport, text: &str) {
    from.write_all(text.as_bytes()).await.unwrap();
    from.flush().await.unwrap();
    let mut read = vec![0; text.len()];
    tokio::time::timeout(Duration::from_secs(10), to.read_exact(&mut read)).await.unwrap().unwrap();
    assert_eq!(String::from_utf8(read).unwrap(), text);
}

// Opens connections only.
struct Near;

impl Peers for Near {
    fn may_connect(&self, _from: SocketAddr) -> bool {
        false
    }

    fn expected(&self, _from: SocketAddr) -> Option<[u8; 16]> {
        None
    }

    fn accepted(&self, connection: QuicTransport) {
        drop(connection);
    }
}

struct Far {
    takes_connections: AtomicBool,
    expects: Mutex<Option<[u8; 16]>>,
    taken: mpsc::UnboundedSender<QuicTransport>,
}

impl Peers for Far {
    fn may_connect(&self, _from: SocketAddr) -> bool {
        self.takes_connections.load(Ordering::SeqCst)
    }

    fn expected(&self, _from: SocketAddr) -> Option<[u8; 16]> {
        *self.expects.lock().unwrap()
    }

    fn accepted(&self, connection: QuicTransport) {
        let _ = self.taken.send(connection);
    }
}

fn near() -> SocketAddr {
    "198.51.100.10:4672".parse().unwrap()
}

fn far() -> SocketAddr {
    "203.0.113.20:4682".parse().unwrap()
}
