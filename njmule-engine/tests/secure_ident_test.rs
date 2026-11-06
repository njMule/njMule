// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The kinds of address a secure identification signature binds, by the codes the network gives them.

use njmule_engine::security::secure_ident::IpKind;

#[test]
fn should_give_every_kind_of_address_its_code_and_back() {
    for (kind, code) in [(IpKind::RemoteClient, 10), (IpKind::LocalClient, 20), (IpKind::None, 30)] {
        assert_eq!(kind.code(), code);
        assert_eq!(IpKind::of(code), Some(kind));
    }
    assert_eq!(IpKind::of(0), None);
}
