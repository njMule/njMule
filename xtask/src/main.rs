// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Cargo build tasks for njMule (`cargo xtask <command>`).

use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs::{self, File};
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode, Stdio};

use data_encoding::HEXLOWER;
use encoding_rs::WINDOWS_1252;
use jiff::Timestamp;
use sha2::{Digest, Sha256};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("xtask error: {err}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let args: Vec<String> = env::args().skip(1).collect();
    let Some(cmd) = args.first().map(String::as_str) else {
        print_usage();
        return Err("missing command".to_owned());
    };
    let root = repo_root()?;
    match cmd {
        "ui" => ui_build(&root),
        "ui-site" => ui_site(&root),
        "ui-compress" => ui_compress(&root),
        "desktop-shell" => desktop_shell(&root),
        "native-service" => native_service(&root),
        "site-check" => site_check(&root),
        "site-prepare" => site_prepare(&root),
        "site" => site_build(&root),
        "update-manifest" => {
            if args.len() != 4 {
                return Err("usage: cargo xtask update-manifest <dist> <version> <notes.md>".to_owned());
            }
            let json = build_update_manifest(Path::new(&args[1]), &args[2], Path::new(&args[3]))?;
            print!("{json}");
            Ok(())
        }
        "rc2properties" => {
            if args.len() < 3 {
                return Err("usage: cargo xtask rc2properties <english .rc> <out dir> [<lang>=<.rc>...]".to_owned());
            }
            rc2properties(Path::new(&args[1]), Path::new(&args[2]), &args[3..])
        }
        "-h" | "--help" | "help" => {
            print_usage();
            Ok(())
        }
        other => {
            print_usage();
            Err(format!("unknown command: {other}"))
        }
    }
}

fn print_usage() {
    eprintln!(
        "Usage: cargo xtask <command>\n\
         \n\
         Commands:\n\
         \x20 ui                                    Build, test, lint and package njmule-ui\n\
         \x20 ui-site                               Build njmule-ui uncompressed site (for Tauri)\n\
         \x20 ui-compress                           Build njmule-ui gzipped assets (for njmule-engine)\n\
         \x20 desktop-shell                         Build the Tauri desktop shell (njmule-gui)\n\
         \x20 native-service                        Build build/windows/njmule-service.exe (Windows)\n\
         \x20 site-check                            Run Prettier and TypeScript checks on website/\n\
         \x20 site-prepare                          Generate captures, CLI help, API docs and partials\n\
         \x20 site                                  Build the documentation website (website/build/site)\n\
         \x20 update-manifest <dist> <ver> <notes>  Print njmule-update.json for a release dist folder\n\
         \x20 rc2properties <en.rc> <out> [l=.rc..] Convert eMule .rc string tables to .properties"
    );
}

fn repo_root() -> Result<PathBuf, String> {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    manifest_dir.parent().map(Path::to_path_buf).ok_or_else(|| "cannot determine repository root".to_owned())
}

fn project_version(root: &Path) -> Result<String, String> {
    let text = fs::read_to_string(root.join("VERSION")).map_err(|e| format!("failed to read VERSION: {e}"))?;
    let version = text.trim().to_owned();
    if version.is_empty() {
        return Err("VERSION file is empty".to_owned());
    }
    Ok(version)
}

fn npm_program() -> &'static str {
    if cfg!(windows) { "npm.cmd" } else { "npm" }
}

fn exe_suffix() -> &'static str {
    if cfg!(windows) { ".exe" } else { "" }
}

fn run_cmd(program: &str, args: &[&str], cwd: &Path, extra_env: &[(&str, &str)]) -> Result<(), String> {
    let mut cmd = Command::new(program);
    cmd.args(args).current_dir(cwd);
    for &(k, v) in extra_env {
        cmd.env(k, v);
    }
    let status = cmd.status().map_err(|e| format!("failed to spawn {program} {args:?} in {}: {e}", cwd.display()))?;
    if !status.success() {
        return Err(format!("command failed ({status}): {program} {}", args.join(" ")));
    }
    Ok(())
}

