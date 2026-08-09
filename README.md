# Hermes Agent for Android

An unofficial Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent).

Not affiliated with or endorsed by Nous Research. It talks to a Hermes Agent
gateway that **you** run, over a network path **you** control. There is no
service behind this app — no relay, no proxy, no account.

## What it does

Streams an agent run to your phone or tablet and lets you answer tool-approval
requests from a notification, so a run does not sit blocked while you are away
from the machine.

| | |
|---|---|
| Backend | Existing gateway `api_server` platform (default port 8642). No patches to the agent, no companion server |
| Transport | Bearer-authenticated HTTP + SSE over a Tailscale tunnel |
| Phone | One pane at a time: sessions → chat → settings |
| Tablet | Desktop-style shell: session rail, transcript, activity rail, bottom status bar |
| Languages | English, Korean — shipped by the app itself, independent of the agent's locale support |

## Requirements

- A running Hermes Agent gateway with the `api_server` platform enabled and
  `API_SERVER_KEY` set
- Tailscale on both the server and the device (MagicDNS name, not a bare
  `100.x` address — see DESIGN.md §3)
- Android 8.0 (API 26) or newer

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

## Documents

| File | Contents |
|---|---|
| [DESIGN.md](DESIGN.md) | Backend contract, event mapping, architecture, the constraints this project refuses to break |
| [PLAY.md](PLAY.md) | Google Play release requirements and open risks |
| [design/mockup.html](design/mockup.html) | Screen mockups |

## Status

Compiles, unit tests pass, lint clean, release AAB builds. **Not yet verified on
a physical device** — rendering, live SSE, and notification actions are
implemented but unexercised.
