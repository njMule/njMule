// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The web interface: the interface of njmule-ui behind a password, and the remote-control API it calls with the login
//! session, every change carrying the session's token in a header. The login page runs no script; no page can be
//! framed, and the session cookie is HTTP-only, same-site and, over HTTPS, secure. When its settings ask for it, the
//! API also answers callers with the API token.

use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use axum::Router;
use axum::body::Body;
use axum::extract::connect_info::{ConnectInfo, Connected};
use axum::extract::{Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware;
use axum::response::{IntoResponse, Response};
use axum::serve::IncomingStream;
use cookie::time::OffsetDateTime;
use cookie::{Cookie, SameSite};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::net::TcpListener;
use tokio::sync::{Semaphore, watch};
use tokio::task::JoinHandle;
use tower::ServiceExt;
use url::Url;

use crate::api::nj_mule_engine::NjMuleEngine;
use crate::remote::control_endpoint;
use crate::remote::remote_api::{self, ApiError, Caller, Client, Exit, Guard, TokenHolders};
use crate::remote::sessions::{self, Session, Sessions};
use crate::remote::settings_file::SettingsFile;
use crate::remote::trusted_proxies::TrustedProxies;
use crate::remote::web_app::{AppFile, WebApp};
use crate::remote::web_ui_settings::WebUiSettings;
use crate::remote::{api_json, api_token, html, passwords};

/// Passwords checked at once at most: each check keeps a core busy for a while, so that logins from many addresses
/// cannot take them all.
pub const PASSWORD_CHECKS: usize = 2;

const HTML_TYPE: &str = "text/html; charset=utf-8";
const TEXT_TYPE: &str = "text/plain; charset=utf-8";
const JSON_TYPE: &str = "application/json; charset=utf-8";
const API_PATH: &str = "/api/";
// The header that carries the session's token on the app's changes: no other site's page can send it.
const CSRF_HEADER: &str = "x-njmule-csrf";
const PAGES_POLICY: &str = "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; \
                            frame-ancestors 'none'; base-uri 'none'";
const APP_POLICY: &str = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; \
                          font-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; \
                          base-uri 'none'";
const MAX_LOGIN_BODY: usize = 16 * 1024;
const MAX_LOGIN_FIELDS: usize = 4;
// Far more than any of the forms has.
const MAX_FIELDS: usize = 32;
const MAX_BODY: usize = 1024 * 1024;
// How long the body of a request answered without it may still come, to keep its connection.
const DRAIN_WAIT: Duration = Duration::from_secs(10);
const LISTEN_BACKLOG: i32 = 128;

// Names under which a browser on this computer reaches the interface; other computers use its address or a name of the
// settings. Any other Host header may be a DNS-rebinding attempt.
const LOOPBACK_NAMES: [&str; 3] = ["localhost", "127.0.0.1", "[::1]"];

// Synology's QuickConnect names an address of a NAS a-b-c-d.<id>.direct.quickconnect.to, and its DNS answers with that
// address only: such a name of the address a request came to cannot be rebound elsewhere.
const QUICKCONNECT: &str = ".direct.quickconnect.to";

/// A running web interface.
pub struct WebUi {
    port: u16,
    checks: usize,
    checking: Arc<Semaphore>,
    stop: watch::Sender<bool>,
    serving: JoinHandle<io::Result<()>>,
}

// What the requests share. The settings are followed as they were at the start: changed ones start another interface.
struct Interface {
    settings: WebUiSettings,
    proxies: TrustedProxies,
    sessions: Arc<Sessions>,
    checking: Arc<Semaphore>,
    // Browsers keep cookies by host, whatever the port: two instances on one computer need two names.
    cookie: String,
    app: WebApp,
    // The API for the login sessions, and for the API token's holders when the settings let them in.
    api: Router,
    token_api: Option<Router>,
}

// The two ends of a connection: the address it reached, and where it came from.
#[derive(Debug, Clone, Copy)]
struct Peers {
    local: Option<IpAddr>,
    remote: IpAddr,
}

impl Connected<IncomingStream<'_, TcpListener>> for Peers {
    fn connect_info(stream: IncomingStream<'_, TcpListener>) -> Self {
        let local = stream.io().local_addr().ok().map(|address| address.ip().to_canonical());
        Self { local, remote: stream.remote_addr().ip().to_canonical() }
    }
}

