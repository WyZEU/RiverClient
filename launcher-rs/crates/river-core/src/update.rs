//! Checking that an update manifest really came from River.
//!
//! The manifest says where every download is and what its SHA-256 should be, so whoever
//! can change the manifest can change what the launcher installs. It is signed with an
//! Ed25519 key that exists only on the publisher's machine, and anything that does not
//! verify is refused.
//!
//! The bytes that get signed must be identical to the ones `launcher/src/update-signature.js`
//! produces, or every manifest River has ever published would look forged. That is why the
//! canonical form here copies JavaScript's rules exactly: keys sorted by UTF-16 code unit,
//! numbers written exactly as they appear in the file, and `signature` left out.

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use ed25519_dalek::{Signature, VerifyingKey};
use sha2::{Digest, Sha256};

/// River's update signing key, as shipped in `launcher/src/config/update-public-key.txt`.
pub const PUBLIC_KEY_B64: &str = "MCowBQYDK2VwAyEAe6LtTQdFDalTTuAaoMDQ5ivkszatFz9Y4KoplagVdbw=";

/// The 12 bytes an Ed25519 SPKI public key starts with, before its 32 raw bytes.
const SPKI_PREFIX: [u8; 12] = [0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00];

#[derive(Debug, thiserror::Error, PartialEq)]
pub enum Error {
    #[error("the manifest carries no signature")]
    MissingSignature,
    #[error("the manifest is not an object")]
    NotAnObject,
    #[error("the signature is not valid base64")]
    MalformedSignature,
    #[error("the public key is not an Ed25519 SPKI key")]
    MalformedKey,
    #[error("the manifest was not signed by River")]
    NotSignedByRiver,
}

/// The exact bytes that are signed: the manifest without its `signature`, in canonical form.
pub fn signed_bytes(manifest: &serde_json::Value) -> Result<Vec<u8>, Error> {
    let object = manifest.as_object().ok_or(Error::NotAnObject)?;
    let mut rest = object.clone();
    rest.remove("signature");
    Ok(canonicalize(&serde_json::Value::Object(rest)).into_bytes())
}

/// Checks a manifest against River's published key.
pub fn verify(manifest: &serde_json::Value) -> Result<(), Error> {
    verify_with(manifest, PUBLIC_KEY_B64)
}

/// Checks a manifest against a given key, so tests can sign with their own.
pub fn verify_with(manifest: &serde_json::Value, public_key_b64: &str) -> Result<(), Error> {
    let signature_b64 = manifest
        .get("signature")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(Error::MissingSignature)?;
    let signature_bytes: [u8; 64] = BASE64
        .decode(signature_b64)
        .map_err(|_| Error::MalformedSignature)?
        .try_into()
        .map_err(|_| Error::MalformedSignature)?;
    let key = verifying_key(public_key_b64)?;
    key.verify_strict(&signed_bytes(manifest)?, &Signature::from_bytes(&signature_bytes))
        .map_err(|_| Error::NotSignedByRiver)
}

fn verifying_key(public_key_b64: &str) -> Result<VerifyingKey, Error> {
    let der = BASE64.decode(public_key_b64.trim()).map_err(|_| Error::MalformedKey)?;
    let raw: [u8; 32] = match der.len() {
        44 if der.starts_with(&SPKI_PREFIX) => der[12..].try_into().map_err(|_| Error::MalformedKey)?,
        32 => der.as_slice().try_into().map_err(|_| Error::MalformedKey)?,
        _ => return Err(Error::MalformedKey),
    };
    VerifyingKey::from_bytes(&raw).map_err(|_| Error::MalformedKey)
}

