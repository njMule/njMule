// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Parsing and escaping shared by the link types. Links come from users, browsers and sites, so every part is checked
//! before it is taken; the same text gives the same link as the Java engine gives (the `ed2k-links.jsonl` corpus).

use std::net::Ipv6Addr;

use data_encoding::{BASE32_NOPAD, HEXLOWER_PERMISSIVE};
use percent_encoding::{AsciiSet, NON_ALPHANUMERIC, utf8_percent_encode};
use time::{Date, Month, OffsetDateTime};

use crate::api::ed2k_link::{
    Ed2kLink, FileLink, InvalidLink, MAX_SOURCES, NodesListLink, SearchLink, ServerLink, ServerListLink, Source,
};
use crate::util::white_space;

const MAX_HOST: usize = 255;
const MAX_DIGITS: usize = 15;
// Printable ASCII but % (the escape), space and | (the link's separator); everything else as UTF-8, upper-case hex.
const LINK_TEXT: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'!')
    .remove(b'"')
    .remove(b'#')
    .remove(b'$')
    .remove(b'&')
    .remove(b'\'')
    .remove(b'(')
    .remove(b')')
    .remove(b'*')
    .remove(b'+')
    .remove(b',')
    .remove(b'-')
    .remove(b'.')
    .remove(b'/')
    .remove(b':')
    .remove(b';')
    .remove(b'<')
    .remove(b'=')
    .remove(b'>')
    .remove(b'?')
    .remove(b'@')
    .remove(b'[')
    .remove(b'\\')
    .remove(b']')
    .remove(b'^')
    .remove(b'_')
    .remove(b'`')
    .remove(b'{')
    .remove(b'}')
    .remove(b'~');

pub(crate) fn parse(text: &str) -> Result<Ed2kLink, InvalidLink> {
    let link = white_space::strip(text);
    if starts_ignoring_case(link, "magnet:?") {
        return magnet(link.get(8..).unwrap_or_default());
    }
    // Browsers hand links to the program they open with the separators escaped.
    let unescaped;
    let link = if !link.contains('|') && starts_ignoring_case(link, "ed2k://%7C") {
        unescaped = link.replace("%7C", "|").replace("%7c", "|");
        unescaped.as_str()
    } else {
        link
    };
    let tokens = tokens(link, '|');
    if !tokens.first().is_some_and(|first| equals_ignoring_case(first, "ed2k://")) {
        return Err(InvalidLink::new("not an ed2k link"));
    }
    let kind = token(&tokens, 1)?;
    match kind {
        "file" => file(&tokens).map(Ed2kLink::File),
        "server" => {
            expect_end(&tokens, 4)?;
            ServerLink::new(token(&tokens, 2)?, port(token(&tokens, 3)?)?).map(Ed2kLink::Server)
        }
        "serverlist" => {
            expect_end(&tokens, 3)?;
            ServerListLink::new(token(&tokens, 2)?).map(Ed2kLink::ServerList)
        }
        "nodeslist" => {
            expect_end(&tokens, 3)?;
            NodesListLink::new(token(&tokens, 2)?).map(Ed2kLink::NodesList)
        }
        "search" => SearchLink::new(&decode(token(&tokens, 2)?)).map(Ed2kLink::Search),
        other => Err(InvalidLink::new(format!("unknown ed2k link type: {other}"))),
    }
}

fn file(tokens: &[&str]) -> Result<FileLink, InvalidLink> {
    let name = white_space::strip(&decode(token(tokens, 2)?)).to_owned();
    let size = size(token(tokens, 3)?)?;
    let hash = hash(token(tokens, 4)?)?;
    let mut aich = None;
    let mut parts = Vec::new();
    let mut http = Vec::new();
    let mut end = None;
    for (index, param) in tokens.iter().enumerate().skip(5) {
        if *param == "/" {
            end = Some(index);
            break;
        }
        if let Some(base32) = param.strip_prefix("h=") {
            aich = aich_hash(base32);
        } else if let Some(list) = param.strip_prefix("p=") {
            parts = part_hashes(list);
        } else if let Some(url) = param.strip_prefix("s=")
            && is_web_url(url)
        {
            http.push(url.to_owned());
        }
    }
    let end = end.ok_or_else(|| InvalidLink::new("a file link ends with |/"))?;
    let mut sources = Vec::new();
    // Optional extension: |sources[@YYMMDD],ip:port,…|/
    if let Some(extension) = tokens.get(end.saturating_add(1)) {
        if tokens.get(end.saturating_add(2)) != Some(&"/") {
            return Err(InvalidLink::new("unterminated link extension"));
        }
        sources = extension_sources(extension);
    }
    FileLink::new(name, size, hash, aich, parts, sources, http)
}

