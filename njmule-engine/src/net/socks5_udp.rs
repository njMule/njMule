// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A UDP association through a SOCKS5 proxy (RFC 1928 §7): datagrams go to the proxy's relay behind a header that names
//! their destination and come back behind one that names their sender. It lasts as long as the TCP connection that
//! asked for it.

use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use tokio::io::AsyncReadExt;
use tokio::sync::Notify;
use tokio::task::JoinHandle;

use crate::config::proxy_settings::ProxySettings;
use crate::net::socks5::{self, Socks5Error, Target};

/// The header of a datagram to or from an IPv4 address.
pub const IPV4_HEADER: usize = 10;
const IPV4: u8 = 1;

#[derive(Debug, Default)]
struct Ended {
    done: AtomicBool,
    notify: Notify,
}

impl Ended {
    fn end(&self) {
        self.done.store(true, Ordering::SeqCst);
        self.notify.notify_waiters();
    }
}

#[derive(Debug)]
pub struct Socks5Udp {
    relay: SocketAddr,
    ended: Arc<Ended>,
    watch: JoinHandle<()>,
}

impl Socks5Udp {
    /// Asks the SOCKS proxy of `proxy` for a UDP association, from `local` (none: any address), waiting `limit` at
    /// most for each answer. [`Socks5Error::Unsupported`] when the proxy will not relay UDP for us.
    pub async fn associate(
        proxy: &ProxySettings,
        local: Option<Ipv4Addr>,
        limit: Duration,
    ) -> Result<Self, Socks5Error> {
        let mut control = socks5::open(proxy, local, limit).await?;
        let proxy_address = control.peer_addr()?;
        // 0.0.0.0:0: the address our datagrams come from is not known before the first one.
        let anywhere = Target::Address(SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)));
        let bound = tokio::time::timeout(limit, socks5::request(&mut control, socks5::UDP_ASSOCIATE, &anywhere))
            .await
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "the proxy did not answer in time"))??;
        // A relay on the proxy's every address, or one it names, is reached where the proxy was.
        let relay = match bound {
            Target::Address(address) if socks5::usable(&bound) => address,
            Target::Address(address) => SocketAddr::new(proxy_address.ip(), address.port()),
            Target::Name(_, port) => SocketAddr::new(proxy_address.ip(), port),
        };
        let ended = Arc::new(Ended::default());
        let watched = Arc::clone(&ended);
        // The proxy ends the association by closing its connection; nothing else comes on it.
        let watch = tokio::spawn(async move {
            let mut ignored = [0; 256];
            while matches!(control.read(&mut ignored).await, Ok(read) if read > 0) {}
            watched.end();
        });
        Ok(Self { relay, ended, watch })
    }

    /// Where the datagrams go, and come from.
    pub fn relay(&self) -> SocketAddr {
        self.relay
    }

    pub fn is_open(&self) -> bool {
        !self.ended.done.load(Ordering::SeqCst)
    }

    /// Waits for the association to end, closed by the proxy or by [`Socks5Udp::close`].
    pub async fn ended(&self) {
        let notified = self.ended.notify.notified();
        if self.is_open() {
            notified.await;
        }
    }

    pub fn close(&self) {
        self.watch.abort();
        self.ended.end();
    }
}

impl Drop for Socks5Udp {
    fn drop(&mut self) {
        self.close();
    }
}

/// `data` for an IPv4 address, behind the header the relay reads.
pub fn wrap(to: SocketAddrV4, data: &[u8]) -> Vec<u8> {
    let mut datagram = vec![0, 0, 0, IPV4];
    datagram.extend(to.ip().octets());
    datagram.extend(to.port().to_be_bytes());
    datagram.extend_from_slice(data);
    datagram
}

/// The sender and payload of a datagram from the relay; none for a fragment, a sender that is not an IPv4 address
/// (eD2K and Kad know no other) or a datagram too short for its header.
pub fn unwrap(datagram: &[u8]) -> Option<(SocketAddr, &[u8])> {
    // RSV (2), FRAG, ATYP, then the address and the port.
    let (header, payload) = datagram.split_first_chunk::<IPV4_HEADER>()?;
    let [_, _, fragment, kind, a, b, c, d, high, low] = *header;
    (fragment == 0 && kind == IPV4).then(|| {
        (SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::new(a, b, c, d), u16::from_be_bytes([high, low]))), payload)
    })
}