fn ensure_npm_installed(dir: &Path) -> Result<(), String> {
    if !dir.join("node_modules").is_dir() {
        run_cmd(npm_program(), &["ci"], dir, &[])?;
    }
    Ok(())
}

fn ui_site(root: &Path) -> Result<(), String> {
    let ui = root.join("njmule-ui");
    ensure_npm_installed(&ui)?;
    run_cmd(npm_program(), &["run", "build"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "translations"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "flags"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "site"], &ui, &[])
}

fn ui_compress(root: &Path) -> Result<(), String> {
    let ui = root.join("njmule-ui");
    ensure_npm_installed(&ui)?;
    run_cmd(npm_program(), &["run", "build"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "translations"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "flags"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "compress"], &ui, &[])
}

fn ui_build(root: &Path) -> Result<(), String> {
    let ui = root.join("njmule-ui");
    ensure_npm_installed(&ui)?;
    run_cmd(npm_program(), &["run", "build"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "test"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "lint"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "translations"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "flags"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "site"], &ui, &[])?;
    run_cmd(npm_program(), &["run", "compress"], &ui, &[])
}

fn desktop_shell(root: &Path) -> Result<(), String> {
    ui_site(root)?;
    let version = project_version(root)?;
    let desktop = root.join("njmule-desktop");
    ensure_npm_installed(&desktop)?;
    let build_dir = desktop.join("build");
    fs::create_dir_all(&build_dir).map_err(|e| format!("failed to create {}: {e}", build_dir.display()))?;
    let version_file = build_dir.join("tauri-version.json");
    let json = format!("{{ \"version\": {} }}\n", serde_json::to_string(&version).map_err(|e| e.to_string())?);
    fs::write(&version_file, json).map_err(|e| format!("failed to write {}: {e}", version_file.display()))?;
    let abs = version_file.to_str().ok_or_else(|| "non-UTF-8 path for tauri-version.json".to_owned())?;
    run_cmd(npm_program(), &["run", "build", "--", "--config", abs], &desktop, &[])
}

fn native_service(root: &Path) -> Result<(), String> {
    if !cfg!(windows) {
        return Err("native-service is only supported on Windows".to_owned());
    }
    let version = project_version(root)?;
    let res_dir = root.join("build").join("windows-res");
    let out_dir = root.join("build").join("windows");
    fs::create_dir_all(&res_dir).map_err(|e| format!("failed to create {}: {e}", res_dir.display()))?;
    fs::create_dir_all(&out_dir).map_err(|e| format!("failed to create {}: {e}", out_dir.display()))?;

    let rc_src = fs::read_to_string(root.join("packaging/windows/njmule-service.rc"))
        .map_err(|e| format!("failed to read njmule-service.rc: {e}"))?;
    let rc_rendered = rc_src.replace("${project.version}", &version);
    fs::write(res_dir.join("njmule-service.rc"), rc_rendered)
        .map_err(|e| format!("failed to write njmule-service.rc: {e}"))?;
    fs::copy(root.join("branding/generated/njmule.ico"), res_dir.join("njmule.ico"))
        .map_err(|e| format!("failed to copy njmule.ico: {e}"))?;

    let rc_cmd = root.join("packaging/windows/rc.cmd");
    let cl_cmd = root.join("packaging/windows/cl.cmd");
    let code = root.join("packaging/windows/service.c");
    let exe = out_dir.join("njmule-service.exe");

    run_cmd("cmd", &["/c", path_str(&rc_cmd)?, "njmule-service.rc", "njmule-service.res", &version], &res_dir, &[])?;
    run_cmd("cmd", &["/c", path_str(&cl_cmd)?, path_str(&exe)?, path_str(&code)?, "njmule-service.res"], &res_dir, &[])
}

fn path_str(path: &Path) -> Result<&str, String> {
    path.to_str().ok_or_else(|| format!("non-UTF-8 path: {}", path.display()))
}

