// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The remote-control API (`openapi.yaml`): the calls the front ends make, answered from the engine.

use std::cmp::Reverse;
use std::collections::{BTreeMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use axum::extract::rejection::JsonRejection;
use axum::extract::{FromRequest, Path, Query, Request, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post, put};
use axum::{Extension, Json, Router};
use reqwest::Url;
use serde_json::{Value, json};
use tokio::sync::{Semaphore, broadcast, watch};

use crate::api::category::Category;
use crate::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use crate::api::ed2k_link::Ed2kLink;
use crate::api::engine_event::EngineEvent;
use crate::api::file_history::FileHistory;
use crate::api::file_names;
use crate::api::list_source::{ListError, ListSource};
use crate::api::nj_mule_engine::NjMuleEngine;
use crate::api::shared_directory::{self, SharedDirectory};
use crate::api::upload_priority::UploadPriority;
use crate::api::{nj_mule_info, search_request};
use crate::config::bind_interface;
use crate::config::preferences::Preferences;
use crate::config::schedule::Schedule;
use crate::engine::NotesError;
use crate::engine::ip_filter_lists;
use crate::engine::network::{ConnectError, MoreAnswer};
use crate::remote::rate_history::{self, RateHistory};
use crate::remote::recent_log::RecentLog;
use crate::remote::search_collector::{Asked, SearchCollector};
use crate::remote::{api_json, api_reader, event_streams};
use crate::search::search_manager::{Scope, StartError};
use crate::server::server_list::{Address, Priority};

// The searches started through the API, with the results the engine's events bring.
type Searches = Arc<Mutex<SearchCollector>>;
// The latest lines of the user's log.
type Log = Arc<Mutex<RecentLog>>;
// The rates of each second, which the API reads from the engine's status.
type Rates = Arc<Mutex<RateHistory>>;

/// What stops njMule when the API is asked to: the program around the engine gives it.
pub type Exit = Arc<dyn Fn() + Send + Sync>;

#[derive(Clone)]
struct Exiting(Option<Exit>);

// How long between two samples of the rates.
const RATE_SAMPLE: Duration = Duration::from_secs(1);
// The span of the rates a front end gets when it asks for none.
const DEFAULT_RATE_SPAN: usize = 600;
const SECONDS_IN_A_MINUTE: usize = 60;

/// The version of the API, which its paths carry.
pub const VERSION: u32 = 1;

/// The calls, relative to the API's base path; `closing` says when the API closes, which ends the event streams.
pub fn router<E: NjMuleEngine>(engine: E, exit: Option<Exit>, closing: watch::Receiver<bool>) -> Router {
    let searches = Searches::default();
    let log = Log::default();
    collect(&engine, Arc::clone(&searches), Arc::clone(&log));
    let rates = Rates::default();
    sample_rates(&engine, Arc::clone(&rates));
    Router::new()
        .route("/", get(api_info))
        .route("/rates", get(rates_of))
        .route("/log", get(recent_log))
        .route("/limits", get(limits::<E>).put(set_limits::<E>))
        .route("/preferences/defaults", get(|| async { Json(api_json::preferences(&Preferences::default())) }))
        .route("/folders", get(folders::<E>))
        .route("/folders/check", post(check_folder))
        .route("/categories", get(categories::<E>).put(set_categories::<E>))
        .route("/shutdown", post(shutdown))
        .route("/status", get(status::<E>))
        .route("/preferences", get(preferences::<E>).patch(update_preferences::<E>))
        .route(
            "/preferences/passwords",
            get(|State(engine): State<E>| async move { Json(api_json::preference_passwords(&engine.preferences())) }),
        )
        .route("/schedule", get(schedule::<E>).put(set_schedule::<E>))
        .route("/ports/test", get(port_test::<E>).post(test_ports::<E>))
        .route("/ipfilter", post(update_ip_filter::<E>))
        .route("/ipfilter/lists", post(update_ip_filter_lists::<E>))
        .route("/interfaces", get(network_interfaces))
        .route("/update", get(|| async { Json(api_json::update()) }))
        .route("/events", get(events::<E>))
        .route(
            "/clients/known",
            get(|State(engine): State<E>| async move {
                Json(Value::Array(engine.known_clients().await.iter().map(api_json::known_client).collect()))
            }),
        )
        .route("/clients/{address}/hello", post(hello::<E>))
        .route("/clients/{address}/files", post(browse_client::<E>))
        .route("/searches", get(list_searches::<E>).post(start_search::<E>))
        .route("/searches/check", post(check_search))
        .route("/searches/spam", get(spam_marks::<E>))
        .route("/searches/saved", get(saved_searches::<E>).put(save_searches::<E>))
        .route("/searches/{id}", get(search_results::<E>).delete(stop_search::<E>))
        .route("/searches/{id}/more", post(more_results::<E>))
        .route("/searches/{id}/spam", post(mark_spam::<E>))
        .route("/searches/{id}/download", post(download_search_result::<E>))
        .route("/shared", get(shared_files::<E>))
        .route("/shared/{hash}/name", put(rename_shared_file::<E>))
        .route("/shared/{hash}/priority", put(set_upload_priority::<E>))
        .route(
            "/shared/directories",
            get(shared_directories::<E>).post(share_directory::<E>).delete(unshare_directory::<E>),
        )
        .route("/downloads", get(downloads::<E>).post(add_downloads::<E>))
        .route("/downloads/completed", delete(clear_completed_downloads::<E>))
        .route("/downloads/completed/{hash}", delete(clear_completed_download::<E>))
        .route("/names/cleanup", post(clean_up_name::<E>))
        .route("/downloads/{hash}", get(download::<E>).delete(cancel_download::<E>))
        .route("/downloads/{hash}/pause", post(pause_download::<E>))
        .route("/downloads/{hash}/resume", post(resume_download::<E>))
        .route("/downloads/{hash}/stop", post(stop_download::<E>))
        .route("/downloads/{hash}/restore", post(restore_download::<E>))
        .route("/downloads/{hash}/parts", get(download_parts::<E>))
        .route("/downloads/{hash}/data", get(download_data::<E>))
        .route("/downloads/{hash}/comments", get(peer_comments::<E>))
        .route("/downloads/{hash}/preview", put(set_download_preview::<E>))
        .route("/downloads/{hash}/autoswap", put(set_download_auto_swap::<E>))
        .route("/downloads/{hash}/swap", post(swap_sources::<E>))
        .route("/downloads/{hash}/priority", put(set_download_priority::<E>))
        .route("/downloads/{hash}/category", put(set_download_category::<E>))
        .route("/downloads/{hash}/name", put(rename_download::<E>))
        .route("/downloads/{hash}/sources", post(add_download_source::<E>))
        .route("/downloads/{hash}/sources/swap", post(swap_source_to_download::<E>))
        .route("/sources", get(sources::<E>))
        .route("/uploads", get(uploads::<E>))
        .route("/bans", get(bans::<E>))
        .route("/bans/{address}", axum::routing::delete(unban::<E>))
        .route("/files/{hash}/notes", get(list_kad_notes::<E>).post(search_kad_notes::<E>))
        .route(
            "/kad",
            get(|State(engine): State<E>| async move {
                Json(engine.kad().await.as_ref().map_or(Value::Null, api_json::kad))
            }),
        )
        .route(
            "/kad/contacts",
            get(|State(engine): State<E>| async move {
                Json(Value::Array(engine.kad_contacts().await.iter().map(api_json::kad_contact).collect()))
            }),
        )
        .route(
            "/kad/searches",
            get(|State(engine): State<E>| async move {
                Json(Value::Array(engine.kad_searches().await.iter().map(api_json::kad_search).collect()))
            }),
        )
        .route(
            "/kad/connect",
            post(|State(engine): State<E>| async move {
                engine.connect_kad().await;
                Json(api_json::ok())
            }),
        )
        .route(
            "/kad/disconnect",
            post(|State(engine): State<E>| async move {
                engine.disconnect_kad().await;
                Json(api_json::ok())
            }),
        )
        .route("/kad/recheck", post(recheck_kad::<E>))
        .route("/kad/bootstrap", post(bootstrap_kad::<E>))
        .route("/kad/nodes", post(add_kad_contacts::<E>))
        .route("/servers", get(servers::<E>).post(add_server::<E>))
        .route("/servers/{address}", axum::routing::delete(remove_server::<E>))
        .route("/servers/{address}/priority", axum::routing::put(set_server_priority::<E>))
        .route("/servers/import", post(import_servers::<E>))
        .route("/servers/lists", get(server_lists::<E>).put(set_server_lists::<E>))
        .route("/servers/connect", post(connect_server::<E>))
        .route("/servers/disconnect", post(disconnect_server::<E>))
        .route("/servers/{address}/static", axum::routing::put(set_static_server::<E>))
        .fallback(|| async { ApiError::new(StatusCode::NOT_FOUND, "No such call") })
        .method_not_allowed_fallback(|| async { ApiError::new(StatusCode::METHOD_NOT_ALLOWED, "No such method") })
        .layer(Extension(searches))
        .layer(Extension(log))
        .layer(Extension(rates))
        .layer(Extension(Exiting(exit)))
        .layer(Extension(Streams { open: Arc::new(Semaphore::new(event_streams::MAX_STREAMS)), closing }))
        .with_state(engine)
}

// Reads the rates from the engine's status each second until the engine stops; a status that does not come leaves
// its second out.
fn sample_rates<E: NjMuleEngine>(engine: &E, rates: Rates) {
    let engine = engine.clone();
    let stopped = engine.stopped();
    tokio::spawn(async move {
        tokio::pin!(stopped);
        let mut ticks = tokio::time::interval(RATE_SAMPLE);
        ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                () = &mut stopped => return,
                _ = ticks.tick() => {}
            }
            if let Ok(Some(status)) = tokio::time::timeout(RATE_SAMPLE, engine.status()).await {
                sampled(&rates).add(status.download_bytes_per_second, status.upload_bytes_per_second);
            }
        }
    });
}

