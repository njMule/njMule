// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The one page the web interface writes itself, the login, which runs no script; and escaping: every text that is not
//! ours goes through [`text`].

use crate::api::nj_mule_info;

/// The style sheet of the login page.
pub const STYLE: &str = include_str!("../../../njmule-http/src/main/resources/org/njmule/http/style.css");

// The browser icons, from the branding.
const ICON_SVG: &[u8] = include_bytes!("../../../branding/njmule-icon.svg");
const ICON_PNG: &[u8] = include_bytes!("../../../branding/generated/njmule-32.png");

/// The icon at `path`, if it is one, with its content type.
pub fn icon(path: &str) -> Option<(&'static str, &'static [u8])> {
    match path {
        "/favicon.svg" => Some(("image/svg+xml", ICON_SVG)),
        "/favicon.png" => Some(("image/png", ICON_PNG)),
        _ => None,
    }
}

/// `value` safe in element content and in quoted attributes: `& < > " '` as `&amp; &lt; &gt; &quot; &#39;`, as the Java
/// engine writes them (the escaping crates write the apostrophe in hexadecimal).
pub fn text(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for c in value.chars() {
        match c {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '"' => escaped.push_str("&quot;"),
            '\'' => escaped.push_str("&#39;"),
            other => escaped.push(other),
        }
    }
    escaped
}

/// A page outside a session, such as the login.
pub fn bare(title: &str, body: &str) -> String {
    let title = text(title);
    format!(
        "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">\
         <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
         <link rel=\"icon\" href=\"favicon.png\" sizes=\"32x32\">\
         <link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">\
         <title>{title} - njMule</title><link rel=\"stylesheet\" href=\"style.css\"></head><body>\
         <main class=\"login\"><a class=\"brand\" href=\"./\">\
         <img src=\"favicon.svg\" alt=\"\" width=\"28\" height=\"28\"><span>{}</span></a><h1>{title}</h1>{body}</main>\
         </body></html>",
        text(nj_mule_info::NAME)
    )
}

/// `path`, a page of the interface, relative to the page that links to it: every page sits at the top, so the interface
/// works under whatever path a reverse proxy serves it.
pub fn relative(path: &str) -> String {
    if path == "/" { "./".to_owned() } else { path.strip_prefix('/').unwrap_or(path).to_owned() }
}
