// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The SOCKS5 handshake (RFC 1928, logging in as RFC 1929 says) that TCP connections and UDP associations share, and
//! the SOCKS4 request a proxy of that version takes. A login is offered only when the settings have a user name: a
//! proxy that prefers one, given the choice, would otherwise refuse us.
//!
//! Ours rather than a crate's: the clients of crates connect, but none asks for a UDP association, which the relay of
//! Kad and the server pings needs.

use std::fmt;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};

use crate::config::proxy_settings::{ProxySettings, ProxyType};

pub const CONNECT: u8 = 1;
pub const UDP_ASSOCIATE: u8 = 3;

const VERSION: u8 = 5;
const NO_LOGIN: u8 = 0;
const USER_AND_PASSWORD: u8 = 2;
const LOGIN_VERSION: u8 = 1;
const IPV4: u8 = 1;
const DOMAIN: u8 = 3;
const IPV6: u8 = 4;
const MAX_NAME: usize = 255;
// The replies that say the proxy will never relay UDP for us: not allowed by its rules, command not supported.
const NOT_ALLOWED: u8 = 2;
const COMMAND_NOT_SUPPORTED: u8 = 7;
const REPLIES: [&str; 9] = [
    "succeeded",
    "general failure",
    "not allowed by its rules",
    "network unreachable",
    "host unreachable",
    "connection refused",
    "TTL expired",
    "command not supported",
    "address type not supported",
];

/// Why the proxy did not do what we asked.
#[derive(Debug)]
pub enum Socks5Error {
    /// The proxy answered in another SOCKS version, or in none.
    NotVersion5,
    /// The proxy will not do it: it wants another login, or refuses the command.
    Unsupported(String),
    Io(io::Error),
}

impl fmt::Display for Socks5Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotVersion5 => f.write_str("the proxy speaks no SOCKS5"),
            Self::Unsupported(reason) => f.write_str(reason),
            Self::Io(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for Socks5Error {}

impl From<io::Error> for Socks5Error {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}

impl From<Socks5Error> for io::Error {
    fn from(e: Socks5Error) -> Self {
        match e {
            Socks5Error::Io(e) => e,
            other => io::Error::other(other.to_string()),
        }
    }
}

/// Where a request goes: an address, or a name the proxy resolves.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Target {
    Address(SocketAddr),
    Name(String, u16),
}

