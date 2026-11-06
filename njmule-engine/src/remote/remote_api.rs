// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API (`openapi.yaml`): the calls the front ends make, answered from the engine.

use std::collections::BTreeMap;

use axum::extract::{Path, Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde_json::{Value, json};

use crate::api::ed2k_link::Ed2kLink;
use crate::engine::Engine;
use crate::engine::network::{ConnectError, MoreAnswer};
use crate::persist::format::server_met;
use crate::remote::{api_json, event_streams};
use crate::search::search_manager::{Scope, StartError};
use crate::server::server_list::Address;

/// The calls, relative to the API's base path.
pub fn router(engine: Engine) -> Router {
    Router::new()
        .route("/preferences", get(preferences).patch(update_preferences))
        .route("/preferences/passwords", get(|State(engine): State<Engine>| async move {
            Json(api_json::preference_passwords(&engine.preferences()))
        }))
        .route("/schedule", get(|| async { Json(api_json::schedule()) }))
        .route("/update", get(|| async { Json(api_json::update()) }))
        .route("/events", get(events))
        .route("/clients/{address}/hello", post(hello))
        .route("/searches", post(start_search))
        .route("/searches/{id}/more", post(more_results))
        .route("/shared", get(shared_files))
        .route("/downloads", get(downloads).post(add_downloads))
        .route("/downloads/completed", delete(clear_completed_downloads))
        .route("/downloads/completed/{hash}", delete(clear_completed_download))
        .route("/downloads/{hash}", delete(cancel_download))
        .route("/downloads/{hash}/pause", post(pause_download))
        .route("/downloads/{hash}/resume", post(resume_download))
        .route("/sources", get(sources))
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

/// Starts a search: `query`, and `scope` (`server` by default, `global`, `kad` or `automatic`).
async fn start_search(State(engine): State<Engine>, Json(body): Json<Value>) -> Result<Response, ApiError> {
    let Some(query) = body.get("query").and_then(Value::as_str) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing text: query"));
    };
    let asked = body.get("scope").and_then(Value::as_str).unwrap_or("server");
    // Automatic: what the networks connected allow, the servers here.
    let scope = if asked.eq_ignore_ascii_case("automatic") { Some(Scope::Global) } else { Scope::parse(asked) }
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a search scope: {asked}")))?;
    let started = engine.start_search(query.trim().to_owned(), scope).await.map_err(|e| match e {
        StartError::Syntax(error) => ApiError::new(StatusCode::BAD_REQUEST, error.to_string()),
        StartError::NotForKad(reason) => ApiError::new(StatusCode::BAD_REQUEST, reason),
        StartError::Unavailable(reason) => ApiError::new(StatusCode::CONFLICT, reason),
    })?;
    Ok((StatusCode::CREATED, Json(json!({"id": started}))).into_response())
}

async fn more_results(State(engine): State<Engine>, Path(id): Path<String>) -> Result<Json<Value>, ApiError> {
    let id: u64 = id.parse().map_err(|_| ApiError::new(StatusCode::NOT_FOUND, "No such search"))?;
    match engine.more_results(id).await {
        MoreAnswer::Asked => Ok(Json(api_json::ok())),
        MoreAnswer::Refused => Err(ApiError::new(StatusCode::CONFLICT, "That search cannot get more results.")),
        MoreAnswer::Unknown => Err(ApiError::new(StatusCode::NOT_FOUND, "No such search")),
    }
}

async fn shared_files(State(engine): State<Engine>) -> Json<Value> {
    Json(Value::Array(engine.shared_files().await.iter().map(api_json::shared_file).collect()))
}

/// Adds the downloads of `links` (one link or a list), paused when `paused` says so.
async fn add_downloads(State(engine): State<Engine>, Json(body): Json<Value>) -> Result<Response, ApiError> {
    let texts: Vec<&str> = match body.get("links") {
        Some(Value::Array(links)) => links.iter().map(|link| link.as_str().unwrap_or_default()).collect(),
        Some(Value::String(link)) => vec![link.as_str()],
        _ => return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing links")),
    };
    let mut links = Vec::with_capacity(texts.len());
    for text in texts {
        match Ed2kLink::try_parse(text.trim()) {
            Some(Ed2kLink::File(link)) => links.push(link),
            _ => return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("Not an eD2K file link: {text}"))),
        }
    }
    let paused = match body.get("paused") {
        Some(Value::Bool(paused)) => Some(*paused),
        None | Some(Value::Null) => None,
        Some(_) => return Err(ApiError::new(StatusCode::BAD_REQUEST, "paused: a truth value")),
    };
    let mut added = Vec::with_capacity(links.len());
    for link in links {
        let info = engine
            .add_download(link, paused)
            .await
            .map_err(|reason| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, reason))?;
        added.push(api_json::download(&info, false));
    }
    Ok((StatusCode::CREATED, Json(Value::Array(added))).into_response())
}

/// The downloads; with `include=parts` each comes with the map of its parts.
async fn downloads(State(engine): State<Engine>, Query(query): Query<BTreeMap<String, String>>) -> Json<Value> {
    let with_parts = query.get("include").is_some_and(|include| include == "parts");
    Json(Value::Array(engine.downloads().await.iter().map(|info| api_json::download(info, with_parts)).collect()))
}

async fn sources(State(engine): State<Engine>) -> Json<Value> {
    Json(Value::Array(engine.sources().await.iter().map(api_json::source).collect()))
}

async fn pause_download(State(engine): State<Engine>, Path(hash): Path<String>) -> Result<Json<Value>, ApiError> {
    let info = engine.pause_download(file_hash(&hash)?).await.ok_or_else(no_download)?;
    Ok(Json(api_json::download(&info, false)))
}

async fn resume_download(State(engine): State<Engine>, Path(hash): Path<String>) -> Result<Json<Value>, ApiError> {
    let info = engine.resume_download(file_hash(&hash)?).await.ok_or_else(no_download)?;
    Ok(Json(api_json::download(&info, false)))
}

async fn cancel_download(State(engine): State<Engine>, Path(hash): Path<String>) -> Result<Json<Value>, ApiError> {
    if engine.cancel_download(file_hash(&hash)?).await { Ok(Json(api_json::ok())) } else { Err(no_download()) }
}

async fn clear_completed_downloads(State(engine): State<Engine>) -> Json<Value> {
    Json(json!({"removed": engine.clear_completed_downloads().await}))
}

async fn clear_completed_download(
    State(engine): State<Engine>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    if engine.clear_completed_download(file_hash(&hash)?).await { Ok(Json(api_json::ok())) } else { Err(no_download()) }
}

// A hash in a path: 32 hex digits; anything else names no download.
fn file_hash(text: &str) -> Result<[u8; 16], ApiError> {
    data_encoding::HEXUPPER_PERMISSIVE
        .decode(text.as_bytes())
        .ok()
        .and_then(|bytes| <[u8; 16]>::try_from(bytes).ok())
        .ok_or_else(no_download)
}

fn no_download() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "No such download")
}
