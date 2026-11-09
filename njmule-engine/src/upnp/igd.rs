// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! An Internet gateway device found with UPnP: discovery over SSDP, its description, and the WAN connection service's
//! port mappings over SOAP. A gateway is only believed when it describes itself from its own LAN address.
//!
//! Written here rather than taken from igd-next, which has no IPv6 firewall service, does not check where a description
//! comes from, and searches on one network only.

use std::collections::HashSet;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket as ProbeSocket};
use std::time::Duration;

use reqwest::{Client, Method, StatusCode};
use roxmltree::{Document, Node};
use tokio::net::UdpSocket;
use tokio::time::Instant;
use url::{Host, Url};

use crate::net::http_route;
use crate::upnp::gateway::{GatewayError, Protocol};

/// Where gateways listen for discovery.
pub const SSDP: SocketAddr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(239, 255, 255, 250)), 1900);

const MAX_DESCRIPTION: usize = 256 * 1024;
const MAX_REASON: usize = 120;
// For the whole answer, headers and body.
const HTTP_TIMEOUT: Duration = Duration::from_secs(5);
// The searches sent within a discovery's wait, evenly spaced from its start.
const SEARCH_ROUNDS: u32 = 3;
// UPnP Device Architecture: a TTL of 2 for discovery.
const SEARCH_TTL: u32 = 2;
const LARGEST_SEARCH_ANSWER: usize = 2048;
const DEVICES: [&str; 2] =
    ["urn:schemas-upnp-org:device:InternetGatewayDevice:2", "urn:schemas-upnp-org:device:InternetGatewayDevice:1"];
const SERVICES: [&str; 2] =
    ["urn:schemas-upnp-org:service:WANIPConnection:", "urn:schemas-upnp-org:service:WANPPPConnection:"];
// The service of IGD 2 routers that opens their IPv6 firewall.
const FIREWALL_SERVICE: &str = "urn:schemas-upnp-org:service:WANIPv6FirewallControl:1";
const PCP_TCP: u8 = 6;
const PCP_UDP: u8 = 17;

pub struct Igd {
    location: Url,
    control: Url,
    service: String,
    firewall: Option<Url>,
    http: Client,
}

