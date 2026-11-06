// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Fetches a list (server.met, nodes.dat, an IP filter) from the web at the user's request: http or https only, at most
//! three redirects, each checked again and never from https to http, never to a loopback, private, link-local or
//! multicast address, at most 50 MiB and 90 seconds in all; a gzip or zip body is unpacked within the same limit. The
//! proxy of the preferences is used. The host is looked up here to check its addresses, also when a proxy then connects
//! to it. A [`WebLists::download`] of a file streams to disk instead, within the length it is expected to have.

use std::io::{self, Cursor, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::path::Path;
use std::time::Duration;

use reqwest::header::{CONTENT_LENGTH, CONTENT_RANGE, LOCATION, RANGE, TRANSFER_ENCODING};
use reqwest::{Response, StatusCode, Url, Version};
use tokio::io::AsyncWriteExt;
use tokio::time::Instant;

use crate::net::http_route;
use crate::net::routes::Routes;

pub const MAX_SIZE: u64 = 50 * 1024 * 1024;

const MAX_REDIRECTS: u32 = 3;
const DEFAULT_TIMEOUT: Duration = Duration::from_secs(90);
// A download may take long on a slow line; it fails when nothing comes for a minute.
const DOWNLOAD_IDLE: Duration = Duration::from_secs(60);
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(4 * 3600);
// What an archive holds the list as, by kind; any other file only when none of these is there.
const LIST_NAMES: [&str; 5] = ["ipfilter.dat", "guarding.p2p", "guardian.p2p", "server.met", "nodes.dat"];

pub struct WebLists {
    routes: Routes,
    reachable: Box<dyn Fn(IpAddr) -> bool + Send + Sync>,
    timeout: Duration,
}

impl WebLists {
    /// A fetcher that reaches the addresses `reachable` lets through, a fetch lasting `timeout` at most.
    pub fn new(routes: Routes, reachable: impl Fn(IpAddr) -> bool + Send + Sync + 'static, timeout: Duration) -> Self {
        Self { routes, reachable: Box::new(reachable), timeout }
    }

    /// A fetcher that reaches public addresses only.
    pub fn create(routes: Routes) -> Self {
        Self::new(routes, is_public, DEFAULT_TIMEOUT)
    }

    /// A fetcher that reaches any address, for one the user set by hand as where to look for new versions: what comes
    /// from there is taken only when signed.
    pub fn any_address(routes: Routes) -> Self {
        Self::new(routes, |_| true, DEFAULT_TIMEOUT)
    }

    /// The body at `uri`, unpacked when it is gzip or zip; an error when the address is refused, the server fails, the
    /// body is too large or late.
    pub async fn fetch(&self, uri: &str) -> io::Result<Vec<u8>> {
        self.fetch_body(uri, false).await
    }

    /// As [`WebLists::fetch`], for a list of lines (an IP filter): a plain body that ends with the connection, not at a
    /// length given first, must end with a line break, as a list cut short would lose its last lines unseen.
    pub async fn fetch_text(&self, uri: &str) -> io::Result<Vec<u8>> {
        self.fetch_body(uri, true).await
    }

    async fn fetch_body(&self, uri: &str, lines: bool) -> io::Result<Vec<u8>> {
        let deadline = Instant::now().checked_add(self.timeout).unwrap_or_else(Instant::now);
        let first = address(uri)?;
        let mut current = first.clone();
        let http = http_route::client(&self.routes)?;
        for hop in 0.. {
            self.check(&current).await?;
            // The whole answer, within the size and the time left: the next hop starts after this one is done.
            let response = within(deadline, http.get(current.clone()).send()).await?.map_err(|e| failure(&e))?;
            let status = response.status();
            if is_redirect(status) {
                if hop >= MAX_REDIRECTS {
                    return Err(io::Error::other(format!("too many redirects from {}", host(&first))));
                }
                current = redirect(&current, location(&response)?)?;
                continue;
            }
            if status != StatusCode::OK {
                return Err(io::Error::other(format!("HTTP {} from {}", status.as_u16(), host(&current))));
            }
            let ends_with_connection = ends_with_connection(&response);
            let data = body_within(response, deadline).await?;
            if lines && !is_packed(&data) && ends_with_connection && !ends_with_line_break(&data) {
                return Err(io::Error::other(format!("the list from {} was cut short", host(&current))));
            }
            return unpack(data);
        }
        Err(io::Error::other("too many redirects"))
    }

    /// Downloads the file at `uri` into `part`, going on from the bytes `part` already holds when the server allows it,
    /// until it holds `size` bytes; reports the bytes held to `progress`. A file that turns out longer is refused; one
    /// cut short leaves `part` for the next try.
    pub async fn download(&self, uri: &str, part: &Path, size: u64, mut progress: impl FnMut(u64)) -> io::Result<()> {
        let first = address(uri)?;
        let mut current = first.clone();
        let http = http_route::client(&self.routes)?;
        let mut hops = 0_u32;
        let mut restarted = false;
        loop {
            self.check(&current).await?;
            let held = held(part, size)?;
            progress(held);
            if held == size {
                return Ok(());
            }
            let mut request = http.get(current.clone()).timeout(DOWNLOAD_TIMEOUT);
            if held > 0 {
                request = request.header(RANGE, format!("bytes={held}-"));
            }
            let response =
                tokio::time::timeout(http_route::CONNECT_TIMEOUT.saturating_add(DOWNLOAD_IDLE), request.send())
                    .await
                    .map_err(|_| {
                        io::Error::new(io::ErrorKind::TimedOut, format!("no answer from {} in time", host(&current)))
                    })?
                    .map_err(|e| failure(&e))?;
            let status = response.status();
            if is_redirect(status) {
                hops = hops.saturating_add(1);
                if hops > MAX_REDIRECTS {
                    return Err(io::Error::other(format!("too many redirects from {}", host(&first))));
                }
                current = redirect(&current, location(&response)?)?;
                continue;
            }
            // The bytes held no longer fit the file: once, from the start.
            if status == StatusCode::RANGE_NOT_SATISFIABLE && held > 0 && !restarted {
                tokio::fs::remove_file(part).await?;
                restarted = true;
                continue;
            }
            let resumed = status == StatusCode::PARTIAL_CONTENT && held > 0 && starts_at(&response, held);
            if status != StatusCode::OK && !resumed {
                return Err(io::Error::other(format!("HTTP {} from {}", status.as_u16(), host(&current))));
            }
            return receive(response, part, if resumed { held } else { 0 }, size, &mut progress, &current).await;
        }
    }

    // The host is looked up here and again by the client or the proxy: a name that changes its address in between is
    // not stopped.
    async fn check(&self, uri: &Url) -> io::Result<()> {
        let scheme = uri.scheme().to_ascii_lowercase();
        if scheme != "http" && scheme != "https" {
            return Err(io::Error::other(format!("only http and https addresses are fetched: {uri}")));
        }
        let addresses: Vec<IpAddr> = match uri.host() {
            None => return Err(io::Error::other(format!("no host in {uri}"))),
            Some(url::Host::Ipv4(ip)) => vec![IpAddr::V4(ip)],
            Some(url::Host::Ipv6(ip)) => vec![IpAddr::V6(ip)],
            Some(url::Host::Domain(name)) => {
                let port = uri.port_or_known_default().unwrap_or(80);
                tokio::net::lookup_host((name, port)).await?.map(|address| address.ip()).collect()
            }
        };
        if let Some(refused) = addresses.into_iter().find(|address| !(self.reachable)(*address)) {
            return Err(io::Error::other(format!("refusing to fetch from the local or private address {refused}")));
        }
        Ok(())
    }
}

// An address as the user gave it, with a scheme and a host as RFC 3986 reads them: a lenient web parser would take
// `http:/server.met` for a host.
fn address(text: &str) -> io::Result<Url> {
    let scheme = text.split_once(':').map(|(scheme, _)| scheme.to_ascii_lowercase()).unwrap_or_default();
    if scheme != "http" && scheme != "https" {
        return Err(io::Error::other(format!("only http and https addresses are fetched: {text}")));
    }
    let authority = text
        .get(scheme.len().saturating_add(1)..)
        .and_then(|rest| rest.strip_prefix("//"))
        .and_then(|rest| rest.split(['/', '?', '#']).next())
        .map(|authority| authority.rsplit('@').next().unwrap_or_default());
    if authority.is_none_or(str::is_empty) {
        return Err(io::Error::other(format!("no host in {text}")));
    }
    Url::parse(text).map_err(|e| io::Error::other(format!("not an address: {text}: {e}")))
}

/// A redirect is checked like the first address, and one from https stays on https.
pub fn redirect(from: &Url, location: &str) -> io::Result<Url> {
    let to = from.join(location).map_err(|_| io::Error::other(format!("a redirect to a bad address: {location}")))?;
    if from.scheme().eq_ignore_ascii_case("https") && !to.scheme().eq_ignore_ascii_case("https") {
        return Err(io::Error::other(format!("refusing a redirect from https to {to}")));
    }
    Ok(to)
}

/// Whether `address` is on the Internet: not loopback, any, link-local, private, multicast, unique local, 0.0.0.0/8 or
/// the carrier-grade NAT range 100.64.0.0/10.
pub fn is_public(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(v4) => is_public_v4(v4),
        IpAddr::V6(v6) => is_public_v6(v6),
    }
}

