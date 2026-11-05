// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's ObfuscationTest, under their names: the Diffie-Hellman group, the TCP handshakes
//! over two connected pipes, and the UDP datagrams, against the vectors of the independent implementation in
//! `tools/golden/` (`obfuscation.txt`).

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::sync::LazyLock;
use std::thread;

use njmule_engine::crypto::rc4::Rc4;
use njmule_engine::protocol::crypt::datagram_obfuscation::{self as datagrams, Kind, Received};
use njmule_engine::protocol::crypt::dh768::{self, Dh768, P, P_BYTES};
use njmule_engine::protocol::crypt::stream_obfuscation::{self as streams, Ciphers};
use njmule_engine::protocol::io::DecodeError;
use njmule_engine::protocol::packet::{Packet, protocol, udp_framing};
use num_bigint::BigUint;
use rand::rngs::StdRng;
use rand::{Rng, RngExt, SeedableRng};
use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream};

const GOLDEN_FILE: &str =
    concat!(env!("CARGO_MANIFEST_DIR"), "/../njmule-core/src/test/resources/golden/obfuscation.txt");
const USER_HASH: [u8; 16] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
const KAD_ID: [u8; 16] = [16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31];
const SENDER_IP: [u8; 4] = [10, 0, 0, 7];

/// `tcp-client magic=34` → key stream, `udp-ed2k packet` / `udp-ed2k datagram` → hex.
static GOLDEN: LazyLock<HashMap<String, String>> = LazyLock::new(|| {
    let text = std::fs::read_to_string(GOLDEN_FILE).expect("the obfuscation vectors");
    let mut map = HashMap::new();
    for line in text.lines().filter(|line| !line.starts_with('#') && !line.trim().is_empty()) {
        let parts: Vec<&str> = line.split(' ').collect();
        if parts[0].starts_with("tcp") {
            map.insert(format!("{} {}", parts[0], parts[1]), parts[2]["keystream=".len()..].to_owned());
        } else {
            map.insert(format!("{} packet", parts[0]), parts[1]["packet=".len()..].to_owned());
            map.insert(format!("{} datagram", parts[0]), parts[2]["datagram=".len()..].to_owned());
        }
    }
    map
});

fn golden(key: &str) -> &'static str {
    GOLDEN.get(key).unwrap_or_else(|| panic!("no {key} in {GOLDEN_FILE}"))
}

fn key_stream(rc4: &mut Rc4) -> String {
    let mut out = [0; 16];
    rc4.apply(&mut out);
    hex::encode(out)
}

fn round_trip(send: &mut Rc4, receive: &mut Rc4, text: &str) -> String {
    let mut data = text.as_bytes().to_vec();
    send.apply(&mut data);
    receive.apply(&mut data);
    String::from_utf8(data).unwrap()
}

fn is_decode_error(error: &io::Error) -> bool {
    error.kind() == ErrorKind::InvalidData && error.get_ref().is_some_and(|inner| inner.is::<DecodeError>())
}

fn random() -> StdRng {
    StdRng::from_rng(&mut rand::rng())
}

/// Two connected ends of a stream; an end closes when its side drops it.
fn duplex() -> (DuplexStream, DuplexStream) {
    tokio::io::duplex(1 << 16)
}

/// The client handshake: the connecting side with `connecting_user_hash`, the accepting one with [`USER_HASH`] and the
/// marker it read.
async fn clients_shake_hands(
    connecting_user_hash: [u8; 16],
    padding: u8,
) -> (io::Result<Ciphers>, io::Result<(u8, Ciphers)>) {
    let (mut a, mut b) = duplex();
    tokio::join!(
        async move {
            let (mut input, mut output) = tokio::io::split(&mut a);
            streams::connect_to_client(&mut input, &mut output, &connecting_user_hash, &mut random(), padding).await
        },
        async move {
            let (mut input, mut output) = tokio::io::split(&mut b);
            let mut marker = [0];
            input.read_exact(&mut marker).await?;
            let ciphers =
                streams::accept_from_client(&mut input, &mut output, &USER_HASH, &mut random(), padding).await?;
            Ok((marker[0], ciphers))
        },
    )
}

