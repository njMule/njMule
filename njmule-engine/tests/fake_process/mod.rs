// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A process a test ends when it wants, with the exit code it wants, and a launcher whose starts the test answers.

#![allow(dead_code, clippy::unwrap_used, clippy::expect_used)]

use std::io;
use std::path::Path;
use std::sync::{Arc, Mutex};

use njmule_engine::update::launcher::{Launcher, Running};

#[derive(Clone, Default)]
pub struct FakeProcess {
    code: Arc<Mutex<Option<i32>>>,
}

impl FakeProcess {
    /// A process that has already ended with `code`.
    pub fn ended(code: i32) -> Self {
        let process = Self::default();
        process.end(code);
        process
    }

    pub fn end(&self, code: i32) {
        *self.code.lock().unwrap() = Some(code);
    }
}

impl Running for FakeProcess {
    fn is_alive(&mut self) -> bool {
        self.code.lock().unwrap().is_none()
    }

    fn exit_code(&mut self) -> Option<i32> {
        *self.code.lock().unwrap()
    }

    fn destroy(&mut self) {
        self.code.lock().unwrap().get_or_insert(-1);
    }
}

type Start = dyn Fn(&[String]) -> io::Result<FakeProcess> + Send + Sync;

/// A launcher that keeps each command started and lets `start` play the program.
pub struct FakeLauncher {
    pub started: Arc<Mutex<Vec<Vec<String>>>>,
    start: Box<Start>,
}

impl FakeLauncher {
    pub fn new(
        started: Arc<Mutex<Vec<Vec<String>>>>,
        start: impl Fn(&[String]) -> io::Result<FakeProcess> + Send + Sync + 'static,
    ) -> Self {
        Self { started, start: Box::new(start) }
    }
}

impl Launcher for FakeLauncher {
    fn start(&self, command: &[String], _log: Option<&Path>) -> io::Result<Box<dyn Running>> {
        self.started.lock().unwrap().push(command.to_vec());
        Ok(Box::new((self.start)(command)?))
    }
}
