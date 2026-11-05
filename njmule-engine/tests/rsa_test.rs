// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's RsaTest, under their names; the comparisons with the JDK's own RSA are Java only.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::net::{IpAddr, Ipv4Addr};
use std::sync::LazyLock;

use njmule_engine::crypto::rsa::{self, NETWORK_KEY_BITS, PrivateKey};
use njmule_engine::crypto::rsa_keys::{self, MAX_PUBLIC_KEY_SIZE};
use njmule_engine::security::secure_ident::{self, IpBinding, IpKind};
use proptest::prelude::*;

static KEY: LazyLock<PrivateKey> = LazyLock::new(|| rsa::generate(NETWORK_KEY_BITS).unwrap());

fn bound_to(a: u8, b: u8, c: u8, d: u8) -> Option<IpBinding> {
    Some(IpBinding { address: IpAddr::V4(Ipv4Addr::new(a, b, c, d)), kind: IpKind::RemoteClient })
}

fn bound_to_ipv6(address: &str) -> Option<IpBinding> {
    Some(IpBinding { address: address.parse().unwrap(), kind: IpKind::RemoteClient })
}

// A peer signed our public key with its own, bound as given; returns its key, our encoded key and the signature.
fn peer_signed_our_key_for(challenge: u32, binding: Option<&IpBinding>) -> (PrivateKey, Vec<u8>, Vec<u8>) {
    let peer = rsa::generate(NETWORK_KEY_BITS).unwrap();
    let ours = rsa_keys::encode_public(&KEY.public_key());
    let signature = secure_ident::sign(&peer, &ours, challenge, binding).unwrap();
    (peer, ours, signature)
}

// DER of a PKCS#1 RSAPublicKey: a sequence of the two integers, each with a leading zero when its top bit is set.
fn pkcs1_public_key(modulus: &[u8], exponent: &[u8]) -> Vec<u8> {
    let integer = |value: &[u8]| {
        let value: Vec<u8> = value.iter().copied().skip_while(|byte| *byte == 0).collect();
        let mut content = if value.first().is_none_or(|byte| byte & 0x80 != 0) { vec![0] } else { Vec::new() };
        content.extend(value);
        [vec![0x02], length(content.len()), content].concat()
    };
    let content = [integer(modulus), integer(exponent)].concat();
    [vec![0x30], length(content.len()), content].concat()
}

fn length(length: usize) -> Vec<u8> {
    if length < 0x80 { vec![length as u8] } else { vec![0x81, length as u8] }
}

#[test]
fn should_fit_network_keys_in_the_eighty_byte_public_key_limit() {
    let encoded = rsa_keys::encode_public(&KEY.public_key());

    assert_eq!(KEY.public_key().modulus_bits(), 384);
    assert!(encoded.len() <= MAX_PUBLIC_KEY_SIZE, "{} bytes", encoded.len());
    assert_eq!(rsa_keys::decode_public(&encoded).unwrap(), KEY.public_key());
}

#[test]
fn should_make_signatures_that_verify() {
    let message = b"public key + challenge";

    let signature = rsa::sign(&KEY, message).unwrap();

    assert_eq!(signature.len(), 48);
    assert!(rsa::verify(&KEY.public_key(), message, &signature));
}

#[test]
fn should_detect_a_tampered_signature() {
    let message = b"public key + challenge";
    let mut signature = rsa::sign(&KEY, message).unwrap();
    signature[10] ^= 1;

    let accepted = rsa::verify(&KEY.public_key(), message, &signature);

    assert!(!accepted);
}

#[test]
fn should_refuse_a_signature_of_the_wrong_size() {
    let accepted = rsa::verify(&KEY.public_key(), b"public key + challenge", &[0; 47]);

    assert!(!accepted);
}

#[test]
fn should_round_trip_key_files() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("cryptkey.dat");

    rsa_keys::write_key_file(&file, &KEY).unwrap();

    let text = std::fs::read_to_string(&file).unwrap();
    let lines: Vec<&str> = text.lines().collect();
    assert!(lines.iter().all(|line| line.len() <= 72));
    assert!(lines[..lines.len() - 1].iter().all(|line| line.len() == 72));
    assert!(text.ends_with('\n'));
    assert_eq!(rsa_keys::read_key_file(&file).unwrap(), *KEY);
}

#[test]
fn should_accept_a_secure_ident_signature_of_the_same_challenge_and_address() {
    let (peer, ours, signature) = peer_signed_our_key_for(0x1234_5678, bound_to(10, 0, 0, 1).as_ref());

    let accepted =
        secure_ident::verify(&peer.public_key(), &signature, &ours, 0x1234_5678, bound_to(10, 0, 0, 1).as_ref());

    assert!(accepted);
}

