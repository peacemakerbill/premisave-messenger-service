# Calls

Audio and video calls for Premisave Messenger: one to one, group, and call links, with screen sharing, a chat inside the
call, adding people during a call, raise hand and reactions, host controls and call history.

## How it fits together

```
 Flutter app  --REST/STOMP-->  messenger-service  --signs tokens, admin API-->  LiveKit server
      |                                                                              |
      +----------------------- audio, video, screen share (WebRTC) -----------------+
```

* **LiveKit** is a self-hosted media server (an SFU). It carries every audio, video and screen-share stream. It runs
  next to the messenger; see [Setting up LiveKit](#setting-up-livekit).
* **messenger-service** decides who may call whom, rings people, hands out join tokens, keeps the list of who is in
  each call, applies host controls, writes the call into the chat and keeps the history. It never touches media.
* **The app** asks the messenger to start or join a call, gets an address and a token back, and connects straight to
  LiveKit with them.

A join token is a short HS256 JWT signed with the LiveKit secret. The person's identity in the room is their user id.

## What a call looks like

1. Person A taps the call button in a chat: `POST /api/calls { chatId, type }`. The messenger creates the call, marks A as
   in it and rings everyone else (the other person in a private chat, up to `CALLS_RING_LIMIT` members of a group).
   A's app connects to LiveKit with the returned `url` and `token`.
2. Each person who is rung gets `INCOMING` on `/user/queue/calls`. Their app sends `/app/call.ringing` so A's screen can say
   "Ringing". They answer with `POST /api/calls/{id}/join` or say no with `POST /api/calls/{id}/decline`.
3. When a second person is in, the call is `ACTIVE` and the clock starts. Anyone in the call can add more people
   (`/invite`). Hosts can mute, remove, lock and make co-hosts.
4. People leave with `/leave`. A one to one call ends when either person hangs up. A group call ends when the last person
   leaves, or when a host ends it. If the host leaves, the role passes to a co-host, or to whoever has been there longest.
5. Every call leaves a line in its chat (`messageType: CALL`, with a `call` summary), updated as the call changes, and an
   entry in each person's call history.

Statuses: `RINGING`, `ACTIVE`, `WAITING` (open with one person or nobody: a group call nobody answered, an unused link),
`ENDED`, `MISSED`, `DECLINED`, `CANCELLED`.

A person can be in one call at a time (`409 ALREADY_IN_CALL`). A private call to someone who is already in a call does not
ring: the caller gets `reason: "BUSY"` and the other person gets a missed-call notification.

## Things the messenger does for you

| | |
|---|---|
| Missed calls | People who did not answer in `CALLS_RING_TIMEOUT_SECONDS` (or the caller hung up first) get a `MISSED_CALL` notification and their history shows it as missed. No email is sent. |
| Calling at the same time | If two people call each other at the same moment, or someone calls a chat that already has a call running, they end up in one call. |
| Dropped connections | LiveKit tells the messenger when someone's connection drops (webhook), and every 20 seconds the messenger compares each call with who LiveKit says is really there. Nobody stays "in a call" after their app crashed. |
| Group calls | Rings up to `CALLS_RING_LIMIT` members. Everyone else in the group sees the running call in the chat (`GET /api/calls/active?chatId=`) and can join. |
| Call links | `POST /api/calls/links` makes a link (valid up to `CALLS_LINK_TTL_HOURS`). Anyone signed in who has it can join, until a host ends the call or the link expires. |
| Chat inside the call | `POST /api/calls/{id}/messages`. Shown live to everyone in the call, kept 30 days. |
| Raise hand and reactions | Done between the apps with LiveKit participant attributes and data messages. The messenger is not involved. |

## Live events (`/user/queue/calls`)

Every event is `{ type, callId, actorId, userId, call, message, at }`. `call` is the whole call as it is now. Apps should
treat it as the truth and redraw from it. `INCOMING` is the one that makes a phone ring; `ENDED` and `PARTICIPANT_MISSED`
make it stop. An `INCOMING` for a call you have already answered on another device is followed by `PARTICIPANT_JOINED`
with your own id as `userId`: stop ringing when you see that.

## Recording and call history

Calls can be recorded and watched later, like Teams. The call chat is also kept, so people can read it after the call.

* **Who can record.** A host or co-host who is in the call. The red "Recording" sign appears for everyone in the call.
* **Who can watch and read.** Everyone who was in the call (people who were only invited, or were removed, cannot).
* **Kept for how long.** Until someone deletes it: the person who started the recording, or a host or co-host of that call.
  The chat messages of a call are kept too (they used to expire after 30 days).
* **The administrators' switch.** An administrator turns recording on or off for everyone from the dashboard (Admin,
  Calls). When it is off, the record button, the "Recording" sign and the recordings list disappear for everyone, the
  recording endpoints refuse, and turning it off finishes any recording that is running. The files stay on disk and come
  back when it is switched on again. `CALLS_RECORDING_ENABLED` only sets the starting value (false until someone decides).

