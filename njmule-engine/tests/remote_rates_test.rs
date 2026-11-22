// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `org.njmule.app.RemoteRatesTest`, plus live round-trips of `RemoteControl` and `ApiClient`.

#![allow(clippy::unwrap_used)]

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use njmule_engine::app::remote_control::{self, RemoteControl, RemoteError};
use njmule_engine::remote::api_client::ApiClient;
use njmule_engine::remote::control_endpoint::ControlEndpoint;

use crate::fake_engine::FakeEngine;

#[test]
fn a_rate_is_typed_in_ki_b_or_with_a_unit() {
    let cases = [
        ("50", 50),
        ("0", 0),
        ("500K", 500),
        ("500k", 500),
        ("2M", 2048),
        ("1.5M", 1536),
        ("1G", 1_048_576),
        (" 3m ", 3072),
    ];
    for (typed, kib) in cases {
        let mut test = Scenario::new();

        test.when_typed(typed);

        test.then_the_rate_is(kib);
    }
}

#[test]
fn anything_else_is_no_rate() {
    for typed in ["-5", "1.5", "fast", "M", "2X", ""] {
        let mut test = Scenario::new();

        test.when_typed(typed);

        test.then_it_was_refused();
    }
}

#[test]
fn a_limit_reads_in_the_unit_that_suits_it() {
    let cases = [(0, "unlimited"), (50, "50 KiB/s"), (1536, "1.50 MiB/s"), (1_153_434, "1.10 GiB/s")];
    for (kib, text) in cases {
        let mut test = Scenario::new();

        test.when_the_limit_is_written(kib);

        test.then_it_reads(text);
    }
}

#[tokio::test]
async fn should_drive_the_remote_api_through_remote_control() {
    let dir = tempfile::tempdir().unwrap();
    let engine = FakeEngine::new(dir.path());
    engine.with(|fake| fake.connected = Some(true));
    let stopped = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&stopped);
    let endpoint =
        ControlEndpoint::start(engine, Some(Arc::new(move || flag.store(true, Ordering::Relaxed)))).await.unwrap();
    let info: serde_json::Value =
        serde_json::from_str(endpoint.control_line().strip_prefix("njmule-control ").unwrap()).unwrap();
    let api = ApiClient::new(
        info.get("url").and_then(serde_json::Value::as_str).unwrap(),
        info.get("token").and_then(serde_json::Value::as_str).unwrap(),
    );

    let mut out = Vec::new();
    RemoteControl::new(&api, &mut out, Duration::from_secs(1)).run(&["status".to_owned()]).await.unwrap();
    let status = String::from_utf8_lossy(&out);
    assert!(status.contains("eD2K:"), "{status}");
    assert!(status.contains("Downloads:"), "{status}");

    out.clear();
    RemoteControl::new(&api, &mut out, Duration::from_secs(1))
        .run(&["limits".to_owned(), "200".to_owned(), "1.5M".to_owned()])
        .await
        .unwrap();
    let limits = String::from_utf8_lossy(&out);
    assert!(limits.contains("Upload:   200 KiB/s"), "{limits}");
    assert!(limits.contains("Download: 1.50 MiB/s"), "{limits}");

    out.clear();
    RemoteControl::new(&api, &mut out, Duration::from_secs(1)).run(&["shutdown".to_owned()]).await.unwrap();
    assert!(String::from_utf8_lossy(&out).contains("saving and exiting"));
    assert!(stopped.load(Ordering::Relaxed));

    endpoint.stop().await;
}

struct Scenario {
    rate: Option<i64>,
    limit: String,
    failure: Option<RemoteError>,
}

impl Scenario {
    fn new() -> Self {
        Self { rate: None, limit: String::new(), failure: None }
    }

    fn when_typed(&mut self, typed: &str) {
        match remote_control::rate(typed) {
            Ok(rate) => self.rate = Some(rate),
            Err(e) => self.failure = Some(e),
        }
    }

    fn when_the_limit_is_written(&mut self, kib: i64) {
        self.limit = remote_control::limit(kib);
    }

    fn then_the_rate_is(&self, kib: i64) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
        assert_eq!(self.rate, Some(kib));
    }

    fn then_it_was_refused(&self) {
        assert!(matches!(self.failure, Some(RemoteError::Usage(_))), "{:?}", self.failure);
    }

    fn then_it_reads(&self, text: &str) {
        assert_eq!(self.limit, text);
    }
}
