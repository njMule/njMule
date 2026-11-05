// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The eD2K types of files, as servers index them.

pub const AUDIO: &str = "Audio";
pub const VIDEO: &str = "Video";
pub const IMAGE: &str = "Image";
/// Programs, archives and disc images.
pub const PROGRAM: &str = "Pro";
pub const DOCUMENT: &str = "Doc";
/// Searches for archives and disc images: the networks index both as [`PROGRAM`].
pub const ARCHIVE: &str = "Arc";
pub const DISC_IMAGE: &str = "Iso";

const EXTENSIONS: [(&str, &[&str]); 5] = [
    (
        AUDIO,
        &[
            "aac", "ac3", "aiff", "ape", "au", "flac", "m4a", "mid", "midi", "mp2", "mp3", "mpc", "ogg", "opus", "ra",
            "wav", "wma",
        ],
    ),
    (
        VIDEO,
        &[
            "3gp", "asf", "avi", "divx", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "ogm", "rm", "rmvb",
            "ts", "vob", "webm", "wmv", "xvid",
        ],
    ),
    (IMAGE, &["bmp", "gif", "ico", "jpeg", "jpg", "png", "psd", "svg", "tif", "tiff", "webp"]),
    (
        PROGRAM,
        &[
            "7z", "ace", "apk", "bat", "bin", "bz2", "cab", "com", "cue", "deb", "dmg", "exe", "gz", "img", "iso",
            "jar", "mdf", "mds", "msi", "nrg", "rar", "rpm", "tar", "tgz", "xz", "zip",
        ],
    ),
    (
        DOCUMENT,
        &[
            "chm", "csv", "doc", "docx", "epub", "htm", "html", "nfo", "odt", "pdf", "ppt", "pptx", "rtf", "txt",
            "xls", "xlsx",
        ],
    ),
];
const ARCHIVES: &[&str] = &["7z", "ace", "bz2", "cab", "gz", "rar", "tar", "tgz", "xz", "zip"];
const DISC_IMAGES: &[&str] = &["bin", "cue", "dmg", "img", "iso", "mdf", "mds", "nrg"];

/// The type of a file named `name`, if its extension tells.
pub fn of(name: &str) -> Option<&'static str> {
    let extension = extension(name);
    EXTENSIONS.iter().find(|(_, extensions)| extensions.contains(&extension.as_str())).map(|(kind, _)| *kind)
}

/// Whether a file named `name` is an [`ARCHIVE`] or a [`DISC_IMAGE`], as `kind` asks; false for other kinds.
pub fn is_a(kind: &str, name: &str) -> bool {
    let extension = extension(name);
    match kind {
        ARCHIVE => ARCHIVES.contains(&extension.as_str()),
        DISC_IMAGE => DISC_IMAGES.contains(&extension.as_str()),
        _ => false,
    }
}

/// Whether `kind` is one of the eD2K file types.
pub fn is_known(kind: &str) -> bool {
    code(kind).is_some()
}

/// The number servers with integer type tags use for `kind`.
pub fn code(kind: &str) -> Option<u32> {
    match kind {
        AUDIO => Some(1),
        VIDEO => Some(2),
        IMAGE => Some(3),
        PROGRAM => Some(4),
        DOCUMENT => Some(5),
        _ => None,
    }
}

fn extension(name: &str) -> String {
    name.rsplit_once('.').map(|(_, extension)| extension.to_lowercase()).unwrap_or_default()
}
