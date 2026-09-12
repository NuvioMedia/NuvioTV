import contextlib
import http.client
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from playback_fixture import PlaybackFixtureServer, byte_range


class PlaybackFixtureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.content = bytes(range(256)) * 8
        self.video = Path(self.tmp.name) / "synthetic.mp4"
        self.video.write_bytes(self.content)
        self.server = PlaybackFixtureServer(("127.0.0.1", 0), self.video, "http://127.0.0.1", 0.15, 0.15)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, target, method="GET", headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        connection.request(method, target, headers=headers or {})
        response = connection.getresponse()
        body = response.read()
        status, response_headers = response.status, dict(response.getheaders())
        connection.close()
        return status, response_headers, body

    def test_range_and_head_preserve_bytes(self):
        status, headers, body = self.request("/video/fast/1.mp4", headers={"Range": "bytes=10-99"})
        self.assertEqual(206, status)
        self.assertEqual(self.content[10:100], body)
        self.assertEqual("bytes 10-99/2048", headers["Content-Range"])
        status, headers, body = self.request("/video/fast/1.mp4", "HEAD")
        self.assertEqual(200, status)
        self.assertEqual("2048", headers["Content-Length"])
        self.assertEqual(b"", body)

    def test_invalid_range_returns_416(self):
        for value in ("bytes=9999-", "bytes=20-10", "bytes=0-1,5-7", "bytes=bad"):
            status, headers, body = self.request("/video/fast/1.mp4", headers={"Range": value})
            self.assertEqual(416, status)
            self.assertEqual("bytes */2048", headers["Content-Range"])

    def test_suffix_range(self):
        self.assertEqual((2038, 2047), byte_range("bytes=-10", 2048))

    def test_slow_delay_is_once_per_run_and_repeatable(self):
        # Actual socket delivery, not a virtual callback. Small delay keeps tests quick.
        for run in (1, 2):
            start = time.monotonic()
            _, _, body = self.request(f"/video/slow/{run}.mp4")
            self.assertGreaterEqual(time.monotonic() - start, 0.14)
            self.assertEqual(self.content, body)
            self.assertFalse(self.server.claim_delay(run))
            _, _, body = self.request(f"/video/slow/{run}.mp4", headers={"Range": "bytes=100-"})
            self.assertEqual(self.content[100:], body)

    def test_head_does_not_consume_slow_delay(self):
        self.request("/video/slow/99.mp4", "HEAD")
        self.assertTrue(self.server.claim_delay(99))

    def test_each_stream_listing_has_a_fresh_run(self):
        urls = []
        for _ in range(2):
            status, headers, body = self.request("/stream/movie/audit:slow.json")
            self.assertEqual(200, status)
            self.assertEqual("no-store", headers["Cache-Control"])
            urls.append(json.loads(body)["streams"][0]["url"])
        self.assertNotEqual(urls[0], urls[1])

    def test_url_encoded_addon_ids(self):
        status, _, body = self.request("/meta/movie/audit%3Afast.json")
        self.assertEqual(200, status)
        self.assertEqual("audit:fast", json.loads(body)["meta"]["id"])
        status, _, body = self.request("/stream/movie/audit%3Aslow.json")
        self.assertEqual(200, status)
        self.assertIn("/video/slow/", json.loads(body)["streams"][0]["url"])

    def test_request_query_secrets_are_not_logged(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.request("/video/fast/1.mp4?token=fixture-secret")
        self.assertNotIn("fixture-secret", out.getvalue() + err.getvalue())


if __name__ == "__main__":
    unittest.main()