fn engine_target_dir(root: &Path) -> PathBuf {
    env::var("CARGO_TARGET_DIR").map(PathBuf::from).unwrap_or_else(|_| root.join("njmule-engine/target"))
}

fn site_check(root: &Path) -> Result<(), String> {
    let website = root.join("website");
    ensure_npm_installed(&website)?;
    run_cmd(npm_program(), &["run", "api"], &website, &[])?;
    run_cmd(npm_program(), &["run", "check"], &website, &[])
}

fn site_prepare(root: &Path) -> Result<(), String> {
    ui_compress(root)?;
    let engine = root.join("njmule-engine");
    run_cmd("cargo", &["build", "--locked", "--bin", "njmule", "--example", "web_capture_demo"], &engine, &[])?;

    let debug_dir = engine_target_dir(root).join("debug");
    let ext = exe_suffix();
    let njmule_exe = debug_dir.join(format!("njmule{ext}"));
    let demo_exe = debug_dir.join("examples").join(format!("web_capture_demo{ext}"));

    let website = root.join("website");
    let cli_dir = website.join("build/cli-help");
    fs::create_dir_all(&cli_dir).map_err(|e| format!("failed to create {}: {e}", cli_dir.display()))?;
    capture_stdout(&njmule_exe, &["--help"], &cli_dir.join("help.txt"))?;
    capture_stdout(&njmule_exe, &["--remote", "help"], &cli_dir.join("remote.txt"))?;

    ensure_npm_installed(&website)?;
    run_cmd(npm_program(), &["run", "api"], &website, &[])?;

    let captures_dir = website.join("build/web-captures");
    run_cmd("node", &["scripts/capture-web.mjs", path_str(&demo_exe)?, path_str(&captures_dir)?], &website, &[])?;
    run_cmd(
        "node",
        &[
            "scripts/prepare.mjs",
            "--strict",
            "--web",
            path_str(&captures_dir)?,
            "--cli",
            path_str(&cli_dir.join("help.txt"))?,
            "--remote",
            path_str(&cli_dir.join("remote.txt"))?,
        ],
        &website,
        &[],
    )
}

fn site_build(root: &Path) -> Result<(), String> {
    site_prepare(root)?;
    let version = project_version(root)?;
    let website = root.join("website");
    run_cmd(
        npm_program(),
        &["run", "build", "--", "--out-dir", "build/site"],
        &website,
        &[("NJMULE_VERSION", &version)],
    )
}

fn capture_stdout(exe: &Path, args: &[&str], out_file: &Path) -> Result<(), String> {
    let output = Command::new(exe)
        .args(args)
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .output()
        .map_err(|e| format!("failed to run {} {args:?}: {e}", exe.display()))?;
    if !output.status.success() {
        return Err(format!("{} {args:?} failed with {}", exe.display(), output.status));
    }
    fs::write(out_file, &output.stdout).map_err(|e| format!("failed to write {}: {e}", out_file.display()))
}

// ---------------------------------------------------------------------------
// Release update manifest
// ---------------------------------------------------------------------------

fn classify_asset(name: &str) -> Option<(&'static str, &'static str)> {
    const PATTERNS: &[(&str, &str, &str, &str)] = &[
        ("njmule-", "-windows-x64.msi", "windows-x64", "msi"),
        ("njmule-", "-windows-x64.zip", "windows-x64", "zip"),
        ("njmule_", "_amd64.deb", "linux-x64", "deb"),
        ("njmule_", "_arm64.deb", "linux-arm64", "deb"),
        ("njmule-", ".x86_64.rpm", "linux-x64", "rpm"),
        ("njmule-", ".aarch64.rpm", "linux-arm64", "rpm"),
        ("njmule-", "-linux-x64-static.tar.xz", "linux-x64", "static-tar"),
        ("njmule-", "-linux-x64.tar.xz", "linux-x64", "tar"),
        ("njmule-", "-linux-arm64.tar.xz", "linux-arm64", "tar"),
        ("njmule-", "-macos-arm64.dmg", "macos-arm64", "dmg"),
        ("njmule-", "-synology-x86_64.spk", "linux-x64", "spk"),
    ];
    for &(prefix, suffix, platform, kind) in PATTERNS {
        if let Some(middle) = name.strip_prefix(prefix).and_then(|s| s.strip_suffix(suffix))
            && !middle.is_empty()
        {
            return Some((platform, kind));
        }
    }
    None
}