impl WebUi {
    /// Starts the interface of `settings`, which must be on and have a password; `exit` stops njMule when the API asks.
    pub async fn start<E: NjMuleEngine>(engine: E, settings: WebUiSettings, exit: Option<Exit>) -> io::Result<Self> {
        Self::start_with(engine, settings, exit, PASSWORD_CHECKS, WebApp::shipped()).await
    }

    /// As [`WebUi::start`], checking `checks` passwords at once at most and serving `app`.
    pub async fn start_with<E: NjMuleEngine>(
        engine: E,
        settings: WebUiSettings,
        exit: Option<Exit>,
        checks: usize,
        app: WebApp,
    ) -> io::Result<Self> {
        if !settings.usable() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "the web interface is off or has no password"));
        }
        let config = engine.directories().config;
        if settings.api {
            // Made before the first call, so that a command line on this computer finds it.
            api_token::load_or_create(&config)?;
        }
        let proxies =
            TrustedProxies::new(&settings.proxies).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;
        // Bound first: the session cookie is named after the port.
        let listener = bind(settings.lan, settings.port)?;
        let port = listener.local_addr()?.port();
        let (stop, mut stopped) = watch::channel(false);
        let sessions = Arc::new(Sessions::default());
        let web = Arc::new(SettingsFile::new(config.clone(), |_| {}));
        let calls = remote_api::router(engine, exit, stop.subscribe(), Some(web));
        let api = Router::new().nest(remote_api::PREFIX, calls.clone()).fallback(no_such_call);
        // A wrong API token counts as a failed login of its address.
        let token_api = settings.api.then(|| {
            let counted = Arc::clone(&sessions);
            let guard: Guard = Arc::new(move |client| counted.may_try(&sessions::client_key(client)));
            Router::new()
                .nest(remote_api::PREFIX, calls)
                .fallback(no_such_call)
                .layer(middleware::from_fn_with_state(TokenHolders { config, guard }, remote_api::token_holders))
        });
        let checking = Arc::new(Semaphore::new(checks));
        tracing::info!(
            "Web interface on {}:{port}{}{}",
            if settings.lan { "*" } else { "127.0.0.1" },
            if settings.hosts.is_empty() { String::new() } else { format!(", also as {}", settings.hosts.join(", ")) },
            if settings.api { ", with the remote-control API" } else { "" }
        );
        let interface = Interface {
            settings,
            proxies,
            sessions,
            checking: Arc::clone(&checking),
            cookie: format!("njmule-session-{port}"),
            app,
            api,
            token_api,
        };
        let service = Router::new().fallback(serve).with_state(Arc::new(interface));
        let serving = tokio::spawn(async move {
            axum::serve(listener, service.into_make_service_with_connect_info::<Peers>())
                .with_graceful_shutdown(async move {
                    let _ = stopped.wait_for(|stop| *stop).await;
                })
                .await
        });
        Ok(Self { port, checks, checking, stop, serving })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// Passwords being checked now.
    pub fn checking_passwords(&self) -> usize {
        self.checks.saturating_sub(self.checking.available_permits())
    }

    /// Ends the event streams and stops answering; the port is free once this returns.
    pub async fn stop(self) {
        self.stop.send_replace(true);
        let _ = self.serving.await;
    }
}

