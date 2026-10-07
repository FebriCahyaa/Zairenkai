// SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
//! Zairenkai Local Control Protocol server.
//!
//! The API is intentionally local-only and read-oriented in v1. Runtime
//! mutations remain behind zperfd's established transaction, license,
//! authority and Sentinel paths.
//!
//! Copyright (C) 2026 FebriCahyaa

use std::collections::BTreeMap;
use std::fs;
use std::io::{Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{FileTypeExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};

use zairenkai_core::api::{self as protocol, Request, Response, MAX_FRAME_BYTES, MAX_RESPONSE_BYTES, PROTOCOL_MAGIC};

use super::Args;

const DEFAULT_SOCKET_NAME: &str = "api.sock";

pub fn run(args: &Args) -> i32 {
    let socket = args.positional.first().map(PathBuf::from).unwrap_or_else(|| Path::new(&args.state).join(DEFAULT_SOCKET_NAME));
    match serve(args, &socket) {
        Ok(()) => 0,
        Err(e) => { eprintln!("zperfd api: {e}"); 5 }
    }
}

fn serve(args: &Args, socket: &Path) -> Result<(), String> {
    let parent = socket.parent().ok_or_else(|| "API socket has no parent directory".to_string())?;
    fs::create_dir_all(parent).map_err(|e| format!("create socket directory: {e}"))?;
    fs::set_permissions(parent, fs::Permissions::from_mode(0o700)).map_err(|e| format!("chmod socket directory: {e}"))?;
    if socket.exists() {
        let metadata = fs::symlink_metadata(socket).map_err(|e| format!("inspect existing socket: {e}"))?;
        if !metadata.file_type().is_socket() { return Err("refusing to replace a non-socket API path".into()); }
        fs::remove_file(socket).map_err(|e| format!("remove stale socket: {e}"))?;
    }
    let listener = UnixListener::bind(socket).map_err(|e| format!("bind {}: {e}", socket.display()))?;
    fs::set_permissions(socket, fs::Permissions::from_mode(0o600)).map_err(|e| format!("chmod socket: {e}"))?;
    eprintln!("zperfd api: listening on {} (root peer only)", socket.display());

    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(2)));
                let _ = stream.set_write_timeout(Some(std::time::Duration::from_secs(2)));
                if let Err(e) = require_root_peer(&stream) {
                    let _ = write_response(&mut stream, &Response::error(0, "peer_denied", e));
                    continue;
                }
                if let Err(e) = handle(args, &mut stream) {
                    let _ = write_response(&mut stream, &Response::error(0, "request_error", e));
                }
            }
            Err(e) => return Err(format!("accept: {e}")),
        }
    }
    Ok(())
}

fn require_root_peer(stream: &UnixStream) -> Result<(), String> {
    let fd = stream.as_raw_fd();
    let mut cred = libc::ucred { pid: 0, uid: u32::MAX, gid: u32::MAX };
    let mut len = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_PEERCRED,
            &mut cred as *mut libc::ucred as *mut libc::c_void, &mut len)
    };
    if rc != 0 { return Err(format!("SO_PEERCRED failed: {}", std::io::Error::last_os_error())); }
    if cred.uid != 0 { return Err(format!("peer uid {} is not root", cred.uid)); }
    Ok(())
}

fn read_frame(stream: &mut UnixStream) -> Result<String, String> {
    let mut header = [0u8; 8];
    stream.read_exact(&mut header).map_err(|e| format!("read frame header: {e}"))?;
    if &header[..4] != PROTOCOL_MAGIC { return Err("invalid ZLP magic".into()); }
    let len = u32::from_le_bytes(header[4..8].try_into().unwrap()) as usize;
    if len == 0 || len > MAX_FRAME_BYTES { return Err(format!("request frame length {len} is invalid")); }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).map_err(|e| format!("read frame body: {e}"))?;
    String::from_utf8(body).map_err(|e| format!("request is not UTF-8: {e}"))
}

fn write_response(stream: &mut UnixStream, response: &Response) -> Result<(), String> {
    let frame = protocol::encode_response(response)?;
    if frame.len() > MAX_RESPONSE_BYTES + 8 { return Err("response frame too large".into()); }
    stream.write_all(&frame).map_err(|e| format!("write response: {e}"))
}

fn handle(args: &Args, stream: &mut UnixStream) -> Result<(), String> {
    let body = read_frame(stream)?;
    let request = match protocol::decode_request(&body) {
        Ok(v) => v,
        Err(e) => { return write_response(stream, &Response::error(0, "invalid_request", e)); }
    };
    let response = dispatch(args, &request);
    write_response(stream, &response)
}

