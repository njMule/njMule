// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! File tag ids, used in .met files, search results and Kad.

pub const FILENAME: u8 = 0x01;
pub const FILESIZE: u8 = 0x02;
pub const FILETYPE: u8 = 0x03;
pub const FILEFORMAT: u8 = 0x04;
pub const LASTSEENCOMPLETE: u8 = 0x05;
pub const TRANSFERRED: u8 = 0x08;

/// Gap start; in part.met files the name is `"\x09" + index` (a string name).
pub const GAPSTART: u8 = 0x09;

/// Gap end, exclusive; in part.met files the name is `"\x0A" + index`.
pub const GAPEND: u8 = 0x0A;

pub const DESCRIPTION: u8 = 0x0B;
pub const PARTFILENAME: u8 = 0x12;
pub const STATUS: u8 = 0x14;
pub const SOURCES: u8 = 0x15;
pub const PERMISSIONS: u8 = 0x16;
pub const DLPRIORITY: u8 = 0x18;
pub const ULPRIORITY: u8 = 0x19;
pub const COMPRESSION: u8 = 0x1A;
pub const CORRUPTED: u8 = 0x1B;
pub const KADLASTPUBLISHKEY: u8 = 0x20;
pub const KADLASTPUBLISHSRC: u8 = 0x21;
pub const FLAGS: u8 = 0x22;
pub const DL_ACTIVE_TIME: u8 = 0x23;
pub const CORRUPTEDPARTS: u8 = 0x24;
pub const DL_PREVIEW: u8 = 0x25;
pub const KADLASTPUBLISHNOTES: u8 = 0x26;
pub const AICH_HASH: u8 = 0x27;
pub const FILEHASH: u8 = 0x28;
pub const COMPLETE_SOURCES: u8 = 0x30;
pub const COLLECTIONAUTHOR: u8 = 0x31;
pub const COLLECTIONAUTHORKEY: u8 = 0x32;
pub const PUBLISHINFO: u8 = 0x33;
pub const LASTSHARED: u8 = 0x34;
pub const AICHHASHSET: u8 = 0x35;
pub const KADAICHHASHPUB: u8 = 0x36;
pub const KADAICHHASHRESULT: u8 = 0x37;
pub const FOLDERNAME: u8 = 0x38;
pub const FILESIZE_HI: u8 = 0x3A;
pub const ATTRANSFERRED: u8 = 0x50;
pub const ATREQUESTED: u8 = 0x51;
pub const ATACCEPTED: u8 = 0x52;
pub const CATEGORY: u8 = 0x53;
pub const ATTRANSFERREDHI: u8 = 0x54;
pub const MAXSOURCES: u8 = 0x55;
pub const MEDIA_ARTIST: u8 = 0xD0;
pub const MEDIA_ALBUM: u8 = 0xD1;
pub const MEDIA_TITLE: u8 = 0xD2;
pub const MEDIA_LENGTH: u8 = 0xD3;
pub const MEDIA_BITRATE: u8 = 0xD4;
pub const MEDIA_CODEC: u8 = 0xD5;
pub const KADMISCOPTIONS: u8 = 0xF2;
pub const ENCRYPTION: u8 = 0xF3;
pub const USER_COUNT: u8 = 0xF4;
pub const FILE_COUNT: u8 = 0xF5;
pub const FILECOMMENT: u8 = 0xF6;
pub const FILERATING: u8 = 0xF7;
pub const BUDDYHASH: u8 = 0xF8;
pub const CLIENTLOWID: u8 = 0xF9;
pub const SERVERPORT: u8 = 0xFA;
pub const SERVERIP: u8 = 0xFB;
pub const SOURCEUPORT: u8 = 0xFC;
pub const SOURCEPORT: u8 = 0xFD;
pub const SOURCEIP: u8 = 0xFE;
pub const SOURCETYPE: u8 = 0xFF;
