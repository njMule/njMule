// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! One uTP connection: a reliable byte stream. Packets are acknowledged cumulatively and selectively; a packet three
//! later ones were acknowledged past is sent again, and when the retransmission timer runs out all that were not
//! acknowledged go again. The window grows in slow start, then by LEDBAT towards a queuing delay of 100 ms, and halves
//! on a loss.

use std::collections::{HashMap, VecDeque};
use std::io;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};
use std::task::{Context, Poll, Waker};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::oneshot;
use tokio::time::Instant;

use super::utp_packet::{Type, UtpPacket};
use super::utp_socket::{Datagrams, Link};

/// Payload bytes per packet: within the smallest IPv6 MTU, after the UDP, framing, obfuscation and uTP headers.
pub const MSS: u64 = 1100;

const RECEIVE_BUFFER: u64 = 1_048_576;
const SEND_BUFFER: u64 = 1_048_576;
const TARGET_DELAY_MICROS: u64 = 100_000;
// LEDBAT's largest growth of the window in a round trip.
const MAX_INCREASE_PER_RTT: f64 = 3_000.0;
const MAX_WINDOW: u64 = 8_388_608;
const INITIAL_RTO: Duration = Duration::from_secs(1);
const MIN_RTO: Duration = Duration::from_millis(500);
const MAX_RTO: Duration = Duration::from_secs(30);
const MAX_TRANSMISSIONS: u32 = 8;
const SYN_TRANSMISSIONS: u32 = 4;
const DUPLICATE_ACKS: u32 = 3;
const REORDER: u16 = 1_024;
const MAX_SACK_BYTES: usize = 32;
const KEEPALIVE: Duration = Duration::from_secs(29);
const SILENCE: Duration = Duration::from_secs(70);
const LINGER: Duration = Duration::from_secs(10);
// The base delay is the lowest sample of the last two minutes, kept by the minute.
const DELAY_BUCKET: Duration = Duration::from_secs(60);

/// A connection as a byte stream: reads end once the other side's data ended, and dropping it sends what was written,
/// then the end.
pub struct UtpConnection {
    shared: Arc<Shared>,
}

/// The connection itself, kept by its socket until both ends finished or it failed.
pub(crate) struct Shared {
    link: Weak<Link>,
    datagrams: Arc<Datagrams>,
    remote: SocketAddr,
    receive_id: u16,
    send_id: u16,
    state: Mutex<State>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    SynSent,
    Connected,
    Closed,
}

// A packet sent and not acknowledged yet.
struct Out {
    kind: Type,
    seq: u16,
    payload: Vec<u8>,
    sent_at: Instant,
    transmissions: u32,
    acknowledged: bool,
    // Taken for lost at a timeout and waiting for room in the window; not counted in flight meanwhile.
    resend: bool,
    // Acknowledged packets past this one, in selective acknowledgements.
    passed: u32,
}

struct State {
    phase: Phase,
    answered: Option<oneshot::Sender<io::Result<()>>>,
    in_flight: VecDeque<Out>,
    to_send: VecDeque<u8>,
    reordered: HashMap<u16, Vec<u8>>,
    to_read: VecDeque<u8>,
    seq: u16,
    ack: u16,
    first_seq: u16,
    in_flight_bytes: u64,
    reordered_bytes: u64,
    fin_seq: Option<u16>,
    eof: bool,
    writing_closed: bool,
    fin_sent: bool,
    fin_acknowledged: bool,
    reading_closed: bool,
    failure: Option<String>,
    max_window: u64,
    slow_start_limit: u64,
    slow_start: bool,
    peer_window: u64,
    rtt: Option<Duration>,
    rtt_variance: Duration,
    rto: Duration,
    last_loss: Option<Instant>,
    last_ack: u16,
    duplicate_acks: u32,
    reply_delay_micros: u32,
    last_sent: Instant,
    last_heard: Instant,
    closed_at: Instant,
    delay_minute_start: Instant,
    delay_last_minute: u64,
    delay_this_minute: u64,
    our_delay_micros: u64,
    advertised: u64,
    reader: Option<Waker>,
    writer: Option<Waker>,
}

impl UtpConnection {
    pub(crate) fn new(shared: Arc<Shared>) -> Self {
        Self { shared }
    }

