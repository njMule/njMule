// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Upload slots, queueing, bans, friend slot, SecureIdent-gated
//! friend sharing, shared directory browsing, AICH requests, idle-slot handover and switching files in a slot.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use njmule_engine::api::ed2k_link::FileLink;
use njmule_engine::api::friend::Friend;
use njmule_engine::api::shared_file_info::SharedFileInfo;
use njmule_engine::api::upload_info::UploadState;
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::crypto::rsa as engine_rsa;
use njmule_engine::engine::Engine;
use njmule_engine::net::tcp_connection::{read_packet, write_packet};
use njmule_engine::peer::identity_handshake::{self, Identity, IdentityHandshake};
use njmule_engine::protocol::ed2k::client_messages::{self, FileName, Range, RequestParts, SendingPart};
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::hello::{Hello, Ours};
use njmule_engine::protocol::ed2k::source_exchange;
use njmule_engine::protocol::io::{PacketReader, PacketWriter, StringEncoding};
use njmule_engine::protocol::packet::opcodes::{client, extended};
use njmule_engine::protocol::packet::{Packet, protocol};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpSocket, TcpStream};

use crate::free_port;

const WAIT: Duration = Duration::from_secs(20);
const PARTIAL: [u8; 16] = [0x5A; 16];
const AICH: [u8; 20] = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20];
const UNKNOWN: [u8; 16] = [0x77; 16];

fn is_identification(packet: &Packet) -> bool {
    packet.protocol() == protocol::EMULE
        && matches!(packet.opcode(), extended::SECIDENTSTATE | extended::PUBLICKEY | extended::SIGNATURE)
}

#[tokio::test]
async fn should_give_the_slots_the_limit_allows_and_queue_the_rest() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_clients(&[1, 2, 3]).await;
    lab.given_the_clients_ask_for_the_file(&[1, 2, 3]).await;
    lab.given_the_client_gets_a_slot(1).await;
    lab.given_the_client_gets_a_slot(2).await;

    lab.when_the_client_asks_for_an_upload(3).await;

    lab.then_the_answer_is_the_queue_rank(1);
    lab.then_the_upload_states_are(&[UploadState::Uploading, UploadState::Uploading, UploadState::Queued]).await;
    lab.stop().await;
}

#[tokio::test]
async fn should_ban_and_drop_a_client_that_asks_too_often() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_clients(&[1, 2, 3]).await;
    lab.given_the_clients_ask_for_the_file(&[1, 2, 3]).await;
    lab.given_the_client_gets_a_slot(1).await;
    lab.given_the_client_gets_a_slot(2).await;
    lab.given_the_client_is_queued_at_rank(3, 1).await;
    lab.given_the_client_is_tolerated_asking_again_at_once(3, 3).await;

    let dropped = lab.when_the_client_asks_once_more_expecting_drop(3).await;

    assert!(dropped);
    lab.stop().await;
}

#[tokio::test]
async fn should_send_a_slot_the_block_it_asks_for_within_the_limit_once_the_banned_client_left() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_clients(&[1, 2, 3]).await;
    lab.given_the_clients_ask_for_the_file(&[1, 2, 3]).await;
    lab.given_the_client_gets_a_slot(1).await;
    lab.given_the_client_gets_a_slot(2).await;
    lab.given_the_client_is_queued_at_rank(3, 1).await;
    lab.given_the_client_is_tolerated_asking_again_at_once(3, 3).await;
    assert!(lab.when_the_client_asks_once_more_expecting_drop(3).await);

    lab.when_the_client_requests_the_bytes(1, 0, 1000).await;

    let part = SendingPart::decode(lab.answer()).unwrap();
    assert_eq!(lab.answer().opcode(), client::SENDINGPART);
    assert_eq!(part.end, 1000);
    lab.then_the_upload_states_are(&[UploadState::Uploading, UploadState::Uploading]).await;
    let uploads = lab.engine().uploads().await;
    assert!(uploads.iter().all(|u| u.file == lab.file().name));
    assert_eq!(uploads.iter().map(|u| u.transferred).sum::<u64>(), 1000);
    let status = lab.engine().status().await.unwrap();
    assert_eq!(status.uploads, 2);
    assert_eq!(status.queued, 0);
    assert_eq!(status.uploaded, 1000);
    assert_eq!(status.shared_files, 1);
    assert!(status.server.is_none());
    let bans = lab.engine().bans().await;
    assert_eq!(bans.len(), 1);
    assert!(bans[0].seconds_left > 0);
    lab.stop().await;
}

