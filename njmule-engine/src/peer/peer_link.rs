// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A connection with another client, shared by our download from it and our upload to it. Two tasks move its frames:
//! one reads and hands each packet to the engine, the other writes what the engine sends. Until the greeting is over a
//! client may send only small frames, so an unknown connection cannot make us allocate much.

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{mpsc, watch};

use crate::net::tcp_connection::{FrameReader, FrameWriter};
use crate::protocol::packet::opcodes::client;
use crate::protocol::packet::{Packet, frame_limits, packing, protocol};

/// A connection silent this long closes, unless a side asks for longer.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(40);

pub type LinkId = u64;

/// What a connection tells the engine.
#[derive(Debug)]
pub enum LinkEvent {
    Packet(LinkId, Packet),
    /// The connection is closed; `reason` is empty after a clean close.
    Closed(LinkId, Option<String>),
}

#[derive(Debug)]
enum Command {
    Send(Packet),
    Close,
}

/// The engine's end of a connection.
#[derive(Debug, Clone)]
pub struct LinkHandle {
    id: LinkId,
    remote: SocketAddr,
    commands: mpsc::UnboundedSender<Command>,
    timeout: Arc<AtomicU64>,
}

impl LinkHandle {
    pub fn id(&self) -> LinkId {
        self.id
    }

    pub fn remote(&self) -> SocketAddr {
        self.remote
    }

    /// Sends `packet` after those sent before; false once the connection is closed.
    pub fn send(&self, packet: Packet) -> bool {
        self.commands.send(Command::Send(packet)).is_ok()
    }

    pub fn close(&self) {
        let _ = self.commands.send(Command::Close);
    }

    /// Fails reads that wait longer than `timeout`, from the next read on.
    pub fn set_timeout(&self, timeout: Duration) {
        self.timeout.store(u64::try_from(timeout.as_millis()).unwrap_or(u64::MAX), Ordering::Relaxed);
    }
}

/// Starts moving the frames of a connection; its packets and its end go to `events`.
pub fn start<R, W>(
    id: LinkId,
    remote: SocketAddr,
    reader: FrameReader<R>,
    writer: FrameWriter<W>,
    events: mpsc::UnboundedSender<LinkEvent>,
) -> LinkHandle
where
    R: AsyncRead + Unpin + Send + 'static,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let (commands, queue) = mpsc::unbounded_channel();
    let timeout = Arc::new(AtomicU64::new(u64::try_from(DEFAULT_TIMEOUT.as_millis()).unwrap_or(u64::MAX)));
    let (closing, closed) = watch::channel(false);
    tokio::spawn(write(writer, queue, closing));
    tokio::spawn(read(id, reader, events, timeout.clone(), closed));
    LinkHandle { id, remote, commands, timeout }
}

async fn write<W: AsyncWrite + Unpin>(
    mut writer: FrameWriter<W>,
    mut queue: mpsc::UnboundedReceiver<Command>,
    closing: watch::Sender<bool>,
) {
    while let Some(command) = queue.recv().await {
        match command {
            Command::Send(packet) => {
                if let Err(e) = writer.write_packet(&packet).await {
                    tracing::debug!("Cannot send to a client: {e}");
                    break;
                }
            }
            Command::Close => break,
        }
    }
    let _ = writer.flush().await;
    let _ = writer.shutdown().await;
    closing.send_replace(true);
}

async fn read<R: AsyncRead + Unpin>(
    id: LinkId,
    mut reader: FrameReader<R>,
    events: mpsc::UnboundedSender<LinkEvent>,
    timeout: Arc<AtomicU64>,
    mut closed: watch::Receiver<bool>,
) {
    let mut greeted = false;
    let reason = loop {
        let wait = Duration::from_millis(timeout.load(Ordering::Relaxed));
        let read = tokio::select! {
            _ = closed.wait_for(|closed| *closed) => break None,
            read = tokio::time::timeout(wait, async {
                if greeted {
                    reader.read_packet(&frame_limits::client).await
                } else {
                    reader.read_packet(&frame_limits::client_handshake).await
                }
            }) => read,
        };
        let packet = match read {
            Err(_) => break Some(format!("silent for {} s", wait.as_secs())),
            Ok(Err(e)) => break Some(e.to_string()),
            Ok(Ok(None)) => break None,
            Ok(Ok(Some(packet))) => packet,
        };
        // Clients pack extended packets: those inflate with the client cap.
        let packet = match packing::unpack(packet, packing::MAX_CLIENT_UNPACKED) {
            Ok(packet) => packet,
            Err(e) => break Some(e.to_string()),
        };
        greeted |= packet.protocol() == protocol::EDONKEY
            && (packet.opcode() == client::HELLO || packet.opcode() == client::HELLOANSWER);
        if events.send(LinkEvent::Packet(id, packet)).is_err() {
            return;
        }
    };
    let _ = events.send(LinkEvent::Closed(id, reason));
}
