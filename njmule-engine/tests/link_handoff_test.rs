// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java LinkHandoffTest, under its names: handing ed2k links and exit requests to the running njMule on the same
//! profile.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use njmule_engine::app::link_handoff::{self, LinkHandoff, Taker};

fn link() -> String {
    format!("ed2k://|file|a.iso|10|{}|/", "ab".repeat(16))
}

fn magnet() -> String {
    format!("magnet:?xt=urn:ed2k:{}&xl=10&dn=a.iso", "ab".repeat(16))
}

fn any_secret() -> String {
    "ab".repeat(32)
}

fn too_long() -> String {
    format!("ed2k://{}", "a".repeat(link_handoff::MAX_LINE - 6))
}

#[test]
fn should_hand_the_links_of_a_later_start_to_the_running_one() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_delivers(&[&link(), "http://example.org/not-a-link"]);

    test.then_the_links_were_delivered();
    test.then_the_running_instance_receives(&[&link()]);
}

#[test]
fn should_hand_the_magnet_links_of_a_later_start_to_the_running_one() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_delivers(&[&magnet()]);

    test.then_the_links_were_delivered();
    test.then_the_running_instance_receives(&[&magnet()]);
}

#[test]
fn should_hand_a_start_that_only_wants_the_window_to_the_running_one() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_delivers(&[]);

    test.then_the_links_were_delivered();
    test.then_the_running_instance_receives(&[]);
}

#[test]
fn should_tell_that_the_running_one_has_a_window_to_show() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_hands_over(&[&link()]);

    test.then_the_taker_is(Taker::Window);
}

#[test]
fn should_tell_that_the_running_one_has_no_window() {
    let mut test = Scenario::new();
    test.given_a_running_headless_instance();

    test.when_a_later_start_hands_over(&[&link()]);

    test.then_the_taker_is(Taker::Headless);
    test.then_the_running_instance_receives(&[&link()]);
}

#[test]
fn should_remove_the_handoff_file_when_the_running_one_closes() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_the_running_instance_closes();

    test.then_there_is_no_handoff_file();
}

#[test]
fn should_find_nobody_listening_once_the_running_one_closed() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    test.given_the_running_instance_closed();

    test.when_a_later_start_delivers(&[&link()]);

    test.then_nobody_listens();
}

#[test]
fn should_keep_the_handoff_file_to_its_owner() {
    let mut test = Scenario::new();

    test.when_the_instance_starts_listening();

    test.then_only_the_owner_can_read_the_handoff_file();
}

#[test]
fn should_let_only_who_knows_the_secret_hand_over_links() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    test.given_another_secret_in_the_handoff_file();

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_links_were_not_delivered();
    test.then_the_running_instance_receives_nothing_within(Duration::from_millis(500));
}

#[test]
fn should_leave_the_handoff_file_another_instance_wrote() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    test.given_another_secret_in_the_handoff_file();

    test.when_the_running_instance_closes();

    test.then_the_handoff_file_stays();
}

#[test]
fn should_take_the_links_even_if_the_receiver_fails() {
    let mut test = Scenario::new();
    test.given_a_running_instance_that_cannot_follow_links();

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_later_start_does_not_run_beside_it();
    test.then_the_running_instance_receives(&[&link()]);
}

#[test]
fn should_not_let_a_link_smuggle_in_another_line_or_an_empty_one_end_the_list() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    let smuggled = format!("ed2k://|server|x|1|/\n{}", link_handoff::EXIT);

    test.when_a_later_start_delivers(&[&smuggled, "", "a\rb", &link()]);

    test.then_the_links_were_delivered();
    test.then_the_running_instance_receives(&[&link()]);
}

#[test]
fn should_take_the_exit_request_only_alone() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_sends_the_secret_and(&format!("{}\n{}\n\n", link(), link_handoff::EXIT));

    test.then_the_answer_starts_with("OK");
    test.then_the_running_instance_receives(&[&link()]);
}

#[test]
fn should_not_let_a_slow_local_program_hold_anybody_up() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    test.given_a_slow_local_program();

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_links_were_delivered();
    test.then_the_running_instance_receives(&[&link()]);
    test.then_the_slow_program_is_dropped();
    test.then_it_all_took_less_than(link_handoff::DEADLINE + Duration::from_secs(5));
}

#[test]
fn should_take_a_file_left_by_a_crash_for_no_running_instance() {
    let mut test = Scenario::new();
    test.given_the_handoff_file(&format!("1 {}\n", any_secret()));

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_links_were_not_delivered();
}

