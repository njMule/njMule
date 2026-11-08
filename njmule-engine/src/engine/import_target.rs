// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What an import of an eMule profile changes in a running engine: the profile is read on a thread of its own, each
//! change is handed to the engine and waited for.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use tokio::runtime::Handle;

use super::Engine;
use crate::api::category::Category;
use crate::api::list_source::ListSource;
use crate::config::preferences::Preferences;
use crate::persist::emule_importer::Target;
use crate::persist::format::known_met::KnownFile;
use crate::persist::format::{known2_met, nodes_dat};

pub(super) struct ImportTarget {
    engine: Engine,
    // The runtime of the engine, whose answers the import's thread waits for.
    runtime: Handle,
}

impl ImportTarget {
    pub(super) fn new(engine: Engine, runtime: Handle) -> Self {
        Self { engine, runtime }
    }
}

impl Target for ImportTarget {
    fn config_dir(&self) -> PathBuf {
        self.engine.directories().config.clone()
    }

    fn update_preferences(
        &mut self,
        change: &dyn Fn(&Preferences) -> Result<Preferences, String>,
    ) -> Result<(), String> {
        self.engine.change_preferences(change).map(|_| ())
    }

    fn add_servers(&mut self, server_met: &Path) -> Result<usize, String> {
        let source = ListSource::File(server_met.to_path_buf());
        self.runtime.block_on(self.engine.add_servers(source)).map_err(|e| e.to_string())
    }

    fn add_kad_contacts(&mut self, contacts: &nodes_dat::Contents) -> Result<usize, String> {
        self.runtime.block_on(self.engine.keep_kad_contacts(contacts.clone())).map_err(|e| e.to_string())
    }

    fn add_known_files(&mut self, files: &[KnownFile], trees: &[known2_met::Entry]) -> usize {
        self.runtime.block_on(self.engine.add_known_files(files.to_vec(), trees.to_vec()))
    }

    fn share_directory(&mut self, directory: &Path) -> Result<(), String> {
        self.runtime.block_on(self.engine.share_directory(directory.to_path_buf(), false))
    }

    // Taken only when we have none of our own.
    fn add_categories(&mut self, categories: &[Category]) -> Result<usize, String> {
        self.runtime.block_on(async {
            if !self.engine.categories().await.is_empty() {
                return Ok(0);
            }
            self.engine.set_categories(categories.to_vec()).await?;
            Ok(categories.len())
        })
    }

    fn adopt_download(&mut self, met: &Path, data: &Path, category: u32) -> Result<String, String> {
        let temp = self.runtime.block_on(self.engine.temp_dir()).ok_or("the engine stopped")?;
        let stem = format!("import-{:016x}", rand::random::<u64>());
        // Names the loader of the downloads leaves alone, until the engine takes the copy over.
        let staged_met = temp.join(format!("{stem}.met.tmp"));
        let staged_data = temp.join(format!("{stem}.tmp"));
        let adopted = fs::copy(met, &staged_met)
            .and_then(|_| fs::copy(data, &staged_data))
            .map_err(|e| format!("Cannot copy the download: {e}"))
            .and_then(|_| {
                self.runtime
                    .block_on(self.engine.adopt_download(staged_met.clone(), staged_data.clone(), category))
                    .map_err(|e| e.to_string())
            });
        for staged in [&staged_met, &staged_data] {
            match fs::remove_file(staged) {
                Err(e) if e.kind() != io::ErrorKind::NotFound => {
                    tracing::warn!("Cannot remove {}: {e}", staged.display());
                }
                _ => {}
            }
        }
        adopted.map(|info| info.name)
    }
}
