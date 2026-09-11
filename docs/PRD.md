# AMI — Digital Caretaker
### Product Requirements Document

**Status:** Living document (reverse-engineered from the codebase + forward-looking requirements)
**Owner:** Shubham
**Package:** `com.example.ami`
**Platform:** Android (minSdk 26, targetSdk 37), versionName 1.0
**Last updated:** 2026-09-11

---

## 1. Summary

AMI is an Android accessibility application that acts as a **voice-driven digital caretaker for elderly or low-tech-literacy users**. It combines Android's `AccessibilityService` with a cloud LLM to:

1. Let a user speak a goal in plain language (e.g. *"Video call my daughter"*, *"Open WhatsApp and read my messages"*).
2. Read the current screen's content, ask an LLM which UI element to interact with next, and visually point to it with an animated on-screen cursor while narrating each step aloud.
3. Proactively remind the user to take medicine on a schedule, run a spoken "wellness check," and escalate to a chain of caregivers if the user is unresponsive.
4. Keep a picture of how the person is actually doing — vitals, adherence, and a day-by-day wellbeing record — and report it to the people who care for them.
5. Ask, on every medicine call, what is actually bothering them, and turn a week of those answers into food and exercise suggestions.

**AMI runs two agents, and the difference between them is what each is allowed to decide.**

| | Navigation agent | Caretaker agent |
|---|---|---|
| Lives in | `AmiPlannerManager` driving `AmiAccessibilityService` | `caretaker/CaretakerAgent` |
| Acts on | The phone — it taps, scrolls and opens things | Nothing. It only ever produces words |
| Input | The current screen, as flattened accessibility text | Symptoms heard on check-in calls, vitals, adherence |
| Guardrail | A blocklist that refuses banking and credential apps outright | A rule gate that routes red-flag symptoms and URGENT readings to a human *before* any model call |

The navigation agent is trusted with actions and tightly fenced on *where*; the caretaker agent is trusted with words and tightly fenced on *when it may speak at all*. Neither is allowed to decide the thing that would hurt someone if it got it wrong.

The app closes the gap between complex smartphone UIs and users (typically elderly, visually impaired, or unfamiliar with technology) who need a patient, step-by-step guide rather than a static tutorial.

---

## 2. Problem Statement

Elderly and cognitively/visually impaired users frequently:
- Struggle to complete multi-step tasks on modern apps (video calling, messaging, navigating settings) because UIs assume familiarity and fine motor precision.
- Forget to take medication on schedule, and family caregivers have no reliable way to know if a dose was missed.
- Have no natural-language way to ask "how do I do X on my phone" and get a live, on-screen answer instead of a generic web tutorial.
- Decline slowly and invisibly — sleeping worse, speaking to fewer people — in ways nobody notices until something goes wrong.

AMI addresses this by turning the phone itself into an active guide: it watches what's on screen, tells the user what to tap next, shows them exactly where, and keeps a record a caregiver can read.

---

## 3. Goals

- **G1 — Guided navigation:** Given a spoken goal, visually and verbally walk the user to completion across any app, one step at a time.
- **G2 — Medication adherence:** Let a caregiver (or the user) schedule medicines — including more than one dose a day — and receive a spoken reminder + confirmation check at the right time.
- **G3 — Caregiver escalation:** If the user doesn't respond, work down an ordered chain of trusted contacts.
- **G4 — Low cognitive load UX:** Single floating trigger button to start; four top-level tabs; nothing more than two steps from anywhere.
- **G5 — Caregiver visibility:** A caregiver can see, without asking, whether doses are being taken and what the assistant has been doing on the phone.
- **G6 — Honest wellbeing signal:** Surface stress, sleep and social contact as trends the recorded data actually supports — never as a decorative number a caregiver could mistake for a measurement.

### Non-Goals (for current scope)
- Not a replacement for emergency medical services (no fall detection, no vitals monitoring).
- Not a general-purpose voice assistant (no weather, search, jokes) — scope is on-device task navigation and care.
- Not intended to control apps that block Accessibility Service interaction, and **explicitly refuses** to read banking, payments and credential apps at all.
- No accounts, no server-side copy of user data. One recipient of care per device (but many caregivers).
- Not a diagnostic tool. No dimension is weighted above another and no reading is interpreted clinically.

---

## 4. Target Users

| Persona | Description | Primary need |
|---|---|---|
| **Primary user — "Grandparent"** | Elderly individual, owns an Android phone, limited comfort with apps, may have mild vision/memory impairment | Voice-guided help completing tasks; reminded to take medicine |
| **Secondary user — "Caregiver"** | Adult child or relative who sets up the phone and configures medicine schedules | Peace of mind that the parent is taking medication and can get help without a phone call |

---

