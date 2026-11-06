// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What we uploaded to and downloaded from each client, by user hash, kept in `clients.met` with the public key a client
//! proved it owns. Clients that gave us data get a better place in our upload queue.

use std::io;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use indexmap::IndexMap;

use crate::persist::atomic_files;
use crate::persist::format::clients_met;

pub const FILE_NAME: &str = "clients.met";

/// Clients not seen for this long are forgotten.
pub const EXPIRY: Duration = Duration::from_secs(150 * 24 * 3600);

const MIB: u64 = 1024 * 1024;
// Below this many bytes received the ratio grows linearly from 1.
const RAMP_END: u64 = 9_646_899;

/// The credit of one client.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Credit {
    pub uploaded: u64,
    pub downloaded: u64,
    last_seen: u64,
    /// The key the client proved it owns, empty if it never did.
    pub public_key: Vec<u8>,
}

/// The time in seconds since 1970, as the credits date what they see.
pub type Clock = Box<dyn Fn() -> u64 + Send + Sync>;

/// The system's time.
pub fn system_clock() -> Clock {
    Box::new(|| SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_secs()))
}

pub struct Credits {
    file: PathBuf,
    clock: Clock,
    credits: IndexMap<[u8; 16], Credit>,
    dirty: bool,
}

impl Credits {
    /// Reads `clients.met` of `config_dir`, dropping expired clients; a file that cannot be read gives no credits.
    pub fn load(config_dir: &Path, clock: Clock) -> Self {
        let mut store = Self { file: config_dir.join(FILE_NAME), clock, credits: IndexMap::new(), dirty: false };
        let oldest = store.now().saturating_sub(EXPIRY.as_secs());
        let read = atomic_files::read(&store.file, |data| {
            clients_met::read(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
        });
        match read {
            Ok(known) => {
                for credit in known.into_iter().filter(|credit| u64::from(credit.last_seen) >= oldest) {
                    store.credits.insert(
                        credit.user_hash,
                        Credit {
                            uploaded: credit.uploaded,
                            downloaded: credit.downloaded,
                            last_seen: u64::from(credit.last_seen),
                            public_key: credit.public_key,
                        },
                    );
                }
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => tracing::warn!("Cannot read {}, credits start from zero: {e}", store.file.display()),
        }
        store
    }

    pub fn get(&self, user_hash: &[u8; 16]) -> Option<&Credit> {
        self.credits.get(user_hash)
    }

    /// We sent `bytes` to the client.
    pub fn uploaded(&mut self, user_hash: [u8; 16], bytes: u64) {
        let credit = self.credit(user_hash);
        credit.uploaded = credit.uploaded.saturating_add(bytes);
        self.dirty = true;
    }

    /// The client sent us `bytes`.
    pub fn downloaded(&mut self, user_hash: [u8; 16], bytes: u64) {
        let credit = self.credit(user_hash);
        credit.downloaded = credit.downloaded.saturating_add(bytes);
        self.dirty = true;
    }

    /// The client proved it owns `key`. The first key is pinned; credit gathered under the user hash before that, by
    /// anyone, starts again from nothing.
    pub fn identified(&mut self, user_hash: [u8; 16], key: &[u8]) {
        let credit = self.credit(user_hash);
        if credit.public_key.is_empty() {
            credit.public_key = key.to_vec();
            if credit.downloaded > 0 {
                credit.downloaded = 1;
                credit.uploaded = 1;
            }
            self.dirty = true;
        }
    }

    /// The factor a client's waiting time is multiplied by in our queue: from 1 to 10, growing with what it gave us
    /// compared with what it got. Clients whose identity is in doubt get 1.
    pub fn ratio(&self, user_hash: &[u8; 16], trusted: bool) -> f64 {
        let Some(credit) = self.credits.get(user_hash).filter(|credit| trusted && credit.downloaded >= MIB) else {
            return 1.0;
        };
        let downloaded = credit.downloaded as f64;
        let mib = MIB as f64;
        let given = if credit.uploaded == 0 { 10.0 } else { downloaded * 2.0 / credit.uploaded as f64 };
        let by_size = (downloaded / mib + 2.0).sqrt();
        let ramp =
            if credit.downloaded < RAMP_END { (downloaded - mib) / (RAMP_END as f64 - mib) * 2.34 + 1.0 } else { 10.0 };
        given.min(by_size.min(ramp)).clamp(1.0, 10.0)
    }

    /// Writes `clients.met` if anything changed.
    pub fn save(&mut self) {
        if !self.dirty {
            return;
        }
        let all: Vec<clients_met::Credit> = self
            .credits
            .iter()
            .filter(|(_, credit)| credit.uploaded != 0 || credit.downloaded != 0)
            .map(|(user_hash, credit)| clients_met::Credit {
                user_hash: *user_hash,
                uploaded: credit.uploaded,
                downloaded: credit.downloaded,
                last_seen: u32::try_from(credit.last_seen).unwrap_or(u32::MAX),
                public_key: credit.public_key.clone(),
            })
            .collect();
        let written = clients_met::write(&all)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
            .and_then(|data| atomic_files::write(&self.file, &data));
        match written {
            Ok(()) => self.dirty = false,
            Err(e) => tracing::warn!("Cannot save {}: {e}", self.file.display()),
        }
    }

    fn credit(&mut self, user_hash: [u8; 16]) -> &mut Credit {
        let now = self.now();
        let credit = self.credits.entry(user_hash).or_insert_with(|| Credit {
            uploaded: 0,
            downloaded: 0,
            last_seen: now,
            public_key: Vec::new(),
        });
        credit.last_seen = now;
        credit
    }

    fn now(&self) -> u64 {
        (self.clock)()
    }
}