    pub fn remote_address(&self) -> SocketAddr {
        self.shared.remote
    }

    /// Ends the connection at once, telling the other side.
    pub fn abort(&self) {
        self.shared.abort("aborted");
    }
}

impl Drop for UtpConnection {
    fn drop(&mut self) {
        self.shared.close();
    }
}

impl std::fmt::Debug for UtpConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "uTP {} {}", self.shared.remote, self.shared.receive_id)
    }
}

impl AsyncRead for UtpConnection {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        self.shared.poll_read(cx, buf)
    }
}

impl AsyncWrite for UtpConnection {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        self.shared.poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let mut s = self.shared.state();
        self.shared.pump(&mut s);
        Poll::Ready(Ok(()))
    }

    /// Sends what was written, then the end; reads go on.
    fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let mut s = self.shared.state();
        s.writing_closed = true;
        self.shared.pump(&mut s);
        Poll::Ready(Ok(()))
    }
}

impl Shared {
    pub(crate) fn outgoing(
        link: &Arc<Link>,
        remote: SocketAddr,
        receive_id: u16,
        answered: oneshot::Sender<io::Result<()>>,
    ) -> Self {
        let mut state = State::new(Phase::SynSent);
        state.seq = 1;
        state.answered = Some(answered);
        Self::new(link, remote, receive_id, receive_id.wrapping_add(1), state)
    }

    pub(crate) fn incoming(link: &Arc<Link>, remote: SocketAddr, syn: &UtpPacket, first_seq: u16) -> Self {
        let mut state = State::new(Phase::Connected);
        state.ack = syn.seq;
        state.seq = first_seq;
        state.first_seq = first_seq;
        state.last_ack = first_seq.wrapping_sub(1);
        state.peer_window = u64::from(syn.window);
        state.reply_delay_micros = link.datagrams().micros().wrapping_sub(syn.timestamp);
        Self::new(link, remote, syn.connection_id.wrapping_add(1), syn.connection_id, state)
    }

