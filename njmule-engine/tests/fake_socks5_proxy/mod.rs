// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A SOCKS5 proxy on the loopback that grants UDP ASSOCIATE and relays the datagrams both ways, for tests, as the Java
//! engine's tests have it. It may ask for a user name and password, or refuse the command; it notes what it saw.

#![allow(dead_code, clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::io::{Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddr, SocketAddrV4, TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, Mutex};
use std::thread;

use njmule_engine::config::proxy_settings::{ProxySettings, ProxyType};
use njmule_engine::net::socks5_udp;

pub struct FakeSocks5Proxy {
    port: u16,
    seen: Arc<Mutex<Vec<String>>>,
    controls: Arc<Mutex<Vec<TcpStream>>>,
}

impl FakeSocks5Proxy {
    /// A proxy that relays UDP for anyone.
    pub fn start() -> Self {
        Self::new("", "", true)
    }

    /// A proxy that relays UDP for `user` and `password` only.
    pub fn with_login(user: &str, password: &str) -> Self {
        Self::new(user, password, true)
    }

    /// A proxy that does not support UDP ASSOCIATE.
    pub fn refusing_udp() -> Self {
        Self::new("", "", false)
    }

    fn new(user: &str, password: &str, grants: bool) -> Self {
        let server = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = server.local_addr().unwrap().port();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let controls = Arc::new(Mutex::new(Vec::new()));
        let (user, password) = (user.to_owned(), password.to_owned());
        let (seen_by, controls_by) = (Arc::clone(&seen), Arc::clone(&controls));
        thread::spawn(move || {
            for control in server.incoming() {
                let Ok(control) = control else { return };
                controls_by.lock().unwrap().push(control.try_clone().unwrap());
                let (seen, user, password) = (Arc::clone(&seen_by), user.clone(), password.clone());
                thread::spawn(move || {
                    serve(control, &seen, &user, &password, grants);
                    seen.lock().unwrap().push("ended".to_owned());
                });
            }
        });
        Self { port, seen, controls }
    }

    /// The settings of this proxy, with `user` and `password`.
    pub fn settings_with(&self, user: &str, password: &str) -> ProxySettings {
        ProxySettings {
            kind: ProxyType::Socks,
            host: "127.0.0.1".to_owned(),
            port: self.port,
            user: user.to_owned(),
            password: password.to_owned(),
        }
    }

    pub fn settings(&self) -> ProxySettings {
        self.settings_with("", "")
    }

    /// What it saw: "login user:password", "associate", "relay to 1.2.3.4:5" and "ended".
    pub fn seen(&self) -> Vec<String> {
        self.seen.lock().unwrap().clone()
    }

    /// Ends every association, as a proxy that restarts.
    pub fn end_associations(&self) {
        for control in self.controls.lock().unwrap().drain(..) {
            let _ = control.shutdown(Shutdown::Both);
        }
    }
}

impl Drop for FakeSocks5Proxy {
    fn drop(&mut self) {
        self.end_associations();
    }
}

fn byte(stream: &mut TcpStream) -> Option<u8> {
    let mut one = [0];
    stream.read_exact(&mut one).ok().map(|()| one[0])
}

fn bytes(stream: &mut TcpStream, count: usize) -> Option<Vec<u8>> {
    let mut read = vec![0; count];
    stream.read_exact(&mut read).ok().map(|()| read)
}

fn serve(
    mut control: TcpStream,
    seen: &Arc<Mutex<Vec<String>>>,
    user: &str,
    password: &str,
    grants: bool,
) -> Option<()> {
    byte(&mut control)?;
    let count = usize::from(byte(&mut control)?);
    let methods = bytes(&mut control, count)?;
    let wanted = if user.is_empty() { 0 } else { 2 };
    let offered = methods.contains(&wanted);
    control.write_all(&[5, if offered { wanted } else { 0xFF }]).ok()?;
    if !offered || (wanted == 2 && !logged_in(&mut control, seen, user, password)?) {
        return None;
    }
    bytes(&mut control, 10)?;
    seen.lock().unwrap().push("associate".to_owned());
    if !grants {
        control.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).ok()?;
        return None;
    }
    let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).ok()?;
    let [high, low] = relay.local_addr().ok()?.port().to_be_bytes();
    control.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, high, low]).ok()?;
    let client_ip = control.peer_addr().ok()?.ip();
    let noted = Arc::clone(seen);
    thread::spawn(move || relay_datagrams(&relay, client_ip, &noted));
    // The association lasts as long as this connection.
    let mut ignored = [0; 64];
    while matches!(control.read(&mut ignored), Ok(read) if read > 0) {}
    Some(())
}

fn logged_in(control: &mut TcpStream, seen: &Mutex<Vec<String>>, user: &str, password: &str) -> Option<bool> {
    byte(control)?;
    let length = usize::from(byte(control)?);
    let name = String::from_utf8(bytes(control, length)?).ok()?;
    let length = usize::from(byte(control)?);
    let secret = String::from_utf8(bytes(control, length)?).ok()?;
    seen.lock().unwrap().push(format!("login {name}:{secret}"));
    let right = name == user && secret == password;
    control.write_all(&[1, u8::from(!right)]).ok()?;
    Some(right)
}

// The client's datagrams carry a header naming their destination; the others go to the client behind one that names
// their sender.
fn relay_datagrams(relay: &UdpSocket, client_ip: std::net::IpAddr, seen: &Mutex<Vec<String>>) {
    let mut buffer = vec![0; 64 * 1024];
    let mut client: Option<SocketAddr> = None;
    while let Ok((length, from)) = relay.recv_from(&mut buffer) {
        if (client.is_none() && from.ip() == client_ip) || client == Some(from) {
            client = Some(from);
            let to = SocketAddr::from((
                [buffer[4], buffer[5], buffer[6], buffer[7]],
                u16::from_be_bytes([buffer[8], buffer[9]]),
            ));
            seen.lock().unwrap().push(format!("relay to {to}"));
            let _ = relay.send_to(&buffer[10..length], to);
        } else if let (Some(client), SocketAddr::V4(from)) = (client, from) {
            let wrapped = socks5_udp::wrap(SocketAddrV4::new(*from.ip(), from.port()), &buffer[..length]);
            let _ = relay.send_to(&wrapped, client);
        }
    }
}
