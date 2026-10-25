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
package org.njmule.app;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.njmule.core.config.AppDirectories;
import org.njmule.webui.WebUiSettings;

/**
 * {@code --service}: njMule as the Windows service "njMule", which njmule-service.exe beside njmule.exe runs headless
 * on the profile in %ProgramData%\njMule as the virtual account NT SERVICE\njMule.
 */
final class WindowsService {

    static final String NAME = "njMule";
    static final String ACCOUNT = "NT SERVICE\\" + NAME;
    static final String FIREWALL_RULE = "njMule (service)";
    static final String HOST = "njmule-service.exe";

    // What njmule-service.exe returns: a state of the service for "status", else a Windows error.
    private static final int STOPPED = 1;
    private static final int STARTING = 2;
    private static final int STOPPING = 3;
    private static final int RUNNING = 4;
    private static final int NOT_INSTALLED = 1060;

    /** What the commands need of Windows; the tests stand in for it. */
    interface Windows {
        boolean applies();

        /** Whether this process has the administrator's rights. */
        boolean elevated() throws IOException;

        /** The running njmule.exe, when this process is one. */
        Optional<Path> program();

        Path programData();

        /** Runs a program, drops its output and returns its exit code. */
        int run(List<String> command) throws IOException;

        /** Runs {@code program} with the administrator's rights, which Windows asks the user for; its exit code. */
        int runElevated(Path program, List<String> args) throws IOException;
    }

    private final Windows system;
    private final PrintStream out;
    private final PrintStream err;
    private final Main.PasswordSource passwords;
    private final StringBuilder said = new StringBuilder();

    WindowsService(Windows system, PrintStream out, PrintStream err, Main.PasswordSource passwords) {
        this.system = system;
        this.out = out;
        this.err = err;
        this.passwords = passwords;
    }

    static int run(LaunchOptions cli, PrintStream out, PrintStream err, Main.PasswordSource passwords) {
        return new WindowsService(new SystemWindows(), out, err, passwords).run(cli);
    }

    int run(LaunchOptions cli) {
        LaunchOptions.ServiceTask task = cli.service().orElseThrow();
        int code;
        try {
            code = execute(cli, task.action());
        } catch (LaunchOptions.UsageException e) {
            complain(e.getMessage());
            code = Main.EXIT_USAGE;
        } catch (IOException e) {
            complain(e.getMessage());
            code = Main.EXIT_FAILURE;
        }
        // The command without the administrator's rights shows what this one said in its own window.
        if (task.output().isPresent()) {
            try {
                Files.writeString(task.output().get(), this.said, StandardCharsets.UTF_8);
            } catch (IOException e) {
                this.err.println("Cannot write " + task.output().get() + ": " + e.getMessage());
            }
        }
        return code;
    }

    private int execute(LaunchOptions cli, LaunchOptions.ServiceAction action)
            throws IOException, LaunchOptions.UsageException {
        if (!this.system.applies()) {
            complain("The service is for Windows; the deb and rpm packages install a systemd unit, njmule.service.");
            return Main.EXIT_USAGE;
        }
        Optional<Path> program = this.system.program();
        Path folder = program.map(p -> p.toAbsolutePath().getParent()).orElse(null);
        if (program.isEmpty() || folder == null || !Files.isRegularFile(folder.resolve(HOST))) {
            complain("The service needs njMule's Windows package: " + HOST + " beside njmule.exe.");
            return Main.EXIT_FAILURE;
        }
        Path host = folder.resolve(HOST);
        if (action == LaunchOptions.ServiceAction.STATUS) {
            return status(host);
        }
        if (!this.system.elevated()) {
            return elevated(cli, program.get());
        }
        return switch (action) {
            case INSTALL -> install(cli, program.get(), folder);
            case UNINSTALL -> uninstall(folder);
            case START -> step(host, "start", "The service njMule runs.");
            case STOP -> step(host, "stop", "The service njMule has stopped.");
            case STATUS -> status(host);
        };
    }

    private int status(Path host) throws IOException {
        int state = this.system.run(List.of(host.toString(), "status"));
        String text = switch (state) {
            case NOT_INSTALLED -> "is not installed (njmule --service=install)";
            case STOPPED -> "is stopped";
            case STARTING -> "is starting";
            case STOPPING -> "is stopping";
            case RUNNING -> "runs";
            default -> "is in state " + state;
        };
        say("The service njMule " + text + ".");
        return state == RUNNING ? Main.EXIT_OK : Main.EXIT_FAILURE;
    }