#[test]
fn should_refuse_a_secure_ident_signature_for_another_challenge() {
    let (peer, ours, signature) = peer_signed_our_key_for(0x1234_5678, bound_to(10, 0, 0, 1).as_ref());

    let accepted =
        secure_ident::verify(&peer.public_key(), &signature, &ours, 0x1234_5679, bound_to(10, 0, 0, 1).as_ref());

    assert!(!accepted);
}

#[test]
fn should_refuse_a_secure_ident_signature_without_the_address() {
    let (peer, ours, signature) = peer_signed_our_key_for(0x1234_5678, bound_to(10, 0, 0, 1).as_ref());

    let accepted = secure_ident::verify(&peer.public_key(), &signature, &ours, 0x1234_5678, None);

    assert!(!accepted);
}

#[test]
fn should_refuse_a_secure_ident_signature_for_another_address() {
    let (peer, ours, signature) = peer_signed_our_key_for(0x1234_5678, bound_to(10, 0, 0, 1).as_ref());

    let accepted =
        secure_ident::verify(&peer.public_key(), &signature, &ours, 0x1234_5678, bound_to(10, 0, 0, 2).as_ref());

    assert!(!accepted);
}

#[test]
fn should_bind_the_challenge_and_the_address_in_the_secure_ident_message() {
    let message = secure_ident::message(&[7], 0x0102_0304, bound_to(10, 0, 0, 1).as_ref());

    assert_eq!(message, [7, 4, 3, 2, 1, 10, 0, 0, 1, 10]);
}

#[test]
fn should_bind_all_sixteen_bytes_of_an_ipv6_address_in_the_secure_ident_message() {
    let message = secure_ident::message(&[7], 0x0102_0304, bound_to_ipv6("2001:db8::1").as_ref());

    assert_eq!(message, [7, 4, 3, 2, 1, 0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 10]);
}

#[test]
fn should_refuse_a_secure_ident_signature_for_another_ipv6_address() {
    let (peer, ours, signature) = peer_signed_our_key_for(0x1234_5678, bound_to_ipv6("2001:db8::1").as_ref());

    let accepted =
        secure_ident::verify(&peer.public_key(), &signature, &ours, 0x1234_5678, bound_to_ipv6("2001:db8::2").as_ref());

    assert!(!accepted);
}

#[test]
fn should_reject_keys_that_are_not_rsa() {
    let decoded = rsa_keys::decode_public(&[0x30, 0x03, 0x02, 0x01, 0x00]);

    assert!(decoded.is_err());
}

#[test]
fn should_reject_public_keys_the_signature_check_cannot_use() {
    use num_bigint_dig::RandPrime;
    let prime = ::rsa::rand_core::OsRng.gen_prime(384).to_bytes_be();
    let modulus = KEY.public_key().modulus();
    let unusable: [(&[u8], &[u8]); 4] =
        [(&modulus, &[0x01, 0x00, 0x00]), (&[15], &[0x01, 0x00, 0x01]), (&prime, &[0x01, 0x00, 0x01]), (&[1], &[3])];

    for (modulus, exponent) in unusable {
        let decoded = rsa_keys::decode_public(&pkcs1_public_key(modulus, exponent));

        assert!(decoded.is_err(), "{modulus:02x?} {exponent:02x?}");
    }
}

#[test]
fn should_refuse_forged_signatures_of_any_shape_without_an_error() {
    let size = KEY.public_key().size_bytes();
    let modulus = KEY.public_key().modulus();
    // The modulus as a signed big-endian number, as other implementations write it.
    let signed_modulus = if modulus[0] & 0x80 != 0 { [vec![0], modulus].concat() } else { modulus };
    let forged = [vec![0xFF; size], vec![0; size], vec![0; size + 1], Vec::new(), signed_modulus];

    for signature in forged {
        assert!(!rsa::verify(&KEY.public_key(), &[1, 2, 3], &signature), "{} bytes", signature.len());
    }
}

proptest! {
    #[test]
    fn should_always_verify_with_decoded_keys_without_throwing(
        modulus in proptest::collection::vec(any::<u8>(), 1..=48),
        exponent in 1u32..=i32::MAX as u32,
    ) {
        let der = pkcs1_public_key(&modulus, &exponent.to_be_bytes());

        if let Ok(key) = rsa_keys::decode_public(&der) {
            prop_assert!(!rsa::verify(&key, &der, &vec![0; key.size_bytes()]));
        }
    }

    #[test]
    fn should_only_fail_with_decode_exception_for_garbage_public_keys(data in proptest::collection::vec(any::<u8>(), 0..=120)) {
        let _ = rsa_keys::decode_public(&data);
    }

    #[test]
    fn should_only_fail_with_decode_exception_for_garbage_private_keys(data in proptest::collection::vec(any::<u8>(), 0..=120)) {
        let _ = rsa_keys::decode_private(&data);
    }
}