#[tokio::test]
async fn should_lift_a_ban() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_clients(&[1, 2, 3]).await;
    lab.given_the_clients_ask_for_the_file(&[1, 2, 3]).await;
    lab.given_the_client_gets_a_slot(1).await;
    lab.given_the_client_gets_a_slot(2).await;
    lab.given_the_client_is_queued_at_rank(3, 1).await;
    lab.given_the_client_is_tolerated_asking_again_at_once(3, 3).await;
    assert!(lab.when_the_client_asks_once_more_expecting_drop(3).await);

    let bans = lab.engine().bans().await;
    assert!(lab.engine().unban(bans[0].address.clone()).await.unwrap());

    assert!(lab.engine().bans().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_lift_no_ban_of_an_address_never_banned() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;

    assert!(!lab.engine().unban("192.0.2.1".to_owned()).await.unwrap());
    lab.stop().await;
}

#[tokio::test]
async fn should_go_on_uploading_after_a_limit_is_set_where_there_was_none() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_the_client_gets_a_slot(15).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    lab.engine()
        .update_preferences(&BTreeMap::from([("limits.uploadKiBps".to_owned(), "10".to_owned())]))
        .unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;

    let hash = lab.file().hash;
    lab.client(15).await.request_bytes(&hash, 0, 1000).await;
    let polled = lab.client(15).await.poll(Duration::from_secs(5)).await;

    assert_eq!(polled.unwrap().opcode(), client::SENDINGPART);
    lab.stop().await;
}

#[tokio::test]
async fn should_skip_an_inverted_range_in_a_request() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_the_client_gets_a_slot(21).await;

    let hash = lab.file().hash;
    let mut w = PacketWriter::new();
    w.bytes(&hash).u32(5000).u32(0).u32(0).u32(4000).u32(1000).u32(0);
    let pkt = Packet::of(protocol::EDONKEY, client::REQUESTPARTS, w.into_byte_array());
    lab.client(21).await.send(&pkt).await;
    let answer = lab.client(21).await.next().await.unwrap();

    let part = SendingPart::decode(&answer).unwrap();
    assert_eq!(part.start, 0);
    assert_eq!(part.end, 1000);
    lab.stop().await;
}

#[tokio::test]
async fn should_keep_sending_a_file_renamed_during_its_upload() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_the_client_gets_a_slot(20).await;
    lab.when_the_client_requests_the_bytes(20, 0, 1000).await;
    assert_eq!(lab.answer().opcode(), client::SENDINGPART);
    assert!(lab.engine().rename_shared_file(lab.file().hash, "renamed.bin".to_owned()).await.unwrap());

    lab.when_the_client_requests_the_bytes(20, 1000, 2000).await;

    let part = SendingPart::decode(lab.answer()).unwrap();
    let expected = fs::read(lab.dir.path().join("njmule/incoming/renamed.bin")).unwrap();
    assert_eq!(part.data, expected[1000..2000]);
    lab.stop().await;
}

#[tokio::test]
async fn should_refuse_the_friend_slot_to_a_client_that_is_not_a_friend() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_a_friend(4).await;

    assert!(!lab.engine().set_friend_slot(Some(user_hash_of(9))).await);
    lab.stop().await;
}

#[tokio::test]
async fn should_give_the_friend_slot_to_a_friend() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_a_friend(4).await;

    assert!(lab.engine().set_friend_slot(Some(user_hash_of(4))).await);
    assert_eq!(lab.engine().friend_slot().await, Some(user_hash_of(4)));
    lab.stop().await;
}

#[tokio::test]
async fn should_let_the_friend_with_the_friend_slot_go_before_the_queue() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_a_friend(4).await;
    assert!(lab.engine().set_friend_slot(Some(user_hash_of(4))).await);
    lab.given_the_clients_ask_for_the_file(&[1, 2, 3, 4]).await;
    lab.given_the_client_gets_a_slot(1).await;
    lab.given_the_client_gets_a_slot(2).await;
    lab.given_the_client_is_queued_at_rank(3, 1).await;

    lab.when_the_client_asks_for_an_upload(4).await;

    assert_eq!(client_messages::decode_queue_rank(lab.answer()).unwrap(), 1, "the friend comes first");
    lab.stop().await;
}

