// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Zairenkai Local Control Protocol (ZLP) v1.
//!
//! The protocol is deliberately local-first: a bounded length-prefixed TOML
//! envelope travels over a Unix domain socket. The transport has no remote
//! network listener and is expected to be protected by filesystem ownership
//! and peer-credential checks in zperfd.
//!
//! Copyright (C) 2026 FebriCahyaa

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const API_NAME: &str = "zairenkai.local";
pub const API_VERSION: u32 = 1;
pub const PROTOCOL_MAGIC: &[u8; 4] = b"ZLP1";
pub const MAX_FRAME_BYTES: usize = 32 * 1024;
pub const MAX_RESPONSE_BYTES: usize = 128 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Request {
    pub api_version: u32,
    pub request_id: u64,
    pub method: String,
    #[serde(default)]
    pub args: BTreeMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Response {
    pub api_version: u32,
    pub request_id: u64,
    pub ok: bool,
    pub code: String,
    pub message: String,
    #[serde(default)]
    pub payload: BTreeMap<String, String>,
}

impl Response {
    pub fn ok(request: &Request, message: impl Into<String>, payload: BTreeMap<String, String>) -> Self {
        Self { api_version: API_VERSION, request_id: request.request_id, ok: true, code: "ok".into(), message: message.into(), payload }
    }

    pub fn error(request_id: u64, code: impl Into<String>, message: impl Into<String>) -> Self {
        Self { api_version: API_VERSION, request_id, ok: false, code: code.into(), message: message.into(), payload: BTreeMap::new() }
    }
}

pub fn encode_request(request: &Request) -> Result<Vec<u8>, String> {
    let body = toml::to_string(request).map_err(|e| format!("encode request: {e}"))?;
    frame(&body, MAX_FRAME_BYTES)
}

pub fn decode_request(body: &str) -> Result<Request, String> {
    let request: Request = toml::from_str(body).map_err(|e| format!("decode request: {e}"))?;
    if request.api_version != API_VERSION {
        return Err(format!("unsupported API version {}", request.api_version));
    }
    if request.method.len() > 96 {
        return Err("method name too long".into());
    }
    if request.args.len() > 64 {
        return Err("too many request arguments".into());
    }
    for (k, v) in &request.args {
        if k.len() > 96 || v.len() > 2048 {
            return Err("request argument exceeds size limit".into());
        }
    }
    Ok(request)
}

pub fn encode_response(response: &Response) -> Result<Vec<u8>, String> {
    let body = toml::to_string(response).map_err(|e| format!("encode response: {e}"))?;
    if body.len() > MAX_RESPONSE_BYTES { return Err("response exceeds size limit".into()); }
    frame(&body, MAX_RESPONSE_BYTES)
}

fn frame(body: &str, max_bytes: usize) -> Result<Vec<u8>, String> {
    let bytes = body.as_bytes();
    if bytes.len() > max_bytes { return Err("frame exceeds size limit".into()); }
    let len = u32::try_from(bytes.len()).map_err(|_| "frame too large")?;
    let mut out = Vec::with_capacity(8 + bytes.len());
    out.extend_from_slice(PROTOCOL_MAGIC);
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(bytes);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_round_trip_is_bounded() {
        let mut args = BTreeMap::new();
        args.insert("scope".into(), "runtime".into());
        let request = Request { api_version: API_VERSION, request_id: 42, method: "core.info".into(), args };
        let framed = encode_request(&request).unwrap();
        assert_eq!(&framed[..4], PROTOCOL_MAGIC);
        let len = u32::from_le_bytes(framed[4..8].try_into().unwrap()) as usize;
        let parsed = decode_request(std::str::from_utf8(&framed[8..8+len]).unwrap()).unwrap();
        assert_eq!(parsed, request);
    }

    #[test]
    fn request_rejects_wrong_api() {
        let body = "api_version = 999\nrequest_id = 1\nmethod = 'core.info'\n";
        assert!(decode_request(body).is_err());
    }
}
