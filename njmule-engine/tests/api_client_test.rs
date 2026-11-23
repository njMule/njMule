// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tests for the remote-control HTTP client (`ApiClient`), update installation help (`update_help`),
//! and `DesktopError`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::error::Error;
use std::fs;
use std::io::{self, Write};
use std::path::Path;

use axum::Router;
use axum::body::Bytes;
use axum::http::{HeaderMap, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{delete, get, post, put};
use flate2::Compression;
use flate2::write::GzEncoder;
use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::remote::api_client::{ApiClient, ApiError};
use njmule_engine::remote::api_token;
use njmule_engine::remote::desktop_integration::DesktopError;
use njmule_engine::remote::settings_file::{ChangeError, SettingsFile, WebChange};
use njmule_engine::remote::update_help;
use njmule_engine::remote::web_app::WebApp;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use serde_json::{Value, json};
use tempfile::TempDir;
use tokio::net::TcpListener;

async fn spawn_server(router: Router) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        let _ = axum::serve(listener, router).await;
    });
    format!("http://{addr}/api/v1")
}

#[tokio::test]
async fn should_perform_get_post_put_delete_and_handle_empty_and_error_responses() {
    let router = Router::new()
        .route(
            "/api/v1/echo",
            get(|headers: HeaderMap| async move {
                let auth = headers.get("authorization").and_then(|v| v.to_str().ok()).unwrap_or("");
                (StatusCode::OK, json!({ "auth": auth }).to_string())
            }),
        )
        .route(
            "/api/v1/items",
            post(|body: Bytes| async move {
                let parsed: Value = serde_json::from_slice(&body).unwrap();
                (StatusCode::CREATED, json!({ "created": parsed }).to_string())
            }),
        )
        .route(
            "/api/v1/items/1",
            put(|body: Bytes| async move {
                let parsed: Value = serde_json::from_slice(&body).unwrap();
                (StatusCode::OK, json!({ "updated": parsed }).to_string())
            }),
        )
        .route("/api/v1/items/1", delete(|| async { StatusCode::NO_CONTENT }))
        .route("/api/v1/blank", get(|| async { (StatusCode::OK, "   ") }))
        .route(
            "/api/v1/json-error",
            get(|| async { (StatusCode::BAD_REQUEST, json!({ "error": "bad input" }).to_string()) }),
        )
        .route("/api/v1/plain-error", get(|| async { (StatusCode::INTERNAL_SERVER_ERROR, "oops") }))
        .route("/api/v1/bad-json", get(|| async { (StatusCode::OK, "{not json") }));

    let base = spawn_server(router).await;
    let client = ApiClient::new(base, "secret-token");

    assert_eq!(client.get("/echo").await.unwrap(), json!({ "auth": "Bearer secret-token" }));
    assert_eq!(client.post("/items", json!({ "a": 1 })).await.unwrap(), json!({ "created": { "a": 1 } }));
    assert_eq!(client.put("/items/1", json!({ "a": 2 })).await.unwrap(), json!({ "updated": { "a": 2 } }));
    assert_eq!(client.delete("/items/1").await.unwrap(), Value::Null);
    assert_eq!(client.get("/blank").await.unwrap(), Value::Null);

    let err = client.get("/json-error").await.unwrap_err();
    assert!(matches!(err, ApiError::Failure { status: 400, ref message } if message == "bad input"));
    assert_eq!(err.to_string(), "bad input");

    let err = client.get("/plain-error").await.unwrap_err();
    assert!(matches!(err, ApiError::Failure { status: 500, ref message } if message == "HTTP 500"));
    assert_eq!(err.to_string(), "HTTP 500");

    let err = client.get("/bad-json").await.unwrap_err();
    assert!(matches!(err, ApiError::Io(_)));
    assert!(err.to_string().contains("Malformed answer"));
}

