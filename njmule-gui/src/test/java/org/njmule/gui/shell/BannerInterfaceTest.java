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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.njmule.core.api.EngineStatus;
import org.njmule.gui.Statuses;
import org.njmule.gui.model.EngineModel;

/** While the network interface the options bind to is gone, the banner says so whatever else the state says. */
class BannerInterfaceTest {

    private Banner.Kind kind;

    @ParameterizedTest
    @EnumSource(EngineModel.Connection.class)
    void shouldTellOfTheMissingInterfaceRatherThanOfBeingOffline(EngineModel.Connection connection) {
        whenTheInterfaceIsMissingAndTheConnectionIs(connection);
        thenTheBannerTellsOfTheMissingInterface();
    }

    private void whenTheInterfaceIsMissingAndTheConnectionIs(EngineModel.Connection connection) {
        EngineStatus s = Statuses.of(Optional.empty(), 0, false, Optional.empty(), false);
        EngineStatus missing = new EngineStatus(
                s.server(),
                s.clientId(),
                s.lowId(),
                s.obfuscated(),
                s.kad(),
                s.downloadBytesPerSecond(),
                s.uploadBytesPerSecond(),
                s.downloaded(),
                s.uploaded(),
                s.downloads(),
                s.uploads(),
                s.queued(),
                s.sharedFiles(),
                s.totalDownloaded(),
                s.totalUploaded(),
                s.sessions(),
                s.sessionSeconds(),
                s.totalSeconds(),
                s.publicIp(),
                s.tcpPort(),
                s.relatedSearch(),
                Optional.of("wg0"));
        this.kind = Banner.kindOf(connection, missing);
    }

    private void thenTheBannerTellsOfTheMissingInterface() {
        assertThat(this.kind).isEqualTo(Banner.Kind.INTERFACE_MISSING);
    }
}
