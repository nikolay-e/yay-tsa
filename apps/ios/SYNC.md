# Cross-machine workflow: Windows session ↔ Mac session

The user is running two separate Claude Code sessions on two physical machines (a Windows PC and
this Mac), both on the same home Wi-Fi as the home server (`109.230.156.80`). There is no shared
memory or conversation history between the two sessions — this file plus the rest of the repo is
the handoff.

## Primary: Remote Control (claude.ai)

Claude Code has a "Remote Control" feature that can link sessions through the same claude.ai
account, and in principle lets one session's `SendMessage` reach a peer session running on a
different machine. If the user has this enabled on both sides, try it first — it's the live,
low-friction path. This was not verified end-to-end at handoff time (the Mac session didn't exist
yet when this was written), so if it doesn't behave as expected, don't fight it — fall back to the
shared folder below and tell the user what happened.

## Fallback (known-working): shared folder on the home server

A copy of this project already exists at `ilia@109.230.156.80:/home/ilia/dev-shared/yaytsa-ios`
(SSH port `2222`). Two scripts move files between this machine and that shared copy:

```bash
./scripts/sync-push.sh   # local project -> server shared copy (overwrites server's tracked files)
./scripts/sync-pull.sh   # server shared copy -> local project (overwrites local tracked files)
```

Neither script touches `.git` on either end — each machine keeps its own independent local git
history; only source files move. Deletions don't propagate (a file removed on one side isn't
removed on the other automatically) — mention it to the user if that matters for a given change.

### One-time setup needed on this Mac

SSH access to the home server is key-based (no password auth). This Mac doesn't have a key
authorized yet. To get one working:

1. Generate a key: `ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_yaytsa -N ""`
2. Print the public key (`cat ~/.ssh/id_ed25519_yaytsa.pub`) and show it to the user.
3. The user needs to get that public key added to `~/.ssh/authorized_keys` on the home server —
   either by relaying it to the Windows Claude Code session (which already has SSH access and can
   append it), or by adding it themselves if they have another way in.
4. Once authorized, test with:
   `ssh -p 2222 -i ~/.ssh/id_ed25519_yaytsa ilia@109.230.156.80 echo ok`
5. If that works, either add a `Host home` entry to `~/.ssh/config` (`HostName 109.230.156.80`,
   `Port 2222`, `User ilia`, `IdentityFile ~/.ssh/id_ed25519_yaytsa`) so the scripts' default `ssh`
   invocation picks it up via `~/.ssh/config` matching, or export `SYNC_HOST`/`SYNC_PORT` env vars
   if you'd rather not touch the global SSH config.

Until this is wired up, these scripts will fail with a connection/auth error — that's expected, not
a sign something else is broken. Don't spend time debugging the app over a broken SSH link; get
the key authorized first.

## Suggested rhythm

Rather than syncing after every file edit, treat each "handoff" as a unit: finish a coherent chunk
of work, `sync-push.sh`, tell the user it's ready, and let them relay to the other session (or rely
on Remote Control if that's working). Pulling at the start of a work session before making changes
avoids clobbering the other side's in-progress edits.
