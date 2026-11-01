"""Managed loopback service. The legacy Yandex decoder remains in server.py."""
import argparse
import concurrent.futures
import html
import json
import os
from pathlib import Path
import re
import secrets
import sys
import threading
import time
import webbrowser
from http.server import ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import server as legacy
from youtube_provider import YouTubeProvider, ProviderError

POOL = concurrent.futures.ThreadPoolExecutor(max_workers=4)


def yandex_track(t):
    cover = getattr(t, 'cover_uri', None)
    return dict(provider='yandex', id=str(t.id), title=t.title or 'Без названия',
                artist=', '.join(a.name for a in (t.artists or [])),
                duration_ms=t.duration_ms or 0,
                cover='https://' + cover.replace('%%', '200x200') if cover else '')


def youtube_track(t):
    s = t.get('snippet', {})
    duration = t.get('contentDetails', {}).get('duration', '')
    m = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?', duration)
    ms = sum(int(v or 0)*n for v, n in zip(m.groups(), (3600000,60000,1000))) if m else 0
    return dict(provider='youtube', id=t['id'], title=html.unescape(s.get('title', 'YouTube')),
                artist=html.unescape(s.get('channelTitle', '')), duration_ms=ms,
                cover=s.get('thumbnails', {}).get('medium', {}).get('url', ''))


class Service:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.youtube = None
        self.youtube_status = {'state': 'not_logged_in'}
        self.yt_lock = threading.Lock()
        self.player_lock = threading.Lock()
        self.player = {'active': False}
        self.browser_events = []
        self.last_heartbeat = time.monotonic()
        self.browser_key = secrets.token_urlsafe(32)
        self.base = ''
        self.yandex_generation = 0
        self.wave_batch = None
        self.wave_lock = threading.Lock()
        self.wave_pending = []
        self.wave_last = None

    def init(self):
        legacy.restore()
        try:
            self.youtube = YouTubeProvider(self.data_dir / 'client_secret.json')
            self.yt()
            if self.youtube._session:
                self.youtube_status = dict(state='ready', **self.youtube.profile())
        except Exception:
            self.youtube_status = {'state': 'retry', 'message': 'Не удалось восстановить YouTube; повторите вход.'}

    def accounts(self):
        ya = {'state': legacy.auth.get('state', 'not_logged_in'), 'name': '', 'avatar': ''}
        if ya['state'] == 'ready':
            account = getattr(getattr(legacy.client, 'me', None), 'account', None)
            if account:
                ya['name'] = getattr(account, 'display_name', '') or getattr(account, 'login', '') or 'Яндекс'
                ya['avatar'] = getattr(account, 'avatar_url', '') or ''
        return {'yandex': ya, 'youtube': dict(self.youtube_status)}

    def login_yandex(self):
        # Legacy flow displays a device code in the browser, never in Minecraft.
        legacy.login_worker()

    def login_youtube(self):
        if not self.yt_lock.acquire(blocking=False):
            return
        try:
            self.youtube_status = {'state': 'waiting', 'message': 'Подтвердите вход в браузере'}
            if not self.youtube:
                self.youtube = YouTubeProvider(self.data_dir / 'client_secret.json')
            self.yt()
            if not self.youtube.credentials_path.exists():
                # Native file selection happens on the helper worker, not the game thread.
                import tkinter
                from tkinter.filedialog import askopenfilename
                root = tkinter.Tk(); root.withdraw(); root.attributes('-topmost', True)
                try:
                    selected = askopenfilename(title='Выберите Google Desktop client_secret.json', filetypes=[('Google OAuth JSON', '*.json')])
                finally:
                    root.destroy()
                if not selected:
                    raise ProviderError('Выбор файла отменён.')
                self.youtube.credentials_path = Path(selected)
                self.youtube._client()  # Validate before remembering the local path.
                self.data_dir.joinpath('google-path.txt').write_text(selected, encoding='utf-8')
            self.youtube.login()
            self.youtube_status = dict(state='ready', **self.youtube.profile())
        except Exception as e:
            self.youtube_status = {'state': 'error', 'message': str(e) if isinstance(e, ProviderError) else 'Не удалось войти в YouTube.'}
        finally:
            self.yt_lock.release()

    def yt(self):
        if not self.youtube:
            raise ProviderError('Войдите в YouTube.')
        saved = self.data_dir / 'google-path.txt'
        if saved.exists():
            self.youtube.credentials_path = Path(saved.read_text(encoding='utf-8'))
        return self.youtube

    def tracks(self, source, query, page=0, liked=False):
        def ya():
            with legacy.API_LOCK:
                if liked:
                    collection = legacy.client.users_likes_tracks()
                    short = (collection.tracks if collection else [])[page*20:(page+1)*20]
                    tracks = legacy.client.tracks([t.id for t in short]) if short else []
                else:
                    result = legacy.client.search(query, type_='track', page=page)
                    tracks = result.tracks.results if result and result.tracks else []
                return [yandex_track(t) for t in tracks]
        def yt():
            # YouTube pagination uses opaque tokens, passed separately by the GUI later.
            result = self.yt().liked_tracks() if liked else self.yt().search(query)
            return [youtube_track(t) for t in result.get('items', [])]
        selected = [('yandex', ya), ('youtube', yt)] if source == 'all' else [(source, ya if source == 'yandex' else yt)]
        futures = [(name, POOL.submit(fn)) for name, fn in selected]
        items, errors = [], []
        for name, future in futures:
            try:
                items.extend(future.result(timeout=50))
            except Exception:
                errors.append(name + ': недоступно; проверьте вход, сеть и квоту')
        return {'tracks': items, 'message': '; '.join(errors)}

    def wave(self):
        with self.wave_lock, legacy.API_LOCK:
            if not self.wave_pending:
                response = legacy.client.rotor_station_tracks('user:onyourwave', queue=self.wave_last)
                if not response:
                    raise ProviderError('Моя волна пока недоступна.')
                self.wave_batch = response.batch_id
                self.wave_pending = [x.track for x in response.sequence if x.track]
            if not self.wave_pending:
                raise ProviderError('Моя волна не вернула треков.')
            t = self.wave_pending.pop(0)
            self.wave_last = str(t.id)
            return yandex_track(t)


