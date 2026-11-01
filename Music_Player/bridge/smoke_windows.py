"""CI smoke test of the real packaged EXE; no user credentials required."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
from urllib.request import Request,urlopen
import psutil

import sys
import zipfile
# Exercise the exact embedded release payload in an empty installation directory.
jar=Path('../source/build/libs/blockradio-0.4.0.jar')
extracted=tempfile.TemporaryDirectory()
if jar.exists():
    with zipfile.ZipFile(jar) as archive:
        payload=archive.read('backend/blockradio-backend.exe')
    assert payload[:2]==b'MZ'
    exe=Path(extracted.name)/'blockradio-backend.exe'
    exe.write_bytes(payload)
else:
    exe=Path('dist/blockradio-backend.exe').resolve()
with tempfile.TemporaryDirectory() as folder:
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    key=secrets.token_urlsafe(48)
    env=dict(os.environ,BLOCKRADIO_SESSION_KEY=key)
    env['PATH']=str(Path(os.environ['SystemRoot'])/'System32')
    for name in ('PYTHONPATH','PYTHONHOME','VIRTUAL_ENV'):
        env.pop(name,None)
    p=subprocess.Popen([str(exe),'--port',str(port),'--data-dir',folder],env=env,cwd=folder)
    children=[]
    def request(path,post=False):
        req=Request(f'http://127.0.0.1:{port}'+path,headers={'X-BlockRadio-Key':key},data=b'{}' if post else None)
        with urlopen(req,timeout=2) as r:return json.load(r)
    try:
        deadline=time.monotonic()+45
        while True:
            try:
                assert request('/health')['ready'];break
            except Exception:
                if p.poll() is not None:raise RuntimeError('Packaged backend exited during startup')
                if time.monotonic()>deadline:raise RuntimeError('Packaged backend did not become healthy')
                time.sleep(.25)
        children=psutil.Process(p.pid).children(recursive=True)
        assert set(request('/accounts'))=={'yandex','youtube'}
        request('/shutdown',True)
        p.wait(timeout=10)
        time.sleep(1)
        assert not any(c.is_running() for c in children),'Helper left orphan processes'
        print('Packaged EXE startup, authenticated health, accounts and shutdown: PASS')
    finally:
        if p.poll() is None:p.kill()
        for child in children:
            try:child.kill()
            except psutil.NoSuchProcess:pass