fn sampled(rates: &Rates) -> MutexGuard<'_, RateHistory> {
    rates.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The rates of the last `span` seconds: each second's, or each minute's beyond an hour.
async fn rates_of(
    Extension(rates): Extension<Rates>,
    Query(query): Query<BTreeMap<String, String>>,
) -> Result<Json<Value>, ApiError> {
    let span = match query.get("span") {
        None => DEFAULT_RATE_SPAN,
        Some(text) => text
            .parse()
            .map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a number of seconds: {text}")))?,
    };
    let history = sampled(&rates);
    Ok(Json(if span > rate_history::SECONDS_KEPT {
        api_json::rates(60, &history.minutes(span.checked_div(SECONDS_IN_A_MINUTE).unwrap_or(0)))
    } else {
        api_json::rates(1, &history.seconds(span))
    }))
}

async fn limits<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(api_json::limits(&engine.preferences()))
}

/// Sets the speed limits given, in KiB/s, 0 for none; the others stay.
async fn set_limits<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let mut changes = BTreeMap::new();
    for (field, key) in [("uploadKiBps", "limits.uploadKiBps"), ("downloadKiBps", "limits.downloadKiBps")] {
        if body.get(field).is_some() {
            changes.insert(key.to_owned(), number(&body, field)?.to_string());
        }
    }
    let now = engine.update_preferences(&changes).map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    Ok(Json(api_json::limits(&now)))
}

/// The folders downloads go to, the profile's own where the preferences name none.
async fn folders<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    let preferences = engine.preferences();
    let dirs = engine.directories();
    Json(json!({
        "incoming": preferences.effective_incoming_dir(&dirs).to_string_lossy(),
        "temp": preferences.effective_temp_dir(&dirs).to_string_lossy(),
    }))
}

