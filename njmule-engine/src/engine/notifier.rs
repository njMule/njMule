// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tells of engine events by e-mail and through a web hook, as the notification preferences say, from the network
//! interface chosen and through the proxy of the preferences. Each message goes on a task of its own, a few at once,
//! and at most [`BURST`] in [`WINDOW`]: a flood of chat messages does not become a flood of e-mails.

use std::collections::VecDeque;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::path::Path;
use std::pin::Pin;
use std::sync::{Arc, Mutex, PoisonError};
use std::task::{Context, Poll};
use std::time::{Duration, Instant};

use futures_util::future::BoxFuture;
use lettre::message::header::ContentType;
use lettre::message::{Mailbox, Message};
use lettre::transport::smtp::authentication::{Credentials, Mechanism};
use lettre::transport::smtp::client::{AsyncSmtpConnection, AsyncTokioStream, TlsParameters};
use lettre::transport::smtp::extension::ClientId;
use reqwest::Client;
use reqwest::header::CONTENT_TYPE;
use rustls::pki_types::ServerName;
use rustls_platform_verifier::BuilderVerifierExt;
use serde_json::json;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio::sync::Semaphore;
use tokio_rustls::TlsConnector;
use tokio_rustls::client::TlsStream;

use crate::api::engine_event::EngineEvent;
use crate::api::notification_result::NotificationResult;
use crate::config::event_commands::Event;
use crate::config::notifications::{Notifications, SmtpSecurity, WebhookFormat};
use crate::net::routes::Routes;
use crate::net::{http_route, tcp_connector};

/// Messages told in [`WINDOW`] at most; those beyond are dropped.
pub const BURST: usize = 10;
pub const WINDOW: Duration = Duration::from_secs(10 * 60);

const MAX_SENDING: usize = 2;
const NETWORK_TIME: Duration = Duration::from_secs(30);
const EMAIL: &str = "email";
const WEBHOOK: &str = "webhook";
const MEBIBYTE: f64 = 1024.0 * 1024.0;

