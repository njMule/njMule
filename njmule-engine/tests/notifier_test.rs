// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NotifierTest, under its names: engine events told by e-mail and through a web hook.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use std::collections::BTreeSet;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Waker};
use std::time::Duration;

use futures_util::future::BoxFuture;
use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use njmule_engine::api::engine_event::EngineEvent;
use njmule_engine::api::notification_result::NotificationResult;
use njmule_engine::config::bind_interface::Binding;
use njmule_engine::config::event_commands::Event;
use njmule_engine::config::notifications::{DEFAULT_SMTP_PORT, Notifications, SmtpSecurity, WebhookFormat};
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::engine::notifier::{self, BURST, Notifier};
use njmule_engine::files::part_file::PartMap;
use njmule_engine::net::http_route;
use njmule_engine::net::routes::Routes;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

const HASH: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef];
const NAME: &str = "debian.iso";

#[test]
fn should_tell_a_completed_download_through_each_channel_on() {
    let mut test = Telling::new();

    test.when_told(&completed());

    test.then_the_mails_are(&["njMule: Download completed|debian.iso"]);
    test.then_the_hook_calls_are(&["njMule: Download completed|debian.iso"]);
}

#[test]
fn should_tell_nothing_of_an_event_not_chosen() {
    let mut test = Telling::new();

    test.when_told(&message());

    test.then_the_mails_are(&[]);
    test.then_the_hook_calls_are(&[]);
}

#[test]
fn should_tell_only_through_the_channels_on() {
    let mut test = Telling::new();
    test.given_the_settings(on(false, true, &[Event::ChatMessage]));

    test.when_told(&message());

    test.then_the_mails_are(&[]);
    test.then_the_hook_calls_are(&["njMule: Chat message|ann:\nhello"]);
}

#[test]
fn should_drop_the_messages_beyond_the_burst() {
    let mut test = Telling::new();

    test.when_told_times(&completed(), BURST + 3);

    test.then_the_hook_was_called(BURST);
}

#[tokio::test]
async fn should_tell_how_the_test_went_on_each_channel() {
    let mut test = Telling::new();
    test.given_the_mail_fails("the server refused the password");

    test.when_tested().await;

    test.then_the_results_are(&[result("email", Some("the server refused the password")), result("webhook", None)]);
}

#[tokio::test]
async fn should_never_send_the_mail_in_the_clear_to_a_server_without_start_tls() {
    let mut test = Telling::new();
    test.given_an_smtp_server_without_start_tls().await;

    test.when_mailed().await;

    test.then_it_failed_for("STARTTLS");
    test.then_no_message_was_sent();
    test.then_the_greeting_named_no_computer();
}

#[tokio::test]
async fn should_call_the_web_hook_through_the_http_proxy_of_the_preferences() {
    let mut test = Telling::new();
    test.given_an_http_proxy_of_the_preferences().await;
    test.given_the_settings(channels(false, 25, true, "https://192.0.2.80/notify"));

    test.when_tested_for_real().await;

    test.then_the_proxy_saw(&["CONNECT 192.0.2.80:443 HTTP/1.1"]);
}

#[tokio::test]
async fn should_send_the_mail_through_the_socks_proxy_of_the_preferences_with_its_credentials() {
    let mut test = Telling::new();
    test.given_a_socks_proxy_of_the_preferences_before_an_smtp_server_without_start_tls("me", "secret").await;

    test.when_mailed_to("192.0.2.25", 587).await;

    test.then_it_failed_for("STARTTLS");
    test.then_the_proxy_saw(&["auth me:secret", "connect 192.0.2.25:587"]);
    test.then_no_message_was_sent();
}

#[tokio::test]
async fn should_send_the_mail_through_the_http_proxy_of_the_preferences() {
    let mut test = Telling::new();
    test.given_an_http_proxy_of_the_preferences_before_an_smtp_server_without_start_tls().await;

    test.when_mailed_to("192.0.2.25", 587).await;

    test.then_it_failed_for("STARTTLS");
    test.then_the_proxy_saw(&["CONNECT 192.0.2.25:587 HTTP/1.1"]);
}

#[tokio::test]
async fn should_send_the_mail_from_the_chosen_interface_only() {
    let mut test = Telling::new();
    test.given_an_smtp_server_without_start_tls().await;
    test.given_the_chosen_interface_has_an_address_this_computer_lacks();

    test.when_mailed().await;

    test.then_the_mail_failed();
    test.then_nothing_reached_the_server();
}

