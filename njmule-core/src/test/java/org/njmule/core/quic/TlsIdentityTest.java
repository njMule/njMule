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
package org.njmule.core.quic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TlsIdentityTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");

    private X509Certificate certificate;

    @Test
    void shouldSignItsCertificateWithItsOwnKey() throws GeneralSecurityException {
        whenAnIdentityIsMadeAt(NOW);

        thenTheCertificateVerifiesWithItsOwnKey();
        thenTheCertificateIsIssuedByItsSubject();
    }

    @Test
    void shouldBeValidFromAnHourBeforeForFiveYears() throws GeneralSecurityException {
        whenAnIdentityIsMadeAt(NOW);

        thenTheCertificateIsValidBetween(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofDays(5 * 365L)));
    }

    private void whenAnIdentityIsMadeAt(Instant now) throws GeneralSecurityException {
        this.certificate = TlsIdentity.selfSigned(new SecureRandom(), now).certificate();
    }

    private void thenTheCertificateVerifiesWithItsOwnKey() {
        X509Certificate c = this.certificate;
        assertThatCode(() -> c.verify(c.getPublicKey())).doesNotThrowAnyException();
        assertThat(c.getPublicKey().getAlgorithm()).isEqualTo("EC");
    }

    private void thenTheCertificateIsIssuedByItsSubject() {
        assertThat(this.certificate.getIssuerX500Principal()).isEqualTo(this.certificate.getSubjectX500Principal());
        assertThat(this.certificate.getSubjectX500Principal().getName()).isEqualTo("CN=njMule QUIC");
    }

    private void thenTheCertificateIsValidBetween(Instant from, Instant to) {
        assertThat(this.certificate.getNotBefore().toInstant()).isEqualTo(from);
        assertThat(this.certificate.getNotAfter().toInstant()).isEqualTo(to);
    }
}
