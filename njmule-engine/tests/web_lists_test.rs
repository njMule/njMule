// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebListsTest, under its names, with a server on the loopback that answers each path as the test
//! sets it.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Shutdown, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use flate2::Compression;
use flate2::write::GzEncoder;
use njmule_engine::config::bind_interface::Binding;
use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::net::routes::Routes;
use njmule_engine::net::web_lists::{self, WebLists};
use reqwest::Url;

const SERVER_LIST: &[u8] = b"server list";
const IP_FILTER: &[u8] = b"1.2.3.4 - 1.2.3.5 , 100 , bad";
const TWO_LINES: &[u8] = b"1.2.3.4 - 1.2.3.5 , 100 , one\n2.2.3.4 - 2.2.3.5 , 100 , two\n";
const NO_BREAK: &[u8] = b"1.2.3.4 - 1.2.3.5 , 100 , one";
const CARRIAGE_RETURN: &[u8] = b"1.2.3.4 - 1.2.3.5 , 100 , one\r";
const ANY_TIME: Duration = Duration::from_secs(90);

fn cut() -> &'static [u8] {
    &TWO_LINES[..40]
}

#[derive(Clone, Default)]
struct Server {
    port: u16,
    // Path -> the whole response, status line to body.
    responses: Arc<Mutex<HashMap<String, Vec<u8>>>>,
    // Paths whose response stops there, the connection left open.
    stalls: Arc<Mutex<HashSet<String>>>,
}

impl Server {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let server = Self { port: listener.local_addr().unwrap().port(), ..Self::default() };
        let serving = server.clone();
        thread::spawn(move || {
            for client in listener.incoming() {
                let Ok(client) = client else { return };
                let serving = serving.clone();
                thread::spawn(move || serving.answer(client));
            }
        });
        server
    }

    fn answer(&self, mut client: TcpStream) {
        let mut reader = BufReader::new(client.try_clone().unwrap());
        let mut request = String::new();
        if reader.read_line(&mut request).unwrap_or(0) == 0 {
            return;
        }
        let path = request.split(' ').nth(1).unwrap_or_default().to_owned();
        let mut line = String::new();
        while reader.read_line(&mut line).unwrap_or(0) > 2 {
            line.clear();
        }
        let response = self.responses.lock().unwrap().get(&path).cloned().unwrap_or_else(|| response(404, "", b""));
        let _ = client.write_all(&response);
        if self.stalls.lock().unwrap().contains(&path) {
            thread::sleep(Duration::from_secs(20));
        }
        let _ = client.shutdown(Shutdown::Both);
    }

    fn respond(&self, path: &str, response: Vec<u8>) {
        self.responses.lock().unwrap().insert(path.to_owned(), response);
    }

    fn uri(&self, path: &str) -> String {
        format!("http://127.0.0.1:{}{path}", self.port)
    }
}

fn response(status: u16, headers: &str, body: &[u8]) -> Vec<u8> {
    let mut all =
        format!("HTTP/1.1 {status} X\r\n{headers}Content-Length: {}\r\nConnection: close\r\n\r\n", body.len())
            .into_bytes();
    all.extend_from_slice(body);
    all
}

// A response whose body ends when the connection closes.
fn until_closed(body: &[u8]) -> Vec<u8> {
    let mut all = b"HTTP/1.1 200 X\r\nConnection: close\r\n\r\n".to_vec();
    all.extend_from_slice(body);
    all
}

fn gzip(data: &[u8]) -> Vec<u8> {
    let mut gz = GzEncoder::new(Vec::new(), Compression::default());
    gz.write_all(data).unwrap();
    gz.finish().unwrap()
}

fn gzipped_zeros(size: u64) -> Vec<u8> {
    let mut gz = GzEncoder::new(Vec::new(), Compression::fast());
    let zeros = vec![0; 1024 * 1024];
    let mut left = size;
    while left > 0 {
        let now = left.min(zeros.len() as u64);
        gz.write_all(&zeros[..now as usize]).unwrap();
        left -= now;
    }
    gz.finish().unwrap()
}

