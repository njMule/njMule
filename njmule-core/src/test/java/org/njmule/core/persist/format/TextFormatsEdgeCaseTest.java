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
package org.njmule.core.persist.format;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.IpFilterDat.Range;
import org.njmule.core.util.Ipv4;

/** Corner cases of the text formats of an eMule profile. */
class TextFormatsEdgeCaseTest {

    @Test
    void aSectionLineMayEndWithAComment() {
        IniFile ini =
                IniFile.parse("[eMule]\nNick=a\n[Proxy] ;the proxy\nProxyName=h\n[a]b]\nk=v\n".getBytes(US_ASCII));

        assertThat(ini.get("Proxy", "ProxyName")).contains("h");
        assertThat(ini.get("eMule", "ProxyName")).isEmpty();
        assertThat(ini.get("a]b", "k")).contains("v");
        assertThat(ini.sections()).containsExactly("eMule", "Proxy", "a]b");
    }

    @Test
    void aLevelOfMoreThanThreeDigitsUsesTheFirstThree() {
        byte[] file = "001.002.003.000 - 001.002.003.255 , 0127 , Long level\n".getBytes(US_ASCII);

        assertThat(IpFilterDat.read(file, "ipfilter.dat"))
                .containsExactly(new Range(Ipv4.of(1, 2, 3, 0), Ipv4.of(1, 2, 3, 255), 12, "Long level"));
    }
}
