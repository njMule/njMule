// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A UPnP router on the loopback, as the Java engine's FakeGateway (never the real one): it answers discovery with the
//! address of its HTTP server, which serves the description and records the SOAP actions.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU32, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::task::JoinHandle;

/// A SOAP action received, such as "AddPortMapping", with its control path and arguments, and when.
#[derive(Debug, Clone)]
pub struct Action {
    pub name: String,
    pub body: String,
    pub at: Instant,
    pub when: SystemTime,
}

/// How the description is served.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Body {
    Whole,
    // Headers, then a few bytes, then nothing.
    Stalled,
    // A megabyte, more than any description has.
    Oversized,
    // Not found at the address announced.
    Missing,
}

struct State {
    description: Mutex<String>,
    body: Mutex<Body>,
    actions: Mutex<Vec<Action>>,
    statuses: Mutex<HashMap<String, String>>,
    fault_code: AtomicU32,
    only_permanent: AtomicBool,
    mapping_delay: Mutex<Duration>,
    delete_delay: Mutex<Duration>,
    location_host: Mutex<String>,
    other_locations: Mutex<Vec<String>>,
    firewall_enabled: AtomicBool,
    pinhole_ids: AtomicU32,
    searches: AtomicUsize,
    silent: AtomicBool,
    misses_first_round: AtomicBool,
    first_search: Mutex<Option<Instant>>,
    http_port: AtomicU16,
}

pub struct FakeGateway {
    ssdp: SocketAddr,
    state: Arc<State>,
    discovery: JoinHandle<()>,
    acceptor: Mutex<JoinHandle<()>>,
}

impl FakeGateway {
    /// A gateway with one WAN connection service of `kind` at `control`.
    pub async fn start(kind: &str, control: &str) -> Self {
        Self::describing(&description(&[(kind, control)])).await
    }

    /// A gateway describing itself with `description`.
    pub async fn describing(description: &str) -> Self {
        let ssdp = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let http = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let state = Arc::new(State {
            description: Mutex::new(description.to_owned()),
            body: Mutex::new(Body::Whole),
            actions: Mutex::default(),
            statuses: Mutex::default(),
            fault_code: AtomicU32::new(0),
            only_permanent: AtomicBool::new(false),
            mapping_delay: Mutex::new(Duration::ZERO),
            delete_delay: Mutex::new(Duration::ZERO),
            location_host: Mutex::new("127.0.0.1".to_owned()),
            other_locations: Mutex::default(),
            firewall_enabled: AtomicBool::new(true),
            pinhole_ids: AtomicU32::new(100),
            searches: AtomicUsize::new(0),
            silent: AtomicBool::new(false),
            misses_first_round: AtomicBool::new(false),
            first_search: Mutex::new(None),
            http_port: AtomicU16::new(http.local_addr().unwrap().port()),
        });
        let address = ssdp.local_addr().unwrap();
        let discovery = tokio::spawn(answer_discovery(ssdp, Arc::clone(&state)));
        let acceptor = Mutex::new(tokio::spawn(serve(http, Arc::clone(&state))));
        Self { ssdp: address, state, discovery, acceptor }
    }

    /// Where to send discovery.
    pub fn ssdp(&self) -> SocketAddr {
        self.ssdp
    }

    pub fn actions(&self) -> Vec<Action> {
        self.state.actions.lock().unwrap().clone()
    }

    /// The actions named `name`, in order.
    pub fn actions_named(&self, name: &str) -> Vec<Action> {
        self.actions().into_iter().filter(|action| action.name == name).collect()
    }

    /// Refuses mappings with this UPnP error code (0: accepts them).
    pub fn refuse_with(&self, code: u32) {
        self.state.fault_code.store(code, Ordering::SeqCst);
    }

    /// Accepts only mappings without a lease, as some routers do.
    pub fn only_permanent_leases(&self) {
        self.state.only_permanent.store(true, Ordering::SeqCst);
    }

    /// What GetStatusInfo says for the service at `control`; "Connected" otherwise.
    pub fn status(&self, control: &str, status: &str) {
        self.state.statuses.lock().unwrap().insert(control.to_owned(), status.to_owned());
    }

    /// Says its IPv6 firewall is off, so that nothing needs opening.
    pub fn ipv6_firewall_off(&self) {
        self.state.firewall_enabled.store(false, Ordering::SeqCst);
    }

