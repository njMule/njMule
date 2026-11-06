// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The value types of the preferences, which the Java engine checks through its notifier and network tests: the
//! notifications, the proxy and the update channel.

#![allow(clippy::unwrap_used)]

use std::collections::BTreeSet;

use njmule_engine::config::event_commands::Event;
use njmule_engine::config::notifications::{self, Notifications, SmtpSecurity, WebhookFormat};
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::config::update_channel::UpdateChannel;

fn notifications() -> Notifications {
    Notifications {
        email: true,
        smtp_host: " smtp.example ".to_owned(),
        smtp_port: 465,
        security: SmtpSecurity::Tls,
        smtp_user: " me ".to_owned(),
        smtp_password: " secret ".to_owned(),
        mail_from: "njmule@example.org".to_owned(),
        mail_to: " a@example.org, ,b@example.org ".to_owned(),
        webhook: true,
        webhook_url: " https://ntfy.example/topic ".to_owned(),
        webhook_body: WebhookFormat::Json,
        events: BTreeSet::from([Event::ChatMessage]),
    }
}

#[test]
fn should_strip_the_texts_of_the_notifications_but_the_password() {
    let validated = notifications().validated().unwrap();

    assert_eq!(validated.smtp_host, "smtp.example");
    assert_eq!(validated.smtp_user, "me");
    assert_eq!(validated.smtp_password, " secret ");
    assert_eq!(validated.webhook_url, "https://ntfy.example/topic");
    assert_eq!(validated.recipients(), ["a@example.org", "b@example.org"]);
    assert!(validated.tells(Event::ChatMessage));
    assert!(!validated.tells(Event::DownloadCompleted));
}

#[test]
fn should_refuse_notifications_with_a_wrong_text_or_port() {
    for (wrong, named) in [
        (Notifications { smtp_host: "x".repeat(256), ..notifications() }, "smtpHost"),
        (Notifications { smtp_user: "a\u{7}b".to_owned(), ..notifications() }, "smtpUser"),
        (Notifications { smtp_password: "a\nb".to_owned(), ..notifications() }, "password"),
        (Notifications { smtp_password: "x".repeat(256), ..notifications() }, "password"),
        (Notifications { mail_from: "\u{85}".to_owned(), ..notifications() }, "mailFrom"),
        (Notifications { mail_to: "y".repeat(256), ..notifications() }, "mailTo"),
        (Notifications { smtp_port: 0, ..notifications() }, "smtpPort"),
        (Notifications { webhook_url: "http://ntfy.example/topic".to_owned(), ..notifications() }, "https"),
    ] {
        let refused = wrong.validated().unwrap_err();

        assert!(refused.contains(named), "{refused}");
    }
}

#[test]
fn should_take_only_an_https_address_with_a_host_for_the_web_hook() {
    assert_eq!(notifications::require_webhook_url("  ").unwrap(), "");
    assert_eq!(
        notifications::require_webhook_url(" HTTPS://gotify.example/message ").unwrap(),
        "HTTPS://gotify.example/message"
    );
    for wrong in ["ftp://ntfy.example/topic", "/topic", "https://exa mple/topic", "ntfy.example/topic"] {
        assert!(notifications::require_webhook_url(wrong).is_err(), "{wrong}");
    }
    let too_long = format!("https://ntfy.example/{}", "t".repeat(notifications::MAX_URL));
    assert!(notifications::require_webhook_url(&too_long).unwrap_err().contains("at most"));
}

#[test]
fn should_read_the_events_of_their_ids_in_any_case() {
    let events = notifications::events(" downloadcompleted , CHATMESSAGE,, ").unwrap();

    assert_eq!(events, BTreeSet::from([Event::DownloadCompleted, Event::ChatMessage]));
    assert_eq!(notifications::events("downloadCompleted,somethingElse").unwrap_err(), "unknown event: somethingElse");
}

#[test]
fn should_never_show_the_passwords_in_a_log() {
    let proxy = ProxySettings {
        kind: ProxyType::Socks,
        host: "proxy.lan".to_owned(),
        port: 1080,
        user: "me".to_owned(),
        password: "hunter2".to_owned(),
    };

    let shown = format!("{proxy:?} {:?}", notifications());

    assert!(!shown.contains("hunter2") && !shown.contains("secret"), "{shown}");
    assert!(shown.contains("***"), "{shown}");
    assert!(!format!("{:?}", ProxySettings::default()).contains("***"));
}

#[test]
fn should_refuse_a_proxy_without_host_or_port() {
    let socks = ProxySettings { kind: ProxyType::Socks, host: " proxy.lan ".to_owned(), ..ProxySettings::default() };

    assert_eq!(socks.clone().validated().unwrap().host, "proxy.lan");
    assert!(socks.clone().validated().unwrap().is_enabled());
    assert!(!ProxySettings::default().is_enabled());
    assert_eq!(
        ProxySettings { host: " ".to_owned(), ..socks.clone() }.validated().unwrap_err(),
        "a proxy needs a host"
    );
    assert!(ProxySettings { port: 0, ..socks }.validated().unwrap_err().contains("port"));
    assert_eq!(ProxyType::Http.name(), "HTTP");
}

#[test]
fn should_follow_the_test_versions_from_a_test_version() {
    for (version, channel) in [
        ("0.3.1", UpdateChannel::Stable),
        ("1.0.0+build.5", UpdateChannel::Stable),
        ("0.4.0-alpha.1", UpdateChannel::Prerelease),
        ("1.0.0-rc.2", UpdateChannel::Prerelease),
        ("v1.0.0", UpdateChannel::Prerelease),
        ("dev", UpdateChannel::Prerelease),
    ] {
        assert_eq!(UpdateChannel::of(version), channel, "{version}");
    }
}
