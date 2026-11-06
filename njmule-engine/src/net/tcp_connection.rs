// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! eD2K frames over an async stream: [`tcp_framing`]'s layout, each frame's size decided from its header before
//! anything is allocated.

use std::collections::VecDeque;
use std::io::{self, ErrorKind};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{Notify, watch};
use tokio::task::AbortHandle;

use crate::net::traffic;

use crate::crypto::rc4::Rc4;
use crate::protocol::io::DecodeError;
use crate::protocol::packet::frame_limit::FrameLimit;
use crate::protocol::packet::{Packet, tcp_framing};

/// Reads one frame: `None` at a clean end of stream before its first byte. A truncated frame, an unknown protocol byte
/// or a payload beyond `limit` is an [`ErrorKind::InvalidData`] error carrying a [`DecodeError`].
pub async fn read_packet(input: &mut (impl AsyncRead + Unpin), limit: &impl FrameLimit) -> io::Result<Option<Packet>> {
    let mut header = [0u8; tcp_framing::HEADER_SIZE];
    let [first, rest @ ..] = &mut header;
    if input.read(std::slice::from_mut(first)).await? == 0 {
        return Ok(None);
    }
    read_fully(input, rest).await?;
    let [protocol, l0, l1, l2, l3, opcode] = header;
    let length =
        tcp_framing::check_header(protocol, u32::from_le_bytes([l0, l1, l2, l3]), opcode, limit).map_err(invalid)?;
    let mut payload = vec![0u8; length];
    read_fully(input, &mut payload).await?;
    Ok(Some(Packet::of(protocol, opcode, payload)))
}

pub async fn write_packet(output: &mut (impl AsyncWrite + Unpin), packet: &Packet) -> io::Result<()> {
    let frame = tcp_framing::encode(packet).map_err(io::Error::other)?;
    output.write_all(&frame).await?;
    output.flush().await
}

async fn read_fully(input: &mut (impl AsyncRead + Unpin), buffer: &mut [u8]) -> io::Result<()> {
    input.read_exact(buffer).await.map(|_| ()).map_err(|e| {
        if e.kind() == ErrorKind::UnexpectedEof {
            invalid(DecodeError::new("connection closed inside a frame"))
        } else {
            e
        }
    })
}

fn invalid(error: DecodeError) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, error)
}

/// The frames a connection receives, decrypted with its key stream when it is obfuscated.
pub struct FrameReader<R> {
    input: R,
    cipher: Option<Rc4>,
}

impl<R: AsyncRead + Unpin> FrameReader<R> {
    pub fn new(input: R, cipher: Option<Rc4>) -> Self {
        Self { input, cipher }
    }

    /// As [`read_packet`], on the decrypted stream.
    pub async fn read_packet(&mut self, limit: &impl FrameLimit) -> io::Result<Option<Packet>> {
        let mut header = [0u8; tcp_framing::HEADER_SIZE];
        let [first, rest @ ..] = &mut header;
        if self.input.read(std::slice::from_mut(first)).await? == 0 {
            return Ok(None);
        }
        read_fully(&mut self.input, rest).await?;
        self.decrypt(&mut header);
        let [protocol, l0, l1, l2, l3, opcode] = header;
        let length = tcp_framing::check_header(protocol, u32::from_le_bytes([l0, l1, l2, l3]), opcode, limit)
            .map_err(invalid)?;
        let mut payload = vec![0u8; length];
        read_fully(&mut self.input, &mut payload).await?;
        self.decrypt(&mut payload);
        Ok(Some(Packet::of(protocol, opcode, payload)))
    }

    fn decrypt(&mut self, data: &mut [u8]) {
        if let Some(cipher) = &mut self.cipher {
            cipher.apply(data);
        }
    }
}

/// The frames a connection sends, encrypted with its key stream when it is obfuscated.
pub struct FrameWriter<W> {
    output: W,
    cipher: Option<Rc4>,
}

impl<W: AsyncWrite + Unpin> FrameWriter<W> {
    pub fn new(output: W, cipher: Option<Rc4>) -> Self {
        Self { output, cipher }
    }

    pub async fn write_packet(&mut self, packet: &Packet) -> io::Result<()> {
        let mut frame = tcp_framing::encode(packet).map_err(io::Error::other)?;
        if let Some(cipher) = &mut self.cipher {
            cipher.apply(&mut frame);
        }
        self.output.write_all(&frame).await?;
        self.output.flush().await
    }

