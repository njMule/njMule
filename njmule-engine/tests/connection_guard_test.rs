// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ConnectionGuardTest, under its names. A permit is released when it is closed or dropped, so the
//! connections still open keep theirs.

#![allow(clippy::unwrap_used, clippy::panic)]

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use njmule_engine::persist::format::ip_filter_dat::Range;
use njmule_engine::security::connection_guard::{Admission, ConnectionGuard, Limits, Permit, Refusal};
use njmule_engine::security::ip_filter::{self, IpFilter};

const PEER: u32 = 0x0102_0304;
const OTHER: u32 = 0x0506_0708;
const SERVER: u32 = 0x0707_0707;
const NINE: u32 = 0x0909_0909;

fn address(text: &str) -> IpAddr {
    text.parse().unwrap()
}

fn home() -> IpAddr {
    address("2001:db8:1:2::1")
}

fn same_home() -> IpAddr {
    address("2001:db8:1:2:ffff:eeee:dddd:7")
}

fn neighbour() -> IpAddr {
    address("2001:db8:1:3::1")
}

fn limits(total: u32, per_address: u32, accepts: u32, window: Duration) -> Limits {
    Limits::new(total, per_address, accepts, window).unwrap()
}

fn seconds(seconds: u64) -> Duration {
    Duration::from_secs(seconds)
}

/// A clock the test moves.
#[derive(Clone, Default)]
struct FakeTicker(Arc<AtomicU64>);

impl FakeTicker {
    fn advance(&self, by: Duration) {
        self.0.fetch_add(u64::try_from(by.as_nanos()).unwrap(), Ordering::SeqCst);
    }

    fn read(&self) -> impl Fn() -> Duration + Send + Sync + 'static {
        let nanos = Arc::clone(&self.0);
        move || Duration::from_nanos(nanos.load(Ordering::SeqCst))
    }
}

fn guard(ticker: &FakeTicker, limits: Limits) -> ConnectionGuard {
    ConnectionGuard::new(|| Arc::new(IpFilter::empty()), |_| false, |_| false, move || limits, ticker.read())
}

fn filtering(ip: u32) -> Arc<IpFilter> {
    let range = Range { start: ip, end: ip, level: 0, description: "test".to_owned() };
    Arc::new(IpFilter::of(&[range], ip_filter::DEFAULT_LEVEL))
}

fn permit(admission: Admission) -> Permit {
    match admission {
        Admission::Admitted(permit) => permit,
        Admission::Refused(reason) => panic!("refused: {reason:?}"),
    }
}

fn refused(admission: &Admission) -> Option<Refusal> {
    match admission {
        Admission::Admitted(_) => None,
        Admission::Refused(reason) => Some(*reason),
    }
}

#[test]
fn should_refuse_filtered_addresses_first() {
    let filter = filtering(PEER);
    let guard = ConnectionGuard::new(
        move || Arc::clone(&filter),
        |_| false,
        |_| false,
        || Limits::DEFAULT,
        FakeTicker::default().read(),
    );

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), Some(Refusal::Filtered));
}

#[test]
fn should_admit_addresses_the_filter_lets_through() {
    let filter = filtering(PEER);
    let guard = ConnectionGuard::new(
        move || Arc::clone(&filter),
        |_| false,
        |_| false,
        || Limits::DEFAULT,
        FakeTicker::default().read(),
    );

    let admission = guard.admit_v4(OTHER);

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_cap_connections_per_address_and_in_total_until_permits_are_released() {
    let guard = guard(&FakeTicker::default(), limits(3, 2, 100, seconds(5)));
    let first = permit(guard.admit_v4(PEER));
    let _second = permit(guard.admit_v4(PEER));
    assert_eq!(refused(&guard.admit_v4(PEER)), Some(Refusal::TooManyFromAddress));
    let _other = permit(guard.admit_v4(OTHER));
    assert_eq!(refused(&guard.admit_v4(NINE)), Some(Refusal::TooManyConnections));
    first.close();
    first.close();
    assert_eq!(guard.open_connections(), 2);

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_read_the_limits_at_each_admission() {
    let settings = Arc::new(Mutex::new(limits(1, 5, 100, seconds(5))));
    let read = Arc::clone(&settings);
    let guard = ConnectionGuard::new(
        || Arc::new(IpFilter::empty()),
        |_| false,
        |_| false,
        move || *read.lock().unwrap(),
        FakeTicker::default().read(),
    );
    let _peer = permit(guard.admit_v4(PEER));
    assert_eq!(refused(&guard.admit_v4(OTHER)), Some(Refusal::TooManyConnections));
    *settings.lock().unwrap() = limits(2, 5, 100, seconds(5));

    let admission = guard.admit_v4(OTHER);

    assert_eq!(refused(&admission), None, "the settings changed");
}

#[test]
fn should_let_the_server_testing_us_pass_the_rate_and_total_limits_but_not_the_filter() {
    let filter = filtering(PEER);
    let exempt = HashSet::from([SERVER, PEER]);
    let guard = ConnectionGuard::new(
        move || Arc::clone(&filter),
        move |ip| exempt.contains(&ip),
        |_| false,
        || limits(1, 5, 1, seconds(5)),
        FakeTicker::default().read(),
    );
    let _other = permit(guard.admit_v4(OTHER));
    assert_eq!(refused(&guard.admit_v4(NINE)), Some(Refusal::AcceptRate));
    let _server = permit(guard.admit_v4(SERVER));

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), Some(Refusal::Filtered));
}

