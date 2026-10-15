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

    private String text;

    @Test
    void shouldGiveAFilteredSemanticVersion() {
        whenTheVersionIsRead();

        thenTheTextMatches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?");
        thenTheTextIsNot("0.0.0-unknown");
    }

    @Test
    void shouldFollowTheGplRecommendationInTheStartupNotice() {
        whenTheStartupNoticeIsMadeWithTheHints("--warranty", "--license");

        thenTheTextContains("njMule", "Next generation Java Mule", NjMuleInfo.COPYRIGHT);
        thenTheTextContains("ABSOLUTELY NO WARRANTY", "'--warranty'", "'--license'");
    }

    @Test
    void shouldBundleTheLicense() {
        whenTheLicenseIsRead();

        thenTheTextContains("GNU GENERAL PUBLIC LICENSE", "Version 3");
    }

    @Test
    void shouldBundleTheNotice() {
        whenTheNoticeIsRead();

        thenTheTextContains("Third-party components");
    }

    @Test
    void shouldGiveSectionsFifteenAndSixteenAsTheWarrantyText() {
        whenTheWarrantyIsRead();

        thenTheTextStartsWith("15. Disclaimer of Warranty.");
        thenTheTextContains("16. Limitation of Liability.");
        thenTheTextDoesNotContain("17. Interpretation");
    }

    private void whenTheVersionIsRead() {
        this.text = NjMuleInfo.version();
    }

    private void whenTheStartupNoticeIsMadeWithTheHints(String warrantyHint, String licenseHint) {
        this.text = NjMuleInfo.startupNotice(warrantyHint, licenseHint);
    }

    private void whenTheLicenseIsRead() {
        this.text = NjMuleInfo.licenseText();
    }

    private void whenTheNoticeIsRead() {
        this.text = NjMuleInfo.noticeText();
    }

    private void whenTheWarrantyIsRead() {
        this.text = NjMuleInfo.warrantyText();
    }

    private void thenTheTextMatches(String regex) {
        assertThat(this.text).matches(regex);
    }

    private void thenTheTextIsNot(String unexpected) {
        assertThat(this.text).isNotEqualTo(unexpected);
    }

    private void thenTheTextContains(String... parts) {
        assertThat(this.text).contains(parts);
    }

    private void thenTheTextStartsWith(String prefix) {
        assertThat(this.text).startsWith(prefix);
    }

    private void thenTheTextDoesNotContain(String part) {
        assertThat(this.text).doesNotContain(part);
    }
}
