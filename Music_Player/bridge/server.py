"""Loopback-only Yandex Music bridge. No credentials are sent to Minecraft."""
import html
import json
import re
import subprocess
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from yandex_music import Client
import imageio_ffmpeg
import keyring

HOST, PORT = '127.0.0.1', 18743
API_LOCK = threading.RLock()
AUTH_LOCK = threading.Lock()
AUDIO_LOCK = threading.Lock()
client = Client()
auth = {'state': 'not_logged_in'}


def restore():
    global client
    try:
        token = keyring.get_password('BlockRadio-Yandex', 'local-user')
        if token:
            client = Client(token).init()
            auth['state'] = 'ready'
    except Exception:
        auth['state'] = 'not_logged_in'


def login_worker():
    global client
    if not AUTH_LOCK.acquire(blocking=False):
        return
    try:
        candidate = Client()

        def on_code(code):
            auth.update(state='waiting', url=code.verification_url,
                        code=code.user_code)
            webbrowser.open(f'http://{HOST}:{PORT}/auth')

        auth.update(state='starting')
        token = candidate.device_auth(on_code=on_code, timeout=300,
                                      device_name='BlockRadio local prototype')
        candidate.init()
        with API_LOCK:
            client = candidate
        try:
            keyring.set_password('BlockRadio-Yandex', 'local-user', token.access_token)
            saved = True
        except Exception:
            saved = False  # No plaintext token fallback.
        auth.clear()
        auth.update(state='ready', saved=saved)
    except Exception as error:
        auth.clear()
        auth.update(state='error', error=type(error).__name__)
    finally:
        AUTH_LOCK.release()


def search_tracks(query, page):
    with API_LOCK:
        result = client.search(query, type_='track', page=page)
    found = result.tracks.results if result and result.tracks else []
    return [{'id': str(t.id), 'title': t.title or 'Без названия',
             'artist': ', '.join(a.name for a in (t.artists or [])),
             'duration_ms': t.duration_ms or 0} for t in found]


def audio_wave(track_id):
    if auth.get('state') != 'ready':
        raise ValueError('Сначала войди через кнопку «Войти в Яндекс».')
    with API_LOCK:
        tracks = client.tracks([track_id])
        if not tracks:
            raise ValueError('Трек не найден.')
        track = tracks[0]
        if track.duration_ms and track.duration_ms > 900_000:
            raise ValueError('Тестовая версия поддерживает треки до 15 минут.')
        options = track.get_download_info(get_direct_links=True)
        options = [o for o in options if o.codec == 'mp3' and o.direct_link]
        if not options:
            raise ValueError('Аудио недоступно для аккаунта. Проверь подписку и регион.')
        chosen = min(options, key=lambda o: abs(o.bitrate_in_kbps - 192))
        url = chosen.direct_link
    # Decode only audio; never copy attached cover art into the playback stream.
    completed = subprocess.run(
        [imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-nostdin',
         '-rw_timeout', '20000000', '-i', url, '-map', '0:a:0', '-vn',
         '-map_metadata', '-1', '-t', '900', '-ac', '2', '-ar', '22050',
         '-c:a', 'pcm_s16le', '-f', 'wav', 'pipe:1'],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=100,
        creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if completed.returncode or len(completed.stdout) < 44:
        raise ValueError('Не удалось получить аудио. Попробуй другой трек.')
    # FFmpeg cannot seek stdout; repair RIFF lengths for Java Sound.
    data = bytearray(completed.stdout)
    data[4:8] = (len(data) - 8).to_bytes(4, 'little')
    pos = 12
    while pos + 8 <= len(data):
        size = int.from_bytes(data[pos+4:pos+8], 'little')
        if data[pos:pos+4] == b'data':
            data[pos+4:pos+8] = (len(data) - pos - 8).to_bytes(4, 'little')
            break
        pos += 8 + size + size % 2
    return bytes(data)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log URLs, search terms, OAuth codes or tokens.

    def reply(self, status, body, content_type='application/json; charset=utf-8'):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.headers.get('Host') != f'{HOST}:{PORT}':
            self.reply(403, {'error': 'Invalid host'})
            return
        path = urlsplit(self.path)
        if path.path == '/auth':
            url = str(auth.get('url', ''))
            # The only outgoing auth link must be a Yandex HTTPS address.
            parsed = urlsplit(url)
            allowed = parsed.scheme == 'https' and bool(re.fullmatch(
                r'(?:(?:[a-z0-9-]+\.)*yandex\.(?:ru|com|by|kz|uz)|ya\.ru)', parsed.hostname or '')) and not parsed.username and not parsed.password
            link = f'<a href="{html.escape(url, quote=True)}" target="_blank" rel="noreferrer">Открыть страницу Яндекса</a>' if allowed else ('Ссылка входа не распознана. Открой https://oauth.yandex.ru/device и введи код ниже.' if auth.get('code') else 'Обнови страницу после начала входа в Minecraft.')
            page = '<!doctype html><meta charset="utf-8"><title>BlockRadio — вход</title><body style="font:20px system-ui;background:#17171b;color:#fff;padding:50px"><h1>Вход в Яндекс</h1><p>' + link + '</p><p>Код: <strong>' + html.escape(str(auth.get('code', ''))) + '</strong></p><p>Подтверди вход на странице Яндекса, затем вернись в Minecraft.</p><p>Статус: ' + html.escape(str(auth.get('state'))) + '</p><p>Пароль вводи только на сайте Яндекса.</p>'
            self.reply(200, page.encode(), 'text/html; charset=utf-8')
            return
        if self.headers.get('X-BlockRadio') != '1' or self.headers.get('Origin'):
            self.reply(403, {'error': 'Local Minecraft client required'})
            return
        try:
            args = parse_qs(path.query)
            if path.path == '/status':
                self.reply(200, dict(auth))
            elif path.path == '/login':
                threading.Thread(target=login_worker, daemon=True).start()
                self.reply(200, {'started': True})
            elif path.path == '/search':
                query = args.get('q', [''])[0].strip()[:160]
                page = max(0, min(100, int(args.get('page', ['0'])[0])))
                if not query:
                    raise ValueError('Введи название песни.')
                self.reply(200, {'tracks': search_tracks(query, page)})
            elif path.path == '/audio':
                track_id = args.get('id', [''])[0]
                if not re.fullmatch(r'\d+(?::\d+)?', track_id):
                    raise ValueError('Некорректный ID трека.')
                if not AUDIO_LOCK.acquire(blocking=False):
                    raise ValueError('Предыдущий трек ещё загружается. Подожди.')
                try:
                    data = audio_wave(track_id)
                finally:
                    AUDIO_LOCK.release()
                self.reply(200, data, 'audio/wav')
            else:
                self.reply(404, {'error': 'Not found'})
        except ValueError as error:
            self.reply(400, {'error': str(error)})
        except Exception as error:
            # Exception messages from remote libraries may contain sensitive URLs.
            self.reply(502, {'error': 'Яндекс недоступен или вход не выполнен (' + type(error).__name__ + '). Нажми «Войти в Яндекс».'})


if __name__ == '__main__':
    print('BlockRadio local bridge: http://127.0.0.1:18743')
    print('Keep this window open while playing Minecraft. Ctrl+C to stop.')
    threading.Thread(target=restore, daemon=True).start()
    try:
        ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        pass
