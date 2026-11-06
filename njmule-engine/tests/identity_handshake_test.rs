// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's IdentityHandshakeTest, under its names: two peers proving each other the user hashes they show.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::VecDeque;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::{Arc, LazyLock};

use njmule_engine::crypto::rsa::{self, NETWORK_KEY_BITS};
use njmule_engine::peer::identities::{self, Identities, Trust};
use njmule_engine::peer::identity_handshake::{Identity, IdentityHandshake, Outcome};
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::extended;
use njmule_engine::protocol::packet::{Packet, protocol};
use njmule_engine::security::secure_ident::{self, IpBinding, IpKind};
use rand::SeedableRng;
use rand::rngs::StdRng;

const HASH_A: [u8; 16] = [0xAA; 16];
const HASH_B: [u8; 16] = [0xBB; 16];
const IP_A: IpAddr = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 1));
const IP_B: IpAddr = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2));

// Keys take a moment to make: the tests share them.
static A: LazyLock<Arc<Identity>> = LazyLock::new(|| Arc::new(Identity::of(rsa::generate(NETWORK_KEY_BITS).unwrap())));
static B: LazyLock<Arc<Identity>> = LazyLock::new(|| Arc::new(Identity::of(rsa::generate(NETWORK_KEY_BITS).unwrap())));
static STRANGER: LazyLock<Identity> = LazyLock::new(|| Identity::of(rsa::generate(NETWORK_KEY_BITS).unwrap()));

fn random() -> StdRng {
    StdRng::from_rng(&mut rand::rng())
}

fn hello(user_hash: [u8; 16], versions: u32) -> Hello {
    let ours = Ours { nick: "peer", udp_port: 0, kad_port: 0, misc_options1: versions << 16, misc_options2: 0 };
    Hello::of(user_hash, 0, 4662, &ours, 0, 0)
}

/// Two peers on the addresses of a connection, each with what it knows.
struct Meeting {
    versions_a: u32,
    versions_b: u32,
    a_pins_b: Option<Vec<u8>>,
    b_pins_a: Option<Vec<u8>>,
    // Both with HighIDs unless a test says otherwise.
    public_a: Option<Ipv4Addr>,
    public_b: Option<Ipv4Addr>,
    address_a: IpAddr,
    address_b: IpAddr,
    results: Vec<String>,
    from_a: Vec<Packet>,
    from_b: Vec<Packet>,
}

impl Meeting {
    fn new(versions_a: u32, versions_b: u32) -> Self {
        Self {
            versions_a,
            versions_b,
            a_pins_b: None,
            b_pins_a: None,
            public_a: Some(Ipv4Addr::new(10, 0, 0, 1)),
            public_b: Some(Ipv4Addr::new(10, 0, 0, 2)),
            address_a: IP_A,
            address_b: IP_B,
            results: Vec::new(),
            from_a: Vec::new(),
            from_b: Vec::new(),
        }
    }

    // A and B delivering each other's packets until neither has any.
    fn shake_hands(&mut self) {
        let mut at_a = IdentityHandshake::new(
            Arc::clone(&A),
            &hello(HASH_B, self.versions_b),
            self.address_b,
            self.a_pins_b.as_deref(),
            self.public_a,
            Some(self.address_a),
        );
        let mut at_b = IdentityHandshake::new(
            Arc::clone(&B),
            &hello(HASH_A, self.versions_a),
            self.address_a,
            self.b_pins_a.as_deref(),
            self.public_b,
            Some(self.address_b),
        );
        let mut to_b: VecDeque<Packet> = at_a.start(&mut random()).into();
        let mut to_a: VecDeque<Packet> = at_b.start(&mut random()).into();
        self.from_a.extend(to_b.iter().cloned());
        self.from_b.extend(to_a.iter().cloned());
        for _ in 0..10 {
            if to_a.is_empty() && to_b.is_empty() {
                break;
            }
            while let Some(packet) = to_a.pop_front() {
                let handled = at_a.received(&packet).unwrap().expect("a packet of the identification");
                self.from_a.extend(handled.send.iter().cloned());
                to_b.extend(handled.send);
                self.note("A", handled.outcome);
            }
            while let Some(packet) = to_b.pop_front() {
                let handled = at_b.received(&packet).unwrap().expect("a packet of the identification");
                self.from_b.extend(handled.send.iter().cloned());
                to_a.extend(handled.send);
                self.note("B", handled.outcome);
            }
        }
    }

