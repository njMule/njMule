// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A connection with another client, shared by our download from it and our upload to it, as clients use one
//! connection for both: each packet goes to the side it belongs to. Until the greeting is over a client may send only
//! small frames, so an unknown connection cannot make us allocate much.

use std::fmt;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use tokio::sync::mpsc;

use crate::net::tcp_connection::{Handler, TcpConnection};
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, frame_limits, packing, protocol};

/// A connection silent this long closes, unless a side asks for longer.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(40);

pub type LinkId = u64;

/// Our download from the client, our upload to it, or the Kad buddy link with it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    Download,
    Upload,
    Buddy,
}

/// The side a packet from the client is for: what uploaders send goes to our download, what buddies send each other
/// to the buddy link, anything else to our upload, which answers hellos and chats too.
pub fn side_of(packet: &Packet) -> Side {
    let opcode = packet.opcode();
    match packet.protocol() {
        protocol::EMULE
            if matches!(
                opcode,
                extended::BUDDYPING | extended::BUDDYPONG | extended::CALLBACK | extended::REASKCALLBACKTCP
            ) =>
        {
            Side::Buddy
        }
        protocol::EDONKEY
            if matches!(
                opcode,
                client::SENDINGPART
                    | client::FILEREQANSNOFIL
                    | client::FILESTATUS
                    | client::HASHSETANSWER
                    | client::ACCEPTUPLOADREQ
                    | client::OUTOFPARTREQS
                    | client::REQFILENAMEANSWER
                    | client::QUEUERANK
                    | client::ASKSHAREDFILESANSWER
                    | client::ASKSHAREDDIRSANS
                    | client::ASKSHAREDFILESDIRANS
                    | client::ASKSHAREDDENIEDANS
            ) =>
        {
            Side::Download
        }
        protocol::EMULE
            if matches!(
                opcode,
                extended::COMPRESSEDPART
                    | extended::COMPRESSEDPART_I64
                    | extended::SENDINGPART_I64
                    | extended::QUEUERANKING
                    | extended::ANSWERSOURCES
                    | extended::ANSWERSOURCES2
                    | extended::PREVIEWANSWER
                    | extended::MULTIPACKETANSWER
                    | extended::MULTIPACKETANSWER_EXT2
                    | extended::PUBLICIP_ANSWER
                    | extended::AICHANSWER
                    | extended::AICHFILEHASHANS
                    | extended::FILEDESC
                    | extended::HASHSETANSWER2
            ) =>
        {
            Side::Download
        }
        _ => Side::Upload,
    }
}

/// What a connection tells the engine.
#[derive(Debug)]
pub enum LinkEvent {
    Packet(LinkId, Packet),
    /// The connection is closed; `reason` is empty after a clean close.
    Closed(LinkId, Option<String>),
}

/// The engine's end of a connection; clones are the same connection.
#[derive(Clone)]
pub struct LinkHandle {
    id: LinkId,
    connection: TcpConnection,
}

impl fmt::Debug for LinkHandle {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("LinkHandle").field("id", &self.id).field("remote", &self.remote()).finish()
    }
}

impl LinkHandle {
    pub fn id(&self) -> LinkId {
        self.id
    }

    pub fn remote(&self) -> SocketAddr {
        self.connection.remote_address()
    }

    /// Our address on the connection.
    pub fn local(&self) -> Option<IpAddr> {
        self.connection.local_address()
    }

    pub fn is_obfuscated(&self) -> bool {
        self.connection.is_obfuscated()
    }

    pub fn is_open(&self) -> bool {
        self.connection.is_open()
    }

    /// Sends `packet` after those sent before; false once the connection is closed.
    pub fn send(&self, packet: Packet) -> bool {
        self.connection.send(packet)
    }

    /// As [`Self::send`], for file data: it goes out after the other packets waiting.
    pub fn send_data(&self, packet: Packet) -> bool {
        self.connection.send_data(packet)
    }

    /// Bytes of file data waiting to be written.
    pub fn queued_data(&self) -> u64 {
        self.connection.queued_data()
    }

    /// Closes the connection once what is queued is sent.
    pub fn close(&self) {
        self.connection.close();
    }

    /// Fails reads that wait longer than `timeout`, from the next read on.
    pub fn set_timeout(&self, timeout: Duration) {
        self.connection.set_read_timeout(timeout);
    }
}

/// Starts a link over `connection`, which reads by the handshake's limits until a hello passes; its packets and its
/// end go to `events`.
pub fn start(id: LinkId, connection: TcpConnection, events: mpsc::UnboundedSender<LinkEvent>) -> LinkHandle {
    connection.set_read_timeout(DEFAULT_TIMEOUT);
    // Clients pack extended packets: those inflate with the client cap.
    connection.start(Forward { id, events }, Box::new(|packet| packing::unpack(packet, packing::MAX_CLIENT_UNPACKED)));
    LinkHandle { id, connection }
}

struct Forward {
    id: LinkId,
    events: mpsc::UnboundedSender<LinkEvent>,
}

impl Handler for Forward {
    fn on_packet(&self, connection: &TcpConnection, packet: Packet) -> Result<(), String> {
        // The next frame is read right after this returns: lift the handshake limit here.
        if packet.protocol() == protocol::EDONKEY
            && (packet.opcode() == client::HELLO || packet.opcode() == client::HELLOANSWER)
        {
            connection.set_frame_limit(frame_limits::client);
        }
        self.events.send(LinkEvent::Packet(self.id, packet)).map_err(|_| "the engine stopped".to_owned())
    }

    fn on_closed(&self, _connection: &TcpConnection, cause: Option<io::Error>) {
        let _ = self.events.send(LinkEvent::Closed(self.id, cause.map(|e| e.to_string())));
    }
}
