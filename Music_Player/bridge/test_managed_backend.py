import json
import tempfile
import threading
import unittest
from types import SimpleNamespace as NS
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from unittest.mock import patch
from http.server import ThreadingHTTPServer
import managed_backend as backend


class ManagedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.service = backend.Service(self.temp.name)
        self.http = ThreadingHTTPServer(('127.0.0.1', 0), backend.Handler)
        self.http.key = 'k'*64
        self.http.service = self.service
        self.base = f'http://127.0.0.1:{self.http.server_port}'
        self.service.base = self.base
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.http.server_close)
        self.addCleanup(self.http.shutdown)

    def call(self, path, data=None, headers=None):
        req = Request(self.base + path, headers=headers or {}, data=json.dumps(data).encode() if data is not None else None)
        with urlopen(req, timeout=2) as r:
            return json.load(r)

    def test_secret_required_and_browser_origin_blocked(self):
        for headers in ({}, {'X-BlockRadio-Key':'wrong'}, {'X-BlockRadio-Key':self.http.key,'Origin':'https://evil.test'}):
            with self.assertRaises(HTTPError) as e:
                self.call('/health', headers=headers)
            self.assertEqual(e.exception.code, 403)
        self.assertTrue(self.call('/health', headers={'X-BlockRadio-Key':self.http.key})['ready'])

    def test_browser_cannot_call_account_routes(self):
        headers = {'X-Player-Key':self.service.browser_key}
        with self.assertRaises(HTTPError):
            self.call('/accounts', headers=headers)
        self.assertFalse(self.call('/player/state', headers=headers)['active'])

    def test_browser_events_drained_once(self):
        self.call('/player/event', {'type':'ended','revision':7}, {'X-Player-Key':self.service.browser_key})
        headers = {'X-BlockRadio-Key':self.http.key}
        self.assertEqual(len(self.call('/health',headers=headers)['events']),1)
        self.assertEqual(self.call('/health',headers=headers)['events'],[])

    def test_youtube_id_cannot_reach_decoder(self):
        with patch.object(backend.legacy,'audio_wave') as decoder:
            with self.assertRaises(HTTPError):
                self.call('/audio?id=YouTubeID11',headers={'X-BlockRadio-Key':self.http.key})
            decoder.assert_not_called()

    def test_accounts_do_not_expose_device_code_or_tokens(self):
        with patch.dict(backend.legacy.auth, {'state':'waiting','code':'secret','access_token':'secret'}):
            result=self.call('/accounts',headers={'X-BlockRadio-Key':self.http.key})
        self.assertNotIn('secret',json.dumps(result))

    def test_wave_fetches_small_batch_only_when_needed(self):
        track=NS(id=123,title='test',artists=[],duration_ms=1000,cover_uri=None)
        fake=NS(rotor_station_tracks=lambda *a,**k:NS(batch_id='b',sequence=[NS(track=track),NS(track=track)]))
        with patch.object(backend.legacy,'client',fake), patch.object(fake,'rotor_station_tracks',wraps=fake.rotor_station_tracks) as fetch:
            self.service.wave();self.service.wave()
            self.assertEqual(fetch.call_count,1)

    def test_youtube_duration_mapping(self):
        result=backend.youtube_track({'id':'abc','snippet':{'title':'A &amp; B'},'contentDetails':{'duration':'PT1H2M3S'}})
        self.assertEqual(result['duration_ms'],3723000)
        self.assertEqual(result['title'],'A & B')

    def test_youtube_pagination_passes_opaque_cursor(self):
        fake = NS(search=lambda query, cursor: {'items': [], 'nextPageToken': 'next'})
        self.service.youtube = fake
        with patch.object(fake, 'search', wraps=fake.search) as search:
            result = self.service.tracks('youtube', 'music', youtube_page='opaque')
            search.assert_called_once_with('music', 'opaque')
        self.assertEqual(result['youtube_next'], 'next')

    def test_exhausted_youtube_list_does_not_spend_quota(self):
        fake = NS(search=lambda *a: self.fail('No more pages'))
        self.service.youtube = fake
        self.assertEqual(self.service.tracks('youtube', 'music', youtube_page='__end__')['tracks'], [])

if __name__=='__main__': unittest.main()