## 5. Current State of the Codebase

### 5.1 Guided navigation

| Feature | Status | Notes |
|---|---|---|
| Floating trigger bubble (mic button) | ✅ | `AmiCursorManager.kt` |
| Voice goal capture (`SpeechRecognizer`) | ✅ | One-shot recognition per tap; "stop"/"cancel" abort the task |
| Screen reading via `AccessibilityNodeInfo` | ✅ **password fields excluded** | `getCleanScreenText` skips `isPassword` nodes |
| Sensitive-app blocklist | ✅ | `navigation/SensitiveApps` refuses banking, payment, wallet and authenticator packages outright — known packages, keyword and whole-segment rules over the package name. AMI stops and says why rather than degrading silently. Android has no "finance" app category, so there is no platform signal to lean on |
| LLM-driven next-action planning | ✅ single provider | `AmiPlannerManager` — **Gemini 2.5 Flash direct on the Google AI Studio (Generative Language) API**, key from `BuildConfig`. Thinking is switched off (`thinkingBudget: 0`): every prompt here wants one short structured line, and thinking tokens come out of the same output budget as the reply. The key travels as an `x-goog-api-key` header, never as a `?key=` query parameter, so it stays out of URLs and logs. HTTP logging gated to debug builds |
| Semantic screen-change detection | ✅ | `navigation/ScreenChangeDetector` compares the *set of actionable labels* and every toggle's state rather than the raw text, digits normalised to `#`. A ticking clock or unread badge no longer counts as a new screen |
| Cursor pointing + swipe animation | ✅ **scrolls to find** | `findAndPointToNode` scrolls the nearest scrollable container (up to 5 attempts) when the target is below the fold |
| Settings navigation | ✅ | `navigation/SettingsDeepLinks` maps ~27 spoken phrases to `Settings.ACTION_*`; planner emits `SETTINGS:<key>` |
| Screen affordances for the planner | ✅ | Each label annotated `[tap]`, `[toggle:on/off]`, `[scrollable]` |
| App launching by voice | ✅ | Resolves any installed app by label via `PackageManager` |
| Global system actions (Home/Back/Notifications) | ✅ | |
| Text-to-speech narration | ✅ | |
| In-app action history | ✅ | `data/ActionLogRecord` + `ActionLogDao`, surfaced in `HistoryActivity`. Stores the step and what AMI said — **never the screen text sent to the model**. Capped at 500 rows, clearable |

### 5.2 Care and escalation

