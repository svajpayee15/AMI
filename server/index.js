require('dotenv').config();

const crypto = require('crypto');
const express = require('express');
const nodemailer = require('nodemailer');
const twilio = require('twilio');

const app = express();
// Render/Railway/Fly terminate TLS in front of the app, so without this Express sees
// http:// and Twilio's signature - which is computed over the https:// URL - never matches.
app.set('trust proxy', 1);
app.use(express.json({ limit: '32kb' }));
// Twilio posts status callbacks as form-encoded, not JSON.
app.use(express.urlencoded({ extended: false, limit: '32kb' }));

const PORT = process.env.PORT || 3000;
const SHARED_SECRET = process.env.SHARED_SECRET || '';
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || '').replace(/\/+$/, '');
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
// E.164: a leading + and 7-15 digits. Twilio rejects anything else anyway.
const PHONE_REGEX = /^\+[1-9]\d{6,14}$/;
const MAX_RECIPIENTS = 10;

if (!SHARED_SECRET) {
  console.warn('WARNING: SHARED_SECRET is not set. Every request will be rejected until it is configured in .env');
}

const transporter = nodemailer.createTransport({
  host: process.env.SMTP_HOST,
  port: Number(process.env.SMTP_PORT) || 587,
  secure: Number(process.env.SMTP_PORT) === 465,
  auth: {
    user: process.env.SMTP_USER,
    pass: process.env.SMTP_PASS,
  },
});

function timingSafeEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

function requireSharedSecret(req, res, next) {
  const provided = req.get('x-ami-shared-secret') || '';
  if (!SHARED_SECRET || !timingSafeEqual(provided, SHARED_SECRET)) {
    return res.status(401).json({ error: 'Invalid or missing shared secret' });
  }
  next();
}

/**
 * Accepts either the `caregiverEmails` array the app now sends or the single
 * `caregiverEmail` older builds send, so a phone that has not been updated keeps
 * working. Returns null when nothing valid was supplied.
 */
function recipientsFrom(body) {
  const raw = Array.isArray(body.caregiverEmails)
    ? body.caregiverEmails
    : [body.caregiverEmail].filter(Boolean);

  const cleaned = raw
    .filter((value) => typeof value === 'string')
    .map((value) => value.trim())
    .filter((value) => EMAIL_REGEX.test(value));

  const unique = [...new Set(cleaned.map((value) => value.toLowerCase()))];
  if (unique.length === 0) return null;
  return unique.slice(0, MAX_RECIPIENTS);
}

app.post('/api/escalate', requireSharedSecret, async (req, res) => {
  const { medicineName, message, timestampIso, urgent } = req.body || {};

  const recipients = recipientsFrom(req.body || {});
  if (!recipients) {
    return res.status(400).json({ error: 'No valid caregiver email address was supplied' });
  }
  if (!medicineName || typeof medicineName !== 'string') {
    return res.status(400).json({ error: 'medicineName is required' });
  }
  if (!message || typeof message !== 'string') {
    return res.status(400).json({ error: 'message is required' });
  }

  // Urgent is prefixed rather than flagged with a header: mail clients show the
  // subject line, and the caregiver decides what to do from their lock screen.
  const prefix = urgent === true ? 'URGENT — ' : '';

  try {
    await transporter.sendMail({
      from: process.env.SMTP_FROM || process.env.SMTP_USER,
      // Recipients are hidden from each other: a caregiver chain is a list of a
      // family's private addresses, and one of them may not know the others.
      bcc: recipients,
      subject: `${prefix}AMI: missed wellness check for ${medicineName}`,
      text: `${message}\n\nTime: ${timestampIso || new Date().toISOString()}`,
    });
    return res.status(200).json({ ok: true, recipients: recipients.length });
  } catch (err) {
    console.error('Failed to send escalation email:', err);
    return res.status(500).json({ error: 'Failed to send email' });
  }
});

