// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Chats with other clients, one conversation for each client address: its IP and the port it listens on. Messages go
//! over the connection the client opened to us when there is one, else over one of our own, opened with a hello. The
//! network opens, reads and closes the connections; this decides what goes where, and what the conversations bring.

use std::collections::HashMap;
use std::net::SocketAddr;

use indexmap::IndexMap;

use crate::peer::peer_link::LinkId;
use crate::protocol::ed2k::chat_messages;
use crate::protocol::ed2k::hello::{self, Hello};
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::social::chat_filter::{self, ChatFilter};

/// Who we chat with.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Correspondent {
    pub address: SocketAddr,
    pub user_hash: [u8; 16],
    pub nick: Option<String>,
}

/// What the conversations bring.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ChatEvent {
    Received(Correspondent, String),
    /// The client wants a CAPTCHA solved before it shows our messages.
    Captcha(Correspondent, Vec<u8>),
    CaptchaResult(Correspondent, bool),
    /// Messages to the client listening at the address could not be sent.
    Failed(SocketAddr, String),
}

/// What the network is to do for the conversations.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Connect to the client listening at the address, for [`Messaging::connected`] or [`Messaging::unreachable`].
    Connect(SocketAddr),
    /// Send the packet over the connection.
    Send(LinkId, Packet),
}

/// What decides which messages show, and who is a friend, as the preferences and the friends say now.
pub struct Judge<'a> {
    pub filter: &'a ChatFilter,
    pub friends: &'a dyn Fn(&[u8; 16]) -> bool,
}

// What the filter knows of so many of the latest clients.
const MAX_FILTER_STATES: usize = 1_000;

#[derive(Debug, Default)]
struct Conversation {
    peer: Option<Hello>,
    // The connection the messages go over, and whether it is still open.
    link: Option<LinkId>,
    open: bool,
    // Our own connection, while it is open.
    own: Option<LinkId>,
    // Whether the conversation goes over our own connection rather than one the client opened.
    over_own: bool,
    connecting: bool,
    // A CAPTCHA is asked for in answer to our messages: none is taken before we write.
    wrote: bool,
    pending: Vec<String>,
}

impl Conversation {
    fn is_open(&self) -> bool {
        self.peer.is_some() && self.open
    }
}

#[derive(Debug, Default)]
pub struct Messaging {
    conversations: HashMap<SocketAddr, Conversation>,
    // What the filter knows of the latest clients, by user hash, the least recent first: a client coming back on
    // another port keeps it.
    filter_states: IndexMap<[u8; 16], chat_filter::State>,
    actions: Vec<Action>,
    events: Vec<ChatEvent>,
}

impl Messaging {
    /// Sends `text` to the client listening at `to`, connecting first if needed; a blank text sends nothing.
    pub fn send(&mut self, to: SocketAddr, text: &str) {
        if text.trim().is_empty() {
            return;
        }
        self.prune();
        let conversation = self.conversations.entry(to).or_default();
        conversation.wrote = true;
        if conversation.is_open()
            && let (Some(peer), Some(link)) = (conversation.peer.clone(), conversation.link)
        {
            self.filter_state(&peer.user_hash).wrote();
            self.message(link, &peer, text);
            return;
        }
        conversation.pending.push(text.to_owned());
        if !conversation.connecting {
            conversation.connecting = true;
            self.actions.push(Action::Connect(to));
        }
    }

    /// Our own connection to `to` is up, on `link`: true when the conversation still wants it, and the network then
    /// sends our hello; else it closes it.
    pub fn connected(&mut self, to: SocketAddr, link: LinkId) -> bool {
        match self.conversations.get_mut(&to) {
            Some(conversation) if conversation.connecting && conversation.own.is_none() => {
                conversation.own = Some(link);
                true
            }
            _ => false,
        }
    }

    /// Our own connection to `to` could not be made.
    pub fn unreachable(&mut self, to: SocketAddr, reason: &str) {
        let Some(conversation) = self.conversations.get_mut(&to) else {
            return;
        };
        conversation.connecting = false;
        if !conversation.pending.is_empty() {
            conversation.pending.clear();
            self.events.push(ChatEvent::Failed(to, reason.to_owned()));
        }
        if conversation.peer.is_none() {
            self.conversations.remove(&to);
        }
    }

    /// A packet on our own connection `link`: the client's hello answer first, then the chat. Whoever answers at the
    /// address we chose to write to is who we meant.
    pub fn own_packet(&mut self, link: LinkId, packet: &Packet, judge: &Judge<'_>) {
        let Some((to, conversation)) =
            self.conversations.iter_mut().find(|(_, conversation)| conversation.own == Some(link))
        else {
            return;
        };
        let to = *to;
        if packet.protocol() == protocol::EDONKEY && packet.opcode() == client::HELLOANSWER {
            let Ok(peer) = Hello::decode(packet) else {
                tracing::debug!("Bad hello answer from {to}");
                return;
            };
            conversation.peer = Some(peer);
            conversation.link = Some(link);
            conversation.open = true;
            conversation.over_own = true;
            self.flush(to);
        } else if let Some(peer) = conversation.peer.clone()
            && is_chat(packet)
        {
            self.handle(to, &peer, true, packet, judge);
        }
    }

