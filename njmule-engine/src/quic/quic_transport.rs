// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A QUIC connection with another client as a byte stream: each side writes on the one stream it opened and reads the
//! other's.

use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::task::{Context, Poll};

use quinn::{Connection, RecvStream, SendStream, VarInt};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

use super::quic_link::Registration;

/// The connection closes when this is dropped.
pub struct QuicTransport {
    connection: Connection,
    remote: SocketAddr,
    ours: SendStream,
    theirs: RecvStream,
    // The halves nobody uses, kept: dropping them would stop the other side's writing on our stream, or finish ours
    // on theirs.
    _ours_unread: RecvStream,
    _theirs_unwritten: SendStream,
    _registration: Registration,
}

impl QuicTransport {
    pub(crate) fn new(
        connection: Connection,
        remote: SocketAddr,
        ours: (SendStream, RecvStream),
        theirs: (SendStream, RecvStream),
        registration: Registration,
    ) -> Self {
        Self {
            connection,
            remote,
            ours: ours.0,
            theirs: theirs.1,
            _ours_unread: ours.1,
            _theirs_unwritten: theirs.0,
            _registration: registration,
        }
    }

    pub fn remote_address(&self) -> SocketAddr {
        self.remote
    }

    /// Ends our stream, then the connection.
    pub fn close(&mut self) {
        let _ = self.ours.finish();
        self.connection.close(VarInt::from_u32(0), b"");
    }
}

impl Drop for QuicTransport {
    fn drop(&mut self) {
        self.close();
    }
}

impl AsyncRead for QuicTransport {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        AsyncRead::poll_read(Pin::new(&mut self.theirs), cx, buf)
    }
}

impl AsyncWrite for QuicTransport {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        AsyncWrite::poll_write(Pin::new(&mut self.ours), cx, buf)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        AsyncWrite::poll_flush(Pin::new(&mut self.ours), cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        AsyncWrite::poll_shutdown(Pin::new(&mut self.ours), cx)
    }
}
