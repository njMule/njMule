// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine's events as a server-sent event stream: `event`, `id` (counting from 1 per stream) and one line of JSON
//! `data` each, of the kinds the reader wants. A reader that falls too far behind gets `lost` and the end of the
//! stream, and connects again.

use std::convert::Infallible;
use std::time::Duration;

use axum::response::sse::{Event, KeepAlive, Sse};
use futures_util::future::ready;
use futures_util::stream::{self, Stream, StreamExt};
use serde_json::{Map, Value};
use tokio::sync::{OwnedSemaphorePermit, watch};
use tokio_stream::wrappers::BroadcastStream;

use crate::api::nj_mule_engine::NjMuleEngine;
use crate::remote::api_json;

const KEEP_ALIVE: Duration = Duration::from_secs(15);
/// Streams open at once at most.
pub const MAX_STREAMS: usize = 8;

/// The events whose names start with one of `wanted`, all when it is empty. The stream ends with the engine or once
/// `closing` says the API closes, so that the API can stop; `permit` is held as long as it runs.
pub fn stream<E: NjMuleEngine>(
    engine: &E,
    wanted: Vec<String>,
    mut closing: watch::Receiver<bool>,
    permit: OwnedSemaphorePermit,
) -> Sse<impl Stream<Item = Result<Event, Infallible>> + use<E>> {
    let subscription = engine.subscribe();
    let missed = stream::iter(subscription.missed.into_iter().map(Ok));
    let events = missed
        .chain(BroadcastStream::new(subscription.live))
        .scan((0u64, false, permit), move |(id, ended, _), received| {
            if *ended {
                return ready(None);
            }
            let (name, data) = received.map_or_else(
                |_| {
                    *ended = true;
                    ("lost", Value::Object(Map::new()))
                },
                |event| api_json::event(&event),
            );
            if name != "lost" && !wanted.is_empty() && !wanted.iter().any(|prefix| name.starts_with(prefix.as_str())) {
                return ready(Some(None));
            }
            *id = id.saturating_add(1);
            ready(Some(Some(Ok(Event::default().event(name).id(id.to_string()).data(data.to_string())))))
        })
        .filter_map(ready);
    let opening = stream::once(ready(Ok(Event::default().comment("njMule events"))));
    let closed = async move {
        let _ = closing.wait_for(|closed| *closed).await;
    };
    let ended = futures_util::future::select(Box::pin(engine.stopped()), Box::pin(closed));
    Sse::new(opening.chain(events).take_until(ended)).keep_alive(KeepAlive::new().interval(KEEP_ALIVE))
}
