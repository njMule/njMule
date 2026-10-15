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
import static org.assertj.core.api.Assertions.catchThrowable;

import atlantafx.base.theme.Styles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.ServerAddress;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(60)
class InputsTest {

    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF";
    // With the hashes of its parts, the link of a 4 GB file runs to about 15,000 characters.
    private static final String BIG_LINK =
            "ed2k://|file|big.iso|4290000000|" + HASH + "|p=" + String.join(":", Collections.nCopies(442, HASH)) + "|/";
    private static final String OTHER_LINK = BIG_LINK.replace("big", "other");

    @TempDir
    Path dir;

    private TestGui test;
    private Inputs inputs;
    private TextField field;
    private Input<?> input;
    private final AtomicBoolean hostRequired = new AtomicBoolean();
    private TextField password;
    private Input<String> secret;
    private BooleanBinding all;
    private Throwable exception;

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
    void shouldKeepOnlyTheDigitsOfANumber() throws Exception {
        givenANumberField("TCP", 1, 65_535);

        whenTyped("4a6 62");

        thenTheTextIs("4662", "letters and spaces dropped");
        thenTheCaretIsAt(4);
        thenTheValueIs(4662);
        thenTheFieldIsMarked(false);
    }

    @Test
    void shouldRefuseAndMarkANumberOutOfItsRange() throws Exception {
        givenANumberField("TCP", 1, 65_535);
        givenTyped("4a6 62");

        whenTheCharactersAreReplaced(0, 4, "99999");

        thenItIsValid(false);
        thenTheFieldIsMarked(true);
    }

    @Test
    void shouldNameTheFieldAndWhatItTakesForANumberOutOfItsRange() throws Exception {
        givenANumberField("TCP", 1, 65_535);
        givenTyped("4a6 62");
        givenTheCharactersReplaced(0, 4, "99999");

        whenTheValueIsRead();

        thenExceptionOccurred(IllegalArgumentException.class, "TCP: A whole number from 1 to 65535");
    }

    @Test
    void shouldTakeNoMoreDigitsThanTheLargestValueHas() throws Exception {
        givenANumberField("TCP", 1, 65_535);
        givenTyped("4a6 62");
        givenTheCharactersReplaced(0, 4, "99999");

        whenTheCharactersAreReplaced(5, 5, "1");

        thenTheTextIs("99999", "no more digits than the largest value has");
    }

    @Test
    void shouldMarkAnEmptiedNumberAndHintWhatItTakes() throws Exception {
        givenANumberField("TCP", 1, 65_535);
        givenTyped("4a6 62");
        givenTheCharactersReplaced(0, 4, "99999");

        whenCleared();

        thenItIsValid(false);
        thenTheFieldIsMarked(true, "an emptied setting is marked");
        thenTheHintIs("A whole number from 1 to 65535");
    }

    @Test
    void shouldTakeLongLinks() throws Exception {
        givenAFileLinksField("Download");

        whenTyped(BIG_LINK + " " + OTHER_LINK);

        thenTheLinksAreNamed("big.iso", "other.iso");
        thenNothingWasTold();
    }

    @Test
    void shouldRefuseAndTellAPasteTooLong() throws Exception {
        givenAFileLinksField("Download");
        givenTyped(BIG_LINK + " " + OTHER_LINK);
        givenCleared();

        whenTyped("x".repeat(Inputs.MAX_LINKS_TEXT + 1));

        thenTheTextIs("", "refused");
        thenTheOnlyThingToldStartsWith("Too long");
    }

    @Test
    void shouldLeaveAnEmptyOptionalNumberWithoutValue() throws Exception {
        whenAnOptionalNumberFieldIsMade("Upload", 0, 100);

        thenTheValueIs(OptionalInt.empty());
    }

    @Test
    void shouldTakeAnOptionalNumberAtTheTopOfItsRange() throws Exception {
        givenAnOptionalNumberField("Upload", 0, 100);

        whenTheTextIsSetTo("100");

        thenTheValueIs(OptionalInt.of(100));
    }

