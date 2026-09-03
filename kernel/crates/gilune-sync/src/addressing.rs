//! Which addresses a peer can be reached at, and which to try first.
//!
//! **One classifier, both sides.** This existed twice and the two copies
//! disagreed: `service::is_routable` decided what this machine would
//! *publish* and `discovery::dialability` decided what it would *dial*,
//! and only the second of them knew that a loopback address advertised
//! beside a real one has to sort last. A rule about what a LAN peer can
//! use is one rule; splitting it meant every later refinement had to be
//! made twice and was made once.
//!
//! The ordering, best first:
//!
//! 1. **Same segment** — routable, and sharing a `/24` with one of this
//!    machine's own routable addresses. A peer that answers on
//!    `192.168.1.20` while this machine sits on `192.168.1.77` is on the
//!    LAN in front of us; a `10.x` address on the same peer is probably
//!    the far end of somebody's VPN.
//! 2. **Routable** — a real address, on a network this machine is not
//!    obviously part of. Still worth dialling; it is simply a worse guess.
//! 3. **Link-local** — `169.254.x.x` is what an interface has when DHCP
//!    never answered, and it routes to almost nothing.
//! 4. **Loopback** — reaches this machine and no other. Fatal if it is
//!    recorded for a peer, which is exactly the defect the live run found.
//! 5. **Unusable** — the unspecified address, multicast, broadcast, and
//!    the documentation ranges. Not addresses of a device at all, so they
//!    are *dropped* rather than sorted last: `ranked` never returns one,
//!    which is what makes "a bogus `0.0.0.0` in a response is never
//!    dialled" a property of the type rather than a check somebody has to
//!    remember.
//!
//! IPv4 only, for the reason `discovery::best_address` gives: a
//! link-local IPv6 address needs the scope id of the interface it was
//! heard on and `host:port` cannot carry one.

use std::net::Ipv4Addr;

/// How reachable an address is, best first. The ordering **is** the
/// decision — `Ord` is derived from the declaration order, and both the
/// publishing and the dialling side sort by it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Reach {
    /// Routable, and on the same `/24` as one of this machine's own.
    SameSegment,
    /// Routable, somewhere else.
    Routable,
    /// What an interface has when DHCP did not answer.
    LinkLocal,
    /// This machine talking to itself.
    Loopback,
    /// Not a device's address at all.
    Unusable,
}

/// Not an address any device answers on: the unspecified address,
/// multicast, broadcast, and the ranges reserved for documentation.
pub fn is_unusable(ip: &Ipv4Addr) -> bool {
    ip.is_unspecified() || ip.is_multicast() || ip.is_broadcast() || ip.is_documentation()
}

/// Whether a peer on a LAN could dial this. Never the unspecified
/// address, never the loopback, never what an interface has when DHCP did
/// not answer.
pub fn is_routable(ip: &Ipv4Addr) -> bool {
    !is_unusable(ip) && !ip.is_loopback() && !ip.is_link_local()
}

/// Whether two addresses share a `/24`. A coarse stand-in for "the same
/// network segment": this crate never sees a netmask — `if_addrs` reports
/// one but a peer's is not knowable from an advertisement — and on the
/// home and small-office networks Gilune is for, the third octet is what
/// changes between the LAN, the VPN and the hypervisor's bridge.
pub fn same_segment(a: &Ipv4Addr, b: &Ipv4Addr) -> bool {
    a.octets()[..3] == b.octets()[..3]
}

/// Where `ip` sits in the ordering, given what this machine's own
/// addresses are. `local` may be empty — then nothing can be same-segment
/// and the ranking degrades to routable-first, which is still right.
pub fn classify(ip: &Ipv4Addr, local: &[Ipv4Addr]) -> Reach {
    if is_unusable(ip) {
        Reach::Unusable
    } else if ip.is_loopback() {
        Reach::Loopback
    } else if ip.is_link_local() {
        Reach::LinkLocal
    } else if local
        .iter()
        .any(|mine| is_routable(mine) && same_segment(mine, ip))
    {
        Reach::SameSegment
    } else {
        Reach::Routable
    }
}

/// Every candidate worth dialling, best first, without duplicates.
///
/// Unusable addresses are dropped rather than ordered last. An address
/// that names no device is not a worse option than a good one; it is not
/// an option, and leaving it in the list would mean every caller had to
/// remember to skip it.
pub fn ranked(candidates: &[Ipv4Addr], local: &[Ipv4Addr]) -> Vec<Ipv4Addr> {
    let mut kept: Vec<Ipv4Addr> = candidates
        .iter()
        .copied()
        .filter(|ip| !is_unusable(ip))
        .collect();
    kept.sort_by_key(|ip| (classify(ip, local), *ip));
    kept.dedup();
    kept
}

