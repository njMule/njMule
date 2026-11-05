// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API (`openapi.yaml`): the calls the front ends make, answered from the engine.

use std::collections::BTreeMap;

use axum::extract::{Path, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::Value;

use crate::engine::Engine;
use crate::remote::{api_json, event_streams};

/// The calls, relative to the API's base path.
pub fn router(engine: Engine) -> Router {
    Router::new()
        .route("/preferences", get(preferences).patch(update_preferences))
        .route("/schedule", get(|| async { Json(api_json::schedule()) }))
        .route("/update", get(|| async { Json(api_json::update()) }))
        .route("/events", get(events))
        .route("/clients/{address}/hello", post(hello))
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