class Handler(legacy.Handler):
    def authorized(self):
        return (self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'
                and not self.headers.get('Origin')
                and secrets.compare_digest(self.headers.get('X-BlockRadio-Key', ''), self.server.key))

    def browser_authorized(self):
        origin = self.headers.get('Origin')
        return (self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'
                and (not origin or origin == self.server.service.base)
                and secrets.compare_digest(self.headers.get('X-Player-Key', ''), self.server.service.browser_key))

    def do_GET(self):
        route = urlsplit(self.path)
        s = self.server.service
        if route.path == '/auth':
            # Delegate the browser-only legacy login page, preserving its link validation.
            return super().do_GET()
        if route.path == '/player':
            if self.headers.get('Host') != f'127.0.0.1:{self.server.server_port}':
                return self.reply(403, {})
            body = Path(__file__).with_name('youtube_player.html').read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Referrer-Policy', 'strict-origin-when-cross-origin')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body); return
        if route.path == '/player/state' and self.browser_authorized():
            with s.player_lock:
                state = dict(s.player)
                state['serverNow'] = time.time()*1000
            return self.reply(200, state)
        if not self.authorized():
            return self.reply(403, {'error': 'Local client authentication required'})
        args = parse_qs(route.query)
        try:
            if route.path == '/health':
                s.last_heartbeat = time.monotonic()
                with s.player_lock:
                    events, s.browser_events = s.browser_events, []
                return self.reply(200, {'ready': True, 'events': events})
            if route.path == '/accounts':
                return self.reply(200, s.accounts())
            if route.path in ('/search', '/liked'):
                source = args.get('source', ['all'])[0]
                if source not in ('all', 'yandex', 'youtube'):
                    raise ValueError()
                query = args.get('q', [''])[0][:160]
                return self.reply(200, s.tracks(source, query, liked=route.path == '/liked'))
            if route.path == '/wave':
                return self.reply(200, {'tracks': [s.wave()]})
            if route.path == '/audio':
                track_id = args.get('id', [''])[0]
                if not re.fullmatch(r'\d+(?::\d+)?', track_id):
                    raise ValueError()
                with legacy.AUDIO_LOCK:
                    return self.reply(200, legacy.audio_wave(track_id), 'audio/wav')
            return self.reply(404, {'error': 'Unknown route'})
        except Exception:
            return self.reply(502, {'error': 'Источник недоступен. Проверьте аккаунт, сеть или подписку.'})

    def do_POST(self):
        s = self.server.service
        path = urlsplit(self.path).path
        browser = path == '/player/event' and self.browser_authorized()
        if not browser and not self.authorized():
            return self.reply(403, {})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= 65536:
                return self.reply(413, {})
            data = json.loads(self.rfile.read(length) or b'{}')
            if browser:
                if data.get('type') in ('ended', 'error'):
                    with s.player_lock:
                        s.browser_events = (s.browser_events + [data])[-8:]
            elif path == '/login':
                provider = data.get('provider')
                if provider not in ('yandex','youtube'):
                    raise ValueError()
                threading.Thread(target=s.login_yandex if provider=='yandex' else s.login_youtube, daemon=True).start()
            elif path == '/logout':
                if data.get('provider') == 'youtube':
                    if s.youtube:
                        s.youtube.logout()
                    s.youtube_status = {'state': 'not_logged_in'}
                else:
                    # Do not race an in-progress device flow.
                    if not legacy.AUTH_LOCK.acquire(blocking=False):
                        return self.reply(409, {'error': 'Дождитесь окончания входа.'})
                    try:
                        import keyring
                        for key in ('local-user', 'session'):
                            if keyring.get_password('BlockRadio-Yandex',key):
                                keyring.delete_password('BlockRadio-Yandex',key)
                        with legacy.API_LOCK:
                            legacy.client = legacy.Client()
                            legacy.auth.clear(); legacy.auth['state'] = 'not_logged_in'
                    finally:
                        legacy.AUTH_LOCK.release()
            elif path == '/player/state':
                with s.player_lock:
                    s.player = dict(data, receivedAt=time.time()*1000)
            elif path == '/player/open':
                webbrowser.open(s.base + '/player#' + s.browser_key)
            elif path == '/shutdown':
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            else:
                return self.reply(404, {})
            self.reply(200, {'ok': True})
        except Exception:
            self.reply(400, {'error': 'Не удалось выполнить действие.'})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--data-dir', required=True)
    args = parser.parse_args()
    key = os.environ.pop('BLOCKRADIO_SESSION_KEY', '')
    if len(key) < 32:
        raise SystemExit('Missing local session key')
    legacy.PORT = args.port
    service = Service(args.data_dir)
    service.base = f'http://127.0.0.1:{args.port}'
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.daemon_threads = True
    server.key, server.service = key, service
    threading.Thread(target=service.init, daemon=True).start()
    def watchdog():
        while time.monotonic() - service.last_heartbeat < 20:
            time.sleep(2)
        server.shutdown()
    threading.Thread(target=watchdog, daemon=True).start()
    try:
        server.serve_forever(poll_interval=.25)
    finally:
        server.server_close()
        POOL.shutdown(wait=False, cancel_futures=True)
        # Java owns this process tree and kills decoding subprocesses on shutdown.
        os._exit(0)


if __name__ == '__main__':
    main()
