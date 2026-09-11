# AMI Escalation Server

Minimal backend the AMI Android app calls when a medicine check-in is missed. It holds
real SMTP and Twilio credentials server-side (never on the device), emails the caregiver
chain, places reminder calls, and tells the app whether those calls were answered.

## Run locally

```bash
cd server
npm install
cp .env.example .env
# edit .env: set SHARED_SECRET and your SMTP credentials
npm start
```

Server listens on `http://localhost:3000` by default.

## Endpoints

All four app-facing endpoints require the `x-ami-shared-secret` header, compared with a
timing-safe comparison against `SHARED_SECRET`.

### `POST /api/escalate` — a missed check-in

```bash
curl -X POST http://localhost:3000/api/escalate \
  -H "Content-Type: application/json" \
  -H "x-ami-shared-secret: <same value as SHARED_SECRET in .env>" \
  -d '{"caregiverEmails":["you@example.com","backup@example.com"],"medicineName":"Morning Pill","message":"test escalation","timestampIso":"2026-01-01T00:00:00Z","urgent":false}'
```

- `caregiverEmails` is an ordered list; the app decides how far down it to go.
  The older single `caregiverEmail` field is still accepted so an app build that has
  not been updated keeps working.
- Recipients are **bcc'd**, so caregivers never see each other's addresses.
- `urgent: true` prefixes the subject with `URGENT — `.
- Addresses are de-duplicated case-insensitively and capped at 10 per request.

### `POST /api/summary` — the weekly digest

Same recipient rules, but the subject and body arrive ready-composed from the app, and
every configured caregiver is mailed rather than only those the ladder reached.

```bash
curl -X POST http://localhost:3000/api/summary \
  -H "Content-Type: application/json" \
  -H "x-ami-shared-secret: <secret>" \
  -d '{"caregiverEmails":["you@example.com"],"subject":"AMI weekly summary","body":"..."}'
```

### `POST /api/call` — a reminder phone call

The middle rung of the escalation ladder. The app rings in-app first; if that goes
unanswered it asks the backend to place a real phone call.

```bash
curl -X POST http://localhost:3000/api/call \
  -H "Content-Type: application/json" \
  -H "x-ami-shared-secret: <secret>" \
  -d '{"phoneNumber":"+14155550123","medicineName":"Morning Pill"}'
```

`phoneNumber` must be E.164 (leading `+` and country code). Without the `TWILIO_*`
values set this returns 503 and the app falls straight through to emailing the
caregiver, so the rest of the check-in still works with calling switched off. On
success it returns `{"ok":true,"sid":"CA…"}`.

### `GET /api/call/:sid/status` — did anyone pick up?

```bash
curl http://localhost:3000/api/call/CA123.../status -H "x-ami-shared-secret: <secret>"
```

Returns `{"sid","status","answered","finished"}`. The app polls this for up to 90
seconds after placing a call, and reports the result in the caregiver email.

Two things to know about `answered`:

- It means **the line was picked up**, not that a person heard the message. Twilio
  cannot tell a human from an answering machine without answering-machine detection.
- It requires `PUBLIC_BASE_URL` to be set (below). Without it Twilio has nowhere to
  report back to, the status stays `queued`, and the app reports the outcome as
  unknown — which it presents to the caregiver as "could not confirm", never as
  "answered".

Outcomes are kept **in memory** for 10 minutes and then discarded. That is deliberate:
the alternative is a database recording which number was rung and when, which this
service has no reason to keep. A restart loses in-flight outcomes, which the app
already handles as unknown. It also means this endpoint assumes a **single instance** —
if you scale to more than one, move `callOutcomes` to shared storage.

### `POST /api/twilio/status` — Twilio's callback (not called by the app)

Authenticated by Twilio's own request signature, not the shared secret: Twilio cannot
send an app header. With no `TWILIO_AUTH_TOKEN` configured the endpoint refuses
everything rather than accepting unsigned POSTs — anyone who guessed a call SID could
otherwise mark a call answered.

## Pointing the Android app at this server

In the app's `local.properties` (never committed):

```
AMI_BACKEND_BASE_URL=http://10.0.2.2:3000/
AMI_ESCALATION_SHARED_SECRET=<same value as SHARED_SECRET in .env>
```

- `10.0.2.2` is the Android **emulator's** alias for your host machine's `localhost` —
  use this only when testing on the emulator.
- For a **physical device on the same Wi-Fi**, use your machine's LAN IP instead
  (e.g. `http://192.168.1.23:3000/`), and make sure your firewall allows inbound
  connections on port 3000.
- Either way, this only works for local testing. A phone off that network
  (cellular data, a different Wi-Fi) **cannot reach a server running on your
  laptop.** For real-world use, deploy this server somewhere reachable from the
  internet — a free tier on Render, Railway, or Fly.io is enough for this
  workload — and point `AMI_BACKEND_BASE_URL` at that public HTTPS URL instead
  (and drop the debug-only cleartext network exception in the Android app once
  you're on HTTPS).

## Deploying

The only deployment-specific setting is `PUBLIC_BASE_URL`, which must be the public
HTTPS origin of this server (e.g. `https://ami-escalation.onrender.com`). It is used
for two things that both break quietly without it: the `statusCallback` URL handed to
Twilio, and the URL the webhook signature is validated against.

`app.set('trust proxy', 1)` is already set, because Render, Railway and Fly all
terminate TLS in front of the app — without it Express would see `http://` and every
Twilio signature check would fail.

## Security notes

- `SHARED_SECRET` is compared with a timing-safe comparison and must match
  exactly between this server's `.env` and the app's `local.properties`.
- Never commit `.env` (already gitignored).
- SMTP and Twilio credentials live only here, never in the Android app.
- Caregiver addresses are bcc'd so a chain never leaks one relative's address to
  another.