/// The IPv4 addresses this machine can actually be dialled at, best
/// first.
///
/// Read from the interface table (`getifaddrs` / `GetAdaptersAddresses`)
/// and **not** by opening a socket. The std-only way to answer this is to
/// bind a UDP socket and `connect` it at a private-range address to learn
/// the preferred outbound interface — and that is a bind, in a program
/// whose § 2 discipline is that it binds when the user switches sync on
/// and at no other moment. A socket opened behind a status query would be
/// exactly the surprise the firewall UX was designed to avoid.
///
/// The honest limitation, which is now answered rather than merely
/// stated: a machine with Hyper-V, WSL or a VPN adapter has several
/// routable addresses and **this cannot know which one a phone shares a
/// network with**. So nothing here picks one. Every candidate is
/// published, the QR carries them all, and the joiner tries them in turn
/// — the device that answers decides, which is the only thing that can.
pub fn local_addresses() -> Vec<Ipv4Addr> {
    let Ok(interfaces) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    let mut addresses: Vec<Ipv4Addr> = interfaces
        .into_iter()
        .filter(|i| i.is_oper_up())
        .filter_map(|i| match i.ip() {
            std::net::IpAddr::V4(ip) => Some(ip),
            std::net::IpAddr::V6(_) => None,
        })
        .filter(is_routable)
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(text: &str) -> Ipv4Addr {
        text.parse().expect("an address")
    }

    #[test]
    fn the_ordering_puts_the_loopback_last_of_the_dialable_and_drops_the_rest() {
        let local = [ip("192.168.1.77")];
        assert_eq!(classify(&ip("192.168.1.20"), &local), Reach::SameSegment);
        assert_eq!(classify(&ip("10.8.0.2"), &local), Reach::Routable);
        assert_eq!(classify(&ip("169.254.3.4"), &local), Reach::LinkLocal);
        assert_eq!(classify(&ip("127.0.0.1"), &local), Reach::Loopback);
        for bogus in ["0.0.0.0", "224.0.0.251", "255.255.255.255", "192.0.2.1"] {
            assert_eq!(classify(&ip(bogus), &local), Reach::Unusable, "{bogus}");
        }
        assert!(Reach::SameSegment < Reach::Routable);
        assert!(Reach::Routable < Reach::LinkLocal);
        assert!(Reach::LinkLocal < Reach::Loopback);
    }

    /// **Second-order test for the shared classifier.** A resolved peer
    /// offers a VPN address and a LAN one; this machine is on the LAN. The
    /// numeric sort the two old copies did between them would have dialled
    /// `10.x` first — the VPN — and on a machine with no route to it that
    /// is a pairing that hangs and then fails for no visible reason.
    #[test]
    fn a_peer_on_this_machines_own_segment_is_dialled_before_one_that_is_not() {
        let local = [ip("192.168.1.77")];
        let offered = [ip("10.8.0.2"), ip("192.168.1.20")];
        assert_eq!(
            ranked(&offered, &local),
            vec![ip("192.168.1.20"), ip("10.8.0.2")],
            "the address on this machine's own segment is the one a LAN peer is at"
        );
        // And with nothing local to compare against, the ordering still
        // holds — it simply cannot prefer either routable address.
        assert_eq!(ranked(&offered, &[]), vec![ip("10.8.0.2"), ip("192.168.1.20")]);
    }

    /// The other half of the same second-order test: a response carrying
    /// an address that names no device must never be dialled, and the way
    /// to guarantee that is for it never to reach a caller.
    #[test]
    fn a_bogus_address_in_a_response_is_never_returned_to_be_dialled() {
        let local = [ip("192.168.1.77")];
        let offered = [
            ip("0.0.0.0"),
            ip("255.255.255.255"),
            ip("224.0.0.251"),
            ip("192.168.1.20"),
        ];
        assert_eq!(ranked(&offered, &local), vec![ip("192.168.1.20")]);
        assert!(
            ranked(&[ip("0.0.0.0")], &local).is_empty(),
            "a peer that offered nothing dialable offered nothing"
        );
    }

    #[test]
    fn ranking_is_stable_and_says_each_address_once() {
        let local = [ip("192.168.1.77")];
        let offered = [
            ip("127.0.0.1"),
            ip("192.168.1.20"),
            ip("169.254.3.4"),
            ip("192.168.1.20"),
            ip("10.8.0.2"),
        ];
        assert_eq!(
            ranked(&offered, &local),
            vec![
                ip("192.168.1.20"),
                ip("10.8.0.2"),
                ip("169.254.3.4"),
                ip("127.0.0.1"),
            ]
        );
    }

    #[test]
    fn what_this_machine_offers_is_only_ever_dialable() {
        for address in local_addresses() {
            assert!(is_routable(&address), "{address} is not one a peer could dial");
            assert_eq!(classify(&address, &[address]), Reach::SameSegment);
        }
    }
}