    fn note(&mut self, side: &str, outcome: Option<Outcome>) {
        match outcome {
            Some(Outcome::Identified { user_hash, .. }) => {
                self.results.push(format!("{side} identified {user_hash:02x?}"))
            }
            Some(Outcome::Failed { user_hash }) => self.results.push(format!("{side} failed {user_hash:02x?}")),
            None => {}
        }
    }

    fn both_identified(&self) {
        let mut results = self.results.clone();
        results.sort();
        assert_eq!(results, [format!("A identified {HASH_B:02x?}"), format!("B identified {HASH_A:02x?}")]);
    }
}

// The address kind of the signature among `sent`, none for a version 1 one.
fn signature_kind(sent: &[Packet]) -> Option<u8> {
    let signature = sent.iter().find(|packet| packet.opcode() == extended::SIGNATURE).expect("a signature");
    let mut input = signature.reader();
    let length = usize::from(input.u8().unwrap());
    input.skip(length).unwrap();
    input.u8().ok()
}

#[test]
fn should_let_strangers_exchange_keys_and_signatures() {
    let mut meeting = Meeting::new(3, 3);

    meeting.shake_hands();

    meeting.both_identified();
}

#[test]
fn should_need_only_the_signature_of_a_pinned_key() {
    let mut meeting = Meeting::new(3, 3);
    meeting.a_pins_b = Some(B.public_key().to_vec());
    meeting.b_pins_a = Some(A.public_key().to_vec());

    meeting.shake_hands();

    meeting.both_identified();
}

// A signs over the key it pinned, which is not B's: both checks fail.
#[test]
fn should_fail_on_a_key_other_than_the_pinned_one() {
    let mut meeting = Meeting::new(3, 3);
    meeting.a_pins_b = Some(STRANGER.public_key().to_vec());

    meeting.shake_hands();

    let mut results = meeting.results.clone();
    results.sort();
    assert_eq!(results, [format!("A failed {HASH_B:02x?}"), format!("B failed {HASH_A:02x?}")]);
}

#[test]
fn should_give_address_bound_signatures_to_peers_without_version_one() {
    let mut meeting = Meeting::new(2, 3);

    meeting.shake_hands();

    meeting.both_identified();
}

#[test]
fn should_leave_alone_peers_that_do_not_take_part() {
    let mut handshake = IdentityHandshake::new(Arc::clone(&A), &hello(HASH_B, 0), IP_B, None, None, Some(IP_A));

    let sent = handshake.start(&mut random());

    assert!(!handshake.is_supported());
    assert!(sent.is_empty());
}

#[test]
fn should_bind_the_signature_to_our_address_when_we_know_it_for_peers_taking_both_versions() {
    let mut meeting = Meeting::new(3, 3);

    meeting.shake_hands();

    meeting.both_identified();
    assert_eq!(signature_kind(&meeting.from_a), Some(IpKind::LocalClient.code()));
    assert_eq!(signature_kind(&meeting.from_b), Some(IpKind::LocalClient.code()));
}

#[test]
fn should_bind_the_signature_to_the_ipv6_address_of_an_ipv6_connection() {
    let mut meeting = Meeting::new(3, 3);
    meeting.address_a = "2001:db8::a".parse().unwrap();
    meeting.address_b = "2001:db8::b".parse().unwrap();

    meeting.shake_hands();

    meeting.both_identified();
    assert_eq!(signature_kind(&meeting.from_a), Some(IpKind::LocalClient.code()));
    assert_eq!(signature_kind(&meeting.from_b), Some(IpKind::LocalClient.code()));
}

#[test]
fn should_give_a_version_one_signature_to_peers_taking_it_without_our_public_address() {
    let mut meeting = Meeting::new(3, 3);
    // A verifier without a HighID may not know the address a signature would be bound to.
    meeting.public_a = None;
    meeting.public_b = None;

    meeting.shake_hands();

    meeting.both_identified();
    assert_eq!(signature_kind(&meeting.from_a), None);
    assert_eq!(signature_kind(&meeting.from_b), None);
}

#[test]
fn should_check_a_signature_bound_to_our_address_with_the_local_one_without_a_public_one() {
    // A takes version 2 only, and neither knows its public address: B binds to A's address as it sees it.
    let mut meeting = Meeting::new(2, 3);
    meeting.public_a = None;
    meeting.public_b = None;

    meeting.shake_hands();

    assert_eq!(signature_kind(&meeting.from_b), Some(IpKind::RemoteClient.code()));
    assert!(meeting.results.contains(&format!("A identified {HASH_B:02x?}")));
}

