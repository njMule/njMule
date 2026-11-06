// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's WebListsDownloadTest, under its names: downloads of a file to disk from a small server on the
//! loopback that answers ranges as the test chooses.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::net::{Ipv4Addr, Shutdown, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use njmule_engine::net::routes::Routes;
use njmule_engine::net::web_lists::WebLists;

fn file() -> Vec<u8> {
    "the package of a new version, ".repeat(400).into_bytes()
}

/// How the server answers a request for a range.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Behaviour {
    Ranges,
    Whole,
    UnsatisfiableOnce,
}

#[derive(Clone)]
struct Server {
    port: u16,
    ranges: Arc<Mutex<Vec<String>>>,
    behaviour: Arc<Mutex<Behaviour>>,
    // The length the server announces, if any, and the bytes it sends at most.
    announced: Arc<Mutex<Option<usize>>>,
    sent: Arc<Mutex<usize>>,
}

impl Server {
    fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let server = Self {
            port: listener.local_addr().unwrap().port(),
            ranges: Arc::default(),
            behaviour: Arc::new(Mutex::new(Behaviour::Ranges)),
            announced: Arc::new(Mutex::new(Some(file().len()))),
            sent: Arc::new(Mutex::new(file().len())),
        };
        let serving = server.clone();
        thread::spawn(move || {
            for client in listener.incoming() {
                let Ok(client) = client else { return };
                serving.answer(client);
            }
        });
        server
    }

    // One request a connection: the path and the range, then the answer the test chose.
    fn answer(&self, mut client: TcpStream) {
        let mut reader = BufReader::new(client.try_clone().unwrap());
        let mut request = String::new();
        if reader.read_line(&mut request).unwrap_or(0) == 0 {
            // The client opened the connection and closed it unused.
            return;
        }
        let path = request.split(' ').nth(1).unwrap_or_default().to_owned();
        let mut from = 0;
        loop {
            let mut line = String::new();
            if reader.read_line(&mut line).unwrap_or(0) <= 2 {
                break;
            }
            if line.to_ascii_lowercase().starts_with("range:") {
                let range = line[6..].trim().to_owned();
                from = range["bytes=".len()..range.find('-').unwrap()].parse().unwrap();
                self.ranges.lock().unwrap().push(range);
            }
        }
        let file = file();
        let mut out = Vec::new();
        if path == "/old" {
            out.extend(b"HTTP/1.1 302 Found\r\nLocation: /file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        } else if from > 0 && *self.behaviour.lock().unwrap() == Behaviour::UnsatisfiableOnce {
            *self.behaviour.lock().unwrap() = Behaviour::Ranges;
            out.extend(
                format!(
                    "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                    file.len()
                )
                .bytes(),
            );
        } else {
            let partial = from > 0 && *self.behaviour.lock().unwrap() == Behaviour::Ranges;
            let start = if partial { from } else { 0 };
            let mut head =
                if partial { "HTTP/1.1 206 Partial Content\r\n".to_owned() } else { "HTTP/1.1 200 OK\r\n".to_owned() };
            if partial {
                head.push_str(&format!("Content-Range: bytes {start}-{}/{}\r\n", file.len() - 1, file.len()));
            }
            if let Some(announced) = *self.announced.lock().unwrap() {
                head.push_str(&format!("Content-Length: {}\r\n", announced - start));
            }
            head.push_str("Connection: close\r\n\r\n");
            out.extend(head.bytes());
            let sent = *self.sent.lock().unwrap();
            out.extend(&file[start..sent.max(start)]);
        }
        let _ = client.write_all(&out);
        let _ = client.flush();
        let _ = client.shutdown(Shutdown::Both);
    }
}

struct Download {
    server: Server,
    _dir: tempfile::TempDir,
    part: PathBuf,
    expected: u64,
    redirect: bool,
    progress: Vec<u64>,
}

impl Download {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("njmule.zip.part");
        Self {
            server: Server::start(),
            _dir: dir,
            part,
            expected: file().len() as u64,
            redirect: false,
            progress: Vec::new(),
        }
    }

    fn part_holds(&self, bytes: usize) {
        fs::write(&self.part, &file()[..bytes]).unwrap();
    }

    async fn run(&mut self) -> std::io::Result<()> {
        let uri = format!("http://127.0.0.1:{}{}", self.server.port, if self.redirect { "/old" } else { "/file" });
        let lists = WebLists::new(Routes::direct(), |_| true, Duration::from_secs(90));
        let progress = &mut self.progress;
        lists.download(&uri, &self.part, self.expected, |held| progress.push(held)).await
    }

    fn ranges(&self) -> Vec<String> {
        self.server.ranges.lock().unwrap().clone()
    }
}

#[tokio::test]
async fn should_download_a_file_into_its_part() {
    let mut download = Download::new();

    download.run().await.unwrap();

    assert_eq!(fs::read(&download.part).unwrap(), file());
    assert_eq!(download.progress.last(), Some(&(file().len() as u64)));
    assert!(download.ranges().is_empty());
}

#[tokio::test]
async fn should_go_on_from_the_bytes_the_part_holds() {
    let mut download = Download::new();
    download.part_holds(1000);

    download.run().await.unwrap();

    assert_eq!(fs::read(&download.part).unwrap(), file());
    assert_eq!(download.ranges(), ["bytes=1000-"]);
}

#[tokio::test]
async fn should_follow_a_redirect_with_the_range() {
    let mut download = Download::new();
    download.part_holds(1000);
    download.redirect = true;

    download.run().await.unwrap();

    assert_eq!(fs::read(&download.part).unwrap(), file());
    assert_eq!(download.ranges(), ["bytes=1000-", "bytes=1000-"]);
}

#[tokio::test]
async fn should_start_again_when_the_server_sends_the_whole_file() {
    let mut download = Download::new();
    download.part_holds(1000);
    *download.server.behaviour.lock().unwrap() = Behaviour::Whole;

    download.run().await.unwrap();

    assert_eq!(fs::read(&download.part).unwrap(), file());
}

#[tokio::test]
async fn should_start_again_when_the_range_no_longer_fits() {
    let mut download = Download::new();
    download.part_holds(1000);
    *download.server.behaviour.lock().unwrap() = Behaviour::UnsatisfiableOnce;

    download.run().await.unwrap();

    assert_eq!(fs::read(&download.part).unwrap(), file());
    assert_eq!(download.ranges(), ["bytes=1000-"]);
}

#[tokio::test]
async fn should_not_ask_for_a_file_the_part_holds_whole() {
    let mut download = Download::new();
    download.part_holds(file().len());

    download.run().await.unwrap();

    assert!(download.ranges().is_empty());
    assert_eq!(download.progress, [file().len() as u64]);
}

#[tokio::test]
async fn should_refuse_a_file_that_says_it_is_longer() {
    let mut download = Download::new();
    download.expected = file().len() as u64 - 1;

    let failure = download.run().await.err().unwrap();

    assert!(failure.to_string().contains("longer than"), "{failure}");
}

#[tokio::test]
async fn should_refuse_a_file_that_turns_out_longer() {
    let mut download = Download::new();
    *download.server.announced.lock().unwrap() = None;
    download.expected = file().len() as u64 - 1;

    let failure = download.run().await.err().unwrap();

    assert!(failure.to_string().contains("longer than"), "{failure}");
}

#[tokio::test]
async fn should_leave_what_came_when_the_file_is_cut_short() {
    let mut download = Download::new();
    *download.server.sent.lock().unwrap() = 1000;

    let failure = download.run().await;

    assert!(failure.is_err());
    assert_eq!(fs::metadata(&download.part).unwrap().len(), 1000);
}
