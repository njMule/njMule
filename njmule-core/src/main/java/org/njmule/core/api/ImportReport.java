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
package org.njmule.core.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What an import of an eMule profile did, file by file.
 *
 * @param startAtLogin eMule starts when the user logs in: the desktop app does the same after the import
 */
public record ImportReport(boolean dryRun, List<Item> items, boolean startAtLogin) {

    /** The outcome for one file. */
    public enum Outcome {
        /** Taken into njMule. */
        IMPORTED,
        /** Would be taken, in a dry run. */
        WOULD_IMPORT,
        /** Left alone, for the reason given. */
        SKIPPED,
        /** Not in the profile. */
        MISSING,
        /** Unreadable or refused. */
        FAILED
    }

    /** One file of the profile and what happened to it, told in phrases that the interfaces translate. */
    public record Item(String file, Outcome outcome, List<Phrase> phrases) {
        public Item {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(outcome, "outcome");
            phrases = List.copyOf(phrases);
        }

        /** An item told by a text of its own, such as an error's message, which stays untranslated. */
        public Item(String file, Outcome outcome, String text) {
            this(file, outcome, text.isEmpty() ? List.of() : List.of(Phrase.of(Phrase.TEXT, text)));
        }

        /** The phrases in English, one after the other. */
        public String detail() {
            return this.phrases.stream().map(Phrase::english).collect(Collectors.joining(Phrase.SEPARATOR));
        }
    }

    /**
     * A part of an item's detail: the text of the interfaces' translations named {@code key}, whose {@code %s} and
     * {@code %d} take {@code args} (strings and whole numbers).
     */
    public record Phrase(String key, List<Object> args) {

        /** Between the phrases of an item, in every language. */
        public static final String SEPARATOR = "; ";

        static final String TEXT = "nj.import.d.text";

        private static final Map<String, String> TEMPLATES = Map.ofEntries(
                Map.entry(TEXT, "%s"),
                Map.entry("nj.import.d.insideEmule", "njMule's configuration is in eMule's folder: nothing is taken"),
                Map.entry("nj.import.d.shortLived", "other nodes' entries, short-lived"),
                Map.entry("nj.import.d.noSection", "no [%s] section"),
                Map.entry("nj.import.d.preferences", "nick, limits, networks, obfuscation"),
                Map.entry("nj.import.d.startAtLogin", "start at login"),
                Map.entry("nj.import.d.portsOurs", "ports and folders stay njMule's"),
                Map.entry("nj.import.d.servers", "%d servers"),
                Map.entry("nj.import.d.added", "%d new"),
                Map.entry("nj.import.d.bootstrapNodes", "%d bootstrap nodes"),
                Map.entry("nj.import.d.kadContacts", "%d Kad contacts"),
                Map.entry("nj.import.d.knownFiles", "%d known files"),
                Map.entry("nj.import.d.aichTrees", "%d AICH trees"),
                Map.entry("nj.import.d.leftOut", "%s left out: %s"),
                Map.entry("nj.import.d.noSharedFolder", "no shared folder"),
                Map.entry("nj.import.d.folders", "%d folders"),
                Map.entry("nj.import.d.notShared", "%d not shared: %s"),
                Map.entry("nj.import.d.singleFiles", "%d single files: not shared yet"),
                Map.entry("nj.import.d.ranges", "%d ranges"),
                Map.entry("nj.import.d.ourOwn", "we have our own"),
                Map.entry("nj.import.d.nextStart", "from the next start"),
                Map.entry("nj.import.d.files", "%d files"),
                Map.entry("nj.import.d.friends", "%d friends"),
                Map.entry("nj.import.d.searches", "%d searches"),
                Map.entry("nj.import.d.spamMarkers", "%d spam markers"),
                Map.entry("nj.import.d.comments", "%d file comments"),
                Map.entry("nj.import.d.addresses", "%d addresses"),
                Map.entry("nj.import.d.entries", "%d entries"),
                Map.entry("nj.import.d.sections", "%d sections"),
                Map.entry("nj.import.d.noCategory", "no category besides all downloads"),
                Map.entry("nj.import.d.categories", "%d categories"),
                Map.entry("nj.import.d.withoutPatterns", "%s without its file name patterns, which are not valid here"),
                Map.entry("nj.import.d.ownIdentity", "njMule keeps its own identity unless asked to adopt eMule's"),
                Map.entry(
                        "nj.import.d.closeEmule",
                        "close eMule first: two clients with one identity spoil their credits"),
                Map.entry("nj.import.d.userHash", "user hash"),
                Map.entry("nj.import.d.identityKey", "secure identification key"),
                Map.entry("nj.import.d.together", "the user hash and the key are taken only together"),
                Map.entry("nj.import.d.credits", "%d credits"),
                Map.entry("nj.import.d.kadId", "Kad ID"),
                Map.entry("nj.import.d.notAsked", "downloads were not asked for"),
                Map.entry("nj.import.d.noDownloads", "no downloads in progress"),
                Map.entry("nj.import.d.noDataFile", "%s: no data file"),
                Map.entry("nj.import.d.pauseFirst", "%s: pause it in eMule first"),
                Map.entry("nj.import.d.download", "%s, %d MiB"),
                Map.entry("nj.import.d.copied", "%s, %d MiB copied"));

        public Phrase {
            Objects.requireNonNull(key, "key");
            if (!TEMPLATES.containsKey(key)) {
                throw new IllegalArgumentException("unknown phrase " + key);
            }
            args = List.copyOf(args);
            for (Object arg : args) {
                if (!(arg instanceof String || arg instanceof Integer || arg instanceof Long)) {
                    throw new IllegalArgumentException("a phrase takes strings and whole numbers: " + arg);
                }
            }
        }

        public static Phrase of(String key, Object... args) {
            return new Phrase(key, List.of(args));
        }

        /** The phrase in English. */
        public String english() {
            return String.format(Locale.ROOT, TEMPLATES.get(this.key), this.args.toArray());
        }

        /** Every phrase's English text by key, which the interfaces' translations follow. */
        public static Map<String, String> englishTexts() {
            return Map.copyOf(TEMPLATES);
        }
    }

    public ImportReport {
        items = List.copyOf(items);
    }

    /** A report of a profile that does not start at login. */
    public ImportReport(boolean dryRun, List<Item> items) {
        this(dryRun, items, false);
    }
}
