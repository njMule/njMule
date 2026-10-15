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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat;

class IpFilterUpdateTest {

    private static final String EMULE_LIST = "001.002.003.000 - 001.002.003.255 , 000 , somebody\n";
    private static final String GUARDIAN_LIST = "somebody:1.2.4.0-1.2.4.255\n";

    private byte[] list;
    private List<IpFilterDat.Range> ranges;

    @Test
    void shouldFindTheFormOfAnIpFilterDatWhateverItsName() {
        givenTheList(EMULE_LIST);

        whenTheListIsReadFrom("https://example.org/ipfilter.zip");

        thenTheRangesFoundAre(1);
    }

    @Test
    void shouldFindTheFormOfAGuardianListWhateverItsName() {
        givenTheList(GUARDIAN_LIST);

        whenTheListIsReadFrom("https://example.org/guarding.p2p.gz");

        thenTheRangesFoundAre(1);
    }

    @Test
    void shouldFindTheFormOfAListAtAnAddressThatDoesNotNameIt() {
        givenTheList(GUARDIAN_LIST);

        whenTheListIsReadFrom("https://example.org/download?list=level1");

        thenTheRangesFoundAre(1);
    }

    @Test
    void shouldFindNoRangeInAPageThatIsNoList() {
        givenTheList("<html>nothing</html>");

        whenTheListIsReadFrom("https://x.org/");

        thenTheRangesFoundAre(0);
    }

    private void givenTheList(String content) {
        this.list = content.getBytes(StandardCharsets.US_ASCII);
    }

    private void whenTheListIsReadFrom(String address) {
        this.ranges = Engine.ipRanges(this.list, URI.create(address));
    }

    private void thenTheRangesFoundAre(int count) {
        assertThat(this.ranges).hasSize(count);
    }
}