/// What a path typed names on the engine's computer: `folder`, `file`, `missing` or `invalid`. A relative one is taken
/// from where njMule runs, as the preferences take it.
async fn check_folder(Body(body): Body) -> Result<Json<Value>, ApiError> {
    let typed = text(&body, "path")?.trim();
    let kind = if typed.contains('\0') {
        "invalid"
    } else {
        match std::path::absolute(if typed.is_empty() { "." } else { typed }) {
            Ok(path) if path.is_dir() => "folder",
            Ok(path) if path.exists() => "file",
            Ok(_) => "missing",
            Err(_) => "invalid",
        }
    };
    Ok(Json(json!({"kind": kind})))
}

async fn shared_directories<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.shared_directories().await.iter().map(api_json::shared_directory).collect()))
}

/// Shares a folder of the engine's computer, with its subfolders unless `subdirectories` is false. One that may hold
/// personal or system files is shared only with `everything`.
async fn share_directory<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let typed = text(&body, "path")?;
    let dir = PathBuf::from(typed.trim());
    if typed.contains('\0') || !dir.is_absolute() || !dir.is_dir() {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("Not a folder of this computer: {typed}")));
    }
    let dir = shared_directory::absolute(&dir);
    if SharedDirectory::is_sensitive(&dir) && !body.get("everything").and_then(Value::as_bool).unwrap_or(false) {
        return Err(ApiError::new(
            StatusCode::CONFLICT,
            "This folder may hold personal or system files: share it with everything set to true.",
        ));
    }
    let subdirectories = body.get("subdirectories").and_then(Value::as_bool).unwrap_or(true);
    engine
        .share_directory(dir, subdirectories)
        .await
        .map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    Ok(Json(api_json::ok()))
}

/// Renames a shared file; others find it under the new name once it is published again.
async fn rename_shared_file<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let renamed = engine
        .rename_shared_file(hash, text(&body, "name")?.to_owned())
        .await
        .map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    shared_known(renamed)
}

/// Sets the upload priority of a shared file: `very_low`, `low`, `normal`, `high`, `release` or `auto`.
async fn set_upload_priority<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let asked = text(&body, "priority")?;
    let priority = UploadPriority::of_name(asked.trim())
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Unknown value: {asked}")))?;
    shared_known(engine.set_upload_priority(hash, priority).await)
}

fn shared_known(known: bool) -> Result<Json<Value>, ApiError> {
    if known { Ok(Json(api_json::ok())) } else { Err(ApiError::new(StatusCode::NOT_FOUND, "Not found.")) }
}

/// Stops sharing a folder shared, given by its `path` as listed.
async fn unshare_directory<E: NjMuleEngine>(
    State(engine): State<E>,
    Query(query): Query<BTreeMap<String, String>>,
) -> Result<Json<Value>, ApiError> {
    let path = query.get("path").ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, "Missing text: path"))?;
    let dir = engine
        .shared_directories()
        .await
        .into_iter()
        .map(|directory| directory.path)
        .find(|dir| dir.to_string_lossy() == path.as_str())
        .ok_or_else(|| ApiError::new(StatusCode::NOT_FOUND, "Not found."))?;
    engine
        .unshare_directory(dir)
        .await
        .map_err(|reason| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, reason))?;
    Ok(Json(api_json::ok()))
}

async fn categories<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.categories().await.iter().map(api_json::category).collect()))
}

/// Replaces the categories; answers them as they are kept.
async fn set_categories<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let Some(given) = body.as_array() else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Categories are a list"));
    };
    let mut all = Vec::with_capacity(given.len());
    for item in given {
        let priority = match item.get("priority") {
            None => DownloadPriority::Auto,
            Some(_) => {
                let asked = text(item, "priority")?;
                DownloadPriority::of_name(asked.trim())
                    .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Unknown value: {asked}")))?
            }
        };
        all.push(Category {
            id: number(item, "id")?,
            name: text(item, "name")?.to_owned(),
            incoming: item.get("incoming").and_then(Value::as_str).map(PathBuf::from),
            comment: item.get("comment").and_then(Value::as_str).unwrap_or_default().to_owned(),
            color: item.get("color").and_then(Value::as_u64).and_then(|c| u32::try_from(c).ok()).unwrap_or(0),
            priority,
            patterns: item.get("patterns").and_then(Value::as_str).unwrap_or_default().to_owned(),
            regex: item.get("regex").and_then(Value::as_bool).unwrap_or(false),
        });
    }
    engine.set_categories(all).await.map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    Ok(Json(Value::Array(engine.categories().await.iter().map(api_json::category).collect())))
}

async fn schedule<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(api_json::schedule(&engine.schedule().await, engine.active_rule().await.as_ref()))
}

/// Replaces the rules of the schedule; answers them with the one in force.
async fn set_schedule<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let Some(given) = body.get("rules").and_then(Value::as_array) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing list: rules"));
    };
    let rules = given
        .iter()
        .map(api_reader::rule)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    let now = engine.update_schedule(Schedule { rules }).await.map_err(|reason| {
        ApiError::new(StatusCode::SERVICE_UNAVAILABLE, format!("The engine cannot do it now: {reason}"))
    })?;
    Ok(Json(api_json::schedule(&now, engine.active_rule().await.as_ref())))
}

async fn port_test<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(engine.port_test().await.as_ref().map_or(Value::Null, api_json::port_test))
}

/// Starts a test of our ports, or follows the one running on them; answers it as it stands, without waiting for its
/// end.
async fn test_ports<E: NjMuleEngine>(State(engine): State<E>) -> Result<(StatusCode, Json<Value>), ApiError> {
    let test = engine
        .test_ports()
        .await
        .ok_or_else(|| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "The engine is stopping."))?;
    Ok((StatusCode::ACCEPTED, Json(api_json::port_test(&test))))
}