// On this computer only, or on every address of it, IPv6 and IPv4 alike where the system allows.
fn bind(lan: bool, port: u16) -> io::Result<TcpListener> {
    let listener = if lan {
        // Windows lets an IPv6 socket take a port another program holds for IPv4, whose clients would then go there.
        if port != 0 {
            drop(std::net::TcpListener::bind((Ipv4Addr::UNSPECIFIED, port))?);
        }
        dual_stack(port).or_else(|_| std::net::TcpListener::bind((Ipv4Addr::UNSPECIFIED, port)))?
    } else {
        std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, port))?
    };
    listener.set_nonblocking(true)?;
    TcpListener::from_std(listener)
}

fn dual_stack(port: u16) -> io::Result<std::net::TcpListener> {
    let socket = Socket::new(Domain::IPV6, Type::STREAM, Some(Protocol::TCP))?;
    socket.set_only_v6(false)?;
    // As the standard library does: a port in TIME_WAIT can be bound again at once. Windows would let another program
    // take a port in use instead.
    #[cfg(not(windows))]
    socket.set_reuse_address(true)?;
    socket.bind(&SocketAddr::from((Ipv6Addr::UNSPECIFIED, port)).into())?;
    socket.listen(LISTEN_BACKLOG)?;
    Ok(socket.into())
}

async fn no_such_call() -> ApiError {
    ApiError::new(StatusCode::NOT_FOUND, "No such call")
}

