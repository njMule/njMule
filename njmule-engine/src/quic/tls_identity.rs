// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The key and self-signed certificate our side of a QUIC connection shows as TLS server. Nobody checks it: the
//! clients prove who they are inside the connection.

use std::sync::Arc;
use std::time::{Duration, SystemTime};

use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};
use time::OffsetDateTime;

const NAME: &str = "njMule QUIC";
const VALID_BEFORE: Duration = Duration::from_secs(3_600);
// Five years of 365 days.
const VALID_FOR: Duration = Duration::from_secs(157_680_000);

pub struct TlsIdentity {
    certificate: CertificateDer<'static>,
    key: PrivateKeyDer<'static>,
}

impl TlsIdentity {
    /// A new P-256 key and its certificate, valid from an hour before `now` for five years.
    pub fn self_signed(now: SystemTime) -> Result<Self, rcgen::Error> {
        let key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)?;
        let mut params = CertificateParams::default();
        params.distinguished_name = DistinguishedName::new();
        params.distinguished_name.push(DnType::CommonName, NAME);
        params.not_before = OffsetDateTime::from(now.checked_sub(VALID_BEFORE).unwrap_or(now));
        params.not_after = OffsetDateTime::from(now.checked_add(VALID_FOR).unwrap_or(now));
        let certificate = params.self_signed(&key)?.der().clone();
        Ok(Self { certificate, key: PrivateKeyDer::from(key) })
    }

    /// A TLS 1.3 server that shows this identity.
    pub fn serve(&self, provider: Arc<CryptoProvider>) -> Result<rustls::ServerConfig, rustls::Error> {
        rustls::ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])?
            .with_no_client_auth()
            .with_single_cert(vec![self.certificate.clone()], self.key.clone_key())
    }

    pub fn certificate(&self) -> &CertificateDer<'static> {
        &self.certificate
    }
}
