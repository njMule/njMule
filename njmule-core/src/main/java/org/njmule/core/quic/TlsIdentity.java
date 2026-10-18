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
package org.njmule.core.quic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1UTCTime;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import tech.kwik.core.server.ServerConnector;

/**
 * The key and self-signed certificate our side of a QUIC connection shows as TLS server. Nobody checks it: the clients
 * prove who they are inside the connection.
 */
final class TlsIdentity {

    // The curve of the key, which the TLS server needs to be told.
    private static final String CURVE = "secp256r1";
    private static final String ALIAS = "quic";

    private static final String NAME = "CN=njMule QUIC";
    private static final Duration VALID_BEFORE = Duration.ofHours(1);
    private static final Duration VALID_FOR = Duration.ofDays(5 * 365L);
    // UTCTime, as certificates valid before 2050 carry their dates.
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    private final KeyStore store;
    private final char[] password;
    private final X509Certificate certificate;

    private TlsIdentity(KeyStore store, char[] password, X509Certificate certificate) {
        this.store = store;
        this.password = password;
        this.certificate = certificate;
    }

    /** A new key and its certificate, valid from an hour ago for five years. */
    static TlsIdentity selfSigned(SecureRandom random, Instant now) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE), random);
        KeyPair keys = generator.generateKeyPair();
        X509Certificate certificate = certify(keys, random, now);
        byte[] secret = new byte[16];
        random.nextBytes(secret);
        char[] password = HexFormat.of().formatHex(secret).toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try {
            store.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException("no empty key store", e);
        }
        store.setKeyEntry(ALIAS, keys.getPrivate(), password, new Certificate[] {certificate});
        return new TlsIdentity(store, password, certificate);
    }

    /** A TLS server that shows this identity. */
    ServerConnector.Builder serve(ServerConnector.Builder server) {
        return server.withKeyStore(this.store, ALIAS, this.password.clone(), CURVE);
    }

    X509Certificate certificate() {
        return this.certificate;
    }

    private static X509Certificate certify(KeyPair keys, SecureRandom random, Instant now)
            throws GeneralSecurityException {
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256);
        X500Name name = new X500Name(NAME);
        V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
        tbs.setSerialNumber(new ASN1Integer(new BigInteger(63, random)));
        tbs.setSignature(algorithm);
        tbs.setIssuer(name);
        tbs.setSubject(name);
        tbs.setStartDate(new Time(new ASN1UTCTime(UTC_TIME.format(now.minus(VALID_BEFORE)))));
        tbs.setEndDate(new Time(new ASN1UTCTime(UTC_TIME.format(now.plus(VALID_FOR)))));
        tbs.setSubjectPublicKeyInfo(
                SubjectPublicKeyInfo.getInstance(keys.getPublic().getEncoded()));
        TBSCertificate unsigned = tbs.generateTBSCertificate();
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keys.getPrivate(), random);
            signer.update(unsigned.getEncoded(ASN1Encoding.DER));
            byte[] der = new DERSequence(new ASN1Encodable[] {unsigned, algorithm, new DERBitString(signer.sign())})
                    .getEncoded(ASN1Encoding.DER);
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (IOException e) {
            throw new GeneralSecurityException("the certificate does not encode", e);
        }
    }
}
