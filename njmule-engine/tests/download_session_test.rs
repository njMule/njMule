// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's DownloadSessionTest, under its names: one session with a scripted source, run by a loop of its
//! own, telling only what the Java test's listener hears: data and the end.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects,
    clippy::panic
)]

use crate::scripted_source;

use std::net::{Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

use njmule_engine::api::download_info::DownloadPriority;
use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::file_comment::FileComment;
use njmule_engine::download::download_queue::DownloadQueue;
use njmule_engine::download::download_session::{self, Context, DownloadSession, MAX_NAME, Note, State};
use njmule_engine::files::part_file::PartFile;
use njmule_engine::files::part_file_storage::DiskDone;
use njmule_engine::net::tcp_connection::TcpConnection;
use njmule_engine::peer::peer_link::{self, LinkEvent};
use njmule_engine::protocol::ed2k::client_messages::{FileStatus, SendingPart};
use njmule_engine::protocol::ed2k::file_description;
use njmule_engine::protocol::ed2k::hello::{self, Hello, Ours};
use njmule_engine::protocol::io::PacketWriter;
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use rand::SeedableRng;
use rand::rngs::StdRng;
use scripted_source::{Link, ScriptedSource, WAIT};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};

const PEER: [u8; 16] = [0x6a; 16];
const MASTER: [u8; 20] = [0x21; 20];

// What the loop owns: the queue, the session and what it works with.
struct Lab {
    queue: DownloadQueue,
    session: DownloadSession,
    ours: Hello,
    random: StdRng,
    told: mpsc::UnboundedSender<String>,
    connected: mpsc::UnboundedSender<std::io::Result<TcpStream>>,
    links: mpsc::UnboundedSender<LinkEvent>,
    // Connection attempts whose outcome reached the session.
    connects: u32,
}

type Job = Box<dyn FnOnce(&mut Lab) + Send>;

