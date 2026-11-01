"""YouTube account/data adapter. Playback belongs exclusively to the visible IFrame.

No tokens are returned to Minecraft or written to plain-text files.
"""
import base64
import hashlib
import json
import secrets
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen

CLIENT_ID = '197849337315-cl8q8kp7p2peam0vrq3b7ebdlj40lodk.apps.googleusercontent.com'
PROJECT_ID = 'youtube-player-513401'
SCOPE = 'https://www.googleapis.com/auth/youtube.readonly'
TOKEN_URL = 'https://oauth2.googleapis.com/token'


class ProviderError(Exception):
    """Safe message: never include remote response bodies or request URLs."""


class LoginRequired(ProviderError):
    pass


class ApiError(ProviderError):
    def __init__(self, status, reason):
        super().__init__(f'YouTube API: {status} ({reason})')
        self.status, self.reason = status, reason


def request_json(url, fields=None, token=None):
    headers = {'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if fields is None else urlencode(fields).encode()
    try:
        with urlopen(Request(url, data=data, headers=headers), timeout=20) as response:
            body = response.read()
            return json.loads(body) if body else {}
    except HTTPError as error:
        # Only expose a small allowlist of machine-readable reasons.
        reason = 'request_failed'
        try:
            payload = json.loads(error.read(65536)).get('error')
            value = payload if isinstance(payload, str) else payload['errors'][0]['reason']
            if value in {'invalid_grant', 'quotaExceeded', 'dailyLimitExceeded',
                         'access_denied', 'insufficientPermissions'}:
                reason = value
        except (ValueError, KeyError, TypeError, IndexError):
            pass
        raise ApiError(error.code, reason) from None
    except (URLError, TimeoutError, ValueError):
        raise ProviderError('YouTube connection failed; retry later.') from None


class SessionStore:
    """Use the OS credential store, with no plaintext fallback."""
    def load(self):
        import keyring
        value = keyring.get_password('BlockRadio-YouTube', CLIENT_ID)
        return json.loads(value) if value else None

    def save(self, value):
        import keyring
        keyring.set_password('BlockRadio-YouTube', CLIENT_ID, json.dumps(value))

    def delete(self):
        import keyring
        if keyring.get_password('BlockRadio-YouTube', CLIENT_ID) is not None:
            keyring.delete_password('BlockRadio-YouTube', CLIENT_ID)


class YouTubeProvider:
    def __init__(self, credentials_path, store=None, transport=request_json, clock=time.time):
        self.credentials_path = Path(credentials_path)
        self.store = store or SessionStore()
        self.transport, self.clock = transport, clock
        self._lock = threading.RLock()
        self._login_lock = threading.Lock()
        self._generation = 0
        self._session = self.store.load() or {}

    def _client(self):
        try:
            config = json.loads(self.credentials_path.read_text(encoding='utf-8'))['installed']
            if config['client_id'] != CLIENT_ID:
                raise ValueError()
            return {'client_id': CLIENT_ID, 'client_secret': config['client_secret']}
        except (OSError, ValueError, KeyError, TypeError):
            raise ProviderError('Select the local Desktop client_secret.json for this project.') from None

    def _save_token(self, result):
        scope = result.get('scope')
        if scope is not None and SCOPE not in scope.split():
            raise ProviderError('YouTube read permission was not granted.')
        session = dict(self._session)
        session.update(access_token=result['access_token'],
                       expires_at=self.clock() + int(result.get('expires_in', 3600)))
        if result.get('refresh_token'):
            session['refresh_token'] = result['refresh_token']
        self.store.save(session)
        self._session = session

    def login(self, open_browser=webbrowser.open, timeout=180):
        """Blocking worker operation; GUI must call off the render thread."""
        if not self._login_lock.acquire(blocking=False):
            raise ProviderError('Login is already in progress.')
        try:
            self._login(open_browser, timeout)
        finally:
            self._login_lock.release()

    def _login(self, open_browser, timeout):
        client = self._client()
        with self._lock:
            generation = self._generation
        verifier = secrets.token_urlsafe(64)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
        state = secrets.token_urlsafe(32)
        result = {}

        class Callback(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                parsed = urlsplit(self.path)
                query = parse_qs(parsed.query)
                valid = (self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'
                         and parsed.path == '/'
                         and len(query.get('state', [])) == 1
                         and secrets.compare_digest(query['state'][0], state))
                if valid:
                    if len(query.get('code', [])) == 1:
                        result['code'] = query['code'][0]
                    elif 'error' in query:
                        result['error'] = True
                    else:
                        valid = False
                body = b'You may close this window.' if valid else b'Invalid callback.'
                self.send_response(200 if valid else 400)
                self.send_header('Content-Type', 'text/plain; charset=utf-8')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        with HTTPServer(('127.0.0.1', 0), Callback) as server:
            server.timeout = .25
            redirect = f'http://127.0.0.1:{server.server_port}/'
            fields = dict(client_id=CLIENT_ID, redirect_uri=redirect, response_type='code',
                          scope=SCOPE, state=state, code_challenge=challenge,
                          code_challenge_method='S256', access_type='offline', prompt='consent')
            if not open_browser('https://accounts.google.com/o/oauth2/v2/auth?' + urlencode(fields)):
                raise ProviderError('Could not open the system browser.')
            deadline = time.monotonic() + timeout
            while not result and time.monotonic() < deadline:
                with self._lock:
                    if generation != self._generation:
                        raise ProviderError('Login cancelled.')
                server.handle_request()
            if 'code' not in result:
                raise ProviderError('Login was cancelled or timed out.')
            response = self.transport(TOKEN_URL, dict(client, grant_type='authorization_code',
                code=result['code'], redirect_uri=redirect, code_verifier=verifier))
            with self._lock:
                if generation != self._generation:
                    raise ProviderError('Login cancelled.')
                # Do not carry a previous account refresh token into a new login.
                previous = self._session
                self._session = {}
                try:
                    self._save_token(response)
                except Exception:
                    self._session = previous
                    raise

    def _token(self, force=False):
        with self._lock:
            if not force and self._session.get('expires_at', 0) > self.clock() + 60:
                return self._session['access_token']
            refresh = self._session.get('refresh_token')
            if not refresh:
                raise LoginRequired('Sign in to YouTube.')
            try:
                result = self.transport(TOKEN_URL, dict(self._client(),
                    grant_type='refresh_token', refresh_token=refresh))
            except ApiError as error:
                if error.reason == 'invalid_grant':
                    self.store.delete()
                    self._session = {}
                    raise LoginRequired('YouTube session expired; sign in again.') from None
                raise
            self._save_token(result)
            return self._session['access_token']

    def _get(self, resource, **params):
        url = 'https://www.googleapis.com/youtube/v3/' + resource + '?' + urlencode(params)
        token = self._token()
        try:
            return self.transport(url, token=token)
        except ApiError as error:
            if error.status != 401:
                raise
        return self.transport(url, token=self._token(force=True))

    def profile(self):
        items = self._get('channels', part='snippet', mine='true').get('items', [])
        if not items:
            return {'provider': 'youtube', 'name': 'Google account', 'avatar': None}
        channel = items[0]
        snippet = channel['snippet']
        return {'provider': 'youtube', 'id': channel['id'], 'name': snippet['title'],
                'avatar': snippet.get('thumbnails', {}).get('default', {}).get('url')}

    def search(self, query, page_token=''):
        query = query.strip()
        if not query or len(query) > 160:
            raise ProviderError('Enter a search query of 1–160 characters.')
        params = dict(part='snippet', q=query, type='video', videoEmbeddable='true',
                      videoSyndicated='true', maxResults=20)
        if page_token:
            params['pageToken'] = page_token
        result = self._get('search', **params)
        ids = [item['id']['videoId'] for item in result.get('items', [])]
        details = self._get('videos', part='snippet,contentDetails,status', id=','.join(ids)) if ids else {}
        return {'items': details.get('items', []), 'nextPageToken': result.get('nextPageToken')}

    def liked_tracks(self, page_token=''):
        params = dict(part='snippet,contentDetails', myRating='like', maxResults=20)
        if page_token:
            params['pageToken'] = page_token
        return self._get('videos', **params)

    def logout(self):
        # Local removal is guaranteed even if remote revocation is temporarily offline.
        with self._lock:
            self._generation += 1
            token = self._session.get('refresh_token') or self._session.get('access_token')
            self.store.delete()
            self._session = {}
        if token:
            try:
                self.transport('https://oauth2.googleapis.com/revoke', {'token': token})
            except ProviderError:
                return False
        return True
