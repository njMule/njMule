// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Our friends, kept in `emfriends.met` as other clients keep theirs, in the order they were added.

use std::io;
use std::path::{Path, PathBuf};

use indexmap::IndexMap;

use crate::api::friend::Friend;
use crate::persist::atomic_files;
use crate::persist::format::friends_met;
use crate::protocol::tag::{Tag, TagName};
use crate::server::server_list::Address;

pub const FILE_NAME: &str = "emfriends.met";

#[derive(Debug)]
pub struct Friends {
    file: PathBuf,
    friends: IndexMap<[u8; 16], friends_met::Friend>,
}

impl Friends {
    /// Reads the friends of the configuration folder `config`; a broken file counts as none.
    pub fn load(config: &Path) -> Self {
        let mut store = Self { file: config.join(FILE_NAME), friends: IndexMap::new() };
        let read = atomic_files::read(&store.file, |data| {
            friends_met::read(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
        });
        match read {
            Ok(friends) => store.friends.extend(friends.into_iter().map(|friend| (friend.user_hash, friend))),
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => tracing::warn!("Cannot read {}: {e}", store.file.display()),
        }
        store
    }

    pub fn contains(&self, user_hash: &[u8; 16]) -> bool {
        self.friends.contains_key(user_hash)
    }

    pub fn all(&self) -> Vec<Friend> {
        self.friends
            .values()
            .map(|known| {
                let address =
                    (known.last_ip != 0 && known.last_port != 0).then(|| Address::new(known.last_ip, known.last_port));
                Friend::new(known.user_hash, known.name().unwrap_or(""), address)
            })
            .collect()
    }

    /// Adds `friend`, or changes its name and address; an empty name or no address keeps the known one. The file keeps
    /// IPv4 addresses only: an address by name is not kept.
    pub fn add(&mut self, friend: &Friend) -> io::Result<()> {
        let known = self.friends.get(&friend.user_hash);
        let (mut ip, mut port) = known.map_or((0, 0), |known| (known.last_ip, known.last_port));
        if let Some(address) = &friend.address {
            (ip, port) = if address.host.is_empty() { (address.ip, address.port) } else { (0, 0) };
        }
        let name = TagName::id(friends_met::NAME);
        let mut tags = Vec::new();
        if !friend.name.is_empty() {
            tags.push(Tag::String { name: name.clone(), value: friend.name.clone() });
        }
        if let Some(known) = known {
            tags.extend(known.tags.iter().filter(|tag| friend.name.is_empty() || *tag.name() != name).cloned());
        }
        let kept = friends_met::Friend {
            user_hash: friend.user_hash,
            last_ip: ip,
            last_port: port,
            last_seen: known.map_or(0, |known| known.last_seen),
            last_chatted: known.map_or(0, |known| known.last_chatted),
            tags,
        };
        self.friends.insert(friend.user_hash, kept);
        self.save()
    }

    /// Removes the friend with `user_hash`, the others keeping their order; false when it is none.
    pub fn remove(&mut self, user_hash: &[u8; 16]) -> io::Result<bool> {
        if self.friends.shift_remove(user_hash).is_none() {
            return Ok(false);
        }
        self.save()?;
        Ok(true)
    }

    fn save(&self) -> io::Result<()> {
        let friends: Vec<friends_met::Friend> = self.friends.values().cloned().collect();
        let data = friends_met::write(&friends).map_err(io::Error::other)?;
        atomic_files::write(&self.file, &data)
    }
}