    /// Answers each DeletePortMapping only after `delay`.
    pub fn delay_deletes(&self, delay: Duration) {
        *self.state.delete_delay.lock().unwrap() = delay;
    }

    /// Answers each AddPortMapping only after `delay`, as a slow router does.
    pub fn delay_mappings(&self, delay: Duration) {
        *self.state.mapping_delay.lock().unwrap() = delay;
    }

    pub fn serve_description(&self, how: Body) {
        *self.state.body.lock().unwrap() = how;
    }

    /// Announces the description on another host, which must not be believed.
    pub fn announce_on(&self, host: &str) {
        host.clone_into(&mut self.state.location_host.lock().unwrap());
    }

    /// Answers no search, as a router whose answers are lost; its web server still answers.
    pub fn stop_answering(&self) {
        self.state.silent.store(true, Ordering::SeqCst);
    }

    pub fn answer_again(&self) {
        self.state.silent.store(false, Ordering::SeqCst);
    }

    /// Misses the first round of searches, those that came together, as when their datagrams are lost.
    pub fn miss_the_first_searches(&self) {
        self.state.misses_first_round.store(true, Ordering::SeqCst);
    }

    /// How many searches came, answered or not.
    pub fn searches(&self) -> usize {
        self.state.searches.load(Ordering::SeqCst)
    }

    /// Other devices answering discovery first, from these description addresses.
    pub fn others_answer_first(&self, locations: Vec<String>) {
        *self.state.other_locations.lock().unwrap() = locations;
    }

    /// The router restarts its web server on another port: the old control address no longer answers.
    pub async fn move_http(&self) {
        let http = TcpListener::bind("127.0.0.1:0").await.unwrap();
        self.state.http_port.store(http.local_addr().unwrap().port(), Ordering::SeqCst);
        let serving = tokio::spawn(serve(http, Arc::clone(&self.state)));
        let old = std::mem::replace(&mut *self.acceptor.lock().unwrap(), serving);
        old.abort();
        let _ = old.await;
    }
}

impl Drop for FakeGateway {
    fn drop(&mut self) {
        self.discovery.abort();
        self.acceptor.lock().unwrap().abort();
    }
}

/// A description listing services as (type, control URL).
pub fn description(services: &[(&str, &str)]) -> String {
    let list: String = services
        .iter()
        .map(|(kind, control)| {
            format!("<service><serviceType>{kind}</serviceType><controlURL>{control}</controlURL></service>")
        })
        .collect();
    format!(
        "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>\
         <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>\
         <deviceList><device><serviceList>{list}</serviceList></device></deviceList></device></root>"
    )
}

async fn answer_discovery(socket: UdpSocket, state: Arc<State>) {
    let mut buffer = [0u8; 2048];
    while let Ok((_, from)) = socket.recv_from(&mut buffer).await {
        state.searches.fetch_add(1, Ordering::SeqCst);
        let now = Instant::now();
        let first = *state.first_search.lock().unwrap().get_or_insert(now);
        // A round goes out at once: what comes within this of the first search belongs to it.
        let first_round = now.duration_since(first) < Duration::from_millis(200);
        if state.silent.load(Ordering::SeqCst) || (state.misses_first_round.load(Ordering::SeqCst) && first_round) {
            continue;
        }
        let others = state.other_locations.lock().unwrap().clone();
        let own = format!(
            "http://{}:{}/desc.xml",
            state.location_host.lock().unwrap(),
            state.http_port.load(Ordering::SeqCst)
        );
        for location in others.iter().chain([&own]) {
            let answer = format!("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nLOCATION: {location}\r\n\r\n");
            let _ = socket.send_to(answer.as_bytes(), from).await;
        }
    }
}

async fn serve(listener: TcpListener, state: Arc<State>) {
    while let Ok((client, _)) = listener.accept().await {
        tokio::spawn(answer(client, Arc::clone(&state)));
    }
}