/**
 * The weekly adherence digest. Separate from /api/escalate because it is not an
 * alert: the subject and body come ready-composed from the app, and every configured
 * caregiver gets it rather than only those the escalation ladder reached.
 */
app.post('/api/summary', requireSharedSecret, async (req, res) => {
  const { subject, body } = req.body || {};

  const recipients = recipientsFrom(req.body || {});
  if (!recipients) {
    return res.status(400).json({ error: 'No valid caregiver email address was supplied' });
  }
  if (!subject || typeof subject !== 'string') {
    return res.status(400).json({ error: 'subject is required' });
  }
  if (!body || typeof body !== 'string') {
    return res.status(400).json({ error: 'body is required' });
  }

  try {
    await transporter.sendMail({
      from: process.env.SMTP_FROM || process.env.SMTP_USER,
      bcc: recipients,
      subject,
      text: body,
    });
    return res.status(200).json({ ok: true, recipients: recipients.length });
  } catch (err) {
    console.error('Failed to send weekly summary:', err);
    return res.status(500).json({ error: 'Failed to send email' });
  }
});

/**
 * Places a real phone call as the middle rung of the escalation ladder: the app rings
 * in-app first, and only falls back to the telephone network when that goes unanswered.
 */
const twilioClient =
  process.env.TWILIO_ACCOUNT_SID && process.env.TWILIO_AUTH_TOKEN
    ? twilio(process.env.TWILIO_ACCOUNT_SID, process.env.TWILIO_AUTH_TOKEN)
    : null;

if (!twilioClient) {
  console.warn('WARNING: Twilio is not configured. /api/call will return 503 until TWILIO_* vars are set in .env');
} else if (!PUBLIC_BASE_URL) {
  console.warn(
    'WARNING: PUBLIC_BASE_URL is not set, so Twilio cannot report whether a call was answered. ' +
      'The app will fall back to reporting the outcome as unknown.'
  );
}

/**
 * Call outcomes, keyed by Twilio call SID.
 *
 * Deliberately in memory: this is ephemeral state with a lifetime of about a minute,
 * and the alternative - a database - would mean storing which number was rung and
 * when, which is exactly the kind of record this service has no reason to keep. A
 * restart loses in-flight outcomes, which the app already handles as "unknown".
 */
const callOutcomes = new Map();
const CALL_OUTCOME_TTL_MS = 10 * 60 * 1000;
// Twilio cannot tell a person from an answering machine without answering-machine
// detection, so "answered" here means the line was picked up - not that anyone heard it.
// The app's wording to the caregiver says exactly that.
const ANSWERED_STATUSES = new Set(['in-progress', 'completed', 'answered']);
const FINISHED_STATUSES = new Set(['completed', 'busy', 'no-answer', 'failed', 'canceled']);

function rememberOutcome(sid, status) {
  callOutcomes.set(sid, {
    status,
    // "completed" only means the call ended; it is `answered` that decides whether a
    // human heard the reminder, and Twilio reports that via in-progress first.
    answered: ANSWERED_STATUSES.has(status) || callOutcomes.get(sid)?.answered === true,
    finished: FINISHED_STATUSES.has(status),
    updatedAt: Date.now(),
  });
}

function pruneOutcomes() {
  const cutoff = Date.now() - CALL_OUTCOME_TTL_MS;
  for (const [sid, entry] of callOutcomes) {
    if (entry.updatedAt < cutoff) callOutcomes.delete(sid);
  }
}
setInterval(pruneOutcomes, CALL_OUTCOME_TTL_MS).unref();

// The spoken message is interpolated into TwiML, so it must be escaped or a stray
// angle bracket in a medicine name would inject markup into the call script.
function escapeXml(value) {
  return String(value).replace(/[<>&'"]/g, (c) => ({
    '<': '&lt;',
    '>': '&gt;',
    '&': '&amp;',
    "'": '&apos;',
    '"': '&quot;',
  })[c]);
}

