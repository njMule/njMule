// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A source a test scripts, as the Java engine's download tests have it: it listens on the loopback address, answers a
//! downloader's hello with its own and then says what the test makes it say, reading what the downloader asks.

#![allow(
    dead_code,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::net::{Ipv4Addr, SocketAddr};
use std::time::Duration;

use njmule_engine::api::ed2k_link::{FileLink, Source};
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, frame_limits, packing, protocol};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Mutex, mpsc};

pub const WAIT: Duration = Duration::from_secs(20);

// The secure identification, which the scripts leave out.
const IDENTIFICATION: [u8; 3] = [extended::SECIDENTSTATE, extended::PUBLICKEY, extended::SIGNATURE];

pub struct ScriptedSource {
    address: SocketAddr,
    user_hash: [u8; 16],
    misc1: u32,
    misc2: u32,
    udp_port: u16,
    accepted: Mutex<mpsc::UnboundedReceiver<TcpStream>>,
    // Taken by a look that leaves it for the next accept.
    waiting: Mutex<Option<TcpStream>>,
    accepting: tokio::task::JoinHandle<()>,
}

impl ScriptedSource {
    /// A source with `user_hash` announcing the options `misc1` and `misc2` in its hello.
    pub async fn new(user_hash: [u8; 16], misc1: u32, misc2: u32) -> Self {
        Self::with_udp_port(user_hash, misc1, misc2, 0).await
    }

    /// As above, announcing `udp_port` as its UDP port too.
    pub async fn with_udp_port(user_hash: [u8; 16], misc1: u32, misc2: u32, udp_port: u16) -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = listener.local_addr().unwrap();
        let (accepted_in, accepted) = mpsc::unbounded_channel();
        let accepting = tokio::spawn(async move {
            while let Ok((stream, _)) = listener.accept().await {
                if accepted_in.send(stream).is_err() {
                    return;
                }
            }
        });
        Self {
            address,
            user_hash,
            misc1,
            misc2,
            udp_port,
            accepted: Mutex::new(accepted),
            waiting: Mutex::new(None),
            accepting,
        }
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    pub fn port(&self) -> u16 {
        self.address.port()
    }

    /// A link of `size` bytes named `name`, with this source.
    pub fn link(&self, name: &str, size: u64, hash: [u8; 16]) -> FileLink {
        let source = Source { host: self.address.ip().to_string(), port: self.port() };
        FileLink::new(name.to_owned(), size, hash, None, Vec::new(), vec![source], Vec::new()).unwrap()
    }

    pub fn hello(&self) -> Hello {
        self.hello_as(self.user_hash, 0, 0)
    }

    /// Its hello with another user hash, client id and server, as a firewalled client tells them.
    pub fn hello_as(&self, user_hash: [u8; 16], client_id: u32, server_ip: u32) -> Hello {
        let ours = Ours {
            nick: "scripted",
            udp_port: self.udp_port,
            kad_port: self.udp_port,
            misc_options1: self.misc1,
            misc_options2: self.misc2,
        };
        Hello::of(user_hash, client_id, self.port(), &ours, server_ip, 4661)
    }

    /// Waits for the next downloader and answers its hello.
    pub async fn accept(&self) -> Link {
        let mut link = Link::over(self.next_stream().await);
        let hello = link.next().await;
        assert_eq!((hello.protocol(), hello.opcode()), (protocol::EDONKEY, client::HELLO), "a hello first");
        link.send(&self.hello().encode(true).unwrap()).await;
        link
    }

    /// Waits for the next downloader and leaves its connection unanswered.
    pub async fn accept_silently(&self) -> TcpStream {
        self.next_stream().await
    }

    /// Waits for the next downloader and closes its connection without a word.
    pub async fn refuse_next(&self) {
        drop(self.next_stream().await);
    }

    /// Whether a downloader connects within `wait`; its connection is left for the next accept.
    pub async fn called_within(&self, wait: Duration) -> bool {
        let mut waiting = self.waiting.lock().await;
        if waiting.is_none() {
            *waiting = tokio::time::timeout(wait, self.accepted.lock().await.recv()).await.ok().flatten();
        }
        waiting.is_some()
    }

    /// Whether a downloader connects within `wait`; its connection is closed without a word.
    pub async fn is_connected_to_within(&self, wait: Duration) -> bool {
        if let Some(stream) = self.waiting.lock().await.take() {
            drop(stream);
            return true;
        }
        tokio::time::timeout(wait, self.accepted.lock().await.recv()).await.ok().flatten().is_some()
    }

    async fn next_stream(&self) -> TcpStream {
        if let Some(stream) = self.waiting.lock().await.take() {
            return stream;
        }
        tokio::time::timeout(WAIT, self.accepted.lock().await.recv())
            .await
            .expect("a downloader connects")
            .expect("the source listens")
    }

    /// Connects to the downloader listening at `port` with `hello`, as a source calling back does.
    pub async fn call(port: u16, hello: &Hello) -> Link {
        let stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
        let mut link = Link::over(stream);
        link.send(&hello.encode(false).unwrap()).await;
        assert_eq!(link.next().await.opcode(), client::HELLOANSWER);
        link
    }
}

impl Drop for ScriptedSource {
    fn drop(&mut self) {
        self.accepting.abort();
    }
}

/// One downloader's connection.
pub struct Link {
    input: OwnedReadHalf,
    output: OwnedWriteHalf,
}

impl Link {
    pub fn over(stream: TcpStream) -> Self {
        let (input, output) = stream.into_split();
        Self { input, output }
    }

    pub async fn send(&mut self, packet: &Packet) {
        write_packet(&mut self.output, packet).await.unwrap();
    }

    /// The next packet, unpacked; the secure identification is left out.
    pub async fn next(&mut self) -> Packet {
        loop {
            let packet = tokio::time::timeout(WAIT, read_packet(&mut self.input, &frame_limits::client))
                .await
                .expect("a packet in time")
                .unwrap()
                .expect("the connection open");
            let packet = packing::unpack(packet, 1 << 22).unwrap();
            if packet.protocol() != protocol::EMULE || !IDENTIFICATION.contains(&packet.opcode()) {
                return packet;
            }
        }
    }

    /// The packets read up to and with the first of `opcode` in `protocol`.
    pub async fn until(&mut self, protocol: u8, opcode: u8) -> Vec<Packet> {
        let mut read = Vec::new();
        loop {
            let packet = self.next().await;
            let found = packet.protocol() == protocol && packet.opcode() == opcode;
            read.push(packet);
            if found {
                return read;
            }
        }
    }

    /// Whether the downloader closes the connection within `wait`, whatever it sends before.
    pub async fn closes_within(&mut self, wait: Duration) -> bool {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            match tokio::time::timeout_at(deadline, read_packet(&mut self.input, &frame_limits::client)).await {
                Ok(Ok(Some(_))) => {}
                Ok(Ok(None) | Err(_)) => return true,
                Err(_) => return false,
            }
        }
    }
}
