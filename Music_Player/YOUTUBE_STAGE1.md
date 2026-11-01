# YouTube-only development: stage 1

The new implementation targets YouTube Data API v3 and the visible YouTube
IFrame Player API only. Spotify and SoundCloud are out of scope. Existing
BlockRadio 0.2.0 files remain as a migration baseline, not as new Yandex work.

## Implemented

`bridge/youtube_provider.py` is an account/data adapter, not an audio decoder.
It supports system-browser Desktop OAuth with PKCE S256, random loopback port,
state validation, timeout/cancellation, OS-keyring session persistence, refresh,
profile, paginated search with video details, liked videos and sign-out.
Search requests only embeddable/syndicated videos; actual playback availability
must still be handled through IFrame errors. No audio is extracted or downloaded.

The existing bridge already declares the keyring dependency. The adapter reads
the user's local Google Desktop credentials file from a supplied path; it never
uploads it. The eventual GUI must provide a file picker rather than asking users
to edit token files. Only the public client/project IDs are committed.

Account operations are blocking worker operations and must never run on the
Minecraft render thread. The adapter is not yet wired to the legacy HTTP bridge
or Minecraft screens. Do not represent this stage as a ready-to-install player.

## Validation

Run `python -m unittest test_youtube_provider -v` from `bridge`.
Tests use fake credentials and an ephemeral loopback server, not real accounts.
Coverage: PKCE callback/state, refresh preservation, revoked sessions, temporary
network failure, quota errors, search details, logout offline, client mismatch.

The development environment available for this change is Python 3.12.
Windows, the user's stated Python 3.15.2 environment, real Google authorization,
OS keyring persistence and Minecraft runtime have not been validated here.

## Next bounded stage

Wire this adapter to a lifecycle-managed local backend and the Accounts screen,
including local credential-file selection. Package and test a Windows executable
before removing the legacy launch requirement. Then implement a visible IFrame
surface with normal YouTube controls and pause when the surface is hidden.
Do not route YouTube through the legacy `/audio` decoder.

Google OAuth Testing sessions can expire despite refresh support. Show login only
for absent/revoked sessions; temporary network errors must remain retryable.
Do not promise YouTube watch history, unrestricted quotas or personalized radio.