    @Test
    void shouldRefuseAnOptionalNumberPastItsRange() throws Exception {
        givenAnOptionalNumberField("Upload", 0, 100);

        whenTheTextIsSetTo("101");

        thenItIsValid(false);
    }

    @Test
    void shouldLeaveAnOptionalTextEmpty() throws Exception {
        whenATextFieldIsMade("Name", 5, false);

        thenTheValueIs("");
    }

    @Test
    void shouldRefuseATextLongerThanAllowed() throws Exception {
        givenATextField("Name", 5, false);

        whenTyped("nights");

        thenTheTextIs("", "longer than allowed is refused");
    }

    @Test
    void shouldTakeATextAsLongAsAllowed() throws Exception {
        givenATextField("Name", 5, false);
        givenTyped("nights");

        whenTyped("night");

        thenTheValueIs("night");
    }

    @Test
    void shouldRefuseARequiredTextLeftEmpty() throws Exception {
        whenATextFieldIsMade("Nick", 50, true);

        thenItIsValid(false);
    }

    @Test
    void shouldDropTheTabsOfARequiredText() throws Exception {
        givenATextField("Nick", 50, true);

        whenTyped("a\tb");

        thenTheTextIs("ab");
        thenItIsValid(true);
    }

    @Test
    void shouldKeepTheHoursAndMinutesOfATimeTyped() throws Exception {
        givenATimeField("From");

        whenTyped("7:30am");

        thenTheTextIs("7:30");
        thenTheValueIs(LocalTime.of(7, 30));
    }

    @Test
    void shouldRefuseATimePastTheDay() throws Exception {
        givenATimeField("From");

        whenTheTextIsSetTo("25:00");

        thenItIsValid(false);
    }

    @Test
    void shouldTakeTheLastMinuteOfTheDay() throws Exception {
        givenATimeField("From");

        whenTheTextIsSetTo("23:59");

        thenTheValueIs(LocalTime.of(23, 59));
    }

    @Test
    void shouldNotMarkAnEmptyAddressPrompt() throws Exception {
        whenAnAddressPromptIsMade("Server");

        thenItIsValid(false);
        thenTheFieldIsMarked(false, "an empty prompt is not marked");
    }

    @Test
    void shouldTakeAnAddressTypedWithSpacesAround() throws Exception {
        givenAnAddressPrompt("Server");

        whenTyped(" 192.168.1.10:4661 ");

        thenTheValueIs(new ServerAddress("192.168.1.10", 4661));
    }

    @ParameterizedTest
    @CsvSource({"server.example.org:4242, server.example.org", "'[2001:db8::1]:4661', 2001:db8::1"})
    void shouldTakeTheHostOfAnAddress(String address, String host) throws Exception {
        givenAnAddressPrompt("Server");

        whenTheTextIsSetTo(address);

        thenTheHostIs(host);
    }

    @Test
    void shouldRefuseAnAddressWithoutAPort() throws Exception {
        givenAnAddressPrompt("Server");

        whenTheTextIsSetTo("server.example.org");

        thenItIsValid(false, "no port");
    }

    @Test
    void shouldRefuseAndMarkABadHostName() throws Exception {
        givenAnAddressPrompt("Server");

        whenTheTextIsSetTo("bad..host:4661");

        thenItIsValid(false);
        thenTheFieldIsMarked(true);
    }

    @Test
    void shouldLeaveAHostEmptyWhileNotRequired() throws Exception {
        whenAHostFieldIsMade("Proxy");

        thenTheValueIs("");
    }

    @Test
    void shouldRefuseAnEmptyHostOnceRequired() throws Exception {
        givenAHostField("Proxy");
        givenTheHostRequired();

        whenTheFieldIsChecked();

        thenItIsValid(false);
    }

    @Test
    void shouldTakeARequiredHost() throws Exception {
        givenAHostField("Proxy");
        givenTheHostRequired();
        givenTheFieldChecked();

        whenTheTextIsSetTo("proxy.lan");

        thenTheValueIs("proxy.lan");
    }

