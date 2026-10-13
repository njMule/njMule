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
package org.njmule.webui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Logged-in sessions: a random 128-bit token in a cookie, a separate token for forms against cross-site requests, and
 * an end after half an hour without use. Failed logins slow an address down. Thread-safe.
 */
final class Sessions {

    /** A session ends after this long without a request. */
    static final Duration IDLE = Duration.ofMinutes(30);

    /** Failed logins from one address before it must wait. */
    static final int MAX_FAILURES = 5;

    /** How long an address that failed too often waits. */
    static final Duration LOCKOUT = Duration.ofMinutes(1);

    private static final int TOKEN_BYTES = 16;

    /** One session; {@code csrf} goes into every form. */
    record Session(String id, String csrf) {}

    private record Entry(Session session, long lastUse) {}

    private record Failures(int count, long since) {}

    private final SecureRandom random;
    private final LongSupplier clock;
    private final Map<String, Entry> sessions = new HashMap<>();
    private final Map<String, Failures> failures = new HashMap<>();

    /** @param clock monotonic nanoseconds */
    Sessions(SecureRandom random, LongSupplier clock) {
        this.random = random;
        this.clock = clock;
    }

    synchronized Session open() {
        Session session = new Session(this.token(), this.token());
        this.sessions.put(session.id(), new Entry(session, this.clock.getAsLong()));
        return session;
    }

    /** The live session of {@code id}, whose use is noted. */
    synchronized Optional<Session> find(String id) {
        this.expire();
        Entry entry = this.sessions.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        this.sessions.put(id, new Entry(entry.session(), this.clock.getAsLong()));
        return Optional.of(entry.session());
    }

    synchronized void close(String id) {
        this.sessions.remove(id);
    }

    /** Whether {@code given} is the form token of {@code session}, compared in constant time. */
    static boolean validForm(Session session, String given) {
        return given != null
                && MessageDigest.isEqual(
                        session.csrf().getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }

    /** Whether {@code address} may try to log in now. */
    synchronized boolean mayTry(String address) {
        Failures f = this.failures.get(address);
        if (f == null || f.count() < MAX_FAILURES) {
            return true;
        }
        if (this.clock.getAsLong() - f.since() >= LOCKOUT.toNanos()) {
            this.failures.remove(address);
            return true;
        }
        return false;
    }

    synchronized void failed(String address) {
        Failures f = this.failures.get(address);
        this.failures.put(address, new Failures(f == null ? 1 : f.count() + 1, this.clock.getAsLong()));
    }

    synchronized void succeeded(String address) {
        this.failures.remove(address);
    }

    synchronized int count() {
        return this.sessions.size();
    }

    private void expire() {
        long now = this.clock.getAsLong();
        for (Iterator<Entry> it = this.sessions.values().iterator(); it.hasNext(); ) {
            if (now - it.next().lastUse() >= IDLE.toNanos()) {
                it.remove();
            }
        }
    }

    private String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        this.random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
