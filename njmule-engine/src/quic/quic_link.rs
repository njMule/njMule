// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! QUIC connections between clients through their routers, whose datagrams travel in frames on the client UDP port.
//! One endpoint opens connections as TLS client and takes them as TLS server; nobody checks certificates, as each
//! side first proves on its stream who it is and whom it expects.

use std::collections::{HashMap, HashSet};
use std::io;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError, Weak};
use std::time::{Duration, Instant, SystemTime};

use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use quinn::{
    ClientConfig, Connection, Endpoint, EndpointConfig, Incoming, ServerConfig, TokioRuntime, TransportConfig, VarInt,
};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};

use super::frame_socket::FrameSocket;
use super::quic_transport::QuicTransport;
use super::tls_identity::TlsIdentity;
use crate::protocol::ed2k::nat_traversal_messages::QuicProof;

/// How QUIC datagrams leave the client UDP port.
pub type Sender = Arc<dyn Fn(SocketAddr, &[u8]) + Send + Sync>;

/// Our user hash, as it is when a connection opens.
pub type OurHash = Arc<dyn Fn() -> [u8; 16] + Send + Sync>;

/// The clients whose connections are taken.
pub trait Peers: Send + Sync + 'static {
    fn may_connect(&self, from: SocketAddr) -> bool;

    /// The user hash of the client awaited at `from`, when known.
    fn expected(&self, from: SocketAddr) -> Option<[u8; 16]>;

    /// A connection a client opened, once it proved who it is.
    fn accepted(&self, connection: QuicTransport);
}

pub const PROTOCOL: &str = "ed2k-ai-natt-quic-v1";
const SERVER_NAME: &str = "emuleai-natt";
// 512 KiB for the one stream, 4 MiB for the connection.
const STREAM_WINDOW: u32 = 524_288;
const CONNECTION_WINDOW: u32 = 4_194_304;
// 50 s, in milliseconds.
const IDLE_MILLIS: u32 = 50_000;
// Pings at half the idle time, which keeps the routers' holes open.
const KEEP_ALIVE: Duration = Duration::from_secs(25);
const HANDSHAKE: Duration = Duration::from_secs(20);
const PROOF_WAIT: Duration = Duration::from_secs(10);
// A host whose connection our server took is heard while it talks: for twice the idle time since its last datagram.
const SERVED_FOR: Duration = Duration::from_secs(100);
const NO_HASH: [u8; 16] = [0; 16];

/// The QUIC side of the client UDP port. Cloning it gives another handle on the same endpoint.
#[derive(Clone)]
pub struct QuicLink {
    link: Arc<Link>,
}

struct Link {
    endpoint: Endpoint,
    socket: Arc<FrameSocket>,
    client: ClientConfig,
    our_hash: OurHash,
    peers: Arc<dyn Peers>,
    books: Mutex<Books>,
}

#[derive(Default)]
struct Books {
    // The hosts we open connections to, while being opened or open.
    opening: HashSet<SocketAddr>,
    // The hosts whose connections our server takes, as last heard.
    served: HashMap<SocketAddr, Instant>,
    // The connections open now, under the numbers of their registrations.
    live: HashMap<u64, (SocketAddr, Connection)>,
    registered: u64,
    stopped: bool,
}

/// A connection in the link's books until it is dropped.
pub(crate) struct Registration {
    link: Weak<Link>,
    number: u64,
    host: Host,
}

#[derive(Clone, Copy)]
enum Host {
    Opened(SocketAddr),
    Taken(SocketAddr),
}