/// Replaces the IP filter with the list at an address.
async fn update_ip_filter<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let url = web_address(text(&body, "url")?)?;
    let added = engine.update_ip_filter(url).await.map_err(cannot_now)?;
    Ok(Json(json!({ "added": added })))
}

/// Replaces the IP filter with the lists of the preferences, joined into one.
async fn update_ip_filter_lists<E: NjMuleEngine>(State(engine): State<E>) -> Result<Json<Value>, ApiError> {
    let lists = ip_filter_lists::addresses(&engine.preferences().ip_filter_lists)
        .iter()
        .map(|line| web_address(line))
        .collect::<Result<Vec<_>, _>>()?;
    if lists.is_empty() {
        return Err(ApiError::new(StatusCode::CONFLICT, "No IP filter list is set."));
    }
    let added = engine.update_ip_filter_lists(lists).await.map_err(cannot_now)?;
    Ok(Json(json!({ "added": added })))
}

fn web_address(text: &str) -> Result<String, ApiError> {
    let text = text.trim();
    let url =
        Url::parse(text).map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, format!("Not an address: {text}")))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Only http and https addresses are taken."));
    }
    Ok(text.to_owned())
}

#[allow(clippy::needless_pass_by_value)]
fn cannot_now(reason: String) -> ApiError {
    ApiError::new(StatusCode::SERVICE_UNAVAILABLE, format!("The engine cannot do it now: {reason}"))
}

/// Stops njMule, after the answer.
async fn shutdown(Extension(exiting): Extension<Exiting>) -> Result<Response, ApiError> {
    let Exiting(Some(exit)) = exiting else {
        return Err(ApiError::new(StatusCode::NOT_IMPLEMENTED, "This njMule cannot be stopped from here."));
    };
    tracing::info!("Exiting as the remote-control API asked");
    exit();
    Ok((StatusCode::ACCEPTED, Json(api_json::ok())).into_response())
}

// Gathers the results of the searches and the user's log until the engine stops, those logged before first.
fn collect<E: NjMuleEngine>(engine: &E, searches: Searches, log: Log) {
    let subscription = engine.subscribe();
    let stopped = engine.stopped();
    tokio::spawn(async move {
        for event in subscription.missed {
            gather(&searches, &log, event);
        }
        let mut events = subscription.live;
        tokio::pin!(stopped);
        loop {
            let event = tokio::select! {
                () = &mut stopped => return,
                event = events.recv() => event,
            };
            match event {
                Ok(event) => gather(&searches, &log, event),
                Err(broadcast::error::RecvError::Lagged(_)) => {}
                Err(broadcast::error::RecvError::Closed) => return,
            }
        }
    });
}

fn gather(searches: &Searches, log: &Log, event: EngineEvent) {
    match event {
        EngineEvent::SearchResults { search, results } => collected(searches).add(search, results),
        EngineEvent::SearchEnded { search, .. } => collected(searches).ended(search),
        EngineEvent::Log(line) => log.lock().unwrap_or_else(PoisonError::into_inner).add(line),
        _ => {}
    }
}

/// The lines of the user's log numbered above `after`, oldest first.
async fn recent_log(
    Extension(log): Extension<Log>,
    Query(query): Query<BTreeMap<String, String>>,
) -> Result<Json<Value>, ApiError> {
    let after = match query.get("after") {
        None => 0,
        Some(text) => {
            text.parse().map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a line number: {text}")))?
        }
    };
    let lines = log.lock().unwrap_or_else(PoisonError::into_inner).after(after);
    Ok(Json(Value::Array(lines.iter().map(api_json::log_entry).collect())))
}

fn collected(searches: &Searches) -> MutexGuard<'_, SearchCollector> {
    searches.lock().unwrap_or_else(PoisonError::into_inner)
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

/// A JSON body; one that does not parse is malformed.
pub struct Body(pub Value);

impl<S: Send + Sync> FromRequest<S> for Body {
    type Rejection = ApiError;

    async fn from_request(request: Request, state: &S) -> Result<Self, Self::Rejection> {
        match Json::<Value>::from_request(request, state).await {
            Ok(Json(value)) => Ok(Self(value)),
            Err(JsonRejection::JsonSyntaxError(e)) => Err(ApiError::new(
                StatusCode::BAD_REQUEST,
                format!(
                    "Malformed JSON: {}",
                    std::error::Error::source(&e).map_or_else(|| e.body_text(), ToString::to_string)
                ),
            )),
            Err(rejection) => Err(ApiError::new(rejection.status(), rejection.body_text())),
        }
    }
}

fn text<'a>(body: &'a Value, name: &str) -> Result<&'a str, ApiError> {
    body.get(name)
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Missing text: {name}")))
}

fn flag(body: &Value, name: &str) -> Result<bool, ApiError> {
    body.get(name)
        .and_then(Value::as_bool)
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Missing truth value: {name}")))
}

fn number(body: &Value, name: &str) -> Result<u32, ApiError> {
    body.get(name)
        .and_then(Value::as_u64)
        .and_then(|n| u32::try_from(n).ok())
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Missing number: {name}")))
}

/// Name and version of njMule and of the API, and the system it runs on, which a report of a problem needs.
async fn api_info() -> Json<Value> {
    let system = sysinfo::System::name().unwrap_or_default();
    let version = sysinfo::System::os_version().unwrap_or_default();
    Json(json!({
        "name": nj_mule_info::NAME,
        "version": nj_mule_info::version(),
        "api": VERSION,
        "system": {"name": system, "version": version, "arch": std::env::consts::ARCH},
    }))
}

async fn status<E: NjMuleEngine>(State(engine): State<E>) -> Result<Json<Value>, ApiError> {
    let status = engine
        .status()
        .await
        .ok_or_else(|| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "The engine is stopping."))?;
    let preferences = engine.preferences();
    let dirs = engine.directories();
    let incoming = preferences.effective_incoming_dir(&dirs);
    let temp = preferences.effective_temp_dir(&dirs);
    // Asked of the system off the engine's task: a network drive may take a moment.
    let (incoming_free, temp_free) =
        tokio::task::spawn_blocking(move || (fs4::available_space(&incoming).ok(), fs4::available_space(&temp).ok()))
            .await
            .unwrap_or_default();
    let reach = api_json::Reach { upnp: preferences.upnp, router_traversal: preferences.router_traversal() };
    Ok(Json(api_json::status(&status, incoming_free, temp_free, reach)))
}

