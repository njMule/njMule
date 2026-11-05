// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The engine's events as a server-sent event stream: `event`, `id` (counting from 1 per stream) and one line of JSON
//! `data` each. A reader that falls too far behind gets `lost` and the end of the stream, and connects again.

use std::convert::Infallible;
use std::time::Duration;

use axum::response::sse::{Event, KeepAlive, Sse};
use futures_util::future::ready;
use futures_util::stream::{self, Stream, StreamExt};
use serde_json::{Map, Value};
use tokio_stream::wrappers::BroadcastStream;

use crate::engine::Engine;
use crate::remote::api_json;

const KEEP_ALIVE: Duration = Duration::from_secs(15);

pub fn stream(engine: &Engine) -> Sse<impl Stream<Item = Result<Event, Infallible>> + use<>> {
    let events = BroadcastStream::new(engine.subscribe()).scan((0u64, false), |(id, ended), received| {
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
        *id = id.saturating_add(1);
        ready(Some(Ok(Event::default().event(name).id(id.to_string()).data(data.to_string()))))
    });
    let opening = stream::once(ready(Ok(Event::default().comment("njMule events"))));
    // The stream ends with the engine, so that the API can stop.
    Sse::new(opening.chain(events).take_until(engine.stopped())).keep_alive(KeepAlive::new().interval(KEEP_ALIVE))
}