fn is_public_v4(v4: Ipv4Addr) -> bool {
    let [a, b, ..] = v4.octets();
    !(v4.is_loopback()
        || v4.is_unspecified()
        || v4.is_link_local()
        || v4.is_private()
        || v4.is_multicast()
        || a == 0
        || (a == 100 && b & 0xC0 == 64))
}

fn is_public_v6(v6: Ipv6Addr) -> bool {
    let [first, ..] = v6.segments();
    let [high, ..] = v6.octets();
    // Link-local fe80::/10, site-local fec0::/10 and unique local fc00::/7.
    !(v6.is_loopback()
        || v6.is_unspecified()
        || v6.is_multicast()
        || first & 0xFFC0 == 0xFE80
        || first & 0xFFC0 == 0xFEC0
        || high & 0xFE == 0xFC)
}

fn is_redirect(status: StatusCode) -> bool {
    matches!(status.as_u16(), 301 | 302 | 303 | 307 | 308)
}

fn host(uri: &Url) -> String {
    uri.host_str().unwrap_or_default().to_owned()
}

fn location(response: &Response) -> io::Result<&str> {
    response
        .headers()
        .get(LOCATION)
        .and_then(|location| location.to_str().ok())
        .ok_or_else(|| io::Error::other("a redirect without a location"))
}