#[tokio::test]
async fn should_give_the_friend_slot_only_once_the_friend_proves_its_key() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_a_friend(16).await;
    assert!(lab.engine().set_friend_slot(Some(user_hash_of(16))).await);
    lab.given_the_client_gets_a_slot(1).await;
    lab.given_the_client_gets_a_slot(2).await;
    lab.given_the_client_is_queued_at_rank(3, 1).await;
    lab.given_an_identifying_client(16).await;
    lab.given_the_client_is_queued_at_rank(16, 2).await;
    lab.given_an_identifying_client(16).await;
    lab.client(16).await.prove().await;

    lab.when_the_client_asks_for_an_upload(16).await;

    assert_eq!(client_messages::decode_queue_rank(lab.answer()).unwrap(), 1, "proven");
    lab.stop().await;
}

#[tokio::test]
async fn should_show_files_shared_with_friends_to_no_client_that_fails_to_prove_the_friends_key() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_visible_to("FRIENDS").await;
    lab.given_a_friend(17).await;
    lab.given_an_identifying_client(17).await;
    lab.client(17).await.send(&ask_shared_files()).await;
    assert!(lab.client(17).await.poll(Duration::from_secs(1)).await.is_none());

    lab.client(17).await.send_fake_proof().await;
    let answer = lab.client(17).await.next().await.unwrap();

    assert_eq!(answer.payload(), [0, 0, 0, 0]);
    lab.stop().await;
}

#[tokio::test]
async fn should_show_files_shared_with_friends_to_the_friend_not_taking_part_in_the_identification() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_visible_to("FRIENDS").await;
    lab.given_a_friend(17).await;
    lab.given_clients(&[17]).await;

    lab.client(17).await.send(&ask_shared_files()).await;
    let answer = lab.client(17).await.next().await.unwrap();

    assert!(answer.payload().starts_with(&[1, 0, 0, 0]));
    lab.stop().await;
}

#[tokio::test]
async fn should_show_files_shared_with_friends_to_the_friend_proving_its_key() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_visible_to("FRIENDS").await;
    lab.given_a_friend(17).await;
    lab.given_an_identifying_client(17).await;
    lab.client(17).await.send(&ask_shared_files()).await;

    lab.client(17).await.prove().await;
    let answer = lab.client(17).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ASKSHAREDFILESANSWER);
    assert!(answer.payload().starts_with(&[1, 0, 0, 0]));
    lab.stop().await;
}

#[tokio::test]
async fn should_list_our_files_in_one_pseudo_directory_to_clients_browsing_by_directory() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_visible_to("EVERYBODY").await;
    lab.given_clients(&[18]).await;

    lab.client(18).await.send(&ask_shared_directories()).await;
    let answer = lab.client(18).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ASKSHAREDDIRSANS);
    let mut reader = PacketReader::of(answer.payload());
    assert_eq!(reader.u32().unwrap(), 1);
    assert_eq!(reader.string16().unwrap(), "!Other");
    lab.stop().await;
}

#[tokio::test]
async fn should_give_our_files_in_the_pseudo_directory_to_clients_browsing_by_directory() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_visible_to("EVERYBODY").await;
    lab.given_clients(&[18]).await;
    lab.client(18).await.send(&ask_shared_directories()).await;
    let _ = lab.client(18).await.next().await.unwrap();

    lab.client(18).await.send(&ask_files_of("!Other")).await;
    let answer = lab.client(18).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ASKSHAREDFILESDIRANS);
    let mut reader = PacketReader::of(answer.payload());
    assert_eq!(reader.string16().unwrap(), "!Other");
    assert_eq!(reader.u32().unwrap(), 1);
    assert_eq!(FileEntry::read(&mut reader).unwrap().hash, lab.file().hash);
    lab.stop().await;
}