fn zip(files: &[(&str, &[u8])]) -> Vec<u8> {
    let mut archive = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
    for (name, data) in files {
        if name.ends_with('/') {
            archive.add_directory(*name, zip::write::SimpleFileOptions::default()).unwrap();
        } else {
            archive.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            archive.write_all(data).unwrap();
        }
    }
    archive.finish().unwrap().into_inner()
}

fn any_address() -> WebLists {
    WebLists::new(Routes::direct(), |_| true, ANY_TIME)
}

fn ip(text: &str) -> IpAddr {
    text.parse().unwrap()
}

async fn fetched(lists: &WebLists, uri: &str) -> std::io::Result<Vec<u8>> {
    lists.fetch(uri).await
}

fn failed_with(result: std::io::Result<Vec<u8>>, words: &str) {
    let failure = result.err().unwrap();
    assert!(failure.to_string().contains(words), "{failure}");
}

#[tokio::test]
async fn should_fetch_a_list() {
    let server = Server::start();
    server.respond("/server.met", response(200, "", SERVER_LIST));

    let body = fetched(&any_address(), &server.uri("/server.met")).await.unwrap();

    assert_eq!(body, SERVER_LIST);
}

#[tokio::test]
async fn should_follow_a_redirect() {
    let server = Server::start();
    server.respond("/server.met", response(200, "", SERVER_LIST));
    server.respond("/moved", response(302, "Location: /server.met\r\n", b""));

    let body = fetched(&any_address(), &server.uri("/moved")).await.unwrap();

    assert_eq!(body, SERVER_LIST);
}

#[tokio::test]
async fn should_unpack_a_gzipped_list() {
    let server = Server::start();
    server.respond("/list.gz", response(200, "", &gzip(SERVER_LIST)));

    let body = fetched(&any_address(), &server.uri("/list.gz")).await.unwrap();

    assert_eq!(body, SERVER_LIST);
}

#[tokio::test]
async fn should_unpack_a_zipped_list() {
    let server = Server::start();
    server.respond("/list.zip", response(200, "", &zip(&[("nodes.dat", SERVER_LIST)])));

    let body = fetched(&any_address(), &server.uri("/list.zip")).await.unwrap();

    assert_eq!(body, SERVER_LIST);
}

#[tokio::test]
async fn should_refuse_local_addresses() {
    let server = Server::start();

    let fetched = WebLists::create(Routes::direct()).fetch(&server.uri("/server.met")).await;

    failed_with(fetched, "local or private");
}

#[tokio::test]
async fn should_refuse_other_schemes() {
    failed_with(any_address().fetch("ftp://example.org/server.met").await, "http and https");
}

#[tokio::test]
async fn should_fail_on_an_http_error() {
    let server = Server::start();

    failed_with(any_address().fetch(&server.uri("/missing")).await, "HTTP 404");
}

#[tokio::test]
async fn should_refuse_a_redirect_loop() {
    let server = Server::start();
    server.respond("/loop", response(302, "Location: /loop\r\n", b""));

    failed_with(any_address().fetch(&server.uri("/loop")).await, "too many redirects");
}

#[tokio::test]
async fn should_check_a_redirect_like_the_first_address() {
    let server = Server::start();
    server.respond("/away", response(301, "Location: ftp://example.org/server.met\r\n", b""));

    failed_with(any_address().fetch(&server.uri("/away")).await, "http and https");
}

#[tokio::test]
async fn should_fail_within_the_deadline_when_the_body_stops_coming() {
    let server = Server::start();
    server.respond("/stalled", b"HTTP/1.1 200 X\r\nContent-Length: 1000\r\n\r\n0123456789".to_vec());
    server.stalls.lock().unwrap().insert("/stalled".to_owned());
    let lists = WebLists::new(Routes::direct(), |_| true, Duration::from_secs(2));

    let fetched = tokio::time::timeout(Duration::from_secs(15), lists.fetch(&server.uri("/stalled"))).await.unwrap();

    assert!(fetched.is_err());
}

#[tokio::test]
async fn should_take_the_list_an_archive_holds_rather_than_its_first_file() {
    let server = Server::start();
    server.respond(
        "/lists.zip",
        response(200, "", &zip(&[("README.txt", b"read me"), ("lists/ipfilter.dat", IP_FILTER)])),
    );

    let body = fetched(&any_address(), &server.uri("/lists.zip")).await.unwrap();

    assert_eq!(body, IP_FILTER);
}

