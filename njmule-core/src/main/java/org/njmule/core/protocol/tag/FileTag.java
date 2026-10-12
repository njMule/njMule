/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.protocol.tag;

/** File tag ids ({@code Opcodes.h} {@code FT_*}), used in .met files, search results and Kad. */
public final class FileTag {

    public static final int FILENAME = 0x01;
    public static final int FILESIZE = 0x02;
    public static final int FILETYPE = 0x03;
    public static final int FILEFORMAT = 0x04;
    public static final int LASTSEENCOMPLETE = 0x05;
    public static final int TRANSFERRED = 0x08;

    /** Gap start; in part.met files the name is {@code "\x09" + index} (a string name). */
    public static final int GAPSTART = 0x09;

    /** Gap end, exclusive; in part.met files the name is {@code "\x0A" + index}. */
    public static final int GAPEND = 0x0A;

    public static final int DESCRIPTION = 0x0B;
    public static final int PARTFILENAME = 0x12;
    public static final int STATUS = 0x14;
    public static final int SOURCES = 0x15;
    public static final int PERMISSIONS = 0x16;
    public static final int DLPRIORITY = 0x18;
    public static final int ULPRIORITY = 0x19;
    public static final int COMPRESSION = 0x1A;
    public static final int CORRUPTED = 0x1B;
    public static final int KADLASTPUBLISHKEY = 0x20;
    public static final int KADLASTPUBLISHSRC = 0x21;
    public static final int FLAGS = 0x22;
    public static final int DL_ACTIVE_TIME = 0x23;
    public static final int CORRUPTEDPARTS = 0x24;
    public static final int DL_PREVIEW = 0x25;
    public static final int KADLASTPUBLISHNOTES = 0x26;
    public static final int AICH_HASH = 0x27;
    public static final int FILEHASH = 0x28;
    public static final int COMPLETE_SOURCES = 0x30;
    public static final int COLLECTIONAUTHOR = 0x31;
    public static final int COLLECTIONAUTHORKEY = 0x32;
    public static final int PUBLISHINFO = 0x33;
    public static final int LASTSHARED = 0x34;
    public static final int AICHHASHSET = 0x35;
    public static final int KADAICHHASHPUB = 0x36;
    public static final int KADAICHHASHRESULT = 0x37;
    public static final int FOLDERNAME = 0x38;
    public static final int FILESIZE_HI = 0x3A;
    public static final int ATTRANSFERRED = 0x50;
    public static final int ATREQUESTED = 0x51;
    public static final int ATACCEPTED = 0x52;
    public static final int CATEGORY = 0x53;
    public static final int ATTRANSFERREDHI = 0x54;
    public static final int MAXSOURCES = 0x55;
    public static final int MEDIA_ARTIST = 0xD0;
    public static final int MEDIA_ALBUM = 0xD1;
    public static final int MEDIA_TITLE = 0xD2;
    public static final int MEDIA_LENGTH = 0xD3;
    public static final int MEDIA_BITRATE = 0xD4;
    public static final int MEDIA_CODEC = 0xD5;
    public static final int KADMISCOPTIONS = 0xF2;
    public static final int ENCRYPTION = 0xF3;
    public static final int USER_COUNT = 0xF4;
    public static final int FILE_COUNT = 0xF5;
    public static final int FILECOMMENT = 0xF6;
    public static final int FILERATING = 0xF7;
    public static final int BUDDYHASH = 0xF8;
    public static final int CLIENTLOWID = 0xF9;
    public static final int SERVERPORT = 0xFA;
    public static final int SERVERIP = 0xFB;
    public static final int SOURCEUPORT = 0xFC;
    public static final int SOURCEPORT = 0xFD;
    public static final int SOURCEIP = 0xFE;
    public static final int SOURCETYPE = 0xFF;

    private FileTag() {}
}