async fn preferences<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(api_json::preferences(&engine.preferences()))
}

async fn update_preferences<E: NjMuleEngine>(
    State(engine): State<E>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
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

/// What the event streams share: their places, and when the API closes.
#[derive(Clone)]
struct Streams {
    open: Arc<Semaphore>,
    closing: watch::Receiver<bool>,
}

/// Follows the engine's events; `types` takes the prefixes of the names wanted, separated by commas.
async fn events<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(streams): Extension<Streams>,
    Query(parameters): Query<Vec<(String, String)>>,
) -> Result<Response, ApiError> {
    let wanted: Vec<String> = parameters
        .iter()
        .filter(|(name, _)| name == "types")
        .flat_map(|(_, value)| value.split(','))
        .map(str::trim)
        .filter(|prefix| !prefix.is_empty())
        .map(str::to_owned)
        .collect();
    let permit = Arc::clone(&streams.open)
        .try_acquire_owned()
        .map_err(|_| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "Too many event streams."))?;
    Ok(event_streams::stream(&engine, wanted, streams.closing, permit).into_response())
}

async fn hello<E: NjMuleEngine>(State(engine): State<E>, Path(address): Path<String>) -> Result<Json<Value>, ApiError> {
    let target = client_address(&address).await?;
    let info = engine.hello(target).await.map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.to_string()))?;
    Ok(Json(api_json::peer_info(&info)))
}

async fn browse_client<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(address): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let target = client_address(&address).await?;
    let files = engine.browse(target).await.map_err(|e| ApiError::new(StatusCode::BAD_GATEWAY, e.to_string()))?;
    Ok(Json(Value::Array(files.iter().filter_map(api_json::shared_by_client).collect())))
}

// A client's `host:port`, its name looked up.
async fn client_address(address: &str) -> Result<std::net::SocketAddr, ApiError> {
    tokio::net::lookup_host(address)
        .await
        .ok()
        .and_then(|mut found| found.next())
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a client address: {address}")))
}

async fn servers<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.servers().await.iter().map(api_json::server).collect()))
}

/// The servers of a `server.met` sent in Base64 (`data`) or read from the engine's computer (`path`).
async fn import_servers<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let added = engine.add_servers(list_source(&body)?).await.map_err(list_failure)?;
    Ok(Json(json!({"added": added})))
}

async fn server_lists<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(json!({"addresses": engine.server_list_addresses().await}))
}

/// Replaces the addresses of the server lists; answers them as kept.
async fn set_server_lists<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let Some(given) = body.get("addresses").and_then(Value::as_array) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing list: addresses"));
    };
    let addresses = given
        .iter()
        .map(|address| web_address(address.as_str().unwrap_or_default()))
        .collect::<Result<Vec<_>, _>>()?;
    engine.set_server_list_addresses(addresses.clone()).await.map_err(cannot_now)?;
    Ok(Json(json!({"addresses": addresses})))
}

async fn recheck_kad<E: NjMuleEngine>(State(engine): State<E>) -> Result<Json<Value>, ApiError> {
    if engine.recheck_kad().await {
        Ok(Json(api_json::ok()))
    } else {
        Err(ApiError::new(StatusCode::CONFLICT, "Kad is not running."))
    }
}

/// Joins Kad through the node at `address`, an IPv4 address or a name with its UDP port.
async fn bootstrap_kad<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let Some(text) = body.get("address").and_then(Value::as_str) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Give the node's address."));
    };
    let invalid = || ApiError::new(StatusCode::BAD_REQUEST, format!("Not a node's address: {text}"));
    let node = tokio::net::lookup_host(text)
        .await
        .map_err(|_| invalid())?
        .find_map(|address| match address {
            std::net::SocketAddr::V4(v4) if v4.port() != 0 => Some(v4),
            _ => None,
        })
        .ok_or_else(invalid)?;
    engine.bootstrap_kad(node).await.map_err(cannot_now)?;
    Ok(Json(api_json::ok()))
}

async fn uploads<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.uploads().await.iter().map(api_json::upload).collect()))
}

async fn bans<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.bans().await.iter().map(api_json::ban).collect()))
}

/// Lifts the ban on an IPv4 address.
async fn unban<E: NjMuleEngine>(State(engine): State<E>, Path(address): Path<String>) -> Result<Json<Value>, ApiError> {
    match engine.unban(address).await {
        Ok(true) => Ok(Json(api_json::ok())),
        Ok(false) => Err(ApiError::new(StatusCode::NOT_FOUND, "No such ban")),
        Err(reason) => Err(ApiError::new(StatusCode::BAD_REQUEST, reason)),
    }
}

/// The contacts of a `nodes.dat` sent in Base64 (`data`) or read from the engine's computer (`path`).
async fn add_kad_contacts<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let added = engine.add_kad_contacts(list_source(&body)?).await.map_err(list_failure)?;
    Ok(Json(json!({"added": added})))
}

// A list from the web (`url`), sent in Base64 (`data`) or read from the engine's computer (`path`).
fn list_source(body: &Value) -> Result<ListSource, ApiError> {
    if let Some(url) = body.get("url").and_then(Value::as_str) {
        Ok(ListSource::Web(web_address(url)?))
    } else if let Some(data) = body.get("data").and_then(Value::as_str) {
        // Padding is accepted, not required.
        data_encoding::BASE64_NOPAD
            .decode(data.trim().trim_end_matches('=').as_bytes())
            .map(ListSource::Data)
            .map_err(|_| ApiError::new(StatusCode::BAD_REQUEST, "The data is not Base64."))
    } else if let Some(path) = body.get("path").and_then(Value::as_str) {
        let file = PathBuf::from(path);
        if !file.is_file() {
            return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("No file at {path}")));
        }
        Ok(ListSource::File(file))
    } else {
        Err(ApiError::new(StatusCode::BAD_REQUEST, "Give the list as an address, as data or as a path."))
    }
}