impl Igd {
    /// Asks `target` (normally [`SSDP`]) for a gateway, a few times, and waits up to `wait`.
    pub async fn discover(target: SocketAddr, wait: Duration) -> io::Result<Option<Self>> {
        // The router is on the LAN: never through the proxy of the preferences.
        let http = http_route::lan_client(HTTP_TIMEOUT)?;
        let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0))).await?;
        socket.set_multicast_ttl_v4(SEARCH_TTL)?;
        let lans = if target.ip().is_multicast() { lans() } else { Vec::new() };
        let start = Instant::now();
        let deadline = start.checked_add(wait).unwrap_or(start);
        let interval = wait.checked_div(SEARCH_ROUNDS).unwrap_or_default();
        let mut rounds = 0;
        let mut next_round = start;
        let mut seen = HashSet::new();
        let mut buffer = vec![0u8; LARGEST_SEARCH_ANSWER];
        while Instant::now() < deadline {
            // UDP loses datagrams: UPnP Device Architecture asks for each search to be sent more than once.
            if rounds < SEARCH_ROUNDS && Instant::now() >= next_round {
                send_searches(&socket, &lans, target).await;
                rounds = rounds.saturating_add(1);
                next_round = start.checked_add(interval.saturating_mul(rounds)).unwrap_or(deadline);
            }
            let until = if rounds < SEARCH_ROUNDS { next_round.min(deadline) } else { deadline };
            let Ok(received) = tokio::time::timeout_at(until, socket.recv_from(&mut buffer)).await else {
                continue;
            };
            let Ok((count, responder)) = received else {
                // On Windows an ICMP error for an earlier datagram fails a receive: harmless.
                continue;
            };
            let answer = String::from_utf8_lossy(buffer.get(..count).unwrap_or_default()).into_owned();
            let Some(location) = location(&answer) else {
                continue;
            };
            if !seen.insert(location.clone()) {
                continue;
            }
            if !trusted(&location, responder.ip()) {
                tracing::debug!("UPnP: ignoring {location} announced by {}", responder.ip());
                continue;
            }
            match describe(&location, &http).await {
                Ok(Some(gateway)) => return Ok(Some(gateway)),
                Ok(None) => {}
                // Another device that answers badly must not hide the router.
                Err(e) => tracing::debug!("UPnP: no description at {location}: {e}"),
            }
        }
        Ok(None)
    }

    pub fn control_url(&self) -> &Url {
        &self.control
    }

    /// The protocol it speaks, as the log names it.
    pub fn protocol(&self) -> &'static str {
        "UPnP"
    }

    /// Its host, as the reports name it.
    pub fn host(&self) -> String {
        self.control.host_str().unwrap_or_default().to_owned()
    }

    /// Forwards `port` to our address towards the router for `lease` seconds (0: until removed); the router keeps the
    /// lease asked for.
    pub async fn add_mapping(
        &self,
        protocol: Protocol,
        port: u16,
        description: &str,
        lease: u32,
    ) -> Result<u32, GatewayError> {
        // Asked each time: our address may have changed since the router was found.
        let ours = local_towards(&self.location)?;
        let arguments = format!(
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>{port}</NewExternalPort>\
             <NewProtocol>{}</NewProtocol><NewInternalPort>{port}</NewInternalPort>\
             <NewInternalClient>{ours}</NewInternalClient><NewEnabled>1</NewEnabled>\
             <NewPortMappingDescription>{}</NewPortMappingDescription><NewLeaseDuration>{lease}</NewLeaseDuration>",
            protocol.name(),
            escape(description)
        );
        self.call("AddPortMapping", &arguments).await?;
        Ok(lease)
    }

    pub async fn delete_mapping(&self, protocol: Protocol, port: u16) -> Result<(), GatewayError> {
        let arguments = format!(
            "<NewRemoteHost></NewRemoteHost><NewExternalPort>{port}</NewExternalPort>\
             <NewProtocol>{}</NewProtocol>",
            protocol.name()
        );
        self.call("DeletePortMapping", &arguments).await.map(drop)
    }

    /// Whether the WAN connection is up; a gateway that does not say is not taken as up.
    pub async fn is_connected(&self) -> bool {
        let answer = match self.call("GetStatusInfo", "").await {
            Ok(answer) => answer,
            Err(e) => {
                tracing::debug!("UPnP: no status of {}: {e}", self.control);
                return false;
            }
        };
        let text = String::from_utf8_lossy(&answer);
        Document::parse(&text)
            .ok()
            .and_then(|doc| named(doc.root(), "NewConnectionStatus"))
            .is_some_and(|status| status.eq_ignore_ascii_case("Connected"))
    }

    /// Opens the router's IPv6 firewall to `port` at our `address` for `lease` seconds; none when the router has no
    /// IPv6 firewall that may be opened, or when it is off and needs no pinhole.
    pub async fn open_pinhole(
        &self,
        protocol: Protocol,
        address: Ipv6Addr,
        port: u16,
        lease: u32,
    ) -> Result<Option<u32>, GatewayError> {
        let Some(firewall) = &self.firewall else {
            return Ok(None);
        };
        let status = self.call_at(firewall, FIREWALL_SERVICE, "GetFirewallStatus", "").await?;
        let status = String::from_utf8_lossy(&status).into_owned();
        let status = Document::parse(&status).map_err(|e| unreadable(&e))?;
        if !flag(status.root(), "FirewallEnabled") {
            return Ok(None);
        }
        if !flag(status.root(), "InboundPinholeAllowed") {
            return Err(GatewayError::Refused("the router opens no pinholes in its IPv6 firewall".to_owned()));
        }
        let transport = if protocol == Protocol::Udp { PCP_UDP } else { PCP_TCP };
        let arguments = format!(
            "<RemoteHost></RemoteHost><RemotePort>0</RemotePort><InternalClient>{address}</InternalClient>\
             <InternalPort>{port}</InternalPort><Protocol>{transport}</Protocol><LeaseTime>{lease}</LeaseTime>"
        );
        let added = self.call_at(firewall, FIREWALL_SERVICE, "AddPinhole", &arguments).await?;
        let added = String::from_utf8_lossy(&added).into_owned();
        let added = Document::parse(&added).map_err(|e| unreadable(&e))?;
        let id = named(added.root(), "UniqueID").ok_or_else(|| io::Error::other("no pinhole id"))?;
        let id = id.parse().map_err(|_| io::Error::other(format!("unreadable pinhole id {id}")))?;
        Ok(Some(id))
    }

    /// Keeps the pinhole `id` open for `lease` seconds more.
    pub async fn renew_pinhole(&self, id: u32, lease: u32) -> Result<(), GatewayError> {
        let firewall = self.firewall.as_ref().ok_or_else(|| io::Error::other("no IPv6 firewall"))?;
        let arguments = format!("<UniqueID>{id}</UniqueID><NewLeaseTime>{lease}</NewLeaseTime>");
        self.call_at(firewall, FIREWALL_SERVICE, "UpdatePinhole", &arguments).await.map(drop)
    }

    pub async fn close_pinhole(&self, id: u32) -> Result<(), GatewayError> {
        let firewall = self.firewall.as_ref().ok_or_else(|| io::Error::other("no IPv6 firewall"))?;
        self.call_at(firewall, FIREWALL_SERVICE, "DeletePinhole", &format!("<UniqueID>{id}</UniqueID>"))
            .await
            .map(drop)
    }

    async fn call(&self, action: &str, arguments: &str) -> Result<Vec<u8>, GatewayError> {
        self.call_at(&self.control, &self.service, action, arguments).await
    }

    async fn call_at(&self, at: &Url, service: &str, action: &str, arguments: &str) -> Result<Vec<u8>, GatewayError> {
        let body = format!(
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
             s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:{action} xmlns:u=\"{service}\">\
             {arguments}</u:{action}></s:Body></s:Envelope>"
        );
        let request = self
            .http
            .request(Method::POST, at.clone())
            .header("SOAPAction", format!("\"{service}#{action}\""))
            .header(reqwest::header::CONTENT_TYPE, "text/xml; charset=\"utf-8\"")
            .body(body);
        let (status, answer) = exchange(request).await?;
        if status != StatusCode::OK {
            let reason = reason(status.as_u16(), &answer);
            tracing::debug!("UPnP {action} refused: {reason}");
            return Err(GatewayError::Refused(reason));
        }
        Ok(answer)
    }
}