fn dispatch(args: &Args, request: &Request) -> Response {
    let required = match request.method.as_str() {
        "core.info" | "core.permissions" => None,
        "device.identity" => Some(zairenkai_core::operation::Operation::Probe),
        "device.inventory" => Some(zairenkai_core::operation::Operation::ReadInventory),
        "device.optimize" => Some(zairenkai_core::operation::Operation::ReadPerformance),
        "safety.evaluate" => None,
        "runtime.status" => Some(zairenkai_core::operation::Operation::ReadStatus),
        _ => None,
    };
    if let Some(operation) = required {
        if let Err(e) = super::core_authorize(operation) {
            return Response::error(request.request_id, "authority_denied", e);
        }
    }
    match request.method.as_str() {
        "core.info" => {
            let mut p = BTreeMap::new();
            p.insert("core_id".into(), zairenkai_core::CORE_ID.into());
            p.insert("core_name".into(), zairenkai_core::CORE_NAME.into());
            p.insert("core_api".into(), zairenkai_core::CORE_API_VERSION.to_string());
            Response::ok(request, "core information", p)
        }
        "core.permissions" => {
            let mut p = BTreeMap::new();
            p.insert("permissions".into(), super::permission_names());
            Response::ok(request, "runtime permission contract", p)
        }
        "device.identity" => {
            let s = super::Sysroot::new(&args.root);
            let identity = super::platform::PlatformIdentity::detect(&s);
            let mut p = BTreeMap::new();
            p.insert("vendor".into(), identity.vendor.as_str().into());
            p.insert("platform".into(), identity.platform);
            p.insert("model".into(), identity.model);
            p.insert("compatible".into(), identity.compatible);
            p.insert("evidence_count".into(), identity.evidence.len().to_string());
            Response::ok(request, "device identity", p)
        }
        "device.inventory" => {
            let s = super::Sysroot::new(&args.root);
            let identity = super::platform::PlatformIdentity::detect(&s);
            let inv = super::inventory::collect(&s, identity);
            let mut p = BTreeMap::new();
            p.insert("inventory_json".into(), super::inventory::json(&inv));
            Response::ok(request, "device inventory", p)
        }
        "device.optimize" => {
            let s = super::Sysroot::new(&args.root);
            let identity = super::platform::PlatformIdentity::detect(&s);
            let inv = super::inventory::collect(&s, identity.clone());
            let plan = super::optimize::plan(&s, &identity, &inv);
            let mut p = BTreeMap::new();
            p.insert("plan_json".into(), super::optimize::json(&plan));
            Response::ok(request, "read-only optimization plan", p)
        }
        "safety.evaluate" => {
            let Some(name) = request.args.get("operation") else { return Response::error(request.request_id, "missing_argument", "operation is required"); };
            let Some(operation) = super::parse_operation_name(name) else { return Response::error(request.request_id, "invalid_operation", "unsupported operation"); };
            if super::core_authorize(operation).is_err() { return Response::error(request.request_id, "authority_denied", "core authority denied operation"); }
            let decision = super::sentinel_evaluate(args, operation);
            let mut p = BTreeMap::new();
            p.insert("allowed".into(), decision.allowed.to_string());
            p.insert("class".into(), format!("{:?}", decision.class));
            p.insert("reasons".into(), decision.reasons.iter().map(|r| format!("{:?}", r)).collect::<Vec<_>>().join(","));
            Response::ok(request, "Sentinel evaluation", p)
        }
        "runtime.status" => runtime_status(args, request),
        _ => Response::error(request.request_id, "method_not_supported", "method is not available in ZLP v1"),
    }
}

fn runtime_status(args: &Args, request: &Request) -> Response {
    let state_dir = Path::new(&args.state);
    let desired = fs::read_to_string(state_dir.join("mode")).unwrap_or_else(|_| "unknown".into()).trim().to_string();
    let effective = fs::read_to_string(state_dir.join("effective_mode")).unwrap_or_else(|_| "unknown".into()).trim().to_string();
    let pending = state_dir.join("transaction.pending").exists();
    let mut p = BTreeMap::new();
    p.insert("desired".into(), desired);
    p.insert("effective".into(), effective);
    p.insert("pending_transaction".into(), pending.to_string());
    Response::ok(request, "runtime status", p)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn socket_default_stays_inside_state() {
        let args = Args { cmd: "api".into(), positional: vec![], root: "/".into(), profile: None, state: "/data/adb/zperf".into(), interval_ms: 1500, lite: false, json: false };
        assert_eq!(Path::new(&args.state).join(DEFAULT_SOCKET_NAME), PathBuf::from("/data/adb/zperf/api.sock"));
    }
}
