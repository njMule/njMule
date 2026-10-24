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
package org.njmule.core.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MinisignTest {

    // The public key and the signatures of the 4 bytes "test" that the reference minisign verifier tests itself with.
    private static final String REFERENCE_KEY = "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3";
    private static final String REFERENCE_HASHED = """
            untrusted comment: signature from minisign secret key
            RUQf6LRCGA9i559r3g7V1qNyJDApGip8MfqcadIgT9CuhV3EMhHoN1mGTkUidF/z7SrlQgXdy8ofjb7bNJJylDOocrCo8KLzZwo=
            trusted comment: timestamp:1556193335\tfile:test
            y/rUw2y8/hOUYjZU71eHp/Wo1KZ40fGy2VJEDl34XMJM+TX48Ss/17u3IvIfbVR1FkZZSNCisQbuQY+bHwhEBg==
            """;
    private static final String REFERENCE_LEGACY = """
            untrusted comment: signature from minisign secret key
            RWQf6LRCGA9i59SLOFxz6NxvASXDJeRtuZykwQepbDEGt87ig1BNpWaVWuNrm73YiIiJbq71Wi+dP9eKL8OC351vwIasSSbXxwA=
            trusted comment: timestamp:1555779966\tfile:test
            QtKMXWyYcwdpZAlPF7tE2ENJkRd1ujvKjlj1m9RtHTBnZPa5WKU5uWRs5GoP5M/VqE81QFuMKI5k/SfNQUaOAA==
            """;
    // The release keys of keys.pub by the ids signatures name them by; minisign prints these ids byte-reversed.
    private static final String SIGNING_KEY_ID = "2aa526fd33b0b09e";
    private static final String STANDBY_KEY_ID = "5019b4faebd5518e";

    private Map<String, Minisign.PublicKey> keys = Map.of();
    private String signature = "";
    private String trustedComment;
    private Throwable exception;

    @Test
    void shouldAcceptASignatureMadeByMinisign() {
        givenTheKeys(REFERENCE_KEY);
        givenTheSignature(REFERENCE_HASHED);

        whenVerifying("test");

        thenNoExceptionOccurred();
        thenTheTrustedCommentIs("timestamp:1556193335\tfile:test");
    }

    @Test
    void shouldRefuseAFileChangedSinceItWasSigned() {
        givenTheKeys(REFERENCE_KEY);
        givenTheSignature(REFERENCE_HASHED);

        whenVerifying("Test");

        thenExceptionOccurred(SignatureException.class, "the signature does not match");
    }

    @Test
    void shouldRefuseALegacySignature() {
        givenTheKeys(REFERENCE_KEY);
        givenTheSignature(REFERENCE_LEGACY);

        whenVerifying("test");

        thenExceptionOccurred(SignatureException.class, "legacy");
    }

    @Test
    void shouldRefuseAChangedTrustedComment() {
        givenTheKeys(REFERENCE_KEY);
        givenTheSignature(REFERENCE_HASHED.replace("timestamp:1556193335", "timestamp:1556193336"));

        whenVerifying("test");

        thenExceptionOccurred(SignatureException.class, "the trusted comment does not match");
    }

    @Test
    void shouldRefuseASignatureOfAKeyNotBuiltIn() {
        givenTheKeys(new TestSigning().publicKeyLine());
        givenTheSignature(REFERENCE_HASHED);

        whenVerifying("test");

        thenExceptionOccurred(SignatureException.class, "unknown key");
    }

    @Test
    void shouldRefuseTextThatIsNoSignature() {
        givenTheKeys(REFERENCE_KEY);
        givenTheSignature("<html>Not Found</html>");

        whenVerifying("test");

        thenExceptionOccurred(SignatureException.class, "not a minisign signature");
    }

    @Test
    void shouldAcceptASignatureOfAnyOfTheKeys() {
        TestSigning standby = new TestSigning();
        givenTheKeys(keyFile(new TestSigning(), standby));
        givenTheSignature(standby.sign("the manifest"));

        whenVerifying("the manifest");

        thenNoExceptionOccurred();
    }

    @Test
    void shouldReadEveryKeyOfAKeyFile() {
        whenTheKeysAreRead(keyFile(new TestSigning(), new TestSigning()));

        thenNoExceptionOccurred();
        thenThereAreKeys(2);
    }

    @Test
    void shouldRefuseALineThatIsNoPublicKey() {
        whenTheKeysAreRead("untrusted comment: minisign public key\nRWQnotakey\n");

        thenExceptionOccurred(IllegalArgumentException.class, "");
    }

    @Test
    void shouldBuildInTheSigningKeyFollowedByTheStandbyKey() {
        whenTheBuiltInKeysAreRead();

        thenNoExceptionOccurred();
        thenTheKeyIdsAre(SIGNING_KEY_ID, STANDBY_KEY_ID);
    }

    private void givenTheKeys(String text) {
        this.keys = Minisign.publicKeys(text);
    }

    private void givenTheSignature(String text) {
        this.signature = text;
    }

    private void whenVerifying(String data) {
        this.exception = catchThrowable(() -> this.trustedComment =
                Minisign.verify(data.getBytes(StandardCharsets.UTF_8), Minisign.signature(this.signature), this.keys));
    }

    private void whenTheKeysAreRead(String text) {
        this.exception = catchThrowable(() -> this.keys = Minisign.publicKeys(text));
    }

    private void whenTheBuiltInKeysAreRead() {
        this.exception = catchThrowable(() -> this.keys = Updates.shippedKeys());
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String message) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(message);
    }

    private void thenTheTrustedCommentIs(String expected) {
        assertThat(this.trustedComment).isEqualTo(expected);
    }

    private void thenThereAreKeys(int count) {
        assertThat(this.keys).hasSize(count);
    }

    private void thenTheKeyIdsAre(String... ids) {
        assertThat(this.keys.keySet()).containsExactly(ids);
    }

    private static String keyFile(TestSigning... signers) {
        StringBuilder text = new StringBuilder("# The keys of a test.\n");
        for (TestSigning signer : signers) {
            text.append("untrusted comment: minisign public key\n")
                    .append(signer.publicKeyLine())
                    .append('\n');
        }
        return text.toString();
    }
}
