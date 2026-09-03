//! One Gilune device, as its own operating-system process.
//!
//! ADR 0019 § 11 scopes M5b to "two processes, one machine, no
//! discovery", and the gate B suite proves the semantics with two kernel
//! handles and a listener thread inside one test binary. This example is
//! the other half of that evidence: two genuinely separate processes,
//! each with its own vault, its own device-local identity and its own
//! address space, pairing over a socket and exchanging operations. There
//! is no shared memory for them to cheat through.
//!
//! Not a test — it is driven from the outside, and it prints facts a
//! human or a script can read.
//!
//!   pair_device listen <vault> <identity-dir> <name>
//!       enrol, bind, open a pairing window, print `CODE <code>` and
//!       `ADDRESS <host:port>`, then serve until told to stop.
//!
//!   pair_device join <vault> <identity-dir> <name> <address> <code>
//!       enrol, bind, pair with the code, sync, print what happened.

use gilune_kernel::limits::Limits;
use gilune_kernel::store::Store;
use gilune_sync::service::SyncService;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!("usage: pair_device <listen|join|capture> <vault> <identity-dir> <name> [..]");
        std::process::exit(2);
    }
    let mode = args[1].as_str();
    let vault = args[2].clone();
    let identity_dir = args[3].clone();
    let name = args[4].clone();

    let store = Store::open(&vault).expect("open the vault");
    store
        .register_plugin(&gilune_kernel::vault::plugin_descriptor())
        .expect("declare the projection");
    let service = SyncService::configure(&store, &identity_dir, &name, None, Limits::DEFAULT)
        .expect("configure sync");

    println!("PID {}", std::process::id());
    println!("DEVICE {}", service.device_id());
    println!("IDENTITY {}", service.identity_public_hex());
    println!("ENROLMENT {}", service.enrolment().as_str());
    println!("FINGERPRINT {}", service.facts().fingerprint);

    match mode {
        "listen" => {
            let address = service
                .set_enabled(true)
                .expect("bind")
                .expect("an address");
            let offer = service.start_pairing().expect("open a pairing window");
            println!("ADDRESS {address}");
            // Every address this machine answers on, best first. On a
            // laptop with a hypervisor or a VPN the first is a guess and
            // the others are the reason a real two-machine run works: the
            // QR carries them all, and a person typing one by hand needs
            // to be able to see them.
            println!("ADDRESSES {}", offer.addresses.join(" "));
            println!("CODE {}", offer.code);
            println!("READY");
            // Serve until stdin closes — the driver keeps it open for as
            // long as it needs this device alive.
            let mut line = String::new();
            while std::io::stdin().read_line(&mut line).unwrap_or(0) > 0 {
                // A Windows driver's first written line arrives with a
                // UTF-8 BOM glued to it; strip it rather than reject the
                // command as unknown.
                let command = line.trim().trim_start_matches('\u{feff}').to_string();
                line.clear();
                match command.as_str() {
                    "peers" => report_peers(&service),
                    "sync" => report_sync(&service, &store),
                    "status" => report_status(&service, &store),
                    "stop" => break,
                    other if other.starts_with("capture ") => {
                        let text = other.trim_start_matches("capture ").to_string();
                        let receipt =
                            store.capture_bytes("txt", text.as_bytes()).expect("capture");
                        println!("CAPTURED {}", receipt.source_id);
                    }
                    other if other.starts_with("revoke ") => {
                        let key = other.trim_start_matches("revoke ").to_string();
                        let done = service.revoke_peer(&store, &key).expect("revoke");
                        println!("REVOKED {done}");
                    }
                    "" => {}
                    other => println!("UNKNOWN {other}"),
                }
                use std::io::Write;
                let _ = std::io::stdout().flush();
            }
        }
        "join" => {
            let address = args[5].clone();
            let code = args[6].clone();
            service.set_enabled(true).expect("bind").expect("an address");
            match service.pair_with_code(&store, &address, &code) {
                Ok(peer) => println!(
                    "PAIRED {} name={} device={} fingerprint={}",
                    peer.static_pub, peer.name, peer.device_id, peer.fingerprint
                ),
                Err(e) => {
                    println!("PAIR-FAILED {e}");
                    std::process::exit(1);
                }
            }
            report_peers(&service);
            report_sync(&service, &store);
            report_status(&service, &store);
        }
        other => {
            eprintln!("unknown mode {other}");
            std::process::exit(2);
        }
    }
}

fn report_peers(service: &SyncService) {
    for peer in service.peers() {
        println!(
            "PEER {} key={} name={} device={} state={} address={}",
            peer.fingerprint, peer.static_pub, peer.name, peer.device_id, peer.state, peer.address
        );
    }
    println!("PEERS {}", service.peers().len());
}

fn report_sync(service: &SyncService, store: &Store) {
    match service.sync_now(store) {
        Ok(report) => {
            // What arrived is history; a note on disk is what a person
            // sees. The shells project after every device sync, so this
            // does too, or "the note crossed" would be a claim about a
            // database rather than about a vault.
            let projected = store.project().map(|s| s.written).unwrap_or(0);
            println!("PROJECTED {projected}");
            println!(
                "SYNC applied={} sent={} duplicates={} blobs={} bytes={} halted={:?}",
                report.applied,
                report.sent,
                report.duplicates,
                report.blobs_completed,
                report.bytes,
                report.halted.iter().map(|h| h.reason.clone()).collect::<Vec<_>>()
            );
        }
        Err(e) => println!("SYNC-FAILED {e}"),
    }
}

fn report_status(_service: &SyncService, store: &Store) {
    let status = store.status().expect("status");
    println!(
        "STATUS sources={} operations={} projections={}",
        status.sources, status.operations, status.projections
    );
}