// Every request: a token holder's call goes to the API, any other to the pages and the app's calls.
async fn serve(State(ui): State<Arc<Interface>>, ConnectInfo(peers): ConnectInfo<Peers>, request: Request) -> Response {
    let (mut parts, body) = request.into_parts();
    let forwarded: Vec<String> = parts
        .headers
        .get_all("x-forwarded-for")
        .iter()
        .filter_map(|value| value.to_str().ok().map(str::to_owned))
        .collect();
    let client = ui.proxies.client(peers.remote, &forwarded).to_canonical();
    parts.extensions.insert(Client(client));
    let Some(path) = decoded_path(parts.uri.path()) else {
        return send(StatusCode::BAD_REQUEST, TEXT_TYPE, "Bad request.");
    };
    let token_path = ui.token_api.is_some() && path.starts_with(API_PATH);
    // The desktop app's window on this njMule from another computer calls across origins with the token, which no
    // page sends by itself.
    let shell = token_path
        .then(|| header_text(&parts.headers, header::ORIGIN))
        .flatten()
        .filter(|origin| control_endpoint::SHELL_ORIGINS.contains(origin));
    let shell = shell.and_then(|origin| HeaderValue::from_str(origin).ok());
    let mut response = if shell.is_some() && parts.method == Method::OPTIONS {
        control_endpoint::preflight()
    } else if let Some(token_api) = ui.token_api.as_ref().filter(|_| token_path)
        && parts.headers.contains_key(header::AUTHORIZATION)
    {
        call(token_api, Request::from_parts(parts, body)).await
    } else {
        let expecting = expects_continue(&parts.headers);
        let mut body = Some(body);
        let response = route(&ui, parts, &path, peers.local, client, &mut body).await;
        // A body left unread is read after the answer, so that the connection can carry another request; one the
        // client waits to be asked for is never asked for, and the connection closes.
        if let (Some(body), false) = (body, expecting) {
            tokio::spawn(async move {
                let _ = tokio::time::timeout(DRAIN_WAIT, axum::body::to_bytes(body, MAX_BODY)).await;
            });
        }
        response
    };
    if let Some(origin) = shell {
        response.headers_mut().insert(header::ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.headers_mut().insert(header::VARY, HeaderValue::from_static("Origin"));
    }
    response
}

async fn call(api: &Router, request: Request) -> Response {
    match api.clone().oneshot(request).await {
        Ok(response) => response,
        Err(never) => match never {},
    }
}

// The path decoded; none when it is not UTF-8 or climbs with dot segments, which no page of the interface has.
fn decoded_path(raw: &str) -> Option<String> {
    let path = percent_encoding::percent_decode_str(raw).decode_utf8().ok()?;
    let climbs = path.split('/').any(|segment| segment == "." || segment == "..");
    (!climbs).then(|| path.into_owned())
}

fn expects_continue(headers: &HeaderMap) -> bool {
    header_text(headers, header::EXPECT).is_some_and(|expect| expect.eq_ignore_ascii_case("100-continue"))
}

fn header_text(headers: &HeaderMap, name: impl header::AsHeaderName) -> Option<&str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

async fn route(
    ui: &Interface,
    parts: axum::http::request::Parts,
    path: &str,
    reached: Option<IpAddr>,
    client: IpAddr,
    body: &mut Option<Body>,
) -> Response {
    // A HEAD gets the headers of the GET; the server leaves the body out.
    let method = if parts.method == Method::HEAD { Method::GET } else { parts.method.clone() };
    let host = header_text(&parts.headers, header::HOST).or_else(|| parts.uri.authority().map(|a| a.as_str()));
    let names = &ui.settings.hosts;
    let reached = reached.unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED));
    if !trusted_host(host, ui.settings.lan, names, reached) {
        return send(
            StatusCode::MISDIRECTED_REQUEST,
            TEXT_TYPE,
            "Unknown host name: open the interface by this computer's address, and add the name to the web \
             interface's host names.",
        );
    }
    let origin = header_text(&parts.headers, header::ORIGIN);
    if method != Method::GET && !trusted_origin(origin, host, names) {
        return send(StatusCode::FORBIDDEN, TEXT_TYPE, "Cross-site request refused.");
    }
    if method == Method::GET {
        if let Some(file) = ui.app.file(path) {
            return send_file(&file, &parts.headers);
        }
        if path == "/style.css" {
            return send(StatusCode::OK, "text/css; charset=utf-8", html::STYLE);
        }
        if let Some((content_type, bytes)) = html::icon(path) {
            return send(StatusCode::OK, content_type, bytes);
        }
    }
    let over_https = origin.is_some_and(|origin| origin.get(..8).is_some_and(|s| s.eq_ignore_ascii_case("https://")));
    if path == "/login" {
        return login(ui, &method, client, over_https, &parts.headers, body).await;
    }
    let session = session(ui, &parts.headers);
    if path.starts_with(API_PATH) {
        return call_api(ui, &method, session, parts, body).await;
    }
    let Some(session) = session else {
        return if method == Method::GET {
            redirect("/login")
        } else {
            send(StatusCode::FORBIDDEN, TEXT_TYPE, "Log in first.")
        };
    };
    match method {
        Method::POST => post(ui, path, &session, over_https, &parts.headers, body).await,
        Method::GET if path == "/" => {
            let page = ui.app.page(&session.csrf, session.guest);
            with_policy(send(StatusCode::OK, HTML_TYPE, page), APP_POLICY)
        }
        Method::GET => send(StatusCode::NOT_FOUND, TEXT_TYPE, "Not found."),
        _ => send(StatusCode::METHOD_NOT_ALLOWED, TEXT_TYPE, "Method not allowed."),
    }
}

// The app's calls: with the session, and with its token on changes. Token holders reach here only while the settings
// turn them away.
async fn call_api(
    ui: &Interface,
    method: &Method,
    session: Option<Session>,
    mut parts: axum::http::request::Parts,
    body: &mut Option<Body>,
) -> Response {
    let Some(session) = session else {
        return if parts.headers.contains_key(header::AUTHORIZATION) {
            send_error(StatusCode::NOT_FOUND, "The remote-control API is off.")
        } else {
            send_error(StatusCode::UNAUTHORIZED, "Log in first.")
        };
    };
    if *method != Method::GET && !sessions::valid_form(&session, header_text(&parts.headers, CSRF_HEADER)) {
        return send_error(StatusCode::FORBIDDEN, "Expired session token: reload the page.");
    }
    parts.extensions.insert(if session.guest { Caller::Guest } else { Caller::Admin });
    call(&ui.api, Request::from_parts(parts, body.take().unwrap_or_default())).await
}

