// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The chats of the network: the messages that come on the clients' connections, and ours, sent over them or over a
//! connection of our own, opened with a hello.

use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use rand::SeedableRng;
use rand::rngs::StdRng;

use super::{Network, PeerEvent};
use crate::api::engine_event::EngineEvent;
use crate::net::tcp_connector;
use crate::peer::peer_link::{self, LinkEvent, LinkId};
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::packet::{Packet, frame_limits};
use crate::social::chat_filter::ChatFilter;
use crate::social::messaging::{Action, ChatEvent, Judge};

/// How long our own connection for a chat waits for the client.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// A connection of our own for a chat ends after so long without a packet.
const CHAT_IDLE: Duration = Duration::from_secs(10 * 60);

impl Network {
    /// Sends `text` to the client listening at `to`, connecting first if needed.
    pub(super) fn send_message(&mut self, to: SocketAddr, text: &str) {
        self.messaging.send(to, text);
        self.after_chats();
    }

    /// A packet on a client's connection: true when it was a chat packet, which the conversation then answers on it.
    pub(super) fn chat_incoming(&mut self, link: LinkId, remote: SocketAddr, peer: &Hello, packet: &Packet) -> bool {
        let proven = self.trusted(link);
        let filter = self.chat_filter();
        let friends = &self.friends;
        let judge = Judge { filter: &filter, friends: &|user_hash| friends.contains(user_hash) };
        let chat = self.messaging.incoming(remote, peer, proven, link, packet, &judge);
        if chat {
            self.after_chats();
        }
        chat
    }

    /// A client's connection closed: a conversation over it waits for another.
    pub(super) fn chat_link_closed(&mut self, link: LinkId) {
        self.messaging.closed(link);
        self.after_chats();
    }

    /// Whether `link` is a connection of our own for a chat.
    pub(super) fn is_chat_link(&self, link: LinkId) -> bool {
        self.chats.contains_key(&link)
    }

    /// What happened on a connection of our own for a chat.
    pub(super) fn chat_event(&mut self, event: LinkEvent) {
        match event {
            LinkEvent::Packet(link, packet) => {
                let filter = self.chat_filter();
                let friends = &self.friends;
                let judge = Judge { filter: &filter, friends: &|user_hash| friends.contains(user_hash) };
                self.messaging.own_packet(link, &packet, &judge);
            }
            LinkEvent::Closed(link, _) => {
                self.chats.remove(&link);
                self.messaging.closed(link);
            }
        }
        self.after_chats();
    }

    /// Our own connection for a chat with the client listening at `to` is up, or could not be made.
    pub(super) fn chat_connected(&mut self, to: SocketAddr, connection: io::Result<tcp_connector::Connection>) {
        match connection {
            Ok(connection) => {
                let id = self.next_link;
                self.next_link = self.next_link.saturating_add(1);
                let connection = connection.into_tcp_connection(frame_limits::client_handshake);
                let handle = peer_link::start(id, connection, self.link_events.clone());
                if !self.messaging.connected(to, id) {
                    handle.close();
                    return;
                }
                handle.set_timeout(CHAT_IDLE);
                match self.shared.our_hello().to_peer_at(&to.ip()).encode(false) {
                    Ok(hello) => {
                        handle.send(hello);
                        self.chats.insert(id, handle);
                    }
                    Err(e) => {
                        handle.close();
                        self.messaging.closed(id);
                        tracing::warn!("Cannot greet {to}: {e}");
                    }
                }
            }
            Err(e) => self.messaging.unreachable(to, &e.to_string()),
        }
        self.after_chats();
    }

    /// Ends our own connections for chats.
    pub(super) fn close_chats(&mut self) {
        for handle in self.chats.values() {
            handle.close();
        }
    }

    // What the conversations ask for: connections opened and packets sent; and what they brought, for the front ends.
    fn after_chats(&mut self) {
        for action in self.messaging.take_actions() {
            match action {
                Action::Connect(to) => {
                    let events = self.peer_events.clone();
                    let routes = self.shared.routes.clone();
                    tokio::spawn(async move {
                        let mut random = StdRng::from_rng(&mut rand::rng());
                        let connection =
                            tcp_connector::connect_to_client(to, None, CONNECT_TIMEOUT, &routes, &mut random).await;
                        let _ = events.send(PeerEvent::ChatConnected(to, Box::new(connection)));
                    });
                }
                Action::Send(link, packet) => {
                    let handle = self
                        .chats
                        .get(&link)
                        .cloned()
                        .or_else(|| self.peers.get(&link).map(|peer| peer.handle.clone()));
                    match handle {
                        Some(handle) => {
                            handle.send(packet);
                        }
                        None => tracing::debug!("The connection of a chat is gone"),
                    }
                }
            }
        }
        for event in self.messaging.take_events() {
            self.publish(match event {
                ChatEvent::Received(from, text) => EngineEvent::MessageReceived {
                    address: from.address.to_string(),
                    user_hash: from.user_hash,
                    nick: from.nick,
                    text,
                },
                ChatEvent::Captcha(from, image) => EngineEvent::CaptchaRequested {
                    address: from.address.to_string(),
                    user_hash: from.user_hash,
                    image,
                },
                ChatEvent::CaptchaResult(from, solved) => {
                    EngineEvent::CaptchaAnswered { address: from.address.to_string(), solved }
                }
                ChatEvent::Failed(to, reason) => EngineEvent::MessageFailed { address: to.to_string(), reason },
            });
        }
    }

    // Which messages show, as the preferences say now.
    fn chat_filter(&self) -> ChatFilter {
        let preferences = self.preferences();
        ChatFilter::new(
            preferences.messages_from_friends_only,
            &ChatFilter::words_of(&preferences.message_filter),
            preferences.advanced_spam_filter,
        )
    }
}
