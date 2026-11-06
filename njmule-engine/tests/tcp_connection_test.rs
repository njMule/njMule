// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TcpConnectionTest, under its names: a framed connection with its queues, plain or obfuscated, to
//! servers, clients and our listener.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::arithmetic_side_effects)]

use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connection::{FrameReader, FrameWriter, Handler, TcpConnection, read_packet, write_packet};
use njmule_engine::net::tcp_connector;
use njmule_engine::net::tcp_listener::TcpListener;
use njmule_engine::protocol::crypt::stream_obfuscation;
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use njmule_engine::security::connection_guard::{ConnectionGuard, Limits, Permit};
use njmule_engine::security::ip_filter::IpFilter;
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::io::AsyncReadExt;
use tokio::net::TcpStream;
use tokio::sync::{mpsc, oneshot};

const TIMEOUT: Duration = Duration::from_secs(5);
const OWN_HASH: [u8; 16] =
    [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
const DATA: u8 = 0x46;
const CONTROL: u8 = 0x58;
// About 10 MB: more than a loopback socket buffers for a peer that does not read.
const DATA_FRAMES: usize = 1_000;

fn random() -> StdRng {
    StdRng::from_rng(&mut rand::rng())
}

/// What a connection told: its packets, and its close once.
struct Recorder {
    packets: mpsc::UnboundedSender<Packet>,
    closed: Mutex<Option<oneshot::Sender<Option<io::Error>>>>,
}

struct Told {
    packets: mpsc::UnboundedReceiver<Packet>,
    closed: oneshot::Receiver<Option<io::Error>>,
}

fn recorder() -> (Recorder, Told) {
    let (packets_in, packets) = mpsc::unbounded_channel();
    let (closed_in, closed) = oneshot::channel();
    (Recorder { packets: packets_in, closed: Mutex::new(Some(closed_in)) }, Told { packets, closed })
}

impl Handler for Recorder {
    fn on_packet(&self, _: &TcpConnection, packet: Packet) -> Result<(), String> {
        let _ = self.packets.send(packet);
        Ok(())
    }

    fn on_closed(&self, _: &TcpConnection, cause: Option<io::Error>) {
        if let Some(closed) = self.closed.lock().unwrap().take() {
            let _ = closed.send(cause);
        }
    }
}

impl Told {
    async fn next(&mut self) -> Packet {
        tokio::time::timeout(TIMEOUT, self.packets.recv()).await.expect("a packet in time").expect("a packet")
    }

    async fn closed(self) -> Option<io::Error> {
        tokio::time::timeout(TIMEOUT, self.closed).await.expect("the close in time").expect("the close")
    }
}

/// Echoes every packet back, and lets its permit go with the connection.
struct Echoing(Mutex<Option<Permit>>);

impl Handler for Echoing {
    fn on_packet(&self, connection: &TcpConnection, packet: Packet) -> Result<(), String> {
        connection.send(packet);
        Ok(())
    }

    fn on_closed(&self, _: &TcpConnection, _: Option<io::Error>) {
        self.0.lock().unwrap().take();
    }
}

/// A handler that cannot take any packet.
struct Failing(mpsc::UnboundedSender<Option<io::Error>>);

impl Handler for Failing {
    fn on_packet(&self, _: &TcpConnection, _: Packet) -> Result<(), String> {
        Err("bug".to_owned())
    }

    fn on_closed(&self, _: &TcpConnection, cause: Option<io::Error>) {
        let _ = self.0.send(cause);
    }
}

fn guard() -> Arc<ConnectionGuard> {
    let started = Instant::now();
    Arc::new(ConnectionGuard::new(
        || Arc::new(IpFilter::empty()),
        |_| false,
        |_| false,
        || Limits::DEFAULT,
        move || started.elapsed(),
    ))
}

/// A listener whose connections echo every packet back.
async fn echo(allow_plain: bool) -> TcpListener {
    TcpListener::open(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)), guard(), move |stream, permit| {
        tokio::spawn(async move {
            if let Ok(connection) = tcp_connector::accept(stream, OWN_HASH, allow_plain, TIMEOUT, &mut random()).await {
                connection
                    .into_tcp_connection(frame_limits::client_handshake)
                    .start(Echoing(Mutex::new(Some(permit))), Box::new(Ok));
            }
        });
    })
    .await
    .unwrap()
}

