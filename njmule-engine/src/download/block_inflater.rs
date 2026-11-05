// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The zlib stream of one compressed block, inflated chunk by chunk as the chunks arrive.

use miniz_oxide::inflate::stream::{InflateState, inflate};
use miniz_oxide::{DataFormat, MZFlush, MZStatus};

use crate::protocol::io::DecodeError;

const CHUNK: usize = 16 * 1024;

pub struct BlockInflater {
    block_start: u64,
    max_output: u64,
    state: Box<InflateState>,
    produced: u64,
    finished: bool,
}

impl BlockInflater {
    pub fn new(block_start: u64, max_output: u64) -> Self {
        Self { block_start, max_output, state: InflateState::new_boxed(DataFormat::Zlib), produced: 0, finished: false }
    }

    /// Where the next inflated bytes go in the file.
    pub fn position(&self) -> u64 {
        self.block_start.saturating_add(self.produced)
    }

    pub fn finished(&self) -> bool {
        self.finished
    }

    /// Feeds one chunk; returns the bytes it inflated to.
    pub fn inflate(&mut self, mut input: &[u8]) -> Result<Vec<u8>, DecodeError> {
        let mut out = Vec::with_capacity(input.len().saturating_mul(4));
        let mut buffer = [0u8; CHUNK];
        // zlib may hold output back once all input is read: inflate until it gives nothing.
        while !self.finished {
            let result = inflate(&mut self.state, input, &mut buffer, MZFlush::None);
            input = input.get(result.bytes_consumed..).unwrap_or_default();
            let written = buffer.get(..result.bytes_written).unwrap_or_default();
            self.produced = self.produced.saturating_add(u64::try_from(written.len()).unwrap_or(u64::MAX));
            if self.produced > self.max_output {
                return Err(DecodeError::new(format!(
                    "compressed block inflates beyond the {} bytes requested",
                    self.max_output
                )));
            }
            out.extend_from_slice(written);
            match result.status {
                Ok(MZStatus::StreamEnd) => self.finished = true,
                Ok(_) if result.bytes_written == 0 && input.is_empty() => break,
                Ok(_) if result.bytes_written == 0 && result.bytes_consumed == 0 => {
                    return Err(DecodeError::new("compressed block makes no progress"));
                }
                Ok(_) => {}
                // Out of input with the stream not over: the next chunk goes on.
                Err(miniz_oxide::MZError::Buf) if input.is_empty() => break,
                Err(e) => return Err(DecodeError::new(format!("corrupt compressed block: {e:?}"))),
            }
        }
        Ok(out)
    }
}
