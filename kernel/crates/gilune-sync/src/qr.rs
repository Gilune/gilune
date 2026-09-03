//! QR encoding (ADR 0019 § 7): "encode in Rust and paint in Compose".
//!
//! The matrix crosses the FFI as bits and the shell draws squares on a
//! `Canvas`. Two things fall out of that: no new Kotlin dependency, and
//! **the pairing secret never crosses the boundary as a string a shell
//! might log** — the only thing the shell ever sees is a grid of booleans.

use crate::{Result, SyncError};
use qrcode::types::Color;
use qrcode::QrCode;

/// A square module matrix, row-major. `width * width == modules.len()`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Matrix {
    pub width: u32,
    /// `true` is a dark module.
    pub modules: Vec<bool>,
}

impl Matrix {
    pub fn at(&self, x: u32, y: u32) -> bool {
        self.modules
            .get((y * self.width + x) as usize)
            .copied()
            .unwrap_or(false)
    }
}

/// Encode a pairing payload. QR is a frozen 2006 standard with no
/// security surface on the encode side, so the only failure worth naming
/// is a payload too large to fit any version.
pub fn encode(text: &str) -> Result<Matrix> {
    let code = QrCode::new(text.as_bytes()).map_err(|e| SyncError::Identity {
        detail: format!("that pairing payload will not fit in a QR code: {e}"),
    })?;
    let width = code.width() as u32;
    Ok(Matrix {
        width,
        modules: code
            .to_colors()
            .into_iter()
            .map(|c| c == Color::Dark)
            .collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pairing::{decode_qr_payload, encode_qr_payload};

    fn at(address: &str) -> Vec<String> {
        vec![address.to_string()]
    }

    #[test]
    fn a_pairing_payload_encodes_to_a_square_matrix() {
        let payload = encode_qr_payload(&[7u8; 16], "a1b2c3d4e5f67890", &at("192.168.1.20:51837"));
        let matrix = encode(&payload).expect("encode");
        assert!(matrix.width >= 21, "no QR version is smaller than 21 modules");
        assert_eq!(
            matrix.modules.len(),
            (matrix.width * matrix.width) as usize,
            "the shell draws this as a square and must not have to guess"
        );
        assert!(matrix.modules.iter().any(|m| *m), "and it is not blank");
    }

    #[test]
    fn the_finder_pattern_is_where_a_scanner_expects_it() {
        let payload = encode_qr_payload(&[1u8; 16], "0123456789abcdef", &at("10.0.0.2:51837"));
        let matrix = encode(&payload).expect("encode");
        // The top-left finder is a 7x7 dark ring with a 3x3 dark centre.
        for x in 0..7 {
            assert!(matrix.at(x, 0), "the top edge of the finder is dark");
            assert!(matrix.at(0, x), "and so is the left edge");
        }
        assert!(!matrix.at(1, 1), "with a light ring inside it");
        assert!(matrix.at(3, 3), "and a dark centre");
    }

    #[test]
    fn what_the_matrix_encodes_is_what_a_scan_would_decode() {
        // The encoder is not re-implemented here; what this pins is that
        // the payload handed to it is the payload the pairing module
        // agreed on, so a change to one is caught against the other.
        let payload = encode_qr_payload(&[5u8; 16], "0123456789abcdef", &at("10.0.0.2:51837"));
        assert!(encode(&payload).is_ok());
        let decoded = decode_qr_payload(&payload).expect("decode");
        assert_eq!(decoded.addresses, at("10.0.0.2:51837"));
        assert_eq!(decoded.secret, vec![5u8; 16]);

        // And a machine offering several still fits a scannable matrix,
        // which is the reason the hosts share one port in the field.
        let several = encode_qr_payload(
            &[5u8; 16],
            "0123456789abcdef",
            &[
                "192.168.1.20:51837".to_string(),
                "172.20.0.1:51837".to_string(),
                "10.8.0.2:51837".to_string(),
            ],
        );
        assert!(encode(&several).is_ok(), "a four-address payload has to still encode");
    }

    #[test]
    fn a_payload_too_large_for_any_qr_version_is_an_error_not_a_panic() {
        assert!(encode(&"x".repeat(8000)).is_err());
    }
}
