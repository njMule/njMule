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
