# Security Policy

## Frontend session storage trade-off

The PWA persists the session token in `localStorage` by default so that login
survives reloads, frontend version updates, and closing/reopening an installed
PWA (unchecking "Remember me" downgrades to tab-scoped `sessionStorage`). This
is a **frontend-only** persistence fix and deliberately does **not** change the
backend auth contract.

Trade-off: a token in `localStorage`/`sessionStorage` is readable by JavaScript,
so it is exposed to XSS. This is mitigated today by a strict Content Security
Policy (script/media/connect allow-lists, inline-script hashes) and token
redaction in logs. The longer-term, more secure design is to move the session
secret out of JS reach entirely — an `HttpOnly; Secure; SameSite` cookie or a
backend-managed session — which would require a backend auth-contract change and
is out of scope for this frontend persistence work.

Startup validation re-checks the persisted token against `/Users/Me`; the
backend guarantees that missing/invalid/expired/revoked tokens return **401**
(which clears auth and routes to login) while permission failures return **403**
(which does not), so a broken token can never leave the user stuck in a
logged-in-but-unauthorized state.