    /// A packet on a connection the client opened for something else, an upload: true when it was a chat packet,
    /// which the conversation then answers on that connection. `proven` when the client's identity is.
    pub fn incoming(
        &mut self,
        remote: SocketAddr,
        peer: &Hello,
        proven: bool,
        link: LinkId,
        packet: &Packet,
        judge: &Judge<'_>,
    ) -> bool {
        if !is_chat(packet) {
            return false;
        }
        let address = SocketAddr::new(remote.ip(), peer.tcp_port);
        self.prune();
        let conversation = self.conversations.entry(address).or_default();
        if !conversation.is_open() || conversation.own.is_none() {
            conversation.peer = Some(peer.clone());
            conversation.link = Some(link);
            conversation.open = true;
            conversation.over_own = false;
            self.flush(address);
        }
        self.handle(address, peer, proven, packet, judge);
        true
    }

    /// The connection `link` closed: a conversation over it waits for another, and one over our own connection that a
    /// connection of the client did not take over ends with it.
    pub fn closed(&mut self, link: LinkId) {
        let mut lost = Vec::new();
        for (to, conversation) in &mut self.conversations {
            if conversation.link == Some(link) {
                conversation.open = false;
            }
            if conversation.own == Some(link) {
                conversation.own = None;
                if conversation.over_own {
                    conversation.peer = None;
                    conversation.over_own = false;
                }
                lost.push(*to);
            }
        }
        for to in lost {
            self.unreachable(to, "the connection closed");
        }
    }

    /// The clients we chat with now.
    pub fn conversations(&mut self) -> usize {
        self.prune();
        self.conversations.len()
    }

    /// What the network is to do, since asked last.
    pub fn take_actions(&mut self) -> Vec<Action> {
        std::mem::take(&mut self.actions)
    }

    /// What the conversations brought, since asked last.
    pub fn take_events(&mut self) -> Vec<ChatEvent> {
        std::mem::take(&mut self.events)
    }

    // Conversations whose connection closed with nothing left to send go: they hold on to the connection.
    fn prune(&mut self) {
        self.conversations.retain(|_, conversation| {
            conversation.connecting
                || conversation.own.is_some()
                || !conversation.pending.is_empty()
                || conversation.is_open()
        });
    }

    // The state of a client, moved last as the most recent; the least recent goes beyond the limit.
    fn filter_state(&mut self, user_hash: &[u8; 16]) -> &mut chat_filter::State {
        match self.filter_states.get_index_of(user_hash) {
            Some(at) => self.filter_states.move_index(at, self.filter_states.len().saturating_sub(1)),
            None if self.filter_states.len() >= MAX_FILTER_STATES => {
                self.filter_states.shift_remove_index(0);
            }
            None => {}
        }
        self.filter_states.entry(*user_hash).or_default()
    }

    fn flush(&mut self, to: SocketAddr) {
        let Some(conversation) = self.conversations.get_mut(&to) else {
            return;
        };
        let pending = std::mem::take(&mut conversation.pending);
        conversation.connecting = false;
        let (Some(peer), Some(link)) = (conversation.peer.clone(), conversation.link) else {
            return;
        };
        if !pending.is_empty() {
            self.filter_state(&peer.user_hash).wrote();
        }
        for text in pending {
            self.message(link, &peer, &text);
        }
        tracing::debug!("Chatting with {to}");
    }

    fn message(&mut self, link: LinkId, peer: &Hello, text: &str) {
        match chat_messages::message(text, peer.misc_options1() & hello::MISC1_UNICODE != 0) {
            Ok(packet) => self.actions.push(Action::Send(link, packet)),
            Err(e) => tracing::debug!("Cannot write a message: {e}"),
        }
    }

    // A friend counts as one with its identity proven: anyone can send its user hash.
    fn handle(&mut self, address: SocketAddr, peer: &Hello, proven: bool, packet: &Packet, judge: &Judge<'_>) {
        let from = Correspondent { address, user_hash: peer.user_hash, nick: peer.nick().map(str::to_owned) };
        let event = if packet.protocol() == protocol::EDONKEY {
            let Ok(text) = chat_messages::text(packet) else {
                tracing::debug!("Bad message from {address}");
                return;
            };
            let friend = proven && (judge.friends)(&peer.user_hash);
            if !judge.filter.shows(self.filter_state(&peer.user_hash), friend, &text) {
                tracing::debug!("Filtered a message from {address}");
                return;
            }
            ChatEvent::Received(from, text)
        } else if packet.opcode() == extended::CHATCAPTCHAREQ {
            if !self.conversations.get(&address).is_some_and(|conversation| conversation.wrote) {
                tracing::debug!("Ignoring a CAPTCHA {address} asked for unasked");
                return;
            }
            match chat_messages::captcha(packet) {
                Ok(image) => ChatEvent::Captcha(from, image),
                Err(e) => {
                    tracing::debug!("Bad CAPTCHA from {address}: {e}");
                    return;
                }
            }
        } else {
            match chat_messages::is_solved(packet) {
                Ok(solved) => ChatEvent::CaptchaResult(from, solved),
                Err(e) => {
                    tracing::debug!("Bad CAPTCHA result from {address}: {e}");
                    return;
                }
            }
        };
        self.events.push(event);
    }
}

/// Whether `packet` belongs to a chat: a message, or a CAPTCHA and its result.
pub fn is_chat(packet: &Packet) -> bool {
    (packet.protocol() == protocol::EDONKEY && packet.opcode() == client::MESSAGE)
        || (packet.protocol() == protocol::EMULE
            && matches!(packet.opcode(), extended::CHATCAPTCHAREQ | extended::CHATCAPTCHARES))
}