async fn to_client(port: u16, obfuscate_for: Option<[u8; 16]>) -> TcpConnection {
    tcp_connector::connect_to_client(
        SocketAddr::from((Ipv4Addr::LOCALHOST, port)),
        obfuscate_for,
        TIMEOUT,
        &Routes::direct(),
        &mut random(),
    )
    .await
    .unwrap()
    .into_tcp_connection(frame_limits::client_handshake)
}

async fn started_to_client(port: u16, obfuscate_for: Option<[u8; 16]>) -> (TcpConnection, Told) {
    let connection = to_client(port, obfuscate_for).await;
    let (recorder, told) = recorder();
    connection.start(recorder, Box::new(Ok));
    (connection, told)
}

/// A minimal eD2K server: reads one frame, answers with an empty server message, then waits for the end.
async fn serve_once(obfuscated: bool) -> (u16, oneshot::Receiver<Packet>, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let (received_in, received) = oneshot::channel();
    let serving = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let (mut input, mut output) = stream.into_split();
        let (receive, send) = if obfuscated {
            let mut marker = [0u8; 1];
            input.read_exact(&mut marker).await.unwrap();
            let ciphers = stream_obfuscation::accept_as_server(&mut input, &mut output, &mut random()).await.unwrap();
            (Some(ciphers.receive), Some(ciphers.send))
        } else {
            (None, None)
        };
        let mut reader = FrameReader::new(input, receive);
        let mut writer = FrameWriter::new(output, send);
        let _ = received_in.send(reader.read_packet(&frame_limits::server).await.unwrap().unwrap());
        writer.write_packet(&Packet::of(protocol::EDONKEY, 0x38, vec![0, 0])).await.unwrap();
        let _ = reader.read_packet(&frame_limits::server).await;
    });
    (port, received, serving)
}

async fn talk_to_a_server(obfuscated: bool) {
    let (port, received, serving) = serve_once(obfuscated).await;
    let connection = tcp_connector::connect_to_server(
        SocketAddr::from((Ipv4Addr::LOCALHOST, port)),
        obfuscated,
        TIMEOUT,
        &Routes::direct(),
        &mut random(),
    )
    .await
    .unwrap()
    .into_tcp_connection(frame_limits::server);
    let (recorder, mut told) = recorder();
    connection.start(recorder, Box::new(Ok));
    let packet = Packet::of(protocol::EDONKEY, 0x01, vec![7, 7, 7]);

    let sent = connection.send(packet.clone());

    assert!(sent);
    assert_eq!(tokio::time::timeout(TIMEOUT, received).await.unwrap().unwrap(), packet);
    assert_eq!(told.next().await, Packet::of(protocol::EDONKEY, 0x38, vec![0, 0]));
    assert_eq!(connection.is_obfuscated(), obfuscated);
    connection.close();
    tokio::time::timeout(TIMEOUT, serving).await.unwrap().unwrap();
}

/// A connection to a peer that reads nothing yet, and the peer's end.
async fn to_a_peer_reading_nothing() -> (TcpConnection, Told, TcpStream) {
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let connection = to_client(port, None).await;
    let (peer, _) = listener.accept().await.unwrap();
    let (recorder, told) = recorder();
    connection.start(recorder, Box::new(Ok));
    (connection, told, peer)
}

fn small_frames_queued(connection: &TcpConnection, count: u8) {
    for i in 0..count {
        assert!(connection.send(Packet::of(protocol::EDONKEY, 0x01, vec![i])));
    }
}