    fn new(link: &Arc<Link>, remote: SocketAddr, receive_id: u16, send_id: u16, state: State) -> Self {
        Self {
            link: Arc::downgrade(link),
            datagrams: link.datagrams(),
            remote,
            receive_id,
            send_id,
            state: Mutex::new(state),
        }
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    // Says the first word: the SYN, or the answer to it.
    pub(crate) fn start(&self) {
        let mut s = self.state();
        if s.phase == Phase::SynSent {
            let syn = Out::new(Type::Syn, s.seq, Vec::new());
            s.seq = next(s.seq);
            s.in_flight.push_back(syn);
            self.transmit_last(&mut s);
        } else {
            let first = s.first_seq;
            self.send_state(&mut s, first);
        }
    }

    // Sends what was written, then the end; reads fail from now on.
    fn close(&self) {
        let mut s = self.state();
        if s.reading_closed {
            return;
        }
        s.reading_closed = true;
        s.writing_closed = true;
        s.closed_at = Instant::now();
        wake(&mut s.reader);
        wake(&mut s.writer);
        if s.phase == Phase::SynSent {
            self.end(&mut s, "closed before it connected", true);
            return;
        }
        self.pump(&mut s);
    }

    pub(crate) fn abort(&self, reason: &str) {
        let mut s = self.state();
        self.end(&mut s, reason, true);
    }

    pub(crate) fn received(&self, packet: UtpPacket) {
        let mut s = self.state();
        if s.phase == Phase::Closed {
            return;
        }
        let now = Instant::now();
        s.last_heard = now;
        if packet.kind == Type::Reset {
            self.end(&mut s, &format!("reset by {}", self.remote), false);
            return;
        }
        if packet.kind == Type::Syn {
            // Our answer was lost: the SYN comes again.
            let first = s.first_seq;
            self.send_state(&mut s, first);
            return;
        }
        s.reply_delay_micros = self.datagrams.micros().wrapping_sub(packet.timestamp);
        s.peer_window = u64::from(packet.window);
        if s.phase == Phase::SynSent {
            let Some(syn) = s.in_flight.front().map(|syn| syn.seq) else {
                return;
            };
            if packet.kind != Type::State || packet.ack != syn {
                return;
            }
            // The answer to our SYN names the first sequence number to come.
            s.ack = packet.seq.wrapping_sub(1);
            s.last_ack = syn;
            s.phase = Phase::Connected;
            if let Some(answered) = s.answered.take() {
                let _ = answered.send(Ok(()));
            }
        }
        self.acknowledged(&mut s, &packet, now);
        if matches!(packet.kind, Type::Data | Type::Fin) {
            if packet.kind == Type::Fin {
                s.fin_seq = Some(packet.seq);
            }
            arrived(&mut s, packet.seq, packet.payload);
            let seq = s.seq;
            self.send_state(&mut s, seq);
        }
        self.pump(&mut s);
        self.finish_if_done(&mut s, now);
    }

    // What the other side acknowledged, cumulatively and selectively; a loss halves the window.
    fn acknowledged(&self, s: &mut State, packet: &UtpPacket, now: Instant) {
        let up_to = packet.ack;
        let mut bytes: u64 = 0;
        let mut newest_sample = None;
        let advanced = after(up_to, s.last_ack);
        while let Some(front) = s.in_flight.front()
            && !after(front.seq, up_to)
            && let Some(out) = s.in_flight.pop_front()
        {
            if !out.acknowledged {
                bytes = bytes.saturating_add(out.len());
                if !out.resend {
                    s.in_flight_bytes = s.in_flight_bytes.saturating_sub(out.len());
                }
                if out.transmissions == 1 {
                    newest_sample = Some(now.saturating_duration_since(out.sent_at));
                }
            }
            if out.kind == Type::Fin {
                s.fin_acknowledged = true;
            }
        }
        if let Some(mask) = &packet.selective_ack {
            bytes = bytes.saturating_add(self.selectively_acknowledged(s, up_to, mask, now));
        }
        if let Some(sample) = newest_sample {
            s.round_trip(sample);
        }
        if advanced {
            s.last_ack = up_to;
            s.duplicate_acks = 0;
        } else if bytes == 0 && packet.kind == Type::State && up_to == s.last_ack && !s.in_flight.is_empty() {
            s.duplicate_acks = s.duplicate_acks.saturating_add(1);
            if s.duplicate_acks == DUPLICATE_ACKS {
                self.lost(s, 0, now);
            }
        }
        if bytes > 0 {
            if packet.timestamp_difference != 0 {
                s.delay_sample(u64::from(packet.timestamp_difference), now);
            }
            s.grow(bytes);
            wake(&mut s.writer);
        }
    }

    // Bit i of the mask is the packet at up_to + 2 + i; three acknowledged past one we wait for mean it was lost.
    fn selectively_acknowledged(&self, s: &mut State, up_to: u16, mask: &[u8], now: Instant) -> u64 {
        let bits = mask.len().saturating_mul(8);
        let newest = (0..bits).rev().find(|&i| bit(mask, i));
        let mut bytes: u64 = 0;
        for out in &mut s.in_flight {
            if let Some(offset) = sack_offset(out.seq, up_to)
                && offset < bits
                && bit(mask, offset)
                && !out.acknowledged
            {
                out.acknowledged = true;
                bytes = bytes.saturating_add(out.len());
                if out.resend {
                    out.resend = false;
                } else {
                    s.in_flight_bytes = s.in_flight_bytes.saturating_sub(out.len());
                }
            }
        }
        for index in 0..s.in_flight.len() {
            let Some(out) = s.in_flight.get(index) else {
                break;
            };
            if out.acknowledged {
                continue;
            }
            let from = sack_offset(out.seq, up_to).map_or(0, |offset| offset.saturating_add(1));
            let passed = newest.map_or(0, |newest| (from..=newest).filter(|&i| bit(mask, i)).count());
            let passed = u32::try_from(passed).unwrap_or(u32::MAX);
            let before = out.passed;
            if passed >= DUPLICATE_ACKS && before < DUPLICATE_ACKS {
                self.lost(s, index, now);
            }
            if let Some(out) = s.in_flight.get_mut(index) {
                out.passed = passed;
            }
        }
        bytes
    }

    // At most one loss a round trip shrinks the window.
    fn lost(&self, s: &mut State, index: usize, now: Instant) {
        let round = s.rtt.unwrap_or_default().max(MIN_RTO);
        if s.last_loss.is_none_or(|last| now.saturating_duration_since(last) > round) {
            s.last_loss = Some(now);
            s.max_window = (s.max_window / 2).max(MSS.saturating_mul(2));
            s.slow_start_limit = s.max_window;
            s.slow_start = false;
        }
        self.transmit(s, index);
    }

    // Sends what the windows allow; with nothing in flight one packet goes whatever they say, as a probe.
    fn pump(&self, s: &mut State) {
        if s.phase != Phase::Connected {
            return;
        }
        let window = s.max_window.min(s.peer_window);
        // What a timeout took for lost goes first.
        for index in 0..s.in_flight.len() {
            let Some(out) = s.in_flight.get(index) else {
                break;
            };
            if !out.resend {
                continue;
            }
            if s.in_flight_bytes > 0 && s.in_flight_bytes.saturating_add(out.len()) > window {
                return;
            }
            self.transmit(s, index);
        }
        let mut sent = false;
        while !s.to_send.is_empty() && s.in_flight.len() < usize::from(REORDER) {
            let size = s.to_send.len().min(usize::try_from(MSS).unwrap_or(usize::MAX));
            let length = u64::try_from(size).unwrap_or(u64::MAX);
            if s.in_flight_bytes > 0 && s.in_flight_bytes.saturating_add(length) > window {
                break;
            }
            let payload: Vec<u8> = s.to_send.drain(..size).collect();
            let out = Out::new(Type::Data, s.seq, payload);
            s.seq = next(s.seq);
            s.in_flight_bytes = s.in_flight_bytes.saturating_add(length);
            s.in_flight.push_back(out);
            self.transmit_last(s);
            sent = true;
        }
        if sent {
            wake(&mut s.writer);
        }
        if s.writing_closed && !s.fin_sent && s.to_send.is_empty() && s.failure.is_none() {
            let fin = Out::new(Type::Fin, s.seq, Vec::new());
            s.seq = next(s.seq);
            s.in_flight.push_back(fin);
            s.fin_sent = true;
            self.transmit_last(s);
        }
    }

    fn transmit_last(&self, s: &mut State) {
        let last = s.in_flight.len().saturating_sub(1);
        self.transmit(s, last);
    }

    fn transmit(&self, s: &mut State, index: usize) {
        let Some(out) = s.in_flight.get_mut(index) else {
            return;
        };
        out.sent_at = Instant::now();
        out.transmissions = out.transmissions.saturating_add(1);
        let counted = !out.resend;
        out.resend = false;
        let (kind, seq, payload) = (out.kind, out.seq, out.payload.clone());
        if !counted {
            s.in_flight_bytes = s.in_flight_bytes.saturating_add(u64::try_from(payload.len()).unwrap_or(u64::MAX));
        }
        let id = if kind == Type::Syn { self.receive_id } else { self.send_id };
        self.send(s, kind, id, seq, payload);
    }

    fn send_state(&self, s: &mut State, seq: u16) {
        self.send(s, Type::State, self.send_id, seq, Vec::new());
    }

    fn send(&self, s: &mut State, kind: Type, connection_id: u16, seq: u16, payload: Vec<u8>) {
        let window = s.receive_window();
        s.advertised = window;
        s.last_sent = Instant::now();
        let packet = UtpPacket {
            kind,
            connection_id,
            timestamp: self.datagrams.micros(),
            timestamp_difference: s.reply_delay_micros,
            window: u32::try_from(window).unwrap_or(u32::MAX),
            seq,
            ack: s.ack,
            selective_ack: s.selective_ack(),
            payload,
        };
        self.datagrams.send(self.remote, &packet);
    }

    pub(crate) fn tick(&self) {
        let mut s = self.state();
        if s.phase == Phase::Closed {
            return;
        }
        let now = Instant::now();
        if let Some(index) = s.in_flight.iter().position(|out| !out.acknowledged && !out.resend)
            && let Some(oldest) = s.in_flight.get(index)
            && now.saturating_duration_since(oldest.sent_at) > s.rto
        {
            let limit = if oldest.kind == Type::Syn { SYN_TRANSMISSIONS } else { MAX_TRANSMISSIONS };
            if oldest.transmissions >= limit {
                self.end(&mut s, &format!("no answer from {}", self.remote), true);
                return;
            }
            // A timeout: the window starts again from one packet, and the timer doubles. Every packet not acknowledged is
            // taken for lost and goes again from the oldest as the window allows, not one a timeout.
            s.slow_start_limit = (s.max_window / 2).max(MSS.saturating_mul(2));
            s.max_window = MSS;
            s.slow_start = true;
            s.rto = s.rto.saturating_mul(2).min(MAX_RTO);
            let mut lost: u64 = 0;
            for out in s.in_flight.iter_mut().filter(|out| !out.acknowledged && !out.resend) {
                out.resend = true;
                lost = lost.saturating_add(out.len());
            }
            s.in_flight_bytes = s.in_flight_bytes.saturating_sub(lost);
            self.transmit(&mut s, index);
        }
        if s.phase == Phase::Connected && now.saturating_duration_since(s.last_sent) > KEEPALIVE {
            let seq = s.seq;
            self.send_state(&mut s, seq);
        }
        if now.saturating_duration_since(s.last_heard) > SILENCE {
            self.end(&mut s, &format!("silent {}", self.remote), true);
            return;
        }
        self.pump(&mut s);
        self.finish_if_done(&mut s, now);
    }

    // Closed here, and the end acknowledged and the other side's received, or lingered long enough: forgotten.
    fn finish_if_done(&self, s: &mut State, now: Instant) {
        if !s.reading_closed {
            return;
        }
        let done = s.fin_acknowledged && s.eof;
        if done || now.saturating_duration_since(s.closed_at) > LINGER {
            s.phase = Phase::Closed;
            self.forget();
        }
    }

    fn end(&self, s: &mut State, reason: &str, tell: bool) {
        if s.phase == Phase::Closed {
            return;
        }
        if tell && s.phase == Phase::Connected {
            let seq = s.seq;
            self.send(s, Type::Reset, self.send_id, seq, Vec::new());
        }
        s.phase = Phase::Closed;
        if s.failure.is_none() {
            s.failure = Some(reason.to_string());
        }
        if let Some(answered) = s.answered.take() {
            let _ = answered.send(Err(io::Error::other(format!("uTP: {reason}"))));
        }
        wake(&mut s.reader);
        wake(&mut s.writer);
        self.forget();
    }

    fn forget(&self) {
        if let Some(link) = self.link.upgrade() {
            link.forget(self.remote, self.receive_id, self);
        }
    }

    fn poll_read(&self, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let mut s = self.state();
        if buf.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        if s.to_read.is_empty() {
            if s.eof {
                return Poll::Ready(Ok(()));
            }
            if s.reading_closed {
                return Poll::Ready(Err(io::Error::new(io::ErrorKind::NotConnected, "closed")));
            }
            if let Some(failure) = &s.failure {
                return Poll::Ready(Err(io::Error::other(format!("uTP: {failure}"))));
            }
            s.reader = Some(cx.waker().clone());
            return Poll::Pending;
        }
        let n = buf.remaining().min(s.to_read.len());
        let (head, tail) = s.to_read.as_slices();
        let from_head = n.min(head.len());
        if let (Some(head), Some(tail)) = (head.get(..from_head), tail.get(..n.saturating_sub(from_head))) {
            buf.put_slice(head);
            buf.put_slice(tail);
        }
        s.to_read.drain(..n);
        // A window that was nearly shut opens again: the other side hears it.
        if s.advertised < MSS.saturating_mul(2)
            && s.receive_window() >= MSS.saturating_mul(4)
            && s.phase == Phase::Connected
        {
            let seq = s.seq;
            self.send_state(&mut s, seq);
        }
        Poll::Ready(Ok(()))
    }

    fn poll_write(&self, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let mut s = self.state();
        if let Some(failure) = &s.failure {
            return Poll::Ready(Err(io::Error::other(format!("uTP: {failure}"))));
        }
        if s.writing_closed {
            return Poll::Ready(Err(io::Error::new(io::ErrorKind::BrokenPipe, "closed")));
        }
        if buf.is_empty() {
            return Poll::Ready(Ok(0));
        }
        let room = usize::try_from(SEND_BUFFER).unwrap_or(usize::MAX).saturating_sub(s.to_send.len());
        if room == 0 {
            s.writer = Some(cx.waker().clone());
            return Poll::Pending;
        }
        let n = room.min(buf.len());
        if let Some(taken) = buf.get(..n) {
            s.to_send.extend(taken);
        }
        self.pump(&mut s);
        Poll::Ready(Ok(n))
    }
}

impl Out {
    fn new(kind: Type, seq: u16, payload: Vec<u8>) -> Self {
        Self {
            kind,
            seq,
            payload,
            sent_at: Instant::now(),
            transmissions: 0,
            acknowledged: false,
            resend: false,
            passed: 0,
        }
    }