#[test]
fn should_take_a_file_with_an_invalid_port_for_no_running_instance() {
    for port in ["70000", "65536", "-1", "0"] {
        let mut test = Scenario::new();
        test.given_the_handoff_file(&format!("{port} {}\n", any_secret()));

        test.when_a_later_start_delivers(&[&link()]);

        test.then_the_links_were_not_delivered();
    }
}

#[test]
fn should_take_a_damaged_file_for_no_running_instance() {
    let damaged = ["4711".to_owned(), format!("port {}", any_secret()), "1 2 3".to_owned()];
    for content in damaged {
        let mut test = Scenario::new();
        test.given_the_handoff_file(&format!("{content}\n"));

        test.when_a_later_start_delivers(&[&link()]);

        test.then_the_links_were_not_delivered();
    }
}

#[test]
fn should_take_an_unreadable_file_for_no_running_instance() {
    let mut test = Scenario::new();
    test.given_the_handoff_file_is_a_directory();

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_links_were_not_delivered();
}

#[test]
fn should_need_a_place_for_the_file_to_listen() {
    let mut test = Scenario::new();
    test.given_a_file_in_place_of_the_configuration_directory();

    test.when_trying_to_listen();

    test.then_an_io_error_occurred();
}

#[test]
fn should_cope_with_a_file_already_gone_when_closing() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    test.given_the_handoff_file_was_deleted();
    test.given_the_running_instance_closed();

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_links_were_not_delivered();
}

#[test]
fn should_give_no_answer_to_a_program_without_the_secret() {
    for sent in ["", "not hex\n", "ab\n"] {
        let mut test = Scenario::new();
        test.given_a_running_instance();

        test.when_a_program_sends(sent);

        test.then_there_is_no_answer();
        test.then_the_running_instance_receives_nothing_within(Duration::from_millis(300));
    }
}

#[test]
fn should_take_at_most_so_many_links() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_sends_the_secret_and(&file_links(link_handoff::MAX_LINKS + 10));

    test.then_the_answer_starts_with("OK");
    test.then_the_running_instance_receives_so_many_links_starting_with(link_handoff::MAX_LINKS, "|0.iso|");
}

#[test]
fn should_take_the_last_link_without_its_line_end() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_sends_the_secret_and(&link());

    test.then_the_answer_starts_with("OK");
    test.then_the_running_instance_receives(&[&link()]);
}

#[test]
fn should_end_the_exchange_at_a_line_too_long() {
    let mut test = Scenario::new();
    test.given_a_running_instance();

    test.when_a_later_start_sends_the_secret_and(&too_long());

    test.then_there_is_no_answer();
    test.then_the_running_instance_receives_nothing_within(Duration::from_millis(300));
}

#[test]
fn should_still_listen_after_a_line_too_long() {
    let mut test = Scenario::new();
    test.given_a_running_instance();
    test.given_a_later_start_sent_the_secret_and(&too_long());

    test.when_a_later_start_delivers(&[&link()]);

    test.then_the_running_instance_still_listens();
}

#[test]
fn should_not_take_an_instance_holding_its_lock_for_gone() {
    let mut test = Scenario::new();
    test.given_an_instance_holding_its_lock();

    test.when_it_is_awaited_to_go(Duration::from_millis(200));

    test.then_it_has_not_gone();
}

#[test]
fn should_take_an_instance_for_gone_once_nobody_holds_its_lock() {
    let mut test = Scenario::new();
    test.given_an_instance_holding_its_lock();
    test.given_it_released_its_lock();

    test.when_it_is_awaited_to_go(Duration::from_secs(5));

    test.then_it_has_gone();
}

#[test]
fn should_take_an_instance_for_gone_when_its_lock_cannot_be_opened_and_its_handoff_file_is_gone() {
    let mut test = Scenario::new();
    test.given_a_lock_that_cannot_be_opened();

    test.when_it_is_awaited_to_go(Duration::from_millis(300));

    test.then_it_has_gone();
}

#[test]
fn should_not_take_an_instance_for_gone_when_its_lock_cannot_be_opened_and_its_handoff_file_stays() {
    let mut test = Scenario::new();
    test.given_a_lock_that_cannot_be_opened();
    test.given_the_handoff_file(&format!("1 {}\n", any_secret()));

    test.when_it_is_awaited_to_go(Duration::from_millis(300));

    test.then_it_has_not_gone();
}

fn file_links(count: usize) -> String {
    let mut links = String::new();
    let hash = "ab".repeat(16);
    for i in 0..count {
        links.push_str(&format!("ed2k://|file|{i}.iso|10|{hash}|/\n"));
    }
    links
}

