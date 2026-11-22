// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Opening connections: plain, or obfuscated with the handshake of the protocol obfuscation; from the interface
//! chosen and through the proxy of the preferences, as the routes say.

use std::io::{self, ErrorKind};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

use rand::CryptoRng;
use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};

use crate::config::proxy_settings::{ProxySettings, ProxyType};
use crate::net::routes::Routes;
use crate::net::socks5::{self, Socks5Error, Target};
use crate::net::tcp_connection::{FrameReader, FrameWriter, Limit, TcpConnection};
use crate::protocol::crypt::stream_obfuscation;
use crate::protocol::packet::protocol;

/// The padding we send at most in a handshake with a client.
pub const MAX_CLIENT_PADDING: u8 = 128;

/// One open connection: the frames it receives and the frames it sends.
pub struct Connection {
    pub reader: FrameReader<OwnedReadHalf>,
    pub writer: FrameWriter<OwnedWriteHalf>,
    pub obfuscated: bool,
    pub remote: SocketAddr,
    /// Our address on it.
    pub local: Option<IpAddr>,
}

impl Connection {
    /// The connection served by its own tasks, not started yet; frames read go by `limit` until another is set.
    pub fn into_tcp_connection(self, limit: Limit) -> TcpConnection {
        TcpConnection::new(self.reader, self.writer, self.remote, self.local, self.obfuscated, limit)
    }
}

/// Connects to a server; obfuscated through the Diffie-Hellman handshake when `obfuscated`.
pub async fn connect_to_server(
    address: SocketAddr,
    obfuscated: bool,
    limit: Duration,
    routes: &Routes,
    random: &mut (impl CryptoRng + Send),
) -> io::Result<Connection> {
    let stream = open(address, limit, routes).await?;
    let local = local_of(&stream);
    let (mut input, mut output) = stream.into_split();
    if !obfuscated {
        return Ok(plain(input, output, address, local));
    }
    let ciphers = within(limit, stream_obfuscation::connect_to_server(&mut input, &mut output, random)).await?;
    Ok(Connection {
        reader: FrameReader::new(input, Some(ciphers.receive)),
        writer: FrameWriter::new(output, Some(ciphers.send)),
        obfuscated: true,
        remote: address,
        local,
    })
}

/// Connects to a client; obfuscated with its user hash when given.
pub async fn connect_to_client(
    address: SocketAddr,
    obfuscate_for: Option<[u8; 16]>,
    limit: Duration,
    routes: &Routes,
    random: &mut (impl CryptoRng + Send),
) -> io::Result<Connection> {
    let stream = open(address, limit, routes).await?;
    let local = local_of(&stream);
    let (mut input, mut output) = stream.into_split();
    let Some(user_hash) = obfuscate_for else {
        return Ok(plain(input, output, address, local));
    };
    let ciphers = within(
        limit,
        stream_obfuscation::connect_to_client(&mut input, &mut output, &user_hash, random, MAX_CLIENT_PADDING),
    )
    .await?;
    Ok(Connection {
        reader: FrameReader::new(input, Some(ciphers.receive)),
        writer: FrameWriter::new(output, Some(ciphers.send)),
        obfuscated: true,
        remote: address,
        local,
    })
}

/// A TCP connection to `address` along `routes`: from the interface chosen, through the proxy of the preferences.
pub async fn open(address: SocketAddr, limit: Duration, routes: &Routes) -> io::Result<TcpStream> {
    let local = routes.local_address()?;
    // The interface njMule is bound to, such as a VPN's, has an IPv4 address: IPv6 would leave around it.
    if let Some(local) = local
        && address.is_ipv6()
    {
        return Err(io::Error::new(ErrorKind::AddrNotAvailable, format!("no IPv6 connections while bound to {local}")));
    }
    let proxy = routes.proxy();
    match proxy.kind {
        ProxyType::None => within(limit, socks5::connect_from(address, local)).await,
        ProxyType::Socks => through_socks(&proxy, address, local, limit).await,
        ProxyType::Http => {
            let mut stream = socks5::connect_to_proxy(&proxy, local, limit).await?;
            within(limit, http_connect(&mut stream, &address.to_string())).await?;
            Ok(stream)
        }
    }
}

