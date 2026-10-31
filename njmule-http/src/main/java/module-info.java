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

module org.njmule.http {
    // njMule over HTTP, served by Jetty over the engine's public API: the web interface and the
    // desktop window's control endpoint.
    requires transitive org.njmule.core;
    requires com.google.common;
    requires org.slf4j;
    requires org.eclipse.jetty.client;
    requires org.eclipse.jetty.server;
    requires tools.jackson.core;

    exports org.njmule.http;
}
