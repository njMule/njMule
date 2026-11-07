// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Flood control and answer checking for Kad datagrams: each address may send each kind of request only so often (a
//! long burst bans it for two hours), and an answer is taken only from an address we sent the matching request to in
//! the last three minutes. The port is not compared: a NAT may answer from another one.

use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use crate::kad::uint128::UInt128;
use crate::protocol::packet::opcodes::kad;

/// How long a request waits for its answer.
pub const ANSWER_WINDOW: Duration = Duration::from_secs(180);
pub const BAN_TIME: Duration = Duration::from_secs(2 * 3600);

const MINUTE_MS: i64 = 60_000;
const BAN_DEBT_MS: i64 = 3 * MINUTE_MS;
// Challenges waiting for their answer at most: hellos from forged addresses must not fill memory.
const MAX_CHALLENGES: usize = 1_000;

// The requests each address may send a minute.
fn per_minute(request: u8) -> Option<i64> {
    Some(match request {
        kad::BOOTSTRAP_REQ
        | kad::PUBLISH_NOTES_REQ
        | kad::FIREWALLED_REQ
        | kad::FIREWALLED2_REQ
        | kad::FINDBUDDY_REQ
        | kad::PING => 2,
        kad::HELLO_REQ
        | kad::SEARCH_KEY_REQ
        | kad::SEARCH_SOURCE_REQ
        | kad::SEARCH_NOTES_REQ
        | kad::PUBLISH_SOURCE_REQ => 3,
        kad::PUBLISH_KEY_REQ => 4,
        kad::REQ => 10,
        kad::CALLBACK_REQ => 1,
        _ => return None,
    })
}

// The requests an answer may answer.
fn requests_of(answer: u8) -> &'static [u8] {
    match answer {
        kad::BOOTSTRAP_RES => &[kad::BOOTSTRAP_REQ],
        kad::HELLO_RES => &[kad::HELLO_REQ],
        kad::HELLO_RES_ACK => &[kad::HELLO_RES],
        kad::RES => &[kad::REQ],
        kad::SEARCH_RES => &[kad::SEARCH_KEY_REQ, kad::SEARCH_SOURCE_REQ, kad::SEARCH_NOTES_REQ],
        kad::PUBLISH_RES => &[kad::PUBLISH_KEY_REQ, kad::PUBLISH_SOURCE_REQ, kad::PUBLISH_NOTES_REQ],
        kad::FIREWALLED_RES => &[kad::FIREWALLED_REQ, kad::FIREWALLED2_REQ],
        kad::FINDBUDDY_RES => &[kad::FINDBUDDY_REQ],
        kad::PONG => &[kad::PING],
        _ => &[],
    }
}

#[derive(Debug, Clone, Copy)]
struct Bucket {
    tokens: i64,
    at: Instant,
}

#[derive(Debug, Clone, Copy)]
struct Sent {
    ip: u32,
    request: u8,
    at: Instant,
}

// A request whose answer proves the address of the contact `id`: a ping, or a lookup for a random target.
#[derive(Debug, Clone, Copy)]
struct Challenge {
    ip: u32,
    request: u8,
    target: UInt128,
    id: UInt128,
    at: Instant,
}

#[derive(Debug, Default)]
pub struct PacketTracking {
    buckets: HashMap<(u32, u8), Bucket>,
    bans: HashMap<u32, Instant>,
    sent: VecDeque<Sent>,
    challenges: VecDeque<Challenge>,
}

impl PacketTracking {
    pub fn new() -> Self {
        Self::default()
    }

