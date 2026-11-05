// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The obfuscated TCP handshakes (protocol obfuscation, not security). Each one yields the RC4 key streams of the rest
//! of the stream.
//!
//! - Client to client: keys `MD5(<receiver user hash><34|203><random part>)`; the caller sends `<marker><random part
//!   4>` in clear, then encrypted `<sync 4><methods supported 1><method preferred 1><padding length 1><padding>`; the
//!   receiver answers `<sync 4><method selected 1><padding length 1><padding>`.
//! - Client to server: Diffie-Hellman ([`Dh768`]), keys `MD5(<secret 96><34|203>)`; the client sends `<marker><g^a
//!   96><padding length 0-15><padding>` in clear, the server answers `<g^b 96>` and the encrypted negotiation, the
//!   client confirms.

use std::io::{self, ErrorKind, Read, Write};

use md5::{Digest, Md5};
use rand::{CryptoRng, RngExt};

use crate::crypto::rc4::{Rc4, TCP_DISCARD};
use crate::protocol::crypt::dh768::{self, Dh768};
use crate::protocol::io::DecodeError;
use crate::protocol::packet::protocol;

/// The key streams of each direction once the handshake is complete.
#[derive(Debug)]
pub struct Ciphers {
    pub send: Rc4,
    pub receive: Rc4,
}

pub const MAGIC_REQUESTER: u8 = 34;
pub const MAGIC_SERVER: u8 = 203;
pub const SYNC: u32 = 0x835E_6FC4;
/// The only method: plain obfuscation.
pub const METHOD_OBFUSCATION: u8 = 0;

const MAX_SERVER_PADDING: u8 = 15;
// <sync><methods supported><method preferred><padding length>
const NEGOTIATION_HEADER: usize = 7;
// <sync><method selected><padding length>
const ANSWER_HEADER: usize = 6;

/// Outgoing connection to a client whose user hash is known.
pub fn connect_to_client(
    input: &mut impl Read,
    output: &mut impl Write,
    receiver_user_hash: &[u8; 16],
    random: &mut impl CryptoRng,
    max_padding: u8,
) -> io::Result<Ciphers> {
    let mut key_part = [0; 4];
    random.fill_bytes(&mut key_part);
    let mut send = client_key(receiver_user_hash, MAGIC_REQUESTER, &key_part);
    let mut receive = client_key(receiver_user_hash, MAGIC_SERVER, &key_part);
    let mut tail = negotiation(random, max_padding);
    send.apply(&mut tail);
    let mut request = vec![marker(random)];
    request.extend_from_slice(&key_part);
    request.extend_from_slice(&tail);
    output.write_all(&request)?;
    output.flush()?;
    let answer: [u8; ANSWER_HEADER] = read_decrypted(input, &mut receive)?;
    check_sync(&answer)?;
    let [.., method, padding] = answer;
    if method != METHOD_OBFUSCATION {
        return Err(invalid(format!("peer selected unsupported obfuscation method {method}")));
    }
    skip_decrypted(input, &mut receive, padding)?;
    Ok(Ciphers { send, receive })
}

/// Incoming connection whose first byte (the marker, already read) is not a protocol byte. The keys derive from our own
/// user hash.
pub fn accept_from_client(
    input: &mut impl Read,
    output: &mut impl Write,
    own_user_hash: &[u8; 16],
    random: &mut impl CryptoRng,
    max_padding: u8,
) -> io::Result<Ciphers> {
    let key_part: [u8; 4] = read_fully(input)?;
    let mut receive = client_key(own_user_hash, MAGIC_REQUESTER, &key_part);
    let mut send = client_key(own_user_hash, MAGIC_SERVER, &key_part);
    let request: [u8; NEGOTIATION_HEADER] = read_decrypted(input, &mut receive)?;
    check_sync(&request)?;
    let [.., padding] = request;
    skip_decrypted(input, &mut receive, padding)?;
    let mut answer = selection(random, max_padding);
    send.apply(&mut answer);
    output.write_all(&answer)?;
    output.flush()?;
    Ok(Ciphers { send, receive })
}

/// Outgoing connection to a server. The final confirmation is written but not flushed, so that it leaves together with
/// the first packet.
pub fn connect_to_server(
    input: &mut impl Read,
    output: &mut impl Write,
    random: &mut impl CryptoRng,
) -> io::Result<Ciphers> {
    let dh = Dh768::generate(random);
    let mut request = vec![marker(random)];
    request.extend_from_slice(&dh.public_value());
    request.extend_from_slice(&padded(random, MAX_SERVER_PADDING));
    output.write_all(&request)?;
    output.flush()?;
    let server_public: [u8; dh768::SIZE] = read_fully(input)?;
    let secret = dh.shared_secret(&server_public).map_err(invalid_data)?;
    let mut send = server_key(&secret, MAGIC_REQUESTER);
    let mut receive = server_key(&secret, MAGIC_SERVER);
    let answer: [u8; NEGOTIATION_HEADER] = read_decrypted(input, &mut receive)?;
    check_sync(&answer)?;
    let [.., padding] = answer;
    skip_decrypted(input, &mut receive, padding)?;
    let mut confirm = selection(random, MAX_SERVER_PADDING);
    send.apply(&mut confirm);
    output.write_all(&confirm)?;
    Ok(Ciphers { send, receive })
}