#[tokio::test]
async fn should_give_our_files_once_a_connection() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_visible_to("EVERYBODY").await;
    lab.given_clients(&[18]).await;
    lab.client(18).await.send(&ask_shared_directories()).await;
    let _ = lab.client(18).await.next().await.unwrap();
    lab.client(18).await.send(&ask_files_of("!Other")).await;
    let _ = lab.client(18).await.next().await.unwrap();

    lab.client(18).await.send(&ask_shared_files()).await;
    let polled = lab.client(18).await.poll(Duration::from_secs(1)).await;

    assert!(polled.is_none());
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_the_aich_hash_of_a_download_to_clients_that_ask_for_it() {
    let mut lab = Lab::new();
    lab.start_engine("", 1).await;
    let link =
        FileLink::new("partial.bin".to_owned(), 1_000_000, PARTIAL, Some(AICH), Vec::new(), Vec::new(), Vec::new())
            .unwrap();
    lab.engine().add_download(link, None).await.unwrap();
    lab.given_clients(&[19]).await;

    let mut w = PacketWriter::new();
    w.bytes(&PARTIAL);
    lab.client(19)
        .await
        .send(&Packet::of(protocol::EMULE, extended::AICHFILEHASHREQ, w.into_byte_array()))
        .await;
    let polled = lab.client(19).await.poll(Duration::from_secs(3)).await.unwrap();

    assert_eq!(polled.opcode(), extended::AICHFILEHASHANS);
    let mut reader = PacketReader::of(polled.payload());
    assert_eq!(reader.hash16().unwrap(), PARTIAL);
    assert_eq!(reader.hash20().unwrap(), AICH);
    lab.stop().await;
}

#[tokio::test]
async fn should_share_a_file_with_the_automatic_upload_priority() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;

    assert_eq!(lab.file().priority, UploadPriority::Auto);
    lab.stop().await;
}

#[tokio::test]
async fn should_keep_the_upload_priority_a_shared_file_is_given() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;

    assert!(lab.engine().set_upload_priority(lab.file().hash, UploadPriority::High).await);
    let files = lab.engine().shared_files().await;
    assert_eq!(files.len(), 1);
    assert_eq!(files[0].priority, UploadPriority::High);
    lab.stop().await;
}

#[tokio::test]
async fn should_set_no_upload_priority_for_a_file_not_shared() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;

    assert!(!lab.engine().set_upload_priority([0u8; 16], UploadPriority::Low).await);
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_a_downloader_the_name_of_the_file() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;
    let hash = lab.file().hash;

    lab.client(4).await.send(&client_messages::for_file(client::REQUESTFILENAME, &hash)).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(FileName::decode(&answer).unwrap().name, "queued.bin");
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_a_downloader_that_a_file_is_not_here() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;

    lab.client(4).await.send(&client_messages::for_file(client::SETREQFILEID, &UNKNOWN)).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::FILEREQANSNOFIL);
    lab.stop().await;
}

#[tokio::test]
async fn should_show_a_downloader_no_shared_files_by_default() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;

    lab.client(4).await.send(&ask_shared_files()).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ASKSHAREDFILESANSWER);
    assert_eq!(answer.payload(), [0, 0, 0, 0]);
    lab.stop().await;
}

#[tokio::test]
async fn should_deny_a_downloader_our_shared_directories_by_default() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;

    lab.client(4).await.send(&ask_shared_directories()).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ASKSHAREDDENIEDANS);
    lab.stop().await;
}

#[tokio::test]
async fn should_deny_a_downloader_the_files_of_a_directory_by_default() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;

    lab.client(4).await.send(&ask_files_of("!Other")).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ASKSHAREDDENIEDANS);
    lab.stop().await;
}

#[tokio::test]
async fn should_tell_a_downloader_its_public_ip() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;

    lab.client(4).await.send(&Packet::empty(protocol::EMULE, extended::PUBLICIP_REQ)).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(answer.opcode(), extended::PUBLICIP_ANSWER);
    assert_eq!(answer.payload(), [127, 0, 0, 1]);
    lab.stop().await;
}

#[tokio::test]
async fn should_answer_the_aich_request_of_a_downloader() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;
    let hash = lab.file().hash;

    let mut w = PacketWriter::new();
    w.bytes(&hash).u16(0).bytes(&[0u8; 20]);
    lab.client(4).await.send(&Packet::of(protocol::EMULE, extended::AICHREQUEST, w.into_byte_array())).await;
    let answer = lab.client(4).await.next().await.unwrap();

    assert_eq!(answer.opcode(), extended::AICHANSWER);
    assert_eq!(answer.payload().len(), 16);
    lab.stop().await;
}

#[tokio::test]
async fn should_give_a_downloader_a_slot_after_a_request_for_sources_nobody_can_answer() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file().await;
    lab.given_clients(&[4]).await;
    let hash = lab.file().hash;

    lab.client(4).await.send(&source_exchange::request(&hash)).await;
    let answer = lab.client(4).await.ask(&hash).await;
    lab.client(4).await.send(&Packet::empty(protocol::EDONKEY, client::CANCELTRANSFER)).await;

    assert_eq!(answer.opcode(), client::ACCEPTUPLOADREQ);
    lab.stop().await;
}

