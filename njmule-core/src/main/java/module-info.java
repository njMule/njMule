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
    // The njMule engine: everything that is not user interface. Front ends (JavaFX GUI, command
    // line, web UI) only use the exported packages: "api" (engine facade, events, application
    // information) and "config" (preferences and directory layout). Everything else is internal.
    requires org.bouncycastle.provider;
    requires org.slf4j;

    exports org.njmule.core.api;
    exports org.njmule.core.config;
    exports org.njmule.core.engine to
            org.njmule.testkit;
}
