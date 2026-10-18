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
package org.njmule.core.update;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import org.njmule.core.api.InstallKind;
import org.njmule.core.persist.AtomicFiles;

/**
 * The installation of a new version, from its hand-over to its end: what the stopping njMule asks the installer to do,
 * then how far the installer got. It lives in {@code cache/update/apply.properties} until the new version has proved
 * itself, so that the next start of either version can tell the user what happened.
 *
 * @param version the version installed
 * @param from the version it replaces
 * @param kind how njMule was installed
 * @param packageFile the package downloaded
 * @param staged the files unpacked from it, or the new bundle; empty for an installer package
 * @param target the installed folder or bundle they replace; empty for an installer package
 * @param probe the program below the target that tells its version, as {@code njmule.exe}
 * @param restart whether njMule starts again once the new version is in place
 * @param unattended whether nobody watches: the installer asks nothing
 * @param program what starts njMule again; empty when it does not restart
 * @param arguments the arguments it starts with
 * @param state how far the installation got
 * @param reason why it failed
 * @param helper the process that installs, once it runs
 * @param time when the state was last set
 */
record ApplyPlan(
        String version,
        String from,
        InstallKind kind,
        Path packageFile,
        Optional<Path> staged,
        Optional<Path> target,
        Optional<String> probe,
        boolean restart,
        boolean unattended,
        Optional<Path> program,
        List<String> arguments,
        State state,
        Optional<String> reason,
        OptionalLong helper,
        Instant time) {

    /** How far an installation got. */
    enum State {
        /** The stopping njMule started the installer. */
        HANDED_OVER,
        /** The installer runs and waits for njMule to end. */
        WAITING,
        /** The new version is in place, the old files kept until it has run long enough. */
        INSTALLED,
        /** Nothing changed: the installation stopped before, or put everything back at once. */
        FAILED,
        /** The new version failed to run and the old one is back. */
        ROLLED_BACK
    }

    ApplyPlan {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(packageFile, "packageFile");
        Objects.requireNonNull(staged, "staged");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(program, "program");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(time, "time");
    }

    ApplyPlan with(State next, Optional<String> why, Instant when) {
        return new ApplyPlan(
                this.version,
                this.from,
                this.kind,
                this.packageFile,
                this.staged,
                this.target,
                this.probe,
                this.restart,
                this.unattended,
                this.program,
                this.arguments,
                next,
                why,
                this.helper,
                when);
    }

    ApplyPlan withHelper(long pid, Instant when) {
        return new ApplyPlan(
                this.version,
                this.from,
                this.kind,
                this.packageFile,
                this.staged,
                this.target,
                this.probe,
                this.restart,
                this.unattended,
                this.program,
                this.arguments,
                State.WAITING,
                Optional.empty(),
                OptionalLong.of(pid),
                when);
    }

    /** The plan in {@code file}, if one is there and readable. */
    static Optional<ApplyPlan> load(Path file) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        Properties p = new Properties();
        p.load(new StringReader(text));
        try {
            List<String> arguments = new ArrayList<>();
            for (int i = 0; p.containsKey("argument." + i); i++) {
                arguments.add(p.getProperty("argument." + i));
            }
            String helper = p.getProperty("helper", "");
            return Optional.of(new ApplyPlan(
                    required(p, "version"),
                    required(p, "from"),
                    InstallKind.of(required(p, "kind")).orElseThrow(() -> new IOException("unknown kind")),
                    Path.of(required(p, "package")),
                    path(p, "staged"),
                    path(p, "target"),
                    Optional.ofNullable(p.getProperty("probe")).filter(v -> !v.isBlank()),
                    Boolean.parseBoolean(p.getProperty("restart")),
                    Boolean.parseBoolean(p.getProperty("unattended")),
                    path(p, "program"),
                    arguments,
                    State.valueOf(required(p, "state").toUpperCase(Locale.ROOT)),
                    Optional.ofNullable(p.getProperty("reason")).filter(r -> !r.isBlank()),
                    helper.isEmpty() ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(helper)),
                    Instant.parse(required(p, "time"))));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("a damaged installation plan " + file + ": " + e.getMessage(), e);
        }
    }

    /** Keeps the plan in {@code file}, replacing it whole. */
    void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", this.version);
        p.setProperty("from", this.from);
        p.setProperty("kind", this.kind.id());
        p.setProperty("package", this.packageFile.toString());
        this.staged.ifPresent(s -> p.setProperty("staged", s.toString()));
        this.target.ifPresent(t -> p.setProperty("target", t.toString()));
        this.probe.ifPresent(t -> p.setProperty("probe", t));
        p.setProperty("restart", Boolean.toString(this.restart));
        p.setProperty("unattended", Boolean.toString(this.unattended));
        this.program.ifPresent(s -> p.setProperty("program", s.toString()));
        for (int i = 0; i < this.arguments.size(); i++) {
            p.setProperty("argument." + i, this.arguments.get(i));
        }
        p.setProperty("state", this.state.name().toLowerCase(Locale.ROOT));
        this.reason.ifPresent(r -> p.setProperty("reason", r));
        this.helper.ifPresent(pid -> p.setProperty("helper", Long.toString(pid)));
        p.setProperty("time", this.time.toString());
        StringWriter text = new StringWriter();
        p.store(text, null);
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        // No backup: an older state of the plan would only mislead.
        AtomicFiles.write(file, out -> out.write(bytes), false);
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("the installation plan lacks " + key);
        }
        return value.strip();
    }

    private static Optional<Path> path(Properties p, String key) {
        return Optional.ofNullable(p.getProperty(key)).filter(v -> !v.isBlank()).map(Path::of);
    }
}