    /// Whether a request may be served; may ban the address.
    pub fn allow(&mut self, ip: u32, request: u8, now: Instant) -> bool {
        let Some(per_minute) = per_minute(request) else {
            return true;
        };
        if self.is_banned(ip, now) {
            return false;
        }
        // Firewall requests share one budget.
        let kind = if request == kad::FIREWALLED2_REQ { kad::FIREWALLED_REQ } else { request };
        let cost = MINUTE_MS.checked_div(per_minute).unwrap_or(MINUTE_MS);
        let Some(bucket) = self.buckets.get_mut(&(ip, kind)) else {
            self.buckets.insert((ip, kind), Bucket { tokens: MINUTE_MS.saturating_sub(cost), at: now });
            return true;
        };
        let elapsed = i64::try_from(now.saturating_duration_since(bucket.at).as_millis()).unwrap_or(i64::MAX);
        let tokens = bucket.tokens.saturating_add(elapsed).min(MINUTE_MS).saturating_sub(cost);
        *bucket = Bucket { tokens, at: now };
        if tokens < -BAN_DEBT_MS
            && let Some(until) = now.checked_add(BAN_TIME)
        {
            self.bans.insert(ip, until);
        }
        tokens >= 0
    }

    /// Drops every packet of `ip` for `time`.
    pub fn ban(&mut self, ip: u32, time: Duration, now: Instant) {
        if let Some(until) = now.checked_add(time) {
            let ban = self.bans.entry(ip).or_insert(until);
            *ban = (*ban).max(until);
        }
    }

    pub fn is_banned(&mut self, ip: u32, now: Instant) -> bool {
        let Some(until) = self.bans.get(&ip) else {
            return false;
        };
        if now >= *until {
            self.bans.remove(&ip);
            return false;
        }
        true
    }

    /// We sent `request` to `ip`.
    pub fn sent(&mut self, ip: u32, request: u8, now: Instant) {
        self.sent.push_back(Sent { ip, request, at: now });
    }

    /// Whether `answer` from `ip` answers a request of ours; the request is consumed.
    pub fn expected(&mut self, ip: u32, answer: u8, now: Instant) -> bool {
        self.expire(now);
        let requests = requests_of(answer);
        let found = self.sent.iter().position(|sent| sent.ip == ip && requests.contains(&sent.request));
        found.and_then(|at| self.sent.remove(at)).is_some()
    }

    /// Whether some request to `ip` waits for `answer`, without consuming it.
    pub fn awaits(&mut self, ip: u32, answer: u8, now: Instant) -> bool {
        self.expire(now);
        let requests = requests_of(answer);
        self.sent.iter().any(|sent| sent.ip == ip && requests.contains(&sent.request))
    }

    /// Whether we sent any request to `ip` in the last three minutes.
    pub fn wrote_to(&mut self, ip: u32, now: Instant) -> bool {
        self.expire(now);
        self.sent.iter().any(|sent| sent.ip == ip)
    }

    /// We sent `ip` a request whose answer proves the contact `id` is there; a zero target for a ping.
    pub fn challenged(&mut self, ip: u32, request: u8, target: UInt128, id: UInt128, now: Instant) {
        self.expire(now);
        if self.challenges.len() >= MAX_CHALLENGES {
            self.challenges.pop_front();
        }
        self.challenges.push_back(Challenge { ip, request, target, id, at: now });
    }

    /// Whether a challenge to `ip` waits for its answer: one at a time.
    pub fn is_challenged(&mut self, ip: u32, now: Instant) -> bool {
        self.expire(now);
        self.challenges.iter().any(|challenge| challenge.ip == ip)
    }

    /// The contact proven by this answer of `ip` to `request` for `target`; consumed.
    pub fn challenge_answered(&mut self, ip: u32, request: u8, target: UInt128, now: Instant) -> Option<UInt128> {
        self.expire(now);
        let at = self.challenges.iter().position(|c| c.ip == ip && c.request == request && c.target == target)?;
        self.challenges.remove(at).map(|challenge| challenge.id)
    }

    /// Drops what is old; call every few minutes.
    pub fn clean(&mut self, now: Instant) {
        self.expire(now);
        self.buckets.retain(|_, bucket| now.saturating_duration_since(bucket.at) <= Duration::from_secs(120));
        self.bans.retain(|_, until| now < *until);
    }

    fn expire(&mut self, now: Instant) {
        while self.sent.front().is_some_and(|sent| now.saturating_duration_since(sent.at) > ANSWER_WINDOW) {
            self.sent.pop_front();
        }
        while self.challenges.front().is_some_and(|c| now.saturating_duration_since(c.at) > ANSWER_WINDOW) {
            self.challenges.pop_front();
        }
    }
}