    /// Writes what was handed over to the stream so far, as a handshake's last bytes.
    pub async fn flush(&mut self) -> io::Result<()> {
        self.output.flush().await
    }

    /// Ends the stream: the other side reads its end.
    pub async fn shutdown(&mut self) -> io::Result<()> {
        self.output.shutdown().await
    }
}

/// The frame size limits of a connection, by protocol and opcode.
pub type Limit = fn(u8, u8) -> Option<usize>;

/// Turns raw frames (for example zlib-packed ones) into the packets the handler sees.
pub type Unpacker = Box<dyn Fn(Packet) -> Result<Packet, DecodeError> + Send + Sync>;

/// What a connection tells, on its reading task.
pub trait Handler: Send + Sync + 'static {
    /// A packet came; an error closes the connection, as a handler that cannot go on.
    fn on_packet(&self, connection: &TcpConnection, packet: Packet) -> Result<(), String>;

    /// Told once; the cause is none after [`TcpConnection::close`] or a clean end of stream.
    fn on_closed(&self, connection: &TcpConnection, cause: Option<io::Error>);
}

const OUTBOX_CAPACITY: usize = 1_024;
// Protocol byte, length and opcode before each payload.
const FRAME_HEADER: u64 = 6;
// What a peer may leave unread, whatever the number of frames.
const OUTBOX_BYTES: u64 = 16 << 20;
/// A clean close sends what is queued, for this long at most.
pub const FLUSH_WAIT: Duration = Duration::from_secs(5);

/// A framed eD2K connection over a stream, a TCP socket's or another, served by a reading and a writing task. Sending
/// never waits: frames wait in a bounded queue, and a peer that does not drain it is disconnected. File data waits
/// behind every other packet. Clones are the same connection.
#[derive(Clone)]
pub struct TcpConnection {
    inner: Arc<Inner>,
}

type Start = Box<dyn FnOnce(TcpConnection, Arc<dyn Handler>, Unpacker) -> Vec<AbortHandle> + Send>;

struct Inner {
    remote: SocketAddr,
    local: Option<IpAddr>,
    obfuscated: bool,
    outbox: Mutex<Outbox>,
    wake: Notify,
    closing: watch::Sender<bool>,
    limit: Mutex<Limit>,
    // In milliseconds, 0 waiting forever.
    read_timeout: AtomicU64,
    start: Mutex<Option<Start>>,
    started: AtomicBool,
    handler: Mutex<Option<Arc<dyn Handler>>>,
    tasks: Mutex<Vec<AbortHandle>>,
    // How it ended, until told: the failure, or none after a clean close.
    cause: Mutex<Option<io::Error>>,
    reported: AtomicBool,
}

#[derive(Default)]
struct Outbox {
    control: VecDeque<(Packet, u64)>,
    data: VecDeque<(Packet, u64)>,
    frames: usize,
    bytes: u64,
    queued_data: u64,
    closed: bool,
}

