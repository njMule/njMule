// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's RoutingTableClosestTest, under its name: the closest contacts the table walks to are those a full
//! sort finds.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::Instant;

use njmule_engine::kad::kad_contact::{self, KadContact};
use njmule_engine::kad::routing_table::RoutingTable;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::nodes_dat::UdpKey;
use proptest::prelude::*;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

const PORT: u16 = 4672;

struct Closest {
    random: StdRng,
    table: RoutingTable,
    target: UInt128,
    max_type: u8,
    closest: Vec<UInt128>,
}

impl Closest {
    fn given_a_table_of_random_contacts(seed: u64, contacts: u32) -> Self {
        let mut random = StdRng::seed_from_u64(seed);
        let now = Instant::now();
        let mut table = RoutingTable::new(UInt128(random.random()), |_| false);
        for at in 0..contacts {
            let ip = u32::from_be_bytes([
                u8::try_from(20 + at / 200).unwrap(),
                u8::try_from(at % 200).unwrap(),
                random.random_range(0..4),
                1,
            ]);
            let mut contact = KadContact::new(UInt128(random.random()), ip, PORT, 4662, 8, now);
            contact.set_verified(random.random_range(0..5) > 0);
            if random.random_bool(0.5) {
                contact.alive(now);
            }
            table.add(contact, false, UdpKey::default(), 0, now);
        }
        if random.random_bool(0.5) {
            table.consolidate();
        }
        let max_type = if random.random_bool(0.5) { kad_contact::NEW } else { 2 };
        Self { random, table, target: UInt128::ZERO, max_type, closest: Vec::new() }
    }

    fn given_a_random_target_near_or_far_from_us(&mut self) {
        let own = self.table.own();
        self.target = match self.random.random_range(0..3) {
            0 => own,
            1 => own ^ UInt128(u128::from(self.random.random::<u64>())),
            _ => UInt128(self.random.random()),
        };
    }

    fn when_the_closest_are_asked_for(&mut self, count: usize) {
        self.closest = self.table.closest(self.target, self.max_type, count).iter().map(|c| c.id()).collect();
    }

    fn then_they_are_the_first_of_a_full_sort(&self, count: usize) {
        let mut all: Vec<UInt128> = self
            .table
            .all()
            .filter(|c| c.is_verified() && c.contact_type() <= self.max_type)
            .map(KadContact::id)
            .collect();
        all.sort_by_key(|id| *id ^ self.target);
        all.truncate(count);
        assert_eq!(self.closest, all);
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(200))]

    #[test]
    fn should_find_the_same_contacts_as_a_full_sort(seed: u64, contacts in 0u32..=300, count in 0usize..=60) {
        let mut closest = Closest::given_a_table_of_random_contacts(seed, contacts);
        closest.given_a_random_target_near_or_far_from_us();

        closest.when_the_closest_are_asked_for(count);

        closest.then_they_are_the_first_of_a_full_sort(count);
    }
}
