// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API (`openapi.yaml`): the calls the front ends make, answered from the engine.

use std::collections::BTreeMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{Value, json};

use crate::engine::Engine;
use crate::engine::network::ConnectError;
use crate::persist::format::server_met;
use crate::remote::{api_json, event_streams};
use crate::server::server_list::Address;

/// The calls, relative to the API's base path.
pub fn router(engine: Engine) -> Router {
    Router::new()
        .route("/preferences", get(preferences).patch(update_preferences))
        .route("/schedule", get(|| async { Json(api_json::schedule()) }))
        .route("/update", get(|| async { Json(api_json::update()) }))
        .route("/events", get(events))
        .route("/clients/{address}/hello", post(hello))
        .route("/servers", get(servers))
        .route("/servers/import", post(import_servers))
        .route("/servers/connect", post(connect_server))
        .route("/servers/disconnect", post(disconnect_server))
        .route("/servers/{address}/static", axum::routing::put(set_static_server))
        .fallback(|| async { ApiError::new(StatusCode::NOT_FOUND, "No such call") })
        .method_not_allowed_fallback(|| async { ApiError::new(StatusCode::METHOD_NOT_ALLOWED, "No such method") })
        .with_state(engine)
}

/// An error answer: its status and `{"error": message}`.
pub struct ApiError {
    status: StatusCode,
    message: String,
}

impl ApiError {
    pub fn new(status: StatusCode, message: impl Into<String>) -> Self {
        Self { status, message: message.into() }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.status, Json(api_json::error(&self.message))).into_response()
    }
}

async fn preferences(State(engine): State<Engine>) -> Json<Value> {
    Json(api_json::preferences(&engine.preferences()))
}

async fn update_preferences(State(engine): State<Engine>, Json(body): Json<Value>) -> Result<Json<Value>, ApiError> {
    let Value::Object(fields) = body else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Preferences are an object"));
    };
    let mut changes = BTreeMap::new();
    for (key, value) in fields {
        let Value::String(text) = value else {
            return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("{key}: values are text")));
        };
        changes.insert(key, text);
    }
    let changed =
        engine.update_preferences(&changes).map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    Ok(Json(api_json::preferences(&changed)))
}

async fn events(State(engine): State<Engine>) -> Response {
    event_streams::stream(&engine).into_response()
}

async fn hello(State(engine): State<Engine>, Path(address): Path<String>) -> Result<Json<Value>, ApiError> {
    let target = tokio::net::lookup_host(address.as_str())
        .await
        .ok()
        .and_then(|mut found| found.next())
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a client address: {address}")))?;
    let info = engine.hello(target).await.map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.to_string()))?;
    Ok(Json(api_json::peer_info(&info)))
}

async fn servers(State(engine): State<Engine>) -> Json<Value> {
    Json(Value::Array(engine.servers().await.iter().map(api_json::server).collect()))
}

/// The servers of a `server.met` sent in Base64 (`data`) or read from the engine's computer (`path`).
async fn import_servers(State(engine): State<Engine>, Json(body): Json<Value>) -> Result<Json<Value>, ApiError> {
    let file = if let Some(data) = body.get("data").and_then(Value::as_str) {
        // Padding is accepted, not required.
        data_encoding::BASE64_NOPAD
            .decode(data.trim().trim_end_matches('=').as_bytes())
            .map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, "The data is not Base64."))?
    } else if let Some(path) = body.get("path").and_then(Value::as_str) {
        std::fs::read(path).map_err(|e| ApiError::new(StatusCode::BAD_REQUEST, format!("Cannot read {path}: {e}")))?
    } else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Give the list as data or as a path."));
    };
    let read = server_met::read(&file)
        .map_err(|e| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a server list: {e}")))?;
    Ok(Json(json!({"added": engine.import_servers(read).await})))
}

async fn connect_server(State(engine): State<Engine>, body: Option<Json<Value>>) -> Result<Json<Value>, ApiError> {
    let address = match body.as_ref().and_then(|Json(body)| body.get("address")) {
        Some(Value::String(text)) => Some(server_address(text)?),
        Some(_) => return Err(ApiError::new(StatusCode::BAD_REQUEST, "The address is text.")),
        None => None,
    };
    engine.connect_server(address).await.map_err(|e| match e {
        ConnectError::Refused(reason) => ApiError::new(StatusCode::BAD_REQUEST, reason),
        ConnectError::NoServer => ApiError::new(StatusCode::CONFLICT, "No known server: add a server.met or give one."),
    })?;
    Ok(Json(api_json::ok()))
}

async fn disconnect_server(State(engine): State<Engine>) -> Json<Value> {
    engine.disconnect_server().await;
    Json(api_json::ok())
}

async fn set_static_server(
    State(engine): State<Engine>,
    Path(address): Path<String>,
    Json(body): Json<Value>,
) -> Result<Json<Value>, ApiError> {
    let address = server_address(&address)?;
    let Some(wanted) = body.get("static").and_then(Value::as_bool) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing truth value: static"));
    };
    if engine.set_static_server(address, wanted).await {
        Ok(Json(api_json::ok()))
    } else {
        Err(ApiError::new(StatusCode::NOT_FOUND, "No such server"))
    }
}

fn server_address(text: &str) -> Result<Address, ApiError> {
    Address::parse(text).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a server address: {text}")))
}
