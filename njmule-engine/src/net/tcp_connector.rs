// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Opening connections: plain, or obfuscated with the handshake of the protocol obfuscation; from the interface
//! chosen and through the proxy of the preferences, as the routes say.

use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::time::Duration;

use rand::CryptoRng;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};

use crate::config::proxy_settings::{ProxySettings, ProxyType};
use crate::net::routes::Routes;
use crate::net::socks5::{self, Socks5Error, Target};
use crate::net::tcp_connection::{FrameReader, FrameWriter};
use crate::protocol::crypt::stream_obfuscation;

/// The padding we send at most in a handshake with a client.
pub const MAX_CLIENT_PADDING: u8 = 128;

/// One open connection: the frames it receives and the frames it sends.
pub struct Connection {
    pub reader: FrameReader<OwnedReadHalf>,
    pub writer: FrameWriter<OwnedWriteHalf>,
    pub obfuscated: bool,
    pub remote: SocketAddr,
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
    let (mut input, mut output) = stream.into_split();
    if !obfuscated {
        return Ok(plain(input, output, address));
    }
    let ciphers = within(limit, stream_obfuscation::connect_to_server(&mut input, &mut output, random)).await?;
    Ok(Connection {
        reader: FrameReader::new(input, Some(ciphers.receive)),
        writer: FrameWriter::new(output, Some(ciphers.send)),
        obfuscated: true,
        remote: address,
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
    let (mut input, mut output) = stream.into_split();
    let Some(user_hash) = obfuscate_for else {
        return Ok(plain(input, output, address));
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
            within(limit, http_connect(&mut stream, address)).await?;
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
async fn http_connect(stream: &mut TcpStream, address: SocketAddr) -> io::Result<()> {
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

fn plain(input: OwnedReadHalf, output: OwnedWriteHalf, remote: SocketAddr) -> Connection {
    Connection {
        reader: FrameReader::new(input, None),
        writer: FrameWriter::new(output, None),
        obfuscated: false,
        remote,
    }
}

async fn within<T>(limit: Duration, work: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    tokio::time::timeout(limit, work)
        .await
        .map_err(|_| io::Error::new(ErrorKind::TimedOut, "no answer in time"))?
}
