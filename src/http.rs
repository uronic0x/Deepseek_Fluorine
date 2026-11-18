use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::time::Duration;

pub const MAX_BODY: usize = 2 * 1024 * 1024;
const MAX_HEADER_BYTES: usize = 16 * 1024;

pub struct HttpRequest {
    pub method: String,
    pub path: String,
    headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl HttpRequest {
    pub fn header(&self, name: &str) -> &str {
        self.headers
            .get(&name.to_ascii_lowercase())
            .map(String::as_str)
            .unwrap_or("")
    }
}

pub fn read_request(stream: &mut TcpStream) -> Result<HttpRequest, &'static str> {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(15)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));

    let mut reader = BufReader::new(stream.try_clone().map_err(|_| "stream clone failed")?);
    let mut first_line = String::new();
    let n = reader
        .read_line(&mut first_line)
        .map_err(|_| "read request line failed")?;
    if n == 0 {
        return Err("empty request");
    }

    let mut parts = first_line.trim_end_matches(['\r', '\n']).split_whitespace();
    let method = parts.next().ok_or("missing method")?.to_owned();
    let raw_target = parts.next().ok_or("missing target")?;
    let path = raw_target.split('?').next().unwrap_or("/").to_owned();
    let _version = parts.next().ok_or("missing version")?;

    let mut headers = HashMap::new();
    let mut header_bytes = first_line.len();
    loop {
        let mut line = String::new();
        let len = reader
            .read_line(&mut line)
            .map_err(|_| "read header failed")?;
        if len == 0 {
            return Err("unexpected eof in headers");
        }
        header_bytes += len;
        if header_bytes > MAX_HEADER_BYTES {
            return Err("headers too large");
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        if let Some((k, v)) = trimmed.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_owned());
        }
    }

    if headers
        .get("transfer-encoding")
        .is_some_and(|v| v.to_ascii_lowercase().contains("chunked"))
    {
        return Err("chunked request body not supported");
    }

    let content_length = match headers.get("content-length") {
        Some(v) => v.parse::<usize>().map_err(|_| "invalid content-length")?,
        None => 0,
    };
    if content_length > MAX_BODY {
        return Err("body_limit");
    }

    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader
            .read_exact(&mut body)
            .map_err(|_| "read body failed")?;
    }

    Ok(HttpRequest {
        method,
        path,
        headers,
        body,
    })
}

fn status_reason(code: u16) -> &'static str {
    match code {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        402 => "Payment Required",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        413 => "Payload Too Large",
        415 => "Unsupported Media Type",
        429 => "Too Many Requests",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        _ => "Error",
    }
}

const CSP: &str = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

pub fn write_response(stream: &mut TcpStream, status: u16, content_type: &str, body: &[u8]) {
    let head = format!(
        "HTTP/1.1 {} {}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: {}\r\n\r\n",
        status,
        status_reason(status),
        content_type,
        body.len(),
        CSP
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

pub fn start_sse(stream: &mut TcpStream) -> std::io::Result<()> {
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\nCache-Control: no-store\r\nX-Accel-Buffering: no\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: {}\r\n\r\n",
        CSP
    );
    stream.write_all(head.as_bytes())?;
    stream.flush()
}

pub fn pipe_sse(stream: &mut TcpStream, mut upstream: impl Read) {
    if start_sse(stream).is_err() {
        return;
    }
    let mut buf = [0u8; 4096];
    loop {
        match upstream.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                if stream.write_all(&buf[..n]).is_err() || stream.flush().is_err() {
                    break;
                }
            }
            Err(_) => break,
        }
    }
}

pub fn write_sse_data(stream: &mut TcpStream, data: &str) -> std::io::Result<()> {
    stream.write_all(b"data: ")?;
    stream.write_all(data.as_bytes())?;
    stream.write_all(b"\n\n")?;
    stream.flush()
}