    fn len(&self) -> u64 {
        u64::try_from(self.payload.len()).unwrap_or(u64::MAX)
    }
}

impl State {
    fn new(phase: Phase) -> Self {
        let now = Instant::now();
        Self {
            phase,
            answered: None,
            in_flight: VecDeque::new(),
            to_send: VecDeque::new(),
            reordered: HashMap::new(),
            to_read: VecDeque::new(),
            seq: 0,
            ack: 0,
            first_seq: 0,
            in_flight_bytes: 0,
            reordered_bytes: 0,
            fin_seq: None,
            eof: false,
            writing_closed: false,
            fin_sent: false,
            fin_acknowledged: false,
            reading_closed: false,
            failure: None,
            max_window: MSS.saturating_mul(2),
            slow_start_limit: MAX_WINDOW,
            slow_start: true,
            peer_window: MSS,
            rtt: None,
            rtt_variance: Duration::ZERO,
            rto: INITIAL_RTO,
            last_loss: None,
            last_ack: 0,
            duplicate_acks: 0,
            reply_delay_micros: 0,
            last_sent: now,
            last_heard: now,
            closed_at: now,
            delay_minute_start: now,
            delay_last_minute: u64::MAX,
            delay_this_minute: u64::MAX,
            our_delay_micros: 0,
            advertised: RECEIVE_BUFFER,
            reader: None,
            writer: None,
        }
    }