fn magnet(query: &str) -> Result<Ed2kLink, InvalidLink> {
    let (mut name, mut length, mut digest, mut aich) = (None, None, None, None);
    let mut sources = Vec::new();
    let mut http = Vec::new();
    for param in tokens(query, '&') {
        // A key of two ASCII letters and '='; any other key is none of ours.
        let (Some(key), Some(b'=')) = (param.get(..2), param.as_bytes().get(2)) else {
            continue;
        };
        let value = param.get(3..).unwrap_or_default();
        match key {
            "dn" => name = Some(value),
            "xl" => length = Some(value),
            "as" => {
                if starts_ignoring_case(value, "http://") {
                    http.push(value.to_owned());
                }
            }
            "xs" => {
                if let Some(rest) = value.strip_prefix("ed2kftp://")
                    && let Some(slash) = rest.find('/').filter(|slash| *slash > 0)
                    && let Some(source) = source(rest.get(..slash).unwrap_or_default())
                {
                    sources.push(source);
                }
            }
            "xt" => {
                if let Some(hex) = value.strip_prefix("urn:ed2k:") {
                    digest = Some(hex);
                } else if let Some(hex) = value.strip_prefix("urn:ed2khash:") {
                    digest = Some(hex);
                } else if let Some(base32) = value.strip_prefix("urn:aich:") {
                    aich = aich_hash(base32);
                }
            }
            _ => {}
        }
    }
    let (Some(name), Some(length), Some(digest)) = (name, length, digest) else {
        return Err(InvalidLink::new("magnet link without eD2K name, size and hash"));
    };
    sources.truncate(MAX_SOURCES);
    let name = white_space::strip(&decode(name)).to_owned();
    FileLink::new(name, size(length)?, hash(digest)?, aich, Vec::new(), sources, http).map(Ed2kLink::File)
}

fn extension_sources(extension: &str) -> Vec<Source> {
    let Some(mut rest) = extension.strip_prefix("sources") else {
        return Vec::new();
    };
    if let Some(dated) = rest.strip_prefix('@') {
        let Some(expiry) = dated.get(..6).filter(|digits| digits.bytes().all(|b| b.is_ascii_digit())) else {
            return Vec::new();
        };
        // Sources that expire today or earlier are dropped, as are those of an impossible date.
        if !expiry_date(expiry).is_some_and(|date| date > OffsetDateTime::now_utc().date()) {
            return Vec::new();
        }
        rest = dated.get(6..).unwrap_or_default();
    }
    rest.split(',').filter_map(source).take(MAX_SOURCES).collect()
}

// A date YYMMDD of this century.
fn expiry_date(digits: &str) -> Option<Date> {
    let number = |range: std::ops::Range<usize>| digits.get(range).and_then(|part| part.parse::<u8>().ok());
    let year = 2000i32.checked_add(i32::from(number(0..2)?))?;
    let month = Month::try_from(number(2..4)?).ok()?;
    Date::from_calendar_date(year, month, number(4..6)?).ok()
}

fn source(entry: &str) -> Option<Source> {
    let colon = entry.rfind(':').filter(|colon| (1..=MAX_HOST).contains(colon))?;
    let host = entry.get(..colon)?;
    let port_text = entry.get(colon.saturating_add(1)..)?;
    if !is_digits(port_text) {
        return None;
    }
    let port = port_text.parse::<u64>().ok().and_then(|port| u16::try_from(port).ok()).filter(|port| *port != 0)?;
    if let Some(inside) = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')) {
        // An IPv6 address, kept without its brackets; an IPv4-mapped one is IPv4, and a zone id names nothing a peer
        // can reach.
        let address = inside.parse::<Ipv6Addr>().ok()?;
        return address.to_ipv4_mapped().is_none().then(|| Source { host: inside.to_owned(), port });
    }
    // Every IPv4 address is also a host name of these characters.
    is_host_name(host).then(|| Source { host: host.to_owned(), port })
}

fn is_host_name(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= MAX_HOST
        && host.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.')
}

fn aich_hash(base32: &str) -> Option<[u8; 20]> {
    if base32.len() != 32 {
        return None;
    }
    BASE32_NOPAD.decode(base32.to_ascii_uppercase().as_bytes()).ok()?.try_into().ok()
}

fn part_hashes(list: &str) -> Vec<[u8; 16]> {
    list.split(':').map(hex32).collect::<Option<Vec<_>>>().unwrap_or_default()
}

