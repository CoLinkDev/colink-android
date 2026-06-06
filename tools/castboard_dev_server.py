# run with `uv run tools\castboard_dev_server.py`
# for debugging castboard with android webview

from __future__ import annotations

import argparse
import html
import mimetypes
import os
import posixpath
import queue
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


DEFAULT_PORT = 5173
POLL_INTERVAL_SECONDS = 0.35


def default_root() -> Path:
    return Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "castboard"


def snapshot(root: Path) -> dict[str, tuple[int, int]]:
    files: dict[str, tuple[int, int]] = {}
    for path in root.rglob("*"):
        if path.is_file():
            stat = path.stat()
            files[str(path.relative_to(root))] = (stat.st_mtime_ns, stat.st_size)
    return files


class ReloadHub:
    def __init__(self) -> None:
        self._clients: set[queue.Queue[str]] = set()
        self._lock = threading.Lock()

    def subscribe(self) -> queue.Queue[str]:
        client: queue.Queue[str] = queue.Queue()
        with self._lock:
            self._clients.add(client)
        return client

    def unsubscribe(self, client: queue.Queue[str]) -> None:
        with self._lock:
            self._clients.discard(client)

    def publish(self, message: str) -> None:
        with self._lock:
            clients = list(self._clients)
        for client in clients:
            client.put(message)


def watch(root: Path, hub: ReloadHub) -> None:
    previous = snapshot(root)
    while True:
        time.sleep(POLL_INTERVAL_SECONDS)
        current = snapshot(root)
        if current != previous:
            previous = current
            hub.publish("reload")


def reload_script() -> str:
    return """
<script>
(() => {
  const source = new EventSource("/__castboard_reload");
  source.addEventListener("reload", () => window.location.reload());
})();
</script>
"""


class CastBoardDevHandler(BaseHTTPRequestHandler):
    root: Path
    hub: ReloadHub

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/__castboard_reload":
            self.serve_reload_stream()
            return
        self.serve_file(parsed.path)

    def serve_reload_stream(self) -> None:
        client = self.hub.subscribe()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        try:
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            while True:
                message = client.get()
                self.wfile.write(f"event: {message}\ndata: {message}\n\n".encode("utf-8"))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        finally:
            self.hub.unsubscribe(client)

    def serve_file(self, raw_path: str) -> None:
        request_path = unquote(raw_path)
        if request_path in ("", "/"):
            request_path = "/index.html"
        normalized = posixpath.normpath(request_path.lstrip("/"))
        if normalized.startswith("../"):
            self.send_error(HTTPStatus.FORBIDDEN)
            return

        target = (self.root / normalized).resolve()
        try:
            target.relative_to(self.root)
        except ValueError:
            self.send_error(HTTPStatus.FORBIDDEN)
            return

        if not target.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return

        content_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        body = target.read_bytes()
        if target.name == "index.html":
            text = body.decode("utf-8")
            marker = "</body>"
            script = reload_script()
            if marker in text:
                text = text.replace(marker, f"{script}\n  {marker}", 1)
            else:
                text += script
            body = text.encode("utf-8")
            content_type = "text/html; charset=utf-8"

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        print(f"{self.address_string()} - {format % args}")


def main() -> None:
    parser = argparse.ArgumentParser(description="CastBoard debug server with browser reload.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--root", type=Path, default=default_root())
    args = parser.parse_args()

    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f"CastBoard root not found: {root}")

    hub = ReloadHub()
    CastBoardDevHandler.root = root
    CastBoardDevHandler.hub = hub
    threading.Thread(target=watch, args=(root, hub), daemon=True).start()

    server = ThreadingHTTPServer((args.host, args.port), CastBoardDevHandler)
    print(f"Serving {html.escape(str(root))}")
    print(f"Android emulator URL: http://10.0.2.2:{args.port}/index.html")
    print(f"LAN URL: http://<computer-ip>:{args.port}/index.html")
    server.serve_forever()


if __name__ == "__main__":
    main()