    fn to_read_bytes(&self) -> u64 {
        u64::try_from(self.to_read.len()).unwrap_or(u64::MAX)
    }

    fn receive_window(&self) -> u64 {
        RECEIVE_BUFFER.saturating_sub(self.to_read_bytes().saturating_add(self.reordered_bytes))
    }

    fn selective_ack(&self) -> Option<Vec<u8>> {
        let highest = self.reordered.keys().filter_map(|&at| sack_offset(at, self.ack)).max()?;
        let length = (highest / 32).saturating_add(1).saturating_mul(4).min(MAX_SACK_BYTES);
        let mut mask = vec![0u8; length];
        for &at in self.reordered.keys() {
            if let Some(bit) = sack_offset(at, self.ack)
                && let Some(byte) = mask.get_mut(bit / 8)
            {
                *byte |= 1u8.wrapping_shl(u32::try_from(bit % 8).unwrap_or(0));
            }
        }
        Some(mask)
    }

    // RFC 6298's estimator.
    fn round_trip(&mut self, sample: Duration) {
        match self.rtt {
            None => {
                self.rtt = Some(sample);
                self.rtt_variance = part(sample, 2);
            }
            Some(rtt) => {
                let deviation = rtt.abs_diff(sample);
                self.rtt_variance = if deviation >= self.rtt_variance {
                    self.rtt_variance.saturating_add(part(deviation.saturating_sub(self.rtt_variance), 4))
                } else {
                    self.rtt_variance.saturating_sub(part(self.rtt_variance.saturating_sub(deviation), 4))
                };
                self.rtt = Some(if sample >= rtt {
                    rtt.saturating_add(part(sample.saturating_sub(rtt), 8))
                } else {
                    rtt.saturating_sub(part(rtt.saturating_sub(sample), 8))
                });
            }
        }
        let rtt = self.rtt.unwrap_or_default();
        self.rto = rtt.saturating_add(self.rtt_variance.saturating_mul(4)).clamp(MIN_RTO, MAX_RTO);
    }