#[tokio::test]
async fn should_take_the_first_file_of_an_archive_without_a_list() {
    let server = Server::start();
    server.respond("/other.zip", response(200, "", &zip(&[("only.txt", IP_FILTER)])));

    let body = fetched(&any_address(), &server.uri("/other.zip")).await.unwrap();

    assert_eq!(body, IP_FILTER, "else the first");
}

#[test]
fn should_resolve_a_relative_redirect() {
    let to = web_lists::redirect(&Url::parse("https://lists.example/ipfilter.zip").unwrap(), "/other.zip").unwrap();

    assert_eq!(to.as_str(), "https://lists.example/other.zip");
}

#[test]
fn should_let_a_redirect_go_from_http_to_https() {
    let to = web_lists::redirect(&Url::parse("http://lists.example/a").unwrap(), "https://mirror.example/a").unwrap();

    assert_eq!(to.as_str(), "https://mirror.example/a");
}

#[test]
fn should_not_let_a_redirect_leave_https_for_http() {
    let refused = web_lists::redirect(
        &Url::parse("https://lists.example/ipfilter.zip").unwrap(),
        "http://mirror.example/ipfilter.zip",
    );

    assert!(refused.err().unwrap().to_string().contains("https"));
}

#[tokio::test]
async fn should_refuse_a_body_shorter_than_its_length() {
    let server = Server::start();
    let mut short = b"HTTP/1.1 200 X\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n".to_vec();
    short.extend_from_slice(cut());
    server.respond("/short", short);

    assert!(any_address().fetch(&server.uri("/short")).await.is_err());
}

#[tokio::test]
async fn should_take_a_whole_list_of_lines() {
    let server = Server::start();
    server.respond("/whole.dat", until_closed(TWO_LINES));

    let body = any_address().fetch_text(&server.uri("/whole.dat")).await.unwrap();

    assert_eq!(body, TWO_LINES);
}

#[tokio::test]
async fn should_refuse_a_list_of_lines_cut_short() {
    let server = Server::start();
    server.respond("/cut.dat", until_closed(cut()));

    failed_with(any_address().fetch_text(&server.uri("/cut.dat")).await, "cut short");
}

#[tokio::test]
async fn should_take_a_body_cut_short_that_is_not_a_list_of_lines() {
    let server = Server::start();
    server.respond("/cut.dat", until_closed(cut()));

    let body = any_address().fetch(&server.uri("/cut.dat")).await.unwrap();

    assert_eq!(body, cut(), "not a list of lines");
}

#[tokio::test]
async fn should_fetch_lists_through_the_http_proxy_of_the_preferences() {
    let server = Server::start();
    // A proxy gets the whole address; nothing listens at the address itself.
    server.respond("http://localhost:9/server.met", response(200, "", b"through the proxy"));
    let proxy = ProxySettings {
        kind: ProxyType::Http,
        host: "127.0.0.1".to_owned(),
        port: server.port,
        ..ProxySettings::default()
    };
    let lists = WebLists::new(Routes::new(move || proxy.clone(), || Binding::Any), |_| true, ANY_TIME);

    let body = lists.fetch("http://localhost:9/server.met").await.unwrap();

    assert_eq!(body, b"through the proxy");
}

