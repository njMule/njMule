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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import atlantafx.base.theme.Styles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class InputsTest {

    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF";

    @TempDir
    Path dir;

    private TestGui test;
    private Inputs inputs;

    @BeforeEach
    void start() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.inputs = new Inputs(this.test.gui);
    }

    @AfterEach
    void stop() {
        this.test.close();
    }

    @Test
    void aNumberKeepsOnlyDigitsAndStaysInItsRange() throws Exception {
        FxTesting.onFx(() -> {
            TextField field = new TextField();
            Input<Integer> port = this.inputs.number(field, "TCP", 1, 65_535);

            field.replaceText(0, 0, "4a6 62");
            assertThat(field.getText()).as("letters and spaces dropped").isEqualTo("4662");
            assertThat(field.getCaretPosition()).isEqualTo(4);
            assertThat(port.value()).isEqualTo(4662);
            assertThat(danger(field)).isFalse();

            field.replaceText(0, 4, "99999");
            assertThat(port.isValid()).isFalse();
            assertThat(danger(field)).isTrue();
            assertThatThrownBy(port::value).hasMessage("TCP: A whole number from 1 to 65535");
            field.replaceText(5, 5, "1");
            assertThat(field.getText())
                    .as("no more digits than the largest value has")
                    .isEqualTo("99999");

            field.clear();
            assertThat(port.isValid()).isFalse();
            assertThat(danger(field)).as("an emptied setting is marked").isTrue();
            assertThat(field.getTooltip().getText()).isEqualTo("A whole number from 1 to 65535");
        });
    }

    @Test
    void optionalValuesMayStayEmpty() throws Exception {
        FxTesting.onFx(() -> {
            TextField limit = new TextField();
            Input<OptionalInt> upload = this.inputs.optionalNumber(limit, "Upload", 0, 100);
            assertThat(upload.value()).isEmpty();
            limit.setText("100");
            assertThat(upload.value()).hasValue(100);
            limit.setText("101");
            assertThat(upload.isValid()).isFalse();

            TextField name = new TextField();
            Input<String> rule = this.inputs.text(name, "Name", 5, false);
            assertThat(rule.value()).isEmpty();
            name.replaceText(0, 0, "nights");
            assertThat(name.getText()).as("longer than allowed is refused").isEmpty();
            name.replaceText(0, 0, "night");
            assertThat(rule.value()).isEqualTo("night");

            TextField nick = new TextField();
            Input<String> required = this.inputs.text(nick, "Nick", 50, true);
            assertThat(required.isValid()).isFalse();
            nick.replaceText(0, 0, "a\tb");
            assertThat(nick.getText()).isEqualTo("ab");
            assertThat(required.isValid()).isTrue();
        });
    }

    @Test
    void timesAreHoursAndMinutes() throws Exception {
        FxTesting.onFx(() -> {
            TextField field = new TextField();
            Input<LocalTime> time = this.inputs.time(field, "From");
            field.replaceText(0, 0, "7:30am");
            assertThat(field.getText()).isEqualTo("7:30");
            assertThat(time.value()).isEqualTo(LocalTime.of(7, 30));
            field.setText("25:00");
            assertThat(time.isValid()).isFalse();
            field.setText("23:59");
            assertThat(time.value()).isEqualTo(LocalTime.of(23, 59));
        });
    }

    @Test
    void addressesAndHostsAreWellFormed() throws Exception {
        FxTesting.onFx(() -> {
            TextField field = new TextField();
            Input<ServerAddress> address = this.inputs.address(field, "Server").quietWhenEmpty();
            assertThat(address.isValid()).isFalse();
            assertThat(danger(field)).as("an empty prompt is not marked").isFalse();

            field.replaceText(0, 0, " 192.168.1.10:4661 ");
            assertThat(address.value()).isEqualTo(new ServerAddress("192.168.1.10", 4661));
            field.setText("server.example.org:4242");
            assertThat(address.value().host()).isEqualTo("server.example.org");
            field.setText("[2001:db8::1]:4661");
            assertThat(address.value().host()).isEqualTo("2001:db8::1");
            field.setText("server.example.org");
            assertThat(address.isValid()).as("no port").isFalse();
            field.setText("bad..host:4661");
            assertThat(address.isValid()).isFalse();
            assertThat(danger(field)).isTrue();

            AtomicBoolean required = new AtomicBoolean();
            TextField proxy = new TextField();
            Input<String> host = this.inputs.host(proxy, "Proxy", required::get);
            assertThat(host.value()).isEmpty();
            required.set(true);
            host.check();
            assertThat(host.isValid()).isFalse();
            proxy.setText("proxy.lan");
            assertThat(host.value()).isEqualTo("proxy.lan");
        });
    }

    @Test
    void urlsAreHttpOrHttpsWithAHost() throws Exception {
        FxTesting.onFx(() -> {
            TextField field = new TextField();
            Input<URI> url = this.inputs.url(field, "server.met");
            field.setText("https://example.org/server.met");
            assertThat(url.value()).isEqualTo(URI.create("https://example.org/server.met"));
            field.setText("ftp://example.org/server.met");
            assertThat(url.isValid()).isFalse();
            field.setText("not an address");
            assertThat(url.isValid()).isFalse();
            field.setText("http:///server.met");
            assertThat(url.isValid()).isFalse();
        });
    }

    @Test
    void foldersMayBeNewButNotFiles() throws Exception {
        Path file = Files.writeString(this.dir.resolve("file.txt"), "x");
        FxTesting.onFx(() -> {
            TextField field = new TextField();
            Input<Optional<Path>> folder = this.inputs.folder(field, "Incoming");
            assertThat(folder.value()).isEmpty();
            field.setText(this.dir.resolve("new").toString());
            assertThat(folder.value()).contains(this.dir.resolve("new").toAbsolutePath());
            field.setText(file.toString());
            assertThat(folder.isValid()).isFalse();

            TextField emule = new TextField();
            Input<Path> existing = this.inputs.existingFolder(emule, "eMule");
            emule.setText(this.dir.resolve("new").toString());
            assertThat(existing.isValid()).isFalse();
            emule.setText(this.dir.toString());
            assertThat(existing.value()).isEqualTo(this.dir);
        });
    }

    @Test
    void linksAndSearchesFollowTheirSyntax() throws Exception {
        FxTesting.onFx(() -> {
            TextField field = new TextField();
            Input<List<Ed2kLink.FileLink>> links = this.inputs.fileLinks(field, "Download");
            field.setText("ed2k://|file|a b.txt|100|" + HASH + "|/ ed2k://|file|c.txt|200|" + HASH + "|/");
            assertThat(links.value()).hasSize(2);
            assertThat(links.value().getFirst().name()).isEqualTo("a b.txt");
            field.setText("ed2k://|file|a.txt|100|" + HASH + "|/ not a link");
            assertThat(links.isValid()).isFalse();

            TextField query = new TextField();
            Input<String> search = this.inputs.searchExpression(query, "Search");
            query.setText("ubuntu iso");
            assertThat(search.value()).isEqualTo("ubuntu iso");
            query.setText("(ubuntu");
            assertThat(search.isValid()).isFalse();
        });
    }

    @Test
    void allValidFollowsEveryInput() throws Exception {
        FxTesting.onFx(() -> {
            TextField a = new TextField("1");
            TextField b = new PasswordField();
            Input<Integer> number = this.inputs.number(a, "A", 1, 9);
            Input<String> secret = Input.of(b, "B", "two spaces", c -> true, 10, t -> {
                        if (!t.equals("  ")) {
                            throw new IllegalArgumentException(t);
                        }
                        return t;
                    })
                    .keepSpaces();
            var all = Input.allValid(List.of(number, secret));
            assertThat(all.get()).isFalse();
            b.setText("  ");
            assertThat(all.get()).as("spaces kept for passwords").isTrue();
            a.setText("0");
            assertThat(all.get()).isFalse();
        });
    }

    private static boolean danger(TextField field) {
        return field.getPseudoClassStates().contains(Styles.STATE_DANGER);
    }
}
