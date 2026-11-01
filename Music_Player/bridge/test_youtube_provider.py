import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit, urlencode
from urllib.request import urlopen
from urllib.error import HTTPError

from youtube_provider import YouTubeProvider, ApiError, LoginRequired, ProviderError, CLIENT_ID, SCOPE


class MemoryStore:
    def __init__(self, value=None):
        self.value = value
    def load(self):
        return self.value
    def save(self, value):
        self.value = dict(value)
    def delete(self):
        self.value = None


class YouTubeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'client_secret.json'
        self.path.write_text(json.dumps({'installed': {'client_id': CLIENT_ID, 'client_secret': 'fake'}}))

    def provider(self, transport, session=None):
        return YouTubeProvider(self.path, MemoryStore(session), transport, lambda: 1000)

    def test_refresh_preserves_refresh_token(self):
        p = self.provider(lambda *a, **k: {'access_token': 'new', 'expires_in': 3600},
                          {'refresh_token': 'original', 'expires_at': 0})
        self.assertEqual(p._token(), 'new')
        self.assertEqual(p.store.value['refresh_token'], 'original')
        self.assertEqual(p.store.value['expires_at'], 4600)

    def test_transient_failure_keeps_session(self):
        def fail(*a, **k):
            raise ApiError(503, 'request_failed')
        p = self.provider(fail, {'refresh_token': 'keep'})
        with self.assertRaises(ApiError):
            p._token()
        self.assertEqual(p.store.value['refresh_token'], 'keep')

    def test_revoked_refresh_requires_login(self):
        def fail(*a, **k):
            raise ApiError(400, 'invalid_grant')
        p = self.provider(fail, {'refresh_token': 'expired'})
        with self.assertRaises(LoginRequired):
            p._token()
        self.assertIsNone(p.store.value)

    def test_login_rejects_forged_state_and_uses_pkce(self):
        calls, errors = [], []
        def transport(url, fields=None, **kwargs):
            calls.append(fields)
            return {'access_token': 'fake', 'refresh_token': 'refresh', 'scope': SCOPE}
        def browser(url):
            query = parse_qs(urlsplit(url).query)
            self.assertEqual(query['code_challenge_method'], ['S256'])
            def callback():
                try:
                    target = query['redirect_uri'][0]
                    with self.assertRaises(HTTPError) as error:
                        urlopen(target + '?state=forged&code=bad', timeout=3)
                    self.assertEqual(error.exception.code, 400)
                    with urlopen(target + '?' + urlencode({'state': query['state'][0], 'code': 'good'}), timeout=3) as response:
                        self.assertEqual(response.status, 200)
                except Exception as error:
                    errors.append(error)
            thread = threading.Thread(target=callback)
            thread.start()
            self.addCleanup(thread.join, 4)
            return True
        p = self.provider(transport)
        p.login(browser, timeout=4)
        self.assertFalse(errors)
        self.assertEqual(calls[0]['code'], 'good')
        self.assertGreaterEqual(len(calls[0]['code_verifier']), 43)
        self.assertEqual(p.store.value['refresh_token'], 'refresh')

    def test_quota_error_does_not_refresh(self):
        calls = []
        def fail(url, **kwargs):
            calls.append(url)
            raise ApiError(403, 'quotaExceeded')
        p = self.provider(fail, {'access_token': 'valid', 'expires_at': 9999})
        with self.assertRaises(ApiError):
            p.search('music')
        self.assertEqual(len(calls), 1)

    def test_search_requests_embeddable_videos_and_durations(self):
        urls = []
        def transport(url, **kwargs):
            urls.append(url)
            if '/search?' in url:
                return {'items': [{'id': {'videoId': 'example'}}], 'nextPageToken': 'page2'}
            return {'items': [{'id': 'example', 'contentDetails': {'duration': 'PT3M'}}]}
        p = self.provider(transport, {'access_token': 'valid', 'expires_at': 9999})
        result = p.search('music')
        self.assertIn('videoEmbeddable=true', urls[0])
        self.assertEqual(result['items'][0]['contentDetails']['duration'], 'PT3M')
        self.assertEqual(result['nextPageToken'], 'page2')

    def test_logout_clears_local_session_when_offline(self):
        def fail(*a, **k):
            raise ProviderError('offline')
        p = self.provider(fail, {'refresh_token': 'secret'})
        self.assertFalse(p.logout())
        self.assertIsNone(p.store.value)
        with self.assertRaises(LoginRequired):
            p._token()

    def test_public_client_needs_no_local_credentials(self):
        self.path.write_text('{"installed":{"client_id":"wrong"}}')
        self.assertEqual({'client_id': CLIENT_ID}, self.provider(None)._client())
        self.path.unlink()
        self.assertEqual({'client_id': CLIENT_ID}, self.provider(None)._client())


if __name__ == '__main__':
    unittest.main()
