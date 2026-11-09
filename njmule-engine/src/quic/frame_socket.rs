// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The datagram socket QUIC runs on: what it sends leaves the client UDP port in frames, and what arrives in frames
//! for it is handed in here. It binds no port of its own.

use std::fmt;
use std::io::{self, IoSliceMut};
use std::net::{Ipv4Addr, SocketAddr};
use std::pin::Pin;
use std::sync::{Arc, Mutex, PoisonError};
use std::task::{Context, Poll};

use quinn::udp::{RecvMeta, Transmit};
use quinn::{AsyncUdpSocket, UdpPoller};
use tokio::sync::mpsc;

use super::quic_link::Sender;

// Datagrams waiting for QUIC to read them; more are dropped, as a full socket buffer would.
const WAITING: usize = 1024;

pub(crate) struct FrameSocket {
    sender: Sender,
    arriving: mpsc::Sender<(SocketAddr, Vec<u8>)>,
    arrived: Mutex<mpsc::Receiver<(SocketAddr, Vec<u8>)>>,
}

impl FrameSocket {
    pub(crate) fn new(sender: Sender) -> Self {
        let (arriving, arrived) = mpsc::channel(WAITING);
        Self { sender, arriving, arrived: Mutex::new(arrived) }
    }

    /// A datagram that came in a frame from `from`.
    pub(crate) fn deliver(&self, from: SocketAddr, datagram: &[u8]) {
        if self.arriving.try_send((from, datagram.to_vec())).is_err() {
            tracing::trace!("A QUIC datagram from {from} dropped: too many wait");
        }
    }
}

impl fmt::Debug for FrameSocket {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("FrameSocket")
    }
}

impl AsyncUdpSocket for FrameSocket {
    fn create_io_poller(self: Arc<Self>) -> Pin<Box<dyn UdpPoller>> {
        Box::pin(Writable)
    }

    fn try_send(&self, transmit: &Transmit) -> io::Result<()> {
        let size = transmit.segment_size.unwrap_or(transmit.contents.len()).max(1);
        for datagram in transmit.contents.chunks(size) {
            (self.sender)(transmit.destination, datagram);
        }
        Ok(())
    }

    fn poll_recv(
        &self,
        cx: &mut Context,
        buffers: &mut [IoSliceMut<'_>],
        meta: &mut [RecvMeta],
    ) -> Poll<io::Result<usize>> {
        // The socket holds the sending side itself: the channel never ends.
        let Poll::Ready(Some((from, datagram))) =
            self.arrived.lock().unwrap_or_else(PoisonError::into_inner).poll_recv(cx)
        else {
            return Poll::Pending;
        };
        let (Some(buffer), Some(meta)) = (buffers.first_mut(), meta.first_mut()) else {
            return Poll::Ready(Ok(0));
        };
        let length = datagram.len().min(buffer.len());
        if let (Some(into), Some(read)) = (buffer.get_mut(..length), datagram.get(..length)) {
            into.copy_from_slice(read);
        }
        *meta = RecvMeta { addr: from, len: length, stride: length, ecn: None, dst_ip: None };
        Poll::Ready(Ok(1))
    }

    // eD2K's client UDP port speaks IPv4.
    fn local_addr(&self) -> io::Result<SocketAddr> {
        Ok(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))
    }
}

// The frames leave at once: the socket is always writable.
#[derive(Debug)]
struct Writable;

impl UdpPoller for Writable {
    fn poll_writable(self: Pin<&mut Self>, _cx: &mut Context) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}
