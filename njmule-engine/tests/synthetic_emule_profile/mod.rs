// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! An eMule profile made up for tests: config, temp, incoming and one shared directory, as the Java engine's tests make
//! it.

#![allow(dead_code, clippy::unwrap_used, clippy::indexing_slicing)]

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use njmule_engine::crypto::{rsa, rsa_keys};
use njmule_engine::files::hash::file_hasher;
use njmule_engine::files::part_file::{self, PartFile, Status};
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::clients_met::{self, Credit};
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::nodes_dat::{self, Contact, UdpKey};
use njmule_engine::persist::format::server_met::{self, Server};
use njmule_engine::persist::format::{cancelled_met, part_met, preferences_dat, preferences_kad_dat, text_files};
use njmule_engine::persist::user_hash_store;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use rand::{Rng, RngExt};
use sha2::{Digest, Sha256};

pub const NICK: &str = "Tester";
pub const KAD_UDP_KEY: u32 = 305_419_896;
pub const SHARED_SIZE: usize = 70_000;
pub const DOWNLOAD_SIZE: usize = 300_000;

pub struct SyntheticEmuleProfile {
    pub config: PathBuf,
    pub temp: PathBuf,
    pub incoming: PathBuf,
    pub shared: PathBuf,
    pub shared_file: PathBuf,
    pub download_data: Vec<u8>,
    /// The hash of the download half done.
    pub half_hash: [u8; 16],
}

fn random_bytes(size: usize) -> Vec<u8> {
    let mut data = vec![0; size];
    rand::rng().fill_bytes(&mut data);
    data
}

fn ip(a: u8, b: u8, c: u8, d: u8) -> u32 {
    u32::from_be_bytes([a, b, c, d])
}

fn hash_of(root: &Path, data: &[u8]) -> file_hasher::FileHashes {
    let scratch = root.join("scratch.bin");
    fs::write(&scratch, data).unwrap();
    let hashes = file_hasher::hash_file(&scratch).unwrap();
    fs::remove_file(&scratch).unwrap();
    hashes
}

fn contact(address: u32) -> Contact {
    Contact {
        id: UInt128(rand::rng().random()),
        ip: address,
        udp_port: 4672,
        tcp_port: 4662,
        version: 8,
        contact_type: 0,
        udp_key: UdpKey::default(),
        verified: true,
    }
}