fn packet(protocol: u8, opcode: u8, payload_size: usize) -> (Packet, Vec<u8>) {
    let packet = Packet::of(protocol, opcode, vec![0; payload_size]);
    let plain = udp_framing::encode(&packet);
    (packet, plain)
}

fn from_client(datagram: &[u8]) -> Result<Received, DecodeError> {
    datagrams::decode_from_client(datagram, &USER_HASH, SENDER_IP, Some(&KAD_ID), 7)
}

fn key_part(hex: &str) -> [u8; 2] {
    hex::decode(hex).unwrap().try_into().unwrap()
}

/// Miller-Rabin with random bases.
fn is_probable_prime(n: &BigUint, rounds: usize) -> bool {
    let one = BigUint::from(1u8);
    let n_minus_one = n - &one;
    let s = n_minus_one.trailing_zeros().unwrap();
    let d = &n_minus_one >> s;
    let mut random = rand::rng();
    (0..rounds).all(|_| {
        let mut bytes = vec![0; usize::try_from(n.bits().div_ceil(8)).unwrap()];
        random.fill_bytes(&mut bytes);
        let base = BigUint::from_bytes_be(&bytes) % (n - 3u8) + 2u8;
        let mut x = base.modpow(&d, n);
        if x == one || x == n_minus_one {
            return true;
        }
        (1..s).any(|_| {
            x = x.modpow(&BigUint::from(2u8), n);
            x == n_minus_one
        })
    })
}

#[test]
fn should_use_a_768_bit_prime_for_the_dh_group() {
    let prime = &*P;

    assert_eq!(prime.bits(), 768);
    assert!(is_probable_prime(prime, 64));
    assert_eq!(
        hex::encode_upper(P_BYTES),
        "F2BF52C55F587ADD5371A936E886EB3C6217A33EC34CB40DC73A41A643AFFCE721FC286366535BDBCE259F2286DA4A91B207CBAA5255D4F6\
         1CCEAED45AD5E0747DF7781828105F340F762387F88B289142FB42688F05150F548B5F436AF70DF3"
    );
}

#[test]
fn should_give_both_dh_parties_the_same_shared_secret() {
    let first = Dh768::generate(&mut rand::rng());
    let second = Dh768::generate(&mut rand::rng());

    let first_secret = first.shared_secret(&second.public_value()).unwrap();
    let second_secret = second.shared_secret(&first.public_value()).unwrap();

    assert_eq!(first.public_value().len(), dh768::SIZE);
    assert_eq!(first_secret, second_secret);
}

#[test]
fn should_reject_a_degenerate_peer_value() {
    let mut one = [0; dh768::SIZE];
    one[dh768::SIZE - 1] = 1;
    let p_minus_one = dh768::to_bytes(&(&*P - 1u8));
    let cases: [(&str, &[u8]); 5] = [
        ("zero", &[0; dh768::SIZE]),
        ("one", &one),
        ("p - 1", &p_minus_one),
        ("above p", &[0xFF; dh768::SIZE]),
        ("short", &[2; dh768::SIZE - 1]),
    ];
    let party = Dh768::generate(&mut rand::rng());
    for (name, peer_value) in cases {
        assert!(party.shared_secret(peer_value).is_err(), "{name}");
    }
}

#[test]
fn should_accept_the_smallest_and_largest_usable_peer_values() {
    let mut two = [0; dh768::SIZE];
    two[dh768::SIZE - 1] = 2;
    let p_minus_two = dh768::to_bytes(&(&*P - 2u8));
    let party = Dh768::generate(&mut rand::rng());

    assert!(party.shared_secret(&two).is_ok());
    assert!(party.shared_secret(&p_minus_two).is_ok());
}

#[test]
fn should_derive_the_golden_client_key_stream() {
    for magic in [streams::MAGIC_REQUESTER, streams::MAGIC_SERVER] {
        let mut key = streams::client_key(&USER_HASH, magic, &[0x01, 0x23, 0x45, 0x67]);

        assert_eq!(key_stream(&mut key), golden(&format!("tcp-client magic={magic}")), "magic {magic}");
    }
}