/// The location a search answer gives, in its LOCATION header.
pub fn location(answer: &str) -> Option<Url> {
    answer.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        name.trim().eq_ignore_ascii_case("LOCATION").then(|| Url::parse(value.trim()).ok()).flatten()
    })
}

/// The reason of a refusal: the code and description of a UPnP fault (UPnP Device Architecture, control errors),
/// printable and short since it goes to the log, else the HTTP status.
pub fn reason(status: u16, body: &[u8]) -> String {
    let fault = (body.len() <= MAX_DESCRIPTION).then(|| String::from_utf8_lossy(body).into_owned()).and_then(|text| {
        let doc = Document::parse(&text).ok()?;
        let code = named(doc.root(), "errorCode")?;
        let told = named(doc.root(), "errorDescription").map(|d| format!(" {d}")).unwrap_or_default();
        Some(format!("{code}{told}"))
    });
    match fault {
        Some(text) => {
            let clean: String = text.chars().map(|c| if c.is_ascii_control() { ' ' } else { c }).collect();
            clean.trim().chars().take(MAX_REASON).collect()
        }
        None => format!("HTTP {status}"),
    }
}

// The WAN connection in use, when the router has more than one; else the first.
async fn describe(location: &Url, http: &Client) -> io::Result<Option<Igd>> {
    let (status, xml) = exchange(http.get(location.clone())).await?;
    if status != StatusCode::OK {
        return Ok(None);
    }
    let text = String::from_utf8_lossy(&xml).into_owned();
    let doc = match Document::parse(&text) {
        Ok(doc) => doc,
        Err(e) => {
            tracing::debug!("UPnP: unreadable description at {location}: {e}");
            return Ok(None);
        }
    };
    let base = match named(doc.root(), "URLBase").map(|base| Url::parse(&base)) {
        Some(Ok(base)) => base,
        Some(Err(_)) => return Ok(None),
        None => location.clone(),
    };
    let responder = location.host_str().and_then(|host| host.trim_matches(['[', ']']).parse::<IpAddr>().ok());
    let mut connections: Vec<(Url, String)> = Vec::new();
    let mut firewall = None;
    for service in doc.descendants().filter(|node| node.is_element() && node.tag_name().name() == "service") {
        let kind = named(service, "serviceType").unwrap_or_default();
        let Some(control) = named(service, "controlURL") else {
            continue;
        };
        let connection = SERVICES.iter().any(|prefix| kind.starts_with(prefix));
        if !connection && kind != FIREWALL_SERVICE {
            continue;
        }
        // An address no URI can hold, or not on the device that answered, discredits the whole description.
        let control = match base.join(&control) {
            Ok(url) if is_reference(&control) && responder.is_some_and(|responder| trusted(&url, responder)) => url,
            _ => return Ok(None),
        };
        if connection {
            if !connections.iter().any(|(known, _)| *known == control) {
                connections.push((control, kind));
            }
        } else if firewall.is_none() {
            firewall = Some(control);
        }
    }
    let candidates: Vec<Igd> = connections
        .into_iter()
        .map(|(control, service)| Igd {
            location: location.clone(),
            control,
            service,
            firewall: firewall.clone(),
            http: http.clone(),
        })
        .collect();
    if candidates.len() > 1 {
        for (at, candidate) in candidates.iter().enumerate() {
            if candidate.is_connected().await {
                return Ok(candidates.into_iter().nth(at));
            }
        }
    }
    Ok(candidates.into_iter().next())
}