fn hash(hex: &str) -> Result<[u8; 16], InvalidLink> {
    hex32(hex).ok_or_else(|| InvalidLink::new("ill-formed file hash"))
}

fn hex32(hex: &str) -> Option<[u8; 16]> {
    if hex.len() != 32 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    HEXLOWER_PERMISSIVE.decode(hex.as_bytes()).ok()?.try_into().ok()
}

fn size(text: &str) -> Result<u64, InvalidLink> {
    if !is_digits(text) {
        return Err(InvalidLink::new(format!("ill-formed file size: {text}")));
    }
    text.parse().map_err(|_| InvalidLink::new(format!("ill-formed file size: {text}")))
}

// A port is checked by the link type: here only its form.
fn port(text: &str) -> Result<u16, InvalidLink> {
    let ill_formed = || InvalidLink::new(format!("ill-formed port: {text}"));
    if !is_digits(text) || text.len() > 5 {
        return Err(ill_formed());
    }
    let port = text.parse::<u32>().map_err(|_| ill_formed())?;
    u16::try_from(port).map_err(|_| InvalidLink::new(format!("invalid server port {port}")))
}

fn is_digits(text: &str) -> bool {
    (1..=MAX_DIGITS).contains(&text.len()) && text.bytes().all(|b| b.is_ascii_digit())
}

pub(crate) fn check_url(url: &str) -> Result<(), InvalidLink> {
    if !is_web_url(url) {
        return Err(InvalidLink::new(format!("only http and https URLs are allowed: {url}")));
    }
    Ok(())
}

fn is_web_url(url: &str) -> bool {
    starts_ignoring_case(url, "http://") || starts_ignoring_case(url, "https://")
}

fn token<'a>(tokens: &[&'a str], index: usize) -> Result<&'a str, InvalidLink> {
    tokens
        .get(index)
        .copied()
        .filter(|token| !token.is_empty())
        .ok_or_else(|| InvalidLink::new("incomplete ed2k link"))
}

fn expect_end(tokens: &[&str], index: usize) -> Result<(), InvalidLink> {
    if tokens.get(index) != Some(&"/") {
        return Err(InvalidLink::new("an ed2k link ends with |/"));
    }
    Ok(())
}

/// Splits like the eD2K link tokenizer: an empty token ends the list.
fn tokens(text: &str, separator: char) -> Vec<&str> {
    text.split(separator).take_while(|token| !token.is_empty()).collect()
}

/// Percent-decoding (control characters dropped), then UTF-8 when the result is a valid UTF-8 byte string; anything
/// else is kept as typed.
pub fn decode(text: &str) -> String {
    let mut chars = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(c) = rest.chars().next() {
        let escaped = rest
            .strip_prefix('%')
            .and_then(|after| after.get(..2))
            .filter(|hex| hex.bytes().all(|b| b.is_ascii_hexdigit()));
        if let Some(value) = escaped.and_then(|hex| u8::from_str_radix(hex, 16).ok()) {
            if value >= 0x20 {
                chars.push(char::from(value));
            }
            rest = rest.get(3..).unwrap_or_default();
        } else {
            chars.push(c);
            rest = rest.get(c.len_utf8()..).unwrap_or_default();
        }
    }
    // Only when every character fits a byte can the text be UTF-8 percent-encoded or typed as Latin-1.
    let bytes: Option<Vec<u8>> = chars.chars().map(|c| u8::try_from(u32::from(c)).ok()).collect();
    bytes.and_then(|bytes| String::from_utf8(bytes).ok()).unwrap_or(chars)
}

/// UTF-8 with `%`, space, `|`, control and non-ASCII bytes percent-encoded.
pub fn encode(text: &str) -> String {
    utf8_percent_encode(text, LINK_TEXT).to_string()
}

// The case-insensitive comparison the Java engine makes, one character to one: the Kelvin sign is a 'k'.
fn equals_ignoring_case(text: &str, expected: &str) -> bool {
    text.chars().count() == expected.chars().count() && starts_ignoring_case(text, expected)
}

fn starts_ignoring_case(text: &str, prefix: &str) -> bool {
    let mut chars = text.chars();
    prefix.chars().all(|p| chars.next().is_some_and(|c| same_letter(c, p)))
}

fn same_letter(a: char, b: char) -> bool {
    fn single(mut mapped: impl Iterator<Item = char>, original: char) -> char {
        match (mapped.next(), mapped.next()) {
            (Some(one), None) => one,
            _ => original,
        }
    }
    a == b
        || single(a.to_uppercase(), a) == single(b.to_uppercase(), b)
        || single(a.to_lowercase(), a) == single(b.to_lowercase(), b)
}