#[tokio::test]
async fn should_post_the_text_with_its_title_to_the_web_hook() {
    let mut test = Telling::new();
    test.given_a_web_hook_answering(200).await;

    test.when_posted_as(WebhookFormat::Text).await;

    test.then_no_error_occurred();
    test.then_the_web_hook_got(&["POST /notify HTTP/1.1", "title: njMule: Test", "content-type: text/plain", "test"]);
}

#[tokio::test]
async fn should_post_the_title_and_the_message_as_json() {
    let mut test = Telling::new();
    test.given_a_web_hook_answering(200).await;

    test.when_posted_as(WebhookFormat::Json).await;

    test.then_no_error_occurred();
    test.then_the_web_hook_got(&["application/json", r#""title":"njMule: Test""#, r#""message":"test""#]);
}

#[tokio::test]
async fn should_ask_for_the_address_of_the_web_hook() {
    let mut test = Telling::new();
    test.given_the_settings(channels(false, 25, true, ""));

    test.when_tested_for_real().await;

    test.then_the_results_are(&[result("webhook", Some("Set the address of the web hook."))]);
}

#[tokio::test]
async fn should_fail_on_a_web_hook_that_answers_an_error() {
    let mut test = Telling::new();
    test.given_a_web_hook_answering(500).await;

    test.when_posted_as(WebhookFormat::Text).await;

    test.then_it_failed_for("answered HTTP 500");
}

#[tokio::test]
async fn should_send_nothing_while_the_chosen_interface_is_gone() {
    let mut test = Telling::new();
    test.given_an_smtp_server_without_start_tls().await;
    test.given_the_chosen_interface_is_gone("wg0");
    let port = test.smtp_port();
    test.given_the_settings(channels(true, port, true, &format!("https://127.0.0.1:{port}")));

    test.when_tested_for_real().await;

    test.then_each_result_failed_for("wg0");
    test.then_nothing_reached_the_server();
}

/// What the servers, the proxy and the web hook of a test saw.
#[derive(Default)]
struct Seen {
    data_sent: AtomicBool,
    reached: AtomicBool,
    greetings: Mutex<Vec<String>>,
    proxy: Mutex<Vec<String>>,
    hook: Mutex<Vec<String>>,
}

struct Telling {
    settings: Notifications,
    mail_failure: Option<String>,
    mailed: Arc<Mutex<Vec<String>>>,
    hooked: Arc<Mutex<Vec<String>>>,
    proxy: ProxySettings,
    binding: Binding,
    smtp: Option<SocketAddr>,
    hook: Option<SocketAddr>,
    seen: Arc<Seen>,
    results: Vec<NotificationResult>,
    outcome: Option<Result<(), String>>,
}

impl Telling {
    fn new() -> Self {
        Self {
            settings: on(true, true, &[]),
            mail_failure: None,
            mailed: Arc::default(),
            hooked: Arc::default(),
            proxy: ProxySettings::default(),
            binding: Binding::Any,
            smtp: None,
            hook: None,
            seen: Arc::default(),
            results: Vec::new(),
            outcome: None,
        }
    }

    fn given_the_settings(&mut self, settings: Notifications) {
        self.settings = settings;
    }

    fn given_the_mail_fails(&mut self, why: &str) {
        self.mail_failure = Some(why.to_owned());
    }

    async fn given_an_smtp_server_without_start_tls(&mut self) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        self.smtp = Some(listener.local_addr().unwrap());
        let seen = Arc::clone(&self.seen);
        tokio::spawn(async move {
            if let Ok((client, _)) = listener.accept().await {
                seen.reached.store(true, Ordering::SeqCst);
                talk_smtp_without_start_tls(client, &seen).await;
            }
        });
    }

    // Notes the tunnel asked for and refuses it: where the hook was to be reached is all the test needs to see.
    async fn given_an_http_proxy_of_the_preferences(&mut self) {
        let listener = self.proxy_of_the_preferences(ProxyType::Http, "", "").await;
        let seen = Arc::clone(&self.seen);
        tokio::spawn(async move {
            if let Ok((mut client, _)) = listener.accept().await {
                let head = read_head(&mut client).await;
                seen.proxy.lock().unwrap().push(head.lines().next().unwrap_or_default().to_owned());
                let refusal = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                let _ = client.write_all(refusal.as_bytes()).await;
            }
        });
    }

    // Asks for the user name and password, then takes the connection as the SMTP server it was asked for.
    async fn given_a_socks_proxy_of_the_preferences_before_an_smtp_server_without_start_tls(
        &mut self,
        user: &str,
        password: &str,
    ) {
        let listener = self.proxy_of_the_preferences(ProxyType::Socks, user, password).await;
        let seen = Arc::clone(&self.seen);
        tokio::spawn(async move {
            if let Ok((mut client, _)) = listener.accept().await
                && socks_handshake(&mut client, &seen).await.is_ok()
            {
                talk_smtp_without_start_tls(client, &seen).await;
            }
        });
    }

    // Takes the tunnel asked for, then the connection as the SMTP server it was asked for.
    async fn given_an_http_proxy_of_the_preferences_before_an_smtp_server_without_start_tls(&mut self) {
        let listener = self.proxy_of_the_preferences(ProxyType::Http, "", "").await;
        let seen = Arc::clone(&self.seen);
        tokio::spawn(async move {
            if let Ok((mut client, _)) = listener.accept().await {
                let head = read_head(&mut client).await;
                seen.proxy.lock().unwrap().push(head.lines().next().unwrap_or_default().to_owned());
                if client.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n").await.is_ok() {
                    talk_smtp_without_start_tls(client, &seen).await;
                }
            }
        });
    }

    // Notes the request, its headers with their names in lower case, and its body, and answers with `status`.
    async fn given_a_web_hook_answering(&mut self, status: u16) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        self.hook = Some(listener.local_addr().unwrap());
        let seen = Arc::clone(&self.seen);
        tokio::spawn(async move {
            if let Ok((mut client, _)) = listener.accept().await {
                let head = read_head(&mut client).await;
                let mut length = 0;
                let mut got = Vec::new();
                for (index, line) in head.lines().filter(|line| !line.is_empty()).enumerate() {
                    match line.split_once(':') {
                        Some((name, value)) if index > 0 => {
                            let name = name.to_ascii_lowercase();
                            if name == "content-length" {
                                length = value.trim().parse().unwrap();
                            }
                            got.push(format!("{name}:{value}"));
                        }
                        _ => got.push(line.to_owned()),
                    }
                }
                let mut body = vec![0; length];
                client.read_exact(&mut body).await.unwrap();
                got.push(String::from_utf8(body).unwrap());
                seen.hook.lock().unwrap().extend(got);
                let answer = format!("HTTP/1.1 {status} X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                let _ = client.write_all(answer.as_bytes()).await;
            }
        });
    }

    // 192.0.2.1 is kept for documentation: no computer has it.
    fn given_the_chosen_interface_has_an_address_this_computer_lacks(&mut self) {
        self.binding = Binding::Bound(Ipv4Addr::new(192, 0, 2, 1));
    }

    fn given_the_chosen_interface_is_gone(&mut self, name: &str) {
        self.binding = Binding::Missing(name.to_owned());
    }

    fn when_told(&mut self, event: &EngineEvent) {
        self.notifier().handle(event);
    }

    fn when_told_times(&mut self, event: &EngineEvent, times: usize) {
        let notifier = self.notifier();
        for _ in 0..times {
            notifier.handle(event);
        }
    }

    async fn when_tested(&mut self) {
        self.results = self.notifier().test().await;
    }

    async fn when_tested_for_real(&mut self) {
        let settings = self.settings.clone();
        self.results = Notifier::create(move || settings.clone(), self.routes()).test().await;
    }

    async fn when_posted_as(&mut self, body: WebhookFormat) {
        let address = format!("http://{}/notify", self.hook.unwrap());
        let client = http_route::client(&Routes::direct()).unwrap();
        self.outcome = Some(notifier::post(&client, &address, body, "njMule: Test", "test").await);
    }

    async fn when_mailed(&mut self) {
        let smtp = self.smtp.unwrap();
        self.when_mailed_to(&smtp.ip().to_string(), smtp.port()).await;
    }

    async fn when_mailed_to(&mut self, host: &str, port: u16) {
        let settings = Notifications {
            email: true,
            smtp_host: host.to_owned(),
            smtp_port: port,
            mail_from: "njmule@example.org".to_owned(),
            mail_to: "me@example.org".to_owned(),
            ..Notifications::default()
        };
        self.outcome = Some(notifier::send_mail(&self.routes(), &settings, "njMule: Test", "test").await);
    }

    fn then_the_mails_are(&self, mails: &[&str]) {
        assert_eq!(*self.mailed.lock().unwrap(), mails);
    }

    fn then_the_hook_calls_are(&self, calls: &[&str]) {
        assert_eq!(*self.hooked.lock().unwrap(), calls);
    }

    fn then_the_hook_was_called(&self, times: usize) {
        assert_eq!(self.hooked.lock().unwrap().len(), times);
    }

    fn then_the_results_are(&self, expected: &[NotificationResult]) {
        assert_eq!(self.results, expected);
    }

    fn then_it_failed_for(&self, part: &str) {
        match &self.outcome {
            Some(Err(why)) => assert!(why.contains(part), "{why}"),
            other => panic!("no failure: {other:?}"),
        }
    }

    fn then_the_greeting_named_no_computer(&self) {
        assert_eq!(*self.seen.greetings.lock().unwrap(), ["EHLO [127.0.0.1]"]);
    }

    fn then_no_message_was_sent(&self) {
        assert!(!self.seen.data_sent.load(Ordering::SeqCst));
    }

    fn then_the_proxy_saw(&self, lines: &[&str]) {
        assert_eq!(*self.seen.proxy.lock().unwrap(), lines);
    }

    fn then_each_result_failed_for(&self, part: &str) {
        let channels: Vec<&str> = self.results.iter().map(|result| result.channel.as_str()).collect();
        assert_eq!(channels, ["email", "webhook"]);
        for result in &self.results {
            assert!(result.error.as_deref().is_some_and(|error| error.contains(part)), "{result:?}");
        }
    }

    fn then_no_error_occurred(&self) {
        assert_eq!(self.outcome, Some(Ok(())));
    }

    fn then_the_mail_failed(&self) {
        assert!(matches!(self.outcome, Some(Err(_))), "{:?}", self.outcome);
    }

    fn then_the_web_hook_got(&self, parts: &[&str]) {
        let got = self.seen.hook.lock().unwrap().join("\n");
        for part in parts {
            assert!(got.contains(part), "{part} not in {got}");
        }
    }

    fn then_nothing_reached_the_server(&self) {
        assert!(!self.seen.reached.load(Ordering::SeqCst));
    }

    fn smtp_port(&self) -> u16 {
        self.smtp.unwrap().port()
    }

    async fn proxy_of_the_preferences(&mut self, kind: ProxyType, user: &str, password: &str) -> TcpListener {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        self.proxy = ProxySettings {
            kind,
            host: "127.0.0.1".to_owned(),
            port,
            user: user.to_owned(),
            password: password.to_owned(),
        };
        listener
    }

    fn routes(&self) -> Routes {
        let proxy = self.proxy.clone();
        let binding = self.binding.clone();
        Routes::new(move || proxy.clone(), move || binding.clone())
    }

    // Each message sent at once, as each channel takes it; the clock moves on at each message.
    fn notifier(&self) -> Notifier {
        let settings = self.settings.clone();
        let failure = self.mail_failure.clone();
        let mailed = Arc::clone(&self.mailed);
        let hooked = Arc::clone(&self.hooked);
        let ticks = AtomicU64::new(0);
        Notifier::new(
            move || settings.clone(),
            Arc::new(move |_, title, text| {
                let outcome = match &failure {
                    Some(why) => Err(why.clone()),
                    None => {
                        mailed.lock().unwrap().push(format!("{title}|{}", first_part(&text)));
                        Ok(())
                    }
                };
                Box::pin(async move { outcome })
            }),
            Arc::new(move |_, title, text| {
                hooked.lock().unwrap().push(format!("{title}|{}", first_part(&text)));
                Box::pin(async { Ok(()) })
            }),
            Arc::new(|mut task: BoxFuture<'static, ()>| {
                assert!(task.as_mut().poll(&mut Context::from_waker(Waker::noop())).is_ready());
            }),
            move || Duration::from_nanos(ticks.fetch_add(1, Ordering::SeqCst)),
        )
    }
}