impl Lab {
    fn with<T>(&mut self, work: impl FnOnce(&mut DownloadSession, &mut Context<'_, StdRng>) -> T) -> T {
        let mut ctx =
            Context { queue: &mut self.queue, ours: &self.ours, random: &mut self.random, now: Instant::now() };
        let result = work(&mut self.session, &mut ctx);
        self.settle();
        result
    }

    // The connection is opened apart: its outcome comes back to the loop.
    fn connect(&mut self) {
        let (address, connected) = (self.session.address(), self.connected.clone());
        tokio::spawn(async move {
            let _ = connected.send(TcpStream::connect(address).await);
        });
    }

    fn connection(&mut self, stream: std::io::Result<TcpStream>) {
        self.connects += 1;
        match stream {
            Ok(stream) => {
                let remote = stream.peer_addr().unwrap();
                let local = stream.local_addr().ok().map(|address| address.ip());
                let (input, output) = stream.into_split();
                let connection = TcpConnection::over(input, output, remote, local, frame_limits::client_handshake);
                let handle = peer_link::start(1, connection, self.links.clone());
                let ours = self.ours.clone();
                self.session.opened(handle, &ours);
            }
            Err(e) => {
                let reason = e.to_string();
                self.with(|session, ctx| session.unreachable(&reason, ctx));
            }
        }
        self.settle();
    }

    fn event(&mut self, event: LinkEvent) {
        match event {
            LinkEvent::Packet(_, packet)
                if self.session.state() == State::Greeting
                    && (packet.protocol(), packet.opcode()) == (protocol::EDONKEY, client::HELLOANSWER) =>
            {
                let hello = Hello::decode(&packet).unwrap();
                self.with(|session, ctx| session.greeted(hello, ctx));
            }
            LinkEvent::Packet(_, packet) => self.with(|session, ctx| session.received(&packet, ctx)),
            LinkEvent::Closed(_, reason) => self.with(|session, ctx| session.closed(reason.as_deref(), ctx)),
        }
    }

    fn settle(&mut self) {
        for note in self.session.take_notes() {
            let told = match note {
                Note::Received { bytes, .. } => format!("received {bytes}"),
                Note::Ended { outcome, reason } => format!("ended {} {reason}", format!("{outcome:?}").to_uppercase()),
                _ => continue,
            };
            let _ = self.told.send(told);
        }
        let _ = self.queue.take_events();
    }
}

async fn serve(
    mut lab: Lab,
    mut jobs: mpsc::UnboundedReceiver<Job>,
    mut links: mpsc::UnboundedReceiver<LinkEvent>,
    mut connected: mpsc::UnboundedReceiver<std::io::Result<TcpStream>>,
    mut disk: mpsc::UnboundedReceiver<DiskDone>,
) {
    loop {
        tokio::select! {
            Some(event) = links.recv() => lab.event(event),
            Some(stream) = connected.recv() => lab.connection(stream),
            Some(done) = disk.recv() => {
                lab.queue.disk_done(done);
                lab.settle();
            }
            job = jobs.recv() => match job {
                Some(job) => job(&mut lab),
                None => {
                    lab.queue.close();
                    return;
                }
            },
        }
    }
}

struct Session {
    dir: tempfile::TempDir,
    source: Option<ScriptedSource>,
    link: Option<Link>,
    jobs: Option<mpsc::UnboundedSender<Job>>,
    told: Option<mpsc::UnboundedReceiver<String>>,
    hash: [u8; 16],
    closed_port: u16,
    shown_name: Option<String>,
}

impl Session {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            source: None,
            link: None,
            jobs: None,
            told: None,
            hash: [0; 16],
            closed_port: 0,
            shown_name: None,
        }
    }

    async fn given_a_source(&mut self, misc1: u32, misc2: u32) {
        self.source = Some(ScriptedSource::new(PEER, misc1, misc2).await);
    }

    fn given_a_session_for_the_file(&mut self, file: [u8; 16]) {
        let address = self.source.as_ref().unwrap().address();
        self.start(file, address);
    }

    async fn given_a_closed_port(&mut self) {
        let probe = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        self.closed_port = probe.local_addr().unwrap().port();
    }

    fn given_a_session_to_the_closed_port_for_the_file(&mut self, file: [u8; 16]) {
        self.start(file, SocketAddr::from((Ipv4Addr::LOCALHOST, self.closed_port)));
    }

    async fn given_the_session_connected(&mut self) {
        self.run(Lab::connect).await;
        self.link = Some(self.source().accept().await);
    }

    // The source answers our file request at once; it asks for no sources, which are not exchanged yet.
    async fn given_the_source_described_the_file(&mut self) {
        let hash = self.hash;
        let link = self.link();
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&file_description::encode(&FileComment::of("fine", 3)).unwrap()).await;
        let mut out = PacketWriter::new();
        out.hash16(&hash).hash20(&MASTER);
        link.send(&Packet::written(protocol::EMULE, extended::AICHFILEHASHANS, out)).await;
        link.send(&whole_file(hash)).await;
    }

    async fn given_the_source_took_back_the_slot_it_gave_us(&mut self) {
        let link = self.link();
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        link.send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
        link.until(protocol::EDONKEY, client::REQUESTPARTS).await;
        link.send(&Packet::empty(protocol::EDONKEY, client::OUTOFPARTREQS)).await;
        self.wait_for("back in the queue", |lab| lab.session.state() == State::Queued).await;
    }

    // Asked for recovery data, it has none; asked again, it sends some.
    async fn given_the_source_had_no_recovery_data_at_first(&mut self) {
        assert!(self.run(|lab| lab.session.may_recover(0)).await);
        self.run(|lab| lab.session.request_aich(0, &MASTER)).await;
        let hash = self.hash;
        let link = self.link();
        let asked = link.until(protocol::EMULE, extended::AICHREQUEST).await.pop().unwrap();
        assert_eq!(asked.reader().hash16().unwrap(), hash);
        let mut out = PacketWriter::new();
        out.hash16(&hash);
        link.send(&Packet::written(protocol::EMULE, extended::AICHANSWER, out)).await;
    }

    async fn given_the_source_sent_recovery_data_when_asked_again(&mut self) {
        self.run(|lab| lab.session.request_aich(0, &MASTER)).await;
        let hash = self.hash;
        let link = self.link();
        link.until(protocol::EMULE, extended::AICHREQUEST).await;
        let mut out = PacketWriter::new();
        out.hash16(&hash).u16(0).hash20(&MASTER).bytes(&[0; 40]);
        link.send(&Packet::written(protocol::EMULE, extended::AICHANSWER, out)).await;
    }

    async fn given_the_source_ranked_us(&mut self, rank: u32) {
        let mut out = PacketWriter::new();
        out.u32(rank);
        self.link().send(&Packet::written(protocol::EDONKEY, client::QUEUERANK, out)).await;
        self.wait_for("the rank", move |lab| lab.session.queue_rank() == rank).await;
    }

    // The download pauses under it: given the slot, it leaves.
    async fn given_the_download_paused(&mut self) {
        let hash = self.hash;
        self.run(move |lab| lab.queue.pause(&hash)).await;
    }

    async fn given_the_source_gave_us_a_slot(&mut self) {
        let hash = self.hash;
        let link = self.link();
        link.until(protocol::EDONKEY, client::SETREQFILEID).await;
        link.send(&whole_file(hash)).await;
        link.until(protocol::EDONKEY, client::STARTUPLOADREQ).await;
        link.send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
        link.until(protocol::EDONKEY, client::REQUESTPARTS).await;
    }

    // Cancelled behind the session's back: the download is gone from the queue.
    async fn given_the_download_cancelled_behind_the_sessions_back(&mut self) {
        let hash = self.hash;
        assert!(self.run(move |lab| lab.queue.cancel(&hash)).await);
    }

    async fn when_the_source_gives_us_the_slot(&mut self) {
        self.link().send(&Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ)).await;
    }

    async fn when_the_session_connects_and_ends(&mut self, reason: &'static str) {
        self.run(move |lab| {
            lab.connect();
            lab.with(|session, ctx| session.close(reason, ctx));
        })
        .await;
    }

    async fn when_the_source_sends_data(&mut self, start: u64, end: u64) {
        let part = SendingPart { file: self.hash, start, end, data: vec![0; (end - start) as usize] };
        self.link().send(&part.encode(false).unwrap()).await;
    }

    fn when_a_source_gives_the_name(&mut self, name: &str) {
        self.shown_name = download_session::shown(name);
    }

    async fn then_the_event_is(&mut self, event: &str) {
        assert_eq!(self.next_told(Duration::from_secs(10)).await.as_deref(), Some(event));
    }

    async fn then_the_event_starts_with(&mut self, event: &str) {
        let told = self.next_told(Duration::from_secs(10)).await.unwrap_or_default();
        assert!(told.starts_with(event), "{told}");
    }

    async fn then_the_connection_closes(&mut self) {
        assert!(self.link().closes_within(WAIT).await);
    }

    async fn then_the_source_is_hung_up_on_without_a_hello(&mut self) {
        let mut silent = Link::over(self.source().accept_silently().await);
        assert!(silent.closes_within(WAIT).await, "closed without a hello");
    }

    async fn then_no_other_event_comes_within(&mut self, wait: Duration) {
        assert_eq!(self.next_told(wait).await, None);
    }

    // The failed connection reaches the ended session, which tells nothing more.
    async fn then_the_failure_comes_too_late(&mut self) {
        self.wait_for("the failure", |lab| lab.connects == 1).await;
        assert_eq!(self.next_told(Duration::from_millis(300)).await, None, "the failure comes too late");
    }

    fn then_the_name_shown_is(&mut self, name: &str) {
        assert_eq!(self.shown_name.as_deref(), Some(name));
    }

    fn then_no_name_is_shown(&mut self) {
        assert_eq!(self.shown_name, None);
    }

    fn source(&self) -> &ScriptedSource {
        self.source.as_ref().unwrap()
    }

    fn link(&mut self) -> &mut Link {
        self.link.as_mut().unwrap()
    }

    // A queue with a download of the file, and a session of it with the source at `address`, on a loop of their own.
    fn start(&mut self, file: [u8; 16], address: SocketAddr) {
        self.hash = file;
        let (done, disk) = mpsc::unbounded_channel();
        let incoming = self.dir.path().join("incoming");
        let mut queue = DownloadQueue::load(
            &self.dir.path().join("temp"),
            Box::new(move |_: &PartFile| incoming.clone()),
            Box::new(|| 0),
            done,
        )
        .unwrap();
        queue.add(&FileLink::of("session.bin", 5000, file).unwrap(), 0, DownloadPriority::Auto).unwrap();
        let (told_in, told) = mpsc::unbounded_channel();
        let (connected_in, connected) = mpsc::unbounded_channel();
        let (links_in, links) = mpsc::unbounded_channel();
        let (jobs_in, jobs) = mpsc::unbounded_channel();
        let ours = Ours { nick: "test", udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
        let lab = Lab {
            queue,
            session: DownloadSession::new(file, address, Instant::now()),
            ours: Hello::of([0xab; 16], 0, 4662, &ours, 0, 0),
            random: StdRng::seed_from_u64(1),
            told: told_in,
            connected: connected_in,
            links: links_in,
            connects: 0,
        };
        tokio::spawn(serve(lab, jobs, links, connected, disk));
        self.jobs = Some(jobs_in);
        self.told = Some(told);
    }

    async fn run<T: Send + 'static>(&self, work: impl FnOnce(&mut Lab) -> T + Send + 'static) -> T {
        let (answer, answered) = oneshot::channel();
        let job: Job = Box::new(move |lab| {
            let _ = answer.send(work(lab));
        });
        self.jobs.as_ref().unwrap().send(job).ok().unwrap();
        answered.await.unwrap()
    }

    async fn wait_for(&self, what: &str, condition: impl Fn(&mut Lab) -> bool + Send + Sync + Copy + 'static) {
        let deadline = Instant::now() + WAIT;
        while !self.run(condition).await {
            assert!(Instant::now() < deadline, "waited in vain for {what}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    async fn next_told(&mut self, wait: Duration) -> Option<String> {
        tokio::time::timeout(wait, self.told.as_mut().unwrap().recv()).await.ok().flatten()
    }
}

// The source has the whole file.
fn whole_file(file: [u8; 16]) -> Packet {
    FileStatus { file, part_count: 0, parts: Vec::new() }.encode().unwrap()
}

#[test]
fn should_cut_the_name_a_source_gives_at_its_longest() {
    let mut session = Session::new();

    session.when_a_source_gives_the_name(&"a".repeat(MAX_NAME + 40));

    session.then_the_name_shown_is(&"a".repeat(MAX_NAME));
}

#[test]
fn should_show_no_name_for_one_of_spaces_and_control_characters() {
    let mut session = Session::new();

    session.when_a_source_gives_the_name(" \u{0}\t ");

    session.then_no_name_is_shown();
}

#[tokio::test]
async fn should_miss_nothing_with_a_listener_that_hears_only_the_essentials() {
    let mut session = Session::new();
    session.given_a_source(1 << hello::MISC1_AICH_SHIFT, hello::MISC2_SOURCE_EXCHANGE2).await;
    session.given_a_session_for_the_file([0x6b; 16]);
    session.given_the_session_connected().await;
    session.given_the_source_described_the_file().await;
    session.given_the_source_took_back_the_slot_it_gave_us().await;
    session.given_the_source_had_no_recovery_data_at_first().await;
    session.given_the_source_sent_recovery_data_when_asked_again().await;
    session.given_the_source_ranked_us(4).await;
    session.given_the_download_paused().await;

    session.when_the_source_gives_us_the_slot().await;

    session.then_the_event_is("ended LATER the download is not active").await;
    session.then_the_connection_closes().await;
}

#[tokio::test]
async fn should_close_the_connection_of_a_session_ended_before_it_opens() {
    let mut session = Session::new();
    session.given_a_source(0, 0).await;
    session.given_a_session_for_the_file([0x6c; 16]);

    session.when_the_session_connects_and_ends("changed our mind").await;

    session.then_the_event_is("ended LATER changed our mind").await;
    session.then_the_source_is_hung_up_on_without_a_hello().await;
    session.then_no_other_event_comes_within(Duration::from_millis(300)).await;
}

#[tokio::test]
async fn should_change_nothing_when_a_connection_fails_after_the_session_ended() {
    let mut session = Session::new();
    session.given_a_closed_port().await;
    session.given_a_session_to_the_closed_port_for_the_file([0x6d; 16]);

    session.when_the_session_connects_and_ends("given up").await;

    session.then_the_event_is("ended LATER given up").await;
    session.then_the_failure_comes_too_late().await;
}

// A download gone from the queue under a session that receives its data is the fault the queue can have here.
#[tokio::test]
async fn should_end_the_session_to_be_asked_again_later_on_a_fault_in_the_queue() {
    let mut session = Session::new();
    session.given_a_source(0, 0).await;
    session.given_a_session_for_the_file([0x6e; 16]);
    session.given_the_session_connected().await;
    session.given_the_source_gave_us_a_slot().await;
    session.given_the_download_cancelled_behind_the_sessions_back().await;

    session.when_the_source_sends_data(0, 100).await;

    session.then_the_event_starts_with("ended LATER the download is gone").await;
    session.then_the_connection_closes().await;
}
