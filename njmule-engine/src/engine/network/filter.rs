// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The IP filter of the network: read from ipfilter.dat at the start, replaced by lists from the web, and applied at once
//! to the server, the sources, the clients and the Kad contacts it refuses.

use std::net::IpAddr;
use std::path::Path;
use std::sync::Arc;
use std::time::{Instant, SystemTime};

use tokio::sync::watch;

use super::{Network, Tick};
use crate::api::log_line::Level as LogLevel;
use crate::config::preferences::Preferences;
use crate::engine::ip_filter_lists;
use crate::net::web_lists::WebLists;
use crate::persist::atomic_files;
use crate::persist::format::ip_filter_dat::{self, Range};
use crate::security::ip_filter::IpFilter;
use crate::security::ip_whitelist;
use crate::server::server_list;

/// The file the IP filter is kept in, with the ranges of every level.
pub const IPFILTER_FILE: &str = "ipfilter.dat";

/// The filter kept in `config`, at the level of the `preferences` and with the addresses they always allow; none when
/// there is no file.
pub(super) fn load_ip_filter(config: &Path, preferences: &Preferences) -> IpFilter {
    let file = config.join(IPFILTER_FILE);
    let ranges = match std::fs::read(&file) {
        Ok(data) => ip_filter_dat::read(&data, IPFILTER_FILE),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
        Err(e) => {
            tracing::warn!("Cannot read {}, no IP filter: {e}", file.display());
            Vec::new()
        }
    };
    IpFilter::of(&ranges, preferences.ip_filter_level).allowing(&always_allowed(preferences))
}

/// Whether the filter blocks an address, as [`crate::util::ipv4`] holds it, for what reads it from other tasks.
pub(super) fn ip_test(filter: &watch::Sender<Arc<IpFilter>>) -> impl Fn(u32) -> bool + Send + Sync + 'static {
    let filter = filter.subscribe();
    move |ip| filter.borrow().blocks(ip)
}

// The preferences checked them already.
fn always_allowed(preferences: &Preferences) -> Vec<Range> {
    ip_whitelist::ranges(&preferences.ip_filter_whitelist).unwrap_or_else(|e| {
        tracing::warn!("The addresses always allowed are left out: {e}");
        Vec::new()
    })
}

impl Network {
    pub(super) fn blocks(&self, ip: u32) -> bool {
        self.ip_filter.borrow().blocks(ip)
    }

    /// Replaces the IP filter with `ranges`, kept as ipfilter.dat; answers how many ranges it has. A filter is not
    /// replaced by nothing.
    pub(super) fn set_ip_filter(&mut self, ranges: &[Range]) -> Result<usize, String> {
        if ranges.is_empty() {
            return Err("the list holds no IP range".to_owned());
        }
        let file = self.shared.dirs.config.join(IPFILTER_FILE);
        atomic_files::write(&file, &ip_filter_dat::write(ranges))
            .map_err(|e| format!("Cannot save {IPFILTER_FILE}: {e}"))?;
        let preferences = self.preferences();
        let filter = IpFilter::of(ranges, preferences.ip_filter_level).allowing(&always_allowed(&preferences));
        let count = filter.range_count();
        self.ip_filter.send_replace(Arc::new(filter));
        self.user_log(LogLevel::Info, format!("{count} IP filter ranges"));
        self.filters_changed();
        Ok(count)
    }

    /// Follows a change of the level, of the addresses always allowed or of the LAN filter.
    pub(super) fn ip_filter_preferences(&mut self, was: &Preferences) {
        let now = self.preferences();
        let level = was.ip_filter_level != now.ip_filter_level;
        let whitelist = was.ip_filter_whitelist != now.ip_filter_whitelist;
        if level {
            // The file keeps every range: read again at the new level.
            let filter = load_ip_filter(&self.shared.dirs.config, &now);
            let count = filter.range_count();
            self.ip_filter.send_replace(Arc::new(filter));
            self.user_log(LogLevel::Info, format!("{count} IP filter ranges at level {}", now.ip_filter_level));
        } else if whitelist {
            let allowed = always_allowed(&now);
            let filter = self.ip_filter.borrow().allowing(&allowed);
            self.ip_filter.send_replace(Arc::new(filter));
            self.user_log(LogLevel::Info, format!("{} addresses or ranges always allowed", allowed.len()));
        }
        if level || whitelist || was.filter_lan_addresses != now.filter_lan_addresses {
            self.filters_changed();
        }
    }