/// Whether a file's contents match the SHA-256 a manifest gives, written as hex.
pub fn matches_sha256(bytes: &[u8], expected_hex: &str) -> bool {
    let actual = Sha256::digest(bytes);
    let expected = expected_hex.trim();
    expected.len() == 64 && hex(&actual).eq_ignore_ascii_case(expected)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// JSON with every object's keys sorted, matching `canonicalize` in update-signature.js.
fn canonicalize(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::Null => "null".to_string(),
        serde_json::Value::Bool(true) => "true".to_string(),
        serde_json::Value::Bool(false) => "false".to_string(),
        // Written exactly as it stood in the file. The manifest is produced by
        // JSON.stringify, so its numbers are already in JavaScript's own form;
        // re-formatting them here is what would introduce a difference.
        serde_json::Value::Number(number) => number.to_string(),
        serde_json::Value::String(text) => quote(text),
        serde_json::Value::Array(items) => {
            let parts: Vec<String> = items.iter().map(canonicalize).collect();
            format!("[{}]", parts.join(","))
        }
        serde_json::Value::Object(entries) => {
            let mut keys: Vec<&String> = entries.keys().collect();
            keys.sort_by(|left, right| utf16_order(left, right));
            let parts: Vec<String> = keys
                .iter()
                .map(|key| format!("{}:{}", quote(key), canonicalize(&entries[key.as_str()])))
                .collect();
            format!("{{{}}}", parts.join(","))
        }
    }
}

/// JavaScript sorts strings by UTF-16 code unit, which differs from Rust's byte order
/// above the basic multilingual plane.
fn utf16_order(left: &str, right: &str) -> std::cmp::Ordering {
    left.encode_utf16().cmp(right.encode_utf16())
}

/// A JSON string escaped the way `JSON.stringify` escapes it.
fn quote(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for character in text.chars() {
        match character {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn canonical_form_matches_javascript() {
        // Each of these was produced by canonicalize() in update-signature.js.
        let cases = [
            (json!({ "b": 1, "a": 2 }), r#"{"a":2,"b":1}"#),
            (json!({ "version": "0.3.0", "required": false, "parts": [] }),
             r#"{"parts":[],"required":false,"version":"0.3.0"}"#),
            (json!({ "notes": "line\none\t\"quoted\"" }), r#"{"notes":"line\none\t\"quoted\""}"#),
            (json!({ "files": [{ "size": 308463114, "name": "a.zip" }] }),
             r#"{"files":[{"name":"a.zip","size":308463114}]}"#),
            (json!({ "A": 1, "a": 2, "Z": 3, "_": 4 }), r#"{"A":1,"Z":3,"_":4,"a":2}"#),
            (json!({ "nothing": null }), r#"{"nothing":null}"#),
        ];
        for (value, expected) in cases {
            assert_eq!(canonicalize(&value), expected);
        }
    }

    #[test]
    fn the_signature_itself_is_not_signed() {
        let manifest = json!({ "version": "0.3.0", "signature": "abc" });
        assert_eq!(String::from_utf8(signed_bytes(&manifest).unwrap()).unwrap(), r#"{"version":"0.3.0"}"#);
    }

    #[test]
    fn rivers_real_public_key_loads() {
        assert!(verifying_key(PUBLIC_KEY_B64).is_ok());
    }

    #[test]
    fn an_unsigned_manifest_is_refused() {
        assert_eq!(verify(&json!({ "version": "0.3.0" })), Err(Error::MissingSignature));
        assert_eq!(verify(&json!({ "version": "0.3.0", "signature": "" })), Err(Error::MissingSignature));
    }

    #[test]
    fn a_manifest_signed_by_someone_else_is_refused() {
        // A real Ed25519 key, just not River's.
        let other = "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE=";
        let manifest = json!({ "version": "0.3.0", "signature": BASE64.encode([7u8; 64]) });
        assert_eq!(verify_with(&manifest, other), Err(Error::NotSignedByRiver));
        assert_eq!(verify(&manifest), Err(Error::NotSignedByRiver));
    }

    #[test]
    fn hashes_are_compared_without_caring_about_case() {
        let upper = "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824";
        assert!(matches_sha256(b"hello", upper));
        assert!(matches_sha256(b"hello", &upper.to_lowercase()));
        assert!(!matches_sha256(b"hello!", upper));
        assert!(!matches_sha256(b"hello", "2cf2"));
    }
}