async fn answer(mut client: TcpStream, state: Arc<State>) {
    let mut head = Vec::new();
    let mut byte = [0u8; 1];
    while !head.ends_with(b"\r\n\r\n") {
        match client.read(&mut byte).await {
            Ok(1) => head.push(byte[0]),
            _ => return,
        }
    }
    let request = String::from_utf8_lossy(&head).into_owned();
    let mut length = 0;
    let mut action = String::new();
    for line in request.split("\r\n") {
        let lower = line.to_ascii_lowercase();
        if let Some(value) = lower.strip_prefix("content-length:") {
            length = value.trim().parse().unwrap_or(0);
        } else if lower.starts_with("soapaction:") {
            action = line[11..].trim().replace('"', "");
        }
    }
    let mut body = vec![0u8; length];
    if client.read_exact(&mut body).await.is_err() {
        return;
    }
    let text = String::from_utf8_lossy(&body).into_owned();
    if request.starts_with("GET") {
        describe(&mut client, &state).await;
        return;
    }
    let control = request.split(' ').nth(1).unwrap_or_default().to_owned();
    let (service, name) = action.split_once('#').unwrap_or_default();
    let envelope = |inner: String| {
        format!(
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>{inner}</s:Body></s:Envelope>"
        )
    };
    match name {
        "GetStatusInfo" => {
            let status =
                state.statuses.lock().unwrap().get(&control).cloned().unwrap_or_else(|| "Connected".to_owned());
            let inner = format!(
                "<u:GetStatusInfoResponse xmlns:u=\"{service}\"><NewConnectionStatus>{status}</NewConnectionStatus>\
                 </u:GetStatusInfoResponse>"
            );
            reply(&mut client, 200, &envelope(inner)).await;
            return;
        }
        "GetFirewallStatus" => {
            let enabled = u8::from(state.firewall_enabled.load(Ordering::SeqCst));
            let inner = format!(
                "<u:GetFirewallStatusResponse xmlns:u=\"{service}\"><FirewallEnabled>{enabled}</FirewallEnabled>\
                 <InboundPinholeAllowed>1</InboundPinholeAllowed></u:GetFirewallStatusResponse>"
            );
            reply(&mut client, 200, &envelope(inner)).await;
            return;
        }
        _ => {}
    }
    state.actions.lock().unwrap().push(Action {
        name: name.to_owned(),
        body: format!("{control} {text}"),
        at: Instant::now(),
        when: SystemTime::now(),
    });
    if name == "AddPinhole" {
        let id = state.pinhole_ids.fetch_add(1, Ordering::SeqCst);
        let inner =
            format!("<u:AddPinholeResponse xmlns:u=\"{service}\"><UniqueID>{id}</UniqueID></u:AddPinholeResponse>");
        reply(&mut client, 200, &envelope(inner)).await;
        return;
    }
    if name == "AddPortMapping" {
        let delay = *state.mapping_delay.lock().unwrap();
        tokio::time::sleep(delay).await;
    } else if name == "DeletePortMapping" {
        let delay = *state.delete_delay.lock().unwrap();
        tokio::time::sleep(delay).await;
    }
    let leased = !text.contains("<NewLeaseDuration>0</NewLeaseDuration>");
    let code = state.fault_code.load(Ordering::SeqCst);
    if name == "AddPortMapping" && state.only_permanent.load(Ordering::SeqCst) && leased {
        reply(&mut client, 500, &fault(725, "OnlyPermanentLeasesSupported")).await;
    } else if code != 0 {
        reply(&mut client, 500, &fault(code, "Action not\nauthorized")).await;
    } else {
        reply(&mut client, 200, "<s:Envelope/>").await;
    }
}

async fn describe(client: &mut TcpStream, state: &State) {
    let how = *state.body.lock().unwrap();
    let description = state.description.lock().unwrap().clone();
    match how {
        Body::Whole => reply(client, 200, &description).await,
        Body::Stalled => {
            let head = "HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 5000\r\n\r\n<?xml";
            let _ = client.write_all(head.as_bytes()).await;
            let _ = client.flush().await;
            tokio::time::sleep(Duration::from_secs(300)).await;
        }
        Body::Oversized => reply(client, 200, &format!("{description}{}", " ".repeat(1024 * 1024))).await,
        Body::Missing => reply(client, 404, "").await,
    }
}

fn fault(code: u32, description: &str) -> String {
    format!(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>\
         <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>\
         <UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>{code}</errorCode>\
         <errorDescription>{description}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    )
}

async fn reply(client: &mut TcpStream, code: u16, text: &str) {
    let head = format!(
        "HTTP/1.1 {code} X\r\nContent-Type: text/xml\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        text.len()
    );
    let _ = client.write_all(head.as_bytes()).await;
    let _ = client.write_all(text.as_bytes()).await;
    let _ = client.flush().await;
}
