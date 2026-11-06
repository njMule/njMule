// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where njMule tells of what happens besides its own windows: an e-mail through the user's SMTP server, never in the
//! clear, and an HTTPS request to a web hook such as an ntfy topic or a Gotify server; each on the events chosen.

use std::collections::BTreeSet;
use std::fmt;

use strum::{EnumString, IntoStaticStr, VariantArray};

use crate::config::event_commands::Event;
use crate::util::white_space::strip;

/// Characters of a host, a user, a password or the addresses at most, in UTF-16 units as the Java engine counts them.
pub const MAX_TEXT: usize = 255;
/// Characters of the web hook's address at most.
pub const MAX_URL: usize = 2048;
pub const DEFAULT_SMTP_PORT: u16 = 587;

/// How the connection to the SMTP server is encrypted.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum SmtpSecurity {
    /// Plain at first, then TLS before anything is sent; the connection fails without it.
    Starttls,
    /// TLS from the start.
    Tls,
}

impl SmtpSecurity {
    pub fn name(self) -> &'static str {
        self.into()
    }
}

/// What the web hook receives.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE", ascii_case_insensitive)]
pub enum WebhookFormat {
    /// The text, with the title in a Title header, as ntfy reads it.
    Text,
    /// JSON with "title" and "message", as Gotify reads it.
    Json,
}

impl WebhookFormat {
    pub fn name(self) -> &'static str {
        self.into()
    }
}

/// `smtp_port` is 587 for STARTTLS, 465 for TLS from the start; `mail_to` the addresses the e-mails go to, apart by
/// commas; `webhook_url` an https address, empty for none.
#[derive(Clone, PartialEq, Eq)]
pub struct Notifications {
    pub email: bool,
    pub smtp_host: String,
    pub smtp_port: u16,
    pub security: SmtpSecurity,
    pub smtp_user: String,
    pub smtp_password: String,
    pub mail_from: String,
    pub mail_to: String,
    pub webhook: bool,
    pub webhook_url: String,
    pub webhook_body: WebhookFormat,
    pub events: BTreeSet<Event>,
}

/// Nothing is sent; once on, the downloads completed and failed and a disk short of space are told.
impl Default for Notifications {
    fn default() -> Self {
        Self {
            email: false,
            smtp_host: String::new(),
            smtp_port: DEFAULT_SMTP_PORT,
            security: SmtpSecurity::Starttls,
            smtp_user: String::new(),
            smtp_password: String::new(),
            mail_from: String::new(),
            mail_to: String::new(),
            webhook: false,
            webhook_url: String::new(),
            webhook_body: WebhookFormat::Text,
            events: BTreeSet::from([Event::DownloadCompleted, Event::DownloadFailed, Event::DiskSpaceShort]),
        }
    }
}

impl Notifications {
    /// These notifications with their texts stripped, or why they cannot be.
    pub fn validated(mut self) -> Result<Self, String> {
        self.smtp_host = text("smtpHost", &self.smtp_host)?;
        self.smtp_user = text("smtpUser", &self.smtp_user)?;
        if too_long(&self.smtp_password, MAX_TEXT) || self.smtp_password.chars().any(char::is_control) {
            return Err(format!("the SMTP password must be at most {MAX_TEXT} printable characters"));
        }
        self.mail_from = text("mailFrom", &self.mail_from)?;
        self.mail_to = text("mailTo", &self.mail_to)?;
        if self.smtp_port == 0 {
            return Err(format!("smtpPort must be in [1, 65535]: {}", self.smtp_port));
        }
        self.webhook_url = require_webhook_url(strip(&self.webhook_url))?;
        Ok(self)
    }

    /// Whether `event` is told.
    pub fn tells(&self, event: Event) -> bool {
        self.events.contains(&event)
    }

    /// The addresses the e-mails go to.
    pub fn recipients(&self) -> Vec<&str> {
        commas(&self.mail_to).collect()
    }
}

/// The events of a comma-separated list of their ids, such as `downloadCompleted,chatMessage`.
pub fn events(ids: &str) -> Result<BTreeSet<Event>, String> {
    commas(ids)
        .map(|wanted| {
            Event::ALL
                .into_iter()
                .find(|event| event.id().eq_ignore_ascii_case(wanted))
                .ok_or_else(|| format!("unknown event: {wanted}"))
        })
        .collect()
}

/// `url` without the white space around it, when it is empty or an https address with a host.
// Only HTTPS: the texts name files and the other users who write.
pub fn require_webhook_url(url: &str) -> Result<String, String> {
    let stripped = strip(url);
    if stripped.is_empty() {
        return Ok(String::new());
    }
    if too_long(url, MAX_URL) {
        return Err(format!("the web hook address must be at most {MAX_URL} characters"));
    }
    let parsed: http::Uri = stripped.parse().map_err(|_| format!("not a web hook address: {url}"))?;
    if !parsed.scheme_str().is_some_and(|scheme| scheme.eq_ignore_ascii_case("https")) || parsed.host().is_none() {
        return Err(format!("the web hook address must be an https address: {url}"));
    }
    Ok(stripped.to_owned())
}

fn commas(text: &str) -> impl Iterator<Item = &str> {
    text.split(',').map(strip).filter(|part| !part.is_empty())
}

fn text(name: &str, value: &str) -> Result<String, String> {
    let stripped = strip(value);
    if too_long(stripped, MAX_TEXT) || stripped.chars().any(char::is_control) {
        return Err(format!("{name} must be at most {MAX_TEXT} printable characters"));
    }
    Ok(stripped.to_owned())
}

fn too_long(text: &str, max: usize) -> bool {
    text.encode_utf16().count() > max
}

// The password never shows in logs.
impl fmt::Debug for Notifications {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Notifications")
            .field("email", &self.email)
            .field("smtp_host", &self.smtp_host)
            .field("smtp_port", &self.smtp_port)
            .field("security", &self.security)
            .field("smtp_user", &self.smtp_user)
            .field("smtp_password", &if self.smtp_password.is_empty() { "" } else { "***" })
            .field("mail_from", &self.mail_from)
            .field("mail_to", &self.mail_to)
            .field("webhook", &self.webhook)
            .field("webhook_url", &self.webhook_url)
            .field("webhook_body", &self.webhook_body)
            .field("events", &self.events)
            .finish()
    }
}
