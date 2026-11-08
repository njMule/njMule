// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The reverse proxies trusted to tell who their client is: a request coming from one of them has its client read from
//! its X-Forwarded-For header, right to left past the trusted proxies. A request from anywhere else is its own client,
//! whatever it says, and no proxy is trusted unless named.

use std::net::IpAddr;

use ipnet::IpNet;

/// The most addresses an X-Forwarded-For header is read for: what a client adds beyond them is left alone.
pub const MAX_HOPS: usize = 32;
// The digits of a prefix length, at most.
const MAX_PREFIX_DIGITS: usize = 3;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TrustedProxies {
    // The first and last address of each entry, both of one family.
    ranges: Vec<(IpAddr, IpAddr)>,
}

impl TrustedProxies {
    /// The proxies of `entries`: addresses, CIDR blocks or ranges, IPv4 or IPv6, as [`entry`] keeps them.
    pub fn new(entries: &[String]) -> Result<Self, String> {
        let ranges = entries.iter().map(|entry| range(entry.trim()).ok_or_else(|| refused(entry)));
        Ok(Self { ranges: ranges.collect::<Result<_, _>>()? })
    }

    pub fn is_empty(&self) -> bool {
        self.ranges.is_empty()
    }

    /// The client of a request that came from `remote`, with these X-Forwarded-For headers.
    pub fn client(&self, remote: IpAddr, forwarded_for: &[String]) -> IpAddr {
        let remote = remote.to_canonical();
        if !self.contains(remote) {
            return remote;
        }
        let hops: Vec<&str> = forwarded_for.iter().flat_map(|header| header.split(',')).map(str::trim).collect();
        let mut client = remote;
        for hop in hops.iter().rev().take(MAX_HOPS) {
            // Only addresses: a name or a port the proxy did not write ends the trust.
            let Ok(address) = hop.parse::<IpAddr>() else {
                return client;
            };
            client = address;
            if !self.contains(client) {
                return client;
            }
        }
        client
    }

    fn contains(&self, address: IpAddr) -> bool {
        self.ranges
            .iter()
            .any(|(first, last)| first.is_ipv4() == address.is_ipv4() && *first <= address && address <= *last)
    }
}

/// `text` as a proxy is named: an address (`172.17.0.1`, `::1`), a CIDR block (`10.0.0.0/8`) or a range
/// (`10.0.0.1-10.0.0.9`), without white space; why not when it is none of those, a host name included.
pub fn entry(text: &str) -> Result<String, String> {
    let entry = text.trim();
    range(entry).map(|_| entry.to_owned()).ok_or_else(|| refused(text))
}

// Literal addresses only, which no name lookup takes for a proxy.
fn range(entry: &str) -> Option<(IpAddr, IpAddr)> {
    if let Some((address, bits)) = entry.split_once('/') {
        let address: IpAddr = address.parse().ok()?;
        if bits.is_empty() || bits.len() > MAX_PREFIX_DIGITS || !bits.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let block = IpNet::new(address, bits.parse().ok()?).ok()?;
        Some((block.network(), block.broadcast()))
    } else if let Some((first, last)) = entry.split_once('-') {
        let (first, last): (IpAddr, IpAddr) = (first.parse().ok()?, last.parse().ok()?);
        (first.is_ipv4() == last.is_ipv4() && first <= last).then_some((first, last))
    } else {
        let address: IpAddr = entry.parse().ok()?;
        Some((address, address))
    }
}

fn refused(text: &str) -> String {
    format!("not an address, a block or a range: {text}")
}
