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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NjMuleInfoTest {

    @Test
    void versionIsAFilteredSemanticVersion() {
        assertThat(NjMuleInfo.version())
                .matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?")
                .isNotEqualTo("0.0.0-unknown");
    }

    @Test
    void startupNoticeFollowsTheGplRecommendation() {
        assertThat(NjMuleInfo.startupNotice("--warranty", "--license"))
                .contains("njMule", "Next generation Java Mule", NjMuleInfo.COPYRIGHT)
                .contains("ABSOLUTELY NO WARRANTY", "'--warranty'", "'--license'");
    }

    @Test
    void licenseAndNoticeAreBundled() {
        assertThat(NjMuleInfo.licenseText()).contains("GNU GENERAL PUBLIC LICENSE", "Version 3");
        assertThat(NjMuleInfo.noticeText()).contains("Third-party components");
    }

    @Test
    void warrantyTextIsSectionsFifteenAndSixteen() {
        assertThat(NjMuleInfo.warrantyText())
                .startsWith("15. Disclaimer of Warranty.")
                .contains("16. Limitation of Liability.")
                .doesNotContain("17. Interpretation");
    }
}