    // The same command once more, with the administrator's rights; its messages come back through a file.
    private int elevated(LaunchOptions cli, Path program) throws IOException {
        LaunchOptions.ServiceTask task = cli.service().orElseThrow();
        List<String> args = new ArrayList<>();
        args.add("--service=" + task.action().name().toLowerCase(Locale.ROOT));
        cli.logLevel().ifPresent(level -> args.addAll(List.of("--log-level", level)));
        if (task.action() == LaunchOptions.ServiceAction.INSTALL) {
            cli.webUi().ifPresent(setup -> args.addAll(webUiArguments(setup)));
        }
        Path output = Files.createTempFile("njmule-service", ".txt");
        try {
            args.addAll(List.of("--service-output", output.toString()));
            say("Windows asks for the administrator's rights"
                    + (task.action() == LaunchOptions.ServiceAction.INSTALL
                            ? "; the web interface password is asked in the window that opens."
                            : "."));
            int code = this.system.runElevated(program, args);
            String told = Files.readString(output, StandardCharsets.UTF_8);
            if (told.isBlank()) {
                complain("Nothing was changed: njMule did not get the administrator's rights.");
                return Main.EXIT_FAILURE;
            }
            this.out.print(told);
            return code;
        } finally {
            Files.deleteIfExists(output);
        }
    }

    // A password file is passed on as an absolute path: the new process starts in another folder.
    static List<String> webUiArguments(LaunchOptions.WebUiSetup setup) {
        List<String> args = new ArrayList<>();
        setup.port().ifPresent(port -> args.addAll(List.of("--webui-port", String.valueOf(port))));
        if (setup.lan()) {
            args.add("--webui-lan");
        }
        if (setup.api()) {
            args.add("--webui-api");
        }
        setup.hosts().forEach(host -> args.addAll(List.of("--webui-host", host)));
        setup.proxies().forEach(proxy -> args.addAll(List.of("--webui-proxy", proxy)));
        if (setup.passwordFile().isPresent()) {
            args.addAll(List.of(
                    "--webui-password-file",
                    setup.passwordFile().get().toAbsolutePath().toString()));
        } else {
            args.add("--webui-password");
        }
        return args;
    }