#[test]
fn should_derive_the_golden_server_key_stream() {
    let secret: [u8; dh768::SIZE] = std::array::from_fn(|i| u8::try_from((3 * i + 1) % 256).unwrap());
    for magic in [streams::MAGIC_REQUESTER, streams::MAGIC_SERVER] {
        let mut key = streams::server_key(&secret, magic);

        assert_eq!(key_stream(&mut key), golden(&format!("tcp-server magic={magic}")), "magic {magic}");
    }
}

#[tokio::test]
async fn should_start_the_client_request_in_clear_and_make_it_readable_with_the_receiver_key() {
    let mut request = Vec::new();

    // The answer never comes: only the request matters here.
    let error = streams::connect_to_client(&mut tokio::io::empty(), &mut request, &USER_HASH, &mut random(), 0)
        .await
        .unwrap_err();

    assert!(is_decode_error(&error), "{error}");
    assert_eq!(request.len(), 1 + 4 + 7);
    assert!(!protocol::is_tcp(request[0]));
    let key_part: [u8; 4] = request[1..5].try_into().unwrap();
    let mut body = request[5..].to_vec();
    streams::client_key(&USER_HASH, streams::MAGIC_REQUESTER, &key_part).apply(&mut body);
    assert_eq!(hex::encode(body), "c46f5e83".to_owned() + "00" + "00" + "00");
}

#[tokio::test]
async fn should_complete_the_client_handshake_and_share_the_stream() {
    let (connecting, accepting) = clients_shake_hands(USER_HASH, 128).await;

    let mut connecting = connecting.unwrap();
    let (marker, mut accepting) = accepting.unwrap();
    assert!(!protocol::is_tcp(marker));
    assert_eq!(round_trip(&mut connecting.send, &mut accepting.receive, "hello"), "hello");
    assert_eq!(round_trip(&mut accepting.send, &mut connecting.receive, "answer"), "answer");
}

#[tokio::test]
async fn should_fail_the_handshake_with_a_wrong_user_hash() {
    let (connecting, accepting) = clients_shake_hands([0; 16], 0).await;

    assert!(is_decode_error(&accepting.unwrap_err()));
    assert!(is_decode_error(&connecting.unwrap_err()));
}

#[tokio::test]
async fn should_agree_on_the_keys_in_the_server_handshake() {
    let (mut a, mut b) = duplex();
    let (client, server) = tokio::join!(
        async move {
            let (mut input, mut output) = tokio::io::split(&mut a);
            let ciphers = streams::connect_to_server(&mut input, &mut output, &mut random()).await?;
            output.flush().await?;
            Ok::<_, io::Error>(ciphers)
        },
        async move {
            let (mut input, mut output) = tokio::io::split(&mut b);
            let mut marker = [0];
            input.read_exact(&mut marker).await?;
            streams::accept_as_server(&mut input, &mut output, &mut random()).await
        },
    );

    let (mut client, mut server) = (client.unwrap(), server.unwrap());
    assert_eq!(round_trip(&mut client.send, &mut server.receive, "login"), "login");
    assert_eq!(round_trip(&mut server.send, &mut client.receive, "id"), "id");
}

#[test]
fn should_encrypt_a_client_datagram_as_the_golden_vector() {
    let plain = hex::decode(golden("udp-ed2k packet")).unwrap();

    let datagram = datagrams::encrypt_for_client_with(&plain, &USER_HASH, [192, 168, 1, 10], 0x5B, key_part("3412"));

    assert_eq!(hex::encode(datagram), golden("udp-ed2k datagram"));
}

#[test]
fn should_encrypt_a_kad_datagram_keyed_by_the_receiver_id_as_the_golden_vector() {
    let plain = hex::decode(golden("udp-kad-id packet")).unwrap();

    let datagram =
        datagrams::encrypt_for_kad_with(&plain, Some(&KAD_ID), 0x1122_3344, 0x5566_7788, 0x58, key_part("7856"))
            .unwrap();

    assert_eq!(hex::encode(datagram), golden("udp-kad-id datagram"));
}

