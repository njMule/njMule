// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our view of the Kad network: a binary tree of zones by distance from our id, whose leaves hold up to [`K`]
//! contacts. The tree is complete down to [`KBASE`] levels; deeper, only the zones closest to us split, so we know our
//! neighbourhood best. Old contacts are never pushed out by new ones. The contacts live in one map by id; the zones
//! hold their ids.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::time::Instant;

use rand::{Rng, RngExt};

use crate::kad::kad_contact::KadContact;
use crate::kad::uint128::UInt128;
use crate::persist::format::nodes_dat::UdpKey;

/// Contacts per leaf.
pub const K: usize = 10;
/// Levels down to which every zone may split.
pub const KBASE: u32 = 4;
/// Zones with an index below this may split at any level.
pub const KK: u128 = 5;

const MAX_LEVEL: u32 = 127;
const PER_SUBNET: u32 = 10;
const PER_SUBNET_IN_BIN: usize = 2;
const SUBNET: u32 = 0xFFFF_FF00;

/// The outcome of adding a contact.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Added,
    Updated,
    Refused,
}

/// A zone: a leaf with a bin of contacts, or two halves, the one nearer us first.
#[derive(Debug)]
pub struct Zone {
    level: u32,
    index: UInt128,
    bin: Vec<UInt128>,
    halves: Option<Box<[Zone; 2]>>,
    next_big_timer: Option<Instant>,
}

impl Zone {
    fn new(level: u32, index: UInt128) -> Self {
        Self { level, index, bin: Vec::new(), halves: None, next_big_timer: None }
    }

    pub fn is_leaf(&self) -> bool {
        self.halves.is_none()
    }

    pub fn level(&self) -> u32 {
        self.level
    }

    pub fn index(&self) -> UInt128 {
        self.index
    }

    /// The ids of the contacts of a leaf, the one that answered last at the end.
    pub fn contacts(&self) -> &[UInt128] {
        &self.bin
    }

    pub fn next_big_timer(&self) -> Option<Instant> {
        self.next_big_timer
    }

    pub fn set_next_big_timer(&mut self, when: Instant) {
        self.next_big_timer = Some(when);
    }

    /// Whether the zone deserves a random lookup to find more contacts for it.
    pub fn wants_contacts(&self) -> bool {
        is_small(self.index) || self.level < KBASE || K.saturating_sub(self.bin.len()) >= 8
    }

    // The half on the side of `distance`.
    fn half(&self, distance: UInt128) -> Option<&Zone> {
        let halves = self.halves.as_deref()?;
        halves.get(usize::from(distance.bit(self.level)))
    }
}

pub struct RoutingTable {
    own: UInt128,
    lan: Box<dyn Fn(u32) -> bool + Send>,
    root: Zone,
    contacts: HashMap<UInt128, KadContact>,
    by_ip: HashMap<u32, UInt128>,
    per_subnet: HashMap<u32, u32>,
}

impl RoutingTable {
    /// `lan` tells the addresses (host order) exempt from the subnet limits.
    pub fn new(own: UInt128, lan: impl Fn(u32) -> bool + Send + 'static) -> Self {
        Self {
            own,
            lan: Box::new(lan),
            root: Zone::new(0, UInt128::ZERO),
            contacts: HashMap::new(),
            by_ip: HashMap::new(),
            per_subnet: HashMap::new(),
        }
    }

    pub fn own(&self) -> UInt128 {
        self.own
    }

    pub fn size(&self) -> usize {
        self.contacts.len()
    }

    pub fn get(&self, id: &UInt128) -> Option<&KadContact> {
        self.contacts.get(id)
    }

    pub fn get_mut(&mut self, id: &UInt128) -> Option<&mut KadContact> {
        self.contacts.get_mut(id)
    }

    pub fn by_address(&self, ip: u32) -> Option<&KadContact> {
        self.by_ip.get(&ip).and_then(|id| self.contacts.get(id))
    }

    pub fn all(&self) -> impl Iterator<Item = &KadContact> {
        self.contacts.values()
    }

    /// How many nodes the network holds, as the leaves tell: one of the first levels is taken full; a deeper one from
    /// the contacts of the zone three levels up, scaled to the whole tree and by `firewalled`, for the nodes no one can
    /// take as a contact. The largest estimate counts.
    pub fn estimated_nodes(&self, firewalled: f64) -> u64 {
        estimate(&self.root, &mut Vec::new(), firewalled)
    }

    /// The share of our contacts that speak `version` or later, 0 without contacts.
    pub fn share_from(&self, version: u8) -> f64 {
        if self.contacts.is_empty() {
            return 0.0;
        }
        let recent = self.contacts.values().filter(|contact| contact.version() >= version).count();
        recent as f64 / self.contacts.len() as f64
    }

