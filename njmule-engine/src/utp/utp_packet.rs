// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A uTP packet (BEP 29, version 1): a 20-byte header in network order, the extensions, then the payload. Only the
//! selective acknowledgement extension is read; others are skipped.

use crate::protocol::io::DecodeError;

/// The packet types, by their code.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Type {
    Data,
    Fin,
    State,
    Reset,
    Syn,
}

impl Type {
    pub fn code(self) -> u8 {
        match self {
            Self::Data => 0,
            Self::Fin => 1,
            Self::State => 2,
            Self::Reset => 3,
            Self::Syn => 4,
        }
    }

    fn of(code: u8) -> Option<Self> {
        match code {
            0 => Some(Self::Data),
            1 => Some(Self::Fin),
            2 => Some(Self::State),
            3 => Some(Self::Reset),
            4 => Some(Self::Syn),
            _ => None,
        }
    }
}

pub const VERSION: u8 = 1;
pub const HEADER: usize = 20;
const SELECTIVE_ACK: u8 = 1;

/// One packet; `selective_ack` is the bitmask of packets received past `ack + 1`, bit 0 of byte 0 for `ack + 2`, of 4
/// to 252 bytes in steps of 4.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UtpPacket {
    pub kind: Type,
    pub connection_id: u16,
    pub timestamp: u32,
    pub timestamp_difference: u32,
    pub window: u32,
    pub seq: u16,
    pub ack: u16,
    pub selective_ack: Option<Vec<u8>>,
    pub payload: Vec<u8>,
}

impl UtpPacket {
    /// The packet's bytes; a selective ack of another length than the extension allows is left out.
    pub fn encode(&self) -> Vec<u8> {
        let mask = self.selective_ack.as_deref().and_then(|mask| Some(mask).zip(mask_length(mask.len())));
        let mut out = Vec::with_capacity(HEADER.saturating_add(self.payload.len()).saturating_add(36));
        out.push(self.kind.code().wrapping_shl(4) | VERSION);
        out.push(if mask.is_some() { SELECTIVE_ACK } else { 0 });
        out.extend_from_slice(&self.connection_id.to_be_bytes());
        out.extend_from_slice(&self.timestamp.to_be_bytes());
        out.extend_from_slice(&self.timestamp_difference.to_be_bytes());
        out.extend_from_slice(&self.window.to_be_bytes());
        out.extend_from_slice(&self.seq.to_be_bytes());
        out.extend_from_slice(&self.ack.to_be_bytes());
        if let Some((mask, length)) = mask {
            out.push(0);
            out.push(length);
            out.extend_from_slice(mask);
        }
        out.extend_from_slice(&self.payload);
        out
    }

    pub fn decode(data: &[u8]) -> Result<Self, DecodeError> {
        let Some((&header, mut rest)) = data.split_first_chunk::<HEADER>() else {
            return Err(DecodeError::new(format!("a uTP packet of {} bytes", data.len())));
        };
        let [first, extension, c0, c1, t0, t1, t2, t3, d0, d1, d2, d3, w0, w1, w2, w3, s0, s1, a0, a1] = header;
        let kind = Type::of(first.wrapping_shr(4))
            .filter(|_| first & 0x0F == VERSION)
            .ok_or_else(|| DecodeError::new("not a uTP packet of version 1"))?;
        let mut extension = extension;
        let mut selective_ack = None;
        while extension != 0 {
            let Some((&[next, size], after)) = rest.split_first_chunk::<2>() else {
                return Err(DecodeError::new("a uTP extension cut short"));
            };
            let Some((value, after)) = after.split_at_checked(usize::from(size)) else {
                return Err(DecodeError::new("a uTP extension cut short"));
            };
            if extension == SELECTIVE_ACK && mask_length(value.len()).is_some() {
                selective_ack = Some(value.to_vec());
            }
            extension = next;
            rest = after;
        }
        Ok(Self {
            kind,
            connection_id: u16::from_be_bytes([c0, c1]),
            timestamp: u32::from_be_bytes([t0, t1, t2, t3]),
            timestamp_difference: u32::from_be_bytes([d0, d1, d2, d3]),
            window: u32::from_be_bytes([w0, w1, w2, w3]),
            seq: u16::from_be_bytes([s0, s1]),
            ack: u16::from_be_bytes([a0, a1]),
            selective_ack,
            payload: rest.to_vec(),
        })
    }
}

// The length byte of a selective ack of `length` bytes, if the extension can carry one.
fn mask_length(length: usize) -> Option<u8> {
    u8::try_from(length).ok().filter(|&n| n >= 4 && n.is_multiple_of(4))
}
