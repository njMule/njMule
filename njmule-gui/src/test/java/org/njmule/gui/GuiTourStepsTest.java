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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiTourStepsTest {

    @TempDir
    Path dir;

    private final List<Path> played = new CopyOnWriteArrayList<>();
    private Throwable failure;

    @Test
    void theSoundOfTheTourIsAShortSilenceForThePlayer() throws Exception {
        whenTheTourPlaysItsSound();
        thenThePlayerGotAWaveFileOfATenthOfASecond();
    }

    @Test
    void aTourWithoutWebInterfaceGoesOn() {
        whenTheTourAsksTheWebInterfaceOf(this.dir);
        thenNoExceptionOccurred();
    }

    private void whenTheTourPlaysItsSound() {
        GuiTour.sound(this.played::add, this.dir);
    }

    private void whenTheTourAsksTheWebInterfaceOf(Path configDir) {
        try {
            GuiTour.webInterface(configDir);
        } catch (RuntimeException e) {
            this.failure = e;
        }
    }

    private void thenThePlayerGotAWaveFileOfATenthOfASecond() throws Exception {
        assertThat(this.played).hasSize(1);
        AudioFileFormat format =
                AudioSystem.getAudioFileFormat(this.played.getFirst().toFile());
        assertThat(format.getType()).isEqualTo(AudioFileFormat.Type.WAVE);
        assertThat(format.getFrameLength() / format.getFormat().getFrameRate()).isEqualTo(0.1f);
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.failure).isNull();
    }
}