async fn within<T>(deadline: Instant, work: impl Future<Output = T>) -> io::Result<T> {
    let late = || io::Error::new(io::ErrorKind::TimedOut, "the list did not come in time");
    if Instant::now() >= deadline {
        return Err(late());
    }
    tokio::time::timeout_at(deadline, work).await.map_err(|_| late())
}

fn failure(e: &reqwest::Error) -> io::Error {
    if e.is_timeout() {
        io::Error::new(io::ErrorKind::TimedOut, "the list did not come in time")
    } else {
        io::Error::other(format!("cannot fetch: {e}"))
    }
}

// The body, within the time left and the size allowed.
async fn body_within(mut response: Response, deadline: Instant) -> io::Result<Vec<u8>> {
    let mut body = Vec::new();
    while let Some(chunk) = within(deadline, response.chunk()).await?.map_err(|e| failure(&e))? {
        if u64::try_from(body.len().saturating_add(chunk.len())).unwrap_or(u64::MAX) > MAX_SIZE {
            return Err(too_large());
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

// An HTTP/1 body with neither a length nor chunks ends when the server closes: a cut looks the same.
fn ends_with_connection(response: &Response) -> bool {
    matches!(response.version(), Version::HTTP_10 | Version::HTTP_11)
        && !response.headers().contains_key(CONTENT_LENGTH)
        && !response.headers().contains_key(TRANSFER_ENCODING)
}

// A partial answer counts only when it goes on from the bytes held: "bytes <held>-<last>/<length>".
fn starts_at(response: &Response, held: u64) -> bool {
    response
        .headers()
        .get(CONTENT_RANGE)
        .and_then(|range| range.to_str().ok())
        .is_some_and(|range| range.trim().starts_with(&format!("bytes {held}-")))
}

// What part holds already, when it is no longer than the file; a longer one is dropped.
fn held(part: &Path, size: u64) -> io::Result<u64> {
    if !part.is_file() {
        return Ok(0);
    }
    let held = std::fs::metadata(part)?.len();
    if held > size {
        std::fs::remove_file(part)?;
        return Ok(0);
    }
    Ok(held)
}

async fn receive(
    mut response: Response,
    part: &Path,
    from: u64,
    size: u64,
    progress: &mut impl FnMut(u64),
    uri: &Url,
) -> io::Result<()> {
    let longer = || io::Error::other(format!("the file at {} is longer than {size} bytes", host(uri)));
    if response.content_length().is_some_and(|length| length > size.saturating_sub(from)) {
        return Err(longer());
    }
    let mut out = if from > 0 {
        tokio::fs::OpenOptions::new().append(true).open(part).await?
    } else {
        tokio::fs::File::create(part).await?
    };
    let mut held = from;
    loop {
        let chunk = tokio::time::timeout(DOWNLOAD_IDLE, response.chunk())
            .await
            .map_err(|_| {
                io::Error::new(io::ErrorKind::TimedOut, format!("nothing came from {} for a minute", host(uri)))
            })
            .and_then(|chunk| chunk.map_err(|e| failure(&e)));
        let chunk = match chunk {
            Ok(Some(chunk)) => chunk,
            Ok(None) => break,
            Err(e) => {
                out.flush().await?;
                return Err(e);
            }
        };
        let length = u64::try_from(chunk.len()).unwrap_or(u64::MAX);
        if length > size.saturating_sub(held) {
            return Err(longer());
        }
        out.write_all(&chunk).await?;
        held = held.saturating_add(length);
        progress(held);
    }
    out.flush().await?;
    if held != size {
        return Err(io::Error::other(format!("the download from {} stopped at {held} of {size} bytes", host(uri))));
    }
    Ok(())
}

// Gzip and zip check their own length and checksum.
fn is_packed(body: &[u8]) -> bool {
    is_gzip(body) || is_zip(body)
}

fn is_gzip(body: &[u8]) -> bool {
    body.starts_with(&[0x1F, 0x8B])
}

fn is_zip(body: &[u8]) -> bool {
    body.starts_with(b"PK\x03\x04")
}

fn ends_with_line_break(data: &[u8]) -> bool {
    data.last().is_none_or(|&last| last == b'\n' || last == b'\r')
}

fn unpack(body: Vec<u8>) -> io::Result<Vec<u8>> {
    if is_gzip(&body) {
        return read_at_most(flate2::read::MultiGzDecoder::new(body.as_slice()), MAX_SIZE);
    }
    if !is_zip(&body) {
        return Ok(body);
    }
    let mut archive = zip::ZipArchive::new(Cursor::new(body)).map_err(io::Error::other)?;
    let mut first = None;
    let mut left = MAX_SIZE;
    for index in 0..archive.len() {
        let entry = archive.by_index(index).map_err(io::Error::other)?;
        if entry.is_dir() {
            continue;
        }
        let name = entry.name().map_err(io::Error::other)?.rsplit('/').next().unwrap_or_default().to_lowercase();
        let data = read_at_most(entry, left)?;
        left = left.saturating_sub(u64::try_from(data.len()).unwrap_or(u64::MAX));
        if LIST_NAMES.contains(&name.as_str()) {
            return Ok(data);
        }
        first.get_or_insert(data);
    }
    first.ok_or_else(|| io::Error::other("an empty zip archive"))
}

// One byte past the limit tells a larger list from one that fills it exactly.
fn read_at_most(input: impl Read, limit: u64) -> io::Result<Vec<u8>> {
    let mut data = Vec::new();
    input.take(limit.saturating_add(1)).read_to_end(&mut data)?;
    if u64::try_from(data.len()).unwrap_or(u64::MAX) > limit {
        return Err(too_large());
    }
    Ok(data)
}

fn too_large() -> io::Error {
    io::Error::other(format!("the list is larger than {} MiB", MAX_SIZE / (1024 * 1024)))
}