#[allow(clippy::needless_pass_by_value)]
fn list_failure(error: ListError) -> ApiError {
    match error {
        ListError::NotAList(reason) => ApiError::new(StatusCode::BAD_REQUEST, reason),
        ListError::Failed(reason) => cannot_now(reason),
    }
}

async fn connect_server<E: NjMuleEngine>(
    State(engine): State<E>,
    body: Option<Json<Value>>,
) -> Result<Json<Value>, ApiError> {
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

async fn disconnect_server<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    engine.disconnect_server().await;
    Json(api_json::ok())
}

async fn set_static_server<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(address): Path<String>,
    Body(body): Body,
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

/// Adds a server by its address; 409 when it is known already.
async fn add_server<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Response, ApiError> {
    let address = server_address(text(&body, "address")?)?;
    if !engine.add_server(address).await {
        return Err(ApiError::new(StatusCode::CONFLICT, "That server is known already."));
    }
    Ok((StatusCode::CREATED, Json(api_json::ok())).into_response())
}

async fn remove_server<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(address): Path<String>,
) -> Result<Json<Value>, ApiError> {
    known_server(engine.remove_server(server_address(&address)?).await)
}

/// Sets the priority a server is tried with: `low`, `normal` or `high`.
async fn set_server_priority<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(address): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let address = server_address(&address)?;
    let asked = text(&body, "priority")?;
    let priority = [Priority::Low, Priority::Normal, Priority::High]
        .into_iter()
        .find(|priority| priority.name().eq_ignore_ascii_case(asked.trim()))
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Unknown value: {asked}")))?;
    known_server(engine.set_server_priority(address, priority).await)
}

fn known_server(known: bool) -> Result<Json<Value>, ApiError> {
    if known { Ok(Json(api_json::ok())) } else { Err(ApiError::new(StatusCode::NOT_FOUND, "No such server")) }
}

fn server_address(text: &str) -> Result<Address, ApiError> {
    Address::parse(text).ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a server address: {text}")))
}

/// Starts a search: `query`, and `scope` (`server` by default, `global`, `kad` or `automatic`).
async fn start_search<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
    Body(body): Body,
) -> Result<Response, ApiError> {
    let Some(query) = body.get("query").and_then(Value::as_str) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing text: query"));
    };
    let asked = body.get("scope").and_then(Value::as_str).unwrap_or("server");
    let scope = if asked.eq_ignore_ascii_case("automatic") {
        Some(automatic_scope(&engine).await?)
    } else {
        Some(
            Scope::parse(asked)
                .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a search scope: {asked}")))?,
        )
    };
    let expression = query.trim().to_owned();
    let started = engine.start_search(expression.clone(), scope).await.map_err(|e| match e {
        StartError::Syntax(error) => ApiError::new(StatusCode::BAD_REQUEST, error.to_string()),
        StartError::NotForKad(reason) => ApiError::new(StatusCode::BAD_REQUEST, reason),
        StartError::Unavailable(reason) => ApiError::new(StatusCode::CONFLICT, reason),
    })?;
    collected(&searches).started(started, Asked { expression, scope: asked.to_ascii_lowercase() });
    Ok((StatusCode::CREATED, Json(json!({"id": started}))).into_response())
}

// As the desktop app picks it: the network connected, Kad with both unless the server is a static or a big one.
async fn automatic_scope<E: NjMuleEngine>(engine: &E) -> Result<Scope, ApiError> {
    let status = engine
        .status()
        .await
        .ok_or_else(|| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, "The engine is stopping."))?;
    let kad_connected = status.kad.as_ref().is_some_and(|kad| kad.connected);
    let known = engine.servers().await.len();
    search_request::automatic(status.server.is_some(), kad_connected, status.server.as_ref(), known)
        .ok_or_else(|| ApiError::new(StatusCode::CONFLICT, "Not connected to a server or to Kad."))
}

/// Tells whether a search would take an expression, before it starts.
async fn check_search(Body(body): Body) -> Result<Json<Value>, ApiError> {
    search_request::check_expression(text(&body, "query")?.trim())
        .map_err(|error| ApiError::new(StatusCode::BAD_REQUEST, error.to_string()))?;
    Ok(Json(api_json::ok()))
}

async fn list_searches<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
) -> Json<Value> {
    let listed = collected(&searches).searches();
    let mut answer = Vec::with_capacity(listed.len());
    for search in listed {
        // Those the server holds more results of wait for them to be asked, as in the search's results.
        let running = search.running && !engine.has_more_results(search.id).await;
        answer.push(json!({
            "id": search.id,
            "query": search.asked.expression,
            "scope": search.asked.scope,
            "running": running,
            "results": search.results,
        }));
    }
    Json(Value::Array(answer))
}

/// The results of a search, those with the most sources first and spam last.
async fn search_results<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
    Path(id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let id = search_id(&id)?;
    let found = collected(&searches).results(id).ok_or_else(no_search)?;
    // Rated now, as the files marked since they came make them.
    let mut rated = engine.rate_spam(found).await;
    rated.sort_by_key(|result| (result.is_spam(), Reverse(result.sources)));
    let more = engine.has_more_results(id).await;
    // A search the server holds more results of waits for them to be asked: nothing comes until then.
    let running = collected(&searches).running(id) && !more;
    let known = Known::now(&engine).await;
    let results: Vec<Value> = rated.iter().map(|result| api_json::found(result, known.of(&result.hash))).collect();
    Ok(Json(json!({"id": id, "running": running, "more": more, "results": results})))
}