    /// Looks whether the lists of the preferences are due, once in a while.
    pub(super) fn check_ip_filter_lists(&mut self) {
        let now = Instant::now();
        if now < self.ip_filter_check_at {
            return;
        }
        self.ip_filter_check_at = now.checked_add(ip_filter_lists::CHECK).unwrap_or(now);
        self.update_ip_filter_if_due();
    }

    /// The lists fetched for an update, or why none could be.
    pub(super) fn ip_filter_fetched(&mut self, ranges: Result<Vec<Range>, String>) {
        self.ip_filter_updating = false;
        match ranges.and_then(|ranges| self.set_ip_filter(&ranges)) {
            Ok(_) => self.ip_filter_tried = None,
            Err(reason) => self.user_log(LogLevel::Warning, format!("IP filter lists not updated: {reason}")),
        }
    }

    // The lists are fetched again once the filter is as old as the preferences say; a failed update waits.
    fn update_ip_filter_if_due(&mut self) {
        let preferences = self.preferences();
        let lists = ip_filter_lists::addresses(&preferences.ip_filter_lists);
        let filter_date = std::fs::metadata(self.shared.dirs.config.join(IPFILTER_FILE))
            .and_then(|metadata| metadata.modified())
            .ok();
        let waiting = self.ip_filter_tried.is_some_and(|tried| tried.elapsed() < ip_filter_lists::RETRY);
        if self.ip_filter_updating
            || waiting
            || !ip_filter_lists::is_due(filter_date, SystemTime::now(), preferences.ip_filter_update_days, &lists)
        {
            return;
        }
        self.ip_filter_updating = true;
        self.ip_filter_tried = Some(Instant::now());
        let web = WebLists::create(self.shared.routes.clone());
        let shared = Arc::clone(&self.shared);
        let ticks = self.ticks.clone();
        tokio::spawn(async move {
            let ranges =
                ip_filter_lists::fetch_joined(&web, &lists, |warning| shared.user_log(LogLevel::Warning, warning))
                    .await;
            let _ = ticks.send(Tick::IpFilterFetched(ranges));
        });
    }

    // A filter applies at once to what is open too: the server, sources, clients and Kad contacts it now refuses go.
    fn filters_changed(&mut self) {
        // A server known by its name has no address while the name resolves; it is checked once it has one.
        let refused = self
            .session
            .server()
            .filter(|server| server.ip != 0 && (self.blocks(server.ip) || self.is_filtered_lan(server.ip)))
            .map(server_list::address_of);
        if let Some(address) = refused {
            self.user_log(LogLevel::Info, format!("Leaving the server {address}: it is now filtered"));
            self.with_session(|session, ctx| session.disconnect(ctx));
        }
        let ours = self.shared.our_hello();
        let sources = self.downloads.drop_blocked_sources(&ours);
        // Clients connect to us whatever the LAN filter says, as the connection guard lets them.
        let filter = self.ip_filter.borrow().clone();
        let clients =
            self.uploads.drop_clients(&|address| matches!(address, IpAddr::V4(v4) if filter.blocks(u32::from(v4))));
        let contacts = self.kad_filters_changed();
        if sources.saturating_add(clients).saturating_add(contacts) > 0 {
            self.user_log(
                LogLevel::Info,
                format!("Now filtered: {sources} sources, {clients} clients, {contacts} Kad contacts dropped"),
            );
        }
        self.after_downloads();
    }
}
