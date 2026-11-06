// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's SharedFilesBrowserTest, under its names: a client's answers to our request for its files.

#![allow(clippy::unwrap_used)]

use std::io;
use std::net::Ipv4Addr;

use njmule_engine::api::search_result::SearchResult;
use njmule_engine::net::routes::Routes;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::peer::shared_files_browser;
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::io::{PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::client;
use njmule_engine::protocol::packet::{Packet, frame_limits, protocol};
use njmule_engine::protocol::tag::tag_codec::write_ed2k_list;
use njmule_engine::protocol::tag::{Ed2kTagFormat, Tag, TagName, file_tag};
use rand::SeedableRng;
use rand::rngs::StdRng;
use tokio::net::TcpListener;

const FILE: [u8; 16] = [0x0F; 16];

fn hello(user_hash: [u8; 16], nick: &str) -> Hello {
    let ours = Ours { nick, udp_port: 0, kad_port: 0, misc_options1: 0, misc_options2: 0 };
    Hello::of(user_hash, 0, 4662, &ours, 0, 0)
}

#[tokio::test]
async fn should_not_take_an_extended_packet_with_the_opcode_of_a_refusal_for_one() {
    let answers = [refusal(protocol::EMULE), files_answer(&list(FILE, "film.avi", 700))];

    let files = browse(&answers).await;

    let files = files.unwrap();
    let only: Vec<(&[u8; 16], &str, u64)> = files.iter().map(|f| (&f.hash, f.name.as_str(), f.size)).collect();
    assert_eq!(only, [(&FILE, "film.avi", 700)]);
}

#[tokio::test]
async fn should_fail_the_browse_on_a_refusal() {
    let answers = [refusal(protocol::EDONKEY)];

    let files = browse(&answers).await;

    let e = files.unwrap_err();
    assert!(e.to_string().contains("does not show its files"), "{e}");
}

#[tokio::test]
async fn should_fail_the_browse_on_a_list_with_more_entries_than_it_holds() {
    let answers = [files_answer(&claiming_entries(5, &list(FILE, "one.bin", 1)))];

    let files = browse(&answers).await;

    assert_eq!(files.unwrap_err().kind(), io::ErrorKind::InvalidData);
}

#[tokio::test]
async fn should_leave_out_entries_without_a_name_or_a_size() {
    let answers = [files_answer(&entries(&[
        entry(vec![size(10)]),
        entry(vec![name("no size")]),
        entry(vec![name("empty"), size(0)]),
    ]))];

    let files = browse(&answers).await;

    assert!(files.unwrap().is_empty());
}

// A client that takes our hello, answers it, then sends `answers` to our request.
async fn browse(answers: &[Packet]) -> io::Result<Vec<SearchResult>> {
    let server = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
    let address = server.local_addr().unwrap();
    let answers = answers.to_vec();
    let client = tokio::spawn(async move {
        let (mut stream, _) = server.accept().await.unwrap();
        assert_eq!(read_packet(&mut stream, &frame_limits::client).await.unwrap().unwrap().opcode(), client::HELLO);
        write_packet(&mut stream, &hello([2; 16], "them").encode(true).unwrap()).await.unwrap();
        let request = read_packet(&mut stream, &frame_limits::client).await.unwrap().unwrap();
        assert_eq!(request.opcode(), client::ASKSHAREDFILES);
        for answer in answers {
            write_packet(&mut stream, &answer).await.unwrap();
        }
        // Until the browser closes.
        let _ = read_packet(&mut stream, &frame_limits::client).await;
    });
    let mut random = StdRng::from_rng(&mut rand::rng());
    let files = shared_files_browser::browse(address, &hello([1; 16], "us"), &Routes::direct(), &mut random).await;
    drop(client);
    files
}

fn refusal(protocol: u8) -> Packet {
    Packet::empty(protocol, client::ASKSHAREDDENIEDANS)
}

fn files_answer(list: &[u8]) -> Packet {
    Packet::of(protocol::EDONKEY, client::ASKSHAREDFILESANSWER, list.to_vec())
}

fn list(hash: [u8; 16], file_name: &str, file_size: u64) -> Vec<u8> {
    entries(&[FileEntry { hash, client_id: 0, port: 0, tags: vec![name(file_name), size(file_size)] }])
}

// The entries of a list under another count.
fn claiming_entries(count: u32, list: &[u8]) -> Vec<u8> {
    let mut out = count.to_le_bytes().to_vec();
    out.extend_from_slice(list.get(4..).unwrap());
    out
}

fn entries(entries: &[FileEntry]) -> Vec<u8> {
    let mut out = PacketWriter::new();
    out.u32(u32::try_from(entries.len()).unwrap());
    for entry in entries {
        out.hash16(&entry.hash).u32(0).u16(0).u32(u32::try_from(entry.tags.len()).unwrap());
        write_ed2k_list(&mut out, &entry.tags, Ed2kTagFormat::New, StringEncoding::Utf8).unwrap();
    }
    out.into_byte_array()
}

fn entry(tags: Vec<Tag>) -> FileEntry {
    FileEntry { hash: FILE, client_id: 0, port: 0, tags }
}

fn name(name: &str) -> Tag {
    Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() }
}

fn size(size: u64) -> Tag {
    Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size }
}