async fn login(
    ui: &Interface,
    method: &Method,
    client: IpAddr,
    over_https: bool,
    headers: &HeaderMap,
    body: &mut Option<Body>,
) -> Response {
    if *method == Method::GET {
        return send(StatusCode::OK, HTML_TYPE, login_page(""));
    }
    if *method != Method::POST {
        return send(StatusCode::METHOD_NOT_ALLOWED, TEXT_TYPE, "Method not allowed.");
    }
    // Behind a trusted proxy each client has its own attempts.
    let address = sessions::client_key(client);
    if !ui.sessions.may_try(&address) {
        return send(StatusCode::TOO_MANY_REQUESTS, HTML_TYPE, login_page("Too many attempts: wait a minute."));
    }
    let Some(form) = read_form(headers, body, MAX_LOGIN_FIELDS, MAX_LOGIN_BODY).await else {
        return send(StatusCode::BAD_REQUEST, TEXT_TYPE, "Bad request.");
    };
    let Ok(permit) = Arc::clone(&ui.checking).try_acquire_owned() else {
        return send(StatusCode::TOO_MANY_REQUESTS, HTML_TYPE, login_page("Busy: try again in a moment."));
    };
    let password = form.get("password").cloned().unwrap_or_default();
    let admin = ui.settings.password_hash.clone();
    let guest = ui.settings.guest_password_hash.clone();
    let checked = tokio::task::spawn_blocking(move || {
        let _checking = permit;
        if passwords::matches(&password, &admin) {
            Some(false)
        } else {
            (!guest.is_empty() && passwords::matches(&password, &guest)).then_some(true)
        }
    })
    .await;
    let Ok(Some(guest)) = checked else {
        tracing::warn!("Wrong web interface password from {address}");
        return send(StatusCode::UNAUTHORIZED, HTML_TYPE, login_page("Wrong password."));
    };
    ui.sessions.succeeded(&address);
    if guest {
        tracing::info!("Web interface guest logged in from {address}");
    }
    let session = ui.sessions.open(guest);
    with_cookie(redirect("/"), session_cookie(&ui.cookie, session.id, over_https))
}

fn login_page(error: &str) -> String {
    let error = if error.is_empty() { String::new() } else { format!("<p class=\"error\">{}</p>", html::text(error)) };
    html::bare(
        "Log in",
        &format!(
            "{error}<form method=\"post\" action=\"login\"><label for=\"password\">Password</label>\
             <input type=\"password\" id=\"password\" name=\"password\" autofocus \
             autocomplete=\"current-password\"> <button class=\"main\">Log in</button></form>"
        ),
    )
}

// The one form left besides the login: logging out, with the session's form token.
async fn post(
    ui: &Interface,
    path: &str,
    session: &Session,
    over_https: bool,
    headers: &HeaderMap,
    body: &mut Option<Body>,
) -> Response {
    let form = read_form(headers, body, MAX_FIELDS, MAX_BODY).await;
    if !form.is_some_and(|form| sessions::valid_form(session, form.get("csrf").map(String::as_str))) {
        return send(StatusCode::FORBIDDEN, TEXT_TYPE, "Expired form: reload the page.");
    }
    if path != "/logout" {
        return send(StatusCode::NOT_FOUND, TEXT_TYPE, "Not found.");
    }
    ui.sessions.close(&session.id);
    let mut expired = session_cookie(&ui.cookie, String::new(), over_https);
    expired.set_max_age(cookie::time::Duration::ZERO);
    expired.set_expires(OffsetDateTime::UNIX_EPOCH);
    with_cookie(redirect("/login"), expired)
}

