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
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ServerAddressTest {

    private ServerAddress address;
    private String text;
    private ServerAddress parsed;
    private boolean valid;
    private Throwable exception;

    @Test
    void shouldParseAHostWithItsPort() {
        whenTheAddressIsParsed(" 192.168.1.10:4661 ");

        thenTheAddressIs(new ServerAddress("192.168.1.10", 4661));
    }

    @Test
    void shouldParseAnIpv6HostInBrackets() {
        whenTheAddressIsParsed("[2001:db8::1]:4661");

        thenTheHostIs("2001:db8::1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"server.example.org", "host:70000"})
    void shouldRefuseAHostWithoutAValidPort(String input) {
        whenTheAddressIsParsed(input);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"2001:db8::1, 4661", "::1, 1", "192.168.1.10, 65535", "server.example.org, 4242"})
    void shouldWriteAddressesThatParseBack(String host, int port) {
        givenTheAddress(host, port);

        whenItIsWrittenAndParsedBack();

        thenTheAddressParsedBackIsTheSame();
    }

    @Test
    void shouldWriteAnIpv6HostInBrackets() {
        givenTheAddress("2001:db8::1", 4661);

        whenItIsWritten();

        thenTheTextIs("[2001:db8::1]:4661");
    }

    @ParameterizedTest
    @CsvSource({
        "server.example.org, true",
        "localhost, true",
        "10.0.0.1, true",
        "2001:db8::1, true",
        "bad..host, false",
        "no spaces, false",
        "'', false"
    })
    void shouldTellWellFormedHosts(String host, boolean expected) {
        whenTheHostIsChecked(host);

        thenTheHostIsValid(expected);
    }

    @Test
    void shouldAcceptAPlainSearchExpression() {
        whenTheSearchExpressionIsChecked("ubuntu iso");

        thenNoExceptionOccurred();
    }

    @Test
    void shouldRefuseAnUnbalancedSearchExpression() {
        whenTheSearchExpressionIsChecked("(ubuntu");

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseASearchExpressionThatIsTooLong() {
        whenTheSearchExpressionIsChecked("x".repeat(SearchRequest.MAX_EXPRESSION_LENGTH + 1));

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    private void givenTheAddress(String host, int port) {
        this.address = new ServerAddress(host, port);
    }

    private void whenTheAddressIsParsed(String input) {
        this.exception = catchThrowable(() -> this.parsed = ServerAddress.parse(input));
    }

    private void whenItIsWrittenAndParsedBack() {
        this.text = this.address.toString();
        this.parsed = ServerAddress.parse(this.text);
    }

    private void whenItIsWritten() {
        this.text = this.address.toString();
    }

    private void whenTheHostIsChecked(String host) {
        this.valid = ServerAddress.isValidHost(host);
    }

    private void whenTheSearchExpressionIsChecked(String expression) {
        this.exception = catchThrowable(() -> SearchRequest.checkExpression(expression));
    }

    private void thenTheAddressIs(ServerAddress expected) {
        assertThat(this.exception).isNull();
        assertThat(this.parsed).isEqualTo(expected);
    }

    private void thenTheHostIs(String expected) {
        assertThat(this.exception).isNull();
        assertThat(this.parsed.host()).isEqualTo(expected);
    }

    private void thenTheAddressParsedBackIsTheSame() {
        assertThat(this.parsed).isEqualTo(this.address);
    }

    private void thenTheTextIs(String expected) {
        assertThat(this.text).isEqualTo(expected);
    }

    private void thenTheHostIsValid(boolean expected) {
        assertThat(this.valid).isEqualTo(expected);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }
}