/// Sends one message, its title and its text, through one channel; why not when it cannot.
pub type Channel = Arc<dyn Fn(Notifications, String, String) -> BoxFuture<'static, Result<(), String>> + Send + Sync>;
/// Runs a task that may wait for the network.
pub type Executor = Arc<dyn Fn(BoxFuture<'static, ()>) + Send + Sync>;

pub struct Notifier {
    settings: Box<dyn Fn() -> Notifications + Send + Sync>,
    mail: Channel,
    hook: Channel,
    executor: Executor,
    ticker: Box<dyn Fn() -> Duration + Send + Sync>,
    sending: Arc<Semaphore>,
    told: Mutex<Told>,
}

// When the latest messages were told, oldest first, and whether those beyond the burst are being dropped.
#[derive(Default)]
struct Told {
    times: VecDeque<Duration>,
    dropping: bool,
}

impl Notifier {
    /// `ticker` tells the time on a monotonic clock.
    pub fn new(
        settings: impl Fn() -> Notifications + Send + Sync + 'static,
        mail: Channel,
        hook: Channel,
        executor: Executor,
        ticker: impl Fn() -> Duration + Send + Sync + 'static,
    ) -> Self {
        Self {
            settings: Box::new(settings),
            mail,
            hook,
            executor,
            ticker: Box::new(ticker),
            sending: Arc::new(Semaphore::new(MAX_SENDING)),
            told: Mutex::default(),
        }
    }

    /// A notifier that sends e-mails and web hook requests along `routes`, on tasks of the runtime.
    pub fn create(settings: impl Fn() -> Notifications + Send + Sync + 'static, routes: Routes) -> Self {
        let mailing = routes.clone();
        let start = Instant::now();
        Self::new(
            settings,
            Arc::new(move |n, title, text| {
                let routes = mailing.clone();
                Box::pin(async move { send_mail(&routes, &n, &title, &text).await })
            }),
            Arc::new(move |n, title, text| {
                let routes = routes.clone();
                Box::pin(async move { call_hook(&routes, &n, &title, &text).await })
            }),
            Arc::new(|task| {
                tokio::spawn(task);
            }),
            move || start.elapsed(),
        )
    }

    /// Tells of `event`, when it is one the preferences choose.
    pub fn handle(&self, event: &EngineEvent) {
        match event {
            EngineEvent::DownloadCompleted { download, file } => self.tell(
                Event::DownloadCompleted,
                "Download completed",
                format!("{} ({}) is complete:\n{}", download.name, mebibytes(download.size), absolute(file)),
            ),
            EngineEvent::DownloadFailed { download, reason } => {
                self.tell(Event::DownloadFailed, "Download failed", format!("{}:\n{reason}", download.name));
            }
            EngineEvent::MessageReceived { address, nick, text, .. } => {
                let sender = nick.as_deref().unwrap_or(address);
                self.tell(Event::ChatMessage, "Chat message", format!("{sender}:\n{text}"));
            }
            EngineEvent::DiskSpace { directory, enough: false } => self.tell(
                Event::DiskSpaceShort,
                "Disk space short",
                format!("The downloads wait: {} is short of space.", absolute(directory)),
            ),
            // Nothing is told of the other events.
            _ => {}
        }
    }

    /// Sends a test message through each channel that is on; empty when none is.
    pub async fn test(&self) -> Vec<NotificationResult> {
        let n = (self.settings)();
        let text = "This is a test of the notifications of njMule.";
        let mut results = Vec::new();
        if n.email {
            results.push(attempt(EMAIL, &self.mail, &n, text).await);
        }
        if n.webhook {
            results.push(attempt(WEBHOOK, &self.hook, &n, text).await);
        }
        results
    }

    fn tell(&self, event: Event, title: &str, text: String) {
        let n = (self.settings)();
        if !n.tells(event) || !(n.email || n.webhook) || !self.admit() {
            return;
        }
        if n.email {
            self.send(EMAIL, &self.mail, &n, title, &text);
        }
        if n.webhook {
            self.send(WEBHOOK, &self.hook, &n, title, &text);
        }
    }

    fn send(&self, channel: &'static str, through: &Channel, n: &Notifications, title: &str, text: &str) {
        let title = titled(title);
        let Ok(permit) = Arc::clone(&self.sending).try_acquire_owned() else {
            tracing::warn!(
                "{MAX_SENDING} notifications are still on their way: the {channel} of \"{title}\" is dropped"
            );
            return;
        };
        let sent = through(n.clone(), title.clone(), text.to_owned());
        (self.executor)(Box::pin(async move {
            if let Err(e) = sent.await {
                tracing::warn!("Cannot send the {channel} of \"{title}\": {e}");
            }
            drop(permit);
        }));
    }

    // At most BURST messages in WINDOW; the first one dropped says so.
    fn admit(&self) -> bool {
        let now = (self.ticker)();
        let mut told = self.told.lock().unwrap_or_else(PoisonError::into_inner);
        while told.times.front().is_some_and(|&first| now.saturating_sub(first) >= WINDOW) {
            told.times.pop_front();
        }
        if told.times.len() >= BURST {
            if !told.dropping {
                tracing::warn!(
                    "More than {BURST} notifications in {} minutes: the next ones are dropped",
                    WINDOW.as_secs() / 60
                );
                told.dropping = true;
            }
            return false;
        }
        told.dropping = false;
        told.times.push_back(now);
        true
    }
}

async fn attempt(channel: &str, through: &Channel, n: &Notifications, text: &str) -> NotificationResult {
    let error = through(n.clone(), titled("Test"), text.to_owned()).await.err();
    NotificationResult { channel: channel.to_owned(), error }
}

fn titled(what: &str) -> String {
    format!("njMule: {what}")
}

#[allow(clippy::cast_precision_loss)]
fn mebibytes(bytes: u64) -> String {
    format!("{:.1} MiB", bytes as f64 / MEBIBYTE)
}

fn absolute(path: &Path) -> String {
    std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf()).display().to_string()
}

/// Sends the message by e-mail along `routes`. Never in the clear: STARTTLS is required, or TLS from the start, and the
/// server's name is checked.
pub async fn send_mail(routes: &Routes, n: &Notifications, title: &str, text: &str) -> Result<(), String> {
    let to = n.recipients();
    let (Some(first), false) = (to.first(), n.smtp_host.is_empty()) else {
        return Err("Set the SMTP server and the addresses to send to.".to_owned());
    };
    let from = if n.mail_from.is_empty() { first } else { n.mail_from.as_str() };
    let mut message = Message::builder().from(mailbox(from)?);
    for recipient in &to {
        message = message.to(mailbox(recipient)?);
    }
    let message = message
        .subject(title)
        .date_now()
        .header(ContentType::TEXT_PLAIN)
        .body(text.to_owned())
        .map_err(|e| e.to_string())?;
    tokio::time::timeout(NETWORK_TIME, deliver(routes, n, &message))
        .await
        .map_err(|_| "The SMTP server did not answer in time.".to_owned())?
}

