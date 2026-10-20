# TV remote API (v1)

The launcher on the Fire TV (`dev.bhawesh.tvhome`) serves this API on the LAN. The phone app
(`phone/`) is its only client. JSON in and out, UTF-8.

## Finding the TV

- mDNS / DNS-SD service type `_tvhome._tcp.`, instance name = TV name (e.g. `Living Room TV`),
  port `8090`, TXT `v=1`.
- Manual fallback: the user types the TV's IP; base URL `http://<ip>:8090`.

## Pairing

Every endpoint except the two below needs `Authorization: Bearer <token>`; without it: `401 {"error":"unpaired"}`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/api/pair/request` | `{"device":"Pixel 8"}` | `{"ok":true}`; the TV shows a 4-digit PIN for 2 minutes. Too soon after the last one: `429 {"error":"pair_cooldown","retry_after_s":30}` |
| POST | `/api/pair/confirm` | `{"pin":"4821"}` | `{"token":"<32 hex>","name":"Living Room TV"}` or `403 {"error":"bad_pin"}` |

Tokens don't expire; the phone stores its token per TV.

A PIN takes 3 wrong guesses, then it's gone and the phone has to ask for a new one. A new PIN
comes at least 30 s after the last request or wrong guess; every 3 wrong guesses double that wait,
up to an hour, until a phone pairs.

## State

| Method | Path | Response |
|---|---|---|
| GET | `/api/info` | `{"name":"Living Room TV","model":"Fire TV Stick (3rd gen)","version":"1","keys":"ready"\|"needs_bootstrap"}` |
| GET | `/api/keys/bootstrap` | `{"adb_port":5555,"cmd":"pkill -f …; CLASSPATH=… nohup app_process … &"}` |
| GET | `/api/now` | `{"pkg":"com.netflix.ninja","label":"Netflix","playing":true}` (`playing` = media audio active) |
| GET | `/api/apps` | `[{"pkg":"com.netflix.ninja","label":"Netflix","color":"#E50914","pinned":true}]` (pinned = on the TV shelf, in shelf order first) |
| GET | `/api/apps/<pkg>/banner.png` | PNG, the app's TV banner (320x180) |

`keys` in `/api/info`: whether key presses and typing work. They go through a small injector
process that must run as the shell user, and Fire OS refuses adb connections made from the stick
itself, so **the phone starts it**: when `keys` is `needs_bootstrap` (first use, and after every TV
reboot), the phone connects to the TV's network adb (`<tv ip>:<adb_port>`, standard ADB protocol
with its own RSA key, auth TOKEN/SIGNATURE/RSAPUBLICKEY), opens `shell:<cmd>` with `cmd` from
`/api/keys/bootstrap`, reads until the stream closes, then re-checks `/api/info` (ready within ~1 s).
The first time, the TV shows Android's "Allow USB debugging?" for the phone's key; the user ticks
"Always allow" and presses OK on the TV remote. Key/text calls return `503 {"error":"needs_bootstrap"}`
until then. Launch/open/play don't need it.

## Control

All return `{"ok":true}` or `{"ok":false,"error":"<reason>"}`.

| Method | Path | Body |
|---|---|---|
| POST | `/api/key` | `{"key":"up","long":false}` |
| POST | `/api/text` | `{"text":"stranger things","submit":false}`; types into the focused field |
| POST | `/api/launch` | `{"pkg":"com.netflix.ninja"}` |
| POST | `/api/open` | `{"url":"https://www.youtube.com/watch?v=...","pkg":"com.amazon.firetv.youtube"}` |

Keys: `up down left right select back home menu play_pause rewind fast_forward next previous
volume_up volume_down mute`.

## Content

A **title**:

```json
{"id":"tv:66732","title":"Stranger Things","year":2016,"kind":"tv",
 "overview":"…","rating":8.6,"genres":["Sci-Fi"],
 "poster":"https://image.tmdb.org/t/p/w342/....jpg","backdrop":"https://image.tmdb.org/t/p/w780/....jpg",
 "offers":[{"pkg":"com.netflix.ninja","label":"Netflix","url":"https://www.netflix.com/title/80057281"}]}
```

`offers` only lists apps installed on this TV; `url` may be absent (then opening = the app's search).

| Method | Path | Body / query | Response |
|---|---|---|---|
| GET | `/api/search?q=stranger%20things` | | `[title, …]` best match first, max 10 |
| GET | `/api/recs` | | `[title, …]` trending on the user's apps (what the TV home shows) |
| GET | `/api/recent` | | `[title or {"pkg","label"} …]` recently opened from TV home or phone |
| POST | `/api/play` | `{"title":"Stranger Things","pkg":null,"kind":null,"id":null}` | `{"ok":true,"opened":{"title":"Stranger Things","pkg":"com.netflix.ninja","how":"deeplink"\|"search"\|"app"}}` or `{"ok":false,"error":"not_found"\|"not_on_your_apps","candidates":[title…]}` |

`/api/play` resolves the title (by `id` if given), picks the offer (`pkg` if given, else the first
installed one), opens it, and records it in `/api/recent`.

## Wallpapers

Pictures on the TV, in `/sdcard/Pictures/tvhome`: the Photos screensaver shows all of them, and one
can be the home's background (Customise home → Background → Wallpaper).

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/api/wallpapers` | | `{"items":[{"name":"up-20261019-231500.jpg","bytes":402113,"modified":1792432500000,"uploaded":true}…],"background":"up-…jpg"\|null,"look":5}` newest first |
| GET | `/api/wallpapers/thumb?name=<name>` | | JPEG, 320x180 |
| POST | `/api/wallpapers` (`?use=background` to also set it) | the image file itself (JPEG, PNG, WebP or HEIF), up to 25 MB, with `Content-Length` | `{"ok":true,"name":"up-….jpg"}` or `400 {"error":"not_an_image"}` |
| DELETE | `/api/wallpapers?name=<name>` | | `{"ok":true}`; if it was the background, the home goes back to Live sky |
| POST | `/api/wallpapers/background` | `{"name":"up-….jpg"}` or `{"name":null}` | `{"ok":true}`; the home switches at once |

The TV turns each upload upright (EXIF), crops it to 16:9 from the centre and stores a 1920x1080
JPEG, whatever size the phone sent. The upload is streamed to a temp file, never held whole in memory.

## Assistant actions (phone side)

The phone's on-device model turns speech/text into exactly one of these (grammar-constrained JSON),
which the phone then maps to the calls above:

```json
{"action":"play","title":"Stranger Things","app":"netflix"}
{"action":"launch","app":"youtube"}
{"action":"keys","keys":["down","down","select"]}
{"action":"type","text":"lofi beats","submit":true}
{"action":"search","query":"new malayalam thrillers"}
{"action":"reply","text":"I can't do that yet."}
```

`app` is a lowercase short name the phone maps to a package using `/api/apps` labels
(netflix, prime, hotstar, youtube, jiocinema, apple tv, zee5, sonyliv, crunchyroll).
