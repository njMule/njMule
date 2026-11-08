// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The web interface as its settings ask: started, stopped or started again as they change while njMule runs.

use tokio::sync::Mutex;

use crate::api::nj_mule_engine::NjMuleEngine;
use crate::remote::remote_api::Exit;
use crate::remote::web_ui::WebUi;
use crate::remote::web_ui_settings::WebUiSettings;

pub struct LiveWebUi<E> {
    engine: E,
    exit: Option<Exit>,
    state: Mutex<Live>,
}

#[derive(Default)]
struct Live {
    running: Option<WebUi>,
    followed: Option<WebUiSettings>,
    closed: bool,
}

impl<E: NjMuleEngine> LiveWebUi<E> {
    /// `exit` stops njMule when the interface's API asks.
    pub fn new(engine: E, exit: Option<Exit>) -> Self {
        Self { engine, exit, state: Mutex::default() }
    }

    /// Follows `settings`; the interface starts again only when they differ from those it runs with.
    pub async fn apply(&self, settings: WebUiSettings) {
        let mut live = self.state.lock().await;
        if live.closed || (live.running.is_some() && live.followed.as_ref() == Some(&settings)) {
            return;
        }
        if let Some(running) = live.running.take() {
            running.stop().await;
        }
        live.followed = Some(settings.clone());
        if !settings.usable() {
            return;
        }
        let port = settings.port;
        match WebUi::start(self.engine.clone(), settings, self.exit.clone()).await {
            Ok(ui) => live.running = Some(ui),
            Err(e) => tracing::warn!("Cannot start the web interface on port {port}: {e}"),
        }
    }

    /// The port the interface answers on, while it runs.
    pub async fn port(&self) -> Option<u16> {
        self.state.lock().await.running.as_ref().map(WebUi::port)
    }

    /// Stops the interface for good.
    pub async fn close(&self) {
        let mut live = self.state.lock().await;
        live.closed = true;
        if let Some(running) = live.running.take() {
            running.stop().await;
        }
    }
}