// The fields of a posted form, the last value of each; none past `max_fields` fields or `max_bytes` bytes. A body of
// another type has no fields.
async fn read_form(
    headers: &HeaderMap,
    body: &mut Option<Body>,
    max_fields: usize,
    max_bytes: usize,
) -> Option<HashMap<String, String>> {
    let bytes = axum::body::to_bytes(body.take().unwrap_or_default(), max_bytes).await.ok()?;
    let form = header_text(headers, header::CONTENT_TYPE)
        .and_then(|value| value.split(';').next())
        .is_some_and(|media| media.trim().eq_ignore_ascii_case("application/x-www-form-urlencoded"));
    if !form {
        return Some(HashMap::new());
    }
    let fields: Vec<(String, String)> = form_urlencoded::parse(&bytes).into_owned().collect();
    (fields.len() <= max_fields).then(|| fields.into_iter().collect())
}

fn session(ui: &Interface, headers: &HeaderMap) -> Option<Session> {
    headers
        .get_all(header::COOKIE)
        .iter()
        .filter_map(|value| value.to_str().ok())
        .flat_map(Cookie::split_parse)
        .filter_map(Result::ok)
        .find(|cookie| cookie.name() == ui.cookie)
        .and_then(|cookie| ui.sessions.find(cookie.value()))
}

// The browser that sent the form came over HTTPS, as through a reverse proxy that ends TLS, when its Origin says so:
// the cookie then travels only that way. No forwarded header is needed, nor trusted.
fn session_cookie(name: &str, value: String, over_https: bool) -> Cookie<'static> {
    Cookie::build((name.to_owned(), value))
        .path("/")
        .http_only(true)
        .same_site(SameSite::Strict)
        .secure(over_https)
        .build()
}

fn with_cookie(mut response: Response, cookie: Cookie<'static>) -> Response {
    if let Ok(value) = HeaderValue::from_str(&cookie.to_string()) {
        response.headers_mut().append(header::SET_COOKIE, value);
    }
    response
}

fn redirect(location: &str) -> Response {
    let mut response = StatusCode::SEE_OTHER.into_response();
    if let Ok(location) = HeaderValue::from_str(&html::relative(location)) {
        response.headers_mut().insert(header::LOCATION, location);
    }
    with_policy(response, PAGES_POLICY)
}

fn send(status: StatusCode, content_type: &'static str, body: impl Into<Body>) -> Response {
    let mut response = (status, body.into()).into_response();
    response.headers_mut().insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    with_policy(response, PAGES_POLICY)
}

// An error of the app's calls, in the API's form.
fn send_error(status: StatusCode, message: &str) -> Response {
    send(status, JSON_TYPE, api_json::error(message).to_string())
}

// A file of the app; the bundled ones never change under their names.
fn send_file(file: &AppFile, headers: &HeaderMap) -> Response {
    let mut extra = Vec::new();
    let bytes = if file.gzipped() {
        extra.push((header::VARY, HeaderValue::from_static("Accept-Encoding")));
        if takes_gzip(header_text(headers, header::ACCEPT_ENCODING)) {
            extra.push((header::CONTENT_ENCODING, HeaderValue::from_static("gzip")));
            file.bytes().to_vec()
        } else {
            match file.unpacked() {
                Ok(bytes) => bytes,
                Err(e) => {
                    tracing::warn!("Cannot unpack a file of the web interface: {e}");
                    return send(StatusCode::INTERNAL_SERVER_ERROR, TEXT_TYPE, "The request failed.");
                }
            }
        }
    } else {
        file.bytes().to_vec()
    };
    let mut response = send(StatusCode::OK, file.content_type(), bytes);
    let cache = if file.immutable() { "public, max-age=31536000, immutable" } else { "no-cache" };
    response.headers_mut().insert(header::CACHE_CONTROL, HeaderValue::from_static(cache));
    for (name, value) in extra {
        response.headers_mut().insert(name, value);
    }
    response
}