#[test]
fn should_check_a_signature_bound_to_no_address_as_such() {
    let mut handshake = IdentityHandshake::new(
        Arc::clone(&A),
        &hello(HASH_B, 3),
        IP_B,
        None,
        Some(Ipv4Addr::new(10, 0, 0, 1)),
        Some(IP_A),
    );
    let state = handshake.start(&mut random());
    let mut input = state[0].reader();
    input.u8().unwrap();
    let challenge = input.u32().unwrap();
    let key = B.public_key();
    let unbound = IpBinding { address: IpAddr::V4(Ipv4Addr::UNSPECIFIED), kind: IpKind::None };
    let signature = secure_ident::sign(B.key(), A.public_key(), challenge, Some(&unbound)).unwrap();
    let mut key_packet = PacketWriter::new();
    key_packet.u8(u8::try_from(key.len()).unwrap()).bytes(key);
    let mut signature_packet = PacketWriter::new();
    signature_packet.u8(u8::try_from(signature.len()).unwrap()).bytes(&signature).u8(IpKind::None.code());

    handshake.received(&Packet::written(protocol::EMULE, extended::PUBLICKEY, key_packet)).unwrap();
    let handled = handshake
        .received(&Packet::written(protocol::EMULE, extended::SIGNATURE, signature_packet))
        .unwrap()
        .unwrap();

    assert!(matches!(handled.outcome, Some(Outcome::Identified { user_hash, .. }) if user_hash == HASH_B));
}

// Beyond the Java engine's examples: the trust a connection's session gives, and our key file.

#[test]
fn should_trust_a_peer_that_does_not_take_part_unsecured_and_one_that_does_once_identified() {
    let identities = Identities::new(Identity::of(rsa::generate(NETWORK_KEY_BITS).unwrap()));
    let (silent, sent) = identities.start(&hello(HASH_B, 0), IP_B, Some(IP_A), None, None, &mut random());
    let (taking_part, _) = identities.start(&hello(HASH_B, 3), IP_B, Some(IP_A), None, None, &mut random());

    assert!(sent.is_empty());
    assert_eq!((silent.trust(), silent.is_settled()), (Trust::Unsecured, true));
    assert_eq!(
        (taking_part.trust(), taking_part.is_settled(), taking_part.is_supported()),
        (Trust::Needed, false, true)
    );
    assert!(Trust::Unsecured.credits() && Trust::Identified.credits() && !Trust::Failed.credits());
}

#[test]
fn should_make_our_key_once_and_read_it_again() {
    let dir = tempfile::tempdir().unwrap();

    let made = identities::load_or_create_key(dir.path()).unwrap();
    let read = identities::load_or_create_key(dir.path()).unwrap();

    assert_eq!(Identity::of(made).public_key(), Identity::of(read).public_key());
}

#[test]
fn should_make_a_new_key_when_its_file_cannot_be_read() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join(identities::KEY_FILE), "not a key").unwrap();

    let key = identities::load_or_create_key(dir.path()).unwrap();

    assert_eq!(
        identities::load_or_create_key(dir.path()).map(|again| Identity::of(again).public_key().to_vec()).unwrap(),
        Identity::of(key).public_key()
    );
}

#[test]
fn should_settle_the_trust_of_both_sessions_once_the_signatures_crossed() {
    let ours = Identities::new(Identity::of(A.key().clone()));
    let theirs = Identities::new(Identity::of(B.key().clone()));
    let (mut at_a, sent_a) = ours.start(&hello(HASH_B, 3), IP_B, Some(IP_A), None, None, &mut random());
    let (mut at_b, sent_b) = theirs.start(&hello(HASH_A, 3), IP_A, Some(IP_B), None, None, &mut random());
    let mut to_b: VecDeque<Packet> = sent_a.into();
    let mut to_a: VecDeque<Packet> = sent_b.into();
    let other = Packet::written(protocol::EMULE, extended::EMULEINFO, PacketWriter::new());

    while !(to_a.is_empty() && to_b.is_empty()) {
        while let Some(packet) = to_a.pop_front() {
            to_b.extend(at_a.received(&packet).unwrap().unwrap().send);
        }
        while let Some(packet) = to_b.pop_front() {
            to_a.extend(at_b.received(&packet).unwrap().unwrap().send);
        }
    }

    assert_eq!((at_a.trust(), at_b.trust()), (Trust::Identified, Trust::Identified));
    assert!(at_a.received(&other).unwrap().is_none());
    assert_eq!(ours.public_key(), A.public_key());
}
