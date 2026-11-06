// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The TCP port clients connect to: each connection the connection guard admits goes to the acceptor with its permit,
//! the others close at once. Clients may come over IPv6 too.

use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use tokio::net::{TcpListener as Listening, TcpSocket, TcpStream};
use tokio::task::JoinHandle;

use crate::security::connection_guard::{Admission, ConnectionGuard, Permit};

const BACKLOG: u32 = 128;

pub struct TcpListener {
    port: u16,
    accepting: Option<JoinHandle<()>>,
}

impl TcpListener {
    /// Binds `address` (port 0 picks a free one) and starts accepting. The acceptor must not wait: it hands the
    /// connection on, and the permit is to be dropped when the connection ends.
    pub async fn open(
        address: SocketAddr,
        guard: Arc<ConnectionGuard>,
        acceptor: impl Fn(TcpStream, Permit) + Send + Sync + 'static,
    ) -> io::Result<Self> {
        let socket = if address.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };
        // As the system's own listeners: a port whose last connections linger may be taken again.
        #[cfg(not(windows))]
        socket.set_reuseaddr(true)?;
        socket.bind(address)?;
        let listening = socket.listen(BACKLOG)?;
        let port = listening.local_addr()?.port();
        let accepting = tokio::spawn(accept(listening, guard, acceptor));
        Ok(Self { port, accepting: Some(accepting) })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// Stops accepting; the port is free once this returns, as when the port changes in the preferences.
    pub async fn close(mut self) {
        if let Some(accepting) = self.accepting.take() {
            accepting.abort();
            let _ = accepting.await;
        }
    }
}

impl Drop for TcpListener {
    fn drop(&mut self) {
        if let Some(accepting) = self.accepting.take() {
            accepting.abort();
        }
    }
}

async fn accept(listening: Listening, guard: Arc<ConnectionGuard>, acceptor: impl Fn(TcpStream, Permit)) {
    loop {
        let (stream, remote) = match listening.accept().await {
            Ok(accepted) => accepted,
            Err(e) => {
                tracing::warn!(
                    "Accept failed on port {}: {e}",
                    listening.local_addr().map_or(0, |address| address.port())
                );
                continue;
            }
        };
        match guard.admit(remote.ip()) {
            Admission::Admitted(permit) => acceptor(stream, permit),
            Admission::Refused(reason) => tracing::debug!("Refused {remote}: {reason:?}"),
        }
    }
}