// What we know of files: downloading, shared, or downloaded or cancelled before; the first that holds wins.
struct Known {
    downloading: HashSet<[u8; 16]>,
    shared: HashSet<[u8; 16]>,
    history: FileHistory,
}

impl Known {
    async fn now<E: NjMuleEngine>(engine: &E) -> Self {
        let downloading = engine
            .downloads()
            .await
            .iter()
            .filter(|download| download.status != DownloadStatus::Complete)
            .map(|download| download.hash)
            .collect();
        let shared = engine.shared_files().await.iter().map(|file| file.hash).collect();
        Self { downloading, shared, history: engine.file_history().await }
    }

    fn of(&self, file: &[u8; 16]) -> Option<&'static str> {
        if self.downloading.contains(file) {
            Some("downloading")
        } else if self.shared.contains(file) {
            Some("shared")
        } else if self.history.downloaded(file) {
            Some("downloaded")
        } else if self.history.cancelled(file) {
            Some("cancelled")
        } else {
            None
        }
    }
}

/// Stops a search and forgets its results.
async fn stop_search<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
    Path(id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let id = known_search(&searches, &id)?;
    engine.stop_search(id).await;
    collected(&searches).forget(id);
    Ok(Json(api_json::ok()))
}

async fn download_search_result<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
    Path(id): Path<String>,
    Body(body): Body,
) -> Result<Response, ApiError> {
    let id = known_search(&searches, &id)?;
    let hash = result_hash(text(&body, "hash")?)?;
    let result = collected(&searches).result(id, &hash).ok_or_else(gone)?;
    let link = result.link().map_err(|e| ApiError::new(StatusCode::BAD_REQUEST, e.to_string()))?;
    let info = engine
        .add_download(link, None)
        .await
        .map_err(|reason| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, reason))?;
    Ok((StatusCode::CREATED, Json(api_json::download(&info, false))).into_response())
}

/// Marks results as spam, or as not spam with `"spam": false`.
async fn mark_spam<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
    Path(id): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let id = known_search(&searches, &id)?;
    let Some(hashes) = body.get("hashes").and_then(Value::as_array) else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Missing list: hashes"));
    };
    let mut marked = Vec::with_capacity(hashes.len());
    for hash in hashes {
        let hash = result_hash(hash.as_str().unwrap_or_default())?;
        marked.push(collected(&searches).result(id, &hash).ok_or_else(gone)?);
    }
    let is_spam = body.get("spam").and_then(Value::as_bool).unwrap_or(true);
    engine.mark_spam(marked, is_spam).await;
    Ok(Json(api_json::ok()))
}

/// The search tabs left open at the last exit, when the preferences keep them.
async fn saved_searches<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.saved_searches().await.iter().map(api_json::saved_search).collect()))
}

/// Keeps these searches for the next start, in place of those kept before.
async fn save_searches<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let Some(given) = body.as_array() else {
        return Err(ApiError::new(StatusCode::BAD_REQUEST, "Saved searches are a list"));
    };
    let searches = given
        .iter()
        .map(api_reader::saved_search)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    engine
        .save_searches(searches)
        .await
        .map_err(|reason| ApiError::new(StatusCode::INTERNAL_SERVER_ERROR, reason))?;
    Ok(Json(api_json::ok()))
}

async fn spam_marks<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(api_json::spam_marks(&engine.spam_marks().await))
}

// A search started here; any other is not found.
fn known_search(searches: &Searches, id: &str) -> Result<u64, ApiError> {
    let id = search_id(id)?;
    collected(searches).results(id).map(|_| id).ok_or_else(no_search)
}

fn search_id(text: &str) -> Result<u64, ApiError> {
    text.parse().map_err(|_| no_search())
}

fn no_search() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "No such search")
}

fn gone() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "That result is gone: search again.")
}

// The hash of a result in a body: 32 hex digits.
fn result_hash(text: &str) -> Result<[u8; 16], ApiError> {
    data_encoding::HEXUPPER_PERMISSIVE
        .decode(text.as_bytes())
        .ok()
        .and_then(|bytes| <[u8; 16]>::try_from(bytes).ok())
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Not a file hash: {text}")))
}

async fn more_results<E: NjMuleEngine>(
    State(engine): State<E>,
    Extension(searches): Extension<Searches>,
    Path(id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let id = known_search(&searches, &id)?;
    match engine.more_results(id).await {
        MoreAnswer::Asked => {
            collected(&searches).resumed(id);
            Ok(Json(api_json::ok()))
        }
        MoreAnswer::Refused => Err(ApiError::new(StatusCode::CONFLICT, "That search cannot get more results.")),
        MoreAnswer::Unknown => Err(ApiError::new(StatusCode::NOT_FOUND, "No such search")),
    }
}

async fn shared_files<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.shared_files().await.iter().map(api_json::shared_file).collect()))
}

/// Adds the downloads of `links` (one link or a list), paused when `paused` says so.
async fn add_downloads<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Response, ApiError> {
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
async fn downloads<E: NjMuleEngine>(
    State(engine): State<E>,
    Query(query): Query<BTreeMap<String, String>>,
) -> Json<Value> {
    let with_parts = query.get("include").is_some_and(|include| include == "parts");
    Json(Value::Array(engine.downloads().await.iter().map(|info| api_json::download(info, with_parts)).collect()))
}

async fn sources<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(Value::Array(engine.sources().await.iter().map(api_json::source).collect()))
}

async fn pause_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let info = engine.pause_download(file_hash(&hash)?).await.ok_or_else(no_download)?;
    Ok(Json(api_json::download(&info, false)))
}

async fn resume_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let info = engine.resume_download(file_hash(&hash)?).await.ok_or_else(no_download)?;
    Ok(Json(api_json::download(&info, false)))
}

async fn cancel_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    if engine.cancel_download(file_hash(&hash)?).await { Ok(Json(api_json::ok())) } else { Err(no_download()) }
}

