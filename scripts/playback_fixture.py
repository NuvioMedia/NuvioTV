"""Local Stremio-compatible addon serving repeatable fast, slow and hung MP4 streams.

Only synthetic/local test media should be used. No accounts, addons or tokens needed.
See docs/playback-device-test-protocol-it.md. Python standard library only.
"""
import argparse
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


KINDS = ("fast", "slow", "hung")


def byte_range(header, length):
    """Single RFC byte range, including suffix requests; reject invalid/multiple ranges."""
    if header is None:
        return 0, length - 1
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header)
    if not match or not any(match.groups()):
        raise ValueError("invalid range")
    first, last = match.groups()
    if not first:
        if int(last) == 0:
            raise ValueError("empty suffix")
        return max(0, length - int(last)), length - 1
    start = int(first)
    end = min(int(last), length - 1) if last else length - 1
    if start >= length or start > end:
        raise ValueError("unsatisfiable range")
    return start, end


class PlaybackFixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, video, public_base_url, delay_seconds=28.0, hung_seconds=90.0):
        super().__init__(address, Handler)
        self.video = Path(video)
        self.public_base_url = public_base_url.rstrip("/")
        self.delay_seconds = delay_seconds
        self.hung_seconds = hung_seconds
        self.run_lock = threading.Lock()
        self.run_id = 0
        self.delayed_runs = set()

    def next_run(self):
        with self.run_lock:
            self.run_id += 1
            # Bound fixture memory for long repeatability runs.
            self.delayed_runs = {run for run in self.delayed_runs if run >= self.run_id - 1000}
            return self.run_id

    def claim_delay(self, run_id):
        with self.run_lock:
            if run_id in self.delayed_runs:
                return False
            self.delayed_runs.add(run_id)
            return True


def meta(kind):
    return {"id": "audit:" + kind, "type": "movie", "name": "Audit " + kind,
            "description": "Video sintetico locale per test NuvioTV", "runtime": "2 min"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Base handler logs full request targets, which could contain pasted credentials.
        pass

    def do_HEAD(self):
        self.dispatch(head=True)

    def do_GET(self):
        self.dispatch(head=False)

    def dispatch(self, head):
        path = unquote(urlsplit(self.path).path)
        if path == "/manifest.json":
            data = {"id": "local.nuvio.audit", "version": "1.1.0", "name": "Nuvio Local Audit",
                    "description": "Test locali ripetibili: fast, slow, hung",
                    "resources": ["catalog", "meta", "stream"], "types": ["movie"],
                    "catalogs": [{"type": "movie", "id": "audit", "name": "Audit riproduzione"}],
                    "idPrefixes": ["audit:"]}
        elif path == "/catalog/movie/audit.json":
            data = {"metas": [meta(kind) for kind in KINDS]}
        elif match := re.fullmatch(r"/meta/movie/audit:(fast|slow|hung)\.json", path):
            data = {"meta": meta(match[1])}
        elif match := re.fullmatch(r"/stream/movie/audit:(fast|slow|hung)\.json", path):
            kind = match[1]
            run_id = self.server.next_run()
            data = {"streams": [{"name": "Fixture locale", "title": "Audit " + kind,
                    "url": f"{self.server.public_base_url}/video/{kind}/{run_id}.mp4"}]}
        elif match := re.fullmatch(r"/video/(fast|slow|hung)/(\d+)\.mp4", path):
            return self.video(match[1], int(match[2]), head)
        else:
            self.send_error(404)
            return
        body = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def video(self, kind, run_id, head):
        size = self.server.video.stat().st_size
        ranged = self.headers.get("Range")
        try:
            start, end = byte_range(ranged, size)
        except ValueError:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.send_response(206 if ranged else 200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Cache-Control", "no-store")
        if ranged:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head:
            return
        should_delay = kind == "slow" and start == 0 and self.server.claim_delay(run_id)
        print(f"fixture scenario={kind} run={run_id} start={start} delayed={should_delay}", flush=True)
        try:
            if kind == "hung":
                time.sleep(self.server.hung_seconds)
                return
            with self.server.video.open("rb") as source:
                source.seek(start)
                remaining = end - start + 1
                if should_delay:
                    # Small reads keep transport alive but withhold enough MP4 data to delay decoding.
                    # Delay only the initial request of this run, never seeks/reconnects.
                    deadline = time.monotonic() + self.server.delay_seconds
                    while remaining > 0 and time.monotonic() < deadline:
                        chunk = source.read(min(32, remaining))
                        self.wfile.write(chunk)
                        self.wfile.flush()
                        remaining -= len(chunk)
                        time.sleep(max(0, min(1, deadline - time.monotonic())))
                while remaining > 0:
                    chunk = source.read(min(64 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True, help="Synthetic H.264/AAC MP4")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--public-base-url", default="http://127.0.0.1:8765",
                        help="Host visible to device; default works with adb reverse")
    parser.add_argument("--delay-seconds", type=float, default=28)
    parser.add_argument("--hung-seconds", type=float, default=90)
    args = parser.parse_args()
    base = urlsplit(args.public_base_url)
    if (base.scheme != "http" or not base.hostname or base.username or base.password
            or base.query or base.fragment or base.path not in ("", "/")):
        parser.error("public-base-url must be a plain http origin without credentials/path/query")
    if not args.video.is_file() or args.video.stat().st_size == 0:
        parser.error("video must be a nonempty local MP4 file")
    if args.delay_seconds < 0 or args.hung_seconds < 0:
        parser.error("delays must be nonnegative")
    server = PlaybackFixtureServer((args.bind, args.port), args.video, args.public_base_url,
                                  args.delay_seconds, args.hung_seconds)
    print("Fixture ready; add the configured origin + /manifest.json in NuvioTV.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