fn banning(banned: &Arc<Mutex<HashSet<IpAddr>>>) -> ConnectionGuard {
    let read = Arc::clone(banned);
    ConnectionGuard::new(
        || Arc::new(IpFilter::empty()),
        |_| false,
        move |address| read.lock().unwrap().contains(&address),
        || Limits::DEFAULT,
        FakeTicker::default().read(),
    )
}

#[test]
fn should_refuse_banned_addresses_as_long_as_the_ban_lasts() {
    let banned = Arc::new(Mutex::new(HashSet::from([IpAddr::V4(Ipv4Addr::from(PEER))])));
    let guard = banning(&banned);
    assert_eq!(refused(&guard.admit_v4(PEER)), Some(Refusal::Banned));
    let _other = permit(guard.admit_v4(OTHER));
    banned.lock().unwrap().clear();

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_count_the_addresses_of_an_ipv6_slash64_as_one() {
    let guard = guard(&FakeTicker::default(), limits(10, 1, 100, seconds(5)));
    let _home = permit(guard.admit(home()));

    let admission = guard.admit(same_home());

    assert_eq!(refused(&admission), Some(Refusal::TooManyFromAddress));
}

#[test]
fn should_count_the_next_ipv6_slash64_apart() {
    let guard = guard(&FakeTicker::default(), limits(10, 1, 100, seconds(5)));
    let _home = permit(guard.admit(home()));

    let admission = guard.admit(neighbour());

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_give_the_slot_of_an_ipv6_slash64_back_when_its_connection_closes() {
    let guard = guard(&FakeTicker::default(), limits(10, 1, 100, seconds(5)));
    permit(guard.admit(home())).close();

    let admission = guard.admit(same_home());

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_refuse_a_banned_ipv6_address() {
    let guard = banning(&Arc::new(Mutex::new(HashSet::from([home()]))));

    let admission = guard.admit(home());

    assert_eq!(refused(&admission), Some(Refusal::Banned));
}

#[test]
fn should_limit_the_accept_rate_over_a_sliding_window() {
    let ticker = FakeTicker::default();
    let guard = guard(&ticker, limits(100, 100, 2, seconds(5)));
    permit(guard.admit_v4(PEER)).close();
    ticker.advance(seconds(3));
    permit(guard.admit_v4(PEER)).close();
    assert_eq!(refused(&guard.admit_v4(PEER)), Some(Refusal::AcceptRate));
    ticker.advance(seconds(2));

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_not_count_refusals_in_the_rate_and_close_permits_once() {
    let guard = guard(&FakeTicker::default(), limits(100, 1, 2, seconds(5)));
    let first = permit(guard.admit_v4(PEER));
    for _ in 0..5 {
        assert_eq!(refused(&guard.admit_v4(PEER)), Some(Refusal::TooManyFromAddress));
    }
    let last = permit(guard.admit_v4(OTHER));
    first.close();
    first.close();
    assert_eq!(guard.open_connections(), 1);
    assert_eq!(refused(&guard.admit_v4(PEER)), Some(Refusal::AcceptRate), "closing a permit gives no accept back");

    last.close();

    assert_eq!(guard.open_connections(), 0);
}

#[test]
fn should_let_an_accept_leave_the_window_exactly_when_it_ends() {
    let ticker = FakeTicker::default();
    let guard = guard(&ticker, limits(100, 100, 1, seconds(5)));
    permit(guard.admit_v4(PEER)).close();
    ticker.advance(seconds(5) - Duration::from_nanos(1));
    assert_eq!(refused(&guard.admit_v4(PEER)), Some(Refusal::AcceptRate));
    ticker.advance(Duration::from_nanos(1));

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), None);
}

#[test]
fn should_still_cap_the_connections_of_an_exempt_address() {
    let guard = ConnectionGuard::new(
        || Arc::new(IpFilter::empty()),
        |ip| ip == PEER,
        |_| false,
        || limits(1, 2, 1, seconds(5)),
        FakeTicker::default().read(),
    );
    let _other = permit(guard.admit_v4(OTHER));
    let _first = permit(guard.admit_v4(PEER));
    let _second = permit(guard.admit_v4(PEER));

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), Some(Refusal::TooManyFromAddress));
}