impl TcpConnection {
    /// A connection over `reader` and `writer`, not started yet; frames read go by `limit` until another is set.
    pub fn new<R, W>(
        reader: FrameReader<R>,
        writer: FrameWriter<W>,
        remote: SocketAddr,
        local: Option<IpAddr>,
        obfuscated: bool,
        limit: Limit,
    ) -> Self
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        let start: Start = Box::new(move |connection, handler, unpacker| {
            let writing = tokio::spawn(write(connection.clone(), writer));
            let reading = tokio::spawn(read(connection, reader, handler, unpacker));
            vec![writing.abort_handle(), reading.abort_handle()]
        });
        Self {
            inner: Arc::new(Inner {
                remote,
                local,
                obfuscated,
                outbox: Mutex::default(),
                wake: Notify::new(),
                closing: watch::Sender::new(false),
                limit: Mutex::new(limit),
                read_timeout: AtomicU64::new(0),
                start: Mutex::new(Some(start)),
                started: AtomicBool::new(false),
                handler: Mutex::new(None),
                tasks: Mutex::new(Vec::new()),
                cause: Mutex::new(None),
                reported: AtomicBool::new(false),
            }),
        }
    }

    /// A plain connection over any stream, not started yet.
    pub fn over<R, W>(input: R, output: W, remote: SocketAddr, local: Option<IpAddr>, limit: Limit) -> Self
    where
        R: AsyncRead + Unpin + Send + 'static,
        W: AsyncWrite + Unpin + Send + 'static,
    {
        Self::new(FrameReader::new(input, None), FrameWriter::new(output, None), remote, local, false, limit)
    }

    /// Starts the reading and writing tasks; packets go to `handler`, told at once if the connection closed already. A
    /// second start does nothing.
    pub fn start(&self, handler: impl Handler, unpacker: Unpacker) {
        if self.inner.started.swap(true, Ordering::SeqCst) {
            return;
        }
        let handler: Arc<dyn Handler> = Arc::new(handler);
        *lock(&self.inner.handler) = Some(Arc::clone(&handler));
        let start = lock(&self.inner.start).take();
        match start {
            Some(start) if self.is_open() => {
                let tasks = start(self.clone(), handler, unpacker);
                lock(&self.inner.tasks).extend(tasks);
            }
            _ => self.report(),
        }
    }

    /// Queues a packet; false if the connection is closed. A full queue, in frames or in bytes, closes the connection:
    /// the peer is not reading.
    pub fn send(&self, packet: Packet) -> bool {
        self.enqueue(packet, false)
    }

    /// As [`Self::send`], for file data: it goes out after the other packets waiting.
    pub fn send_data(&self, packet: Packet) -> bool {
        self.enqueue(packet, true)
    }

    /// Bytes of file data waiting to be written.
    pub fn queued_data(&self) -> u64 {
        lock(&self.inner.outbox).queued_data
    }

    fn enqueue(&self, packet: Packet, file_data: bool) -> bool {
        let length = frame_length(&packet);
        let full = {
            let mut outbox = lock(&self.inner.outbox);
            if outbox.closed {
                return false;
            }
            outbox.bytes = outbox.bytes.saturating_add(length);
            outbox.frames = outbox.frames.saturating_add(1);
            if outbox.bytes > OUTBOX_BYTES || outbox.frames > OUTBOX_CAPACITY {
                true
            } else {
                if file_data {
                    outbox.queued_data = outbox.queued_data.saturating_add(length);
                    outbox.data.push_back((packet, length));
                } else {
                    outbox.control.push_back((packet, length));
                }
                false
            }
        };
        if full {
            self.fail(io::Error::other("send queue full, peer not reading"));
            return false;
        }
        self.inner.wake.notify_one();
        true
    }

    /// Frame size limits for the next frames, as once a handshake completes.
    pub fn set_frame_limit(&self, limit: Limit) {
        *lock(&self.inner.limit) = limit;
    }

    /// Fails reads that wait longer than `timeout`; zero waits forever.
    pub fn set_read_timeout(&self, timeout: Duration) {
        self.inner.read_timeout.store(u64::try_from(timeout.as_millis()).unwrap_or(u64::MAX), Ordering::Relaxed);
    }

    pub fn remote_address(&self) -> SocketAddr {
        self.inner.remote
    }

    /// Our address on the connection.
    pub fn local_address(&self) -> Option<IpAddr> {
        self.inner.local
    }

    pub fn is_obfuscated(&self) -> bool {
        self.inner.obfuscated
    }

    pub fn is_open(&self) -> bool {
        !lock(&self.inner.outbox).closed
    }

    /// Closes the connection once the packets already queued are sent, within [`FLUSH_WAIT`].
    pub fn close(&self) {
        self.shutdown(None);
    }

    /// Closes the connection for every session, as for a client that broke the protocol.
    pub fn disconnect(&self) {
        self.close();
    }

    fn fail(&self, cause: io::Error) {
        self.shutdown(Some(cause));
    }

    fn shutdown(&self, cause: Option<io::Error>) {
        let failed = cause.is_some();
        {
            let mut outbox = lock(&self.inner.outbox);
            if outbox.closed {
                return;
            }
            outbox.closed = true;
            if failed {
                *outbox = Outbox { closed: true, ..Outbox::default() };
            }
        }
        *lock(&self.inner.cause) = cause;
        self.inner.closing.send_replace(true);
        self.inner.wake.notify_one();
        if failed || !self.inner.started.load(Ordering::SeqCst) {
            self.end_tasks();
        } else {
            // The writer sends what is queued, then closes; a peer that does not read is cut off.
            let connection = self.clone();
            if let Ok(runtime) = tokio::runtime::Handle::try_current() {
                runtime.spawn(async move {
                    tokio::time::sleep(FLUSH_WAIT).await;
                    connection.end_tasks();
                });
            }
        }
        self.report();
    }

    // The tasks leave the stream, which closes it; a connection never started drops the stream its start holds.
    fn end_tasks(&self) {
        for task in lock(&self.inner.tasks).drain(..) {
            task.abort();
        }
        lock(&self.inner.start).take();
    }

    // Tells the handler once, when there is one.
    fn report(&self) {
        let handler = lock(&self.inner.handler).clone();
        if let Some(handler) = handler
            && !self.is_open()
            && !self.inner.reported.swap(true, Ordering::SeqCst)
        {
            let cause = lock(&self.inner.cause).take();
            handler.on_closed(self, cause);
        }
    }

    // The next frame to write, control first: none to write yet, or none ever once closed and the queues are empty.
    fn next_frame(&self) -> Next {
        let mut outbox = lock(&self.inner.outbox);
        let (taken, file_data) = match outbox.control.pop_front() {
            Some(frame) => (frame, false),
            None => match outbox.data.pop_front() {
                Some(frame) => (frame, true),
                None => return if outbox.closed { Next::Done } else { Next::Wait },
            },
        };
        let (packet, length) = taken;
        outbox.frames = outbox.frames.saturating_sub(1);
        outbox.bytes = outbox.bytes.saturating_sub(length);
        if file_data {
            outbox.queued_data = outbox.queued_data.saturating_sub(length);
        }
        traffic::sent(usize::try_from(length).unwrap_or(usize::MAX), file_data);
        Next::Frame(packet)
    }
}

