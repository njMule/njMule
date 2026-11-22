// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A caller of the remote-control API, for the command line: answers come back as JSON values.

use std::fmt;
use std::io;
use std::path::Path;
use std::time::Duration;

use percent_encoding::{NON_ALPHANUMERIC, utf8_percent_encode};
use reqwest::Method;
use reqwest::header::{AUTHORIZATION, CONTENT_TYPE};
use serde_json::Value;

use crate::net::http_route;
use crate::remote::api_token;
use crate::remote::event_streams;
use crate::remote::web_ui_settings::WebUiSettings;

const TIMEOUT: Duration = Duration::from_secs(30);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_ANSWER: usize = 64 * 1024 * 1024;
const PREFIX: &str = "/api/v1";

// A stream sends a comment at each heartbeat: one silent for several is gone.
const STREAM_IDLE: Duration = Duration::from_secs(event_streams::KEEP_ALIVE.as_secs().saturating_mul(4));

/// An error from calling the remote-control API: either an HTTP error answer or an I/O / transport error.
#[derive(Debug)]
pub enum ApiError {
    /// An answer other than 2xx; `message` is the API's `"error"` field when present, else `"HTTP <status>"`.
    Failure { status: u16, message: String },
    /// Network, timeout or malformed response error.
    Io(io::Error),
}

impl fmt::Display for ApiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failure { message, .. } => f.write_str(message),
            Self::Io(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for ApiError {}

impl From<io::Error> for ApiError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

/// Calls a running njMule's remote-control API.
#[derive(Debug, Clone)]
pub struct ApiClient {
    base: String,
    token: String,
}

impl ApiClient {
    /// `base` is the address of the API, as `http://127.0.0.1:4711/api/v1`.
    pub fn new(base: impl Into<String>, token: impl Into<String>) -> Self {
        Self { base: base.into(), token: token.into() }
    }

    /// The API of the njMule using `config_dir` on this computer, when its settings let it answer.
    pub fn local(config_dir: &Path) -> io::Result<Option<Self>> {
        let settings = WebUiSettings::load(config_dir);
        let Some(token) = api_token::load(config_dir)? else {
            return Ok(None);
        };
        if !settings.usable() || !settings.api {
            return Ok(None);
        }
        Ok(Some(Self::new(format!("http://127.0.0.1:{}{PREFIX}", settings.port), token)))
    }

    pub async fn get(&self, path: &str) -> Result<Value, ApiError> {
        self.call(Method::GET, path, None).await
    }

    pub async fn post(&self, path: &str, body: Value) -> Result<Value, ApiError> {
        self.call(Method::POST, path, Some(body)).await
    }

    pub async fn put(&self, path: &str, body: Value) -> Result<Value, ApiError> {
        self.call(Method::PUT, path, Some(body)).await
    }

    pub async fn delete(&self, path: &str) -> Result<Value, ApiError> {
        self.call(Method::DELETE, path, None).await
    }

    /// Follows the event stream, handing each event's name and JSON payload to `each` until it returns `Ok(false)` or
    /// the stream ends.
    pub async fn events<F>(&self, types: &str, mut each: F) -> Result<(), ApiError>
    where
        F: FnMut(&str, Value) -> io::Result<bool>,
    {
        let client = http_route::direct_client(CONNECT_TIMEOUT)?;
        let encoded = utf8_percent_encode(types, NON_ALPHANUMERIC);
        let url = format!("{}/events?types={encoded}", self.base);
        let request = client.get(&url).header(AUTHORIZATION, format!("Bearer {}", self.token));
        let mut response = tokio::time::timeout(TIMEOUT, request.send())
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "No answer in time."))?
            .map_err(transport_error)?;
        let status = response.status().as_u16();
        if status != 200 {
            let body = read_bounded(&mut response, 64 * 1024).await.unwrap_or_default();
            return Err(failure_from_body(status, &body));
        }
        let mut name = "message".to_owned();
        let mut pending: Vec<u8> = Vec::new();
        loop {
            let chunk = tokio::time::timeout(STREAM_IDLE, response.chunk())
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "No answer in time."))?
                .map_err(transport_error)?;
            let Some(bytes) = chunk else {
                break;
            };
            pending.extend_from_slice(&bytes);
            if pending.len() > MAX_ANSWER {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "The answer is too large.").into());
            }
            while let Some(pos) = pending.iter().position(|&b| b == b'\n') {
                let raw: Vec<u8> = pending.drain(..=pos).collect();
                let line = String::from_utf8_lossy(&raw);
                let line = line.trim_end_matches(['\r', '\n']);
                if let Some(event_name) = line.strip_prefix("event: ") {
                    name = event_name.to_owned();
                } else if let Some(payload) = line.strip_prefix("data: ") {
                    let parsed = parse_json(payload.as_bytes())?;
                    let keep_going = each(&name, parsed)?;
                    if !keep_going {
                        return Ok(());
                    }
                    name = "message".to_owned();
                }
            }
        }
        Ok(())
    }

    async fn call(&self, method: Method, path: &str, body: Option<Value>) -> Result<Value, ApiError> {
        let client = http_route::lan_client(TIMEOUT)?;
        let url = format!("{}{path}", self.base);
        let mut request = client.request(method, &url).header(AUTHORIZATION, format!("Bearer {}", self.token));
        if let Some(body) = body {
            let encoded = serde_json::to_vec(&body)
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("Cannot encode JSON: {e}")))?;
            request = request.header(CONTENT_TYPE, "application/json; charset=utf-8").body(encoded);
        }
        let mut response = request.send().await.map_err(transport_error)?;
        let status = response.status().as_u16();
        let bytes = read_bounded(&mut response, MAX_ANSWER).await?;
        if status / 100 != 2 {
            return Err(failure_from_body(status, &bytes));
        }
        if bytes.is_empty() {
            return Ok(Value::Null);
        }
        parse_json(&bytes)
    }
}

async fn read_bounded(response: &mut reqwest::Response, max: usize) -> Result<Vec<u8>, ApiError> {
    let mut out = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(transport_error)? {
        if out.len().saturating_add(chunk.len()) > max {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "The answer is too large.").into());
        }
        out.extend_from_slice(&chunk);
    }
    Ok(out)
}

fn transport_error(error: reqwest::Error) -> ApiError {
    if error.is_timeout() {
        ApiError::Io(io::Error::new(io::ErrorKind::TimedOut, "No answer in time."))
    } else {
        ApiError::Io(io::Error::other(error.to_string()))
    }
}

fn failure_from_body(status: u16, body: &[u8]) -> ApiError {
    let message = parse_json(body)
        .ok()
        .and_then(|value| value.get("error").and_then(Value::as_str).map(str::to_owned))
        .unwrap_or_else(|| format!("HTTP {status}"));
    ApiError::Failure { status, message }
}

fn parse_json(bytes: &[u8]) -> Result<Value, ApiError> {
    let text = std::str::from_utf8(bytes)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("Malformed answer: {e}")))?;
    if text.trim().is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_str(text)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("Malformed answer: {e}")).into())
}