app.post('/api/call', requireSharedSecret, async (req, res) => {
  const { phoneNumber, medicineName, message } = req.body || {};

  // Validate the request before inspecting server configuration: a malformed body is
  // a client error whether or not Twilio happens to be wired up on this deployment.
  if (!phoneNumber || !PHONE_REGEX.test(phoneNumber)) {
    return res.status(400).json({ error: 'phoneNumber is missing or not in E.164 format' });
  }
  if (!medicineName || typeof medicineName !== 'string') {
    return res.status(400).json({ error: 'medicineName is required' });
  }
  if (!twilioClient || !process.env.TWILIO_FROM_NUMBER) {
    return res.status(503).json({ error: 'Calling is not configured on this server' });
  }

  const spoken =
    message && typeof message === 'string'
      ? message
      : `Hello. This is a reminder from AMI. It is time for your ${medicineName}. Please remember to take it.`;

  const options = {
    to: phoneNumber,
    from: process.env.TWILIO_FROM_NUMBER,
    twiml: `<Response><Pause length="1"/><Say voice="alice">${escapeXml(spoken)}</Say><Pause length="1"/><Say voice="alice">${escapeXml(spoken)}</Say></Response>`,
  };

  // Without a publicly reachable base URL Twilio has nowhere to report back to, so
  // the call still goes out and the outcome simply stays unknown.
  if (PUBLIC_BASE_URL) {
    options.statusCallback = `${PUBLIC_BASE_URL}/api/twilio/status`;
    options.statusCallbackMethod = 'POST';
    options.statusCallbackEvent = ['initiated', 'ringing', 'answered', 'completed'];
  }

  try {
    const call = await twilioClient.calls.create(options);
    rememberOutcome(call.sid, call.status || 'queued');
    return res.status(200).json({ ok: true, sid: call.sid });
  } catch (err) {
    console.error('Failed to place escalation call:', err);
    return res.status(500).json({ error: 'Failed to place call' });
  }
});

/**
 * Twilio's status callback.
 *
 * Authenticated by Twilio's own request signature rather than the shared secret:
 * Twilio is the caller here, and it has no way to send an app header. Without a
 * configured auth token the endpoint refuses everything rather than accepting
 * unsigned POSTs, since anyone who guessed a SID could otherwise mark a call answered.
 */
app.post(
  '/api/twilio/status',
  (req, res, next) => {
    if (!process.env.TWILIO_AUTH_TOKEN) {
      return res.status(503).json({ error: 'Twilio is not configured on this server' });
    }
    // The URL is pinned rather than reconstructed from the request: the signature is
    // computed over the exact URL Twilio was given, and a proxy that rewrites the host
    // would otherwise make every callback fail validation.
    const options = { validate: true };
    if (PUBLIC_BASE_URL) options.url = `${PUBLIC_BASE_URL}/api/twilio/status`;
    return twilio.webhook(options)(req, res, next);
  },
  (req, res) => {
    const sid = req.body.CallSid;
    const status = req.body.CallStatus;
    if (sid && status) {
      rememberOutcome(sid, status);
      console.log(`Call ${sid} is now ${status}`);
    }
    // Twilio expects a 204 or empty TwiML; anything else is logged as an error.
    return res.status(204).end();
  }
);

app.get('/api/call/:sid/status', requireSharedSecret, (req, res) => {
  const entry = callOutcomes.get(req.params.sid);
  if (!entry) {
    return res.status(404).json({ error: 'Unknown call' });
  }
  return res.status(200).json({
    sid: req.params.sid,
    status: entry.status,
    answered: entry.answered,
    finished: entry.finished,
  });
});

app.get('/health', (req, res) => res.status(200).json({ ok: true }));

app.listen(PORT, () => {
  console.log(`AMI escalation server listening on port ${PORT}`);
});