enum Next {
    Frame(Packet),
    Wait,
    Done,
}

fn frame_length(packet: &Packet) -> u64 {
    FRAME_HEADER.saturating_add(u64::try_from(packet.payload().len()).unwrap_or(u64::MAX))
}

async fn write<W: AsyncWrite + Unpin>(connection: TcpConnection, mut writer: FrameWriter<W>) {
    loop {
        match connection.next_frame() {
            Next::Frame(packet) => {
                if let Err(e) = writer.write_packet(&packet).await {
                    connection.fail(e);
                    return;
                }
            }
            Next::Wait => connection.inner.wake.notified().await,
            Next::Done => break,
        }
    }
    let _ = writer.flush().await;
    let _ = writer.shutdown().await;
    connection.end_tasks();
}

async fn read<R: AsyncRead + Unpin>(
    connection: TcpConnection,
    mut reader: FrameReader<R>,
    handler: Arc<dyn Handler>,
    unpacker: Unpacker,
) {
    let mut closing = connection.inner.closing.subscribe();
    loop {
        let limit = *lock(&connection.inner.limit);
        let wait = connection.inner.read_timeout.load(Ordering::Relaxed);
        let reading = async {
            if wait == 0 {
                Ok(reader.read_packet(&limit).await)
            } else {
                tokio::time::timeout(Duration::from_millis(wait), reader.read_packet(&limit)).await
            }
        };
        let read = tokio::select! {
            _ = closing.wait_for(|closed| *closed) => return,
            read = reading => read,
        };
        let frame = match read {
            Err(_) => {
                connection.fail(io::Error::new(ErrorKind::TimedOut, format!("silent for {wait} ms")));
                return;
            }
            Ok(Err(e)) => {
                connection.fail(e);
                return;
            }
            Ok(Ok(None)) => {
                connection.close();
                return;
            }
            Ok(Ok(Some(frame))) => frame,
        };
        traffic::received(usize::try_from(frame_length(&frame)).unwrap_or(usize::MAX));
        // The handler heard of the close: nothing more for it.
        if !connection.is_open() {
            return;
        }
        let told = unpacker(frame).map_err(invalid).and_then(|packet| {
            handler
                .on_packet(&connection, packet)
                .map_err(|why| io::Error::other(format!("packet handler failed: {why}")))
        });
        if let Err(e) = told {
            connection.fail(e);
            return;
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}
