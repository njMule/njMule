// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The largest payload accepted for a TCP frame.

/// Largest payload accepted for a TCP frame, decided from its header before any allocation; `None` when the opcode is
/// not acceptable at all in the current connection state.
pub trait FrameLimit {
    fn max_payload(&self, protocol: u8, opcode: u8) -> Option<usize>;
}

impl<F: Fn(u8, u8) -> Option<usize>> FrameLimit for F {
    fn max_payload(&self, protocol: u8, opcode: u8) -> Option<usize> {
        self(protocol, opcode)
    }
}

/// The same cap for every frame.
pub fn fixed(max_payload: usize) -> impl FrameLimit {
    move |_: u8, _: u8| Some(max_payload)
}
