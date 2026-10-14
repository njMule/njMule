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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.util.Subscription;

/**
 * What one window follows of the engine model, ended together when a new window replaces it (after a language change):
 * the model outlives its windows, and would otherwise keep the old ones updated and in memory. FX thread only.
 */
public final class Subscriptions {

    private final List<Subscription> all = new ArrayList<>();

    /** Calls {@code subscriber} with the old and the new value on each change of {@code value}. */
    public <T> void onChange(ObservableValue<T> value, BiConsumer<? super T, ? super T> subscriber) {
        this.all.add(value.subscribe(subscriber));
    }

    /** Hands each change of {@code list} to {@code listener}. */
    public <E> void onListChange(ObservableList<E> list, ListChangeListener<? super E> listener) {
        list.addListener(listener);
        this.all.add(() -> list.removeListener(listener));
    }

    /** Ends every subscription. */
    public void unsubscribe() {
        this.all.forEach(Subscription::unsubscribe);
        this.all.clear();
    }
}