Endpoints (all under `/api/calls`, signed-in users):

| Method and path | What it does |
| --- | --- |
| `POST /{id}/recording/start` | A host or co-host starts recording. |
| `POST /{id}/recording/stop` | A host or co-host stops it. The video is ready a few seconds later. |
| `GET /{id}/recordings` | The recordings of the call, newest first. Each ready one has a `playUrl` (a signed link that needs no header, with Range support). |
| `DELETE /{id}/recordings/{recordingId}` | Deletes a recording and its file. |
| `GET /{id}/messages?limit=` | The call chat, up to 1000 messages, also after the call ended. |
| `GET /api/call-settings` | `{ recordingEnabled, canManage, storageMode, storageReady }`, and for administrators also `storageLocation`, `storageProblem` and `storageFreeMb`. |
| `PUT /api/call-settings/recording` | Administrators only. Body `{ "enabled": true }`. |

New live events: `RECORDING_STARTED`, `RECORDING_STOPPED` and `RECORDING_SETTING`. Every `call` object carries
`recordingEnabled`, `recording` (a recording is running now) and `recordingCount` (finished recordings).

### Setting up recording

Recording is done by LiveKit Egress, the second service in `deploy/livekit/docker-compose.yml`.

1. **Redis.** Egress and the LiveKit server talk through Redis. The compose file points both at `localhost:6379`, the Redis the
   messenger already uses. Change `LIVEKIT_REDIS_ADDRESS` (and `LIVEKIT_REDIS_PASSWORD`) if yours is elsewhere. LiveKit will not
   start when that Redis cannot be reached.
2. **A folder for the videos**, writable by the recorder (it runs as an unprivileged user):
   ```bash
   cd deploy/livekit
   mkdir -p recordings && chmod 777 recordings
   ```
   The messenger reads the same folder through `CALLS_RECORDINGS_DIR` (default `deploy/livekit/recordings`, relative to where
   the messenger runs). Elsewhere, set `CALLS_RECORDINGS_HOST_DIR` for Docker and `CALLS_RECORDINGS_DIR` for the messenger to the same
   folder. Inside the container the folder is `/out` (`CALLS_RECORDINGS_EGRESS_DIR`).
3. **Start it:** `docker compose --env-file ../../.env up -d`, then restart the messenger. Check `docker logs premisave-egress`.
4. **Switch it on** as an administrator, in the app: Admin, Calls, Call recording.

The recorder runs a headless browser, so each recording uses a good part of a CPU core. Videos are MP4, about 0.5 to 1 GB
per hour at the default quality. Plan disk space, because recordings are never deleted automatically.

### Where recordings are stored

Videos are kept in a folder. That folder is either on the server (LOCAL, the default) or on a network share such as a NAS
(NETWORK). Both the recorder and the messenger must use the same folder.

| | LOCAL | NETWORK |
| --- | --- | --- |
| Messenger | `CALLS_RECORDINGS_MODE=LOCAL`, `CALLS_RECORDINGS_DIR=/srv/premisave/recordings` | `CALLS_RECORDINGS_MODE=NETWORK`, `CALLS_RECORDINGS_NETWORK_MOUNT_PATH=/mnt/premisave-nas`, `CALLS_RECORDINGS_NETWORK_DIRECTORY=premisave-recordings` |
| Recorder (`deploy/livekit`) | `CALLS_RECORDINGS_HOST_DIR=/srv/premisave/recordings` | `CALLS_RECORDINGS_HOST_DIR=/mnt/premisave-nas/premisave-recordings` |

For NETWORK, mount the share on the host first, the same way as for attachments (the service never mounts anything itself).
An SMB share on a Synology NAS, in `/etc/fstab`:

```
//192.168.1.50/premisave  /mnt/premisave-nas  cifs  credentials=/root/.smbcredentials,uid=1000,gid=1000,file_mode=0666,dir_mode=0777,_netdev,nofail  0  0
```

or NFS: `192.168.1.50:/volume1/premisave  /mnt/premisave-nas  nfs  defaults,_netdev,nofail  0  0`. Then
`sudo mkdir -p /mnt/premisave-nas/premisave-recordings && sudo chmod 777 /mnt/premisave-nas/premisave-recordings` and start the
recorder with `CALLS_RECORDINGS_HOST_DIR=/mnt/premisave-nas/premisave-recordings`. The recorder writes there as an unprivileged
user, so the folder must be writable by everyone (or by the user the share maps to).

