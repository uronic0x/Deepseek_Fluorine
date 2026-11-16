"""Real Rust HTTP server + local fake upstream. Tests Chat SSE proxy & Work local tool loop."""
import http.client
import http.server
import json
import os
import pathlib
import socket
import subprocess
import threading
import time
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEEN = []
SSE = (
    ": keep-alive\n\n"
    + "data: "
    + json.dumps(
        {
            "choices": [
                {
                    "delta": {
                        "content": "你好😀",
                        "server_tool_calls": [
                            {"index": 0, "calls": [{"signature": "opaque", "kind": "read_link"}]}
                        ],
                    },
                    "finish_reason": "stop",
                }
            ]
        },
        ensure_ascii=False,
    )
    + "\n\ndata: "
    + json.dumps(
        {
            "choices": [],
            "usage": {"prompt_tokens": 10, "completion_tokens": 4, "server_tool_use": {"tool_search": 1}},
        }
    )
    + "\n\ndata: [DONE]\n\n"
)


class Fake(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"data":[{"id":"deepseek-flash","context_window":1048576}]}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        SEEN.append(body)
        if body["messages"][-1].get("content") == "limit":
            self.send_response(429)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"secret":"must not leak"}')
            return

        # Non-streaming local tool loop simulation
        if not body.get("stream", True):
            last_role = body["messages"][-1]["role"]
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            if last_role == "user":
                resp = {
                    "choices": [
                        {
                            "finish_reason": "tool_calls",
                            "message": {
                                "role": "assistant",
                                "reasoning_content": "Need to call calc",
                                "content": "",
                                "tool_calls": [
                                    {
                                        "id": "call_1",
                                        "type": "function",
                                        "function": {
                                            "name": "calc",
                                            "arguments": '{"expression":"(2+3)*4"}',
                                        },
                                    }
                                ],
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 20, "completion_tokens": 10},
                }
            else:
                resp = {
                    "choices": [
                        {
                            "finish_reason": "stop",
                            "message": {
                                "role": "assistant",
                                "reasoning_content": "Got 20",
                                "content": "计算结果为 20",
                            },
                        }
                    ],
                    "usage": {"prompt_tokens": 30, "completion_tokens": 8},
                }
            self.wfile.write(json.dumps(resp, ensure_ascii=False).encode())
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(SSE.encode())


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Integration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.up = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=cls.up.serve_forever, daemon=True).start()
        cls.port = free_port()
        env = {
            **os.environ,
            "PORT": str(cls.port),
            "DEEPSEEK_TEST_BASE": f"http://127.0.0.1:{cls.up.server_port}",
        }
        cls.proc = subprocess.Popen(
            [str(ROOT / "target/debug/qingming")],
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(100):
            try:
                if cls.request("GET", "/health")[0] == 200:
                    return
            except OSError:
                pass
            time.sleep(0.05)
        raise RuntimeError("Rust server did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        cls.up.shutdown()
        cls.up.server_close()

    @classmethod
    def request(cls, method, path, body=None, **override):
        c = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=10)
        hs = {
            "Origin": f"http://127.0.0.1:{cls.port}",
            "Authorization": "Bearer test-not-real",
            "Content-Type": "application/json",
            **override,
        }
        c.request(method, path, json.dumps(body or {}), hs)
        r = c.getresponse()
        result = (r.status, dict(r.getheaders()), r.read().decode())
        c.close()
        return result

    def payload(self, **kw):
        return {
            "stage": "chat",
            "model": "deepseek-flash",
            "web": True,
            "local_tools": False,
            "effort": "high",
            "max_tokens": 16384,
            "messages": [{"role": "user", "content": "hi"}],
            **kw,
        }

    def test_assets(self):
        for path in ["/", "/app.js", "/core.js", "/style.css"]:
            status, headers, _ = self.request("GET", path)
            self.assertEqual(status, 200)
            self.assertIn("frame-ancestors", headers["Content-Security-Policy"])

    def test_same_origin_and_host_guards(self):
        self.assertEqual(
            self.request("POST", "/api/chat", self.payload(), Origin="https://evil.example")[0], 403
        )
        self.assertEqual(self.request("GET", "/", Host="evil.example")[0], 403)
        self.assertEqual(self.request("POST", "/api/chat", self.payload(), Authorization="")[0], 401)

    def test_local_tool_loop_preserves_reasoning(self):
        status, _, body = self.request(
            "POST",
            "/api/chat",
            self.payload(stage="execute", local_tools=True, model="deepseek-v4-pro"),
        )
        self.assertEqual(status, 200)
        self.assertIn("计算结果为 20", body)
        self.assertIn('"value":20', body)
        # Verify reasoning_content was preserved in round 2 upstream request
        last_req = SEEN[-1]
        assistant_msg = [m for m in last_req["messages"] if m["role"] == "assistant"][-1]
        self.assertEqual(assistant_msg["reasoning_content"], "Need to call calc")


if __name__ == "__main__":
    unittest.main(verbosity=2)
