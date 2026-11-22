// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The eD2K links and the start at login of this computer, as the desktop window on it sets them through the API.

use std::io;
use std::path::PathBuf;
use std::sync::{Mutex, PoisonError};

use crate::app::autostart::{Autostart, SystemAutostart};
use crate::app::link_association::LinkAssociation;
use crate::remote::desktop_integration::{Change, DesktopError, DesktopIntegration, Settings};

/// A setting of the system, on or off.
pub trait Toggle: Send + Sync {
    fn is_on(&self) -> io::Result<bool>;
    fn set(&self, on: bool) -> io::Result<()>;
}

/// The eD2K links and the start at login of this computer.
pub struct DesktopSystem {
    links: Option<Box<dyn Toggle>>,
    start: Option<Box<dyn Toggle>>,
    changing: Mutex<()>,
}

impl DesktopSystem {
    pub fn new(links: Option<Box<dyn Toggle>>, start: Option<Box<dyn Toggle>>) -> Self {
        Self { links, start, changing: Mutex::new(()) }
    }

    /// This computer's, for the profile in `config_dir`; a start at login passes `arguments`.
    pub fn for_this_system(config_dir: PathBuf, arguments: &[String]) -> Self {
        let links = LinkAssociation::for_this_system(config_dir.clone())
            .map(|association| Box::new(LinksToggle(association)) as Box<dyn Toggle>);
        let start = SystemAutostart::for_this_system(config_dir, arguments)
            .map(|autostart| Box::new(AutostartToggle(autostart)) as Box<dyn Toggle>);
        Self::new(links, start)
    }

    fn read_settings(&self) -> io::Result<Settings> {
        Ok(Settings { links: state(self.links.as_deref())?, autostart: state(self.start.as_deref())? })
    }
}

impl DesktopIntegration for DesktopSystem {
    fn settings(&self) -> io::Result<Settings> {
        let _guard = self.changing.lock().unwrap_or_else(PoisonError::into_inner);
        self.read_settings()
    }

    fn change(&self, change: Change) -> Result<Settings, DesktopError> {
        if change.links.is_some() && self.links.is_none() {
            return Err(DesktopError::Unsupported("this njMule cannot open ed2k links on this system".to_owned()));
        }
        if change.autostart.is_some() && self.start.is_none() {
            return Err(DesktopError::Unsupported("this njMule cannot start at login on this system".to_owned()));
        }
        let _guard = self.changing.lock().unwrap_or_else(PoisonError::into_inner);
        if let (Some(on), Some(toggle)) = (change.links, self.links.as_deref()) {
            toggle.set(on)?;
        }
        if let (Some(on), Some(toggle)) = (change.autostart, self.start.as_deref()) {
            toggle.set(on)?;
        }
        Ok(self.read_settings()?)
    }
}

fn state(toggle: Option<&dyn Toggle>) -> io::Result<Option<bool>> {
    toggle.map(Toggle::is_on).transpose()
}

struct LinksToggle(LinkAssociation);

impl Toggle for LinksToggle {
    fn is_on(&self) -> io::Result<bool> {
        self.0.is_ours()
    }

    // Given up, the links go back to the program that opened them before.
    fn set(&self, on: bool) -> io::Result<()> {
        if on { self.0.take() } else { self.0.give_back() }
    }
}

struct AutostartToggle(SystemAutostart);

impl Toggle for AutostartToggle {
    fn is_on(&self) -> io::Result<bool> {
        self.0.is_on()
    }

    fn set(&self, on: bool) -> io::Result<()> {
        if on { self.0.turn_on() } else { self.0.turn_off() }
    }
}