| Feature | Status | Notes |
|---|---|---|
| Medicine schedule | ✅ **multiple doses/day + dosage notes** | `medicines` (name + dosage note) and `medicine_doses` (one row per time, each with its own "taken today") |
| Wellness check on medicine time | ✅ **reboot-safe, per dose** | One exact alarm per *dose* (`reminders/MedicineAlarmScheduler`); `BootReceiver` re-arms on reboot and `AmiApplication` on every launch, which also repairs alarms left by a force-stop |
| Check-in as an incoming call | ✅ | `call/AmiCallActivity` rings full-screen over the lock screen; checks `canUseFullScreenIntent()` (restricted since Android 14) and falls back to a notification |
| Nurse-style conversation | ✅ **speaks the dosage note, asks about symptoms** | `call/NurseConversation` runs a multi-turn exchange bounded to 6 turns and reads the caregiver's dosage note back verbatim (the model is barred from rewording it). Falls back to a deterministic 3-attempt script when the model is unreachable — and the scripted path asks the symptom question too, so complaints are still captured with no API key |
| Symptom capture on the call | ✅ | The call asks plainly about pain or trouble, naming examples ("a headache, stomach trouble, dizziness") rather than "how are you?", which gets "fine" from everyone. `caretaker/SymptomExtractor` reads the whole transcript into a fixed vocabulary — handling negation, complaints phrased *as* negations ("can't sleep"), and body-part-plus-complaint-word phrasing. Unit-tested |
| Red flag during a call | ✅ **deterministic** | `caretaker/Symptom.redFlag` (chest pain, breathlessness, a fall, fainting, bleeding, stroke signs) ends the call calmly and escalates to the **whole** caregiver chain at once, flagged urgent — on a confirmed dose too, since someone can take their tablet and still have told AMI they fell |
| Check-in history | ✅ | `data/CheckInRecord` carries `doseId` and `scheduledTime`, so a report can say *which* dose was missed |
| Adherence report | ✅ | `care/AdherenceReport` — pure arithmetic, unit-tested. An unanswered check-in counts as **missed**, never excluded; a week with nothing scheduled reports "no data", not 0% |
| Escalation chain | ✅ | `care/EscalationPlan` widens with severity: primary hears about every miss, the second is pulled in immediately when nobody answered at all, later contacts join as the run of misses lengthens. Unit-tested |
| Twilio call outcome | ✅ | Backend registers a `statusCallback`, the app polls `GET /api/call/:sid/status` for up to 90s, and the caregiver email says whether the call was answered. A timeout reports "could not confirm" — never "answered" |
| Weekly summary email | ✅ | `care/WeeklySummary` + `WeeklySummarySender`, fired by an inexact Sunday-evening alarm and guarded by a "has a week actually passed" check so a Doze-delayed alarm can't send twice. Toggleable, with "send now" |
| Caregiver configuration | ✅ | Ordered chain of name + email, plus the user's E.164 number, in `SettingsActivity`. The old single-address preference is read as a fallback so upgrades keep their contact |
| Escalation backend | ✅ self-hosted | Node/Express (`server/`): `/api/escalate` (bcc'd chain), `/api/summary`, `/api/call`, `/api/call/:sid/status`, `/api/twilio/status` (Twilio-signature authenticated) and `/health`. Every app-facing route is behind a shared-secret header. **Needs real hosting** to work off the local network |

### 5.3 Health and wellbeing

| Feature | Status | Notes |
|---|---|---|
| Health data (steps, weight, BMI, BP, sugar) | ✅ | `health/HealthConnectSource` via **Health Connect**, not Google Fit |
| Manual vitals entry | ✅ | `health/VitalsActivity` with large +/- steppers; `LocalVitalsStore` backs it when Health Connect is unavailable |
| Diet suggestions | ✅ **rule-gated** | `health/VitalsAssessment` triages readings in plain arithmetic *before* any model call; URGENT hard-stops food advice and escalates to the **whole** chain at once |
| Caretaker agent | ✅ **rule-gated** | `caretaker/CaretakerAgent` — AMI's second agent. Reads the symptoms collected on check-in calls, and turns them into what to eat and how to move. `SymptomDigest` counts mentions and *days affected* separately in plain arithmetic first (three separate days is a pattern; four mentions in one afternoon is a bad afternoon), and a red flag or an URGENT reading returns "speak to someone" **without any model call at all**. Falls back to per-symptom built-in tips when the model is unreachable. Unit-tested |
| Caretaker report screen | ✅ | `caretaker/CaretakerActivity`, reached from the Health tab — what was noticed (with the person's own words), what to eat, exercise to follow, and a "read this to me" button. Stays off the bottom bar to keep it at four readable tabs |
| Wellbeing story | ✅ screen complete, **no input path yet** | `wellbeing/` — stress, sleep and social contact on a 0–100 scale, one row per calendar day. `WellbeingStory` derives this-week-vs-last-week stress and connections, this-month-vs-last-month overall wellbeing, a burnout direction with its run length, and up to three evidenced patterns. See §6.6 and the open item below |
| Trend chart | ✅ | `wellbeing/TrendSeries` buckets a week/month/year into 7/5/12 points; `TrendChartView` draws it with the "seek help" rule on the correct side per dimension. Missing days are drawn as gaps, never as zero |
| Relaxation activities | ✅ **built to the supplied design** | `games/` — five of them, reached from a hub on **Home** (not Health: the moment someone wants this is the moment they are least inclined to go looking for it). **Breathe with me** paces 4s in / 2s hold / 6s out against a dandelion whose seeds come away on the exhale and drift back on the rest — the longer exhale is the point, not a style choice. **Bubble pop** floats negative thoughts up a wall to be burst. **Nature touch** is water that answers a finger. **Memory pairs** and **Music therapy** complete the set. Rules live in pure classes (`BreathingSession`, `BubbleField`, `MemoryBoard`, `RipplePond`, `ThoughtBubbles`), all unit-tested |
| Scene art | ✅ **painted, not shipped** | Backgrounds are drawn on Canvas by `games/SceneView` subclasses from a palette sampled off the reference recording. The design pack's backdrops arrived as Instagram screenshots, an Android screenshot with its status bar, a **watermarked Shutterstock comp** and a Meta-AI-watermarked image — none shippable, and none scalable to an arbitrary screen without the chrome showing. The pack's clean cut-outs (dandelion stem and seed, soap bubble, cloud, paper and wood textures) **are** used, at 96 KB total in `drawable-nodpi` |
| Scene chrome | ✅ | `layout/include_game_chrome.xml` — headline top-left in `sans-serif-black` caps, orange close X top-right, and a hint line in Android's own `cursive` family, which is the nearest on-device match to the design's script and cannot fall back to a box glyph. Every style carries its own shadow: text sits straight on illustration, and cream on a pale sky is unreadable without one |
| Music therapy | ⚠️ **silent** | `games/MusicActivity` + `MusicLibrary` — mood chips, transport and a turning record all wired; `MusicLibrary.tracksFor` returns nothing until audio files are dropped into `res/raw` and listed, and the card says so plainly rather than faking a progress bar. **No audio came with the design hand-off** |
| Every activity has an ending | ✅ | `games/GameFinish` + `include_game_finish.xml` — one shared panel, same two choices in the same place, for the three activities that can finish. Fixes a set of screens that were each a dead end or endless: memory completed with **no way to play again**, breathing set `finished` and then refused to restart, and bubble pop refilled forever. Nature touch is the deliberate exception — it is the one that asks nothing and so has nothing to finish |
| Breathing survives an interruption | ✅ | Elapsed time is banked in `onPause` and resumed from in `onResume`. It used to restart from the first breath, throwing away two minutes of someone's effort because they glanced at a notification. A row of dots, one per breath, replaced having no sense of progress at all — dots rather than a number, so it reads as shape rather than as a countdown |
| Bubble pop has an arc | ✅ | `games/ThoughtSession` deals a finite set, the field visibly thins as thoughts are popped, and clearing the last one ends the exercise. Progress counts **up** and only appears after the first pop: "12 to go" on arrival reads as a quota, "3 gone" reads as progress. Unit-tested |
| Add your own music | ✅ | The design's "ADD MUSIC +", built: the system picker hands back audio already on the phone, `games/MusicStore` keeps it per mood with a **persistable** read grant (without which every track silently fails to open days later), and a plain `MediaPlayer` plays it. Playback is released in `onPause` — a relaxation screen is not a background player, and there is no notification left behind that could stop it |
| Games designed not to punish | ✅ | No timers, no scores, no failure states anywhere. A bubble that drifts off the top is not a miss; a mismatched pair is shown for a deliberately long 1.4s before turning back, and the board refuses taps meanwhile so a double-tap cannot hide it early; bubble hit areas are 1.45× the drawn radius so a tremor never costs a second attempt; Calm Pond shows no counter at all, because a number on screen would give the one activity that asks nothing of anybody a goal |
| Wearable / Health Connect linking | ✅ | `profile/ConnectWearableSheet` branches on actual availability: install, request permissions, or say it is already connected |

### 5.4 Shell, setup and platform

| Feature | Status | Notes |
|---|---|---|
| Bottom navigation | ✅ | `navigation/AmiNavBar` — four tabs (Home, Health, Report, Settings) over Activities, with a stack rule that keeps back at most Home-plus-one. Unit-tested |
| Onboarding / permission explanation | ✅ | `OnboardingActivity` is the launcher activity |
| Profile settings | ✅ | `profile/ProfileSettingsActivity` — grouped rows for profile, care settings, wearables, activity, Health Connect and notification state, the five info pages, and delete-all-data |
| Local profile | ✅ | `profile/EditProfileActivity` — optional name and date of birth, on-device only, gating nothing |
| Info pages | ✅ | `profile/InfoActivity` — About, Privacy, User agreement, Help, Report an issue (hands off to a mail app; there is no backend that accepts reports) |
| Delete all my data | ✅ | Two-step confirm, then `clearAllTables()` + `AmiPreferences.clearAll()`. Phrased honestly: there is no account and no server copy |
| Persistence layer | ✅ | Room **schema v4** (medicines, doses, check-ins, action log, wellbeing entries) with real 1→2, 2→3 and 3→4 migrations; DataStore for onboarding, caregiver chain, phone, summary settings, name and DOB |
| Foreground service / boot persistence | ✅ | `AmiWellnessService` (foreground, type `microphone`) + `BootReceiver` |
| API key / secrets management | ✅ | `local.properties` → `BuildConfig`; `AmiApplication` warns on blank values |
| Visual design | ✅ | Elderly-first system in `values/styles_ami.xml`, applied across every screen. Theme is **Material Components (M2)** — M3-only widgets resolve no style and break at runtime |

### Still open

**Code-fixable**

- **The wellbeing screen has no way to get data.** `WellbeingRepository.record()` has no callers anywhere in the app, so `wellbeing_entries` is always empty and the screen permanently shows its empty state. It needs an input path — the obvious one being a question or two at the end of the nurse check-in, which already happens daily and already has the person talking.
- **No cap on planner calls per goal.** `ScreenChangeDetector` filters the redundant ones, but a genuinely churning screen can still run up cost indefinitely.

**Needs a decision, an account or a device**

- **`GEMINI_API_KEY` is blank**, so `BuildConfig` compiles it to `""`. The nurse conversation and diet suggestions degrade to their scripted/fallback paths. Diagnosable via the `AMI_CONFIG` warning `AmiApplication` logs.
- **`AMI_ESCALATION_SHARED_SECRET` is blank.** The server fails closed on a missing secret, so *every* escalation, call and summary request is currently rejected with 401 before it reaches SMTP or Twilio. Both sides need the same non-empty value.
- **`AMI_BACKEND_BASE_URL` points at a local address.** Escalation email, reminder calls and the weekly summary no-op off that network. A free tier on Render/Railway/Fly covers this workload; `PUBLIC_BASE_URL` must be set there or call outcomes stay unknown.
- **Rotate the OpenRouter/Groq keys that were previously hardcoded in source** — manual, outside what code can fix. Both providers are now gone from the app; the keys still need revoking at the provider.
- **Play Store declarations** before any public release: Health Connect data-type access, full-screen-intent justification, and the accessibility-API declaration (which needs a demo video). None are needed for sideloaded personal use.
- The migration test (`AmiDatabaseMigrationTest`) is instrumented and needs a device or emulator; it has not been executed yet.

---

## 6. Core Features (Target Behavior)

### 6.1 Voice-Guided Task Navigation
- User taps the floating trigger bubble → mic opens → speaks a goal.
- AMI checks the foreground package first. Banking, payment and credential apps are refused out loud, and nothing from them is read or sent.
- Otherwise it reads the visible screen (text + content descriptions, password fields excluded, each label annotated with its affordances).
- Goal + screen content + running action history is sent to an LLM, which returns:
  - The name/label of the one UI element to interact with next, **or**
  - A structured command (`SETTINGS:<key>`, `LAUNCH:<app>`, `ACTION:<system action>`, `SWIPE:<direction>`), **or**
  - `GOAL_REACHED` to end the task.
- AMI speaks a short, encouraging instruction and animates the floating cursor to the target element.
- The loop continues as the screen changes, but only when the change is *meaningful* — a new or vanished button, a flipped toggle, a different app, or a substantially different screen body.
- Every step is written to the in-app action log for later caregiver review.

### 6.2 Floating Assistant UI
- Always-on, non-intrusive trigger bubble (target — draggable; currently fixed position).
- Expands into a cursor + speech-bubble overlay only while a task is active.
- A visible "stop" affordance appears once a task starts.

### 6.3 Medicine Reminders
- Caregiver/user adds a medicine name, an optional dosage note ("one tablet, with food") and a time. Adding the same name again with a different time gives that medicine a second dose rather than a duplicate entry.
- At each scheduled minute the phone rings with a full-screen check-in call for *that dose*.
- A dose already confirmed today is skipped rather than rung again — being asked twice about the same tablet is how someone takes it twice.
- Answering hands over to a real spoken conversation; the dosage note is read back word for word.
- Confirmed doses are marked taken for that day, per dose; the flag resets daily by epoch-day comparison.

### 6.4 Caregiver Escalation
- The primary caregiver is emailed about every missed check-in.
- If nobody answered the phone at all, the second contact is brought in at once.
- Each further contact joins once the run of consecutive misses reaches their position in the chain.
- Between the in-app call and the emails, AMI asks the backend to place a real phone call to the user, then waits for Twilio's verdict and tells the caregiver whether it was picked up.
- An urgent vitals reading skips the ladder entirely and mails everyone at once.

### 6.5 Caregiver Reporting
- A care report screen shows 7-day and 30-day adherence, a per-medicine breakdown, every check-in with the user's own words, and the guided-navigation log.
- A weekly summary email goes to the whole chain on Sunday evening, in plain prose, leading with the one thing that needs attention.

### 6.6 Wellbeing Story
- Three dimensions are recorded per day on a 0–100 scale: **stress** (higher is worse), **sleep** and **social** (higher is better), plus a count of people actually spoken to.
- One row per calendar day, keyed by epoch day, so recording a second reading for a day *corrects* the first rather than letting the trend count it twice.
- Nothing derived is stored. Burnout state, overall wellbeing and every insight are recomputed from the raw rows on each load, so a correction to one day moves the headline with it.
- The screen states four things: stress this week vs. last, whether sustained stress is rising/easing/stable and for how many weeks, connections this week vs. last, and overall wellbeing this month vs. last.
- Up to three "what's working for you" cards appear, each only when the history supports it: the weekday that reliably carries least stress (needs that weekday recorded twice), next-day stress after a good night vs. a poor one (needs three days each side), and social score on days with company vs. days without. A gap smaller than 8 points is treated as noise and no card is shown.
- The trend chart draws a week as 7 daily points, a month as 5 weekly averages, a year as 12 monthly averages. A day with no reading is a gap, not a zero. The dotted "seek help" rule is a ceiling for stress and a floor for connection; sleep has no such line, because one bad night is not on its own a reason to act.
- **Not yet reachable in practice** — see Still open. The screen is complete and tested; nothing writes to it.

### 6.7 Setup, Privacy and Control
- Four tabs: Home (what is happening now), Health (the body), Report (the record), Settings (setup).
- Profile is a courtesy, not an account: a name for AMI to use and an optional date of birth, both local, both optional.
- The Health Connect and notification switches report state this app does not own, and tapping either hands off to the system surface that actually controls it rather than pretending to flip it.
- "Delete all my data" wipes every table and every preference after a two-step confirm, and says plainly that there is no server copy to leave behind.

---

## 7. Key User Flows

**Flow A — First-time setup**
1. Launch app → grant overlay permission → grant microphone permission → enable Accessibility Service (deep-links to system settings; the toggle itself is manual, as Android requires).
2. Add first medicine, dosage note and time (optional). Add a second time for the same medicine if needed.
3. Settings → Account settings → add at least one caregiver, and the user's own phone number.
4. Return to home; floating trigger bubble appears.

**Flow B — Guided task**
1. Tap trigger bubble → "I'm listening..." → user states goal.
2. AMI narrates and points step-by-step across one or more apps, replanning only when the screen meaningfully changes.
3. User says "stop", AMI detects goal completion, or AMI refuses because the foreground app is a banking/credential app.

**Flow C — Medicine reminder**
1. At the scheduled time the phone rings full-screen for that dose.
2. Answering starts the spoken check-in; declining or ignoring runs the escalation ladder.
3. Outcome, and anything said about how they felt, is written to the check-in history.

**Flow D — Caregiver review**
1. Caregiver opens AMI → *Report* tab.
2. Reads adherence, individual check-ins, and what AMI has been doing on the phone.
3. Or simply reads Sunday's summary email.

**Flow E — Wellbeing check** *(blocked until §5.3's open item lands)*
1. Home or Health → the wellbeing story.
2. Reads this week against last, picks a dimension tab and a range, sees the trend and the patterns the data supports.

---

## 8. Technical Architecture

```
OnboardingActivity ── consent, then hands off to MainActivity
     │
AmiNavBar ─── four tabs over Activities; Home is the root, every other tab
     │        closes itself on the way out so back never retraces the bar
     │
     ├── HOME     MainActivity ─── setup UI, sequenced permission requests,
     │                             dose CRUD, 7-day adherence glance
     │                └── GamesActivity ─┬── BreathingActivity  (dandelion / breath)
     │                                   ├── BubbleActivity     (negative thoughts)
     │                                   ├── MemoryActivity     (pairs)
     │                                   ├── PondActivity       (nature touch / water)
     │                                   └── MusicActivity      (silent until res/raw)
     ├── HEALTH   VitalsActivity ── Health Connect + manual entry
     │                ├── WellbeingActivity (one level under Health)
     │                └── CaretakerActivity (one level under Health)
     ├── REPORT   HistoryActivity ─ AdherenceReport + check-ins + action log
     └── SETTINGS ProfileSettingsActivity
                      ├── EditProfileActivity ─── name, DOB (local only)
                      ├── SettingsActivity ────── caregiver chain, phone, summary
                      ├── ConnectWearableSheet ── Health Connect linking
                      └── InfoActivity ────────── about / privacy / help / report

Data
     ├── MedicineRepository ─┬─> Room v5: medicines + medicine_doses
     │                       ├─> Room v5: check_ins
     │                       ├─> Room v5: symptom_reports
     │                       └─> Room v5: action_log
     ├── WellbeingRepository ──> Room v5: wellbeing_entries
     ├── AmiPreferences ──────> DataStore (onboarding, caregiver chain, phone,
     │                                     summary, display name, DOB)
     └── HealthRepository ───┬─> HealthConnectSource ──> Health Connect
                             └─> LocalVitalsStore ────> DataStore (manual fallback)

AmiAccessibilityService ─── screen observer, orchestrator, TTS + STT
     │    │    │
     │    │    ├── SensitiveApps ──────── refuse banking/credential packages
     │    │    └── ScreenChangeDetector ─ is this worth another LLM call?
     │    ├── AmiCursorManager ─── WindowManager overlays
     │    └── SettingsDeepLinks ── spoken phrase -> Settings.ACTION_*
     ▼
AmiPlannerManager ─── Google AI Studio -> gemini-2.5-flash
     ▲                findNextAction() for navigation, chat() for conversation
     │
MedicineAlarmScheduler ─ one exact alarm per dose (BootReceiver + AmiApplication re-arm)
     ▼
MedicineAlarmReceiver ──> AmiWellnessService (foreground, microphone)
                               │
                               ├── AmiCallActivity ─── full-screen ringing UI
                               ├── NurseConversation ─ multi-turn check-in
                               └── EscalationPlan ───> EscalationClient ──> server/
                                                        (chain email, call, outcome poll)

WeeklySummaryScheduler ─ inexact Sunday alarm
     ▼
WeeklySummaryReceiver ──> WeeklySummarySender ──> AdherenceReport + WeeklySummary
                                              └─> EscalationClient.sendSummary()
```

- **Screen understanding:** flattened text/content-description dump of the visible
  `AccessibilityNodeInfo` tree, capped at 100 nodes / 2000 chars, password nodes excluded,
  each label annotated `[tap]` / `[toggle:on|off]` / `[scrollable]`.
- **Change detection:** Jaccard similarity over the normalised actionable-label set
  (threshold 0.85) and the full label set (0.5), plus exact comparison of toggle states
  and the package name. Digits collapse to `#`.
- **Planning:** one LLM call per *significant* screen change; the model returns
  `BUTTON_TEXT | VOICE_RESPONSE`, or a verb prefix.
- **Health triage:** deterministic thresholds in `VitalsAssessment` run before the model.
- **Wellbeing derivation:** pure functions over `WellbeingEntry` rows, Android-free, so
  every figure on the screen is unit-testable without a device.
- **Networking:** Retrofit + Gson; OkHttp body logging gated to `BuildConfig.DEBUG`.
  Cleartext is permitted only in the debug `network_security_config`, scoped to loopback.
  App-facing backend routes require an `x-ami-shared-secret` header compared in constant time.

---

## 9. Non-Functional Requirements & Known Risks

**Security / privacy**
- ✅ Secrets are in `local.properties` → `BuildConfig`. The previously hardcoded keys still need **manual rotation**.
- ✅ Password fields are excluded from screen capture, **and** banking/payment/authenticator packages are refused outright. The blocklist is deliberately over-broad: a false positive costs one spoken refusal, a false negative sends a bank balance to an LLM.
- ✅ The action log stores what AMI *did*, never the screen text it read.
- ✅ Caregiver emails are bcc'd, so a chain never leaks one relative's address to another.
- ✅ No account, no server-side copy: everything but escalation email lives on the device, and the delete action really is complete.
- ✅ The backend fails closed without a shared secret rather than serving open endpoints.
- 🟡 Screen text from any non-blocked foreground app is still sent to a third-party model while a goal is active. Disclosed in onboarding; inherent to the design.
- 🔴 `GEMINI_API_KEY` and `AMI_ESCALATION_SHARED_SECRET` are presently **blank**, so every model-driven feature runs its fallback path and every backend call is refused with 401.

**Safety (health features)**
- Diet advice is gated behind deterministic thresholds, never generated for an URGENT reading, and always carries a not-medical-advice disclaimer.
- The dosage note is read back verbatim and the model is explicitly barred from rewording it — restating a dose in the model's own words would be giving medical instructions.
- Thresholds are general-adult guidance; AMI does not know whether a glucose reading was fasting or post-meal, so only the extremes are treated as actionable.
- The check-in answer classifier matches negation first, on whole words: a substring scan reads "I haven't taken it" as *taken*, which would record a missed dose as taken and suppress the escalation it exists to trigger.
- Adherence counts only *confirmed* doses. An unanswered check-in is missed, not excluded — the alternative flatters the number exactly when something is going wrong.
- Wellbeing shows no number it cannot derive from a real reading. Empty history says so; a gap in the chart is a gap; a pattern below an 8-point margin is not claimed at all.

**Reliability**
- ✅ Medicines, doses, check-in history, action log, wellbeing rows and vitals persist; alarms survive reboot, force-stop and the dose-schema upgrade.
- ✅ Migrations 1→2, 2→3 and 3→4 are real rather than destructive; 2→3 rebuilds three tables and is covered by an instrumented `MigrationTestHelper` test — **which still needs to be run on a device**.
- ✅ The weekly summary re-checks the calendar rather than trusting the alarm, so a Doze-delayed or coalesced alarm cannot send two digests in a week.
- 🟡 Twilio's `answered` means the line was picked up, not that a person heard it — voicemail counts as answered without answering-machine detection. The caregiver email wording says exactly this.
- 🟡 Call outcomes live in server memory for 10 minutes. Deliberate (the alternative is a database of who was rung and when), but it assumes a single backend instance.
- Health Connect's client library is still **alpha** (`1.2.0-alpha06`).

**Cost/Latency**
- ✅ Redundant planner calls are filtered by `ScreenChangeDetector` rather than raw string inequality, which was the main source of spurious calls on chatty screens.
- 🔴 There is still no cap on calls per goal; a genuinely churning screen can still be expensive.
- ✅ The wellbeing screen loads its headline and its chart from one query, not one per card.

**Accessibility/UX**
- ✅ Onboarding explains each permission in plain language before anything is requested.
- ✅ Type scale, contrast and touch targets are sized for the target persona across every screen.
- ✅ Permission round-trips are requested one per visit rather than three at once.
- ✅ The ringing notification is swapped for a quiet follow-up one as soon as the ring ends, so the phone never claims to be ringing while the service is only waiting on a call outcome.
- ✅ Settings rows are one full-height tap target each; the switch inside a row is not separately focusable.
- ✅ Window insets are handled at the shell, so tabs never sit under the gesture pill.
- ⚠️ The theme is Material Components (M2). Material 3 widgets resolve no style here and have crashed a screen on measure — use the M2 equivalent (`SwitchMaterial`, not `MaterialSwitch`).

**Test coverage**
- **101 local unit tests** across 10 classes, covering the logic that decides something consequential: adherence arithmetic, the escalation ladder, the caregiver-chain encoding and its legacy fallback, screen-change significance, the sensitive-app rules, the summary wording, the navigation stack rule, and the whole wellbeing derivation (metric direction vs. improvement, bucketing and gaps, insights and burnout runs).
- One instrumented migration test, not yet run.
- Everything Android-framework-bound (services, activities, alarms) is untested.

---

## 10. Success Metrics

Pre-launch; these are proposed for the first pilot.

| Metric | Target |
|---|---|
| Task completion rate (goal spoken → `GOAL_REACHED`) | ≥ 80% for top 5 supported tasks |
| Median steps-to-completion vs. manual attempt | Reduction vs. unassisted baseline (user testing) |
| Medicine reminder response rate | ≥ 90% acknowledged within 5 minutes |
| False escalations (caregiver notified despite dose taken) | < 5% |
| LLM calls per completed goal | ≤ 6 median |
| Days with a wellbeing reading recorded | ≥ 70% of days in the pilot |
| Crash-free session rate | ≥ 99% |

---

## 11. Roadmap

**Phase 0 — Harden foundations** ✅ complete
**Phase 1 — Trust & consent** ✅ complete
**Phase 1.5 — Design** ✅ complete

**Phase 2 — Navigation quality** ✅ complete
Settings deep-links, scroll-to-find, affordance annotations, general app resolution,
semantic change detection, sensitive-app blocklist, in-app action history.

**Phase 3 — Care features** ✅ complete
Nurse-style check-in call, check-in history, Health Connect vitals, manual entry,
rule-gated diet suggestions, multiple doses per day with dosage notes, adherence
reports, an ordered escalation chain, the weekly summary email, and Twilio
call-outcome reporting.

**Phase 3.5 — Shell & wellbeing** 🟡 mostly complete
Four-tab navigation, profile settings, info pages, delete-all-data, and the wellbeing
story with its trend chart and evidenced insights. **Outstanding:** nothing writes
wellbeing readings yet — the screen needs an input path before it is a feature rather
than a surface.

**Phase 4 — Pilot readiness** ⬜ not started
- Deploy the backend; set a real `GEMINI_API_KEY`, a matching shared secret on both sides, and a public `AMI_BACKEND_BASE_URL`.
- Run the instrumented migration test on a device, then an end-to-end run on a real phone.
- Cap LLM calls per goal.
- Play Console declarations and the accessibility demo video.
- Configurable diet thresholds per user (see Open Questions).

---

## 12. Open Questions

1. ~~Which LLM should be canonical?~~ **Settled:** Gemini 2.5 Flash, called directly on the Google AI Studio API (was via OpenRouter; the extra hop and second account bought nothing).
2. ~~SMS or push for escalation?~~ **Settled:** email via the self-hosted backend, with a Twilio phone call as the middle rung.
3. ~~How should multiple caregivers be prioritised?~~ **Settled:** an ordered chain that widens with severity — `care/EscalationPlan`.
4. ~~Is a package-level blocklist needed before any non-family user?~~ **Settled:** yes, and it ships — `navigation/SensitiveApps`.
5. **How should wellbeing readings be captured?** The nurse check-in is the obvious host — it already happens daily and the person is already speaking — but three 0–100 questions is a lot to ask by voice every day. A two-question rotation, or sliders on the Health tab, are the alternatives.
6. Where should the backend be hosted? Still open; a free tier on Render/Railway/Fly would cover this workload.
7. Should the diet thresholds be configurable per user? They are general-adult values, and someone with diagnosed diabetes has different targets. The same question applies to the wellbeing "seek help" lines (stress ≥ 68, connection ≤ 25), which are currently fixed.
8. Should the weekly summary include the action log, or is adherence enough? Including it would tell a caregiver a great deal about how the phone is being used, which is not obviously theirs to know. The same judgement now covers the wellbeing trend.
9. Should a caregiver be able to edit the schedule remotely, or does that need a second app and an account system?