impl SyntheticEmuleProfile {
    /// Writes every file of the profile, with one complete download and one half done.
    pub fn create(root: &Path) -> Self {
        let config = root.join("config");
        let temp = root.join("Temp");
        let incoming = root.join("Incoming");
        let shared = root.join("Shared");
        for dir in [&config, &temp, &incoming, &shared] {
            fs::create_dir_all(dir).unwrap();
        }
        let shared_file = shared.join("shared.bin");
        fs::write(&shared_file, random_bytes(SHARED_SIZE)).unwrap();
        let download_data = random_bytes(DOWNLOAD_SIZE);
        let download_hash = hash_of(root, &download_data).ed2k.file_hash();
        let other = random_bytes(DOWNLOAD_SIZE);
        let half_hash = hash_of(root, &other).ed2k.file_hash();
        let mut half_data = other;
        half_data[DOWNLOAD_SIZE / 2..].fill(0);
        let profile = Self { config, temp, incoming, shared, shared_file, download_data, half_hash };
        let ini = [
            "[eMule]".to_owned(),
            format!("Nick={NICK}"),
            "MaxUpload=50".to_owned(),
            "MaxDownload=65535".to_owned(),
            "MaxConnections=300".to_owned(),
            "MaxSourcesPerFile=400".to_owned(),
            "Reconnect=1".to_owned(),
            "Autoconnect=0".to_owned(),
            "RememberCancelledFiles=0".to_owned(),
            "StoreSearches=0".to_owned(),
            "AddNewFilesPaused=1".to_owned(),
            "StartNextFile=2".to_owned(),
            "FilterBadIPs=0".to_owned(),
            "MessagesFromFriendsOnly=1".to_owned(),
            "MessageFilter=buy now|cheap".to_owned(),
            "FilenameCleanups=dvdrip|sponsored".to_owned(),
            "AutoFilenameCleanup=1".to_owned(),
            "EnableSearchResultSpamFilter=0".to_owned(),
            "MaxConnectionsPerFiveSeconds=35".to_owned(),
            "QueueSize=9000".to_owned(),
            "NetworkED2K=1".to_owned(),
            "NetworkKademlia=0".to_owned(),
            "SecureIdent=1".to_owned(),
            "UseCreditSystem=1".to_owned(),
            "CryptLayerSupported=1".to_owned(),
            "CryptLayerRequested=1".to_owned(),
            "CryptLayerRequired=0".to_owned(),
            "Port=61000".to_owned(),
            "UDPPort=61001".to_owned(),
            format!("IncomingDir={}", profile.incoming.display()),
            format!("TempDir={}", profile.temp.display()),
            format!("KadUDPKey={KAD_UDP_KEY}"),
            String::new(),
        ]
        .join("\r\n");
        profile.write("preferences.ini", ini.as_bytes());
        let servers = [ip(10, 1, 2, 3), ip(10, 1, 2, 4)]
            .into_iter()
            .zip([4661, 4242])
            .map(|(ip, port)| Server { ip, port, tags: Vec::new() })
            .collect::<Vec<_>>();
        profile.write("server.met", &server_met::write(&servers).unwrap());
        let contacts = [ip(10, 2, 0, 1), ip(10, 3, 0, 1), ip(10, 4, 0, 1)].map(contact);
        profile.write("nodes.dat", &nodes_dat::write(&contacts).unwrap());
        let hashes = file_hasher::hash_file(&profile.shared_file).unwrap().ed2k;
        let mtime = fs::metadata(&profile.shared_file).unwrap().modified().unwrap();
        let known = KnownFile {
            mtime: u32::try_from(mtime.duration_since(UNIX_EPOCH).unwrap().as_secs()).unwrap(),
            file_hash: hashes.file_hash(),
            part_hashes: hashes.part_hashes().to_vec(),
            tags: vec![
                Tag::String { name: TagName::id(file_tag::FILENAME), value: "shared.bin".to_owned() },
                Tag::Int { name: TagName::id(file_tag::FILESIZE), value: SHARED_SIZE as u64 },
            ],
        };
        profile.write("known.met", &known_met::write(&[known]).unwrap());
        let shared_dirs = [profile.shared.display().to_string(), root.join("gone").display().to_string()];
        profile.write("shareddir.dat", &text_files::write_lines(&shared_dirs));
        profile.write("sharedfiles.dat", &text_files::write_lines(&[profile.shared_file.display().to_string()]));
        profile.write("cancelled.met", &cancelled_met::write(7, &[cancelled_met::key(7, &[0; 16])]).unwrap());
        profile.write("ipfilter.dat", b"010.000.000.000 - 010.000.000.255 , 000 , test range\n");
        let user_hash = user_hash_store::generate(&mut rand::rng());
        let preferences =
            preferences_dat::Contents { version: preferences_dat::VERSION, user_hash, window_placement: Vec::new() };
        profile.write("preferences.dat", &preferences_dat::write(&preferences));
        rsa_keys::write_key_file(&profile.config.join("cryptkey.dat"), &rsa::generate(rsa::NETWORK_KEY_BITS).unwrap())
            .unwrap();
        let credit = Credit {
            user_hash: user_hash_store::generate(&mut rand::rng()),
            uploaded: 1_000,
            downloaded: 2_000,
            last_seen: 1_700_000_000,
            public_key: Vec::new(),
        };
        profile.write("clients.met", &clients_met::write(&[credit]).unwrap());
        let kad = preferences_kad_dat::Contents { last_ip: 0, kad_id: UInt128(rand::rng().random()) };
        profile.write("preferencesKad.dat", &preferences_kad_dat::write(&kad));
        profile.write("key_index.dat", &[4, 0, 0, 0]);
        profile.download(1, "complete.bin", download_hash, DOWNLOAD_SIZE, Status::Paused, &profile.download_data);
        profile.download(2, "half.bin", half_hash, DOWNLOAD_SIZE / 2, Status::Downloading, &half_data);
        profile
    }

    pub fn write(&self, name: &str, data: &[u8]) {
        fs::write(self.config.join(name), data).unwrap();
    }

    fn root(&self) -> PathBuf {
        self.config.parent().unwrap().to_path_buf()
    }

    fn download(&self, number: u32, name: &str, hash: [u8; 16], written: usize, status: Status, data: &[u8]) {
        let mut file =
            PartFile::create(number, &self.temp, name, DOWNLOAD_SIZE as u64, hash, None, Vec::new()).unwrap();
        file.written(0, written as u64);
        file.set_status(status);
        file.save().unwrap();
        fs::write(file.data_file(), data).unwrap();
    }

    /// Puts download `number` (1 complete, 2 half done) in eMule's category `category`.
    pub fn put_in_category(&self, number: u32, category: u64) {
        self.retag(number, Tag::Int { name: TagName::id(file_tag::CATEGORY), value: category });
    }

    /// Gives download `number` (1 complete, 2 half done) `tag` in place of the one of that name.
    pub fn retag(&self, number: u32, tag: Tag) {
        let met = self.temp.join(format!("{}{}", part_file::file_name(number), part_file::MET_SUFFIX));
        let mut contents = part_met::read(&fs::read(&met).unwrap()).unwrap();
        contents.tags.retain(|t| t.name() != tag.name());
        contents.tags.push(tag);
        fs::write(&met, part_met::write(&contents).unwrap()).unwrap();
    }

    /// SHA-256 and time of every file below the profile, to prove an import left it untouched.
    pub fn fingerprint(&self) -> BTreeMap<String, String> {
        fingerprint(&self.root())
    }
}

/// SHA-256 and time of every file below `root`.
pub fn fingerprint(root: &Path) -> BTreeMap<String, String> {
    let mut sums = BTreeMap::new();
    let mut folders = vec![root.to_path_buf()];
    while let Some(folder) = folders.pop() {
        for entry in fs::read_dir(&folder).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                folders.push(path);
            } else {
                let modified = fs::metadata(&path).unwrap().modified().unwrap();
                let sum = data_encoding::HEXLOWER.encode(&Sha256::digest(fs::read(&path).unwrap()));
                sums.insert(path.strip_prefix(root).unwrap().display().to_string(), format!("{sum}@{modified:?}"));
            }
        }
    }
    sums
}