// Greets and answers EHLO without offering STARTTLS; it takes anything that follows as the message.
async fn talk_smtp_without_start_tls(client: TcpStream, seen: &Seen) {
    let (input, mut output) = client.into_split();
    let mut lines = BufReader::new(input).lines();
    if output.write_all(b"220 test ESMTP\r\n").await.is_err() {
        return;
    }
    while let Ok(Some(line)) = lines.next_line().await {
        let command = line.to_ascii_uppercase();
        let answer: &[u8] = if command.starts_with("EHLO") {
            seen.greetings.lock().unwrap().push(line);
            b"250 test\r\n"
        } else if command.starts_with("DATA") {
            seen.data_sent.store(true, Ordering::SeqCst);
            b"354 go on\r\n"
        } else if command.starts_with("QUIT") {
            let _ = output.write_all(b"221 bye\r\n").await;
            return;
        } else {
            b"250 ok\r\n"
        };
        if output.write_all(answer).await.is_err() {
            return;
        }
    }
}

// The SOCKS 5 handshake with a user name and password (RFC 1928, RFC 1929), noting both and the address asked for.
async fn socks_handshake(client: &mut TcpStream, seen: &Seen) -> std::io::Result<()> {
    client.read_u8().await?;
    let methods = client.read_u8().await?;
    client.read_exact(&mut vec![0; usize::from(methods)]).await?;
    client.write_all(&[5, 2]).await?;
    client.read_u8().await?;
    let name = read_text(client).await?;
    let secret = read_text(client).await?;
    seen.proxy.lock().unwrap().push(format!("auth {name}:{secret}"));
    client.write_all(&[1, 0]).await?;
    client.read_exact(&mut [0; 3]).await?;
    let host = match client.read_u8().await? {
        3 => read_text(client).await?,
        1 => {
            let mut ip = [0; 4];
            client.read_exact(&mut ip).await?;
            Ipv4Addr::from(ip).to_string()
        }
        _ => {
            let mut ip = [0; 16];
            client.read_exact(&mut ip).await?;
            std::net::Ipv6Addr::from(ip).to_string()
        }
    };
    let port = client.read_u16().await?;
    seen.proxy.lock().unwrap().push(format!("connect {host}:{port}"));
    client.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).await
}

