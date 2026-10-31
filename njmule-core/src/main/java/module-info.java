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

module org.njmule.core {
    // The njMule engine: everything that is not user interface. Front ends (command line, web
    // interface, desktop window through the API) only use the exported packages: "api" (engine facade, events,
    // application
    // information), "config" (preferences and directory layout) and "remote" (the remote-control
    // API, which a front end serves on its HTTP server). Everything else is internal.
    requires com.google.common;
    requires static com.google.errorprone.annotations;
    requires jakarta.mail;
    requires java.net.http;
    requires java.xml;
    requires org.apache.commons.compress;
    requires org.eclipse.jetty.client;
    requires transitive org.eclipse.jetty.server;
    requires org.bouncycastle.provider;
    requires org.semver4j;
    requires tech.kwik.core;
    requires org.slf4j;
    requires org.tukaani.xz;
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires com.networknt.schema;

    exports org.njmule.core.api;
    exports org.njmule.core.config;
    exports org.njmule.core.remote;

    // The test kit's fake server and peers speak the protocol through the internal codecs.
    exports org.njmule.core.files.hash to
            org.njmule.testkit;
    exports org.njmule.core.protocol.crypt to
            org.njmule.testkit;
    exports org.njmule.core.persist.format to
            org.njmule.testkit;
    exports org.njmule.core.protocol.ed2k to
            org.njmule.testkit;
    exports org.njmule.core.protocol.io to
            org.njmule.testkit;
    exports org.njmule.core.protocol.packet to
            org.njmule.testkit;
    exports org.njmule.core.protocol.tag to
            org.njmule.testkit;
    exports org.njmule.core.search to
            org.njmule.testkit;
    exports org.njmule.core.util to
            org.njmule.testkit;
}