**Choosing the folder from the dashboard.** The `.env` values above are the starting point. An administrator can also open
Messenger settings, Storage (Flutter app) or Settings, Storage (browser client) and choose a subfolder inside the recorder's
folder and the free-space limit. The recorder itself can only write to its own folder, which Docker fixes with
`CALLS_RECORDINGS_HOST_DIR`, so the dashboard cannot move it somewhere else. Videos made after the change go to
`<subfolder>/<callId>/<recordingId>.mp4` inside that folder, and earlier videos stay where they are and keep playing. "Reset to
.env" goes back to the plain folder and `CALLS_RECORDINGS_MIN_FREE_MB`. See the README, section "Storage from the dashboard".

Safety checks, both on by default:

* **The share must really be mounted.** With `CALLS_RECORDINGS_NETWORK_REQUIRE_MOUNT=true` (recommended), starting a recording is
  refused unless the mount path is on a network file system (cifs, nfs, ...). If the share drops, nothing is recorded to the
  empty mount folder on the local disk. The messenger answers 503 "Recording storage is not available right now" and the
  administrators see the reason in the app (Admin, Calls).
* **Enough free space.** Recording is refused when less than `CALLS_RECORDINGS_MIN_FREE_MB` (500) are free. Set 0 to turn it off.

The check covers the messenger's view of the folder. The recorder is a separate container: start it only after the share is
mounted (`nofail` and `_netdev` in fstab keep the machine booting when the NAS is away), or Docker will create an empty folder
at that path on the local disk and the recorder will write there.

With the messenger in Docker (the compose files in this repository), the folder is bind mounted into the container at
`/data/recordings`. In NETWORK mode set `CALLS_RECORDINGS_MODE=NETWORK` and `CALLS_RECORDINGS_HOST_DIR` to the share's folder on the
host; the compose files already point the mount path at `/data/recordings`. The mount check works through the bind mount.

Several messenger copies, or the messenger on another machine than the recorder, need NETWORK storage that every one of them
mounts at the same place.

Webhooks: the LiveKit server also reports `egress_started`, `egress_updated` and `egress_ended` to the same
`/api/calls/livekit-webhook`. If one is lost, a check every 30 seconds looks at the file and settles the recording.

## Setting up LiveKit

You need a Linux server (or your own machine for development) with Docker.

1. **Pick a key and a secret.** The secret must be at least 32 characters.
   ```bash
   openssl rand -hex 32     # use the output as LIVEKIT_API_SECRET
   ```
   Put both in the messenger's `.env`:
   ```
   LIVEKIT_API_KEY=premisave
   LIVEKIT_API_SECRET=<the long value>
   LIVEKIT_URL=ws://localhost:7880
   LIVEKIT_API_URL=http://localhost:7880
   ```
2. **Start LiveKit** from `deploy/livekit`:
   ```bash
   cd deploy/livekit
   docker compose --env-file ../../.env up -d
   docker compose logs -f livekit
   ```
3. **Restart the messenger.** With a key and secret set, calls switch on. With them empty, every call endpoint answers
   `503 CALLS_NOT_CONFIGURED`.
4. Check it: `curl http://localhost:7880` answers `OK`.
5. **Redis.** LiveKit needs a Redis at `LIVEKIT_REDIS_ADDRESS` (default `localhost:6379`) and does not start without one. The Redis
   that the messenger's own `docker compose up` starts is not published outside Docker. A Redis installed on the computer
   works, and LiveKit can share it. Otherwise start a small one for LiveKit, but only when nothing listens on 6379 yet
   (a second one cannot take the port and stays in the `Created` state; remove it with `docker rm -f premisave-livekit-redis`):
   ```bash
   ss -ltn | grep -q ':6379 ' || docker run -d --name premisave-livekit-redis --restart unless-stopped -p 127.0.0.1:6379:6379 redis:7-alpine
   ```

### Day to day

Run these in `deploy/livekit`.

| Command | What it does |
| --- | --- |
| `docker compose ps` | Both `premisave-livekit` and `premisave-egress` should be Up |
| `docker compose logs -f livekit egress` | Follows both logs |
| `docker compose --env-file ../../.env up -d --force-recreate` | Applies a changed `.env`. A plain `up -d` keeps the old settings |
| `docker compose pull` then `up -d` | Updates the images |
| `docker compose down` | Stops both. Recordings stay in their folder |
| `docker inspect premisave-egress --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'` | Shows which folder the recorder writes to. It must be the folder the messenger reads |