#[test]
fn should_refuse_a_rate_window_that_is_not_a_positive_span_of_time() {
    for window in [Duration::ZERO, Duration::from_secs(365 * 300 * 86_400)] {
        assert!(Limits::new(100, 100, 2, window).is_err(), "{window:?}");
    }
}

#[test]
fn should_accept_the_longest_rate_window() {
    assert_eq!(limits(100, 100, 2, Limits::MAX_RATE_WINDOW).rate_window(), Limits::MAX_RATE_WINDOW);
}

#[test]
fn should_accept_a_rate_window_of_one_nanosecond() {
    assert_eq!(limits(100, 100, 2, Duration::from_nanos(1)).rate_window(), Duration::from_nanos(1));
}

#[test]
fn should_leave_the_accept_rate_to_others_when_an_address_opens_connections_in_a_row() {
    let guard = guard(&FakeTicker::default(), limits(100, 2, 8, seconds(5)));
    let mut admitted = 0;
    for _ in 0..8 {
        if let Admission::Admitted(permit) = guard.admit_v4(PEER) {
            admitted += 1;
            permit.close();
        }
    }

    let admission = guard.admit_v4(OTHER);

    assert_eq!(admitted, 2);
    assert_eq!(refused(&admission), None);
}

#[test]
fn should_count_our_connections_in_the_total_the_incoming_ones_share() {
    let guard = guard(&FakeTicker::default(), limits(2, 5, 100, seconds(5)));
    let ours = [guard.outgoing(), guard.outgoing()];

    let admission = guard.admit_v4(OTHER);

    assert_eq!(refused(&admission), Some(Refusal::TooManyConnections));
    assert!(!guard.has_room());
    assert_eq!(guard.open_connections(), 2);
    ours[0].close();
    ours[0].close();
    assert!(guard.has_room());
    let _other = permit(guard.admit_v4(OTHER));
    assert!(!guard.has_room());
}

#[test]
fn should_leave_no_room_for_our_connections_once_the_rate_of_new_ones_is_reached() {
    let guard = guard(&FakeTicker::default(), limits(100, 5, 2, seconds(5)));
    let _ours = [guard.outgoing(), guard.outgoing()];

    let room = guard.has_room();

    assert!(!room);
}

#[test]
fn should_count_our_new_connections_in_the_rate_the_incoming_ones_share() {
    let guard = guard(&FakeTicker::default(), limits(100, 5, 2, seconds(5)));
    let _ours = [guard.outgoing(), guard.outgoing()];

    let admission = guard.admit_v4(OTHER);

    assert_eq!(refused(&admission), Some(Refusal::AcceptRate));
}

#[test]
fn should_have_room_for_our_connections_again_once_the_window_passed() {
    let ticker = FakeTicker::default();
    let guard = guard(&ticker, limits(100, 5, 2, seconds(5)));
    let _ours = [guard.outgoing(), guard.outgoing()];
    ticker.advance(seconds(5));

    let room = guard.has_room();

    assert!(room);
}

#[test]
fn should_release_a_permit_when_it_is_dropped() {
    let guard = guard(&FakeTicker::default(), limits(1, 1, 100, seconds(5)));
    drop(permit(guard.admit_v4(PEER)));

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), None);
    assert!(Limits::new(0, 1, 1, seconds(1)).is_err());
}

#[test]
fn should_take_an_ipv4_address_as_its_number() {
    let guard = guard(&FakeTicker::default(), limits(10, 1, 100, seconds(5)));
    let _peer = permit(guard.admit(IpAddr::V4(Ipv4Addr::from(PEER))));

    let admission = guard.admit_v4(PEER);

    assert_eq!(refused(&admission), Some(Refusal::TooManyFromAddress));
}