    /// Adds `contact`, or updates the known contact with its id when `update` is set; `sender_key` is the key the
    /// contact gave us in this packet, if any.
    pub fn add(
        &mut self,
        mut contact: KadContact,
        update: bool,
        sender_key: UdpKey,
        our_ip: u32,
        now: Instant,
    ) -> Outcome {
        if contact.id() == self.own || contact.version() <= 1 || contact.udp_port() == 0 {
            return Outcome::Refused;
        }
        if contact.udp_port() == 53 && contact.version() <= 5 {
            return Outcome::Refused;
        }
        if self.contacts.contains_key(&contact.id()) {
            return if update { self.update(&contact, sender_key, our_ip, now) } else { Outcome::Refused };
        }
        let distance = contact.id() ^ self.own;
        loop {
            let leaf = leaf(&self.root, distance);
            if leaf.bin.len() < K {
                if !self.fits(contact.ip(), leaf, None) {
                    return Outcome::Refused;
                }
                if sender_key.key != 0 {
                    contact.set_udp_key(sender_key);
                }
                leaf_mut(&mut self.root, distance).bin.push(contact.id());
                self.index(contact);
                return Outcome::Added;
            }
            if !can_split(leaf) {
                return Outcome::Refused;
            }
            let own = self.own;
            split(leaf_mut(&mut self.root, distance), own);
        }
    }

    fn update(&mut self, fresh: &KadContact, sender_key: UdpKey, our_ip: u32, now: Instant) -> Outcome {
        let Some(known) = self.contacts.get(&fresh.id()) else {
            return Outcome::Refused;
        };
        // A key bound to our address proves who the contact is: another key is someone else.
        let stored = known.udp_key();
        if stored.key != 0 && stored.ip == our_ip && sender_key.key != stored.key {
            return Outcome::Refused;
        }
        if known.version() < 6
            && known.hello_received()
            && (known.ip() != fresh.ip()
                || known.udp_port() != fresh.udp_port()
                || known.tcp_port() != fresh.tcp_port()
                || known.version() != fresh.version())
        {
            return Outcome::Refused;
        }
        if fresh.version() < known.version() {
            return Outcome::Refused;
        }
        let id = fresh.id();
        let old_ip = known.ip();
        if fresh.ip() != old_ip {
            let leaf = leaf(&self.root, id ^ self.own);
            if !self.fits(fresh.ip(), leaf, Some(id)) {
                return Outcome::Refused;
            }
            self.unindex_address(id, old_ip);
            self.by_ip.insert(fresh.ip(), id);
            let subnet = self.per_subnet.entry(fresh.ip() & SUBNET).or_insert(0);
            *subnet = subnet.saturating_add(1);
        }
        if let Some(known) = self.contacts.get_mut(&id) {
            known.move_to(fresh.ip(), fresh.udp_port());
            known.update(fresh.tcp_port(), fresh.version());
            if sender_key.key != 0 {
                known.set_udp_key(sender_key);
            }
            if fresh.is_verified() {
                known.set_verified(true);
            }
        }
        self.alive(&id, now);
        Outcome::Updated
    }

    /// The contact answered: it ages and moves to the end of its bin.
    pub fn alive(&mut self, id: &UInt128, now: Instant) {
        let Some(contact) = self.contacts.get_mut(id) else {
            return;
        };
        contact.alive(now);
        let bin = &mut leaf_mut(&mut self.root, *id ^ self.own).bin;
        if let Some(at) = bin.iter().position(|kept| kept == id) {
            bin.remove(at);
            bin.push(*id);
        }
    }

    pub fn remove(&mut self, id: &UInt128) -> Option<KadContact> {
        let bin = &mut leaf_mut(&mut self.root, *id ^ self.own).bin;
        let at = bin.iter().position(|kept| kept == id)?;
        bin.remove(at);
        let contact = self.contacts.remove(id)?;
        self.unindex_address(*id, contact.ip());
        Some(contact)
    }

    /// Whether a lookup may ask a contact another node told it of, though the table does not take it: no verified
    /// contact has its id at another address, and it would fit the address limits.
    pub fn is_acceptable(&self, id: &UInt128, ip: u32, udp_port: u16) -> bool {
        if let Some(known) = self.contacts.get(id) {
            return !known.is_verified() || (known.ip() == ip && known.udp_port() == udp_port);
        }
        !self.by_ip.contains_key(&ip)
            && ((self.lan)(ip) || self.per_subnet.get(&(ip & SUBNET)).copied().unwrap_or(0) < PER_SUBNET)
    }

    /// Up to `count` verified contacts of type at most `max_type`, closest first.
    pub fn closest(&self, target: UInt128, max_type: u8, count: usize) -> Vec<&KadContact> {
        let mut out = Vec::with_capacity(count.min(self.contacts.len()));
        if count > 0 {
            self.closest_in(&self.root, target ^ self.own, target, max_type, count, &mut out);
        }
        out
    }