// A device that sends without end, or stops half-way, holds us no longer than the timeout, nor for more than a
// description's size.
async fn exchange(request: reqwest::RequestBuilder) -> io::Result<(StatusCode, Vec<u8>)> {
    let mut response = request.send().await.map_err(io::Error::other)?;
    let status = response.status();
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(io::Error::other)? {
        if body.len().saturating_add(chunk.len()) > MAX_DESCRIPTION {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "an answer larger than any description"));
        }
        body.extend_from_slice(&chunk);
    }
    Ok((status, body))
}

// Only the device that answered may describe itself, and only over plain HTTP on its LAN.
fn trusted(url: &Url, responder: IpAddr) -> bool {
    if !url.scheme().eq_ignore_ascii_case("http") {
        return false;
    }
    let host = match url.host() {
        Some(Host::Ipv4(host)) => IpAddr::V4(host),
        Some(Host::Ipv6(host)) => IpAddr::V6(host),
        _ => return false,
    };
    let local = match host {
        IpAddr::V4(host) => host.is_private() || host.is_link_local() || host.is_loopback(),
        IpAddr::V6(host) => {
            // Site-local fec0::/10, link-local fe80::/10, loopback.
            let prefix = host.segments().first().copied().unwrap_or_default() & 0xffc0;
            prefix == 0xfec0 || prefix == 0xfe80 || host.is_loopback()
        }
    };
    host == responder.to_canonical() && local
}

// Whether `text` may stand in a URI as it is: no spaces, quotes or the other characters RFC 3986 leaves out.
fn is_reference(text: &str) -> bool {
    !text.is_empty() && text.chars().all(|c| !c.is_ascii() || (c.is_ascii_graphic() && !"\"<>\\^`{|}".contains(c)))
}

