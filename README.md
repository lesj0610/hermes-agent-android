# Hermes Agent for Android

An unofficial Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent).

Not affiliated with or endorsed by Nous Research. It talks to a Hermes Agent
gateway that **you** run, over a network path **you** control. There is no
service behind this app — no relay, no proxy, no account, no telemetry.

## What it does

Streams an agent run to your phone or tablet and lets you answer tool-approval
requests from a notification, so a run does not sit blocked while you are away
from the machine.

| | |
|---|---|
| Chat | Live transcript with streamed reasoning, tool cards and results, approval sheet. The send button becomes Stop while a run is in flight, and Send again the moment you type |
| Composer | Camera, photo and text-file attachments; model and reasoning level; dictation; spoken conversation |
| Commands | Typing `/` opens the gateway's own registry: skills and quick commands run on the agent, read-only queries answer inline, `/compress` compacts the conversation. What has no server-side action is listed and marked, not hidden |
| Sessions | Drawer list with search, and a per-session menu: rename, pin, copy ID, branch, export, archive, delete |
| Artifacts | The images, files and links the recent runs produced, gathered from the session transcripts |
| Projects | The desktop's named multi-folder workspaces — read and written server-side, not stored on the phone (needs the optional dashboard) |
| Settings | One hub: connection, dashboard, gateway state, toolsets, profiles, skills, model, display, language, notifications, permissions |
| Phone | Single pane with a drawer over it |
| Tablet | Three columns: the drawer docks as the left one, with a rail and a status bar; the dividers drag |
| Languages | English, Korean — shipped by the app, independent of the agent's own locale support |

## What it talks to

| Server | Used for | Auth |
|---|---|---|
| Gateway `api_server` (default 8642) | Sessions, artifacts, and turns when no dashboard is configured | Bearer token over HTTP + SSE |
| Dashboard (optional, default 9119) | Turns with reasoning, projects, slash commands, browsing the agent host's folders | Password login, then a one-shot ticket per socket |

No patches to the agent and no companion server: the app is written against
what these two already expose.

## Requirements

- A running Hermes Agent gateway with the `api_server` platform enabled and
  `API_SERVER_KEY` set
- Any network path from the device to that gateway — a VPN or mesh tunnel, a
  LAN, or a TLS reverse proxy on a public name. The app does not prescribe one;
  it accepts whatever address you give it
- Android 8.0 (API 26) or newer
- For projects only: the Hermes dashboard, reachable and signed in

The app will connect over plain `http://` and tells you in Settings when it is
doing so, because in that case the confidentiality of the traffic comes from
the network rather than from the connection. Whether that is acceptable depends
on which of the paths above you chose, which is why it is your call and not a
setting baked into the build.

## Limits worth knowing

These are properties of the surfaces the app is built on, not oversights:

- **`max` and `ultra` need a gateway from 2026-08-19 or later.** Older API
  servers validate reasoning efforts against a set that predates those two and
  drop what they do not recognise, so the run quietly uses the default instead.
  There is no capability flag for it, so the app cannot detect it and does not
  pretend to.
- **Artifacts that live on the agent's host cannot be opened here.** A path in a
  transcript names a file on that machine, and the gateway serves no file route.
  Those are shown with their path and copy on tap; links and remote images open.
- **The artifact scan reads the most recent sessions, not all of them.** Each one
  is a separate request; the screen says how many it read.
- **Voice is on-device and half duplex.** The gateway has no audio surface, so
  nothing recorded leaves as audio, and the app listens or speaks, not both.
- **Some slash commands only exist in the desktop's own UI.** `/help`, `/tools`
  and the like are screens the desktop draws; the gateway answers "not a
  quick/plugin/bundle/skill command" because there is nothing on the server to
  run. Skills, quick commands and plugin commands do run, and so does
  `/compress`.
- **Reasoning needs the dashboard.** A turn runs over the gateway's event
  socket when one is configured, which is where streamed thinking and tool
  results live. Without it the app falls back to `/v1/runs`, which has no
  thinking channel at all — that is a property of the route, not a setting. An
  attachment also keeps the turn on HTTP, since the socket's submit takes text.
  See [docs/ws-transcript-contract.md](docs/ws-transcript-contract.md).
- **Deleting a session is permanent.** It is the same call the desktop's Delete
  makes: the row, its messages and the transcript files on the agent's host all
  go. Archive is the reversible one.

## Build

```bash
source ./env.sh          # sandboxed SDK/Gradle paths, no global install
./gradlew assembleDebug
```

`./sdk.sh sdk list` manages SDK packages.

Both scripts keep the SDK, the Gradle home and the Android CLI's own state
outside the home directory, so nothing is installed system-wide and removing
three directories removes the toolchain. Edit the paths at the top of `env.sh`
and `sdk.sh` to point at wherever you want them.

One trap worth knowing if you change them: the `android` CLI reads
`ANDROID_USER_HOME` while AGP reads `ANDROID_PREFS_ROOT`, and exporting both in
the same process makes AGP fail with
`Could not create provider for value source AndroidLocationsBuildService`.
That is why the two scripts exist separately instead of one shared env file.

Screens render to PNG on the JVM without a device or emulator:

```bash
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
# app/build/outputs/roborazzi/
```

Release signing reads `keystore.properties`, which is not in the repository and
must not be — the release build simply goes unsigned without it.

## Documents

| File | Contents |
|---|---|
| [DESIGN.md](DESIGN.md) | Backend contract, event mapping, architecture, the constraints this project refuses to break |
| [design/mockup.html](design/mockup.html) | Screen mockups |
| [docs/ws-transcript-contract.md](docs/ws-transcript-contract.md) | The socket event contract, and why reasoning needs it |

## License

Apache License 2.0 — see [LICENSE](LICENSE), [NOTICE](NOTICE) and
[THIRD-PARTY.md](THIRD-PARTY.md).

The launcher icon is the Hermes Agent desktop icon, redistributed under that
project's MIT licence. Everything else here is original.

Chosen over MIT for two clauses that matter to a project like this one: §6 says
plainly that the licence grants no trademark rights, and §3 carries a patent
grant that terminates on patent litigation. Nothing here is derived from the
Hermes Agent source; the client was written against its HTTP surface.

## Status

Version 1.6. Compiles, unit tests pass, lint clean, release AAB builds, and the
app runs against a live gateway.

Not yet exercised on hardware: the camera and file attachment round trip, the
voice controls, and the socket transport — its event mapping was built against
a captured live run, but the app has not yet driven one.
