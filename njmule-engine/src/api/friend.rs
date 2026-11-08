// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A friend: a client we let do more than others, known by its user hash.

use crate::server::server_list::Address;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Friend {
    pub user_hash: [u8; 16],
    pub name: String,
    /// Where it was last seen.
    pub address: Option<Address>,
}

impl Friend {
    /// The friend, its name without the blanks around it.
    pub fn new(user_hash: [u8; 16], name: &str, address: Option<Address>) -> Self {
        Self { user_hash, name: name.trim().to_owned(), address }
    }
}