struct Scenario {
    dir: tempfile::TempDir,
    sender: Sender<Vec<String>>,
    received: Receiver<Vec<String>>,
    handoff: Option<LinkHandoff>,
    not_a_directory: PathBuf,
    slow: Option<TcpStream>,
    stop_trickle: Arc<AtomicBool>,
    trickle: Option<JoinHandle<()>>,
    start: Instant,
    lock_file: Option<File>,
    delivered: bool,
    taker: Taker,
    gone: bool,
    answer: String,
    failure: Option<io::Error>,
}

impl Scenario {
    fn new() -> Self {
        let (sender, received) = mpsc::channel();
        Self {
            dir: tempfile::tempdir().unwrap(),
            sender,
            received,
            handoff: None,
            not_a_directory: PathBuf::new(),
            slow: None,
            stop_trickle: Arc::new(AtomicBool::new(false)),
            trickle: None,
            start: Instant::now(),
            lock_file: None,
            delivered: false,
            taker: Taker::None,
            gone: false,
            answer: String::new(),
            failure: None,
        }
    }

    fn given_a_running_instance(&mut self) {
        let sender = self.sender.clone();
        self.handoff = Some(
            LinkHandoff::listen(self.dir.path(), move |links| {
                let _ = sender.send(links);
            })
            .unwrap(),
        );
    }

    fn given_a_running_headless_instance(&mut self) {
        let sender = self.sender.clone();
        self.handoff = Some(
            LinkHandoff::listen_headless(self.dir.path(), true, move |links| {
                let _ = sender.send(links);
            })
            .unwrap(),
        );
    }

    fn given_a_running_instance_that_cannot_follow_links(&mut self) {
        let sender = self.sender.clone();
        self.handoff = Some(
            LinkHandoff::listen(self.dir.path(), move |links| {
                let _ = sender.send(links);
                panic!("cannot follow");
            })
            .unwrap(),
        );
    }

    fn given_the_running_instance_closed(&mut self) {
        self.when_the_running_instance_closes();
    }

    fn given_another_secret_in_the_handoff_file(&self) {
        fs::write(self.dir.path().join(link_handoff::FILE_NAME), format!("{} {}\n", self.port(), "00".repeat(32)))
            .unwrap();
    }