    private int install(LaunchOptions cli, Path program, Path folder) throws IOException, LaunchOptions.UsageException {
        Path host = folder.resolve(HOST);
        Path root = profile();
        Path config = AppDirectories.resolve(Optional.of(root)).config();
        Files.createDirectories(config);
        setPassword(cli, config);
        check(this.system.run(List.of(host.toString(), "install")), "register the service");
        // The virtual account reads njMule's programs and keeps its profile, and nothing else of this computer.
        check(
                this.system.run(List.of("icacls", root.toString(), "/grant", ACCOUNT + ":(OI)(CI)M", "/T", "/Q")),
                "give the service its profile");
        check(
                this.system.run(List.of("icacls", folder.toString(), "/grant", ACCOUNT + ":(OI)(CI)RX", "/T", "/Q")),
                "let the service run njMule's programs");
        // A rule of an earlier installation may name another folder.
        this.system.run(List.of("netsh", "advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE));
        check(
                this.system.run(List.of(
                        "netsh",
                        "advfirewall",
                        "firewall",
                        "add",
                        "rule",
                        "name=" + FIREWALL_RULE,
                        "dir=in",
                        "action=allow",
                        "program=" + program,
                        "enable=yes",
                        "profile=private,domain")),
                "let other clients reach it through the firewall");
        check(this.system.run(List.of(host.toString(), "start")), "start the service");
        WebUiSettings settings = WebUiSettings.load(config);
        say("njMule runs as the Windows service \"njMule\", started with Windows, on the profile in " + root + ".");
        say("Its web interface: http://localhost:" + settings.port() + "/"
                + (settings.lan() ? ", open to the other computers of the network." : ""));
        say("The firewall rule \"" + FIREWALL_RULE + "\" lets other clients in on private and domain networks.");
        return Main.EXIT_OK;
    }

    private void setPassword(LaunchOptions cli, Path config) throws IOException, LaunchOptions.UsageException {
        if (WebUiPassword.setFromFile(cli, config)) {
            return;
        }
        if (cli.webUi().isEmpty() && !WebUiSettings.load(config).passwordHash().isEmpty()) {
            return;
        }
        say("The web interface of the service needs a password.");
        Main.setTypedWebUiPassword(
                cli.webUi()
                        .orElse(new LaunchOptions.WebUiSetup(
                                Optional.empty(), false, false, Optional.empty(), List.of())),
                config,
                this.passwords);
    }

    private int uninstall(Path folder) throws IOException {
        Path host = folder.resolve(HOST);
        if (this.system.run(List.of(host.toString(), "status")) == NOT_INSTALLED) {
            say("The service njMule is not installed.");
            return Main.EXIT_OK;
        }
        Path root = profile();
        // The account's rights go first: once the service is gone, its name no longer resolves.
        this.system.run(List.of("icacls", folder.toString(), "/remove", ACCOUNT, "/T", "/Q"));
        this.system.run(List.of("icacls", root.toString(), "/remove", ACCOUNT, "/T", "/Q"));
        check(this.system.run(List.of(host.toString(), "uninstall")), "remove the service");
        this.system.run(List.of("netsh", "advfirewall", "firewall", "delete", "rule", "name=" + FIREWALL_RULE));
        say("The service njMule is removed; its profile stays in " + root + ".");
        return Main.EXIT_OK;
    }

    private int step(Path host, String verb, String done) throws IOException {
        int code = this.system.run(List.of(host.toString(), verb));
        if (code == NOT_INSTALLED) {
            complain("The service njMule is not installed: njmule --service=install");
            return Main.EXIT_FAILURE;
        }
        check(code, verb + " the service");
        say(done);
        return Main.EXIT_OK;
    }

    private Path profile() {
        return this.system.programData().resolve(NAME);
    }

    private static void check(int code, String what) throws IOException {
        if (code != 0) {
            throw new IOException("Cannot " + what + " (Windows error " + code + ").");
        }
    }

    private void say(String text) {
        this.out.println(text);
        this.said.append(text).append(System.lineSeparator());
    }

    private void complain(String text) {
        this.err.println(text);
        this.said.append(text).append(System.lineSeparator());
    }

    /** Windows itself. */
    static final class SystemWindows implements Windows {
        @Override
        public boolean applies() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        }

        // fltmc answers only an administrator.
        @Override
        public boolean elevated() throws IOException {
            return run(List.of("fltmc")) == 0;
        }

        @Override
        public Optional<Path> program() {
            return ProcessHandle.current()
                    .info()
                    .command()
                    .map(Path::of)
                    .filter(p -> String.valueOf(p.getFileName()).equalsIgnoreCase("njmule.exe"));
        }

        @Override
        public Path programData() {
            return Optional.ofNullable(System.getenv("ProgramData"))
                    .map(Path::of)
                    .orElse(Path.of(
                            System.getenv().getOrDefault("SystemDrive", "C:") + File.separator + "ProgramData"));
        }

        @Override
        public int run(List<String> command) throws IOException {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroy();
                throw new IOException("Interrupted", e);
            }
        }

        // Start-Process -Verb RunAs is what asks Windows for the rights; the script goes encoded, so that no quote of
        // its arguments meets the command line's own.
        @Override
        public int runElevated(Path program, List<String> args) throws IOException {
            String script = "$p = Start-Process -FilePath " + quoted(program.toString())
                    + " -ArgumentList " + quoted(commandLine(args))
                    + " -Verb RunAs -Wait -PassThru; exit $p.ExitCode";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            return run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded));
        }

        private static String quoted(String text) {
            return "'" + text.replace("'", "''") + "'";
        }
    }

    /** The arguments as one command line, quoted as the C runtime of Windows reads them back. */
    static String commandLine(List<String> args) {
        StringBuilder line = new StringBuilder();
        for (String arg : args) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            if (!arg.isEmpty() && arg.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) {
                line.append(arg);
                continue;
            }
            line.append('"');
            int backslashes = 0;
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '\\') {
                    backslashes++;
                    continue;
                }
                // Backslashes before a quote are doubled, and the quote escaped.
                line.append("\\".repeat(c == '"' ? backslashes * 2 + 1 : backslashes))
                        .append(c);
                backslashes = 0;
            }
            line.append("\\".repeat(backslashes * 2)).append('"');
        }
        return line.toString();
    }
}
