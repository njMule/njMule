// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A node of our routing table. Its type ages from 3 (new) to 0 (known for hours) while it answers, and climbs to 4
//! (to be removed) while it does not.

use std::time::{Duration, Instant};

use crate::kad::uint128::UInt128;
use crate::persist::format::nodes_dat::{self, UdpKey};
use crate::protocol::kad::kad_messages;

/// Type of a new contact.
pub const NEW: u8 = 3;
/// Type of a contact that stopped answering.
pub const DEAD: u8 = 4;

const MINUTE: Duration = Duration::from_secs(60);
const HOUR: Duration = Duration::from_secs(3600);
const HALF_HOUR: Duration = Duration::from_secs(1800);
// A contact checked again within this keeps its type.
const RECHECK: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KadContact {
    id: UInt128,
    ip: u32,
    udp_port: u16,
    tcp_port: u16,
    version: u8,
    udp_key: UdpKey,
    verified: bool,
    contact_type: u8,
    created: Instant,
    expires: Option<Instant>,
    type_set: Option<Instant>,
    in_use: u32,
    hello_received: bool,
    heard: bool,
}

impl KadContact {
    pub fn new(id: UInt128, ip: u32, udp_port: u16, tcp_port: u16, version: u8, now: Instant) -> Self {
        Self {
            id,
            ip,
            udp_port,
            tcp_port,
            version,
            udp_key: UdpKey::default(),
            verified: false,
            contact_type: NEW,
            created: now,
            expires: None,
            type_set: None,
            in_use: 0,
            hello_received: false,
            heard: false,
        }
    }

    pub fn of(contact: &kad_messages::Contact, now: Instant) -> Self {
        Self::new(contact.id, contact.ip, contact.udp_port, contact.tcp_port, contact.version, now)
    }

    pub fn id(&self) -> UInt128 {
        self.id
    }

    pub fn ip(&self) -> u32 {
        self.ip
    }

    pub fn udp_port(&self) -> u16 {
        self.udp_port
    }

    pub fn tcp_port(&self) -> u16 {
        self.tcp_port
    }

    pub fn version(&self) -> u8 {
        self.version
    }

    pub fn udp_key(&self) -> UdpKey {
        self.udp_key
    }

    pub fn is_verified(&self) -> bool {
        self.verified
    }

    pub fn contact_type(&self) -> u8 {
        self.contact_type
    }

    pub fn expires(&self) -> Option<Instant> {
        self.expires
    }

    pub fn hello_received(&self) -> bool {
        self.hello_received
    }

    /// Whether a packet came from the contact itself since it entered the table: it was not only loaded or told of.
    pub fn was_heard_from(&self) -> bool {
        self.heard
    }

    pub fn heard_from(&mut self) {
        self.heard = true;
    }

    pub fn in_use(&self) -> bool {
        self.in_use > 0
    }

    /// A lookup takes the contact; it stays until released.
    pub fn hold(&mut self) {
        self.in_use = self.in_use.saturating_add(1);
    }

    pub fn release(&mut self) {
        self.in_use = self.in_use.saturating_sub(1);
    }

    pub fn set_verified(&mut self, verified: bool) {
        self.verified = verified;
    }

    pub fn set_udp_key(&mut self, key: UdpKey) {
        self.udp_key = key;
    }

    pub fn set_hello_received(&mut self) {
        self.hello_received = true;
    }

    // Only the routing table moves a contact: it keeps its address index.
    pub(crate) fn move_to(&mut self, ip: u32, udp_port: u16) {
        if ip != self.ip {
            self.verified = false;
        }
        self.ip = ip;
        self.udp_port = udp_port;
    }

    pub(crate) fn update(&mut self, tcp_port: u16, version: u8) {
        self.tcp_port = tcp_port;
        self.version = self.version.max(version);
    }

    /// The contact answered: its type falls with its age.
    pub fn alive(&mut self, now: Instant) {
        let age = now.saturating_duration_since(self.created);
        let (contact_type, lasts) = if age < HOUR {
            (2, HOUR)
        } else if age < HOUR.saturating_mul(2) {
            (1, HOUR.saturating_add(HALF_HOUR))
        } else {
            (0, HOUR.saturating_mul(2))
        };
        self.contact_type = contact_type;
        self.expires = now.checked_add(lasts);
        self.type_set = Some(now);
        self.heard = true;
    }

    /// We are about to check the contact: its type climbs until it answers.
    pub fn checking(&mut self, now: Instant) {
        let recent = self.type_set.is_some_and(|set| now.saturating_duration_since(set) < RECHECK);
        if recent || self.contact_type == DEAD {
            return;
        }
        self.type_set = Some(now);
        self.contact_type = self.contact_type.saturating_add(1);
        self.expires = now.checked_add(MINUTE.saturating_mul(2));
    }

    pub fn is_expired(&self, now: Instant) -> bool {
        self.expires.is_some_and(|expires| now >= expires)
    }

    pub fn touch_expires(&mut self, now: Instant) {
        if self.expires.is_none() {
            self.expires = Some(now);
        }
    }

    pub fn message(&self) -> kad_messages::Contact {
        kad_messages::Contact {
            id: self.id,
            ip: self.ip,
            udp_port: self.udp_port,
            tcp_port: self.tcp_port,
            version: self.version,
        }
    }

    pub fn record(&self) -> nodes_dat::Contact {
        nodes_dat::Contact {
            id: self.id,
            ip: self.ip,
            udp_port: self.udp_port,
            tcp_port: self.tcp_port,
            version: self.version,
            contact_type: self.contact_type,
            udp_key: self.udp_key,
            verified: self.verified,
        }
    }
}
