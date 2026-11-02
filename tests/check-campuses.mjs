// Checks campuses/campuses.json and that the web prototype's copy matches it exactly.
// Usage: node tests/check-campuses.mjs
import fs from 'node:fs';
const root = new URL('..', import.meta.url).pathname;
const data = JSON.parse(fs.readFileSync(root + 'campuses/campuses.json', 'utf8'));
const phone = /^\+[1-9]\d{9,14}$/;
let bad = 0;
const fail = (msg) => { console.log('FAIL  ' + msg); bad++; };

const ids = new Set();
for (const c of data.campuses) {
  if (!/^[a-z0-9-]+$/.test(c.id) || ids.has(c.id)) fail(`${c.id}: id must be unique lowercase`);
  ids.add(c.id);
  if (!c.name) fail(`${c.id}: missing name`);
  if (!c.police?.name || !phone.test(c.police?.phone || '')) fail(`${c.id}: police needs a name and a +1... phone`);
  for (const k of ['ride', 'support']) {
    if (c[k] && (!c[k].name || !phone.test(c[k].phone || ''))) fail(`${c.id}: ${k} needs a name and a +1... phone`);
  }
  if (!Array.isArray(c.sources) || !c.sources.length || !c.sources.every(u => u.startsWith('https://'))) fail(`${c.id}: needs https sources`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(c.verified || '')) fail(`${c.id}: verified date YYYY-MM-DD`);
  const age = (Date.now() - Date.parse(c.verified)) / 864e5;
  if (age > 365) fail(`${c.id}: last verified over a year ago; re-check the numbers`);
}

// The prototype embeds a copy between CAMPUSES-START and CAMPUSES-END markers.
const html = fs.readFileSync(root + 'web-prototype/index.html', 'utf8');
const m = html.match(/\/\* CAMPUSES-START \*\/([\s\S]*?)\/\* CAMPUSES-END \*\//);
if (!m) fail('prototype has no campus list');
else if (JSON.stringify(JSON.parse(m[1])) !== JSON.stringify(data.campuses)) fail('prototype campus list differs from campuses.json');

console.log(bad ? `${bad} problem(s).` : `${data.campuses.length} campuses OK; prototype copy matches.`);
process.exit(bad ? 1 : 0);