fn sha256_file(path: &Path) -> Result<String, io::Error> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 65_536];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(HEXLOWER.encode(&hasher.finalize()))
}

fn build_update_manifest(dist: &Path, version: &str, notes_file: &Path) -> Result<String, String> {
    let notes = fs::read_to_string(notes_file)
        .map_err(|e| format!("failed to read {}: {e}", notes_file.display()))?
        .trim()
        .to_owned();
    let critical = notes.lines().any(|line| line.trim() == "### Security");
    let mut files: Vec<PathBuf> = fs::read_dir(dist)
        .map_err(|e| format!("failed to read {}: {e}", dist.display()))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.is_file())
        .collect();
    files.sort();

    let mut assets = Vec::new();
    for file in files {
        let Some(name) = file.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        let Some((platform, kind)) = classify_asset(name) else {
            continue;
        };
        let size = fs::metadata(&file).map_err(|e| format!("failed to stat {}: {e}", file.display()))?.len();
        let hash = sha256_file(&file).map_err(|e| format!("failed to hash {}: {e}", file.display()))?;
        let line = format!(
            "    {{\"platform\": {}, \"kind\": {}, \"name\": {}, \"size\": {size}, \"sha256\": {}}}",
            json_str(platform)?,
            json_str(kind)?,
            json_str(name)?,
            json_str(&hash)?,
        );
        assets.push(line);
    }
    if assets.is_empty() {
        return Err(format!("no packages in {}", dist.display()));
    }
    let now = Timestamp::now().strftime("%Y-%m-%dT%H:%M:%SZ").to_string();
    Ok(format!(
        "{{\n  \"schema\": 1,\n  \"version\": {},\n  \"published\": {},\n  \"critical\": {critical},\n  \"notes\": {},\n  \"assets\": [\n{}\n  ]\n}}\n",
        json_str(version)?,
        json_str(&now)?,
        json_str(&notes)?,
        assets.join(",\n"),
    ))
}

fn json_str(value: &str) -> Result<String, String> {
    serde_json::to_string(value).map_err(|e| e.to_string())
}

// ---------------------------------------------------------------------------
// Resource script to .properties converter
// ---------------------------------------------------------------------------

fn rc2properties(english_rc: &Path, out_dir: &Path, specs: &[String]) -> Result<(), String> {
    let english = read_rc(english_rc)?;
    fs::create_dir_all(out_dir).map_err(|e| format!("failed to create {}: {e}", out_dir.display()))?;
    write_properties(&out_dir.join("emule.properties"), &english)?;
    println!("{} strings in English", english.len());
    let english_keys: BTreeSet<String> = english.keys().cloned().collect();
    for spec in specs {
        let Some((lang, rc_path)) = spec.split_once('=') else {
            return Err(format!("invalid language spec (expected lang=path): {spec}"));
        };
        let mut translated = read_rc(Path::new(rc_path))?;
        translated.retain(|k, _| english_keys.contains(k));
        write_properties(&out_dir.join(format!("emule_{lang}.properties")), &translated)?;
        let missing: Vec<&String> = english_keys.iter().filter(|k| !translated.contains_key(*k)).collect();
        println!("{} strings in {lang}, missing: {missing:?}", translated.len());
    }
    Ok(())
}

fn decode_rc(bytes: &[u8]) -> String {
    let slice = bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(bytes);
    match std::str::from_utf8(slice) {
        Ok(text) => text.to_owned(),
        Err(_) => WINDOWS_1252.decode(slice).0.into_owned(),
    }
}