    @Test
    void shouldTakeAnHttpsUrl() throws Exception {
        givenAUrlField("server.met");

        whenTheTextIsSetTo("https://example.org/server.met");

        thenTheValueIs(URI.create("https://example.org/server.met"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.org/server.met", "not an address", "http:///server.met"})
    void shouldRefuseAUrlThatIsNotHttpOrHttpsWithAHost(String url) throws Exception {
        givenAUrlField("server.met");

        whenTheTextIsSetTo(url);

        thenItIsValid(false);
    }

    @Test
    void shouldLeaveAnEmptyFolderWithoutValue() throws Exception {
        whenAFolderFieldIsMade("Incoming");

        thenTheValueIs(Optional.empty());
    }

    @Test
    void shouldTakeAFolderNotMadeYet() throws Exception {
        givenAFolderField("Incoming");

        whenTheTextIsSetToThePath("new");

        thenTheFolderTakenIs("new");
    }

    @Test
    void shouldRefuseAFileForAFolder() throws Exception {
        givenAFile("file.txt");
        givenAFolderField("Incoming");

        whenTheTextIsSetToThePath("file.txt");

        thenItIsValid(false);
    }

    @Test
    void shouldRefuseAMissingFolderWhereOneMustExist() throws Exception {
        givenAnExistingFolderField("eMule");

        whenTheTextIsSetToThePath("new");

        thenItIsValid(false);
    }

    @Test
    void shouldTakeAnExistingFolder() throws Exception {
        givenAnExistingFolderField("eMule");

        whenTheTextIsSetToTheTestFolder();

        thenTheValueIsTheTestFolder();
    }

    @Test
    void shouldTakeLinksApartBySpaces() throws Exception {
        givenAFileLinksField("Download");

        whenTheTextIsSetTo("ed2k://|file|a b.txt|100|" + HASH + "|/ ed2k://|file|c.txt|200|" + HASH + "|/");

        thenThereAreLinks(2);
        thenTheFirstLinkIsNamed("a b.txt");
    }

    @Test
    void shouldRefuseLinksFollowedByOtherText() throws Exception {
        givenAFileLinksField("Download");

        whenTheTextIsSetTo("ed2k://|file|a.txt|100|" + HASH + "|/ not a link");

        thenItIsValid(false);
    }

    @Test
    void shouldTakeASearchExpression() throws Exception {
        givenASearchField("Search");

        whenTheTextIsSetTo("ubuntu iso");

        thenTheValueIs("ubuntu iso");
    }

    @Test
    void shouldRefuseASearchWithAParenthesisLeftOpen() throws Exception {
        givenASearchField("Search");

        whenTheTextIsSetTo("(ubuntu");

        thenItIsValid(false);
    }

    @Test
    void shouldNotBeAllValidWhileAnInputIsNot() throws Exception {
        givenANumberField("A", 1, 9);
        givenTheTextSetTo("1");
        givenAPasswordFieldTakingOnlyTwoSpaces();

        whenAllInputsAreJoined();

        thenAllAreValid(false);
    }

    @Test
    void shouldBeAllValidOnceEveryInputIs() throws Exception {
        givenANumberField("A", 1, 9);
        givenTheTextSetTo("1");
        givenAPasswordFieldTakingOnlyTwoSpaces();
        givenAllInputsJoined();

        whenThePasswordIsSetTo("  ");

        thenAllAreValid(true, "spaces kept for passwords");
    }

    @Test
    void shouldNotBeAllValidOnceAnInputTurnsInvalid() throws Exception {
        givenANumberField("A", 1, 9);
        givenTheTextSetTo("1");
        givenAPasswordFieldTakingOnlyTwoSpaces();
        givenAllInputsJoined();
        givenThePasswordSetTo("  ");

        whenTheTextIsSetTo("0");

        thenAllAreValid(false);
    }

    private void givenANumberField(String name, int min, int max) throws Exception {
        make(f -> this.inputs.number(f, name, min, max));
    }

    private void givenAnOptionalNumberField(String name, int min, int max) throws Exception {
        make(f -> this.inputs.optionalNumber(f, name, min, max));
    }

    private void givenATextField(String name, int maxLength, boolean required) throws Exception {
        make(f -> this.inputs.text(f, name, maxLength, required));
    }

    private void givenATimeField(String name) throws Exception {
        make(f -> this.inputs.time(f, name));
    }

    private void givenAnAddressPrompt(String name) throws Exception {
        make(f -> this.inputs.address(f, name).quietWhenEmpty());
    }

    private void givenAHostField(String name) throws Exception {
        make(f -> this.inputs.host(f, name, this.hostRequired::get));
    }

    private void givenAUrlField(String name) throws Exception {
        make(f -> this.inputs.url(f, name));
    }

    private void givenAFolderField(String name) throws Exception {
        make(f -> this.inputs.folder(f, name));
    }

    private void givenAnExistingFolderField(String name) throws Exception {
        make(f -> this.inputs.existingFolder(f, name));
    }

    private void givenAFileLinksField(String name) throws Exception {
        make(f -> this.inputs.fileLinks(f, name));
    }

    private void givenASearchField(String name) throws Exception {
        make(f -> this.inputs.searchExpression(f, name));
    }

    private void givenAPasswordFieldTakingOnlyTwoSpaces() throws Exception {
        FxTesting.onFx(() -> {
            this.password = new PasswordField();
            this.secret = Input.of(this.password, "B", "two spaces", c -> true, 10, t -> {
                        if (!t.equals("  ")) {
                            throw new IllegalArgumentException(t);
                        }
                        return t;
                    })
                    .keepSpaces();
        });
    }

    private void givenAFile(String name) throws Exception {
        Files.writeString(this.dir.resolve(name), "x");
    }

    private void givenTheHostRequired() {
        this.hostRequired.set(true);
    }

    private void givenTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.field.replaceText(0, 0, text));
    }

    private void givenTheCharactersReplaced(int start, int end, String text) throws Exception {
        FxTesting.onFx(() -> this.field.replaceText(start, end, text));
    }

    private void givenTheTextSetTo(String text) throws Exception {
        FxTesting.onFx(() -> this.field.setText(text));
    }

    private void givenCleared() throws Exception {
        FxTesting.onFx(() -> this.field.clear());
    }

    private void givenTheFieldChecked() throws Exception {
        FxTesting.onFx(() -> this.input.check());
    }

    private void givenAllInputsJoined() throws Exception {
        FxTesting.onFx(() -> {
            this.all = Input.allValid(List.of(this.input, this.secret));
        });
    }

    private void givenThePasswordSetTo(String text) throws Exception {
        FxTesting.onFx(() -> this.password.setText(text));
    }

    private void whenAnOptionalNumberFieldIsMade(String name, int min, int max) throws Exception {
        make(f -> this.inputs.optionalNumber(f, name, min, max));
    }

    private void whenATextFieldIsMade(String name, int maxLength, boolean required) throws Exception {
        make(f -> this.inputs.text(f, name, maxLength, required));
    }

    private void whenAnAddressPromptIsMade(String name) throws Exception {
        make(f -> this.inputs.address(f, name).quietWhenEmpty());
    }

    private void whenAHostFieldIsMade(String name) throws Exception {
        make(f -> this.inputs.host(f, name, this.hostRequired::get));
    }

    private void whenAFolderFieldIsMade(String name) throws Exception {
        make(f -> this.inputs.folder(f, name));
    }

    private void whenTyped(String text) throws Exception {
        FxTesting.onFx(() -> this.field.replaceText(0, 0, text));
    }

    private void whenTheCharactersAreReplaced(int start, int end, String text) throws Exception {
        FxTesting.onFx(() -> this.field.replaceText(start, end, text));
    }

    private void whenTheTextIsSetTo(String text) throws Exception {
        FxTesting.onFx(() -> this.field.setText(text));
    }

    private void whenTheTextIsSetToThePath(String name) throws Exception {
        FxTesting.onFx(() -> this.field.setText(this.dir.resolve(name).toString()));
    }

    private void whenTheTextIsSetToTheTestFolder() throws Exception {
        FxTesting.onFx(() -> this.field.setText(this.dir.toString()));
    }

    private void whenCleared() throws Exception {
        FxTesting.onFx(() -> this.field.clear());
    }

    private void whenTheFieldIsChecked() throws Exception {
        FxTesting.onFx(() -> this.input.check());
    }

    private void whenTheValueIsRead() {
        this.exception = catchThrowable(this::value);
    }

    private void whenAllInputsAreJoined() throws Exception {
        FxTesting.onFx(() -> {
            this.all = Input.allValid(List.of(this.input, this.secret));
        });
    }

    private void whenThePasswordIsSetTo(String text) throws Exception {
        FxTesting.onFx(() -> this.password.setText(text));
    }

    private void thenTheTextIs(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.getText())).isEqualTo(text);
    }

    private void thenTheTextIs(String text, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.getText())).as(why).isEqualTo(text);
    }

    private void thenTheCaretIsAt(int position) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.getCaretPosition())).isEqualTo(position);
    }

    private void thenTheValueIs(Object value) throws Exception {
        assertThat(value()).isEqualTo(value);
    }

    private void thenTheHostIs(String host) throws Exception {
        assertThat(value())
                .asInstanceOf(InstanceOfAssertFactories.type(ServerAddress.class))
                .extracting(ServerAddress::host)
                .isEqualTo(host);
    }

    private void thenTheLinksAreNamed(String... names) throws Exception {
        assertThat(value())
                .asInstanceOf(InstanceOfAssertFactories.list(Ed2kLink.FileLink.class))
                .extracting(Ed2kLink.FileLink::name)
                .containsExactly(names);
    }

    private void thenThereAreLinks(int count) throws Exception {
        assertThat(value()).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(count);
    }

    private void thenTheFirstLinkIsNamed(String name) throws Exception {
        assertThat(value())
                .asInstanceOf(InstanceOfAssertFactories.list(Ed2kLink.FileLink.class))
                .first()
                .extracting(Ed2kLink.FileLink::name)
                .isEqualTo(name);
    }

    private void thenTheFolderTakenIs(String name) throws Exception {
        assertThat(value()).isEqualTo(Optional.of(this.dir.resolve(name).toAbsolutePath()));
    }

    private void thenTheValueIsTheTestFolder() throws Exception {
        assertThat(value()).isEqualTo(this.dir);
    }

    private void thenItIsValid(boolean valid) throws Exception {
        assertThat(FxTesting.onFx(() -> this.input.isValid())).isEqualTo(valid);
    }

    private void thenItIsValid(boolean valid, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> this.input.isValid())).as(why).isEqualTo(valid);
    }

    private void thenTheFieldIsMarked(boolean marked) throws Exception {
        assertThat(FxTesting.onFx(() -> danger(this.field))).isEqualTo(marked);
    }

    private void thenTheFieldIsMarked(boolean marked, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> danger(this.field))).as(why).isEqualTo(marked);
    }

    private void thenTheHintIs(String hint) throws Exception {
        assertThat(FxTesting.onFx(() -> this.field.getTooltip().getText())).isEqualTo(hint);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String message) {
        assertThat(this.exception).isInstanceOf(type).hasMessage(message);
    }

    private void thenNothingWasTold() {
        assertThat(this.test.told).isEmpty();
    }

    private void thenTheOnlyThingToldStartsWith(String start) {
        assertThat(this.test.told).singleElement().asString().startsWith(start);
    }

    private void thenAllAreValid(boolean valid) throws Exception {
        assertThat(FxTesting.onFx(() -> this.all.get())).isEqualTo(valid);
    }

    private void thenAllAreValid(boolean valid, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> this.all.get())).as(why).isEqualTo(valid);
    }

    // Read on the FX thread, as the controls are.
    private Object value() throws Exception {
        return FxTesting.onFx(() -> this.input.value());
    }

    private void make(Function<TextField, Input<?>> factory) throws Exception {
        FxTesting.onFx(() -> {
            this.field = new TextField();
            this.input = factory.apply(this.field);
        });
    }

    private static boolean danger(TextField field) {
        return field.getPseudoClassStates().contains(Styles.STATE_DANGER);
    }
}
