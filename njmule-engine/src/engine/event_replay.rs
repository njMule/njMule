// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What a front end that follows the events late missed: the latest lines of the log, and the server we connect or are
//! connected to with what it said since. Other states it reads when it wants.

use std::collections::VecDeque;

use crate::api::engine_event::EngineEvent;

pub const LOG_LINES: usize = 100;
pub const SERVER_MESSAGES: usize = 50;

#[derive(Debug, Default)]
pub struct EventReplay {
    log: VecDeque<EngineEvent>,
    // The latest connecting or connected event, then the server's messages; empty while disconnected.
    server: VecDeque<EngineEvent>,
}

impl EventReplay {
    pub fn record(&mut self, event: &EngineEvent) {
        match event {
            EngineEvent::Log(_) => {
                if self.log.len() >= LOG_LINES {
                    self.log.pop_front();
                }
                self.log.push_back(event.clone());
            }
            EngineEvent::ServerConnecting(_) => {
                self.server.clear();
                self.server.push_back(event.clone());
            }
            EngineEvent::ServerConnected { server, .. } => {
                // What the server said while we logged in stays.
                self.server.retain(
                    |kept| matches!(kept, EngineEvent::ServerMessage { server: from, .. } if from.address == server.address),
                );
                self.server.push_front(event.clone());
            }
            EngineEvent::ServerDisconnected { .. } => self.server.clear(),
            EngineEvent::ServerMessage { .. } if !self.server.is_empty() && self.server.len() <= SERVER_MESSAGES => {
                self.server.push_back(event.clone());
            }
            _ => {}
        }
    }

    /// The events to tell first, in their order.
    pub fn missed(&self) -> Vec<EngineEvent> {
        self.log.iter().chain(&self.server).cloned().collect()
    }
}