    fn given_a_slow_local_program(&mut self) {
        let stream = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, self.port()))).unwrap();
        let mut writer = stream.try_clone().unwrap();
        let stop = Arc::clone(&self.stop_trickle);
        self.trickle = Some(thread::spawn(move || {
            while !stop.load(Ordering::SeqCst) {
                if writer.write_all(b"a").is_err() {
                    break;
                }
                thread::sleep(Duration::from_millis(200));
            }
        }));
        self.slow = Some(stream);
        self.start = Instant::now();
    }

    fn given_the_handoff_file(&self, content: &str) {
        fs::write(self.dir.path().join(link_handoff::FILE_NAME), content).unwrap();
    }

    fn given_the_handoff_file_is_a_directory(&self) {
        fs::create_dir_all(self.dir.path().join(link_handoff::FILE_NAME)).unwrap();
    }

    fn given_the_handoff_file_was_deleted(&self) {
        fs::remove_file(self.dir.path().join(link_handoff::FILE_NAME)).unwrap();
    }

    fn given_a_file_in_place_of_the_configuration_directory(&mut self) {
        self.not_a_directory = self.dir.path().join("config");
        fs::write(&self.not_a_directory, "a file").unwrap();
    }

    fn given_a_later_start_sent_the_secret_and(&self, lines: &str) {
        let _ = self.exchange(&format!("{}\n{lines}", self.secret()));
    }

    fn given_an_instance_holding_its_lock(&mut self) {
        let file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(false)
            .open(self.dir.path().join("njmule.lock"))
            .unwrap();
        file.lock().unwrap();
        self.lock_file = Some(file);
    }

    fn given_it_released_its_lock(&mut self) {
        self.lock_file = None;
    }

    fn given_a_lock_that_cannot_be_opened(&self) {
        fs::create_dir_all(self.dir.path().join("njmule.lock")).unwrap();
    }

    fn when_the_instance_starts_listening(&mut self) {
        self.given_a_running_instance();
    }

    fn when_trying_to_listen(&mut self) {
        let sender = self.sender.clone();
        self.failure = LinkHandoff::listen(&self.not_a_directory, move |links| {
            let _ = sender.send(links);
        })
        .err();
    }

    fn when_a_later_start_hands_over(&mut self, links: &[&str]) {
        self.taker = LinkHandoff::hand_over(self.dir.path(), links);
    }

    fn when_a_later_start_delivers(&mut self, links: &[&str]) {
        self.delivered = LinkHandoff::deliver(self.dir.path(), links);
    }

    fn when_a_later_start_sends_the_secret_and(&mut self, lines: &str) {
        self.answer = self.exchange(&format!("{}\n{lines}", self.secret()));
    }

    fn when_a_program_sends(&mut self, text: &str) {
        self.answer = self.exchange(text);
    }

    fn when_the_running_instance_closes(&mut self) {
        if let Some(mut handoff) = self.handoff.take() {
            handoff.close();
        }
    }

    fn when_it_is_awaited_to_go(&mut self, timeout: Duration) {
        self.gone = LinkHandoff::await_gone(self.dir.path(), timeout);
    }

    fn then_the_taker_is(&self, expected: Taker) {
        assert_eq!(self.taker, expected);
    }

    fn then_the_links_were_delivered(&self) {
        assert!(self.delivered);
    }

    fn then_the_links_were_not_delivered(&self) {
        assert!(!self.delivered);
    }

    fn then_nobody_listens(&self) {
        assert!(!self.delivered, "nobody listens");
    }

    fn then_the_later_start_does_not_run_beside_it(&self) {
        assert!(self.delivered, "the later start must not try to run beside it");
    }

    fn then_the_running_instance_still_listens(&self) {
        assert!(self.delivered, "still listening");
    }

    fn then_the_running_instance_receives(&self, links: &[&str]) {
        let got = self.received.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(got, links);
    }

    fn then_the_running_instance_receives_so_many_links_starting_with(&self, count: usize, first_part: &str) {
        let got = self.received.recv_timeout(Duration::from_secs(5)).unwrap();
        assert_eq!(got.len(), count);
        assert!(got[0].contains(first_part), "{}", got[0]);
    }

    fn then_the_running_instance_receives_nothing_within(&self, timeout: Duration) {
        assert!(self.received.recv_timeout(timeout).is_err());
    }

    fn then_the_answer_starts_with(&self, start: &str) {
        assert!(self.answer.starts_with(start), "{:?}", self.answer);
    }

    fn then_there_is_no_answer(&self) {
        assert!(self.answer.is_empty(), "{:?}", self.answer);
    }

    fn then_there_is_no_handoff_file(&self) {
        assert!(!self.dir.path().join(link_handoff::FILE_NAME).exists());
    }

    fn then_the_handoff_file_stays(&self) {
        assert!(self.dir.path().join(link_handoff::FILE_NAME).exists());
    }

    fn then_only_the_owner_can_read_the_handoff_file(&self) {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode =
                fs::metadata(self.dir.path().join(link_handoff::FILE_NAME)).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }
    }

    fn then_the_slow_program_is_dropped(&mut self) {
        let slow = self.slow.as_mut().unwrap();
        slow.set_read_timeout(Some(link_handoff::DEADLINE + Duration::from_secs(5))).unwrap();
        let mut buf = [0u8; 1];
        let closed = match slow.read(&mut buf) {
            Ok(0) => true,
            Err(e) if e.kind() == io::ErrorKind::ConnectionReset || e.kind() == io::ErrorKind::ConnectionAborted => {
                true
            }
            other => panic!("expected dropped connection, got {other:?}"),
        };
        assert!(closed);
    }

    fn then_it_all_took_less_than(&self, limit: Duration) {
        assert!(self.start.elapsed() < limit, "took {:?}", self.start.elapsed());
    }

    fn then_an_io_error_occurred(&self) {
        assert!(self.failure.is_some());
    }

    fn then_it_has_gone(&self) {
        assert!(self.gone);
    }

    fn then_it_has_not_gone(&self) {
        assert!(!self.gone);
    }

    fn port(&self) -> u16 {
        let content = fs::read_to_string(self.dir.path().join(link_handoff::FILE_NAME)).unwrap();
        content.trim().split(' ').next().unwrap().parse().unwrap()
    }

    fn secret(&self) -> String {
        let content = fs::read_to_string(self.dir.path().join(link_handoff::FILE_NAME)).unwrap();
        content.trim().split(' ').nth(1).unwrap().to_owned()
    }

    fn exchange(&self, text: &str) -> String {
        let mut client = TcpStream::connect(SocketAddr::from((Ipv4Addr::LOCALHOST, self.port()))).unwrap();
        client.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let _ = client.write_all(text.as_bytes());
        let _ = client.shutdown(Shutdown::Write);
        let mut answer = Vec::new();
        let _ = client.read_to_end(&mut answer);
        String::from_utf8_lossy(&answer).into_owned()
    }
}

impl Drop for Scenario {
    fn drop(&mut self) {
        self.stop_trickle.store(true, Ordering::SeqCst);
        self.slow = None;
        if let Some(handle) = self.trickle.take() {
            let _ = handle.join();
        }
        self.handoff = None;
        self.lock_file = None;
    }
}
