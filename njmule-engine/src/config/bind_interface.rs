// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The network interface njMule's connections are bound to, as the preferences choose it: any (blank), an interface by
//! its name or display name, or an IPv4 address of this computer. A chosen interface that is not there leaves njMule
//! offline rather than reaching the network another way.
//!
//! Interfaces go by the names the system gives them: on Windows the name shown in the network settings ("Wi-Fi"), not
//! the names the Java engine makes up (`wlan1`), so a choice made there by such a name is to be made again.

use std::net::{IpAddr, Ipv4Addr};

use if_addrs::IfOperStatus;

use crate::util::white_space::strip;

/// A network interface of this computer that is up.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub name: String,
    pub display_name: String,
    pub loopback: bool,
    pub addresses: Vec<Ipv4Addr>,
}

/// What a choice binds to now.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Binding {
    /// Every interface.
    Any,
    /// The address of the chosen interface.
    Bound(Ipv4Addr),
    /// The chosen interface or address is not there now.
    Missing(String),
}

/// The interfaces that are up now, with their IPv4 addresses, in the order the system lists them; none when they cannot
/// be listed.
pub fn interfaces() -> Vec<Candidate> {
    let Ok(found) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    let mut candidates: Vec<Candidate> = Vec::new();
    // One entry for each address: an interface not known to be down, with its IPv4 addresses gathered.
    for entry in found.into_iter().filter(|entry| !is_down(&entry.oper_status)) {
        let position = match candidates.iter().position(|candidate| candidate.name == entry.name) {
            Some(position) => position,
            None => {
                candidates.push(Candidate {
                    display_name: entry.name.clone(),
                    name: entry.name.clone(),
                    loopback: false,
                    addresses: Vec::new(),
                });
                candidates.len().saturating_sub(1)
            }
        };
        if let (Some(candidate), IpAddr::V4(address)) = (candidates.get_mut(position), entry.ip()) {
            candidate.loopback |= address.is_loopback();
            candidate.addresses.push(address);
        }
    }
    candidates
}

// A tunnel that tells no state, as many VPNs do on Linux, counts as up.
fn is_down(status: &IfOperStatus) -> bool {
    matches!(status, IfOperStatus::Down | IfOperStatus::NotPresent | IfOperStatus::LowerLayerDown)
}

/// What `choice` binds to with the interfaces up now; a blank choice lists none.
pub fn resolve_now(choice: &str) -> Binding {
    if strip(choice).is_empty() {
        return Binding::Any;
    }
    resolve(choice, &interfaces())
}

/// The interfaces worth choosing among `available`: not the loopback, with an IPv4 address.
pub fn choices(available: &[Candidate]) -> Vec<&Candidate> {
    available.iter().filter(|candidate| !candidate.loopback && !candidate.addresses.is_empty()).collect()
}

/// What `choice` binds to among `available`.
pub fn resolve(choice: &str, available: &[Candidate]) -> Binding {
    let wanted = strip(choice);
    if wanted.is_empty() {
        return Binding::Any;
    }
    if let Some(address) = address(wanted) {
        return if available.iter().any(|candidate| candidate.addresses.contains(&address)) {
            Binding::Bound(address)
        } else {
            Binding::Missing(wanted.to_owned())
        };
    }
    available
        .iter()
        .filter(|candidate| candidate.name.to_lowercase() == wanted.to_lowercase() || candidate.display_name == wanted)
        .flat_map(|candidate| &candidate.addresses)
        // A link-local address is what an interface takes while it waits for its own.
        .find(|address| !address.is_link_local())
        .map_or_else(|| Binding::Missing(wanted.to_owned()), |address| Binding::Bound(*address))
}

/// Whether `choice` reads as an IPv4 address, in four decimal parts, rather than an interface name.
pub fn is_address(choice: &str) -> bool {
    address(strip(choice)).is_some()
}

// Four parts of one to three decimal digits; a leading zero is no octal sign, as in the Java engine. The standard
// library refuses leading zeros, and takes no shortened form such as 10.8.0, which nobody means as an address here.
fn address(text: &str) -> Option<Ipv4Addr> {
    let mut octets = [0_u8; 4];
    let mut parts = text.split('.');
    for octet in &mut octets {
        let part = parts.next()?;
        if part.is_empty() || part.len() > 3 || !part.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        *octet = part.parse().ok()?;
    }
    parts.next().is_none().then_some(Ipv4Addr::from(octets))
}
