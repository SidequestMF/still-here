// Still Here relay: receives an alert from the iPhone or watch and texts each trusted contact.
// iOS apps can't send SMS silently, so this small service does it through Twilio.
//
// Run:  RELAY_TOKEN=... TWILIO_SID=... TWILIO_TOKEN=... TWILIO_FROM=+1... node server.js
// Needs Node 18 or newer. No dependencies.

const http = require('node:http');
const crypto = require('node:crypto');

function tokenMatches(header, token) {
  if (!token || typeof header !== 'string') return false;
  const a = Buffer.from(header), b = Buffer.from(`Bearer ${token}`);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const MAX_CONTACTS = 5;
const SEEN_TTL_MS = 60 * 60 * 1000;

const CAUSES = {
  noResponse: (name, trig) => `No response to a check-in after ${trig}.`,
  failedTests: (name, trig) => `Response tests were much slower than normal after ${trig}.`,
  askedForHelp: (name) => `${name} asked for help.`,
};
const TRIGGERS = {
  scheduled: 'a scheduled check-in',
  heartRateChange: 'an unusual change in heart rate',
  stumble: 'a stumble or fall',
  stillness: 'being unusually still',
  manual: 'a check the wearer asked for',
  helpButton: 'the wearer pressing the help button',
  watchOff: "the watch losing contact with the wearer's wrist",
};

// The message is built here from known fields, never passed through from the client,
// so a leaked token can't be used to send arbitrary text.
function composeMessage(event, now = new Date()) {
  const name = String(event.wearerName || 'Your friend').replace(/[^\p{L}\p{M} '\-]/gu, '').slice(0, 40) || 'Your friend';
  const created = Number.isFinite(Date.parse(event.createdAt)) ? new Date(event.createdAt) : now;
  const time = created.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', timeZone: event.timeZone || 'America/Chicago' });
  const trig = TRIGGERS[event.trigger] || 'a check-in';
  const causeFn = CAUSES[event.cause] || CAUSES.noResponse;
  const lines = [`Still Here alert for ${name} at ${time}.`, causeFn(name, trig)];
  const lat = Number(event.latitude), lon = Number(event.longitude);
  if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
    lines.push(`Location: https://maps.google.com/?q=${lat.toFixed(5)},${lon.toFixed(5)}`);   // opens on iPhone and Android
  }
  // Campus mode: where they live and the campus police number. A bad field is dropped, never
  // allowed to block the alert.
  const campus = event.campus && typeof event.campus === 'object' ? event.campus : null;
  if (campus) {
    const home = cleanText(campus.home, 60);
    if (home) lines.push(`${name} lives at: ${home}`);
    const phone = typeof campus.policePhone === 'string' && /^\+[1-9]\d{9,14}$/.test(campus.policePhone) ? campus.policePhone : null;
    if (phone) {
      const police = cleanText(campus.policeName, 50) || 'Campus police';
      lines.push(`Campus police (${police}): ${formatPhone(phone)}. They can usually reach ${name} fastest on campus.`);
    }
  }
  lines.push(`Please call ${name} or go to them now. If you can't reach them and think they're in danger, call 911.`);
  return lines.join('\n');
}

// Letters, digits, spaces and a little punctuation. No dots, slashes or colons, so it can't become a link.
function cleanText(v, max) {
  return String(v ?? '').replace(/[^\p{L}\p{M}\p{N} ',#()\-]/gu, '').replace(/\s+/g, ' ').trim().slice(0, max);
}

function formatPhone(e164) {
  const m = /^\+1(\d{3})(\d{3})(\d{4})$/.exec(e164);
  return m ? `${m[1]}-${m[2]}-${m[3]}` : e164;
}

function validate(body) {
  if (!body || typeof body !== 'object') return 'Body must be JSON.';
  const { event, contacts } = body;
  if (!event || typeof event !== 'object') return 'Missing event.';
  if (typeof event.id !== 'string' || !/^[0-9A-Fa-f-]{36}$/.test(event.id)) return 'Missing or bad event id.';
  if (!Array.isArray(contacts) || contacts.length === 0) return 'No contacts.';
  if (contacts.length > MAX_CONTACTS) return `At most ${MAX_CONTACTS} contacts.`;
  for (const c of contacts) {
    if (!c || typeof c.phone !== 'string' || !/^\+[1-9]\d{7,14}$/.test(c.phone)) return 'Each contact needs a phone number like +16085551234.';
  }
  return null;
}

function createRelay({ token, sendSms, now = () => Date.now() }) {
  const seen = new Map();   // event id → time first handled

  async function handle(authHeader, body) {
    if (!tokenMatches(authHeader, token)) return { status: 401, json: { error: 'Wrong or missing token.' } };
    const problem = validate(body);
    if (problem) return { status: 400, json: { error: problem } };

    for (const [id, t] of seen) if (now() - t > SEEN_TTL_MS) seen.delete(id);
    const id = body.event.id.toLowerCase();
    if (seen.has(id)) return { status: 200, json: { ok: true, duplicate: true } };
    seen.set(id, now());

    const text = composeMessage(body.event);
    const phones = [...new Set(body.contacts.map(c => c.phone))];
    const results = await Promise.allSettled(phones.map(p => sendSms(p, text)));
    const sent = results.filter(r => r.status === 'fulfilled').length;
    if (sent === 0) {
      seen.delete(id);   // let a retry try again
      return { status: 502, json: { error: 'Could not send any texts.' } };
    }
    return { status: 200, json: { ok: true, sent, failed: phones.length - sent } };
  }
  return { handle };
}

function twilioSender({ sid, authToken, from }) {
  return async (to, body) => {
    const res = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${sid}/Messages.json`, {
      method: 'POST',
      headers: {
        Authorization: 'Basic ' + Buffer.from(`${sid}:${authToken}`).toString('base64'),
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      body: new URLSearchParams({ To: to, From: from, Body: body }),
    });
    if (!res.ok) throw new Error(`Twilio ${res.status}`);
  };
}

if (require.main === module) {
  const relay = createRelay({
    token: process.env.RELAY_TOKEN,
    sendSms: twilioSender({ sid: process.env.TWILIO_SID, authToken: process.env.TWILIO_TOKEN, from: process.env.TWILIO_FROM }),
  });
  http.createServer((req, res) => {
    if (req.method !== 'POST' || req.url !== '/alert') { res.writeHead(404).end(); return; }
    let raw = '';
    req.on('data', chunk => { raw += chunk; if (raw.length > 20000) req.destroy(); });
    req.on('end', async () => {
      let body = null;
      try { body = JSON.parse(raw); } catch {}
      const out = await relay.handle(req.headers.authorization, body);
      res.writeHead(out.status, { 'Content-Type': 'application/json' }).end(JSON.stringify(out.json));
    });
  }).listen(process.env.PORT || 8787);
}

module.exports = { composeMessage, validate, createRelay };