fn parse_rc_quoted(s: &str) -> Option<&str> {
    let trimmed = s.trim_start();
    let rest = trimmed.strip_prefix('"')?;
    let bytes = rest.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'"' {
            if i + 1 < bytes.len() && bytes[i + 1] == b'"' {
                i += 2;
                continue;
            }
            let after = rest[i + 1..].trim();
            if after.is_empty() || after.starts_with("//") {
                return Some(&rest[..i]);
            }
            return None;
        }
        i += 1;
    }
    None
}

fn parse_rc_entry(line: &str) -> Option<(&str, Option<&str>)> {
    let trimmed = line.trim_start();
    if !trimmed.starts_with("IDS_") {
        return None;
    }
    let key_end = trimmed.find(|c: char| !(c.is_ascii_alphanumeric() || c == '_')).unwrap_or(trimmed.len());
    let key = &trimmed[..key_end];
    let rest = trimmed[key_end..].trim();
    if rest.is_empty() || rest.starts_with("//") {
        return Some((key, None));
    }
    parse_rc_quoted(rest).map(|val| (key, Some(val)))
}

fn read_rc(path: &Path) -> Result<BTreeMap<String, String>, String> {
    let data = fs::read(path).map_err(|e| format!("failed to read {}: {e}", path.display()))?;
    let text = decode_rc(&data);
    let lines: Vec<&str> = text.lines().collect();
    let mut strings = BTreeMap::new();
    let mut in_table = false;
    let mut i = 0;
    while i < lines.len() {
        let stripped = lines[i].trim();
        if stripped == "STRINGTABLE" {
            in_table = true;
            i += 1;
            continue;
        }
        if !in_table {
            i += 1;
            continue;
        }
        if stripped == "END" {
            in_table = false;
            i += 1;
            continue;
        }
        if let Some((key, maybe_text)) = parse_rc_entry(lines[i]) {
            let mut text_val = maybe_text;
            if text_val.is_none()
                && i + 1 < lines.len()
                && let Some(cont) = parse_rc_quoted(lines[i + 1])
            {
                text_val = Some(cont);
                i += 1;
            }
            if let Some(raw) = text_val {
                strings.insert(key.to_owned(), convert_rc_string(raw));
            }
        }
        i += 1;
    }
    Ok(strings)
}

fn strip_bracketed_mnemonics(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let chars: Vec<char> = raw.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        if i + 3 < chars.len()
            && chars[i] == '('
            && chars[i + 1] == '&'
            && chars[i + 2].is_ascii_alphanumeric()
            && chars[i + 3] == ')'
        {
            i += 4;
        } else {
            out.push(chars[i]);
            i += 1;
        }
    }
    out
}

fn convert_rc_string(raw: &str) -> String {
    let text = strip_bracketed_mnemonics(raw);
    let chars: Vec<char> = text.chars().collect();
    let mut plain = String::with_capacity(text.len());
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();
        if c == '"' && next == Some('"') {
            plain.push('"');
            i += 2;
        } else if c == '\\'
            && let Some(n) = next
        {
            match n {
                'n' => plain.push('\n'),
                't' => plain.push('\t'),
                'r' => plain.push('\r'),
                other => plain.push(other),
            }
            i += 2;
        } else if c == '&' {
            if next == Some('&') {
                plain.push('&');
                i += 2;
            } else {
                i += 1;
            }
        } else {
            plain.push(c);
            i += 1;
        }
    }
    convert_printf_specifiers(&plain).replace('\r', "")
}

