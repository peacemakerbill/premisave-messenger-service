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
* **Recording.** LiveKit can record with its Egress service. It is not set up here.
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