### Trying it on your own machine

* **Chrome on the same machine** works with `ws://localhost:7880`: browsers treat `localhost` as secure, so the camera and
  microphone are allowed.
* **A phone on your Wi-Fi** needs your computer's address: `LIVEKIT_URL=ws://192.168.x.x:7880`. The Android emulator
  reaches your computer as `10.0.2.2`; the Premisave app maps `localhost` to that for you.
* **Chrome on another device** needs `https` and `wss`: browsers only allow the camera on secure pages. Use a real domain.

### Production

* Give LiveKit its own domain (for example `live.premisave.com`) and put a TLS proxy (Caddy or nginx) in front of port
  7880, so the apps connect to `wss://live.premisave.com`. Set `LIVEKIT_URL` to that, and keep `LIVEKIT_API_URL` pointing at
  the server directly (`http://localhost:7880` when they share a machine).
* Open the firewall for `7881/tcp` and `50000-50200/udp`. Set `LIVEKIT_USE_EXTERNAL_IP=true` when the server sits behind
  a public IP it does not know about (most cloud servers).
* Many mobile networks and offices block UDP. Turn on LiveKit's built-in TURN server (see the LiveKit docs, "turn" in the
  config) with a domain and certificate, so calls still connect for them.
* The webhook goes from LiveKit to the messenger (`LIVEKIT_WEBHOOK_URL`, default
  `http://localhost:8081/api/calls/livekit-webhook`). It is signed with the secret, so it can safely be reachable from the
  network. If it cannot be reached, calls still work: the 20 second check does the same job a little later.
* One LiveKit server handles many calls. Past a few hundred people at once, run several with Redis, as the LiveKit docs
  describe. The messenger itself can run as several instances: events go through the same Redis relay as messages.

### Windows and macOS with Docker Desktop

`network_mode: host` does not give the container the real network there. Replace it with port mappings:
`7880:7880`, `7881:7881` and `50000-50200:50000-50200/udp`, and set `LIVEKIT_USE_EXTERNAL_IP=false` and
`rtc.node_ip` to your computer's LAN address in the config. It is simpler to run the server on a Linux machine.

## What is not included

* **Ringing when the app is closed.** The phone rings only while the app is running and connected (live events). Ringing a
  closed app needs push notifications (Firebase Cloud Messaging on Android, Apple push and CallKit on iPhone), which need
  accounts and keys of your own. The missed-call notification still arrives when they open the app.
* **A waiting room.** A host can lock a call, which keeps new people out.

## Troubleshooting

| Problem | Likely cause |
|---|---|
| Every call request answers `503 CALLS_NOT_CONFIGURED` | `LIVEKIT_API_KEY` or `LIVEKIT_API_SECRET` is empty, or the secret is under 32 characters. |
| The call connects then drops at once, or the app says "invalid token" | The messenger and LiveKit have different keys or secrets. Both read them from `.env`; restart both after changing it. |
| Connects but there is no sound or picture | UDP is blocked. Check the firewall for `50000-50200/udp` and `7881/tcp`, set `LIVEKIT_USE_EXTERNAL_IP=true` on a cloud server, and consider TURN. |
| Chrome says the camera is blocked | The page is not secure. Use `localhost` or `https`/`wss`. |
| Hosts cannot mute or remove people | The messenger cannot reach `LIVEKIT_API_URL`. The call record still changes; check the logs for "LiveKit RemoveParticipant failed". |
| People stay in a call after closing the app | The webhook is not arriving and the 20 second check cannot reach LiveKit either. Check `LIVEKIT_API_URL`. |

### Recording does not work

* **"The recorder is not available".** The Egress container is not running: `docker ps` and `docker logs premisave-egress`.
  The usual causes are a Redis that is not reachable, or a different Redis than the LiveKit server uses.
* **The recording ends as "failed" with "video file was not found".** The recorder and the messenger are not looking at the same
  folder, or the folder is not writable. Run `ls -l deploy/livekit/recordings` after a short recording: the file should be there.
* **Egress logs a Chrome sandbox error.** Add `cap_add: [SYS_ADMIN]` to the `egress` service.
* **Recording answers 503 "Recording storage is not available right now".** In NETWORK mode the share is not mounted (check `mount | grep premisave` and `/etc/fstab`), or the free space is below `CALLS_RECORDINGS_MIN_FREE_MB`. The messenger log has the exact reason, and so does the
  administrators' call settings screen.
* **No record button.** An administrator has to switch recording on first, and the call must have a host or co-host (you are the
  host of a call you start).