    fn delay_sample(&mut self, micros: u64, now: Instant) {
        if now.saturating_duration_since(self.delay_minute_start) > DELAY_BUCKET {
            self.delay_last_minute = self.delay_this_minute;
            self.delay_this_minute = u64::MAX;
            self.delay_minute_start = now;
        }
        self.delay_this_minute = self.delay_this_minute.min(micros);
        let base = self.delay_last_minute.min(self.delay_this_minute);
        self.our_delay_micros = micros.saturating_sub(base);
    }

    // Slow start until the delay nears the target or a loss; then LEDBAT, at most a few kilobytes more a round trip.
    fn grow(&mut self, bytes: u64) {
        if self.slow_start {
            if self.our_delay_micros > TARGET_DELAY_MICROS / 10 * 9 || self.max_window >= self.slow_start_limit {
                self.slow_start = false;
            } else {
                self.max_window = self.max_window.saturating_add(bytes).min(MAX_WINDOW);
                return;
            }
        }
        let off_target = (TARGET_DELAY_MICROS as f64 - self.our_delay_micros as f64) / TARGET_DELAY_MICROS as f64;
        let window_factor = bytes.min(self.max_window) as f64 / bytes.max(self.max_window) as f64;
        // Saturating casts: a change past the bounds is clamped below anyway.
        let change = (MAX_INCREASE_PER_RTT * window_factor * off_target).round() as i64;
        let window = i64::try_from(self.max_window).unwrap_or(i64::MAX).saturating_add(change);
        self.max_window = u64::try_from(window).unwrap_or(0).clamp(MSS, MAX_WINDOW);
    }
}

fn arrived(s: &mut State, at: u16, payload: Vec<u8>) {
    let distance = at.wrapping_sub(s.ack);
    if distance == 0 || distance >= 0x8000 {
        return;
    }
    if distance == 1 {
        if !take(s, payload) {
            return;
        }
        s.ack = at;
        while let Some(waiting) = s.reordered.remove(&next(s.ack)) {
            s.reordered_bytes = s.reordered_bytes.saturating_sub(u64::try_from(waiting.len()).unwrap_or(0));
            take(s, waiting);
            s.ack = next(s.ack);
        }
        if s.fin_seq == Some(s.ack) {
            s.eof = true;
        }
        wake(&mut s.reader);
    } else {
        let length = u64::try_from(payload.len()).unwrap_or(u64::MAX);
        let held = s.to_read_bytes().saturating_add(s.reordered_bytes).saturating_add(length);
        if distance <= REORDER && held <= RECEIVE_BUFFER && !s.reordered.contains_key(&at) {
            s.reordered_bytes = s.reordered_bytes.saturating_add(length);
            s.reordered.insert(at, payload);
        }
    }
}

// Data with no room is dropped: the other side sends it again.
fn take(s: &mut State, payload: Vec<u8>) -> bool {
    let length = u64::try_from(payload.len()).unwrap_or(u64::MAX);
    if s.to_read_bytes().saturating_add(s.reordered_bytes).saturating_add(length) > RECEIVE_BUFFER {
        return false;
    }
    s.to_read.extend(payload);
    true
}

fn part(whole: Duration, parts: u32) -> Duration {
    whole.checked_div(parts).unwrap_or_default()
}

fn wake(waker: &mut Option<Waker>) {
    if let Some(waker) = waker.take() {
        waker.wake();
    }
}

fn bit(mask: &[u8], i: usize) -> bool {
    mask.get(i / 8).is_some_and(|byte| byte.wrapping_shr(u32::try_from(i % 8).unwrap_or(0)) & 1 != 0)
}

// The bit of `at` in a selective ack past `ack`: `ack + 2` is bit 0; none for those before.
fn sack_offset(at: u16, ack: u16) -> Option<usize> {
    usize::from(at.wrapping_sub(ack)).checked_sub(2)
}

fn next(seq: u16) -> u16 {
    seq.wrapping_add(1)
}

// Whether a lies past b, modulo 2^16.
fn after(a: u16, b: u16) -> bool {
    let d = a.wrapping_sub(b);
    d != 0 && d < 0x8000
}
