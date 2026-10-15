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
package org.njmule.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class LinkHandoffTest {

    private static final String LINK = "ed2k://|file|a.iso|10|" + "ab".repeat(16) + "|/";
    private static final String ANY_SECRET = "ab".repeat(32);
    // One character too many, and no more: nothing is left unread to reset the connection.
    private static final String TOO_LONG = "ed2k://" + "a".repeat(LinkHandoff.MAX_LINE - 6);

    @TempDir
    Path dir;

    private final BlockingQueue<List<String>> received = new LinkedBlockingQueue<>();
    private LinkHandoff handoff;
    private Path notADirectory;
    private Socket slow;
    private Thread trickle;
    private long start;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean delivered;
    private boolean gone;
    private boolean stillInterrupted;
    private String answer;
    private Throwable exception;

    @AfterEach
    void closeWhatIsStillOpen() throws IOException {
        if (this.trickle != null) {
            this.trickle.interrupt();
        }
        if (this.slow != null) {
            this.slow.close();
        }
        if (this.handoff != null) {
            this.handoff.close();
        }
        releaseTheLock();
    }

    @Test
    void shouldHandTheLinksOfALaterStartToTheRunningOne() throws Exception {
        givenARunningInstance();

        whenALaterStartDelivers(LINK, "http://example.org/not-a-link");

        thenTheLinksWereDelivered();
        thenTheRunningInstanceReceives(LINK);
    }

    @Test
    void shouldHandAStartThatOnlyWantsTheWindowToTheRunningOne() throws Exception {
        givenARunningInstance();

        whenALaterStartDelivers();

        thenTheLinksWereDelivered();
        thenTheRunningInstanceReceives();
    }

    @Test
    void shouldRemoveTheHandoffFileWhenTheRunningOneCloses() throws Exception {
        givenARunningInstance();

        whenTheRunningInstanceCloses();

        thenThereIsNoHandoffFile();
    }

    @Test
    void shouldFindNobodyListeningOnceTheRunningOneClosed() throws Exception {
        givenARunningInstance();
        givenTheRunningInstanceClosed();

        whenALaterStartDelivers(LINK);

        thenNobodyListens();
    }

    @Test
    void shouldKeepTheHandoffFileToItsOwner() throws Exception {
        whenTheInstanceStartsListening();

        thenOnlyTheOwnerCanReadTheHandoffFile();
    }

    @Test
    void shouldLetOnlyWhoKnowsTheSecretHandOverLinks() throws Exception {
        givenARunningInstance();
        givenAnotherSecretInTheHandoffFile();

        whenALaterStartDelivers(LINK);

        thenTheLinksWereNotDelivered();
        thenTheRunningInstanceReceivesNothingWithin(Duration.ofMillis(500));
    }

    @Test
    void shouldLeaveTheHandoffFileAnotherInstanceWrote() throws Exception {
        givenARunningInstance();
        givenAnotherSecretInTheHandoffFile();

        whenTheRunningInstanceCloses();

        thenTheHandoffFileStays();
    }

    @Test
    void shouldTakeTheLinksEvenIfTheReceiverFails() throws Exception {
        givenARunningInstanceThatCannotFollowLinks();

        whenALaterStartDelivers(LINK);

        thenTheLaterStartDoesNotRunBesideIt();
        thenTheRunningInstanceReceives(LINK);
    }

    @Test
    void shouldNotLetALinkSmuggleInAnotherLineOrAnEmptyOneEndTheList() throws Exception {
        givenARunningInstance();

        whenALaterStartDelivers("ed2k://|server|x|1|/\n" + LinkHandoff.EXIT, "", "a\rb", LINK);

        thenTheLinksWereDelivered();
        thenTheRunningInstanceReceives(LINK);
    }

    @Test
    void shouldTakeTheExitRequestOnlyAlone() throws Exception {
        givenARunningInstance();

        whenALaterStartSendsTheSecretAnd(LINK + "\n" + LinkHandoff.EXIT + "\n\n");

        thenTheAnswerStartsWith("OK");
        thenTheRunningInstanceReceives(LINK);
    }

    @Test
    void shouldNotLetASlowLocalProgramHoldAnybodyUp() throws Exception {
        givenARunningInstance();
        givenASlowLocalProgram();

        whenALaterStartDelivers(LINK);

        thenTheLinksWereDelivered();
        thenTheRunningInstanceReceives(LINK);
        thenTheSlowProgramIsDropped();
        thenItAllTookLessThan(LinkHandoff.DEADLINE.plusSeconds(5));
    }

    @Test
    void shouldTakeAFileLeftByACrashForNoRunningInstance() throws Exception {
        givenTheHandoffFile("1 " + ANY_SECRET + "\n");

        whenALaterStartDelivers(LINK);

        thenTheLinksWereNotDelivered();
    }

    @ParameterizedTest
    @ValueSource(strings = {"70000", "65536", "-1", "0"})
    void shouldTakeAFileWithAnInvalidPortForNoRunningInstance(String port) throws Exception {
        givenTheHandoffFile(port + " " + ANY_SECRET + "\n");

        whenALaterStartDelivers(LINK);

        thenTheLinksWereNotDelivered();
    }

    @ParameterizedTest
    @MethodSource("damagedContents")
    void shouldTakeADamagedFileForNoRunningInstance(String content) throws Exception {
        givenTheHandoffFile(content + "\n");

        whenALaterStartDelivers(LINK);

        thenTheLinksWereNotDelivered();
    }

    @Test
    void shouldTakeAnUnreadableFileForNoRunningInstance() throws Exception {
        givenTheHandoffFileIsADirectory();

        whenALaterStartDelivers(LINK);

        thenTheLinksWereNotDelivered();
    }

    @Test
    void shouldNeedAPlaceForTheFileToListen() throws Exception {
        givenAFileInPlaceOfTheConfigurationDirectory();

        whenTryingToListen();

        thenExceptionOccurred(IOException.class);
    }

    @Test
    void shouldCopeWithAFileAlreadyGoneWhenClosing() throws Exception {
        givenARunningInstance();
        givenTheHandoffFileWasDeleted();
        givenTheRunningInstanceClosed();

        whenALaterStartDelivers(LINK);

        thenTheLinksWereNotDelivered();
    }

    // Only the first line, which ends the exchange: nothing is left unread to reset the connection.
    @ParameterizedTest
    @ValueSource(strings = {"", "not hex\n", "ab\n"})
    void shouldGiveNoAnswerToAProgramWithoutTheSecret(String sent) throws Exception {
        givenARunningInstance();

        whenAProgramSends(sent);

        thenThereIsNoAnswer();
        thenTheRunningInstanceReceivesNothingWithin(Duration.ofMillis(300));
    }

    @Test
    void shouldTakeAtMostSoManyLinks() throws Exception {
        givenARunningInstance();

        whenALaterStartSendsTheSecretAnd(fileLinks(LinkHandoff.MAX_LINKS + 10));

        thenTheAnswerStartsWith("OK");
        thenTheRunningInstanceReceivesSoManyLinksStartingWith(LinkHandoff.MAX_LINKS, "|0.iso|");
    }

    @Test
    void shouldTakeTheLastLinkWithoutItsLineEnd() throws Exception {
        givenARunningInstance();

        whenALaterStartSendsTheSecretAnd(LINK);

        thenTheAnswerStartsWith("OK");
        thenTheRunningInstanceReceives(LINK);
    }

    @Test
    void shouldEndTheExchangeAtALineTooLong() throws Exception {
        givenARunningInstance();

        whenALaterStartSendsTheSecretAnd(TOO_LONG);

        thenThereIsNoAnswer();
        thenTheRunningInstanceReceivesNothingWithin(Duration.ofMillis(300));
    }

    @Test
    void shouldStillListenAfterALineTooLong() throws Exception {
        givenARunningInstance();
        givenALaterStartSentTheSecretAnd(TOO_LONG);

        whenALaterStartDelivers(LINK);

        thenTheRunningInstanceStillListens();
    }

    @Test
    void shouldNotTakeAnInstanceHoldingItsLockForGoneInAnInterruptedWait() throws Exception {
        givenAnInstanceHoldingItsLock();

        whenItIsAwaitedToGoWhileInterrupted(Duration.ofSeconds(5));

        thenItHasNotGone();
        thenTheThreadIsStillInterrupted();
    }

    @Test
    void shouldTakeAnInstanceForGoneOnceNobodyHoldsItsLock() throws Exception {
        givenAnInstanceHoldingItsLock();
        givenItReleasedItsLock();

        whenItIsAwaitedToGo(Duration.ofSeconds(5));

        thenItHasGone();
    }

    @Test
    void shouldTakeAnInstanceForGoneWhenItsLockCannotBeOpenedAndItsHandoffFileIsGone() throws Exception {
        givenALockThatCannotBeOpened();

        whenItIsAwaitedToGo(Duration.ofMillis(300));

        thenItHasGone();
    }

    @Test
    void shouldNotTakeAnInstanceForGoneWhenItsLockCannotBeOpenedAndItsHandoffFileStays() throws Exception {
        givenALockThatCannotBeOpened();
        givenTheHandoffFile("1 " + ANY_SECRET + "\n");

        whenItIsAwaitedToGo(Duration.ofMillis(300));

        thenItHasNotGone();
    }

    static Stream<String> damagedContents() {
        return Stream.of("4711", "port " + ANY_SECRET, "1 2 3");
    }

    private void givenARunningInstance() throws IOException {
        this.handoff = LinkHandoff.listen(this.dir, this.received::add);
    }

    private void givenARunningInstanceThatCannotFollowLinks() throws IOException {
        this.handoff = LinkHandoff.listen(this.dir, links -> {
            this.received.add(links);
            throw new IllegalArgumentException("cannot follow");
        });
    }

    private void givenTheRunningInstanceClosed() {
        whenTheRunningInstanceCloses();
    }

    private void givenAnotherSecretInTheHandoffFile() throws IOException {
        Files.writeString(
                this.dir.resolve(LinkHandoff.FILE_NAME),
                port() + " " + "00".repeat(32) + "\n",
                StandardCharsets.US_ASCII);
    }

    private void givenASlowLocalProgram() throws IOException {
        this.slow = new Socket(InetAddress.getLoopbackAddress(), port());
        Socket socket = this.slow;
        // A byte now and then, never a whole line.
        this.trickle = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    socket.getOutputStream().write('a');
                    Thread.sleep(200);
                }
            } catch (IOException | InterruptedException e) {
                // Closed.
            }
        });
        this.start = System.nanoTime();
    }

    private void givenTheHandoffFile(String content) throws IOException {
        Files.writeString(this.dir.resolve(LinkHandoff.FILE_NAME), content);
    }

    private void givenTheHandoffFileIsADirectory() throws IOException {
        Files.createDirectories(this.dir.resolve(LinkHandoff.FILE_NAME));
    }

    private void givenTheHandoffFileWasDeleted() throws IOException {
        Files.delete(this.dir.resolve(LinkHandoff.FILE_NAME));
    }

    private void givenAFileInPlaceOfTheConfigurationDirectory() throws IOException {
        this.notADirectory = Files.writeString(this.dir.resolve("config"), "a file");
    }

    private void givenALaterStartSentTheSecretAnd(String lines) throws IOException {
        exchange(secret() + "\n" + lines);
    }

    private void givenAnInstanceHoldingItsLock() throws IOException {
        this.lockChannel =
                FileChannel.open(this.dir.resolve("njmule.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        this.lock = this.lockChannel.lock();
    }

    private void givenItReleasedItsLock() throws IOException {
        releaseTheLock();
    }

    private void givenALockThatCannotBeOpened() throws IOException {
        Files.createDirectories(this.dir.resolve("njmule.lock"));
    }

    private void whenTheInstanceStartsListening() throws IOException {
        givenARunningInstance();
    }

    private void whenTryingToListen() {
        this.exception =
                catchThrowable(() -> this.handoff = LinkHandoff.listen(this.notADirectory, this.received::add));
    }

    private void whenALaterStartDelivers(String... links) {
        this.delivered = LinkHandoff.deliver(this.dir, List.of(links));
    }

    private void whenALaterStartSendsTheSecretAnd(String lines) throws IOException {
        this.answer = exchange(secret() + "\n" + lines);
    }

    private void whenAProgramSends(String text) throws IOException {
        this.answer = exchange(text);
    }

    private void whenTheRunningInstanceCloses() {
        this.handoff.close();
        this.handoff = null;
    }

    private void whenItIsAwaitedToGo(Duration timeout) {
        this.gone = LinkHandoff.awaitGone(this.dir, timeout);
    }

    private void whenItIsAwaitedToGoWhileInterrupted(Duration timeout) {
        Thread.currentThread().interrupt();
        try {
            this.gone = LinkHandoff.awaitGone(this.dir, timeout);
            this.stillInterrupted = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }
    }

    private void thenTheLinksWereDelivered() {
        assertThat(this.delivered).isTrue();
    }

    private void thenTheLinksWereNotDelivered() {
        assertThat(this.delivered).isFalse();
    }

    private void thenNobodyListens() {
        assertThat(this.delivered).as("nobody listens").isFalse();
    }

    private void thenTheLaterStartDoesNotRunBesideIt() {
        assertThat(this.delivered)
                .as("the later start must not try to run beside it")
                .isTrue();
    }

    private void thenTheRunningInstanceStillListens() {
        assertThat(this.delivered).as("still listening").isTrue();
    }

    private void thenTheRunningInstanceReceives(String... links) throws InterruptedException {
        assertThat(this.received.poll(5, TimeUnit.SECONDS)).containsExactly(links);
    }

    private void thenTheRunningInstanceReceivesSoManyLinksStartingWith(int count, String firstPart)
            throws InterruptedException {
        List<String> links = this.received.poll(5, TimeUnit.SECONDS);
        assertThat(links).hasSize(count);
        assertThat(links.getFirst()).contains(firstPart);
    }

    private void thenTheRunningInstanceReceivesNothingWithin(Duration timeout) throws InterruptedException {
        assertThat(this.received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS))
                .isNull();
    }

    private void thenTheAnswerStartsWith(String start) {
        assertThat(this.answer).startsWith(start);
    }

    private void thenThereIsNoAnswer() {
        assertThat(this.answer).isEmpty();
    }

    private void thenThereIsNoHandoffFile() {
        assertThat(this.dir.resolve(LinkHandoff.FILE_NAME)).doesNotExist();
    }

    private void thenTheHandoffFileStays() {
        assertThat(this.dir.resolve(LinkHandoff.FILE_NAME))
                .as("the file another instance wrote stays")
                .exists();
    }

    private void thenOnlyTheOwnerCanReadTheHandoffFile() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(this.dir.resolve(LinkHandoff.FILE_NAME))))
                    .isEqualTo("rw-------");
        }
    }

    private void thenTheSlowProgramIsDropped() throws IOException {
        this.slow.setSoTimeout(
                Math.toIntExact(LinkHandoff.DEADLINE.plusSeconds(5).toMillis()));
        assertThat(this.slow.getInputStream().read()).as("dropped").isEqualTo(-1);
    }

    private void thenItAllTookLessThan(Duration limit) {
        assertThat(Duration.ofNanos(System.nanoTime() - this.start)).isLessThan(limit);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenItHasGone() {
        assertThat(this.gone).isTrue();
    }

    private void thenItHasNotGone() {
        assertThat(this.gone).isFalse();
    }

    private void thenTheThreadIsStillInterrupted() {
        assertThat(this.stillInterrupted).isTrue();
    }

    private void releaseTheLock() throws IOException {
        if (this.lock != null) {
            this.lock.close();
            this.lock = null;
        }
        if (this.lockChannel != null) {
            this.lockChannel.close();
            this.lockChannel = null;
        }
    }

    private static String fileLinks(int count) {
        StringBuilder links = new StringBuilder();
        for (int i = 0; i < count; i++) {
            links.append("ed2k://|file|")
                    .append(i)
                    .append(".iso|10|")
                    .append("ab".repeat(16))
                    .append("|/\n");
        }
        return links.toString();
    }

    private int port() throws IOException {
        return Integer.parseInt(Files.readString(this.dir.resolve(LinkHandoff.FILE_NAME), StandardCharsets.US_ASCII)
                .split(" ", -1)[0]);
    }

    private String secret() throws IOException {
        return Files.readString(this.dir.resolve(LinkHandoff.FILE_NAME), StandardCharsets.US_ASCII)
                .strip()
                .split(" ", -1)[1];
    }

    // Sends text as a later start would, and returns the answer.
    private String exchange(String text) throws IOException {
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port())) {
            client.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            client.shutdownOutput();
            return new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
