import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from types import SimpleNamespace as NS
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import wave

import server


class BridgeTests(unittest.TestCase):
    def test_search_mapping(self):
        track = NS(id=123, title='Песня', artists=[NS(name='Артист')], duration_ms=1000)
        fake = NS(search=lambda *a, **kw: NS(tracks=NS(results=[track])))
        with patch.object(server, 'client', fake):
            self.assertEqual(server.search_tracks('тест', 0)[0]['artist'], 'Артист')

    def test_audio_is_valid_wave(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp)/'test.wav'
            with wave.open(str(f), 'wb') as out:
                out.setnchannels(1)
                out.setsampwidth(2)
                out.setframerate(22050)
                out.writeframes(b'\x00\x00'*2205)
            option = NS(codec='mp3', direct_link=str(f), bitrate_in_kbps=192)
            track = NS(duration_ms=100, get_download_info=lambda **kw: [option])
            fake = NS(tracks=lambda ids: [track])
            with patch.object(server, 'client', fake), patch.dict(server.auth, state='ready'):
                result = server.audio_wave('123')
            with wave.open(io.BytesIO(result)) as decoded:
                self.assertEqual(decoded.getnchannels(), 2)
                self.assertEqual(decoded.getframerate(), 22050)
                self.assertGreater(decoded.getnframes(), 0)

    def test_local_api_blocks_browser_and_bad_ids(self):
        httpd = server.ThreadingHTTPServer((server.HOST, server.PORT), server.Handler)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = f'http://{server.HOST}:{server.PORT}'
        try:
            for path, headers, status in [('/status', {}, 403),
                ('/status', {'X-BlockRadio':'1','Origin':'https://example.com'},403),
                ('/audio?id=../../file', {'X-BlockRadio':'1'},400)]:
                with self.assertRaises(HTTPError) as caught:
                    urlopen(Request(base+path, headers=headers), timeout=3)
                self.assertEqual(caught.exception.code,status)
            with urlopen(Request(base+'/status',headers={'X-BlockRadio':'1'}),timeout=3) as r:
                self.assertIn('state',json.load(r))
            for url, allowed in [('https://ya.ru/device', True),
                                 ('https://oauth.yandex.ru/device', True),
                                 ('https://ya.ru.evil.test/device', False),
                                 ('https://evil.test/device', False)]:
                with patch.dict(server.auth, state='waiting', url=url, code='test-code'):
                    with urlopen(base+'/auth', timeout=3) as r:
                        page = r.read().decode()
                    self.assertEqual('Открыть страницу Яндекса</a>' in page, allowed)
                    self.assertNotIn('Ожидание кода', page)
        finally:
            httpd.shutdown()
            httpd.server_close()


if __name__ == '__main__':
    unittest.main()

