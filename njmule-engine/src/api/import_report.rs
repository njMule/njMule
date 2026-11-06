// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What an import of an eMule profile did, file by file.

use strum::{EnumString, IntoStaticStr, VariantArray};

/// The outcome for one file.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, EnumString, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "SCREAMING_SNAKE_CASE")]
pub enum Outcome {
    /// Taken into njMule.
    Imported,
    /// Would be taken, in a dry run.
    WouldImport,
    /// Left alone, for the reason given.
    Skipped,
    /// Not in the profile.
    Missing,
    /// Unreadable or refused.
    Failed,
}

impl Outcome {
    pub fn name(self) -> &'static str {
        self.into()
    }
}

/// The phrases of the reports, which the interfaces translate as `nj.import.d.<name>`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, IntoStaticStr, VariantArray)]
#[strum(serialize_all = "camelCase")]
pub enum Key {
    Text,
    InsideEmule,
    ShortLived,
    NoSection,
    Preferences,
    StartAtLogin,
    PortsOurs,
    Servers,
    Added,
    BootstrapNodes,
    KadContacts,
    KnownFiles,
    AichTrees,
    LeftOut,
    NoSharedFolder,
    Folders,
    NotShared,
    SingleFiles,
    Ranges,
    OurOwn,
    NextStart,
    Files,
    Friends,
    Searches,
    SpamMarkers,
    Comments,
    Addresses,
    Entries,
    Sections,
    NoCategory,
    Categories,
    WithoutPatterns,
    OwnIdentity,
    CloseEmule,
    UserHash,
    IdentityKey,
    Together,
    Credits,
    KadId,
    NotAsked,
    NoDownloads,
    NoDataFile,
    PauseFirst,
    Download,
    Copied,
}

impl Key {
    /// The key of the interfaces' translations.
    pub fn translation_key(self) -> String {
        let name: &'static str = self.into();
        format!("nj.import.d.{name}")
    }

    /// The phrase in English, where `%s` and `%d` take the arguments in turn.
    pub fn template(self) -> &'static str {
        match self {
            Self::Text => "%s",
            Self::InsideEmule => "njMule's configuration is in eMule's folder: nothing is taken",
            Self::ShortLived => "other nodes' entries, short-lived",
            Self::NoSection => "no [%s] section",
            Self::Preferences => "nick, limits, networks, obfuscation",
            Self::StartAtLogin => "start at login",
            Self::PortsOurs => "ports and folders stay njMule's",
            Self::Servers => "%d servers",
            Self::Added => "%d new",
            Self::BootstrapNodes => "%d bootstrap nodes",
            Self::KadContacts => "%d Kad contacts",
            Self::KnownFiles => "%d known files",
            Self::AichTrees => "%d AICH trees",
            Self::LeftOut => "%s left out: %s",
            Self::NoSharedFolder => "no shared folder",
            Self::Folders => "%d folders",
            Self::NotShared => "%d not shared: %s",
            Self::SingleFiles => "%d single files: not shared yet",
            Self::Ranges => "%d ranges",
            Self::OurOwn => "we have our own",
            Self::NextStart => "from the next start",
            Self::Files => "%d files",
            Self::Friends => "%d friends",
            Self::Searches => "%d searches",
            Self::SpamMarkers => "%d spam markers",
            Self::Comments => "%d file comments",
            Self::Addresses => "%d addresses",
            Self::Entries => "%d entries",
            Self::Sections => "%d sections",
            Self::NoCategory => "no category besides all downloads",
            Self::Categories => "%d categories",
            Self::WithoutPatterns => "%s without its file name patterns, which are not valid here",
            Self::OwnIdentity => "njMule keeps its own identity unless asked to adopt eMule's",
            Self::CloseEmule => "close eMule first: two clients with one identity spoil their credits",
            Self::UserHash => "user hash",
            Self::IdentityKey => "secure identification key",
            Self::Together => "the user hash and the key are taken only together",
            Self::Credits => "%d credits",
            Self::KadId => "Kad ID",
            Self::NotAsked => "downloads were not asked for",
            Self::NoDownloads => "no downloads in progress",
            Self::NoDataFile => "%s: no data file",
            Self::PauseFirst => "%s: pause it in eMule first",
            Self::Download => "%s, %d MiB",
            Self::Copied => "%s, %d MiB copied",
        }
    }
}

/// An argument of a phrase: a text or a whole number.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Arg {
    Text(String),
    Number(i64),
}

impl From<&str> for Arg {
    fn from(text: &str) -> Self {
        Self::Text(text.to_owned())
    }
}

impl From<String> for Arg {
    fn from(text: String) -> Self {
        Self::Text(text)
    }
}

impl From<i64> for Arg {
    fn from(number: i64) -> Self {
        Self::Number(number)
    }
}

impl From<u64> for Arg {
    fn from(number: u64) -> Self {
        Self::Number(i64::try_from(number).unwrap_or(i64::MAX))
    }
}

impl From<usize> for Arg {
    fn from(number: usize) -> Self {
        Self::Number(i64::try_from(number).unwrap_or(i64::MAX))
    }
}

/// A part of an item's detail: a translated text whose `%s` and `%d` take the arguments.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Phrase {
    pub key: Key,
    pub args: Vec<Arg>,
}

/// Between the phrases of an item, in every language.
pub const SEPARATOR: &str = "; ";

impl Phrase {
    pub fn of(key: Key, args: Vec<Arg>) -> Self {
        Self { key, args }
    }

    /// The phrase in English.
    pub fn english(&self) -> String {
        let mut text = String::new();
        let mut args = self.args.iter();
        let mut rest = self.key.template();
        while let Some(at) = rest.find('%') {
            let (before, after) = rest.split_at(at);
            text.push_str(before);
            let mut chars = after.chars().skip(1);
            match (chars.next(), args.next()) {
                (Some('s' | 'd'), Some(Arg::Text(arg))) => text.push_str(arg),
                (Some('s' | 'd'), Some(Arg::Number(arg))) => text.push_str(&arg.to_string()),
                _ => text.push('%'),
            }
            rest = after.get(2..).unwrap_or_default();
        }
        text.push_str(rest);
        text
    }
}

/// One file of the profile and what happened to it, told in phrases.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Item {
    pub file: String,
    pub outcome: Outcome,
    pub phrases: Vec<Phrase>,
}

impl Item {
    pub fn new(file: &str, outcome: Outcome, phrases: Vec<Phrase>) -> Self {
        Self { file: file.to_owned(), outcome, phrases }
    }

    /// An item told by a text of its own, such as an error's message, which stays untranslated.
    pub fn text(file: &str, outcome: Outcome, text: &str) -> Self {
        let phrases = if text.is_empty() { Vec::new() } else { vec![Phrase::of(Key::Text, vec![text.into()])] };
        Self::new(file, outcome, phrases)
    }

    /// The phrases in English, one after the other.
    pub fn detail(&self) -> String {
        self.phrases.iter().map(Phrase::english).collect::<Vec<_>>().join(SEPARATOR)
    }
}

/// `start_at_login`: eMule starts when the user logs in, and the desktop app does the same after the import.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportReport {
    pub dry_run: bool,
    pub items: Vec<Item>,
    pub start_at_login: bool,
}