#[tokio::test]
async fn should_talk_to_a_plain_server() {
    talk_to_a_server(false).await;
}

#[tokio::test]
async fn should_talk_to_an_obfuscated_server() {
    talk_to_a_server(true).await;
}

#[tokio::test]
async fn should_connect_plain_or_obfuscated_to_the_listener() {
    for obfuscated in [false, true] {
        let listener = echo(true).await;

        let (connection, _) = started_to_client(listener.port(), obfuscated.then_some(OWN_HASH)).await;

        assert_eq!(connection.is_obfuscated(), obfuscated);
        connection.close();
        listener.close().await;
    }
}

#[tokio::test]
async fn should_get_its_packets_back_from_an_echoing_listener() {
    for obfuscated in [false, true] {
        let listener = echo(true).await;
        let (connection, mut told) = started_to_client(listener.port(), obfuscated.then_some(OWN_HASH)).await;
        let packet = Packet::of(protocol::EDONKEY, 0x01, vec![1, 2, 3]);

        let sent = connection.send(packet.clone());

        assert!(sent);
        assert_eq!(told.next().await, packet, "obfuscated: {obfuscated}");
        connection.close();
        listener.close().await;
    }
}

#[tokio::test]
async fn should_close_a_connection_to_the_listener_without_error() {
    for obfuscated in [false, true] {
        let listener = echo(true).await;
        let (connection, mut told) = started_to_client(listener.port(), obfuscated.then_some(OWN_HASH)).await;
        let packet = Packet::of(protocol::EDONKEY, 0x01, vec![1, 2, 3]);
        assert!(connection.send(packet.clone()));
        assert_eq!(told.next().await, packet);

        connection.close();

        assert!(told.closed().await.is_none(), "obfuscated: {obfuscated}");
        listener.close().await;
    }
}

#[tokio::test]
async fn should_drop_plain_clients_of_a_listener_requiring_obfuscation() {
    let listener = echo(false).await;
    let (connection, told) = started_to_client(listener.port(), None).await;

    connection.send(Packet::of(protocol::EDONKEY, 0x01, vec![0]));

    told.closed().await;
    assert!(!connection.is_open());
    listener.close().await;
}

#[tokio::test]
async fn should_close_the_connection_on_a_frame_beyond_the_handshake_limit() {
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let serving = tokio::spawn(async move {
        let (mut stream, _) = listener.accept().await.unwrap();
        // Not a greeting: refused before the payload is allocated.
        let _ = write_packet(&mut stream, &Packet::of(protocol::EDONKEY, 0x46, vec![0; 16])).await;
        let mut rest = [0u8; 1];
        let _ = stream.read(&mut rest).await;
    });

    let (_connection, told) = started_to_client(port, None).await;

    let cause = told.closed().await.expect("a cause");
    assert_eq!(cause.kind(), io::ErrorKind::InvalidData, "{cause}");
    tokio::time::timeout(TIMEOUT, serving).await.unwrap().unwrap();
}

#[tokio::test]
async fn should_drop_a_peer_not_reading_once_a_few_megabytes_wait() {
    let (connection, told, peer) = to_a_peer_reading_nothing().await;
    let big = Packet::of(protocol::EDONKEY, 0x4B, vec![0; 1 << 20]);
    let mut queued = 0;

    while queued < 64 && connection.send(big.clone()) {
        queued += 1;
    }

    assert!(queued < 64, "frames queued: {queued}");
    assert!(told.closed().await.is_some());
    assert!(peer.peer_addr().is_ok(), "the peer's socket is still open");
}

#[tokio::test]
async fn should_still_send_frames_queued_before_a_close() {
    let (connection, told, mut peer) = to_a_peer_reading_nothing().await;
    small_frames_queued(&connection, 200);

    connection.close();

    assert!(told.closed().await.is_none());
    for i in 0..200u8 {
        let frame = read_packet(&mut peer, &frame_limits::client).await.unwrap().unwrap();
        assert_eq!(frame.payload(), [i]);
    }
    assert!(read_packet(&mut peer, &frame_limits::client).await.unwrap().is_none(), "closed after the last frame");
}