fn with_policy(mut response: Response, policy: &'static str) -> Response {
    let headers = response.headers_mut();
    headers.insert(header::CONTENT_SECURITY_POLICY, HeaderValue::from_static(policy));
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    // Not no-referrer: browsers would then send "Origin: null" with the pages' own forms.
    headers.insert(header::REFERRER_POLICY, HeaderValue::from_static("same-origin"));
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    response
}

/// Whether a request with this Host header may be answered: a name of this computer, a name of the settings, or, while
/// other computers may connect, an address or a QuickConnect name of the address `reached`.
pub fn trusted_host(host: Option<&str>, lan: bool, names: &[String], reached: IpAddr) -> bool {
    let Some(host) = host else {
        return false;
    };
    let name = without_port(host);
    LOOPBACK_NAMES.contains(&name.as_str())
        || names.contains(&name)
        || (lan && (is_address(&name) || is_quickconnect_name_of(&name, reached)))
}

/// Whether a change may come from a page of this `origin`: browsers send it with every POST, and a different one is
/// another site's form. A name of the settings stands for the pages on any port and web scheme: a reverse proxy shows
/// them on its own, and may pass its own address as Host.
pub fn trusted_origin(origin: Option<&str>, host: Option<&str>, names: &[String]) -> bool {
    let Some(origin) = origin else {
        return true;
    };
    // An origin is a scheme and an authority, with nothing after them.
    let (Ok(_), Some((scheme, authority))) = (Url::parse(origin), origin.split_once("://")) else {
        return false;
    };
    if authority.is_empty() || authority.contains(['/', '?', '#']) {
        return false;
    }
    host.is_some_and(|host| authority.eq_ignore_ascii_case(host))
        || ((scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https"))
            && !authority.contains('@')
            && names.contains(&without_port(authority)))
}

/// Whether a client with this Accept-Encoding takes gzip (RFC 9110): a coding of quality 0 is refused.
pub fn takes_gzip(accept_encoding: Option<&str>) -> bool {
    accept_encoding.is_some_and(|header| {
        header.split(',').any(|item| {
            let mut parts = item.split(';').map(str::trim);
            let coding = parts.next().unwrap_or_default();
            let refused = parts.filter_map(|parameter| parameter.split_once('=')).any(|(name, value)| {
                name.trim().eq_ignore_ascii_case("q") && value.trim().parse::<f32>().is_ok_and(|q| q <= 0.0)
            });
            !refused && (coding.eq_ignore_ascii_case("gzip") || coding == "*")
        })
    })
}

// The name of host[:port], in lower case.
fn without_port(authority: &str) -> String {
    let name = match (authority.rfind(':'), authority.rfind(']')) {
        (Some(colon), Some(bracket)) if colon > bracket => authority.get(..colon),
        (Some(colon), None) => authority.get(..colon),
        _ => Some(authority),
    };
    name.unwrap_or(authority).to_ascii_lowercase()
}

// An IP address literal, which no DNS answer can point elsewhere.
fn is_address(name: &str) -> bool {
    match name.strip_prefix('[').and_then(|inner| inner.strip_suffix(']')) {
        Some(inner) => inner.parse::<Ipv6Addr>().is_ok(),
        None => name.parse::<Ipv4Addr>().is_ok(),
    }
}

fn is_quickconnect_name_of(name: &str, reached: IpAddr) -> bool {
    let IpAddr::V4(reached) = reached.to_canonical() else {
        return false;
    };
    let Some((dashed, id)) = name.strip_suffix(QUICKCONNECT).and_then(|rest| rest.split_once('.')) else {
        return false;
    };
    let named = !id.is_empty() && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-');
    let parts: Vec<&str> = dashed.split('-').collect();
    let digits = parts.len() == 4 && parts.iter().all(|part| (1..=3).contains(&part.len()));
    named && digits && parts.join(".").parse::<Ipv4Addr>().is_ok_and(|address| address == reached)
}