#[tokio::test]
async fn should_stream_sse_events_and_report_non_200_errors() {
    let router = Router::new().route(
        "/api/v1/events",
        get(|| async {
            let body = concat!(
                ": keep-alive\n\n",
                "event: status\n",
                "data: {\"n\":1}\n\n",
                "data: {\"n\":2}\n\n",
                "event: done\n",
                "data: {\"n\":3}\n\n",
            );
            ([(axum::http::header::CONTENT_TYPE, "text/event-stream")], body).into_response()
        }),
    );

    let base = spawn_server(router).await;
    let client = ApiClient::new(base, "tok");

    // Stop early on the second event.
    let mut seen = Vec::new();
    client
        .events("status,done", |name, value| {
            seen.push((name.to_owned(), value));
            Ok(seen.len() < 2)
        })
        .await
        .unwrap();
    assert_eq!(seen, vec![("status".to_owned(), json!({"n": 1})), ("message".to_owned(), json!({"n": 2}))]);

    // Read until stream end.
    let mut all = Vec::new();
    client
        .events("status", |name, value| {
            all.push((name.to_owned(), value));
            Ok(true)
        })
        .await
        .unwrap();
    assert_eq!(all.len(), 3);
    assert_eq!(all[2].0, "done");

    // Non-200 error on /events.
    let err_router = Router::new().route(
        "/api/v1/events",
        get(|| async { (StatusCode::UNAUTHORIZED, json!({ "error": "unauthorized" }).to_string()) }),
    );
    let err_client = ApiClient::new(spawn_server(err_router).await, "bad");
    let err = err_client.events("status", |_, _| Ok(true)).await.unwrap_err();
    assert!(matches!(err, ApiError::Failure { status: 401, ref message } if message == "unauthorized"));
}

#[tokio::test]
async fn should_resolve_local_client_from_config_dir_and_report_io_errors() {
    let dir = TempDir::new().unwrap();
    // Without a token, local() returns None.
    assert!(ApiClient::local(dir.path()).unwrap().is_none());

    // With a token but API disabled / not usable, local() returns None.
    let _token = api_token::load_or_create(dir.path()).unwrap();
    assert!(ApiClient::local(dir.path()).unwrap().is_none());

    // With web UI enabled, password set, and API enabled, local() returns Some.
    let settings = WebUiSettings {
        enabled: true,
        api: true,
        password_hash: "hash".to_owned(),
        port: 4711,
        ..WebUiSettings::default()
    };
    settings.save(dir.path()).unwrap();
    assert!(ApiClient::local(dir.path()).unwrap().is_some());

    // Connection refused maps to ApiError::Io.
    let closed = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = closed.local_addr().unwrap().port();
    drop(closed);
    let err = ApiClient::new(format!("http://127.0.0.1:{port}/api/v1"), "t").get("/status").await.unwrap_err();
    assert!(matches!(err, ApiError::Io(_)));
    let from_io: ApiError = io::Error::other("boom").into();
    assert_eq!(from_io.to_string(), "boom");
}

#[test]
fn should_format_update_install_help_for_each_install_kind() {
    let url = "https://example.test/releases/njmule-1.0.0.deb?sig=1";
    assert!(
        update_help::how_to_install(InstallKind::Deb, Some(url), None).contains("sudo apt install ./njmule-1.0.0.deb")
    );
    assert!(update_help::how_to_install(InstallKind::Deb, None, None).contains("the .deb package"));
    assert!(
        update_help::how_to_install(InstallKind::Rpm, Some("https://example.test/njmule.rpm#top"), None)
            .contains("sudo dnf install ./njmule.rpm")
    );
    assert!(update_help::how_to_install(InstallKind::Rpm, None, None).contains("the .rpm package"));
    assert!(
        update_help::how_to_install(InstallKind::Synology, Some("https://example.test/njmule.spk"), None)
            .contains("Package Center")
    );
    assert!(update_help::how_to_install(InstallKind::Synology, None, None).contains("the .spk package"));
    assert!(update_help::how_to_install(InstallKind::Docker, None, None).contains("docker compose pull"));
    assert!(
        update_help::how_to_install(InstallKind::Tar, None, Some("https://example.test/release"))
            .contains("https://example.test/release")
    );
    assert!(update_help::how_to_install(InstallKind::Tar, None, None).contains("https://"));

    let path = Path::new("/tmp/njmule.pkg");
    assert!(update_help::how_to_install_file(InstallKind::Deb, path).contains("sudo apt install"));
    assert!(update_help::how_to_install_file(InstallKind::Rpm, path).contains("sudo dnf install"));
    assert!(update_help::how_to_install_file(InstallKind::Synology, path).contains("Package Center"));
    assert!(update_help::how_to_install_file(InstallKind::Dmg, path).contains("Applications"));
    assert!(update_help::how_to_install_file(InstallKind::Msi, path).contains("as this copy was installed"));
}

#[test]
fn should_format_desktop_errors_and_expose_io_source() {
    let unsupported = DesktopError::Unsupported("not on headless".to_owned());
    assert_eq!(unsupported.to_string(), "not on headless");
    assert!(unsupported.source().is_none());

    let io_err: DesktopError = io::Error::new(io::ErrorKind::PermissionDenied, "denied").into();
    assert!(io_err.to_string().contains("denied"));
    assert!(io_err.source().is_some());
}

