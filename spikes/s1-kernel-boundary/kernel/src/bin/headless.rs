//! Headless host profile (ADR 0008): the same kernel, no JVM, no UI.
//! Opens a kernel, runs a put/get/stream roundtrip, prints timings as JSON.
//! Usage: headless <dir> [blob-mib]

use spike_s1_kernel::Kernel;
use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let dir = args.next().ok_or_else(|| anyhow::anyhow!("usage: headless <dir> [blob-mib]"))?;
    let blob_mib: usize = args.next().map(|s| s.parse()).transpose()?.unwrap_or(64);

    let t = Instant::now();
    let kernel = Kernel::open(dir)?;
    let open_ms = t.elapsed().as_secs_f64() * 1000.0;

    let t = Instant::now();
    let record = kernel.put_record("headless probe".into(), None)?;
    let put_ms = t.elapsed().as_secs_f64() * 1000.0;
    assert!(kernel.get_record(record.id.clone())?.is_some());

    let chunk = vec![0xa5u8; 64 * 1024];
    let chunks = blob_mib * 16; // 64 KiB * 16 = 1 MiB
    let t = Instant::now();
    let writer = kernel.clone().open_blob_write("headless.bin".into())?;
    for _ in 0..chunks {
        writer.write_chunk(chunk.clone())?;
    }
    let handle = writer.finish()?;
    let write_s = t.elapsed().as_secs_f64();

    let t = Instant::now();
    let reader = kernel.open_blob_read("headless.bin".into())?;
    let mut total = 0u64;
    while let Some(c) = reader.read_chunk(64 * 1024)? {
        total += c.len() as u64;
    }
    let read_s = t.elapsed().as_secs_f64();
    assert_eq!(total, handle.bytes);

    println!(
        "{}",
        serde_json::json!({
            "open_ms": open_ms,
            "put_ms": put_ms,
            "blob_mib": blob_mib,
            "write_mib_s": blob_mib as f64 / write_s,
            "read_mib_s": blob_mib as f64 / read_s,
            "records": kernel.record_count()?,
        })
    );
    Ok(())
}
