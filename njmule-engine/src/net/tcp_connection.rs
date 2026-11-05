// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! eD2K frames over an async stream: [`tcp_framing`]'s layout, each frame's size decided from its header before
//! anything is allocated.

use std::io::{self, ErrorKind};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::crypto::rc4::Rc4;
use crate::protocol::io::DecodeError;
use crate::protocol::packet::frame_limit::FrameLimit;
use crate::protocol::packet::{Packet, tcp_framing};

/// Reads one frame: `None` at a clean end of stream before its first byte. A truncated frame, an unknown protocol byte
/// or a payload beyond `limit` is an [`ErrorKind::InvalidData`] error carrying a [`DecodeError`].
pub async fn read_packet(input: &mut (impl AsyncRead + Unpin), limit: &impl FrameLimit) -> io::Result<Option<Packet>> {
    let mut header = [0u8; tcp_framing::HEADER_SIZE];
    let [first, rest @ ..] = &mut header;
    if input.read(std::slice::from_mut(first)).await? == 0 {
        return Ok(None);
    }
    read_fully(input, rest).await?;
    let [protocol, l0, l1, l2, l3, opcode] = header;
    let length =
        tcp_framing::check_header(protocol, u32::from_le_bytes([l0, l1, l2, l3]), opcode, limit).map_err(invalid)?;
    let mut payload = vec![0u8; length];
    read_fully(input, &mut payload).await?;
    Ok(Some(Packet::of(protocol, opcode, payload)))
}

pub async fn write_packet(output: &mut (impl AsyncWrite + Unpin), packet: &Packet) -> io::Result<()> {
    let frame = tcp_framing::encode(packet).map_err(io::Error::other)?;
    output.write_all(&frame).await?;
    output.flush().await
}

async fn read_fully(input: &mut (impl AsyncRead + Unpin), buffer: &mut [u8]) -> io::Result<()> {
    input.read_exact(buffer).await.map(|_| ()).map_err(|e| {
        if e.kind() == ErrorKind::UnexpectedEof {
            invalid(DecodeError::new("connection closed inside a frame"))
        } else {
            e
        }
    })
}

fn invalid(error: DecodeError) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, error)
}

/// The frames a connection receives, decrypted with its key stream when it is obfuscated.
pub struct FrameReader<R> {
    input: R,
    cipher: Option<Rc4>,
}

impl<R: AsyncRead + Unpin> FrameReader<R> {
    pub fn new(input: R, cipher: Option<Rc4>) -> Self {
        Self { input, cipher }
    }

    /// As [`read_packet`], on the decrypted stream.
    pub async fn read_packet(&mut self, limit: &impl FrameLimit) -> io::Result<Option<Packet>> {
        let mut header = [0u8; tcp_framing::HEADER_SIZE];
        let [first, rest @ ..] = &mut header;
        if self.input.read(std::slice::from_mut(first)).await? == 0 {
            return Ok(None);
        }
        read_fully(&mut self.input, rest).await?;
        self.decrypt(&mut header);
        let [protocol, l0, l1, l2, l3, opcode] = header;
        let length = tcp_framing::check_header(protocol, u32::from_le_bytes([l0, l1, l2, l3]), opcode, limit)
            .map_err(invalid)?;
        let mut payload = vec![0u8; length];
        read_fully(&mut self.input, &mut payload).await?;
        self.decrypt(&mut payload);
        Ok(Some(Packet::of(protocol, opcode, payload)))
    }

    fn decrypt(&mut self, data: &mut [u8]) {
        if let Some(cipher) = &mut self.cipher {
            cipher.apply(data);
        }
    }
}

/// The frames a connection sends, encrypted with its key stream when it is obfuscated.
pub struct FrameWriter<W> {
    output: W,
    cipher: Option<Rc4>,
}

impl<W: AsyncWrite + Unpin> FrameWriter<W> {
    pub fn new(output: W, cipher: Option<Rc4>) -> Self {
        Self { output, cipher }
    }

    pub async fn write_packet(&mut self, packet: &Packet) -> io::Result<()> {
        let mut frame = tcp_framing::encode(packet).map_err(io::Error::other)?;
        if let Some(cipher) = &mut self.cipher {
            cipher.apply(&mut frame);
        }
        self.output.write_all(&frame).await?;
        self.output.flush().await
    }

    /// Writes what was handed over to the stream so far, as a handshake's last bytes.
    pub async fn flush(&mut self) -> io::Result<()> {
        self.output.flush().await
    }
}