#[tokio::test]
async fn should_call_a_waiting_client_back_when_a_slot_frees() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_clients(&[5, 6]).await;
    lab.given_the_client_gets_a_slot(5).await;
    lab.given_the_client_gets_a_slot(6).await;

    let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
    let listen_port = listener.local_addr().unwrap().port();
    let hash = lab.file().hash;
    {
        let mut waiting = Client::connect(lab.port, 7, listen_port, 0).await;
        assert_eq!(waiting.ask(&hash).await.opcode(), extended::QUEUERANKING);
    }

    lab.client(5).await.send(&Packet::empty(protocol::EDONKEY, client::CANCELTRANSFER)).await;
    let (socket, _) = tokio::time::timeout(WAIT, listener.accept()).await.unwrap().unwrap();
    let mut called = Client::from_stream(socket);
    assert_eq!(called.next().await.unwrap().opcode(), client::HELLO);

    called.send(&hello(7, listen_port, 0).encode(true).unwrap()).await;
    let answer = called.next().await.unwrap();

    assert_eq!(answer.opcode(), client::ACCEPTUPLOADREQ);
    lab.stop().await;
}

#[tokio::test]
async fn should_give_another_address_claiming_a_user_hash_neither_its_slot_nor_its_place() {
    // macOS gives the loopback 127.0.0.1 alone, not the whole of 127.0.0.0/8.
    let Ok(probe) = TcpSocket::new_v4().and_then(|s| {
        s.bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), 0))?;
        Ok(s)
    }) else {
        return;
    };
    drop(probe);

    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_the_clients_ask_for_the_file(&[7]).await;
    lab.given_the_client_gets_a_slot(7).await;

    let hash = lab.file().hash;
    let mut impostor = Client::connect_from(Ipv4Addr::new(127, 0, 0, 2), lab.port, 7).await;
    impostor.send(&client_messages::for_file(client::STARTUPLOADREQ, &hash)).await;
    assert!(impostor.poll(Duration::from_secs(1)).await.is_none());

    lab.when_the_client_requests_the_bytes(7, 0, 1000).await;

    assert_eq!(lab.answer().opcode(), client::SENDINGPART);
    assert_eq!(lab.engine().uploads().await.len(), 1);
    lab.stop().await;
}

#[tokio::test]
async fn should_drop_and_ban_a_client_changing_its_user_hash() {
    let mut lab = Lab::new();
    lab.start_engine("", 1).await;
    lab.given_clients(&[8]).await;
    lab.client(8).await.send(&hello(9, 5009, 0).encode(false).unwrap()).await;
    assert!(lab.client(8).await.next().await.is_none());

    let mut again = TcpStream::connect((Ipv4Addr::LOCALHOST, lab.port)).await.unwrap();
    let mut byte = [0u8; 1];
    let n = tokio::time::timeout(WAIT, again.read(&mut byte)).await.unwrap().unwrap_or(0);
    assert_eq!(n, 0, "banned address should be closed at once");
    assert!(lab.engine().uploads().await.is_empty());
    lab.stop().await;
}

#[tokio::test]
async fn should_give_a_slot_left_idle_to_the_next_client_in_the_queue() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_a_file_at_kibps(1).await;
    lab.given_clients(&[11, 12]).await;
    lab.given_the_client_gets_a_slot(11).await;
    lab.given_the_client_gets_a_slot(12).await;
    lab.given_the_client_is_queued(13).await;
    lab.when_the_client_requests_the_bytes(11, 0, 1000).await;
    assert_eq!(lab.answer().opcode(), client::SENDINGPART);

    let polled_12 = lab.client(12).await.poll(Duration::from_secs(25)).await;
    let polled_13 = lab.client(13).await.poll(Duration::from_secs(25)).await;

    assert_eq!(polled_12.unwrap().opcode(), client::OUTOFPARTREQS);
    assert_eq!(polled_13.unwrap().opcode(), client::ACCEPTUPLOADREQ);
    lab.stop().await;
}