#[test]
fn should_serve_web_app_assets_across_content_types_and_unpack_gzip() {
    let dir = TempDir::new().unwrap();
    fs::create_dir_all(dir.path().join("assets")).unwrap();
    fs::create_dir_all(dir.path().join("i18n")).unwrap();
    fs::create_dir_all(dir.path().join("flags")).unwrap();
    fs::write(dir.path().join("index.html"), "token=__NJMULE_CSRF__ role=__NJMULE_ROLE__").unwrap();
    fs::write(dir.path().join("assets/a.js"), b"console.log(1);").unwrap();
    fs::write(dir.path().join("assets/b.css"), b"body{}").unwrap();
    fs::write(dir.path().join("assets/c.svg"), b"<svg/>").unwrap();
    fs::write(dir.path().join("assets/d.woff2"), b"font2").unwrap();
    fs::write(dir.path().join("assets/e.ttf"), b"fontt").unwrap();
    fs::write(dir.path().join("flags/it.png"), b"png").unwrap();
    fs::write(dir.path().join("THIRD-PARTY.md"), b"notices").unwrap();
    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    gz.write_all(b"{\"hello\":\"ciao\"}").unwrap();
    fs::write(dir.path().join("i18n/it.json.gz"), gz.finish().unwrap()).unwrap();

    let app = WebApp::from_folder(dir.path().to_path_buf());
    assert_eq!(app.page("tok", false), "token=tok role=admin");
    assert_eq!(app.page("tok", true), "token=tok role=guest");
    assert_eq!(app.file("/assets/a.js").unwrap().content_type(), "text/javascript; charset=utf-8");
    assert!(app.file("/assets/a.js").unwrap().immutable());
    assert_eq!(app.file("/assets/a.js").unwrap().unpacked().unwrap(), b"console.log(1);");
    assert_eq!(app.file("/assets/b.css").unwrap().content_type(), "text/css; charset=utf-8");
    assert_eq!(app.file("/assets/c.svg").unwrap().content_type(), "image/svg+xml");
    assert_eq!(app.file("/assets/d.woff2").unwrap().content_type(), "font/woff2");
    assert_eq!(app.file("/assets/e.ttf").unwrap().content_type(), "font/ttf");
    assert_eq!(app.file("/flags/it.png").unwrap().content_type(), "image/png");
    assert_eq!(app.file("/THIRD-PARTY.md").unwrap().content_type(), "text/markdown; charset=utf-8");
    let tr = app.file("/i18n/it.json").unwrap();
    assert!(tr.gzipped());
    assert_eq!(tr.content_type(), "application/json; charset=utf-8");
    assert_eq!(tr.unpacked().unwrap(), b"{\"hello\":\"ciao\"}");
    assert!(app.file("/../secret").is_none());
    let _ = WebApp::shipped().page("x", false);
}

#[test]
fn should_validate_settings_file_passwords_and_compute_lan_url() {
    let dir = TempDir::new().unwrap();
    let file = SettingsFile::new(dir.path().to_path_buf(), |_| {});

    // Enabling without password fails.
    assert!(matches!(
        file.change(WebChange { enabled: Some(true), ..WebChange::default() }),
        Err(ChangeError::Invalid(_))
    ));
    // Too-short password fails.
    assert!(matches!(
        file.change(WebChange { password: Some("short".to_owned()), ..WebChange::default() }),
        Err(ChangeError::Invalid(_))
    ));
    // Valid admin password + LAN + custom host.
    let view = file
        .change(WebChange {
            enabled: Some(true),
            lan: Some(true),
            hosts: Some(vec!["mule.local".to_owned()]),
            password: Some("admin-password-123".to_owned()),
            ..WebChange::default()
        })
        .unwrap();
    assert!(view.enabled && view.lan && view.has_password);
    assert!(view.url.as_deref().is_some_and(|u| u.starts_with("http://")));
    // Guest password equal to admin password fails.
    assert!(matches!(
        file.change(WebChange { guest_password: Some("admin-password-123".to_owned()), ..WebChange::default() }),
        Err(ChangeError::Invalid(_))
    ));
    // Distinct guest password succeeds, then empty clears it.
    let with_guest = file
        .change(WebChange { guest_password: Some("guest-password-456".to_owned()), ..WebChange::default() })
        .unwrap();
    assert!(with_guest.has_guest);
    let cleared = file.change(WebChange { guest_password: Some(String::new()), ..WebChange::default() }).unwrap();
    assert!(!cleared.has_guest);
}