/// A TCP connection to the proxy of `proxy`, from `local` (none: any address), each step waited for `limit` at most.
pub async fn connect_to_proxy(
    proxy: &ProxySettings,
    local: Option<Ipv4Addr>,
    limit: Duration,
) -> io::Result<TcpStream> {
    let mut last = io::Error::new(io::ErrorKind::NotFound, format!("no address for {}", proxy.host));
    for address in within(limit, tokio::net::lookup_host((proxy.host.as_str(), proxy.port))).await? {
        match within(limit, connect_from(address, local)).await {
            Ok(stream) => return Ok(stream),
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// A TCP connection to `address`, from `local` when given.
pub async fn connect_from(address: SocketAddr, local: Option<Ipv4Addr>) -> io::Result<TcpStream> {
    let socket = if address.is_ipv4() { TcpSocket::new_v4()? } else { TcpSocket::new_v6()? };
    if let Some(local) = local {
        socket.bind(SocketAddr::new(IpAddr::V4(local), 0))?;
    }
    let stream = socket.connect(address).await?;
    stream.set_nodelay(true)?;
    Ok(stream)
}

/// A TCP connection to the SOCKS proxy of `proxy`, logged in.
pub async fn open(proxy: &ProxySettings, local: Option<Ipv4Addr>, limit: Duration) -> Result<TcpStream, Socks5Error> {
    if proxy.kind != ProxyType::Socks {
        return Err(Socks5Error::Unsupported("not a SOCKS proxy".to_owned()));
    }
    let mut stream = connect_to_proxy(proxy, local, limit).await?;
    tokio::time::timeout(limit, log_in(&mut stream, proxy))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "the proxy did not answer in time"))??;
    Ok(stream)
}

async fn log_in(stream: &mut TcpStream, proxy: &ProxySettings) -> Result<(), Socks5Error> {
    let login = !proxy.user.is_empty();
    let greeting: &[u8] = if login { &[VERSION, 2, NO_LOGIN, USER_AND_PASSWORD] } else { &[VERSION, 1, NO_LOGIN] };
    stream.write_all(greeting).await?;
    let mut answer = [0; 2];
    stream.read_exact(&mut answer).await?;
    let [version, method] = answer;
    if version != VERSION {
        return Err(Socks5Error::NotVersion5);
    }
    if method == NO_LOGIN {
        return Ok(());
    }
    if method != USER_AND_PASSWORD || !login {
        return Err(Socks5Error::Unsupported(if method == USER_AND_PASSWORD {
            "the proxy wants a user name and password".to_owned()
        } else {
            "the proxy takes none of our ways to log in".to_owned()
        }));
    }
    let (user, password) = (proxy.user.as_bytes(), proxy.password.as_bytes());
    let (Ok(user_length), Ok(password_length)) = (u8::try_from(user.len()), u8::try_from(password.len())) else {
        return Err(Socks5Error::Unsupported("the user name or password is too long for SOCKS5".to_owned()));
    };
    let mut request = vec![LOGIN_VERSION, user_length];
    request.extend_from_slice(user);
    request.push(password_length);
    request.extend_from_slice(password);
    stream.write_all(&request).await?;
    let mut status = [0; 2];
    stream.read_exact(&mut status).await?;
    let [_, accepted] = status;
    if accepted != 0 {
        return Err(Socks5Error::Unsupported("the proxy refused the user name or password".to_owned()));
    }
    Ok(())
}

/// Asks the proxy, over `stream` that [`open`] logged in, for `command` on `target`; returns the address the proxy
/// bound.
pub async fn request(stream: &mut TcpStream, command: u8, target: &Target) -> Result<Target, Socks5Error> {
    let mut out = vec![VERSION, command, 0];
    let port = match target {
        Target::Name(name, port) => {
            let length = u8::try_from(name.len())
                .ok()
                .filter(|&length| usize::from(length) <= MAX_NAME)
                .ok_or_else(|| io::Error::other(format!("host name too long for SOCKS5: {name}")))?;
            out.extend([DOMAIN, length]);
            out.extend_from_slice(name.as_bytes());
            *port
        }
        Target::Address(address) => {
            match address.ip() {
                IpAddr::V4(v4) => {
                    out.push(IPV4);
                    out.extend(v4.octets());
                }
                IpAddr::V6(v6) => {
                    out.push(IPV6);
                    out.extend(v6.octets());
                }
            }
            address.port()
        }
    };
    out.extend(port.to_be_bytes());
    stream.write_all(&out).await?;
    let mut head = [0; 4];
    stream.read_exact(&mut head).await?;
    let [version, status, _, kind] = head;
    if version != VERSION {
        return Err(Socks5Error::NotVersion5);
    }
    if status != 0 {
        let reason = REPLIES.get(usize::from(status)).map_or_else(|| format!("reply {status}"), |r| (*r).to_owned());
        if command == UDP_ASSOCIATE && (status == NOT_ALLOWED || status == COMMAND_NOT_SUPPORTED) {
            return Err(Socks5Error::Unsupported(format!("the proxy relays no UDP ({reason})")));
        }
        return Err(Socks5Error::Io(io::Error::other(format!("the proxy could not reach {target:?}: {reason}"))));
    }
    let bound = match kind {
        IPV4 => {
            let mut octets = [0; 4];
            stream.read_exact(&mut octets).await?;
            Target::Address(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(octets)), stream.read_u16().await?))
        }
        IPV6 => {
            let mut octets = [0; 16];
            stream.read_exact(&mut octets).await?;
            Target::Address(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(octets)), stream.read_u16().await?))
        }
        DOMAIN => {
            let mut name = vec![0; usize::from(stream.read_u8().await?)];
            stream.read_exact(&mut name).await?;
            Target::Name(String::from_utf8_lossy(&name).into_owned(), stream.read_u16().await?)
        }
        other => {
            return Err(Socks5Error::Io(io::Error::other(format!("unknown address type {other} in a SOCKS5 reply"))));
        }
    };
    Ok(bound)
}

/// Asks a SOCKS4 proxy, over a new connection to it, to connect to `target`, an IPv4 address.
pub async fn connect4(stream: &mut TcpStream, target: SocketAddr) -> io::Result<()> {
    let IpAddr::V4(ip) = target.ip() else {
        return Err(io::Error::other("a SOCKS4 proxy reaches IPv4 addresses only"));
    };
    let mut request = vec![4, CONNECT];
    request.extend(target.port().to_be_bytes());
    request.extend(ip.octets());
    // An empty user id.
    request.push(0);
    stream.write_all(&request).await?;
    let mut answer = [0; 8];
    stream.read_exact(&mut answer).await?;
    let [_, reply, ..] = answer;
    match reply {
        90 => Ok(()),
        code => Err(io::Error::other(format!("the SOCKS4 proxy refused the connection (reply {code})"))),
    }
}

/// Whether `address` is one a UDP relay can be reached at as given.
pub fn usable(address: &Target) -> bool {
    matches!(address, Target::Address(SocketAddr::V4(v4)) if !v4.ip().is_unspecified())
}

async fn within<T>(limit: Duration, future: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    tokio::time::timeout(limit, future)
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "the proxy did not answer in time"))?
}
