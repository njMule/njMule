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
package org.njmule.core.remote;

import java.util.concurrent.Flow;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NjMuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The latest search results and log lines of an engine, collected from its events for front ends that answer later
 * requests: the web pages and the remote-control API.
 */
public final class RecentEvents implements AutoCloseable, Flow.Subscriber<EngineEvent> {

    private static final Logger logger = LoggerFactory.getLogger(RecentEvents.class);

    private final SearchCollector searches = new SearchCollector();
    private final RecentLog log = new RecentLog();
    private final Conversations conversations = new Conversations();
    private volatile Flow.Subscription subscription;

    private RecentEvents() {}

    /** Starts collecting the events of {@code engine}. */
    public static RecentEvents follow(NjMuleEngine engine) {
        RecentEvents recent = new RecentEvents();
        engine.events().subscribe(recent);
        return recent;
    }

    public SearchCollector searches() {
        return this.searches;
    }

    public RecentLog log() {
        return this.log;
    }

    public Conversations conversations() {
        return this.conversations;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(EngineEvent event) {
        switch (event) {
            case EngineEvent.SearchResults r -> this.searches.add(r);
            case EngineEvent.SearchEnded e -> this.searches.ended(e);
            case EngineEvent.Log l -> this.log.add(l.line());
            case EngineEvent.MessageReceived m -> this.conversations.received(m);
            case EngineEvent.CaptchaRequested c -> this.conversations.captcha(c);
            case EngineEvent.CaptchaAnswered a -> this.conversations.answered(a);
            case EngineEvent.MessageFailed f -> this.conversations.failed(f);
            default -> {
                // Front ends read the rest when they are asked for it.
            }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        logger.debug("Engine events ended", throwable);
    }

    @Override
    public void onComplete() {
        // The engine stopped; the next request reports it.
    }

    /** Stops collecting. */
    @Override
    public void close() {
        Flow.Subscription s = this.subscription;
        if (s != null) {
            s.cancel();
        }
    }
}
