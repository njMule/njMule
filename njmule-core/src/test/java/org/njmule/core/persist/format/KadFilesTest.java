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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.NodesDat.Contact;
import org.njmule.core.persist.format.NodesDat.Contents;
import org.njmule.core.persist.format.NodesDat.UdpKey;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.util.Ipv4;

class KadFilesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final UInt128 ID = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");
    // The id on the wire: four little-endian words, most significant first.
    private static final String ID_WIRE = "33221100" + "77665544" + "BBAA9988" + "FFEEDDCC";
    private static final String BOOTSTRAP_RECORD = ID_WIRE + "04030201" + "3612" + "3712" + "08";

    private UInt128 id;
    private UInt128 resultingId;
    private String hex;
    private int bit;
    private long top32;
    private int comparison;
    private Contact contact;
    private PreferencesKadDat.Contents kadPreferences;
    private PreferencesKadDat.Contents kadPreferencesRead;
    private byte[] file;
    private Contents contents;
    private Throwable exception;

    @Test
    void shouldWriteAnIdAsLittleEndianWordsOnTheWire() {
        givenTheId(ID);

        whenTheIdIsWrittenForTheWire();

        thenTheHexIgnoringCaseIs(ID_WIRE);
    }

    @Test
    void shouldReadAnIdFromLittleEndianWordsOnTheWire() throws DecodeException {
        givenTheFile(ID_WIRE);

        whenAnIdIsReadFromTheWire();

        thenTheResultingIdIs(ID);
    }

    @Test
    void shouldTakeAnIdFromABigEndianHash() {
        whenAnIdIsTakenFromTheHash("00112233445566778899AABBCCDDEEFF");

        thenTheResultingIdIs(ID);
    }

    @Test
    void shouldGiveAnIdAsABigEndianHash() {
        givenTheId(ID);

        whenTheIdIsTurnedIntoAHash();

        thenTheHexIs("00112233445566778899AABBCCDDEEFF");
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 0", "127, 1"})
    void shouldCountTheBitsFromTheMostSignificant(int index, int expected) {
        givenTheId("80000000000000000000000000000001");

        whenTheBitIsRead(index);

        thenTheBitIs(expected);
    }

    @Test
    void shouldTakeTheTop32BitsFromTheMostSignificant() {
        givenTheId("80000000000000000000000000000001");

        whenTheTop32BitsAreRead();

        thenTheTop32BitsAre(0x8000_0000L);
    }

    @Test
    void shouldCompareIdsFromTheMostSignificantBit() {
        givenTheId("80000000000000000000000000000001");

        whenTheIdIsComparedWith("7FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

        thenTheIdIsTheGreater();
    }

    @Test
    void shouldGiveZeroForAnIdXoredWithItself() {
        givenTheId("80000000000000000000000000000001");

        whenTheIdIsXoredWithItself();

        thenTheResultingIdIs(UInt128.ZERO);
    }

    @Test
    void shouldReadVersionZeroWithContactTypes() {
        givenTheFile("01000000" + ID_WIRE + "04030201" + "3612" + "3712" + "02");

        whenTheNodesFileIsRead();

        thenTheFileVersionIs(0);
        thenTheFirstContactIs(ID, "1.2.3.4", 4662, 4663);
        thenTheFirstContactHasTheType(2);
    }

    @Test
    void shouldReadVersionOneWithKadVersions() {
        givenTheFile("00000000" + "01000000" + "01000000" + ID_WIRE + "04030201" + "3612" + "3712" + "08");

        whenTheNodesFileIsRead();

        thenTheFirstContactHasTheKadVersion(8);
        thenTheFirstContactIsNotVerified();
    }

    @Test
    void shouldWriteVersionTwo() {
        givenAVerifiedContactWithAUdpKey();

        whenTheContactIsWritten();

        thenTheFileHasTheSize(12 + 34);
        thenTheFileStartsWith("00000000" + "02000000" + "01000000");
    }

    @Test
    void shouldReadBackTheVersionTwoItWrites() {
        givenAVerifiedContactWithAUdpKey();
        givenTheContactWritten();

        whenTheNodesFileIsRead();

        thenTheFileVersionIs(2);
        thenTheContactsAreOnlyTheOneWritten();
    }

    @Test
    void shouldRecogniseABootstrapList() {
        givenTheFile("00000000" + "03000000" + "01000000" + "02000000" + BOOTSTRAP_RECORD + BOOTSTRAP_RECORD);

        whenTheNodesFileIsRead();

        thenTheListIsABootstrapList();
        thenTheNumberOfContactsIs(2);
    }

    @Test
    void shouldRefuseABootstrapListShorterThanItsCount() {
        givenTheFile("00000000" + "03000000" + "01000000" + "02000000" + BOOTSTRAP_RECORD);

        whenTheNodesFileIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00000000" + "09000000", "00000000"})
    void shouldIgnoreUnknownVersions(String content) {
        givenTheFile(content);

        whenTheNodesFileIsRead();

        thenTheNumberOfContactsIs(0);
    }

    @Test
    void shouldRejectAnImpossibleCount() {
        givenTheFile("ffffff7f" + ID_WIRE);

        whenTheNodesFileIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldWriteTheKadPreferences() {
        givenTheKadPreferences(Ipv4.of(10, 0, 0, 1), ID);

        whenTheKadPreferencesAreWritten();

        thenTheFileHasTheSize(23);
        thenTheHexIgnoringCaseIs("0100000a" + "0000" + ID_WIRE + "00");
    }

    @Test
    void shouldReadBackTheKadPreferences() throws DecodeException {
        givenTheKadPreferences(Ipv4.of(10, 0, 0, 1), ID);
        givenTheKadPreferencesWritten();

        whenTheKadPreferencesAreRead();

        thenTheKadPreferencesReadAreTheOnesWritten();
    }

    @Property
    void shouldFailOnGarbageOnlyWithADecodeException(@ForAll @Size(max = 120) byte[] data) {
        whenGarbageIsReadAsNodesAndKadPreferences(data);

        thenNoExceptionButADecodeExceptionOccurred();
    }

    private void givenTheId(UInt128 value) {
        this.id = value;
    }

    private void givenTheId(String value) {
        this.id = UInt128.parseHex(value);
    }

    private void givenTheFile(String content) {
        this.file = HEX.parseHex(content);
    }

    private void givenAVerifiedContactWithAUdpKey() {
        this.contact = new Contact(
                ID, Ipv4.of(5, 6, 7, 8), 4672, 4662, 10, 0, new UdpKey(0x12345678, Ipv4.of(9, 9, 9, 9)), true);
    }

    private void givenTheContactWritten() {
        this.file = NodesDat.write(List.of(this.contact));
    }

    private void givenTheKadPreferences(int ip, UInt128 kadId) {
        this.kadPreferences = new PreferencesKadDat.Contents(ip, kadId);
    }

    private void givenTheKadPreferencesWritten() {
        this.file = PreferencesKadDat.write(this.kadPreferences);
    }

    private void whenTheIdIsWrittenForTheWire() {
        this.hex = HEX.formatHex(this.id.toWireBytes());
    }

    private void whenAnIdIsReadFromTheWire() throws DecodeException {
        this.resultingId = UInt128.fromWireBytes(this.file);
    }

    private void whenAnIdIsTakenFromTheHash(String hash) {
        this.resultingId = UInt128.of(Hash16.parseHex(hash));
    }

    private void whenTheIdIsTurnedIntoAHash() {
        this.hex = this.id.toHash16().toHex();
    }

    private void whenTheBitIsRead(int index) {
        this.bit = this.id.bit(index);
    }

    private void whenTheTop32BitsAreRead() {
        this.top32 = this.id.top32();
    }

    private void whenTheIdIsComparedWith(String other) {
        this.comparison = this.id.compareTo(UInt128.parseHex(other));
    }

    private void whenTheIdIsXoredWithItself() {
        this.resultingId = this.id.xor(this.id);
    }

    private void whenTheContactIsWritten() {
        this.file = NodesDat.write(List.of(this.contact));
    }

    private void whenTheNodesFileIsRead() {
        try {
            this.contents = NodesDat.read(this.file);
        } catch (DecodeException e) {
            this.exception = e;
        }
    }

    private void whenTheKadPreferencesAreWritten() {
        this.file = PreferencesKadDat.write(this.kadPreferences);
        this.hex = HEX.formatHex(this.file);
    }

    private void whenTheKadPreferencesAreRead() throws DecodeException {
        this.kadPreferencesRead = PreferencesKadDat.read(this.file);
    }

    private void whenGarbageIsReadAsNodesAndKadPreferences(byte[] data) {
        this.exception = catchThrowable(() -> {
            NodesDat.read(data);
            PreferencesKadDat.read(data);
        });
    }

    private void thenTheHexIs(String expected) {
        assertThat(this.hex).isEqualTo(expected);
    }

    private void thenTheHexIgnoringCaseIs(String expected) {
        assertThat(this.hex).isEqualToIgnoringCase(expected);
    }

    private void thenTheResultingIdIs(UInt128 expected) {
        assertThat(this.resultingId).isEqualTo(expected);
    }

    private void thenTheBitIs(int expected) {
        assertThat(this.bit).isEqualTo(expected);
    }

    private void thenTheTop32BitsAre(long expected) {
        assertThat(this.top32).isEqualTo(expected);
    }

    private void thenTheIdIsTheGreater() {
        assertThat(this.comparison).isPositive();
    }

    private void thenTheFileVersionIs(int version) {
        assertThat(this.exception).isNull();
        assertThat(this.contents.fileVersion()).isEqualTo(version);
    }

    private void thenTheFirstContactIs(UInt128 contactId, String ip, int udpPort, int tcpPort) {
        Contact first = this.contents.contacts().getFirst();
        assertThat(first.id()).isEqualTo(contactId);
        assertThat(Ipv4.format(first.ip())).isEqualTo(ip);
        assertThat(first.udpPort()).isEqualTo(udpPort);
        assertThat(first.tcpPort()).isEqualTo(tcpPort);
    }

    private void thenTheFirstContactHasTheType(int type) {
        assertThat(this.contents.contacts().getFirst().type()).isEqualTo(type);
    }

    private void thenTheFirstContactHasTheKadVersion(int version) {
        assertThat(this.exception).isNull();
        assertThat(this.contents.contacts().getFirst().version()).isEqualTo(version);
    }

    private void thenTheFirstContactIsNotVerified() {
        assertThat(this.contents.contacts().getFirst().verified()).isFalse();
    }

    private void thenTheFileHasTheSize(int size) {
        assertThat(this.file).hasSize(size);
    }

    private void thenTheFileStartsWith(String header) {
        assertThat(HEX.formatHex(this.file, 0, header.length() / 2)).isEqualTo(header);
    }

    private void thenTheContactsAreOnlyTheOneWritten() {
        assertThat(this.contents.contacts()).containsExactly(this.contact);
    }

    private void thenTheListIsABootstrapList() {
        assertThat(this.exception).isNull();
        assertThat(this.contents.bootstrap()).isTrue();
    }

    private void thenTheNumberOfContactsIs(int count) {
        assertThat(this.exception).isNull();
        assertThat(this.contents.contacts()).hasSize(count);
    }

    private void thenTheKadPreferencesReadAreTheOnesWritten() {
        assertThat(this.kadPreferencesRead).isEqualTo(this.kadPreferences);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenNoExceptionButADecodeExceptionOccurred() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }
}