/// The server side of [`connect_to_server`], after the marker byte; used by test servers.
pub fn accept_as_server(
    input: &mut impl Read,
    output: &mut impl Write,
    random: &mut impl CryptoRng,
) -> io::Result<Ciphers> {
    let client_public: [u8; dh768::SIZE] = read_fully(input)?;
    let [padding] = read_fully(input)?;
    discard(input, padding)?;
    let dh = Dh768::generate(random);
    let secret = dh.shared_secret(&client_public).map_err(invalid_data)?;
    let mut receive = server_key(&secret, MAGIC_REQUESTER);
    let mut send = server_key(&secret, MAGIC_SERVER);
    let mut tail = negotiation(random, MAX_SERVER_PADDING);
    send.apply(&mut tail);
    let mut answer = dh.public_value().to_vec();
    answer.extend_from_slice(&tail);
    output.write_all(&answer)?;
    output.flush()?;
    let confirm: [u8; ANSWER_HEADER] = read_decrypted(input, &mut receive)?;
    check_sync(&confirm)?;
    let [.., padding] = confirm;
    skip_decrypted(input, &mut receive, padding)?;
    Ok(Ciphers { send, receive })
}

/// A random first byte that cannot be mistaken for a plain TCP protocol byte.
pub fn marker(random: &mut impl CryptoRng) -> u8 {
    loop {
        let candidate: u8 = random.random();
        if !protocol::is_tcp(candidate) {
            return candidate;
        }
    }
}

pub fn client_key(user_hash: &[u8; 16], magic: u8, key_part: &[u8; 4]) -> Rc4 {
    let mut material = user_hash.to_vec();
    material.push(magic);
    material.extend_from_slice(key_part);
    tcp_cipher(&md5(&material))
}

pub fn server_key(secret: &[u8; dh768::SIZE], magic: u8) -> Rc4 {
    let mut material = secret.to_vec();
    material.push(magic);
    tcp_cipher(&md5(&material))
}

pub fn md5(data: &[u8]) -> [u8; 16] {
    Md5::digest(data).into()
}

fn tcp_cipher(key: &[u8; 16]) -> Rc4 {
    let mut rc4 = Rc4::new(key);
    rc4.skip(TCP_DISCARD);
    rc4
}

// <sync><methods supported><method preferred><padding length><padding>, not yet encrypted.
fn negotiation(random: &mut impl CryptoRng, max_padding: u8) -> Vec<u8> {
    let mut out = SYNC.to_le_bytes().to_vec();
    out.push(METHOD_OBFUSCATION);
    out.push(METHOD_OBFUSCATION);
    out.extend_from_slice(&padded(random, max_padding));
    out
}

// <sync><method selected><padding length><padding>, not yet encrypted.
fn selection(random: &mut impl CryptoRng, max_padding: u8) -> Vec<u8> {
    let mut out = SYNC.to_le_bytes().to_vec();
    out.push(METHOD_OBFUSCATION);
    out.extend_from_slice(&padded(random, max_padding));
    out
}

// <padding length><padding> of 0 to max_padding random bytes.
fn padded(random: &mut impl CryptoRng, max_padding: u8) -> Vec<u8> {
    let length = random.random_range(0..=max_padding);
    let mut out = vec![0; usize::from(length)];
    random.fill_bytes(&mut out);
    out.insert(0, length);
    out
}

fn check_sync(decrypted: &[u8]) -> io::Result<()> {
    match decrypted.first_chunk::<4>() {
        Some(sync) if u32::from_le_bytes(*sync) == SYNC => Ok(()),
        _ => Err(invalid("wrong obfuscation sync value: not an obfuscated stream".to_owned())),
    }
}

fn read_decrypted<const N: usize>(input: &mut impl Read, cipher: &mut Rc4) -> io::Result<[u8; N]> {
    let mut data = read_fully(input)?;
    cipher.apply(&mut data);
    Ok(data)
}

fn skip_decrypted(input: &mut impl Read, cipher: &mut Rc4, length: u8) -> io::Result<()> {
    discard(input, length)?;
    cipher.skip(usize::from(length));
    Ok(())
}

fn discard(input: &mut impl Read, length: u8) -> io::Result<()> {
    let mut data = vec![0; usize::from(length)];
    fill(input, &mut data)
}

fn read_fully<const N: usize>(input: &mut impl Read) -> io::Result<[u8; N]> {
    let mut data = [0; N];
    fill(input, &mut data)?;
    Ok(data)
}

fn fill(input: &mut impl Read, data: &mut [u8]) -> io::Result<()> {
    input.read_exact(data).map_err(|e| match e.kind() {
        ErrorKind::UnexpectedEof => invalid("connection closed during the obfuscation handshake".to_owned()),
        _ => e,
    })
}

fn invalid(message: String) -> io::Error {
    invalid_data(DecodeError::new(message))
}

fn invalid_data(error: DecodeError) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, error)
}