/// A TCP connection to `host` at `port` along `routes`, as [`open`]. A proxy looks the name up itself, so that the
/// lookup does not leave around it.
pub async fn open_host(host: &str, port: u16, limit: Duration, routes: &Routes) -> io::Result<TcpStream> {
    if let Ok(ip) = host.trim_start_matches('[').trim_end_matches(']').parse::<IpAddr>() {
        return open(SocketAddr::new(ip, port), limit, routes).await;
    }
    let local = routes.local_address()?;
    let proxy = routes.proxy();
    match proxy.kind {
        ProxyType::None => {
            let found: Vec<SocketAddr> =
                within(limit, async { Ok(tokio::net::lookup_host((host, port)).await?.collect()) }).await?;
            // Bound to an interface, IPv4 only, as for the addresses.
            let address = found
                .into_iter()
                .find(|address| local.is_none() || address.is_ipv4())
                .ok_or_else(|| io::Error::new(ErrorKind::NotFound, format!("no address of {host} to reach")))?;
            open(address, limit, routes).await
        }
        ProxyType::Socks => {
            let mut stream = socks5::open(&proxy, local, limit).await?;
            let target = Target::Name(host.to_owned(), port);
            within(limit, async { Ok(socks5::request(&mut stream, socks5::CONNECT, &target).await?) }).await?;
            Ok(stream)
        }
        ProxyType::Http => {
            let mut stream = socks5::connect_to_proxy(&proxy, local, limit).await?;
            within(limit, http_connect(&mut stream, &format!("{host}:{port}"))).await?;
            Ok(stream)
        }
    }
}

async fn through_socks(
    proxy: &ProxySettings,
    address: SocketAddr,
    local: Option<std::net::Ipv4Addr>,
    limit: Duration,
) -> io::Result<TcpStream> {
    match socks5::open(proxy, local, limit).await {
        Ok(mut stream) => {
            within(limit, async {
                Ok(socks5::request(&mut stream, socks5::CONNECT, &Target::Address(address)).await?)
            })
            .await?;
            Ok(stream)
        }
        // A SOCKS4 proxy, asked again in its own version on a new connection.
        Err(Socks5Error::NotVersion5) => {
            let mut stream = socks5::connect_to_proxy(proxy, local, limit).await?;
            within(limit, socks5::connect4(&mut stream, address)).await?;
            Ok(stream)
        }
        Err(e) => Err(e.into()),
    }
}

// The CONNECT method of an HTTP proxy (RFC 9110): a few lines, where a crate would bring an HTTP stack of its own.
async fn http_connect(stream: &mut TcpStream, address: &str) -> io::Result<()> {
    const MAX_HEAD: usize = 8 * 1024;
    stream.write_all(format!("CONNECT {address} HTTP/1.1\r\nHost: {address}\r\n\r\n").as_bytes()).await?;
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") {
        if head.len() >= MAX_HEAD {
            return Err(io::Error::other("the HTTP proxy answered with too long a head"));
        }
        head.push(stream.read_u8().await?);
    }
    let status = head.split(|&b| b == b' ').nth(1).unwrap_or_default();
    if status == b"200" {
        Ok(())
    } else {
        let line = String::from_utf8_lossy(head.split(|&b| b == b'\r').next().unwrap_or_default()).into_owned();
        Err(io::Error::other(format!("the HTTP proxy refused the connection: {line}")))
    }
}

fn plain(input: OwnedReadHalf, output: OwnedWriteHalf, remote: SocketAddr, local: Option<IpAddr>) -> Connection {
    Connection {
        reader: FrameReader::new(input, None),
        writer: FrameWriter::new(output, None),
        obfuscated: false,
        remote,
        local,
    }
}

