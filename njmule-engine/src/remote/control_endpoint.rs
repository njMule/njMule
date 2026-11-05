// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The control endpoint of the desktop window and the test kit: the API on a loopback port, for whoever holds the
//! token it prints. Pages of other origins than the desktop shell's are refused, so a web page cannot reach it.

use std::io;
use std::net::{Ipv4Addr, SocketAddr};

use axum::Router;
use axum::extract::{Request, State};
use axum::http::{HeaderValue, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use rand::Rng;
use serde_json::json;
use subtle::ConstantTimeEq;
use tokio::net::TcpListener;
use tokio::sync::watch;
use tokio::task::JoinHandle;

use crate::engine::Engine;
use crate::remote::remote_api::{self, ApiError};

const BASE_PATH: &str = "/api/v1";
// The origins of the desktop shell's own pages (Tauri: Windows, then macOS and Linux); no web page has them.
const SHELL_ORIGINS: [&str; 3] = ["http://tauri.localhost", "https://tauri.localhost", "tauri://localhost"];

/// A running endpoint.
pub struct ControlEndpoint {
    url: String,
    token: String,
    stop: watch::Sender<bool>,
    serving: JoinHandle<io::Result<()>>,
}

impl ControlEndpoint {
    /// Serves the API of `engine` on a free loopback port.
    pub async fn start(engine: Engine) -> io::Result<Self> {
        let mut secret = [0u8; 32];
        rand::rng().fill_bytes(&mut secret);
        let token = data_encoding::BASE64URL_NOPAD.encode(&secret);
        let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).await?;
        let url = format!("http://{}{BASE_PATH}", listener.local_addr()?);
        let app = Router::new()
            .nest(BASE_PATH, remote_api::router(engine))
            .layer(middleware::from_fn_with_state(token.clone(), guard));
        let (stop, mut stopped) = watch::channel(false);
        let serving = tokio::spawn(async move {
            axum::serve(listener, app)
                .with_graceful_shutdown(async move {
                    let _ = stopped.wait_for(|stop| *stop).await;
                })
                .await
        });
        Ok(Self { url, token, stop, serving })
    }

    /// The line that tells the starting process where the API is and how to call it.
    pub fn control_line(&self) -> String {
        format!("njmule-control {}", json!({"url": self.url, "token": self.token}))
    }

    pub async fn stop(self) {
        self.stop.send_replace(true);
        let _ = self.serving.await;
    }
}

// Lets through the requests that carry the token and come from no web page, or from the desktop shell's.
async fn guard(State(token): State<String>, request: Request, next: Next) -> Response {
    let origin = request.headers().get(header::ORIGIN).cloned();
    if let Some(origin) = &origin
        && !SHELL_ORIGINS.iter().any(|shell| origin.as_bytes() == shell.as_bytes())
    {
        return ApiError::new(StatusCode::FORBIDDEN, "Origin not allowed").into_response();
    }
    let presented = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.split_once(' '))
        .filter(|(scheme, _)| scheme.eq_ignore_ascii_case("Bearer"))
        .map(|(_, credentials)| credentials.trim());
    let allowed = presented.is_some_and(|presented| bool::from(presented.as_bytes().ct_eq(token.as_bytes())));
    if !allowed {
        let mut refused = ApiError::new(StatusCode::UNAUTHORIZED, "A token is required").into_response();
        refused.headers_mut().insert(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
        return refused;
    }
    let mut response = next.run(request).await;
    if let Some(origin) = origin {
        response.headers_mut().insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.headers_mut().insert(header::VARY, HeaderValue::from_static("Origin"));
    }
    response
}