// A SOCKS 5 proxy (RFC 1928, user name and password per RFC 1929) that relays one connection both ways.
fn socks5(saw: Arc<Mutex<Vec<String>>>) -> u16 {
    let proxy = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = proxy.local_addr().unwrap().port();
    thread::spawn(move || {
        let (mut client, _) = proxy.accept().unwrap();
        let mut head = [0; 2];
        client.read_exact(&mut head).unwrap();
        let mut methods = vec![0; usize::from(head[1])];
        client.read_exact(&mut methods).unwrap();
        client.write_all(&[5, 2]).unwrap();
        let read_text = |client: &mut TcpStream| {
            let mut length = [0];
            client.read_exact(&mut length).unwrap();
            let mut text = vec![0; usize::from(length[0])];
            client.read_exact(&mut text).unwrap();
            String::from_utf8(text).unwrap()
        };
        let mut version = [0];
        client.read_exact(&mut version).unwrap();
        let (user, password) = (read_text(&mut client), read_text(&mut client));
        saw.lock().unwrap().push(format!("auth {user}:{password}"));
        client.write_all(&[1, 0]).unwrap();
        let mut request = [0; 4];
        client.read_exact(&mut request).unwrap();
        let host = match request[3] {
            3 => read_text(&mut client),
            _ => {
                let mut ip = [0; 4];
                client.read_exact(&mut ip).unwrap();
                Ipv4Addr::from(ip).to_string()
            }
        };
        let mut port = [0; 2];
        client.read_exact(&mut port).unwrap();
        let port = u16::from_be_bytes(port);
        saw.lock().unwrap().push(format!("connect {host}:{port}"));
        let mut upstream = TcpStream::connect((host.as_str(), port)).unwrap();
        client.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 0]).unwrap();
        let (mut from_client, mut to_client) = (client.try_clone().unwrap(), client);
        let mut to_upstream = upstream.try_clone().unwrap();
        thread::spawn(move || {
            let _ = std::io::copy(&mut upstream, &mut to_client);
            let _ = to_client.shutdown(Shutdown::Write);
        });
        let _ = std::io::copy(&mut from_client, &mut to_upstream);
    });
    port
}

#[tokio::test]
async fn should_fetch_lists_through_the_socks_proxy_of_the_preferences_with_its_credentials() {
    let server = Server::start();
    server.respond("/server.met", response(200, "", b"through socks"));
    let saw = Arc::new(Mutex::new(Vec::new()));
    let proxy = ProxySettings {
        kind: ProxyType::Socks,
        host: "127.0.0.1".to_owned(),
        port: socks5(Arc::clone(&saw)),
        user: "me".to_owned(),
        password: "secret".to_owned(),
    };
    let lists = WebLists::new(Routes::new(move || proxy.clone(), || Binding::Any), |_| true, ANY_TIME);

    let body = lists.fetch(&server.uri("/server.met")).await.unwrap();

    assert_eq!(body, b"through socks");
    assert_eq!(*saw.lock().unwrap(), ["auth me:secret".to_owned(), format!("connect 127.0.0.1:{}", server.port)]);
}

#[tokio::test]
async fn should_follow_every_kind_of_redirect() {
    let server = Server::start();
    server.respond("/server.met", response(200, "", SERVER_LIST));
    for status in [303, 307, 308] {
        server.respond(&format!("/moved{status}"), response(status, "Location: /server.met\r\n", b""));

        let body = fetched(&any_address(), &server.uri(&format!("/moved{status}"))).await.unwrap();

        assert_eq!(body, SERVER_LIST, "HTTP {status}");
    }
}

#[tokio::test]
async fn should_refuse_an_address_without_scheme() {
    failed_with(any_address().fetch("server.met").await, "http and https");
}

#[tokio::test]
async fn should_refuse_an_address_without_host() {
    failed_with(any_address().fetch("http:/server.met").await, "no host");
}

#[tokio::test]
async fn should_refuse_a_redirect_to_a_bad_address() {
    let server = Server::start();
    server.respond("/bad", response(302, "Location: http://bad host/list\r\n", b""));

    failed_with(any_address().fetch(&server.uri("/bad")).await, "bad address");
}

#[tokio::test]
async fn should_refuse_a_redirect_without_location() {
    let server = Server::start();
    server.respond("/nowhere", response(302, "", b""));

    failed_with(any_address().fetch(&server.uri("/nowhere")).await, "without a location");
}

#[tokio::test]
async fn should_fetch_nothing_without_time_left() {
    let server = Server::start();
    server.respond("/server.met", response(200, "", &[1]));
    let lists = WebLists::new(Routes::direct(), |_| true, Duration::ZERO);

    failed_with(lists.fetch(&server.uri("/server.met")).await, "in time");
}