#[test]
fn should_encrypt_a_kad_datagram_keyed_by_the_verify_key_as_the_golden_vector() {
    let plain = hex::decode(golden("udp-kad-verify packet")).unwrap();

    let datagram =
        datagrams::encrypt_for_kad_with(&plain, None, 0xCAFE_BABE, 0x5566_7788, 0x5A, key_part("7856")).unwrap();

    assert_eq!(hex::encode(datagram), golden("udp-kad-verify datagram"));
}

#[test]
fn should_encrypt_a_server_datagram_as_the_golden_vector() {
    let plain = hex::decode(golden("udp-server packet")).unwrap();

    let datagram = datagrams::encrypt_for_server_with(&plain, 0xDEAD_BEEF, 0x42, key_part("9abc"));

    assert_eq!(hex::encode(datagram), golden("udp-server datagram"));
}

#[test]
fn should_refuse_a_kad_datagram_without_a_key() {
    let (_, plain) = packet(protocol::KAD, 0x11, 25);

    let result = datagrams::encrypt_for_kad(&plain, Some(&[0; 16]), 0, 6, &mut rand::rng());

    assert!(result.is_err());
}

#[test]
fn should_decode_a_datagram_keyed_by_the_user_hash() {
    let (packet, plain) = packet(protocol::EMULE, 0x90, 16);
    let datagram = datagrams::encrypt_for_client(&plain, &USER_HASH, SENDER_IP, &mut rand::rng());

    let received = from_client(&datagram).unwrap();

    assert_eq!(received.kind, Kind::Ed2k);
    assert_eq!(received.packet, packet);
}

#[test]
fn should_decode_a_datagram_keyed_by_the_kad_id() {
    let (packet, plain) = packet(protocol::KAD, 0x11, 25);
    let datagram = datagrams::encrypt_for_kad(&plain, Some(&KAD_ID), 5, 6, &mut rand::rng()).unwrap();

    let received = from_client(&datagram).unwrap();

    assert_eq!(received.kind, Kind::KadIdKey);
    assert_eq!(received.packet, packet);
    assert_eq!((received.receiver_verify_key, received.sender_verify_key), (5, 6));
}

#[test]
fn should_decode_a_datagram_keyed_by_the_verify_key() {
    let (packet, plain) = packet(protocol::KAD, 0x11, 25);
    let datagram = datagrams::encrypt_for_kad(&plain, None, 7, 8, &mut rand::rng()).unwrap();

    let received = from_client(&datagram).unwrap();

    assert_eq!(received.kind, Kind::KadVerifyKey);
    assert_eq!(received.packet, packet);
}

/// Each round has a datagram keyed by the user hash, by the Kad id, by the verify key and one in clear.
fn datagrams_of_every_key(rounds: usize) -> Vec<(Packet, Kind, Vec<u8>)> {
    let mut random = rand::rng();
    let mut all = Vec::new();
    for round in 0..rounds {
        for kind in [Kind::Ed2k, Kind::KadIdKey, Kind::KadVerifyKey, Kind::Plain] {
            let protocol = if matches!(kind, Kind::Ed2k | Kind::Plain) { protocol::EMULE } else { protocol::KAD };
            let (packet, plain) = packet(protocol, 0x11, round);
            let datagram = match kind {
                Kind::Ed2k => datagrams::encrypt_for_client(&plain, &USER_HASH, SENDER_IP, &mut random),
                Kind::KadIdKey => datagrams::encrypt_for_kad(&plain, Some(&KAD_ID), 5, 6, &mut random).unwrap(),
                Kind::KadVerifyKey => datagrams::encrypt_for_kad(&plain, None, 7, 8, &mut random).unwrap(),
                _ => plain,
            };
            all.push((packet, kind, datagram));
        }
    }
    all
}

fn decode_on_threads(sent: &[(Packet, Kind, Vec<u8>)], threads: usize) {
    thread::scope(|scope| {
        let decoders: Vec<_> = (0..threads)
            .map(|_| scope.spawn(|| sent.iter().map(|(_, _, d)| from_client(d).unwrap()).collect::<Vec<_>>()))
            .collect();
        for decoder in decoders {
            let decoded = decoder.join().unwrap();
            for ((packet, kind, _), received) in sent.iter().zip(&decoded) {
                assert_eq!((&received.packet, received.kind), (packet, *kind));
            }
            assert_eq!(decoded.len(), sent.len());
        }
    });
}

