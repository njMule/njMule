// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Opening connections: plain, or obfuscated with the handshake of the protocol obfuscation.

use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::time::Duration;

use rand::CryptoRng;
use tokio::net::TcpStream;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};

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
    random: &mut (impl CryptoRng + Send),
) -> io::Result<Connection> {
    let stream = open(address, limit).await?;
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
    random: &mut (impl CryptoRng + Send),
) -> io::Result<Connection> {
    let stream = open(address, limit).await?;
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

async fn open(address: SocketAddr, limit: Duration) -> io::Result<TcpStream> {
    let stream = within(limit, TcpStream::connect(address)).await?;
    stream.set_nodelay(true)?;
    Ok(stream)
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
