"""实际 Java HTTP 服务的集成测试；只使用 Python 标准库。"""
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]


class TransferTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='eink-server-test-')
        cls.base = Path(cls.temp.name)
        cls.storage = cls.base / 'received'
        cls.storage.mkdir()
        classes = cls.base / 'classes'
        classes.mkdir()
        subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', str(classes),
                        str(ROOT / 'app/src/main/java/io/github/einktransfer/TransferServer.java'),
                        str(ROOT / 'tests/ServerHarness.java')], check=True)
        cls.process = subprocess.Popen(['java', '-cp', str(classes), 'io.github.einktransfer.ServerHarness',
                                        str(cls.storage), str(ROOT / 'app/src/main/assets/web')],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        port = cls.process.stdout.readline()
        if not port:
            raise RuntimeError(cls.process.stderr.read())
        cls.port = int(port)

    @classmethod
    def tearDownClass(cls):
        cls.process.stdin.write('\n')
        cls.process.stdin.flush()
        cls.process.communicate(timeout=5)
        cls.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=10)
        c.request(method, path, body=body, headers=headers or {})
        r = c.getresponse()
        result = (r.status, dict(r.getheaders()), r.read())
        c.close()
        return result

    def file(self, name):
        return '/api/file?name=' + quote(name, safe='')

    def test_01_page_and_headers(self):
        code, headers, body = self.request('GET', '/')
        self.assertEqual(code, 200)
        self.assertIn('墨屏传文件', body.decode())
        self.assertIn("script-src 'self'", headers['Content-Security-Policy'])
        self.assertEqual(headers['X-Content-Type-Options'], 'nosniff')
        self.assertEqual(self.request('GET', '/app.js')[0], 200)
        self.assertEqual(self.request('GET', '/missing')[0], 404)

    def test_02_all_extensions_and_bytes(self):
        for suffix in ['epub', 'txt', 'pdf', 'png', 'zip', 'apk', 'ttf', 'custom', '']:
            name = '类型验证' + ('.' + suffix if suffix else '')
            data = bytes(range(256)) * 1024
            self.assertEqual(self.request('PUT', self.file(name), data)[0], 201)
            code, headers, received = self.request('GET', self.file(name))
            self.assertEqual(code, 200)
            self.assertEqual(hashlib.sha256(data).digest(), hashlib.sha256(received).digest())
            self.assertTrue(headers['Content-Disposition'].startswith('attachment;'))
            self.assertEqual(headers['Content-Type'], 'application/octet-stream')

    def test_03_zero_unicode_special_names(self):
        for name, data in [('零字节', b''), ('中文 空格 % # & +.bin', b'\x00\xff\r\n')]:
            self.assertEqual(self.request('PUT', self.file(name), data)[0], 201)
            self.assertEqual(self.request('GET', self.file(name))[2], data)
        _, _, listing = self.request('GET', '/api/files')
        self.assertIn('中文 空格 % # & +.bin', [x['name'] for x in json.loads(listing)])

    def test_04_duplicate_does_not_overwrite(self):
        path = self.file('duplicate.bin')
        self.assertEqual(self.request('PUT', path, b'original')[0], 201)
        self.assertEqual(self.request('PUT', path, b'changed')[0], 409)
        self.assertEqual(self.request('GET', path)[2], b'original')

    def test_05_delete_is_exact(self):
        a, b = self.file('delete.txt'), self.file('keep.txt')
        for p in (a, b): self.assertEqual(self.request('PUT', p, b'keep')[0], 201)
        self.assertEqual(self.request('DELETE', a)[0], 200)
        self.assertEqual(self.request('GET', a)[0], 404)
        self.assertEqual(self.request('GET', b)[2], b'keep')

    def test_06_path_and_symlink_rejected(self):
        outside = self.base / 'outside.txt'
        outside.write_bytes(b'private')
        (self.storage / 'link').symlink_to(outside)
        for name in ['../outside.txt', '/etc/passwd', 'a/b', 'a\\b', '..', '.', '.eink-partials', 'a\x00b', 'a\nb', '长' * 100, 'link']:
            for method in ['PUT', 'GET', 'DELETE']:
                self.assertEqual(self.request(method, self.file(name), b'x' if method == 'PUT' else None)[0], 400, (method, name))
        self.assertEqual(outside.read_bytes(), b'private')
        (self.storage / 'link').unlink()
        self.assertEqual(self.request('PUT', '/api/file?name=%XX', b'x')[0], 400)

    def test_07_cross_site_and_dns_host_rejected(self):
        self.assertEqual(self.request('GET', '/api/files', headers={'Origin': 'https://foreign.example'})[0], 403)
        self.assertEqual(self.request('GET', '/api/files', headers={'Host': 'foreign.example'})[0], 403)
        self.assertEqual(self.request('OPTIONS', '/api/file')[0], 404)

    def test_08_size_limit_before_body(self):
        self.assertEqual(self.request('PUT', self.file('oversized'), b'', {'Content-Length': '2147483649'})[0], 413)
        self.assertEqual(self.request('PUT', self.file('negative'), b'', {'Content-Length': '-1'})[0], 411)

    def test_09_interrupted_upload_cleans_partial(self):
        s = socket.create_connection(('127.0.0.1', self.port))
        s.sendall(('PUT ' + self.file('interrupted') + ' HTTP/1.1\r\nHost: 127.0.0.1:' + str(self.port) + '\r\nContent-Length: 100000\r\n\r\n').encode() + b'partial')
        time.sleep(.1)
        self.assertEqual(self.request('PUT', self.file('interrupted'), b'new')[0], 409)
        listing = json.loads(self.request('GET', '/api/files')[2])
        self.assertNotIn('interrupted', [x['name'] for x in listing])
        s.close()
        for _ in range(100):
            if not list((self.storage / '.eink-partials').iterdir()): break
            time.sleep(.02)
        self.assertEqual(list((self.storage / '.eink-partials').iterdir()), [])
        self.assertEqual(self.request('PUT', self.file('interrupted'), b'retry')[0], 201)

    def test_10_concurrent_same_name(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda data: self.request('PUT', self.file('race'), data)[0], [b'A' * 65536, b'B' * 65536]))
        self.assertEqual(sorted(results), [201, 409])
        self.assertIn(self.request('GET', self.file('race'))[2], [b'A' * 65536, b'B' * 65536])

    def test_11_html_is_attachment(self):
        name = 'payload.html'
        data = b'<script>alert("test")</script>'
        self.assertEqual(self.request('PUT', self.file(name), data)[0], 201)
        code, headers, body = self.request('GET', self.file(name))
        self.assertEqual(code, 200)
        self.assertEqual(body, data)
        self.assertIn('attachment', headers['Content-Disposition'])
        self.assertIn('nosniff', headers['X-Content-Type-Options'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
