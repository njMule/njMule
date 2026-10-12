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
package org.njmule.core.protocol.packet;

import java.util.Optional;

/** Opcode tables of the eD2K and Kad protocols, one enum per protocol and transport. */
public final class Opcodes {

    private Opcodes() {}

    /** A named opcode. */
    public interface Opcode {
        int code();
    }

    /** Client ↔ server over TCP ({@link Protocol#EDONKEY}). */
    public enum Server implements Opcode {
        LOGINREQUEST(0x01),
        REJECT(0x05),
        GETSERVERLIST(0x14),
        OFFERFILES(0x15),
        SEARCHREQUEST(0x16),
        DISCONNECT(0x18),
        GETSOURCES(0x19),
        SEARCH_USER(0x1A),
        CALLBACKREQUEST(0x1C),
        QUERY_MORE_RESULT(0x21),
        GETSOURCES_OBFU(0x23),
        SERVERLIST(0x32),
        SEARCHRESULT(0x33),
        SERVERSTATUS(0x34),
        CALLBACKREQUESTED(0x35),
        CALLBACK_FAIL(0x36),
        SERVERMESSAGE(0x38),
        IDCHANGE(0x40),
        SERVERIDENT(0x41),
        FOUNDSOURCES(0x42),
        USERS_LIST(0x43),
        FOUNDSOURCES_OBFU(0x44);

        private static final Server[] BY_CODE = table(values(), new Server[256]);
        private final int code;

        Server(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return this.code;
        }

        public static Optional<Server> of(int code) {
            return lookup(BY_CODE, code);
        }
    }

    /** Client ↔ server over UDP (server TCP port + 4). */
    public enum ServerUdp implements Opcode {
        GLOBSEARCHREQ3(0x90),
        GLOBSEARCHREQ2(0x92),
        GLOBGETSOURCES2(0x94),
        GLOBSERVSTATREQ(0x96),
        GLOBSERVSTATRES(0x97),
        GLOBSEARCHREQ(0x98),
        GLOBSEARCHRES(0x99),
        GLOBGETSOURCES(0x9A),
        GLOBFOUNDSOURCES(0x9B),
        GLOBCALLBACKREQ(0x9C),
        INVALID_LOWID(0x9E),
        SERVER_LIST_REQ(0xA0),
        SERVER_LIST_RES(0xA1),
        SERVER_DESC_REQ(0xA2),
        SERVER_DESC_RES(0xA3),
        SERVER_LIST_REQ2(0xA4);

        private static final ServerUdp[] BY_CODE = table(values(), new ServerUdp[256]);
        private final int code;

        ServerUdp(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return this.code;
        }

        public static Optional<ServerUdp> of(int code) {
            return lookup(BY_CODE, code);
        }
    }

    /** Client ↔ client over TCP, base protocol ({@link Protocol#EDONKEY}). */
    public enum Client implements Opcode {
        HELLO(0x01),
        SENDINGPART(0x46),
        REQUESTPARTS(0x47),
        FILEREQANSNOFIL(0x48),
        END_OF_DOWNLOAD(0x49),
        ASKSHAREDFILES(0x4A),
        ASKSHAREDFILESANSWER(0x4B),
        HELLOANSWER(0x4C),
        CHANGE_CLIENT_ID(0x4D),
        MESSAGE(0x4E),
        SETREQFILEID(0x4F),
        FILESTATUS(0x50),
        HASHSETREQUEST(0x51),
        HASHSETANSWER(0x52),
        STARTUPLOADREQ(0x54),
        ACCEPTUPLOADREQ(0x55),
        CANCELTRANSFER(0x56),
        OUTOFPARTREQS(0x57),
        REQUESTFILENAME(0x58),
        REQFILENAMEANSWER(0x59),
        CHANGE_SLOT(0x5B),
        QUEUERANK(0x5C),
        ASKSHAREDDIRS(0x5D),
        ASKSHAREDFILESDIR(0x5E),
        ASKSHAREDDIRSANS(0x5F),
        ASKSHAREDFILESDIRANS(0x60),
        ASKSHAREDDENIEDANS(0x61);

        private static final Client[] BY_CODE = table(values(), new Client[256]);
        private final int code;

        Client(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return this.code;
        }

        public static Optional<Client> of(int code) {
            return lookup(BY_CODE, code);
        }
    }

