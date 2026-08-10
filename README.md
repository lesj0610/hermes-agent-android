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
| Chat | Live transcript over SSE, tool cards, approval sheet, stop |
| Composer | Camera, photo and text-file attachments; model and reasoning level; dictation; spoken conversation |
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
| Gateway `api_server` (default 8642) | Everything above except projects | Bearer token over HTTP + SSE |
| Dashboard (optional, default 9119) | Projects, and browsing the agent host's folders | Password login, then a one-shot ticket for the projects socket |

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

- **Reasoning levels stop at `xhigh`.** The agent defines `max` and `ultra` too,
  but the HTTP route validates against a set that predates them and silently
  drops what it does not recognise, so a run would quietly use the default.
  Offering a control that does nothing is worse than not offering it.
- **Artifacts that live on the agent's host cannot be opened here.** A path in a
  transcript names a file on that machine, and the gateway serves no file route.
  Those are shown with their path and copy on tap; links and remote images open.
- **The artifact scan reads the most recent sessions, not all of them.** Each one
  is a separate request; the screen says how many it read.
- **Voice is on-device and half duplex.** The gateway has no audio surface, so
  nothing recorded leaves as audio, and the app listens or speaks, not both.
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

Version 1.0. Compiles, unit tests pass, lint clean, release AAB builds, and the
app runs against a live gateway.

Not yet exercised on hardware: the camera and file attachment round trip, the
voice controls, and the projects socket.