fn mailbox(address: &str) -> Result<Mailbox, String> {
    address.parse().map_err(|e| format!("Not an e-mail address: {address} ({e})"))
}

async fn deliver(routes: &Routes, n: &Notifications, message: &Message) -> Result<(), String> {
    let stream = tcp_connector::open_host(&n.smtp_host, n.smtp_port, NETWORK_TIME, routes)
        .await
        .map_err(|e| format!("Cannot reach {}: {e}", n.smtp_host))?;
    // The greeting names an address literal rather than this computer, whose name may be its owner's.
    let hello = ClientId::Ipv4(Ipv4Addr::LOCALHOST);
    let mut connection = if n.security == SmtpSecurity::Tls {
        let secured = Secured(tls(stream, &n.smtp_host).await?);
        AsyncSmtpConnection::connect_with_transport(Box::new(secured), &hello).await.map_err(|e| e.to_string())?
    } else {
        let mut connection =
            AsyncSmtpConnection::connect_with_transport(Box::new(stream), &hello).await.map_err(|e| e.to_string())?;
        if !connection.can_starttls() {
            connection.abort().await;
            return Err("The SMTP server offers no STARTTLS: the mail is not sent in the clear.".to_owned());
        }
        let parameters = TlsParameters::new_rustls(n.smtp_host.clone()).map_err(|e| e.to_string())?;
        connection.starttls(parameters, &hello).await.map_err(|e| e.to_string())?;
        connection
    };
    if !n.smtp_user.is_empty() {
        let credentials = Credentials::new(n.smtp_user.clone(), n.smtp_password.clone());
        connection.auth(&[Mechanism::Plain, Mechanism::Login], &credentials).await.map_err(|e| e.to_string())?;
    }
    connection.send(message.envelope(), &message.formatted()).await.map_err(|e| e.to_string())?;
    let _ = connection.quit().await;
    Ok(())
}

// TLS from the start, the server's certificate checked against the system's, as for the other connections.
async fn tls(stream: TcpStream, host: &str) -> Result<TlsStream<TcpStream>, String> {
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .and_then(BuilderVerifierExt::with_platform_verifier)
        .map_err(|e| e.to_string())?
        .with_no_client_auth();
    let name = ServerName::try_from(host.trim_start_matches('[').trim_end_matches(']').to_owned())
        .map_err(|e| format!("Not a server name: {host} ({e})"))?;
    TlsConnector::from(Arc::new(config)).connect(name, stream).await.map_err(|e| e.to_string())
}

// A TLS stream as the mail library takes one.
#[derive(Debug)]
struct Secured(TlsStream<TcpStream>);

impl AsyncRead for Secured {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().0).poll_read(cx, buf)
    }
}

impl AsyncWrite for Secured {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().0).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().0).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().0).poll_shutdown(cx)
    }
}

impl AsyncTokioStream for Secured {
    fn peer_addr(&self) -> io::Result<SocketAddr> {
        self.0.get_ref().0.peer_addr()
    }
}

// A client for each request, as the route may have changed since the last one.
async fn call_hook(routes: &Routes, n: &Notifications, title: &str, text: &str) -> Result<(), String> {
    if n.webhook_url.is_empty() {
        return Err("Set the address of the web hook.".to_owned());
    }
    let client = http_route::client(routes).map_err(|e| e.to_string())?;
    post(&client, &n.webhook_url, n.webhook_body, title, text).await
}

/// Posts the message to the web hook at `address`, which the preferences keep to https.
pub async fn post(client: &Client, address: &str, body: WebhookFormat, title: &str, text: &str) -> Result<(), String> {
    let request = client.post(address).timeout(NETWORK_TIME);
    let request = match body {
        WebhookFormat::Text => {
            request.header("Title", title).header(CONTENT_TYPE, "text/plain; charset=utf-8").body(text.to_owned())
        }
        WebhookFormat::Json => {
            request.header(CONTENT_TYPE, "application/json").body(json!({"title": title, "message": text}).to_string())
        }
    };
    let answer = request
        .send()
        .await
        .map_err(|e| if e.is_timeout() { "The web hook did not answer in time.".to_owned() } else { chain(&e) })?;
    if !answer.status().is_success() {
        return Err(format!("The web hook answered HTTP {}.", answer.status().as_u16()));
    }
    Ok(())
}

// An error with its causes, which tell what went wrong where the error itself names the request only.
fn chain(error: &dyn std::error::Error) -> String {
    let mut text = error.to_string();
    let mut cause = error.source();
    while let Some(next) = cause {
        text.push_str(": ");
        text.push_str(&next.to_string());
        cause = next.source();
    }
    text
}
