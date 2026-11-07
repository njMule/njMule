// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's Ipv6RouteTest, under its names: each case of the parameterized test in turn.

use std::net::{IpAddr, Ipv6Addr, SocketAddr};

use njmule_engine::download::download_manager;

const THEIRS: Ipv6Addr = Ipv6Addr::new(0x2001, 0xdb8, 0, 0, 0, 0, 0, 7);
const PORT: u16 = 4662;

struct Route {
    we_have_ipv6: bool,
    theirs: Option<Ipv6Addr>,
    route: Option<SocketAddr>,
}

impl Route {
    fn given_we_have_ipv6(ours: bool) -> Self {
        Self { we_have_ipv6: ours, theirs: None, route: None }
    }

    fn given_the_client_gave_its_ipv6_address(&mut self, given: bool) {
        self.theirs = given.then_some(THEIRS);
    }

    fn when_the_route_is_chosen(&mut self, wanted: bool) {
        self.route = download_manager::ipv6_route(self.we_have_ipv6, self.theirs, PORT, wanted);
    }

    fn then_it_goes_over_ipv6(&self, expected: bool) {
        assert_eq!(self.route, expected.then_some(SocketAddr::new(IpAddr::V6(THEIRS), PORT)));
    }
}

#[test]
fn should_go_over_ipv6_when_both_have_it_and_ipv4_does_not_do() {
    for (ours, they_gave_it, ipv4_does_not_do, over_ipv6) in
        [(true, true, true, true), (false, true, true, false), (true, false, true, false), (true, true, false, false)]
    {
        let mut route = Route::given_we_have_ipv6(ours);
        route.given_the_client_gave_its_ipv6_address(they_gave_it);

        route.when_the_route_is_chosen(ipv4_does_not_do);

        route.then_it_goes_over_ipv6(over_ipv6);
    }
}
