// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The download categories as `categories.properties`: `category.ID.*` keys. A broken category is left out and logged.

use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};

use crate::api::category::Category;
use crate::api::download_info::DownloadPriority;
use crate::persist::{atomic_files, property_lines};
use crate::util::white_space::strip;

pub const FILE_NAME: &str = "categories.properties";

const PREFIX: &str = "category.";

/// The categories in the order of their ids; none without the file.
pub fn load(file: &Path) -> io::Result<Vec<Category>> {
    if !file.exists() && !atomic_files::bak_path(file).exists() {
        return Ok(Vec::new());
    }
    let properties = property_lines::read(file)?;
    let mut categories = Vec::new();
    for id in property_lines::numbers(&properties, PREFIX) {
        match category(&properties, id) {
            Ok(category) => categories.push(category),
            Err(reason) => {
                let name = file.file_name().unwrap_or_default().to_string_lossy();
                tracing::warn!("Ignoring category {id} of {name}: {reason}");
            }
        }
    }
    Ok(categories)
}

pub fn save(file: &Path, categories: &[Category]) -> io::Result<()> {
    let mut text = "# njMule download categories\n".to_owned();
    for category in categories {
        let key = |name: &str| format!("{PREFIX}{}.{name}", category.id);
        property_lines::append(&mut text, &key("name"), &category.name);
        let incoming = category.incoming.as_ref().map(|path| path.display().to_string()).unwrap_or_default();
        property_lines::append(&mut text, &key("incoming"), &incoming);
        property_lines::append(&mut text, &key("comment"), &category.comment);
        property_lines::append(&mut text, &key("color"), &category.color.to_string());
        property_lines::append(&mut text, &key("priority"), &category.priority.name().to_ascii_uppercase());
        property_lines::append(&mut text, &key("patterns"), &category.patterns);
        property_lines::append(&mut text, &key("regex"), &category.regex.to_string());
    }
    atomic_files::write(file, text.as_bytes())
}

fn category(properties: &BTreeMap<String, String>, id: i32) -> Result<Category, String> {
    let get = |name: &str| properties.get(&format!("{PREFIX}{id}.{name}")).map(String::as_str);
    let incoming = strip(get("incoming").unwrap_or_default());
    let color = strip(get("color").unwrap_or("0"));
    let priority = strip(get("priority").unwrap_or("AUTO"));
    Category {
        id: u32::try_from(id).map_err(|_| format!("a category id is 1 or more: {id}"))?,
        name: get("name").unwrap_or_default().to_owned(),
        incoming: (!incoming.is_empty()).then(|| PathBuf::from(incoming)),
        comment: get("comment").unwrap_or_default().to_owned(),
        color: color.parse().map_err(|_| format!("not a colour: {color}"))?,
        priority: DownloadPriority::of_name(priority).ok_or_else(|| format!("not a priority: {priority}"))?,
        patterns: get("patterns").unwrap_or_default().to_owned(),
        // As the Java engine reads it: only "true", in any case, is true.
        regex: get("regex").is_some_and(|regex| regex.eq_ignore_ascii_case("true")),
    }
    .validated()
}
