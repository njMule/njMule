// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's TlsIdentityTest, under its names.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use njmule_engine::quic::tls_identity::TlsIdentity;
use x509_parser::oid_registry::OID_KEY_TYPE_EC_PUBLIC_KEY;
use x509_parser::prelude::{FromDer, X509Certificate};

// 2026-10-19T08:00:00Z.
const NOW_SECONDS: u64 = 1_792_396_800;

#[test]
fn should_sign_its_certificate_with_its_own_key() {
    let mut test = Identity::default();

    test.when_an_identity_is_made_at(now());

    test.then_the_certificate_verifies_with_its_own_key();
    test.then_the_certificate_is_issued_by_its_subject();
}

#[test]
fn should_be_valid_from_an_hour_before_for_five_years() {
    let mut test = Identity::default();

    test.when_an_identity_is_made_at(now());

    test.then_the_certificate_is_valid_between(NOW_SECONDS - 3_600, NOW_SECONDS + 5 * 365 * 24 * 3_600);
}

#[derive(Default)]
struct Identity {
    certificate: Vec<u8>,
}

impl Identity {
    fn when_an_identity_is_made_at(&mut self, now: SystemTime) {
        self.certificate = TlsIdentity::self_signed(now).unwrap().certificate().to_vec();
    }

    fn then_the_certificate_verifies_with_its_own_key(&self) {
        let certificate = self.parsed();
        certificate.verify_signature(None).expect("signed with its own key");
        assert_eq!(certificate.public_key().algorithm.algorithm, OID_KEY_TYPE_EC_PUBLIC_KEY);
    }

    fn then_the_certificate_is_issued_by_its_subject(&self) {
        let certificate = self.parsed();
        assert_eq!(certificate.issuer(), certificate.subject());
        assert_eq!(certificate.subject().to_string(), "CN=njMule QUIC");
    }

    fn then_the_certificate_is_valid_between(&self, from: u64, to: u64) {
        let validity = self.parsed().validity().clone();
        assert_eq!(validity.not_before.timestamp(), i64::try_from(from).unwrap());
        assert_eq!(validity.not_after.timestamp(), i64::try_from(to).unwrap());
    }

    fn parsed(&self) -> X509Certificate<'_> {
        X509Certificate::from_der(&self.certificate).unwrap().1
    }
}

fn now() -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(NOW_SECONDS)
}
