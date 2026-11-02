// Tests for the relay. Run: node relay/test.js
const assert = require('node:assert/strict');
const { composeMessage, validate, createRelay } = require('./server.js');

const id = '3F2504E0-4F89-11D3-9A0C-0305E82C3301';
const event = {
  id, wearerName: 'Jess', trigger: 'heartRateChange', cause: 'noResponse',
  createdAt: '2026-11-03T05:42:00Z', timeZone: 'America/Chicago', latitude: 44.2619, longitude: -88.4154,
};
const contacts = [{ name: 'Maya', phone: '+16085551234' }, { name: 'Sam', phone: '+19205550000' }];
let pass = 0;
async function t(name, fn) {
  try { await fn(); pass++; console.log('PASS ', name); }
  catch (e) { console.log('FAIL ', name, '\n      ', e.message); process.exitCode = 1; }
}

(async () => {
  await t('message has name, local time, reason, map link, and 911 advice', () => {
    const m = composeMessage(event);
    assert.match(m, /Still Here alert for Jess at 11:42 PM\./);
    assert.match(m, /No response to a check-in after an unusual change in heart rate\./);
    assert.match(m, /maps\.google\.com\/\?q=44\.26190,-88\.41540/);
    assert.match(m, /call 911/);
    assert.ok(m.length < 320, `message is ${m.length} chars; keep it to ~2 SMS segments`);
  });
  await t('help-button message', () => {
    assert.match(composeMessage({ ...event, cause: 'askedForHelp', trigger: 'helpButton' }), /Jess asked for help\./);
  });
  await t('no location → no map line, still sends', () => {
    const m = composeMessage({ ...event, latitude: undefined, longitude: undefined });
    assert.doesNotMatch(m, /maps/);
  });
  await t('bad coordinates are dropped', () => {
    assert.doesNotMatch(composeMessage({ ...event, latitude: 999, longitude: 5 }), /maps/);
    assert.doesNotMatch(composeMessage({ ...event, latitude: 'x', longitude: 'y' }), /maps/);
  });
  await t('name is cleaned: no links or injected text', () => {
    const m = composeMessage({ ...event, wearerName: 'Jess http://evil.example/win-a-prize' });
    assert.doesNotMatch(m, /http:\/\/evil/);
    assert.doesNotMatch(m, /\.example/, 'no dots, so phones cannot turn it into a link');
  });
  await t('accented names survive', () => {
    assert.match(composeMessage({ ...event, wearerName: 'Zoë Núñez' }), /Zoë Núñez/);
  });
  await t('unknown trigger/cause fall back to safe wording', () => {
    const m = composeMessage({ ...event, trigger: 'zzz', cause: 'yyy' });
    assert.match(m, /No response to a check-in after a check-in\./);
  });
  await t('missing date uses now', () => {
    assert.match(composeMessage({ ...event, createdAt: 'nonsense' }), /at \d{1,2}:\d{2} [AP]M/);
  });

  await t('campus mode adds home and campus police lines', () => {
    const m = composeMessage({ ...event, campus: { policeName: 'Harvard University Police', policePhone: '+16174951212', home: 'Quincy House, entry B' } });
    assert.match(m, /Jess lives at: Quincy House, entry B/);
    assert.match(m, /Campus police \(Harvard University Police\): 617-495-1212\. They can usually reach Jess fastest on campus\./);
    assert.ok(m.indexOf('Campus police') < m.indexOf('call 911'), 'campus line comes before the 911 advice');
    assert.ok(m.length < 480, `campus message is ${m.length} chars; keep it to ~3 SMS segments`);
  });
  await t('campus: bad police number is dropped, alert still sent', () => {
    const m = composeMessage({ ...event, campus: { policeName: 'X', policePhone: 'call me maybe' } });
    assert.doesNotMatch(m, /Campus police/); assert.match(m, /call 911/);
  });
  await t('campus: missing name gets a sensible default', () => {
    assert.match(composeMessage({ ...event, campus: { policePhone: '+12034324400' } }), /Campus police \(Campus police\): 203-432-4400/);
  });
  await t('campus: text fields cannot carry links', () => {
    const m = composeMessage({ ...event, campus: { policeName: 'Police https://evil.example', policePhone: '+12034324400', home: 'www.evil.example/x' } });
    assert.doesNotMatch(m, /evil\.example|https:\/\/evil|www\./);
  });
  await t('campus: not an object is ignored', () => {
    assert.doesNotMatch(composeMessage({ ...event, campus: 'Harvard' }), /Campus police/);
  });

  await t('validate rejects bad input', () => {
    assert.ok(validate(null));
    assert.ok(validate({ event: { ...event, id: 'short' }, contacts }));
    assert.ok(validate({ event, contacts: [] }));
    assert.ok(validate({ event, contacts: [{ phone: '6085551234' }] }), 'needs +country code');
    assert.ok(validate({ event, contacts: Array(6).fill(contacts[0]) }), 'max 5 contacts');
    assert.equal(validate({ event, contacts }), null);
  });

  const sent = [];
  let failNumbers = new Set();
  const relay = createRelay({
    token: 'secret',
    sendSms: async (to, text) => { if (failNumbers.has(to)) throw new Error('fail'); sent.push({ to, text }); },
  });

  await t('wrong or missing token → 401, nothing sent', async () => {
    assert.equal((await relay.handle('Bearer nope', { event, contacts })).status, 401);
    assert.equal((await relay.handle(undefined, { event, contacts })).status, 401);
    assert.equal(sent.length, 0);
  });
  await t('good request texts every contact once', async () => {
    const r = await relay.handle('Bearer secret', { event, contacts });
    assert.equal(r.status, 200); assert.equal(r.json.sent, 2); assert.equal(sent.length, 2);
  });
  await t('same alert from watch and phone is sent only once', async () => {
    const r = await relay.handle('Bearer secret', { event: { ...event, id: id.toLowerCase() }, contacts });
    assert.equal(r.json.duplicate, true); assert.equal(sent.length, 2);
  });
  await t('duplicate phone numbers in the list are texted once', async () => {
    sent.length = 0;
    const id2 = '3F2504E0-4F89-11D3-9A0C-0305E82C3302';
    await relay.handle('Bearer secret', { event: { ...event, id: id2 }, contacts: [contacts[0], contacts[0]] });
    assert.equal(sent.length, 1);
  });
  await t('one number fails → others still sent, reports the failure', async () => {
    sent.length = 0; failNumbers = new Set([contacts[0].phone]);
    const r = await relay.handle('Bearer secret', { event: { ...event, id: '3F2504E0-4F89-11D3-9A0C-0305E82C3303' }, contacts });
    assert.equal(r.status, 200); assert.equal(r.json.sent, 1); assert.equal(r.json.failed, 1);
  });
  await t('all numbers fail → 502, and a retry is allowed', async () => {
    failNumbers = new Set(contacts.map(c => c.phone));
    const ev = { ...event, id: '3F2504E0-4F89-11D3-9A0C-0305E82C3304' };
    assert.equal((await relay.handle('Bearer secret', { event: ev, contacts })).status, 502);
    failNumbers = new Set(); sent.length = 0;
    const r = await relay.handle('Bearer secret', { event: ev, contacts });
    assert.equal(r.status, 200); assert.equal(sent.length, 2);
  });
  await t('relay with no token configured refuses everything', async () => {
    const open = createRelay({ token: '', sendSms: async () => { throw new Error('should not send'); } });
    assert.equal((await open.handle('Bearer ', { event, contacts })).status, 401);
  });

  console.log(`\n${pass} relay tests passed${process.exitCode ? ', some failed' : ''}.`);
})();
