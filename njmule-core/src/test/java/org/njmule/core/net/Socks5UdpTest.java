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
package org.njmule.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.config.ProxySettings;

@Timeout(30)
class Socks5UdpTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final InetSocketAddress PEER = new InetSocketAddress("10.1.2.3", 4672);

    private FakeSocks5Proxy proxy;
    private Socks5Udp association;
    private ProxySettings settings;
    private Throwable exception;
    private byte[] wrapped;
    private Optional<UdpRoute.Received> unwrapped;
    private DatagramSocket client;
    private DatagramSocket peer;
    private String delivered;

    @AfterEach
    void close() throws IOException {
        if (this.association != null) {
            this.association.close();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
        for (DatagramSocket socket : new DatagramSocket[] {this.client, this.peer}) {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @Test
    void shouldGetARelayFromAProxyWithoutALogin() throws Exception {
        givenAProxy(FakeSocks5Proxy.start());

        whenAnAssociationIsAsked();

        thenTheAssociationIsOpen();
        thenTheProxySaw("associate");
    }

    @Test
    void shouldLogInWithTheUserAndPasswordOfTheSettings() throws Exception {
        givenAProxy(FakeSocks5Proxy.withLogin("mule", "s3cret"));
        givenTheLogin("mule", "s3cret");

        whenAnAssociationIsAsked();

        thenTheAssociationIsOpen();
        thenTheProxySaw("login mule:s3cret", "associate");
    }

    @Test
    void shouldTellAProxyThatRelaysNoUdp() throws Exception {
        givenAProxy(FakeSocks5Proxy.refusingUdp());

        whenAnAssociationIsAsked();

        thenTheProxyRelaysNoUdp();
    }

    @Test
    void shouldTellAProxyThatWantsALoginTheSettingsLack() throws Exception {
        givenAProxy(FakeSocks5Proxy.withLogin("mule", "s3cret"));

        whenAnAssociationIsAsked();

        thenTheProxyRelaysNoUdp();
    }

    @Test
    void shouldTellAWrongPassword() throws Exception {
        givenAProxy(FakeSocks5Proxy.withLogin("mule", "s3cret"));
        givenTheLogin("mule", "wrong");

        whenAnAssociationIsAsked();

        thenTheProxyRelaysNoUdp();
    }

    @Test
    void shouldTellAnHttpProxy() throws Exception {
        givenTheSettings(new ProxySettings(ProxySettings.Type.HTTP, "127.0.0.1", 3128, "", ""));

        whenAnAssociationIsAsked();

        thenTheProxyRelaysNoUdp();
    }

    @Test
    void shouldEndWhenTheProxyEndsTheAssociation() throws Exception {
        givenAProxy(FakeSocks5Proxy.start());
        givenAnAssociation();

        whenTheProxyEndsItsAssociations();

        thenTheAssociationEnds();
    }

    @Test
    void shouldCarryADatagramThroughTheRelayAndItsAnswerBack() throws Exception {
        givenAProxy(FakeSocks5Proxy.start());
        givenAnAssociation();
        givenAClientAndAPeerSocket();

        whenTheClientSendsThroughTheRelayAndThePeerAnswers("ping", "pong");

        thenTheProxySaw("relay to 127.0.0.1:" + this.peer.getLocalPort());
        thenTheClientGotFromThePeer("pong");
    }

    @Test
    void shouldReadTheSenderOfAWrappedDatagram() {
        givenADatagramWrappedFor(PEER, "hello");

        whenItIsUnwrapped();

        thenItCameFrom(PEER, "hello");
    }

    @Test
    void shouldDropAFragment() {
        givenADatagramWrappedFor(PEER, "hello");
        givenTheFragmentNumber(1);

        whenItIsUnwrapped();

        thenNothingIsRead();
    }

    @Test
    void shouldDropADatagramShorterThanItsHeader() {
        givenADatagram(new byte[] {0, 0, 0, 1, 10, 1, 2});

        whenItIsUnwrapped();

        thenNothingIsRead();
    }

    @Test
    void shouldDropADatagramFromANamedSender() {
        givenADatagramWrappedFor(PEER, "hello");
        givenTheAddressType(3);

        whenItIsUnwrapped();

        thenNothingIsRead();
    }

    private void givenAProxy(FakeSocks5Proxy fake) {
        this.proxy = fake;
        this.settings = fake.settings();
    }

    private void givenTheLogin(String user, String password) {
        this.settings = this.proxy.settings(user, password);
    }

    private void givenTheSettings(ProxySettings chosen) {
        this.settings = chosen;
    }

    private void givenAnAssociation() throws IOException {
        this.association = Socks5Udp.associate(this.settings, null, WAIT);
    }

    private void givenAClientAndAPeerSocket() throws IOException {
        this.client = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        this.peer = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        this.client.setSoTimeout((int) WAIT.toMillis());
        this.peer.setSoTimeout((int) WAIT.toMillis());
    }

    private void givenADatagramWrappedFor(InetSocketAddress to, String text) {
        this.wrapped = Socks5Udp.wrap(to, text.getBytes(StandardCharsets.US_ASCII));
    }

    private void givenADatagram(byte[] data) {
        this.wrapped = data.clone();
    }

    private void givenTheFragmentNumber(int fragment) {
        this.wrapped[2] = (byte) fragment;
    }

    private void givenTheAddressType(int type) {
        this.wrapped[3] = (byte) type;
    }

    private void whenAnAssociationIsAsked() {
        this.exception = catchThrowable(() -> this.association = Socks5Udp.associate(this.settings, null, WAIT));
    }

    private void whenTheProxyEndsItsAssociations() throws IOException {
        this.proxy.endAssociations();
    }

    private void whenTheClientSendsThroughTheRelayAndThePeerAnswers(String question, String answer) throws IOException {
        InetSocketAddress peerAt = new InetSocketAddress(InetAddress.getLoopbackAddress(), this.peer.getLocalPort());
        DatagramPacket out = UdpRoute.through(this.association)
                .outgoing(peerAt, question.getBytes(StandardCharsets.US_ASCII))
                .orElseThrow();
        this.client.send(out);
        byte[] buffer = new byte[1024];
        DatagramPacket asked = new DatagramPacket(buffer, buffer.length);
        this.peer.receive(asked);
        assertThat(new String(buffer, 0, asked.getLength(), StandardCharsets.US_ASCII))
                .isEqualTo(question);
        byte[] reply = answer.getBytes(StandardCharsets.US_ASCII);
        this.peer.send(new DatagramPacket(reply, reply.length, asked.getSocketAddress()));
        DatagramPacket back = new DatagramPacket(new byte[1024], 1024);
        this.client.receive(back);
        UdpRoute.Received read =
                UdpRoute.through(this.association).incoming(back).orElseThrow();
        assertThat(read.from().getPort()).isEqualTo(this.peer.getLocalPort());
        this.delivered = new String(back.getData(), read.offset(), read.length(), StandardCharsets.US_ASCII);
    }

    private void whenItIsUnwrapped() {
        this.unwrapped = Socks5Udp.unwrap(this.wrapped, 0, this.wrapped.length);
    }

    private void thenTheAssociationIsOpen() {
        assertThat(this.exception).isNull();
        assertThat(this.association.isOpen()).isTrue();
        assertThat(this.association.relay().getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
    }

    private void thenTheProxySaw(String... events) {
        assertThat(this.proxy.seen()).contains(events);
    }

    private void thenTheProxyRelaysNoUdp() {
        assertThat(this.exception).isInstanceOf(Socks5Udp.Unsupported.class);
    }

    private void thenTheAssociationEnds() throws InterruptedException {
        Thread done = Thread.ofVirtual().start(() -> {
            try {
                this.association.awaitEnd();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(done.join(Duration.ofSeconds(10))).isTrue();
        assertThat(this.association.isOpen()).isFalse();
    }

    private void thenTheClientGotFromThePeer(String answer) {
        assertThat(this.delivered).isEqualTo(answer);
    }

    private void thenItCameFrom(InetSocketAddress from, String text) {
        UdpRoute.Received read = this.unwrapped.orElseThrow();
        assertThat(read.from()).isEqualTo(from);
        assertThat(new String(this.wrapped, read.offset(), read.length(), StandardCharsets.US_ASCII))
                .isEqualTo(text);
    }

    private void thenNothingIsRead() {
        assertThat(this.unwrapped).isEmpty();
    }
}