#[test]
fn should_decode_datagrams_of_every_key_one_after_the_other_on_one_thread() {
    decode_on_threads(&datagrams_of_every_key(5), 1);
}

#[test]
fn should_decode_datagrams_on_many_threads_at_once() {
    decode_on_threads(&datagrams_of_every_key(200), 8);
}

#[test]
fn should_decode_a_plain_datagram() {
    let (packet, plain) = packet(protocol::EMULE, 0x90, 16);

    let received = from_client(&plain).unwrap();

    assert_eq!(received.kind, Kind::Plain);
    assert_eq!(received.packet, packet);
}

#[test]
fn should_add_the_obfuscation_overhead_to_a_datagram_for_the_server() {
    let (_, plain) = packet(protocol::EDONKEY, 0x97, 8);

    let datagram = datagrams::encrypt_for_server(&plain, 99, &mut rand::rng());

    assert_eq!(datagram.len(), udp_framing::HEADER_SIZE + 8 + datagrams::ED2K_OVERHEAD);
}

#[test]
fn should_key_server_answers_the_other_way() {
    let (packet, plain) = packet(protocol::EDONKEY, 0x97, 8);
    let material = [99u32.to_le_bytes().as_slice(), &[datagrams::MAGIC_FROM_SERVER, 1, 2]].concat();
    let mut body = [&[0xD5, 0x24, 0xEF, 0x13, 0x00], plain.as_slice()].concat();
    Rc4::new(&streams::md5(&material)).apply(&mut body);
    let datagram = [&[0x42, 1, 2], body.as_slice()].concat();

    let received = datagrams::decode_from_server(&datagram, 99).unwrap();

    assert_eq!(received.kind, Kind::Server);
    assert_eq!(received.packet, packet);
}

#[test]
fn should_decode_what_the_server_side_sends_and_receives() {
    let (packet, plain) = packet(protocol::EDONKEY, 0x97, 8);
    let to_server = datagrams::encrypt_for_server(&plain, 99, &mut rand::rng());
    let from_server = datagrams::encrypt_from_server(&plain, 99, &mut rand::rng());

    let at_server = datagrams::decode_at_server(&to_server, 99).unwrap();
    let at_client = datagrams::decode_from_server(&from_server, 99).unwrap();

    assert_eq!((at_server.kind, at_server.packet), (Kind::Server, packet.clone()));
    assert_eq!((at_client.kind, at_client.packet), (Kind::Server, packet));
}

#[test]
fn should_refuse_an_obfuscated_datagram_whose_packet_has_no_header() {
    let datagram = datagrams::encrypt_for_client_with(&[protocol::EMULE], &USER_HASH, SENDER_IP, 0x5B, [1, 2]);

    let result = from_client(&datagram);

    assert!(result.is_err());
}

#[test]
fn should_decode_random_datagrams_or_fail_cleanly() {
    let mut random = rand::rng();
    for _ in 0..20_000 {
        let mut data = vec![0; random.random_range(0..=80)];
        random.fill_bytes(&mut data);

        // Malformed input must come back as a DecodeError, never as a panic.
        let _ = datagrams::decode_from_client(&data, &USER_HASH, [0; 4], Some(&USER_HASH), 1);
        let _ = datagrams::decode_from_server(&data, 1);
    }
}

#[test]
fn should_derive_the_kad_verify_key_from_the_address_and_the_udp_key() {
    // Computed by an independent implementation: MD5(<ip big-endian><udp key little-endian>), folded, mod 2^32 - 2, + 1.
    let cases = [(0x1234_5678, 0xC0A8_010A, 0xBD78_53B4), (0, 0, 0x0D05_F875), (0xFFFF_FFFF, 0x7F00_0001, 0x38EA_F5E8)];
    for (udp_key, peer_ip, expected) in cases {
        assert_eq!(datagrams::kad_verify_key(udp_key, peer_ip), expected, "{udp_key:#x} {peer_ip:#x}");
    }
}
