# AMI — Digital Caretaker

A voice-driven Android caretaker for elderly and low-tech-literacy users. AMI reads the
screen and guides the person through tasks one tap at a time, calls them at medicine time
and asks how they are actually feeling, turns those answers into a caretaker report, and
escalates to family when something is wrong.

Full product detail is in [`docs/PRD.md`](docs/PRD.md).

---

## What it does

**Guided navigation.** Say a goal in plain words — *"video call my daughter"* — and AMI
reads the visible screen, asks Gemini what to tap next, and points at it with an on-screen
cursor while narrating each step. It refuses to read banking, payment and authenticator
apps at all, and skips password fields everywhere.

**Medicine check-ins as a phone call.** At each scheduled dose the phone rings with a
full-screen incoming call. AMI asks whether the dose was taken, then asks plainly about
pain or trouble — naming examples, because "how are you?" gets "fine" from everyone.

**A caretaker agent.** Symptoms heard on those calls are counted by day, not just by
mention, and turned into what to eat and how to move. A red-flag symptom or an urgent
vitals reading routes to a human *before* any model call — that ordering is the safety
property the feature is built around.

**Escalation.** Missed check-ins work down an ordered chain of caregivers by email, with a
real phone call in between. A red flag reaches everyone at once.

**Five relaxation activities.** Paced breathing against a dandelion that comes apart on the
exhale, popping negative thoughts, a memory game, water that answers a finger, and a music
player that plays songs already on the phone. Nothing is timed, nothing is scored, and
none of them can be lost.

---

## Building

```bash
git clone <your-fork-url> && cd AMI
cp local.properties.example local.properties   # then fill in what you want
./gradlew installDebug
```

**It builds and runs with every secret blank.** The model-driven features fall back to
deterministic scripted paths and the backend calls are refused; nothing crashes. Add a
[Gemini API key](https://aistudio.google.com/apikey) to `local.properties` to switch the
conversational paths on.

### Tests

```bash
./gradlew testDebugUnitTest          # ~230 local tests, no device needed
./gradlew connectedDebugAndroidTest  # needs a device or emulator
```

The instrumented suite includes the Room migration tests, which run real SQLite against
the schemas KSP exports.

### The escalation backend

`server/` is a small Node/Express service that sends the caregiver emails and places the
Twilio calls. It is optional — without it the app still rings, still records check-ins,
and simply reports that it could not notify anyone.

```bash
cd server && npm install
cp .env.example .env                 # SMTP and Twilio credentials
npm start
```

---

## Secrets

Nothing secret is committed, and nothing secret belongs in the repo.

| File | Holds | Status |
|---|---|---|
| `local.properties` | Gemini key, shared secret, keystore passwords | gitignored |
| `keystore/*.jks` | Release signing key | gitignored |
| `server/.env` | SMTP and Twilio credentials | gitignored |

`local.properties.example` and `server/.env.example` are the templates, and carry no real
values. The API key travels as an `x-goog-api-key` header rather than a query parameter so
it stays out of URLs, logs and crash reports.

---

## Not what this is

AMI is not a medical device and does not diagnose anything. It cannot detect a fall or
monitor anyone continuously, and it is not a substitute for medical care or emergency
services. Diet and exercise suggestions are general guidance, gated behind deterministic
thresholds, and always carry that caveat.