async fn download<E: NjMuleEngine>(State(engine): State<E>, Path(hash): Path<String>) -> Result<Json<Value>, ApiError> {
    let info = known_download(&engine, file_hash(&hash)?).await?;
    Ok(Json(api_json::download(&info, false)))
}

async fn download_parts<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let info = known_download(&engine, file_hash(&hash)?).await?;
    Ok(Json(api_json::download_parts(&info)))
}

// A download as it is now; an unknown one is not found.
async fn known_download<E: NjMuleEngine>(engine: &E, hash: [u8; 16]) -> Result<DownloadInfo, ApiError> {
    engine.downloads().await.into_iter().find(|info| info.hash == hash).ok_or_else(no_download)
}

// A change to a download: an unknown one is not found, the others are answered as they are now.
async fn changed<E: NjMuleEngine>(engine: &E, hash: [u8; 16], made: bool) -> Result<Json<Value>, ApiError> {
    if !made {
        return Err(no_download());
    }
    let info = known_download(engine, hash).await?;
    Ok(Json(api_json::download(&info, false)))
}

async fn stop_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let made = engine.stop_download(hash).await;
    changed(&engine, hash, made).await
}

async fn restore_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let made = engine.restore_download(hash).await;
    changed(&engine, hash, made).await
}

async fn set_download_preview<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let made = engine.set_download_preview(hash, flag(&body, "previewFirst")?).await;
    changed(&engine, hash, made).await
}

async fn set_download_auto_swap<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let made = engine.set_download_auto_swap(hash, flag(&body, "autoSwap")?).await;
    changed(&engine, hash, made).await
}

async fn set_download_priority<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let asked = text(&body, "priority")?;
    let priority = DownloadPriority::of_name(asked.trim())
        .ok_or_else(|| ApiError::new(StatusCode::BAD_REQUEST, format!("Unknown value: {asked}")))?;
    let made = engine.set_download_priority(hash, priority).await;
    changed(&engine, hash, made).await
}

async fn set_download_category<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let made = engine.set_download_category(hash, number(&body, "category")?).await;
    changed(&engine, hash, made).await
}

async fn rename_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let made = engine
        .rename_download(hash, text(&body, "name")?.to_owned())
        .await
        .map_err(|reason| ApiError::new(StatusCode::BAD_REQUEST, reason))?;
    changed(&engine, hash, made).await
}

async fn add_download_source<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let source = client_address(text(&body, "address")?.trim()).await?;
    let made = engine.add_source(hash, source).await;
    changed(&engine, hash, made).await
}

async fn swap_source_to_download<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let source = client_address(text(&body, "address")?.trim()).await?;
    Ok(Json(json!({"moved": engine.swap_source_to_download(hash, source).await})))
}

/// Moves the sources shared with other downloads: `here`, to this one, or `away`, this one's to the others.
async fn swap_sources<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
    Body(body): Body,
) -> Result<Json<Value>, ApiError> {
    let hash = file_hash(&hash)?;
    let moved = match text(&body, "direction")? {
        "here" => engine.swap_sources_to_download(hash).await,
        "away" => engine.swap_sources_from_download(hash).await,
        other => return Err(ApiError::new(StatusCode::BAD_REQUEST, format!("Unknown value: {other}"))),
    };
    Ok(Json(json!({"added": moved})))
}

/// Where the data of a download is, which a player on this computer can show while it grows.
async fn download_data<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let data = engine
        .download_data(file_hash(&hash)?)
        .await
        .ok_or_else(|| ApiError::new(StatusCode::NOT_FOUND, "The start of the file has not come yet."))?;
    let data = std::path::absolute(&data).unwrap_or(data);
    Ok(Json(json!({"path": data.to_string_lossy()})))
}

async fn peer_comments<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let comments = engine.peer_comments(file_hash(&hash)?).await;
    Ok(Json(Value::Array(comments.iter().map(|c| api_json::comment(&c.user, &c.file_name, &c.comment)).collect())))
}

// The words taken out are those of the preferences, as for the names of new downloads.
async fn clean_up_name<E: NjMuleEngine>(State(engine): State<E>, Body(body): Body) -> Result<Json<Value>, ApiError> {
    let clean = file_names::clean_up(text(&body, "name")?, &engine.preferences().name_cleanups);
    Ok(Json(json!({"name": clean})))
}

async fn list_kad_notes<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let notes = engine.kad_notes(file_hash(&hash)?).await;
    Ok(Json(Value::Array(notes.iter().map(api_json::kad_note).collect())))
}

async fn search_kad_notes<E: NjMuleEngine>(
    State(engine): State<E>,
    Path(hash): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let notes = engine.search_kad_notes(file_hash(&hash)?).await.map_err(|refused| match refused {
        NotesError::UnknownFile => {
            ApiError::new(StatusCode::BAD_REQUEST, format!("Neither downloaded nor shared: {hash}"))
        }
        NotesError::Kad(reason) => {
            ApiError::new(StatusCode::SERVICE_UNAVAILABLE, format!("The engine cannot do it now: {reason}"))
        }
    })?;
    Ok(Json(Value::Array(notes.iter().map(api_json::kad_note).collect())))
}

async fn clear_completed_downloads<E: NjMuleEngine>(State(engine): State<E>) -> Json<Value> {
    Json(json!({"removed": engine.clear_completed_downloads().await}))
}

async fn clear_completed_download<E: NjMuleEngine>(
    State(engine): State<E>,
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

// The interfaces worth choosing, listed off the engine's task: the system may take a moment.
async fn network_interfaces() -> Result<Response, ApiError> {
    let found = tokio::task::spawn_blocking(bind_interface::interfaces)
        .await
        .map_err(|e| ApiError::new(StatusCode::SERVICE_UNAVAILABLE, format!("The interfaces cannot be listed: {e}")))?;
    let choices: Vec<Value> = bind_interface::choices(&found).into_iter().map(api_json::network_interface).collect();
    Ok(Json(choices).into_response())
}
