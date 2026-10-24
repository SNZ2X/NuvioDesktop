# Desktop Live TV

Open **Live TV** from the desktop sidebar or top navigation bar. **Manage
sources** lets you add, edit, enable, disable or remove a source for the current
profile. Source configuration stays on this computer and is not account-synced.

## Adding a source

- **M3U URL:** supply the URL of a channel playlist, including any query
  parameters required by your provider. Playlist and stream URLs use HTTP(S).
- **M3U file:** select a local channel playlist. Relative web addresses in a
  remote playlist resolve against its final URL after redirects.
- **Xtream Codes:** enter the provider's HTTP(S) host/base path, username and
  password separately. Only live channels are imported.

Give each source a recognizable name. Source lists display that name instead of
its URL or credentials. A source can be disabled without deleting its settings.
Use **Refresh** after changing a playlist or when channel/guide data needs updating.

A channel playlist is different from the HLS media playlist for one stream. HLS
segment files are not imported as separate TV channels.

## Programme data

An optional XMLTV URL or local XMLTV file supplies programme data. Gzip-compressed
XMLTV files are supported. Sources can also advertise XMLTV through an M3U header
or Xtream endpoint. An explicitly configured guide takes precedence.

Channel `tvg-id`/Xtream EPG IDs match XMLTV programme channel IDs within the source.
Two sources can reuse the same EPG ID without sharing guide entries. Programmes
use their XMLTV timezone offsets. Missing guide data does not prevent playback.

For example, a synthetic playlist entry can contain:

```m3u
#EXTM3U url-tvg="https://provider.example/guide.xml"
#EXTINF:-1 tvg-id="example-news" tvg-logo="https://provider.example/news.png" group-title="News",Example News
https://provider.example/live/news.m3u8
```

The `.example` addresses above are placeholders. The app does not supply an IPTV
subscription, provider account or channel playlist.

## Browsing and playback

Search channel names and filter by source or group. Channel rows show current
and upcoming programmes when available; **Guide** shows programme times and
details. Selecting a channel opens the existing native player. Channel URLs and
headers are captured at launch; source edits apply on the next launch.

Live sessions show a **LIVE** indicator and omit VOD resume, watched-state,
scrobbling, seeking, skip intervals and episode automation. Pause, volume,
tracks, fullscreen, retry and back controls remain available. Previous/next
channel controls, including Page Up/Down, cycle through the filtered channel
list used to start playback. A single-channel list has no switching controls. If native shutdown fails, playback
Retry is withheld while its host is retained; try Back again or restart the app.

## Local storage

The entire saved source configuration is encrypted with AES-GCM under the
platform app-data directory's `livetv` folder. A random local key and source
files use restrictive permissions. Keep the key with the encrypted data if
making a manual backup; losing the key prevents decryption.

This uses a local key file, not an operating-system credential vault. Another
process running as the same user can read both the key and source files. The
encryption protects against accidentally exposing the source file by itself.
Account-data reset clears IPTV configuration and invalidates loaded repositories.

Fetching and parsing are bounded: channel playlists are limited to 16 MiB;
Xtream JSON catalogs and decoded XMLTV data to 64 MiB. XML external entities and DTDs are
disabled. Provider failures are shown without raw credentialed URLs.

## Contribution status

This is a prepared contribution on a feature branch. Upstream submission still
requires the maintainer approval described in `CONTRIBUTING.md`. Build, test,
review and platform coverage are recorded in `IPTV_CONTRIBUTION.md`.