impl QuicLink {
    /// A link whose datagrams leave through `sender`. It runs on the Tokio runtime it is made on.
    pub fn new(sender: Sender, our_hash: OurHash, peers: Arc<dyn Peers>) -> io::Result<Self> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let socket = Arc::new(FrameSocket::new(sender));
        let server = match server(&provider) {
            Ok(server) => Some(server),
            Err(e) => {
                tracing::warn!("Connections through routers over QUIC cannot come in: {e}");
                None
            }
        };
        let endpoint = Endpoint::new_with_abstract_socket(
            EndpointConfig::default(),
            server,
            socket.clone(),
            Arc::new(TokioRuntime),
        )?;
        let client = client(provider)?;
        let link =
            Arc::new(Link { endpoint: endpoint.clone(), socket, client, our_hash, peers, books: Mutex::default() });
        tokio::spawn(accept(endpoint, Arc::downgrade(&link)));
        Ok(Self { link })
    }

    /// A QUIC datagram that came in a frame from `from`.
    pub fn received(&self, from: SocketAddr, datagram: &[u8]) {
        let Some(first) = datagram.first() else {
            return;
        };
        let now = Instant::now();
        let talking = {
            let books = self.link.books();
            if books.stopped {
                return;
            }
            if books.opening.contains(&from) {
                drop(books);
                self.link.socket.deliver(from, datagram);
                return;
            }
            books.served.get(&from).is_some_and(|heard| now.saturating_duration_since(*heard) < SERVED_FOR)
        };
        // Only a host we take connections from may start one: its first packets have the long header.
        if talking || (first & 0x80 != 0 && self.link.peers.may_connect(from)) {
            let mut books = self.link.books();
            if !talking {
                books.served.retain(|_, heard| now.saturating_duration_since(*heard) < SERVED_FOR);
            }
            books.served.insert(from, now);
            drop(books);
            self.link.socket.deliver(from, datagram);
        }
    }

    /// Opens a connection to `to`, whose client we expect to have the user hash `peer` when known.
    pub async fn connect(&self, to: SocketAddr, peer: Option<[u8; 16]>) -> io::Result<QuicTransport> {
        let registration = register(&self.link, Host::Opened(to))?;
        let connecting =
            self.link.endpoint.connect_with(self.link.client.clone(), to, SERVER_NAME).map_err(io::Error::other)?;
        let connection = tokio::time::timeout(HANDSHAKE, connecting)
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "no QUIC handshake"))?
            .map_err(io::Error::other)?;
        self.link.establish(connection, to, peer, registration).await
    }

    /// Whether a connection to `to` is being opened, or is open.
    pub fn connecting(&self, to: SocketAddr) -> bool {
        self.link.books().opening.contains(&to)
    }

    /// The hosts of the connections open now.
    pub fn connections(&self) -> Vec<SocketAddr> {
        let books = self.link.books();
        books
            .live
            .values()
            .filter(|(_, connection)| connection.close_reason().is_none())
            .map(|(at, _)| *at)
            .collect()
    }

    /// Closes every connection; what comes later is dropped.
    pub fn close(&self) {
        self.link.books().stopped = true;
        self.link.endpoint.close(VarInt::from_u32(0), b"");
    }
}

impl Link {
    fn books(&self) -> MutexGuard<'_, Books> {
        self.books.lock().unwrap_or_else(PoisonError::into_inner)
    }

    // Both sides write their proof first and check the other's.
    async fn establish(
        &self,
        connection: Connection,
        remote: SocketAddr,
        peer: Option<[u8; 16]>,
        registration: Registration,
    ) -> io::Result<QuicTransport> {
        let us = (self.our_hash)();
        let mut ours = connection.open_bi().await.map_err(io::Error::other)?;
        ours.0.write_all(&QuicProof { sender: us, receiver: peer.unwrap_or(NO_HASH) }.encode()).await?;
        let read = async {
            let mut theirs = connection.accept_bi().await.map_err(io::Error::other)?;
            let mut proof = [0; QuicProof::LENGTH];
            theirs.1.read_exact(&mut proof).await.map_err(io::Error::other)?;
            io::Result::Ok((theirs, proof))
        };
        let (theirs, proof) = tokio::time::timeout(PROOF_WAIT, read)
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "no proof in time"))??;
        let proof = QuicProof::decode(&proof).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        if proof.receiver != NO_HASH && proof.receiver != us {
            return Err(io::Error::other("a proof for another client"));
        }
        if peer.is_some_and(|peer| proof.sender != peer) {
            return Err(io::Error::other("a proof from another client"));
        }
        self.books().live.insert(registration.number, (remote, connection.clone()));
        Ok(QuicTransport::new(connection, remote, ours, theirs, registration))
    }
}

