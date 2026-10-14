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
package org.njmule.gui.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays WAV files with the JDK's sampled sound, one at a time on a thread of its own: a sound asked for while another
 * plays is left out. The first failure is logged as a warning, later ones only for debugging.
 */
public final class WavPlayer implements SoundPlayer {

    // A notification sound lasts a few seconds; a long file is cut, a huge one never read into memory.
    private static final long MAX_SECONDS = 30;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final Logger logger = LoggerFactory.getLogger(WavPlayer.class);

    private final AtomicBoolean playing = new AtomicBoolean();
    private final AtomicBoolean warned = new AtomicBoolean();

    @Override
    public void play(Path file) {
        if (!this.playing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("njmule-sound").start(() -> {
            try {
                playNow(file);
            } catch (IOException | UnsupportedAudioFileException | LineUnavailableException | RuntimeException e) {
                this.failed(file, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                this.playing.set(false);
            }
        });
    }

    /** Whether a sound plays now, or is about to. */
    boolean isPlaying() {
        return this.playing.get();
    }

    private static void playNow(Path file)
            throws IOException, UnsupportedAudioFileException, LineUnavailableException, InterruptedException {
        if (Files.size(file) > MAX_BYTES) {
            throw new IOException("larger than " + MAX_BYTES + " bytes");
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile());
                Clip clip = AudioSystem.getClip()) {
            CountDownLatch stopped = new CountDownLatch(1);
            clip.addLineListener(e -> {
                if (LineEvent.Type.STOP.equals(e.getType())) {
                    stopped.countDown();
                }
            });
            clip.open(in);
            clip.start();
            if (!stopped.await(MAX_SECONDS, TimeUnit.SECONDS)) {
                logger.debug("{} plays longer than {} s: cut", file, MAX_SECONDS);
            }
        }
    }

    private void failed(Path file, Exception e) {
        if (this.warned.compareAndSet(false, true)) {
            logger.warn("Cannot play {}: {}", file, e.toString());
        } else {
            logger.debug("Cannot play {}: {}", file, e.toString());
        }
    }
}