#[tokio::test]
async fn should_take_a_gzipped_list_as_it_comes_since_gzip_checks_itself() {
    let server = Server::start();
    server.respond("/packed.gz", until_closed(&gzip(NO_BREAK)));

    let body = any_address().fetch_text(&server.uri("/packed.gz")).await.unwrap();

    assert_eq!(body, NO_BREAK, "gzip checks itself");
}

#[tokio::test]
async fn should_take_a_list_ending_in_a_carriage_return_as_it_comes() {
    let server = Server::start();
    server.respond("/cr.dat", until_closed(CARRIAGE_RETURN));

    let body = any_address().fetch_text(&server.uri("/cr.dat")).await.unwrap();

    assert_eq!(body, CARRIAGE_RETURN);
}

#[tokio::test]
async fn should_take_an_empty_list_as_it_comes() {
    let server = Server::start();
    server.respond("/empty.dat", until_closed(b""));

    let body = any_address().fetch_text(&server.uri("/empty.dat")).await.unwrap();

    assert!(body.is_empty());
}

#[tokio::test]
async fn should_take_a_list_whose_length_was_given_as_it_comes() {
    let server = Server::start();
    server.respond("/sized.dat", response(200, "", NO_BREAK));

    let body = any_address().fetch_text(&server.uri("/sized.dat")).await.unwrap();

    assert_eq!(body, NO_BREAK, "its length was given");
}

#[tokio::test]
async fn should_take_a_chunked_list_as_it_comes() {
    let server = Server::start();
    let mut chunked = b"HTTP/1.1 200 X\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n1d\r\n".to_vec();
    chunked.extend_from_slice(NO_BREAK);
    chunked.extend_from_slice(b"\r\n0\r\n\r\n");
    server.respond("/chunked.dat", chunked);

    let body = any_address().fetch_text(&server.uri("/chunked.dat")).await.unwrap();

    assert_eq!(body, NO_BREAK, "chunks end on their own");
}

#[tokio::test]
async fn should_leave_alone_bodies_that_only_look_packed() {
    let server = Server::start();
    for looks_packed in [&[0x1F, 0x00, b'x'][..], &[b'P', b'K', 1, 2, b'x'], &[b'P', b'K', 3, 9, b'x'], b"P"] {
        server.respond("/a", response(200, "", looks_packed));

        let body = fetched(&any_address(), &server.uri("/a")).await.unwrap();

        assert_eq!(body, looks_packed);
    }
}

#[tokio::test]
async fn should_refuse_an_archive_with_only_folders() {
    let server = Server::start();
    server.respond("/folders.zip", response(200, "", &zip(&[("lists/", b"")])));

    failed_with(any_address().fetch(&server.uri("/folders.zip")).await, "empty zip");
}

#[tokio::test]
async fn should_take_a_gzipped_list_that_fills_the_limit_exactly() {
    let server = Server::start();
    server.respond("/full.gz", response(200, "", &gzipped_zeros(web_lists::MAX_SIZE)));

    let body = fetched(&any_address(), &server.uri("/full.gz")).await.unwrap();

    assert_eq!(body.len() as u64, web_lists::MAX_SIZE);
}

#[tokio::test]
async fn should_refuse_a_gzip_bomb() {
    let server = Server::start();
    server.respond("/bomb.gz", response(200, "", &gzipped_zeros(web_lists::MAX_SIZE + 1024 * 1024)));

    failed_with(any_address().fetch(&server.uri("/bomb.gz")).await, "larger than");
}

#[test]
fn should_not_count_a_private_or_special_address_as_public() {
    for literal in [
        "127.0.0.1",
        "10.1.2.3",
        "192.168.1.1",
        "172.16.0.1",
        "169.254.1.1",
        "100.64.0.1",
        "0.0.0.1",
        "224.0.0.1",
        "::1",
        "fd00::1",
        "fe80::1",
        "0.0.0.0",
        "::",
    ] {
        assert!(!web_lists::is_public(ip(literal)), "{literal}");
    }
}

#[test]
fn should_count_a_public_address() {
    for literal in ["8.8.8.8", "100.128.0.1", "2001:4860:4860::8888"] {
        assert!(web_lists::is_public(ip(literal)), "{literal}");
    }
}
