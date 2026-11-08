// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the tests of the web interface share: requests sent as raw bytes, as a browser or a caller may send them, the
//! answers read back, and an interface laid out as Gradle ships it.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::io::{Read, Write};
use std::path::Path;

use flate2::Compression;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use njmule_engine::remote::passwords;
use njmule_engine::remote::web_app::WebApp;
use njmule_engine::remote::web_ui_settings::WebUiSettings;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

pub const PASSWORD: &str = "correct horse";

/// The settings of an interface on a free port of this computer, with the password `password`.
pub fn settings(password: &str) -> WebUiSettings {
    WebUiSettings {
        enabled: true,
        port: 0,
        password_hash: passwords::hash_with(password, &[0u8; 16], 1000),
        ..WebUiSettings::default()
    }
}

/// An answer as it came: its status, its headers in their order, and its body.
#[derive(Debug, Default)]
pub struct Answer {
    pub raw: String,
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Answer {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(header, _)| header.eq_ignore_ascii_case(name)).map(|(_, value)| value.as_str())
    }

    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    /// The body unpacked from gzip.
    pub fn unpacked(&self) -> String {
        let mut text = String::new();
        GzDecoder::new(self.body.as_slice()).read_to_string(&mut text).unwrap();
        text
    }

    /// The `name=value` the answer's cookie sets.
    pub fn cookie(&self) -> String {
        let set = self.header("Set-Cookie").expect("a cookie");
        set.split(';').next().unwrap().to_owned()
    }
}

/// A request to `path` of 127.0.0.1:`port`, which closes its connection once answered.
pub fn request(port: u16, method: &str, path: &str, headers: &[(&str, &str)], body: &str) -> String {
    let mut request = format!("{method} {path} HTTP/1.1\r\nConnection: close\r\n");
    if !headers.iter().any(|(name, _)| name.eq_ignore_ascii_case("Host")) {
        request.push_str(&format!("Host: 127.0.0.1:{port}\r\n"));
    }
    for (name, value) in headers {
        request.push_str(&format!("{name}: {value}\r\n"));
    }
    if !body.is_empty() || method == "POST" {
        request.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    request.push_str("\r\n");
    request.push_str(body);
    request
}

/// The fields of a form as a browser sends them.
pub fn form(fields: &[(&str, &str)]) -> String {
    form_urlencoded::Serializer::new(String::new()).extend_pairs(fields).finish()
}

/// Sends `request` on a connection of its own and reads the answer until the connection closes.
pub async fn exchange(port: u16, request: &str) -> Answer {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut bytes = Vec::new();
    stream.read_to_end(&mut bytes).await.unwrap();
    parse(&bytes)
}

/// An answer read from its bytes; a chunked body is joined.
pub fn parse(bytes: &[u8]) -> Answer {
    let raw = String::from_utf8_lossy(bytes).into_owned();
    let end = bytes.windows(4).position(|window| window == b"\r\n\r\n").expect("an answer's head");
    let head = String::from_utf8_lossy(&bytes[..end]).into_owned();
    let mut lines = head.lines();
    let status = lines.next().unwrap()[9..12].parse().unwrap();
    let headers: Vec<(String, String)> = lines
        .filter_map(|line| line.split_once(':'))
        .map(|(name, value)| (name.trim().to_owned(), value.trim().to_owned()))
        .collect();
    let mut body = bytes[end + 4..].to_vec();
    let chunked =
        headers.iter().any(|(name, value)| name.eq_ignore_ascii_case("Transfer-Encoding") && value == "chunked");
    if chunked {
        body = unchunked(&body);
    }
    Answer { raw, status, headers, body }
}

fn unchunked(mut rest: &[u8]) -> Vec<u8> {
    let mut body = Vec::new();
    while let Some(line) = rest.windows(2).position(|window| window == b"\r\n") {
        let size = usize::from_str_radix(String::from_utf8_lossy(&rest[..line]).trim(), 16).unwrap();
        if size == 0 {
            break;
        }
        body.extend_from_slice(&rest[line + 2..line + 2 + size]);
        rest = &rest[line + 2 + size + 2..];
    }
    body
}

/// The session token the app's page carries.
pub fn page_token(page: &str) -> String {
    let start = page.find("name=\"njmule-csrf\" content=\"").expect("the session token") + 28;
    page[start..].split('"').next().unwrap().to_owned()
}

/// The interface laid out in `dir` as Gradle ships it: its page, a translation and a script compressed, and a flag.
pub fn web_app(dir: &Path) -> WebApp {
    std::fs::create_dir_all(dir.join("i18n")).unwrap();
    std::fs::create_dir_all(dir.join("flags")).unwrap();
    std::fs::create_dir_all(dir.join("assets")).unwrap();
    std::fs::write(
        dir.join("index.html"),
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\" />\
         <meta name=\"njmule-csrf\" content=\"__NJMULE_CSRF__\" />\
         <meta name=\"njmule-role\" content=\"__NJMULE_ROLE__\" /><title>njMule</title></head>\
         <body><div id=\"app\"></div><script type=\"module\" src=\"assets/index-B1x2.js\"></script></body></html>",
    )
    .unwrap();
    std::fs::write(dir.join("i18n/en.json.gz"), gzip("{\n  \"IDS_EM_TRANS\": \"Transfers\"\n}\n")).unwrap();
    std::fs::write(dir.join("assets/index-B1x2.js.gz"), gzip("console.log('njMule');\n")).unwrap();
    std::fs::write(dir.join("flags/it.png"), b"\x89PNG\r\n\x1a\n").unwrap();
    WebApp::from_folder(dir.to_path_buf())
}

fn gzip(text: &str) -> Vec<u8> {
    let mut packer = GzEncoder::new(Vec::new(), Compression::best());
    packer.write_all(text.as_bytes()).unwrap();
    packer.finish().unwrap()
}