    // Every contact of a zone's half on the side of the target is closer to it than any of the other half, so the
    // zones are walked from the target's side and only the bins needed are sorted.
    fn closest_in<'a>(
        &'a self,
        zone: &Zone,
        distance: UInt128,
        target: UInt128,
        max_type: u8,
        count: usize,
        out: &mut Vec<&'a KadContact>,
    ) {
        let Some(halves) = zone.halves.as_deref() else {
            let mut usable: Vec<&KadContact> = zone
                .bin
                .iter()
                .filter_map(|id| self.contacts.get(id))
                .filter(|contact| contact.is_verified() && contact.contact_type() <= max_type)
                .collect();
            usable.sort_by(|a, b| compare_distances(a.id(), b.id(), target));
            out.extend(usable.into_iter().take(count.saturating_sub(out.len())));
            return;
        };
        let [near, far] = halves;
        let (first, second) = if distance.bit(zone.level) == 1 { (far, near) } else { (near, far) };
        self.closest_in(first, distance, target, max_type, count, out);
        if out.len() < count {
            self.closest_in(second, distance, target, max_type, count, out);
        }
    }

    /// Up to `count` contacts of the zones near the root that `wanted` accepts: to start a node from.
    pub fn bootstrap_contacts(
        &self,
        count: usize,
        wanted: impl Fn(&KadContact) -> bool,
        random: &mut impl Rng,
    ) -> Vec<&KadContact> {
        let mut found = Vec::new();
        self.collect(&self.root, 5, &wanted, random, &mut found);
        found.truncate(count);
        found
    }

    fn collect<'a>(
        &'a self,
        zone: &Zone,
        depth: u32,
        wanted: &impl Fn(&KadContact) -> bool,
        random: &mut impl Rng,
        out: &mut Vec<&'a KadContact>,
    ) {
        let Some(halves) = zone.halves.as_deref() else {
            out.extend(zone.bin.iter().filter_map(|id| self.contacts.get(id)).filter(|contact| wanted(contact)));
            return;
        };
        let [near, far] = halves;
        if depth == 0 {
            let half = if random.random_bool(0.5) { near } else { far };
            self.collect(half, 0, wanted, random, out);
            return;
        }
        self.collect(near, depth.saturating_sub(1), wanted, random, out);
        self.collect(far, depth.saturating_sub(1), wanted, random, out);
    }

    pub fn leaves(&self) -> Vec<&Zone> {
        let mut leaves = Vec::new();
        let mut pending = vec![&self.root];
        while let Some(zone) = pending.pop() {
            match zone.halves.as_deref() {
                None => leaves.push(zone),
                Some([near, far]) => {
                    pending.push(far);
                    pending.push(near);
                }
            }
        }
        leaves
    }

    pub fn leaves_mut(&mut self) -> Vec<&mut Zone> {
        let mut leaves = Vec::new();
        let mut pending = vec![&mut self.root];
        while let Some(zone) = pending.pop() {
            match zone.halves {
                Some(ref mut halves) => {
                    let [near, far] = &mut **halves;
                    pending.push(far);
                    pending.push(near);
                }
                None => leaves.push(zone),
            }
        }
        leaves
    }

    /// A random id inside `zone`.
    pub fn random_id_in(&self, zone: &Zone, random: &mut impl Rng) -> UInt128 {
        self.random_id_in_zone(zone.level, zone.index, random)
    }

    /// A random id inside the zone of `level` and `index`.
    pub fn random_id_in_zone(&self, level: u32, index: UInt128, random: &mut impl Rng) -> UInt128 {
        let mut distance = UInt128::random(random);
        if level > 0 {
            let free = UInt128::BITS.saturating_sub(level);
            let prefix = index.0.checked_shl(free).unwrap_or(0);
            let mask = u128::MAX.checked_shl(free).unwrap_or(0);
            distance = UInt128(prefix | (distance.0 & !mask));
        }
        distance ^ self.own
    }

    /// Moves a contact to the end of its bin, as one checked last.
    pub fn to_back(&mut self, id: &UInt128) {
        let bin = &mut leaf_mut(&mut self.root, *id ^ self.own).bin;
        if let Some(at) = bin.iter().position(|kept| kept == id) {
            bin.remove(at);
            bin.push(*id);
        }
    }

    /// Merges sibling leaves that together hold fewer than half a bin.
    pub fn consolidate(&mut self) {
        consolidate(&mut self.root);
    }

    // One contact per address in the whole table, and few per /24 unless on a LAN.
    fn fits(&self, ip: u32, leaf: &Zone, moving: Option<UInt128>) -> bool {
        if self.by_ip.get(&ip).is_some_and(|id| Some(*id) != moving) {
            return false;
        }
        if (self.lan)(ip) {
            return true;
        }
        let subnet = ip & SUBNET;
        let mut in_subnet = self.per_subnet.get(&subnet).copied().unwrap_or(0);
        // A contact moving inside its own /24 leaves its place there.
        if moving.and_then(|id| self.contacts.get(&id)).is_some_and(|contact| contact.ip() & SUBNET == subnet) {
            in_subnet = in_subnet.saturating_sub(1);
        }
        if in_subnet >= PER_SUBNET {
            return false;
        }
        let in_bin = leaf
            .bin
            .iter()
            .filter(|id| Some(**id) != moving)
            .filter_map(|id| self.contacts.get(id))
            .filter(|contact| contact.ip() & SUBNET == subnet)
            .count();
        in_bin < PER_SUBNET_IN_BIN
    }

    fn index(&mut self, contact: KadContact) {
        let subnet = self.per_subnet.entry(contact.ip() & SUBNET).or_insert(0);
        *subnet = subnet.saturating_add(1);
        self.by_ip.insert(contact.ip(), contact.id());
        self.contacts.insert(contact.id(), contact);
    }

    fn unindex_address(&mut self, id: UInt128, ip: u32) {
        if self.by_ip.get(&ip) == Some(&id) {
            self.by_ip.remove(&ip);
        }
        if let Some(count) = self.per_subnet.get_mut(&(ip & SUBNET)) {
            *count = count.saturating_sub(1);
            if *count == 0 {
                self.per_subnet.remove(&(ip & SUBNET));
            }
        }
    }
}