impl Drop for Link {
    fn drop(&mut self) {
        self.endpoint.close(VarInt::from_u32(0), b"");
    }
}

impl Drop for Registration {
    fn drop(&mut self) {
        let Some(link) = self.link.upgrade() else {
            return;
        };
        let mut books = link.books();
        books.live.remove(&self.number);
        match self.host {
            Host::Opened(to) => books.opening.remove(&to),
            Host::Taken(from) => books.served.remove(&from).is_some(),
        };
    }
}

fn register(link: &Arc<Link>, host: Host) -> io::Result<Registration> {
    let mut books = link.books();
    if books.stopped {
        return Err(io::Error::other("QUIC is closed"));
    }
    if let Host::Opened(to) = host
        && !books.opening.insert(to)
    {
        return Err(io::Error::other(format!("already connecting to {to}")));
    }
    books.registered = books.registered.wrapping_add(1);
    Ok(Registration { link: Arc::downgrade(link), number: books.registered, host })
}

// The connections clients open, each handed on once the client proved who it is; until the endpoint closes.
async fn accept(endpoint: Endpoint, link: Weak<Link>) {
    while let Some(incoming) = endpoint.accept().await {
        let Some(link) = link.upgrade() else {
            return;
        };
        tokio::spawn(async move {
            let from = incoming.remote_address();
            match take(&link, incoming, from).await {
                Ok(connection) => link.peers.accepted(connection),
                Err(e) => tracing::debug!("No QUIC connection from {from}: {e}"),
            }
        });
    }
}

async fn take(link: &Arc<Link>, incoming: Incoming, from: SocketAddr) -> io::Result<QuicTransport> {
    let connecting = incoming.accept().map_err(io::Error::other)?;
    let connection = tokio::time::timeout(HANDSHAKE, connecting)
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "no QUIC handshake"))?
        .map_err(io::Error::other)?;
    let registration = register(link, Host::Taken(from))?;
    link.establish(connection, from, link.peers.expected(from), registration).await
}

fn server(provider: &Arc<CryptoProvider>) -> Result<ServerConfig, Box<dyn std::error::Error + Send + Sync>> {
    let mut tls = TlsIdentity::self_signed(SystemTime::now())?.serve(provider.clone())?;
    tls.alpn_protocols = vec![PROTOCOL.as_bytes().to_vec()];
    let mut server = ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls)?));
    server.transport_config(Arc::new(transport(None)));
    Ok(server)
}

fn client(provider: Arc<CryptoProvider>) -> io::Result<ClientConfig> {
    let mut tls = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(io::Error::other)?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Unchecked(provider)))
        .with_no_client_auth();
    tls.alpn_protocols = vec![PROTOCOL.as_bytes().to_vec()];
    let mut client = ClientConfig::new(Arc::new(QuicClientConfig::try_from(tls).map_err(io::Error::other)?));
    client.transport_config(Arc::new(transport(Some(KEEP_ALIVE))));
    Ok(client)
}

// One stream each way, opened by each side; connection ids of 8 bytes, quinn's own length.
fn transport(keep_alive: Option<Duration>) -> TransportConfig {
    let mut transport = TransportConfig::default();
    transport
        .max_idle_timeout(Some(VarInt::from_u32(IDLE_MILLIS).into()))
        .keep_alive_interval(keep_alive)
        .max_concurrent_bidi_streams(VarInt::from_u32(1))
        .max_concurrent_uni_streams(VarInt::from_u32(0))
        .stream_receive_window(VarInt::from_u32(STREAM_WINDOW))
        .receive_window(VarInt::from_u32(CONNECTION_WINDOW));
    transport
}

// Certificates go unchecked on purpose, as the proofs identify the clients; the handshake's signatures are checked.
#[derive(Debug)]
struct Unchecked(Arc<CryptoProvider>);

impl ServerCertVerifier for Unchecked {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}
