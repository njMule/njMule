// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's MinisignTest, under its names. The verifier does not tell a changed file from a changed trusted
//! comment: both fail as a signature that does not match.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::update::minisign::{self, MinisignError, PublicKey};
use njmule_engine::update::updates;

use crate::test_signing::TestSigning;

// The public key and the signatures of the 4 bytes "test" that the reference minisign verifier tests itself with.
const REFERENCE_KEY: &str = "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3";
const REFERENCE_HASHED: &str = "untrusted comment: signature from minisign secret key
RUQf6LRCGA9i559r3g7V1qNyJDApGip8MfqcadIgT9CuhV3EMhHoN1mGTkUidF/z7SrlQgXdy8ofjb7bNJJylDOocrCo8KLzZwo=
trusted comment: timestamp:1556193335\tfile:test
y/rUw2y8/hOUYjZU71eHp/Wo1KZ40fGy2VJEDl34XMJM+TX48Ss/17u3IvIfbVR1FkZZSNCisQbuQY+bHwhEBg==
";
const REFERENCE_LEGACY: &str = "untrusted comment: signature from minisign secret key
RWQf6LRCGA9i59SLOFxz6NxvASXDJeRtuZykwQepbDEGt87ig1BNpWaVWuNrm73YiIiJbq71Wi+dP9eKL8OC351vwIasSSbXxwA=
trusted comment: timestamp:1555779966\tfile:test
QtKMXWyYcwdpZAlPF7tE2ENJkRd1ujvKjlj1m9RtHTBnZPa5WKU5uWRs5GoP5M/VqE81QFuMKI5k/SfNQUaOAA==
";
// The release keys of keys.pub by the ids signatures name them by; minisign prints these ids byte-reversed.
const SIGNING_KEY_ID: &str = "2aa526fd33b0b09e";
const STANDBY_KEY_ID: &str = "5019b4faebd5518e";

#[test]
fn should_accept_a_signature_made_by_minisign() {
    let mut test = Verifying::default();
    test.given_the_keys(REFERENCE_KEY);
    test.given_the_signature(REFERENCE_HASHED);

    test.when_verifying("test");

    test.then_no_exception_occurred();
    test.then_the_trusted_comment_is("timestamp:1556193335\tfile:test");
}

#[test]
fn should_refuse_a_file_changed_since_it_was_signed() {
    let mut test = Verifying::default();
    test.given_the_keys(REFERENCE_KEY);
    test.given_the_signature(REFERENCE_HASHED);

    test.when_verifying("Test");

    test.then_exception_occurred("does not match");
}

#[test]
fn should_refuse_a_legacy_signature() {
    let mut test = Verifying::default();
    test.given_the_keys(REFERENCE_KEY);
    test.given_the_signature(REFERENCE_LEGACY);

    test.when_verifying("test");

    test.then_exception_occurred("legacy");
}

#[test]
fn should_refuse_a_changed_trusted_comment() {
    let mut test = Verifying::default();
    test.given_the_keys(REFERENCE_KEY);
    test.given_the_signature(&REFERENCE_HASHED.replace("timestamp:1556193335", "timestamp:1556193336"));

    test.when_verifying("test");

    test.then_exception_occurred("does not match");
}

#[test]
fn should_refuse_a_signature_of_a_key_not_built_in() {
    let mut test = Verifying::default();
    test.given_the_keys(&TestSigning::new().public_key_line());
    test.given_the_signature(REFERENCE_HASHED);

    test.when_verifying("test");

    test.then_exception_occurred("unknown key");
}

#[test]
fn should_refuse_text_that_is_no_signature() {
    let mut test = Verifying::default();
    test.given_the_keys(REFERENCE_KEY);
    test.given_the_signature("<html>Not Found</html>");

    test.when_verifying("test");

    test.then_exception_occurred("not a minisign signature");
}

#[test]
fn should_accept_a_signature_of_any_of_the_keys() {
    let mut test = Verifying::default();
    let standby = TestSigning::new();
    test.given_the_keys(&key_file(&[&TestSigning::new(), &standby]));
    test.given_the_signature(&standby.sign(b"the manifest"));

    test.when_verifying("the manifest");

    test.then_no_exception_occurred();
}

#[test]
fn should_read_every_key_of_a_key_file() {
    let mut test = Verifying::default();

    test.when_the_keys_are_read(&key_file(&[&TestSigning::new(), &TestSigning::new()]));

    test.then_no_exception_occurred();
    test.then_there_are_keys(2);
}

#[test]
fn should_refuse_a_line_that_is_no_public_key() {
    let mut test = Verifying::default();

    test.when_the_keys_are_read("untrusted comment: minisign public key\nRWQnotakey\n");

    test.then_exception_occurred("");
}

#[test]
fn should_build_in_the_signing_key_followed_by_the_standby_key() {
    let mut test = Verifying::default();

    test.when_the_built_in_keys_are_read();

    test.then_no_exception_occurred();
    test.then_the_key_ids_are(&[SIGNING_KEY_ID, STANDBY_KEY_ID]);
}

#[derive(Default)]
struct Verifying {
    keys: Vec<PublicKey>,
    signature: String,
    trusted_comment: Option<String>,
    failure: Option<MinisignError>,
}

impl Verifying {
    fn given_the_keys(&mut self, text: &str) {
        self.keys = minisign::public_keys(text).unwrap();
    }

    fn given_the_signature(&mut self, text: &str) {
        self.signature = text.to_owned();
    }

    fn when_verifying(&mut self, data: &str) {
        let verified = minisign::signature(&self.signature)
            .and_then(|signature| minisign::verify(data.as_bytes(), &signature, &self.keys));
        match verified {
            Ok(comment) => self.trusted_comment = Some(comment),
            Err(e) => self.failure = Some(e),
        }
    }

    fn when_the_keys_are_read(&mut self, text: &str) {
        match minisign::public_keys(text) {
            Ok(keys) => self.keys = keys,
            Err(e) => self.failure = Some(e),
        }
    }

    fn when_the_built_in_keys_are_read(&mut self) {
        self.keys = updates::shipped_keys();
    }

    fn then_no_exception_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_exception_occurred(&self, message: &str) {
        let failure = self.failure.as_ref().expect("a failure");
        assert!(failure.to_string().contains(message), "{failure}");
    }

    fn then_the_trusted_comment_is(&self, expected: &str) {
        assert_eq!(self.trusted_comment.as_deref(), Some(expected));
    }

    fn then_there_are_keys(&self, count: usize) {
        assert_eq!(self.keys.len(), count);
    }

    fn then_the_key_ids_are(&self, ids: &[&str]) {
        assert_eq!(self.keys.iter().map(|key| key.id.as_str()).collect::<Vec<_>>(), ids);
    }
}

fn key_file(signers: &[&TestSigning]) -> String {
    signers
        .iter()
        .map(|signer| format!("untrusted comment: minisign public key\n{}\n", signer.public_key_line()))
        .collect()
}