/// Compares the distances of two ids to the target.
pub fn compare_distances(a: UInt128, b: UInt128, target: UInt128) -> Ordering {
    (a ^ target).cmp(&(b ^ target))
}

fn estimate<'a>(zone: &'a Zone, above: &mut Vec<&'a Zone>, firewalled: f64) -> u64 {
    if let Some([near, far]) = zone.halves.as_deref() {
        above.push(zone);
        let most = estimate(near, above, firewalled).max(estimate(far, above, firewalled));
        above.pop();
        return most;
    }
    if zone.level < KBASE {
        return (1u64 << zone.level).saturating_mul(K as u64);
    }
    let Some(sample) = above.get(usize::try_from(zone.level.saturating_sub(3)).unwrap_or(usize::MAX)) else {
        return 0;
    };
    let scale = 2f64.powi(i32::try_from(zone.level.saturating_sub(3)).unwrap_or(i32::MAX));
    (scale * contacts_under(sample) as f64 * firewalled) as u64
}

fn contacts_under(zone: &Zone) -> usize {
    match zone.halves.as_deref() {
        None => zone.bin.len(),
        Some([near, far]) => contacts_under(near).saturating_add(contacts_under(far)),
    }
}

fn leaf(root: &Zone, distance: UInt128) -> &Zone {
    let mut zone = root;
    while let Some(half) = zone.half(distance) {
        zone = half;
    }
    zone
}

// The match is on the field itself, so that only the arm that descends borrows it.
fn leaf_mut(root: &mut Zone, distance: UInt128) -> &mut Zone {
    let mut zone = root;
    loop {
        let side = distance.bit(zone.level);
        match zone.halves {
            Some(ref mut halves) => {
                let [near, far] = &mut **halves;
                zone = if side == 0 { near } else { far };
            }
            None => return zone,
        }
    }
}

fn can_split(zone: &Zone) -> bool {
    zone.level < MAX_LEVEL && (is_small(zone.index) || zone.level < KBASE) && zone.bin.len() == K
}

fn split(zone: &mut Zone, own: UInt128) {
    let level = zone.level.saturating_add(1);
    let near_index = UInt128(zone.index.0 << 1);
    let far_index = UInt128(zone.index.0 << 1 | 1);
    let mut halves = [Zone::new(level, near_index), Zone::new(level, far_index)];
    for id in zone.bin.drain(..) {
        let side = usize::from((id ^ own).bit(zone.level));
        if let Some(half) = halves.get_mut(side) {
            half.bin.push(id);
        }
    }
    zone.halves = Some(Box::new(halves));
}

fn consolidate(zone: &mut Zone) {
    let Some(halves) = zone.halves.as_deref_mut() else {
        return;
    };
    let [near, far] = halves;
    consolidate(near);
    consolidate(far);
    if near.is_leaf() && far.is_leaf() && near.bin.len().saturating_add(far.bin.len()) < K / 2 {
        let mut bin = std::mem::take(&mut near.bin);
        bin.append(&mut far.bin);
        zone.bin = bin;
        zone.halves = None;
    }
}

fn is_small(index: UInt128) -> bool {
    index.0 < KK
}