async fn send_searches(socket: &UdpSocket, lans: &[Ipv4Addr], target: SocketAddr) {
    for device in DEVICES {
        let search = format!(
            "M-SEARCH * HTTP/1.1\r\nHOST: {}:{}\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: {device}\r\n\r\n",
            target.ip(),
            target.port()
        );
        if lans.is_empty()
            && let Err(e) = socket.send_to(search.as_bytes(), target).await
        {
            tracing::debug!("UPnP: cannot search at {target}: {e}");
        }
        for lan in lans {
            let sent = match socket2::SockRef::from(socket).set_multicast_if_v4(lan) {
                Ok(()) => socket.send_to(search.as_bytes(), target).await.map(drop),
                Err(e) => Err(e),
            };
            if let Err(e) = sent {
                tracing::debug!("UPnP: cannot search on {lan}: {e}");
            }
        }
    }
}

// Multicast takes the route of lowest metric, often a virtual adapter (WSL, Hyper-V, a VPN) that leads to no router:
// the search goes out on every LAN instead, from one of its addresses.
fn lans() -> Vec<Ipv4Addr> {
    let interfaces = match if_addrs::get_if_addrs() {
        Ok(interfaces) => interfaces,
        Err(e) => {
            tracing::debug!("UPnP: cannot list the network interfaces: {e}");
            return Vec::new();
        }
    };
    let mut lans: Vec<Ipv4Addr> = Vec::new();
    let mut names = HashSet::new();
    for interface in interfaces {
        let if_addrs::IfAddr::V4(v4) = &interface.addr else {
            continue;
        };
        let usable = interface.is_oper_up() && !interface.is_loopback() && !interface.is_p2p();
        if usable && !v4.ip.is_link_local() && !v4.ip.is_loopback() && names.insert(interface.name.clone()) {
            lans.push(v4.ip);
        }
    }
    lans
}

fn local_towards(location: &Url) -> io::Result<IpAddr> {
    let host = location.host_str().unwrap_or_default().trim_matches(['[', ']']);
    let address: IpAddr = host.parse().map_err(|_| io::Error::other(format!("not an address: {host}")))?;
    let any: IpAddr = if address.is_ipv6() { Ipv6Addr::UNSPECIFIED.into() } else { Ipv4Addr::UNSPECIFIED.into() };
    let probe = ProbeSocket::bind(SocketAddr::new(any, 0))?;
    probe.connect(SocketAddr::new(address, location.port_or_known_default().unwrap_or(80)))?;
    Ok(probe.local_addr()?.ip())
}

// A flag the router does not give counts as set.
fn flag(answer: Node<'_, '_>, name: &str) -> bool {
    named(answer, name)
        .is_none_or(|value| value == "1" || value.eq_ignore_ascii_case("true") || value.eq_ignore_ascii_case("yes"))
}

// The first element of this local name, in any namespace, that holds text.
fn named(parent: Node<'_, '_>, local_name: &str) -> Option<String> {
    parent
        .descendants()
        .filter(|node| node.is_element() && node.tag_name().name() == local_name)
        .find_map(|node| {
            let text: String =
                node.descendants().filter_map(|text| text.is_text().then(|| text.text()).flatten()).collect();
            let text = text.trim();
            (!text.is_empty()).then(|| text.to_owned())
        })
}

// Text as XML element content: the markup characters escaped, the control characters XML cannot hold left out.
fn escape(text: &str) -> String {
    let mut escaped = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '\t' | '\n' | '\r' => escaped.push(c),
            c if c.is_control() => {}
            c => escaped.push(c),
        }
    }
    escaped
}

fn unreadable(e: &roxmltree::Error) -> GatewayError {
    GatewayError::Failed(io::Error::new(io::ErrorKind::InvalidData, format!("unreadable answer: {e}")))
}