// Our address on a connection, unless it is the wildcard.
fn local_of(stream: &TcpStream) -> Option<IpAddr> {
    stream.local_addr().ok().map(|address| normalize_ip(address.ip())).filter(|ip| !ip.is_unspecified())
}

/// Unwraps an IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) from a dual-stack socket back into its IPv4 address.
pub fn normalize_ip(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map_or(ip, IpAddr::V4),
        IpAddr::V4(_) => ip,
    }
}

/// Unwraps an IPv4-mapped IPv6 socket address back into its IPv4 socket address.
pub fn normalize_addr(addr: SocketAddr) -> SocketAddr {
    SocketAddr::new(normalize_ip(addr.ip()), addr.port())
}

/// Opens a TCP listener on `port`: bound to `local` when an IPv4 interface is chosen, else dual-stack (`[::]` with
/// `IPV6_V6ONLY = false`) so both IPv4 and IPv6 clients can connect, falling back to `0.0.0.0` on IPv4-only hosts.
pub async fn listen(local: Option<Ipv4Addr>, port: u16) -> io::Result<TcpListener> {
    if let Some(v4) = local {
        return TcpListener::bind(SocketAddr::from((v4, port))).await;
    }
    match bind_dual_stack(port) {
        Ok(listener) => Ok(listener),
        Err(e) if is_ipv6_unavailable(&e) => TcpListener::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, port))).await,
        Err(e) => Err(e),
    }
}

fn bind_dual_stack(port: u16) -> io::Result<TcpListener> {
    let socket = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_only_v6(false)?;
    #[cfg(not(windows))]
    socket.set_reuse_address(true)?;
    socket.set_nonblocking(true)?;
    socket.bind(&SocketAddr::from((Ipv6Addr::UNSPECIFIED, port)).into())?;
    socket.listen(1024)?;
    TcpListener::from_std(socket.into())
}

fn is_ipv6_unavailable(error: &io::Error) -> bool {
    matches!(error.kind(), ErrorKind::Unsupported | ErrorKind::AddrNotAvailable | ErrorKind::InvalidInput)
}

/// Takes a connection a client opened: plain, or obfuscated with keys from our user hash; a plain one is refused unless
/// `allow_plain`, when obfuscation is required. The handshake must end within `limit`.
pub async fn accept(
    stream: TcpStream,
    own_user_hash: [u8; 16],
    allow_plain: bool,
    limit: Duration,
    random: &mut (impl CryptoRng + Send),
) -> io::Result<Connection> {
    stream.set_nodelay(true)?;
    let remote = normalize_addr(stream.peer_addr()?);
    let local = local_of(&stream);
    let mut first = [0u8; 1];
    if within(limit, stream.peek(&mut first)).await? == 0 {
        return Err(io::Error::new(ErrorKind::UnexpectedEof, "closed before the first byte"));
    }
    let (mut input, mut output) = stream.into_split();
    if protocol::is_tcp(first[0]) {
        if !allow_plain {
            return Err(io::Error::other("plain connection refused, obfuscation required"));
        }
        return Ok(plain(input, output, remote, local));
    }
    let mut marker = [0u8; 1];
    input.read_exact(&mut marker).await?;
    let ciphers = within(
        limit,
        stream_obfuscation::accept_from_client(&mut input, &mut output, &own_user_hash, random, MAX_CLIENT_PADDING),
    )
    .await?;
    Ok(Connection {
        reader: FrameReader::new(input, Some(ciphers.receive)),
        writer: FrameWriter::new(output, Some(ciphers.send)),
        obfuscated: true,
        remote,
        local,
    })
}

async fn within<T>(limit: Duration, work: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    tokio::time::timeout(limit, work)
        .await
        .map_err(|_| io::Error::new(ErrorKind::TimedOut, "no answer in time"))?
}