fn convert_printf_specifiers(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] != b'%' {
            let ch = input[i..].chars().next().unwrap_or_default();
            out.push(ch);
            i += ch.len_utf8();
            continue;
        }
        let mut j = i + 1;
        while j < bytes.len() && matches!(bytes[j], b'-' | b'+' | b' ' | b'0' | b'#') {
            j += 1;
        }
        while j < bytes.len() && bytes[j].is_ascii_digit() {
            j += 1;
        }
        if j < bytes.len() && bytes[j] == b'.' {
            let mut k = j + 1;
            while k < bytes.len() && bytes[k].is_ascii_digit() {
                k += 1;
            }
            if k > j + 1 {
                j = k;
            }
        }
        let flags_and_width = &input[i + 1..j];
        let mut after_len = j;
        if input[after_len..].starts_with("I64") {
            after_len += 3;
        } else if input[after_len..].starts_with("ll") {
            after_len += 2;
        } else if matches!(bytes.get(after_len), Some(b'l' | b'h')) {
            after_len += 1;
        }
        if let Some(&spec) = bytes.get(after_len)
            && matches!(spec, b'd' | b'i' | b'u' | b'x' | b'X' | b's' | b'S' | b'c' | b'f' | b'g' | b'e' | b'E')
        {
            let mapped = match spec {
                b'i' | b'u' => 'd',
                b'S' => 's',
                other => char::from(other),
            };
            out.push('%');
            out.push_str(flags_and_width);
            out.push(mapped);
            i = after_len + 1;
            continue;
        }
        out.push('%');
        i += 1;
    }
    out
}

fn write_properties(file: &Path, strings: &BTreeMap<String, String>) -> Result<(), String> {
    let mut lines = Vec::with_capacity(strings.len() + 6);
    // REUSE-IgnoreStart: header for generated files, not this file.
    lines.push("# SPDX-FileCopyrightText: 2002-2026 Merkur and the eMule contributors".to_owned());
    lines.push("# SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors".to_owned());
    lines.push("# SPDX-License-Identifier: GPL-3.0-or-later".to_owned());
    // REUSE-IgnoreEnd
    lines.push("#".to_owned());
    lines.push("# Interface strings converted from eMule's string tables by cargo xtask rc2properties;".to_owned());
    lines.push("# regenerate instead of editing.".to_owned());
    for (k, v) in strings {
        lines.push(format!("{k}={}", escape_property(v)));
    }
    let content = format!("{}\n", lines.join("\n"));
    fs::write(file, content).map_err(|e| format!("failed to write {}: {e}", file.display()))
}

fn escape_property(value: &str) -> String {
    let mut out = String::with_capacity(value.len() + 8);
    for (idx, c) in value.chars().enumerate() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            ' ' if idx == 0 => out.push_str("\\ "),
            other => out.push(other),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn should_classify_all_release_package_names() {
        assert_eq!(classify_asset("njmule-0.5.0-windows-x64.msi"), Some(("windows-x64", "msi")));
        assert_eq!(classify_asset("njmule-0.5.0-windows-x64.zip"), Some(("windows-x64", "zip")));
        assert_eq!(classify_asset("njmule_0.5.0_amd64.deb"), Some(("linux-x64", "deb")));
        assert_eq!(classify_asset("njmule_0.5.0_arm64.deb"), Some(("linux-arm64", "deb")));
        assert_eq!(classify_asset("njmule-0.5.0.x86_64.rpm"), Some(("linux-x64", "rpm")));
        assert_eq!(classify_asset("njmule-0.5.0.aarch64.rpm"), Some(("linux-arm64", "rpm")));
        assert_eq!(classify_asset("njmule-0.5.0-linux-x64-static.tar.xz"), Some(("linux-x64", "static-tar")));
        assert_eq!(classify_asset("njmule-0.5.0-linux-x64.tar.xz"), Some(("linux-x64", "tar")));
        assert_eq!(classify_asset("njmule-0.5.0-linux-arm64.tar.xz"), Some(("linux-arm64", "tar")));
        assert_eq!(classify_asset("njmule-0.5.0-macos-arm64.dmg"), Some(("macos-arm64", "dmg")));
        assert_eq!(classify_asset("njmule-0.5.0-synology-x86_64.spk"), Some(("linux-x64", "spk")));
        assert_eq!(classify_asset("SHA256SUMS"), None);
    }

    #[test]
    fn should_convert_rc_mnemonics_and_printf_specifiers() {
        assert_eq!(convert_rc_string(r#"&Open (&O) ""%I64u"" && %S\n"#), "Open  \"%d\" & %s\n");
        assert_eq!(convert_rc_string("%.1f MiB (%lu)"), "%.1f MiB (%d)");
    }
}