#[tokio::test]
async fn should_refuse_to_send_after_a_close() {
    let (connection, _told, _peer) = to_a_peer_reading_nothing().await;
    small_frames_queued(&connection, 200);
    connection.close();

    let sent = connection.send(Packet::empty(protocol::EDONKEY, 0x02));

    assert!(!sent);
}

async fn failing_connection() -> (TcpConnection, mpsc::UnboundedReceiver<Option<io::Error>>, TcpStream) {
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let connection = to_client(port, None).await;
    let (told_in, told) = mpsc::unbounded_channel();
    connection.start(Failing(told_in), Box::new(Ok));
    let (peer, _) = listener.accept().await.unwrap();
    (connection, told, peer)
}

async fn close_told_because(told: &mut mpsc::UnboundedReceiver<Option<io::Error>>, reason: &str) {
    let cause = tokio::time::timeout(TIMEOUT, told.recv()).await.unwrap().unwrap().expect("a cause");
    assert!(cause.to_string().contains(reason), "{cause}");
}

#[tokio::test]
async fn should_close_the_connection_when_a_handler_fails() {
    let (_connection, mut told, mut peer) = failing_connection().await;

    write_packet(&mut peer, &Packet::of(protocol::EDONKEY, 0x01, vec![0])).await.unwrap();

    close_told_because(&mut told, "handler failed").await;
}

#[tokio::test]
async fn should_tell_the_close_of_a_failed_handler_only_once() {
    let (connection, mut told, mut peer) = failing_connection().await;
    write_packet(&mut peer, &Packet::of(protocol::EDONKEY, 0x01, vec![0])).await.unwrap();
    close_told_because(&mut told, "handler failed").await;

    connection.close();

    assert!(tokio::time::timeout(Duration::from_millis(300), told.recv()).await.is_err(), "told once");
    assert!(!connection.is_open());
}

async fn closed_before_it_starts() -> (TcpConnection, tokio::net::TcpListener) {
    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let connection = to_client(listener.local_addr().unwrap().port(), None).await;
    connection.close();
    (connection, listener)
}

#[tokio::test]
async fn should_say_a_connection_closed_before_it_starts_is_closed_when_started() {
    let (connection, _listener) = closed_before_it_starts().await;
    let (recorder, told) = recorder();

    connection.start(recorder, Box::new(Ok));

    assert!(told.closed().await.is_none());
}

#[tokio::test]
async fn should_refuse_to_send_on_a_connection_closed_before_it_started() {
    let (connection, _listener) = closed_before_it_starts().await;
    let (recorder, told) = recorder();
    connection.start(recorder, Box::new(Ok));
    assert!(told.closed().await.is_none());

    let sent = connection.send(Packet::empty(protocol::EDONKEY, 0x01));

    assert!(!sent);
}

#[tokio::test]
async fn should_send_a_packet_while_file_data_waits_before_it() {
    let (connection, _told, mut peer) = to_a_peer_reading_nothing().await;
    for _ in 0..DATA_FRAMES {
        assert!(connection.send_data(Packet::of(protocol::EDONKEY, DATA, vec![0; 10_240])));
    }
    assert!(connection.queued_data() > 0);

    let sent = connection.send(Packet::of(protocol::EDONKEY, CONTROL, vec![0]));

    assert!(sent);
    let mut data_before = 0;
    while read_packet(&mut peer, &frame_limits::client).await.unwrap().unwrap().opcode() == DATA {
        data_before += 1;
    }
    assert!(data_before < DATA_FRAMES, "data before the packet: {data_before}");
    for _ in data_before..DATA_FRAMES {
        assert_eq!(read_packet(&mut peer, &frame_limits::client).await.unwrap().unwrap().opcode(), DATA);
    }
    assert_eq!(connection.queued_data(), 0);
    connection.close();
}
