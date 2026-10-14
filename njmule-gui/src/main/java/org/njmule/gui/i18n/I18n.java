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
package org.njmule.gui.i18n;

import java.text.Bidi;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The interface texts of one language: eMule's strings ({@code IDS_*} keys, converted from its string tables) and
 * njMule's own ({@code nj.*} keys, plus the eMule texts it words differently). A missing translation falls back to
 * English.
 */
public final class I18n {

    /** The languages of the interface; English first, then by their own names. */
    public static final List<Locale> LANGUAGES = List.of(
            Locale.ENGLISH,
            Locale.of("ca"),
            Locale.of("cs"),
            Locale.of("da"),
            Locale.GERMAN,
            Locale.of("et"),
            Locale.of("es"),
            Locale.FRENCH,
            Locale.of("gl"),
            Locale.ITALIAN,
            Locale.of("lv"),
            Locale.of("lt"),
            Locale.of("hu"),
            Locale.of("nl"),
            Locale.of("nb"),
            Locale.of("pl"),
            Locale.of("pt", "BR"),
            Locale.of("pt", "PT"),
            Locale.of("ro"),
            Locale.of("sq"),
            Locale.of("sl"),
            Locale.of("fi"),
            Locale.of("sv"),
            Locale.of("vi"),
            Locale.of("tr"),
            Locale.of("el"),
            Locale.of("bg"),
            Locale.of("ru"),
            Locale.of("uk"),
            Locale.of("ar"),
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.JAPANESE,
            Locale.KOREAN);

    // Where Chinese is written in the traditional script when the locale names no script.
    private static final Set<String> TRADITIONAL_REGIONS = Set.of("TW", "HK", "MO");
    private static final String EMULE_BUNDLE = "org.njmule.gui.i18n.emule";
    private static final String NJMULE_BUNDLE = "org.njmule.gui.i18n.nj";
    // Norwegian that names no written standard, and Nynorsk, get the Bokmål translation.
    private static final Set<String> OTHER_NORWEGIAN = Set.of("no", "nn");
    // The languages written from right to left; Java before 17 named Hebrew "iw".
    private static final Set<String> RIGHT_TO_LEFT = Set.of("ar", "fa", "he", "iw");
    // Directional embeddings (LRE, RLE and PDF), which JavaFX draws as nothing.
    private static final char LEFT_TO_RIGHT_EMBEDDING = (char) 0x202A;
    private static final char RIGHT_TO_LEFT_EMBEDDING = (char) 0x202B;
    private static final char POP_DIRECTIONAL_FORMATTING = (char) 0x202C;
    private static final Pattern LINES = Pattern.compile("\n");

    private final Locale locale;
    private final ResourceBundle emule;
    private final ResourceBundle njmule;

    private I18n(Locale locale) {
        this.locale = locale;
        // English is the base bundle: asking for the root locale never falls back to the
        // system's language.
        Locale lookup = locale.getLanguage().equals("en") ? Locale.ROOT : locale;
        this.emule = ResourceBundle.getBundle(EMULE_BUNDLE, lookup);
        this.njmule = ResourceBundle.getBundle(NJMULE_BUNDLE, lookup);
    }

    /** The texts of {@code locale}'s language, or English when there is no translation. */
    public static I18n of(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        if (OTHER_NORWEGIAN.contains(locale.getLanguage())) {
            return new I18n(Locale.of("nb"));
        }
        for (Locale language : LANGUAGES) {
            if (language.getLanguage().equals(locale.getLanguage()) && sameWriting(language, locale)) {
                return new I18n(language);
            }
        }
        return new I18n(Locale.ENGLISH);
    }

    // Chinese is written in two scripts and Portuguese in two standards: the locale picks the translation.
    private static boolean sameWriting(Locale language, Locale locale) {
        return switch (language.getLanguage()) {
            case "zh" -> language.equals(Locale.TRADITIONAL_CHINESE) == traditionalChinese(locale);
            case "pt" -> language.getCountry().equals(locale.getCountry().equals("BR") ? "BR" : "PT");
            default -> true;
        };
    }

    private static boolean traditionalChinese(Locale locale) {
        String script = locale.getScript();
        return script.equals("Hant") || (script.isEmpty() && TRADITIONAL_REGIONS.contains(locale.getCountry()));
    }

    public Locale locale() {
        return this.locale;
    }

    /** Whether this language is written from right to left, as Arabic, Hebrew and Persian are. */
    public boolean isRightToLeft() {
        return RIGHT_TO_LEFT.contains(this.locale.getLanguage());
    }

    /**
     * {@code text} kept in the direction of its first letters, line by line, when the interface runs right to left: a
     * file name or an address inside a sentence, or a sentence shown where text runs left to right, would otherwise mix
     * with what is around it. Unchanged in the other languages.
     */
    public String embed(String text) {
        if (!this.isRightToLeft() || text.isEmpty()) {
            return text;
        }
        List<String> lines = new ArrayList<>();
        // An embedding ends with its line.
        for (String line : LINES.split(text, -1)) {
            if (line.isEmpty()) {
                lines.add(line);
            } else {
                boolean leftToRight = new Bidi(line, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
                lines.add((leftToRight ? LEFT_TO_RIGHT_EMBEDDING : RIGHT_TO_LEFT_EMBEDDING)
                        + line
                        + POP_DIRECTIONAL_FORMATTING);
            }
        }
        return String.join("\n", lines);
    }

    /** The name of this language, written in it: Deutsch, Italiano. */
    public String name() {
        return this.t("nj.language");
    }

    /** The text of {@code key}; an unknown key shows itself, so it is noticed. */
    public String t(String key) {
        try {
            return this.bundle(key).getString(key);
        } catch (MissingResourceException e) {
            return "[" + key + "]";
        }
    }

    /** The text of {@code key} with its {@code %} placeholders filled in; a text filled in is {@link #embed}ded. */
    public String f(String key, Object... args) {
        String pattern = this.t(key);
        try {
            return String.format(this.locale, pattern, this.embedded(args));
        } catch (IllegalFormatException e) {
            return pattern;
        }
    }

    // Numbers, characters, truth values and times are formatted, not embedded.
    private Object[] embedded(Object[] args) {
        if (!this.isRightToLeft()) {
            return args;
        }
        Object[] shown = args.clone();
        for (int i = 0; i < shown.length; i++) {
            if (shown[i] != null
                    && !(shown[i] instanceof Number
                            || shown[i] instanceof Character
                            || shown[i] instanceof Boolean
                            || shown[i] instanceof TemporalAccessor
                            || shown[i] instanceof Date
                            || shown[i] instanceof Calendar)) {
                shown[i] = this.embed(shown[i].toString());
            }
        }
        return shown;
    }

    /** Whether {@code key} has a text. */
    public boolean has(String key) {
        return this.bundle(key).containsKey(key);
    }

    // A language may word an eMule text its own way; English keeps eMule's.
    private ResourceBundle bundle(String key) {
        return !key.startsWith("IDS_") || this.njmule.containsKey(key) ? this.njmule : this.emule;
    }
}
