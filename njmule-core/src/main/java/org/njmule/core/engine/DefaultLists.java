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
package org.njmule.core.engine;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.njmule.core.kad.KadNode;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.server.ServerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server list and the Kad contacts a new profile starts from, shipped with njMule: those of
 * https://upd.emule-security.org, which the fields that update them suggest as well.
 */
final class DefaultLists {

    /** Set to {@code true}, a new profile starts with no server and no Kad contact: tests must not reach real ones. */
    static final String DISABLED_PROPERTY = "njmule.defaultlists.disabled";

    private static final Logger logger = LoggerFactory.getLogger(DefaultLists.class);

    private DefaultLists() {}

    static boolean enabled() {
        return !Boolean.getBoolean(DISABLED_PROPERTY);
    }

    static List<ServerMet.Server> servers() {
        try {
            return ServerMet.read(read(ServerList.FILE_NAME));
        } catch (IOException e) {
            logger.warn("Cannot read the server list shipped with njMule: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The contacts shipped with njMule, only to bootstrap from: every new user starts from the same list, so its nodes
     * are asked for contacts one at a time and never become anyone's contacts.
     */
    static NodesDat.Contents nodes() {
        try {
            NodesDat.Contents shipped = NodesDat.read(read(KadNode.NODES_FILE));
            return new NodesDat.Contents(shipped.fileVersion(), true, shipped.contacts());
        } catch (IOException e) {
            logger.warn("Cannot read the Kad contacts shipped with njMule: {}", e.getMessage());
            return new NodesDat.Contents(2, true, List.of());
        }
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream in = DefaultLists.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new FileNotFoundException(name);
            }
            return in.readAllBytes();
        }
    }
}
