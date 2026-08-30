# The WebSocket transcript contract

Extracted from a running gateway, not from documentation. This is what the next
change implements: driving a conversation over the dashboard's `/api/ws` bridge
instead of `POST /v1/runs`, so the app renders the transcript the desktop
renders.

## Why the HTTP route cannot show reasoning

`/v1/runs` has no thinking channel. Its only reasoning-shaped event is
`reasoning.available`, and that is not thinking:

```python
# agent/conversation_loop.py — fires once an assistant message is COMPLETE
_think_text = assistant_message.content.strip()
_think_text = re.sub(r'</?(?:REASONING_SCRATCHPAD|think|reasoning)>', '', _think_text)
agent.tool_progress_callback("reasoning.available", "_thinking", _think_text[:500], None)
```

It carries the assistant message's own text, truncated to 500 characters, after
the same text has already streamed as deltas. The callback exists to relay a
subagent's progress to a parent display.

Three attempts to separate this echo from genuine narration failed. They could
not work: the relay strips reasoning tags that the streamed copy keeps, so the
two strings are not comparable. The app now drops the event entirely on this
route rather than guessing.

## What the socket carries

Frame envelope, from `tui_gateway/server.py:_event_frame`:

```json
{"jsonrpc": "2.0", "method": "event",
 "params": {"type": "<event>", "session_id": "<sid>", "payload": { }}}
```

| Event | Payload | Becomes |
|---|---|---|
| `thinking.delta` | `{text}` | Reasoning, streaming — **the real thinking channel** |
| `reasoning.delta` | `{text}` | Reasoning, streaming |
| `reasoning.available` | `{text, verbose?}` | Reasoning, complete |
| `message.start` | — | Opens an assistant item |
| `message.delta` | `{text}` | Appends to it |
| `message.complete` | `{text, status}` | Closes it |
| `tool.start` | `{tool_id, name, context, args, args_text}` | ToolCall, running |
| `tool.complete` | `{tool_id, name, args, duration_s, result / result_text, summary, todos?, inline_diff?}` | ToolCall, finished |
| `approval.request` | `{command, choices, smart_denied, …}` | Approval sheet |
| `status.update` | `{kind, text}` | Status line |
| `error` | `{message}` | Failure item |

`tool.complete` carries the tool's result, which the HTTP route never sends —
the reason a successful call had nothing to show but a status.

## Driving a turn

A live gateway session is required; the app's sessions are database rows.

```
session.resume {session_id: <stored id>}   → result.session_id is a NEW live id
prompt.submit  {session_id: <live id>, text}
   … events stream on the same socket …
approval.respond {session_id: <live id>, …}
session.close  {session_id: <live id>}
```

`session.resume` returning a different id from the one passed in is the trap:
calling `session.compress` with the stored id answers `session not found`. Same
applies here.

Verified end to end against a running gateway during the `/compress` work: 41
stored messages became 13, and the change was visible through the gateway's own
messages route afterwards.

## Constraints

- **Needs the dashboard**, like Projects. Without it the app stays on
  `/v1/runs`, which means no reasoning — that is a property of the route, not a
  setting.
- The socket is per-run rather than persistent, matching how the projects calls
  use it; a run holds it open for the turn's duration.
- Approvals must arrive on whichever transport started the run. Mixing them is
  how an approval would be answered against a session that never asked.