#[tokio::test]
async fn should_send_the_file_its_client_switched_to_in_a_slot() {
    let mut lab = Lab::new();
    lab.given_an_engine_sharing_two_files().await;
    lab.given_the_client_gets_a_slot(14).await;
    let second_hash = lab.files[1].hash;
    let second_name = lab.files[1].name.clone();
    lab.client(14).await.send(&client_messages::for_file(client::SETREQFILEID, &second_hash)).await;
    assert_eq!(lab.client(14).await.next().await.unwrap().opcode(), client::FILESTATUS);

    lab.client(14).await.request_bytes(&second_hash, 0, 1000).await;
    let answer = lab.client(14).await.next().await.unwrap();

    let part = SendingPart::decode(&answer).unwrap();
    assert_eq!(part.file, second_hash);
    let expected = fs::read(lab.dir.path().join("njmule/incoming").join(second_name)).unwrap();
    assert_eq!(part.data, expected[0..1000]);
    lab.stop().await;
}

struct Lab {
    dir: tempfile::TempDir,
    engine: Option<Engine>,
    port: u16,
    files: Vec<SharedFileInfo>,
    clients: HashMap<u64, Client>,
    answer: Option<Packet>,
}

impl Lab {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            engine: None,
            port: 0,
            files: Vec::new(),
            clients: HashMap::new(),
            answer: None,
        }
    }

    fn engine(&self) -> &Engine {
        self.engine.as_ref().unwrap()
    }

    fn file(&self) -> &SharedFileInfo {
        &self.files[0]
    }

    fn answer(&self) -> &Packet {
        self.answer.as_ref().unwrap()
    }

    async fn start_engine(&mut self, extra: &str, count: usize) {
        let root = self.dir.path().join("njmule");
        let dirs = AppDirectories::under_root(&root).unwrap();
        fs::create_dir_all(&dirs.config).unwrap();
        let incoming = root.join("incoming");
        fs::create_dir_all(&incoming).unwrap();
        for i in 0..count {
            let mut data = vec![0u8; 300_000];
            StdRng::seed_from_u64(5 + i as u64).fill(&mut data[..]);
            let name = if i == 0 { "queued.bin".to_owned() } else { format!("other{i}.bin") };
            fs::write(incoming.join(name), data).unwrap();
        }
        let port = free_port::free_port();
        fs::write(
            dirs.config.join("njmule.properties"),
            format!("net.tcpPort={port}\nnet.kad.enabled=false\ndirs.incoming={}\n{extra}", escaped(&incoming)),
        )
        .unwrap();
        let engine = Engine::start(dirs).await.unwrap();
        self.port = engine.listen_port();
        self.engine = Some(engine);
    }

    async fn await_shared(&mut self, count: usize) {
        let deadline = Instant::now() + WAIT;
        while Instant::now() < deadline {
            let files = self.engine().shared_files().await;
            if files.len() >= count {
                self.files = files;
                return;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!("not shared");
    }

    async fn given_an_engine_sharing_a_file(&mut self) {
        self.start_engine("", 1).await;
        self.await_shared(1).await;
    }

    async fn given_an_engine_sharing_a_file_at_kibps(&mut self, kibps: u32) {
        self.start_engine(&format!("limits.uploadKiBps={kibps}\n"), 1).await;
        self.await_shared(1).await;
    }

    async fn given_an_engine_sharing_a_file_visible_to(&mut self, visibility: &str) {
        self.start_engine(&format!("share.visibleTo={visibility}\n"), 1).await;
        self.await_shared(1).await;
    }

    async fn given_an_engine_sharing_two_files(&mut self) {
        self.start_engine("", 2).await;
        self.await_shared(2).await;
    }

    async fn given_a_friend(&self, seed: u64) {
        self.engine()
            .add_friend(Friend { user_hash: user_hash_of(seed), name: "pal".to_owned(), address: None })
            .await
            .unwrap();
    }

    async fn given_clients(&mut self, seeds: &[u64]) {
        for &seed in seeds {
            let c = Client::connect(self.port, seed, 5000 + seed as u16, 0).await;
            self.clients.insert(seed, c);
        }
    }

    async fn given_an_identifying_client(&mut self, seed: u64) {
        let misc1 = identity_handshake::SUPPORTED << 16;
        let c = Client::connect(self.port, seed, 5000 + seed as u16, misc1).await;
        self.clients.insert(seed, c);
    }

    async fn client(&mut self, seed: u64) -> &mut Client {
        if !self.clients.contains_key(&seed) {
            let c = Client::connect(self.port, seed, 5000 + seed as u16, 0).await;
            self.clients.insert(seed, c);
        }
        self.clients.get_mut(&seed).unwrap()
    }

    async fn given_the_clients_ask_for_the_file(&mut self, seeds: &[u64]) {
        let hash = self.file().hash;
        for &seed in seeds {
            let c = self.client(seed).await;
            c.send(&client_messages::for_file(client::SETREQFILEID, &hash)).await;
            assert_eq!(c.next().await.unwrap().opcode(), client::FILESTATUS);
        }
    }

    async fn given_the_client_gets_a_slot(&mut self, seed: u64) {
        let hash = self.file().hash;
        let ans = self.client(seed).await.ask(&hash).await;
        assert_eq!(ans.opcode(), client::ACCEPTUPLOADREQ);
    }

    async fn given_the_client_is_queued(&mut self, seed: u64) {
        let hash = self.file().hash;
        let ans = self.client(seed).await.ask(&hash).await;
        assert_eq!(ans.opcode(), extended::QUEUERANKING);
    }

    async fn given_the_client_is_queued_at_rank(&mut self, seed: u64, rank: u32) {
        let hash = self.file().hash;
        let ans = self.client(seed).await.ask(&hash).await;
        assert_eq!(client_messages::decode_queue_rank(&ans).unwrap(), rank);
    }

    async fn given_the_client_is_tolerated_asking_again_at_once(&mut self, seed: u64, times: usize) {
        for _ in 0..times {
            self.given_the_client_is_queued(seed).await;
        }
    }

    async fn when_the_client_asks_for_an_upload(&mut self, seed: u64) {
        let hash = self.file().hash;
        let ans = self.client(seed).await.ask(&hash).await;
        self.answer = Some(ans);
    }

    async fn when_the_client_asks_once_more_expecting_drop(&mut self, seed: u64) -> bool {
        let hash = self.file().hash;
        let c = self.client(seed).await;
        c.send(&client_messages::for_file(client::STARTUPLOADREQ, &hash)).await;
        c.next().await.is_none()
    }

    async fn when_the_client_requests_the_bytes(&mut self, seed: u64, start: u64, end: u64) {
        let hash = self.file().hash;
        let c = self.client(seed).await;
        c.request_bytes(&hash, start, end).await;
        self.answer = Some(c.next().await.unwrap());
    }

    fn then_the_answer_is_the_queue_rank(&self, rank: u32) {
        assert_eq!(self.answer().protocol(), protocol::EMULE);
        assert_eq!(self.answer().opcode(), extended::QUEUERANKING);
        assert_eq!(client_messages::decode_queue_rank(self.answer()).unwrap(), rank);
    }

    async fn then_the_upload_states_are(&self, states: &[UploadState]) {
        let actual: Vec<UploadState> = self.engine().uploads().await.into_iter().map(|u| u.state).collect();
        assert_eq!(actual, states);
    }

    async fn stop(mut self) {
        self.clients.clear();
        if let Some(engine) = self.engine.take() {
            engine.stop().await;
        }
    }
}

struct Client {
    stream: TcpStream,
    engine_hello: Option<Hello>,
}

impl Client {
    fn from_stream(stream: TcpStream) -> Self {
        Self { stream, engine_hello: None }
    }

    async fn connect(port: u16, seed: u64, listen_port: u16, misc1: u32) -> Self {
        let stream = TcpStream::connect((Ipv4Addr::LOCALHOST, port)).await.unwrap();
        let mut client = Self::from_stream(stream);
        client.send(&hello(seed, listen_port, misc1).encode(false).unwrap()).await;
        let answer = client.next().await.unwrap();
        assert_eq!(answer.opcode(), client::HELLOANSWER);
        client.engine_hello = Some(Hello::decode(&answer).unwrap());
        client
    }

    async fn connect_from(local: Ipv4Addr, port: u16, seed: u64) -> Self {
        let socket = TcpSocket::new_v4().unwrap();
        socket.bind(SocketAddr::new(IpAddr::V4(local), 0)).unwrap();
        let stream = socket.connect(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port)).await.unwrap();
        let mut client = Self::from_stream(stream);
        client.send(&hello(seed, 5000 + seed as u16, 0).encode(false).unwrap()).await;
        let answer = client.next().await.unwrap();
        assert_eq!(answer.opcode(), client::HELLOANSWER);
        client.engine_hello = Some(Hello::decode(&answer).unwrap());
        client
    }

    async fn send(&mut self, packet: &Packet) {
        write_packet(&mut self.stream, packet).await.unwrap();
    }

    async fn ask(&mut self, file: &[u8; 16]) -> Packet {
        self.send(&client_messages::for_file(client::STARTUPLOADREQ, file)).await;
        self.next().await.unwrap()
    }

    async fn request_bytes(&mut self, file: &[u8; 16], start: u64, end: u64) {
        let req = RequestParts {
            file: *file,
            ranges: [Range { start, end }, Range { start: 0, end: 0 }, Range { start: 0, end: 0 }],
        };
        self.send(&req.encode(false).unwrap()).await;
    }

    async fn next(&mut self) -> Option<Packet> {
        loop {
            let pkt = match tokio::time::timeout(WAIT, read_packet(&mut self.stream, &|_, _| Some(1 << 20))).await {
                Ok(Ok(Some(p))) => p,
                Ok(Ok(None) | Err(_)) | Err(_) => return None,
            };
            if !is_identification(&pkt) {
                return Some(pkt);
            }
        }
    }

    async fn poll(&mut self, wait: Duration) -> Option<Packet> {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            if left.is_zero() {
                return None;
            }
            match tokio::time::timeout(left, read_packet(&mut self.stream, &|_, _| Some(1 << 20))).await {
                Ok(Ok(Some(pkt))) if !is_identification(&pkt) => return Some(pkt),
                Ok(Ok(Some(_))) => {}
                Ok(Ok(None) | Err(_)) | Err(_) => return None,
            }
        }
    }

    async fn prove(&mut self) {
        let own = Arc::new(Identity::of(engine_rsa::generate(engine_rsa::NETWORK_KEY_BITS).unwrap()));
        let mut handshake = IdentityHandshake::new(
            own,
            self.engine_hello.as_ref().unwrap(),
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            None,
            None,
            Some(IpAddr::V4(Ipv4Addr::LOCALHOST)),
        );
        let mut rng = rand::rng();
        let mut signed = false;
        for pkt in handshake.start(&mut rng) {
            signed |= pkt.opcode() == extended::SIGNATURE;
            self.send(&pkt).await;
        }
        while !signed {
            let pkt = tokio::time::timeout(WAIT, read_packet(&mut self.stream, &|_, _| Some(1 << 20)))
                .await
                .unwrap()
                .unwrap()
                .unwrap();
            if is_identification(&pkt)
                && let Some(handled) = handshake.received(&pkt).unwrap()
            {
                for out in handled.send {
                    signed |= out.opcode() == extended::SIGNATURE;
                    self.send(&out).await;
                }
            }
        }
    }

    async fn send_fake_proof(&mut self) {
        let key = Identity::of(engine_rsa::generate(engine_rsa::NETWORK_KEY_BITS).unwrap()).public_key().to_vec();
        let mut key_payload = vec![u8::try_from(key.len()).unwrap()];
        key_payload.extend_from_slice(&key);
        let mut sig_payload = vec![48u8];
        sig_payload.extend_from_slice(&[0u8; 48]);
        self.send(&Packet::of(protocol::EMULE, extended::PUBLICKEY, key_payload)).await;
        self.send(&Packet::of(protocol::EMULE, extended::SIGNATURE, sig_payload)).await;
    }
}

fn user_hash_of(seed: u64) -> [u8; 16] {
    let mut hash = [0u8; 16];
    StdRng::seed_from_u64(seed).fill(&mut hash[..]);
    hash
}

fn hello(seed: u64, listen_port: u16, misc1: u32) -> Hello {
    let nick = format!("client {seed}");
    let ours = Ours { nick: &nick, udp_port: 0, kad_port: 0, misc_options1: misc1, misc_options2: 0 };
    Hello::of(user_hash_of(seed), 0, listen_port, &ours, 0, 0)
}

fn ask_shared_files() -> Packet {
    Packet::empty(protocol::EDONKEY, client::ASKSHAREDFILES)
}

fn ask_shared_directories() -> Packet {
    Packet::empty(protocol::EDONKEY, client::ASKSHAREDDIRS)
}

fn ask_files_of(directory: &str) -> Packet {
    let mut w = PacketWriter::new();
    w.string16(directory, StringEncoding::Utf8).unwrap();
    Packet::of(protocol::EDONKEY, client::ASKSHAREDFILESDIR, w.into_byte_array())
}

fn escaped(path: &std::path::Path) -> String {
    path.to_string_lossy().replace('\\', "\\\\")
}
