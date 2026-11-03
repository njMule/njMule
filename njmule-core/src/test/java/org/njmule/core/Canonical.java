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
package org.njmule.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A decoded value as JSON a corpus keeps and another engine can give alike: records as objects of their components
 * (with an {@code @type} where they implement a sealed interface), integers as the unsigned values of their width, as
 * the protocol sends them, floats as the hex of their bits, bytes and hashes in lower-case hex.
 */
public final class Canonical {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final HexFormat HEX = HexFormat.of();

    private Canonical() {}

    public static JsonNode of(Object value) {
        return switch (value) {
            case null -> NODES.nullNode();
            case Boolean b -> NODES.booleanNode(b);
            case Byte n -> NODES.numberNode(Byte.toUnsignedInt(n));
            case Short n -> NODES.numberNode(Short.toUnsignedInt(n));
            case Integer n -> NODES.numberNode(Integer.toUnsignedLong(n));
            case Long n -> NODES.numberNode(new BigInteger(Long.toUnsignedString(n)));
            case Float f -> NODES.stringNode(HEX.toHexDigits(Float.floatToRawIntBits(f)));
            case Double d -> NODES.stringNode(HEX.toHexDigits(Double.doubleToRawLongBits(d)));
            case String s -> NODES.stringNode(s);
            case byte[] bytes -> NODES.stringNode(HEX.formatHex(bytes));
            case Bytes bytes -> NODES.stringNode(HEX.formatHex(bytes.toByteArray()));
            case Hash16 hash -> NODES.stringNode(HEX.formatHex(hash.toByteArray()));
            case Hash20 hash -> NODES.stringNode(HEX.formatHex(hash.toByteArray()));
            case UInt128 id -> NODES.stringNode(HEX.formatHex(id.toBigEndian()));
            // An id as its number, a name as the hex of its bytes.
            case TagName name ->
                name.isId() ? NODES.numberNode(name.id()) : NODES.stringNode(HEX.formatHex(name.toBytes()));
            case Packet packet ->
                NODES.objectNode()
                        .put("protocol", packet.protocol())
                        .put("opcode", packet.opcode())
                        .put("payload", HEX.formatHex(packet.payload()));
            case BitSet bits -> array(bits.stream().boxed().toList());
            case Optional<?> optional -> of(optional.orElse(null));
            case OptionalInt optional -> optional.isPresent() ? of(optional.getAsInt()) : NODES.nullNode();
            case OptionalLong optional -> optional.isPresent() ? of(optional.getAsLong()) : NODES.nullNode();
            case Enum<?> constant -> NODES.stringNode(constant.name());
            case Collection<?> values -> array(values);
            case Record record -> object(record);
            default -> throw new IllegalArgumentException("No canonical form for a " + value.getClass());
        };
    }

    private static ArrayNode array(Collection<?> values) {
        ArrayNode array = NODES.arrayNode();
        values.forEach(v -> array.add(of(v)));
        return array;
    }

    private static ObjectNode object(Record record) {
        ObjectNode object = NODES.objectNode();
        Class<?> type = record.getClass();
        if (Arrays.stream(type.getInterfaces()).anyMatch(Class::isSealed)) {
            object.put("@type", type.getSimpleName());
        }
        for (RecordComponent component : type.getRecordComponents()) {
            object.set(component.getName(), of(component(record, component.getAccessor())));
        }
        return object;
    }

    private static Object component(Record record, Method accessor) {
        try {
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + accessor, e);
        }
    }
}