async fn read_text(client: &mut TcpStream) -> std::io::Result<String> {
    let length = client.read_u8().await?;
    let mut text = vec![0; usize::from(length)];
    client.read_exact(&mut text).await?;
    Ok(String::from_utf8_lossy(&text).into_owned())
}

// The head of an HTTP request, read a byte at a time so that nothing after it is taken.
async fn read_head(client: &mut TcpStream) -> String {
    let mut head = Vec::new();
    while !head.ends_with(b"\r\n\r\n") {
        match client.read_u8().await {
            Ok(byte) => head.push(byte),
            Err(_) => break,
        }
    }
    String::from_utf8_lossy(&head).replace('\r', "")
}

fn channels(email: bool, smtp_port: u16, webhook: bool, hook: &str) -> Notifications {
    Notifications {
        email,
        smtp_host: "127.0.0.1".to_owned(),
        smtp_port,
        mail_to: "me@example.org".to_owned(),
        webhook,
        webhook_url: hook.to_owned(),
        ..Notifications::default()
    }
}

// With no events chosen, those of the defaults.
fn on(email: bool, webhook: bool, events: &[Event]) -> Notifications {
    let defaults = Notifications::default();
    Notifications {
        email,
        smtp_host: "smtp.example.org".to_owned(),
        smtp_port: DEFAULT_SMTP_PORT,
        security: SmtpSecurity::Starttls,
        mail_to: "me@example.org".to_owned(),
        webhook,
        webhook_url: "https://ntfy.example.org/njmule".to_owned(),
        events: if events.is_empty() {
            defaults.events.clone()
        } else {
            events.iter().copied().collect::<BTreeSet<_>>()
        },
        ..defaults
    }
}

fn result(channel: &str, error: Option<&str>) -> NotificationResult {
    NotificationResult { channel: channel.to_owned(), error: error.map(str::to_owned) }
}

// The completed download's text goes on with its size and file, which the test leaves out.
fn first_part(text: &str) -> &str {
    text.split_once(" (").map_or(text, |(first, _)| first)
}

fn completed() -> EngineEvent {
    let download = DownloadInfo {
        hash: HASH,
        name: NAME.to_owned(),
        size: 1234,
        completed: 1234,
        status: DownloadStatus::Complete,
        priority: DownloadPriority::Normal,
        category: 0,
        sources: 0,
        transferring: 0,
        bytes_per_second: 0,
        parts: PartMap { size: 0, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
        preview_first: false,
        auto_swap: false,
    };
    EngineEvent::DownloadCompleted { download, file: PathBuf::from("incoming").join(NAME) }
}

fn message() -> EngineEvent {
    EngineEvent::MessageReceived {
        address: "192.0.2.1:4662".to_owned(),
        user_hash: HASH,
        nick: Some("ann".to_owned()),
        text: "hello".to_owned(),
    }
}