    /** Client ↔ client over TCP, extended protocol ({@link Protocol#EMULE}). */
    public enum Extended implements Opcode {
        EMULEINFO(0x01),
        EMULEINFOANSWER(0x02),
        COMPRESSEDPART(0x40),
        QUEUERANKING(0x60),
        FILEDESC(0x61),
        REQUESTSOURCES(0x81),
        ANSWERSOURCES(0x82),
        REQUESTSOURCES2(0x83),
        ANSWERSOURCES2(0x84),
        PUBLICKEY(0x85),
        SIGNATURE(0x86),
        SECIDENTSTATE(0x87),
        REQUESTPREVIEW(0x90),
        PREVIEWANSWER(0x91),
        MULTIPACKET(0x92),
        MULTIPACKETANSWER(0x93),
        PUBLICIP_REQ(0x97),
        PUBLICIP_ANSWER(0x98),
        CALLBACK(0x99),
        REASKCALLBACKTCP(0x9A),
        AICHREQUEST(0x9B),
        AICHANSWER(0x9C),
        AICHFILEHASHANS(0x9D),
        AICHFILEHASHREQ(0x9E),
        BUDDYPING(0x9F),
        BUDDYPONG(0xA0),
        COMPRESSEDPART_I64(0xA1),
        SENDINGPART_I64(0xA2),
        REQUESTPARTS_I64(0xA3),
        MULTIPACKET_EXT(0xA4),
        CHATCAPTCHAREQ(0xA5),
        CHATCAPTCHARES(0xA6),
        FWCHECKUDPREQ(0xA7),
        KAD_FWTCPCHECK_ACK(0xA8),
        MULTIPACKET_EXT2(0xA9),
        MULTIPACKETANSWER_EXT2(0xB0),
        HASHSETREQUEST2(0xB1),
        HASHSETANSWER2(0xB2);

        private static final Extended[] BY_CODE = table(values(), new Extended[256]);
        private final int code;

        Extended(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return this.code;
        }

        public static Optional<Extended> of(int code) {
            return lookup(BY_CODE, code);
        }
    }

    /** Client ↔ client over UDP ({@link Protocol#EMULE}). */
    public enum ClientUdp implements Opcode {
        REASKFILEPING(0x90),
        REASKACK(0x91),
        FILENOTFOUND(0x92),
        QUEUEFULL(0x93),
        REASKCALLBACKUDP(0x94),
        DIRECTCALLBACKREQ(0x95),
        PORTTEST(0xFE);

        private static final ClientUdp[] BY_CODE = table(values(), new ClientUdp[256]);
        private final int code;

        ClientUdp(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return this.code;
        }

        public static Optional<ClientUdp> of(int code) {
            return lookup(BY_CODE, code);
        }
    }

    /** Kad version 2 over UDP ({@link Protocol#KAD}); version 1 requests are not answered. */
    public enum Kad implements Opcode {
        BOOTSTRAP_REQ(0x01),
        BOOTSTRAP_RES(0x09),
        HELLO_REQ(0x11),
        HELLO_RES(0x19),
        REQ(0x21),
        HELLO_RES_ACK(0x22),
        RES(0x29),
        SEARCH_KEY_REQ(0x33),
        SEARCH_SOURCE_REQ(0x34),
        SEARCH_NOTES_REQ(0x35),
        SEARCH_RES(0x3B),
        PUBLISH_KEY_REQ(0x43),
        PUBLISH_SOURCE_REQ(0x44),
        PUBLISH_NOTES_REQ(0x45),
        PUBLISH_RES(0x4B),
        PUBLISH_RES_ACK(0x4C),
        FIREWALLED_REQ(0x50),
        FINDBUDDY_REQ(0x51),
        CALLBACK_REQ(0x52),
        FIREWALLED2_REQ(0x53),
        FIREWALLED_RES(0x58),
        FIREWALLED_ACK_RES(0x59),
        FINDBUDDY_RES(0x5A),
        PING(0x60),
        PONG(0x61),
        FIREWALLUDP(0x62);

        private static final Kad[] BY_CODE = table(values(), new Kad[256]);
        private final int code;

        Kad(int code) {
            this.code = code;
        }

        @Override
        public int code() {
            return this.code;
        }

        public static Optional<Kad> of(int code) {
            return lookup(BY_CODE, code);
        }
    }

    // Fills a 256-entry table indexed by opcode (no reflection, for the native image).
    private static <T extends Opcode> T[] table(T[] values, T[] byCode) {
        for (T value : values) {
            if (byCode[value.code()] != null) {
                throw new IllegalStateException("duplicate opcode " + value);
            }
            byCode[value.code()] = value;
        }
        return byCode;
    }

    private static <T> Optional<T> lookup(T[] byCode, int code) {
        return code >= 0 && code < byCode.length
                ? Optional.ofNullable(byCode[code])
                : Optional.empty();
    }
}
