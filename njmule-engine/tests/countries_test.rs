// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The table of countries: when it is read or fetched, the switch that keeps it from being fetched, and the country a
//! running engine gives its servers once it read its copy.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::arithmetic_side_effects)]

mod free_port;

use std::fs;
use std::time::{Duration, Instant, SystemTime};

use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::countries::{self, Step};
use njmule_engine::engine::switches::Switches;
use njmule_engine::net::country_table::CountryTable;
use njmule_engine::server::server_list::Address;

const DAY: Duration = Duration::from_secs(24 * 60 * 60);
const SERVER: &str = "81.2.3.4:4661";

#[test]
fn should_fetch_the_table_when_its_copy_is_a_month_old() {
    let mut test = Countries::default();

    test.when_the_next_step_is_asked(true, Some(33 * DAY), None, true);

    test.then_the_step_is(Step::Fetch);
}

#[test]
fn should_fetch_the_table_when_the_cache_has_no_copy() {
    let mut test = Countries::default();

    test.when_the_next_step_is_asked(true, None, None, false);

    test.then_the_step_is(Step::Fetch);
}

#[test]
fn should_wait_after_a_failed_fetch_and_read_the_copy_meanwhile() {
    let mut test = Countries::default();

    test.when_the_next_step_is_asked(true, Some(40 * DAY), Some(Duration::from_secs(60)), false);

    test.then_the_step_is(Step::Read);
}

#[test]
fn should_read_a_young_copy_once() {
    let mut test = Countries::default();

    test.when_the_next_step_is_asked(true, Some(DAY), None, false);

    test.then_the_step_is(Step::Read);
}

#[test]
fn should_do_nothing_with_a_young_copy_held() {
    let mut test = Countries::default();

    test.when_the_next_step_is_asked(true, Some(DAY), None, true);

    test.then_the_step_is(Step::Nothing);
}

#[test]
fn should_never_fetch_the_table_when_the_switch_keeps_it_from_it() {
    let mut test = Countries::default();

    test.when_the_next_step_is_asked(false, None, None, false);

    test.then_the_step_is(Step::Nothing);
}

#[test]
fn should_fetch_the_countries_from_the_executable_unless_switched_off() {
    let mut test = Countries::default();

    test.when_the_switches_are(&["njmule.upnp.disabled=true"]);

    test.then_the_countries_are_fetched(true);
}

#[test]
fn should_keep_the_countries_from_being_fetched_by_their_switch() {
    let mut test = Countries::default();

    test.when_the_switches_are(&["njmule.countries.disabled=true"]);

    test.then_the_countries_are_fetched(false);
}

#[tokio::test]
async fn should_give_a_server_the_country_of_the_copy_the_engine_read() {
    let mut test = Countries::default();
    test.given_the_cache_holds_the_countries("81.0.0.0,81.255.255.255,NL\n");
    test.given_a_running_engine().await;
    test.given_the_server_is_known(SERVER).await;

    test.when_the_engine_read_the_countries().await;

    test.then_the_country_of_the_server_is(SERVER, "nl").await;
}

#[derive(Default)]
struct Countries {
    profile: Option<tempfile::TempDir>,
    engine: Option<Engine>,
    step: Option<Step>,
    switches: Option<Switches>,
}

impl Countries {
    fn given_the_cache_holds_the_countries(&mut self, csv: &str) {
        let cache = self.directories().cache;
        fs::create_dir_all(&cache).unwrap();
        let table = CountryTable::parse(csv.as_bytes()).unwrap();
        fs::write(cache.join(countries::FILE), table.write()).unwrap();
    }

    async fn given_a_running_engine(&mut self) {
        let dirs = self.directories();
        fs::create_dir_all(&dirs.config).unwrap();
        let preferences = format!("net.tcpPort={}\nnet.kad.enabled=false\n", free_port::free_port());
        fs::write(dirs.config.join("njmule.properties"), preferences).unwrap();
        self.engine = Some(Engine::start(dirs).await.unwrap());
    }

    async fn given_the_server_is_known(&self, address: &str) {
        assert!(self.engine().add_server(Address::parse(address).unwrap()).await);
    }

    fn when_the_next_step_is_asked(
        &mut self,
        may_fetch: bool,
        age: Option<Duration>,
        tried: Option<Duration>,
        held: bool,
    ) {
        let now = SystemTime::now();
        let written = age.map(|age| now - age);
        let tried = tried.map(|ago| Instant::now() - ago);
        self.step = Some(countries::step(may_fetch, written, now, tried, held));
    }

    fn when_the_switches_are(&mut self, switches: &[&str]) {
        let switches: Vec<String> = switches.iter().map(|&switch| switch.to_owned()).collect();
        self.switches = Some(Switches::of(&switches));
    }

    // The first look at the table comes shortly after the start.
    async fn when_the_engine_read_the_countries(&self) {
        tokio::time::sleep(countries::FIRST_CHECK).await;
    }

    fn then_the_step_is(&self, step: Step) {
        assert_eq!(self.step, Some(step));
    }

    fn then_the_countries_are_fetched(&self, fetched: bool) {
        assert_eq!(self.switches.unwrap().countries, fetched);
    }

    async fn then_the_country_of_the_server_is(&self, address: &str, country: &str) {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let servers = self.engine().servers().await;
            let server = servers.iter().find(|server| server.address == address).expect("the server");
            if server.country.as_deref() == Some(country) {
                return;
            }
            assert!(Instant::now() < deadline, "no country yet: {server:?}");
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    fn directories(&mut self) -> AppDirectories {
        let profile = self.profile.get_or_insert_with(|| tempfile::tempdir().unwrap());
        AppDirectories::under_root(profile.path()).unwrap()
    }

    fn engine(&self) -> Engine {
        self.engine.clone().expect("a running engine")
    }
}

impl Drop for Countries {
    fn drop(&mut self) {
        if let Some(engine) = self.engine.take() {
            tokio::spawn(async move { engine.stop().await });
        }
    }
}
