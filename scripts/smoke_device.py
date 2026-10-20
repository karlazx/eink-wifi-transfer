#!/usr/bin/env python3
"""对已打开的阅读器服务验证小文件传输；只创建并清理本脚本的唯一命名样本。"""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import socket
import time
from urllib.parse import quote, urlsplit
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--url', required=True, help='阅读器显示的 HTTP 地址')
    parser.add_argument('--output', default='build/device-results.json')
    args = parser.parse_args()
    url = urlsplit(args.url)
    if url.scheme != 'http' or not url.hostname or not url.port:
        parser.error('请提供含端口的 HTTP 地址')
    prefix = 'm1-smoke-' + uuid.uuid4().hex[:10] + '-'
    created = set()
    evidence = []

    def path(name):
        return '/api/file?name=' + quote(name, safe='')

    def request(method, target, body=None, headers=None):
        connection = http.client.HTTPConnection(url.hostname, url.port, timeout=30)
        connection.request(method, target, body=body, headers=headers or {})
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def check(label, condition):
        if not condition:
            raise AssertionError(label)
        evidence.append({'check': label, 'result': '通过'})
        print('通过：' + label, flush=True)

    try:
        for ext in ['epub', 'txt', 'pdf', 'png', 'zip', 'apk', 'ttf', '任意格式', '']:
            name = prefix + '类型' + ('.' + ext if ext else '')
            body = bytes(range(256)) * 64
            code, _, _ = request('PUT', path(name), body)
            if code == 201:
                created.add(name)
            check('格式 ' + (ext or '无扩展名') + ' 上传', code == 201)
            code, headers, received = request('GET', path(name))
            check('格式 ' + (ext or '无扩展名') + ' 下载与 SHA-256', code == 200 and hashlib.sha256(received).digest() == hashlib.sha256(body).digest() and 'attachment' in headers.get('Content-Disposition', ''))
        for label, suffix, body in [('零字节', '零字节', b''), ('中文特殊名称', '中文 空格 % # & +.bin', b'\x00\xff\r\n')]:
            name = prefix + suffix
            code, _, _ = request('PUT', path(name), body)
            if code == 201: created.add(name)
            check(label + ' 上传及下载', code == 201 and request('GET', path(name))[2] == body)
        name = prefix + 'duplicate'
        code = request('PUT', path(name), b'original')[0]
        if code == 201: created.add(name)
        check('同名拒绝且原内容保留', code == 201 and request('PUT', path(name), b'replacement')[0] == 409 and request('GET', path(name))[2] == b'original')
        race = prefix + 'race'
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda body: request('PUT', path(race), body)[0], [b'A' * 16384, b'B' * 16384]))
        if 201 in results: created.add(race)
        check('两客户端同名竞争不覆盖', sorted(results) == [201, 409])
        for name in ['../outside', '/sdcard/outside', 'nested/file', 'a\\b']:
            check('拒绝非法路径 ' + name, request('PUT', path(name), b'x')[0] == 400)
        check('拒绝跨站请求', request('GET', '/api/files', headers={'Origin': 'https://foreign.example'})[0] == 403)
        interrupted = prefix + 'interrupted'
        sock = socket.create_connection((url.hostname, url.port), timeout=5)
        sock.sendall(('PUT ' + path(interrupted) + ' HTTP/1.1\r\nHost: ' + url.netloc + '\r\nContent-Length: 65536\r\n\r\n').encode() + b'partial')
        time.sleep(.2)
        check('上传中同名被拒绝', request('PUT', path(interrupted), b'new')[0] == 409)
        sock.close()
        time.sleep(.3)
        check('中断未提交为正式文件', request('GET', path(interrupted))[0] == 404)
        code = request('PUT', path(interrupted), b'retry')[0]
        if code == 201: created.add(interrupted)
        check('中断后可重新上传', code == 201)
        listing = json.loads(request('GET', '/api/files')[2])
        check('列表包含全部已完成样本', created.issubset({f['name'] for f in listing}))
    finally:
        failures = []
        for name in sorted(created):
            try:
                if request('DELETE', path(name))[0] != 200: failures.append(name)
            except OSError:
                failures.append(name)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps({'checks': evidence, 'cleanup_ok': not failures, 'scope': '只测试小文件，未执行大文件测试'}, ensure_ascii=False, indent=2) + '\n')
        if failures:
            raise RuntimeError('自建样本清理失败，请恢复服务后仅清理此前缀：' + prefix)
        print('自建样本已清理，未触碰其他文件。', flush=True)


if __name__ == '__main__':
    main()
