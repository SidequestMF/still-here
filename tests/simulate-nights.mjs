// Runs simulated nights through the detection logic and checks what fires and when.
// Usage: node tests/simulate-nights.mjs   (exits non-zero if any scenario fails)
import { HeartRateWatcher, MotionWatcher, passes } from './detection-mirror.mjs';

// Deterministic random numbers so every run is identical.
function rng(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 2 ** 32; };
}
function gauss(r) { return Math.sqrt(-2 * Math.log(r() || 1e-9)) * Math.cos(2 * Math.PI * r()); }

// Motion generators: user acceleration in g (gravity removed), sampled at 10 Hz.
const motionKinds = {
  dancing: (r) => Math.abs(0.35 + 0.15 * gauss(r)),
  walking: (r) => Math.abs(0.15 + 0.05 * gauss(r)),
  // standing and talking: small noise plus a gesture burst every ~10–60 s
  talking: (r, st) => {
    if (st.burst > 0) { st.burst--; return Math.abs(0.12 + 0.05 * gauss(r)); }
    if (r() < 1 / 300) st.burst = 10 + Math.floor(r() * 25);
    return Math.abs(0.012 + 0.006 * gauss(r));
  },
  // seated at a table, quieter: gesture every ~1–3 minutes
  seated: (r, st) => {
    if (st.burst > 0) { st.burst--; return Math.abs(0.1 + 0.04 * gauss(r)); }
    if (r() < 1 / 1200) st.burst = 10 + Math.floor(r() * 20);
    return Math.abs(0.008 + 0.004 * gauss(r));
  },
  // seated, staring at phone: a real move only every ~5–15 minutes
  scrolling: (r, st) => {
    if (st.burst > 0) { st.burst--; return Math.abs(0.1 + 0.04 * gauss(r)); }
    if (r() < 1 / 6000) st.burst = 15 + Math.floor(r() * 20);
    return Math.abs(0.01 + 0.004 * gauss(r));
  },
  slumped: (r) => Math.abs(0.004 + 0.002 * gauss(r)),
  off: () => 0,
};

// segments: [{ from, to (minutes), motion, hr: [startBpm, endBpm] | null }]
// events:   [{ at (minutes), impact: g }]  and  hrGlitches: [{ at, value }]
function runNight({ name, minutes, segments, events = [], hrGlitches = [], seed = 1, stopAtFirst = false, trace = null }) {
  const r = rng(seed);
  const hrw = new HeartRateWatcher(0);
  const mw = new MotionWatcher(0);
  const st = { burst: 0 };
  const fired = [];
  const pending = events.map(e => ({ ...e, t: e.at * 60 }));
  const glitches = hrGlitches.map(g => ({ ...g, t: g.at * 60 }));

  for (let i = 0; i <= minutes * 600; i++) {
    const t = i / 10;
    const seg = segments.find(s => t >= s.from * 60 && t < s.to * 60) || segments[segments.length - 1];
    let g = motionKinds[seg.motion](r, st);
    const ev = pending.find(e => !e.done && t >= e.t);
    if (ev) { g = ev.impact; ev.done = true; }
    const m = mw.add(g, t);
    if (trace) trace.push(`M ${t} ${g}`);
    if (m) { fired.push({ type: m, min: t / 60 }); if (stopAtFirst) break; }

    if (i % 50 === 0 && seg.hr) {            // heart rate every 5 seconds
      const p = (t - seg.from * 60) / ((seg.to - seg.from) * 60);
      let bpm = seg.hr[0] + (seg.hr[1] - seg.hr[0]) * Math.min(1, Math.max(0, p)) + 3 * gauss(r);
      const gl = glitches.find(x => !x.done && t >= x.t);
      if (gl) { bpm = gl.value; gl.done = true; }
      if (trace) trace.push(`H ${t} ${bpm}`);
      if (hrw.add(bpm, t, mw.secondsSinceMovement(t), mw.intensity)) {
        fired.push({ type: 'heartRate', min: t / 60 });
        hrw.snooze(t);                        // wearer passed the check and carried on
        if (stopAtFirst) break;
      }
    }
  }
  return { name, fired, baseline: hrw.baseline };
}

const scenarios = [
  {
    name: 'Normal night out (4 h: talk, dance, sit, walk)',
    // A rare extra check-in is OK (it's one quick tap test). An alert would not be.
    expect: { maxCount: 1 },
    minutes: 240, seed: 11,
    segments: [
      { from: 0, to: 30, motion: 'talking', hr: [88, 90] },
      { from: 30, to: 90, motion: 'dancing', hr: [118, 128] },
      { from: 90, to: 135, motion: 'seated', hr: [86, 82] },
      { from: 135, to: 150, motion: 'walking', hr: [100, 100] },
      { from: 150, to: 210, motion: 'dancing', hr: [120, 126] },
      { from: 210, to: 240, motion: 'walking', hr: [102, 98] },
    ],
  },
  {
    name: 'Drugged: heart rate falls 95→58 and she slumps (symptoms start at 75 min)',
    expect: { anyOf: ['heartRate', 'stillness'], within: [75, 105] },
    minutes: 150, seed: 12, stopAtFirst: true,
    segments: [
      { from: 0, to: 40, motion: 'talking', hr: [90, 92] },
      { from: 40, to: 75, motion: 'dancing', hr: [118, 115] },
      { from: 75, to: 85, motion: 'talking', hr: [100, 80] },
      { from: 85, to: 95, motion: 'seated', hr: [80, 60] },
      { from: 95, to: 150, motion: 'slumped', hr: [60, 56] },
    ],
  },
  {
    name: 'Drugged, small heart-rate change (only stillness gives it away)',
    expect: { anyOf: ['stillness', 'heartRate'], within: [60, 115] },
    minutes: 150, seed: 13, stopAtFirst: true,
    segments: [
      { from: 0, to: 60, motion: 'talking', hr: [90, 90] },
      { from: 60, to: 85, motion: 'seated', hr: [90, 80] },
      { from: 85, to: 150, motion: 'slumped', hr: [80, 76] },
    ],
  },
  {
    name: 'Drugged after 2 hours of dancing (no recent calm moments to compare with)',
    expect: { anyOf: ['heartRate', 'stillness'], within: [130, 160] },
    minutes: 200, seed: 25, stopAtFirst: true,
    segments: [
      { from: 0, to: 20, motion: 'talking', hr: [88, 88] },
      { from: 20, to: 140, motion: 'dancing', hr: [120, 125] },
      { from: 140, to: 150, motion: 'seated', hr: [95, 60] },
      { from: 150, to: 200, motion: 'slumped', hr: [60, 55] },
    ],
  },
  {
    name: 'Falls while dancing and stays down',
    expect: { anyOf: ['stumble'], within: [50, 50.5] },
    minutes: 60, seed: 14, stopAtFirst: true,
    segments: [
      { from: 0, to: 50, motion: 'dancing', hr: [115, 120] },
      { from: 50, to: 60, motion: 'slumped', hr: [100, 85] },
    ],
    events: [{ at: 50, impact: 3.6 }],
  },
  {
    name: 'Hard bump while dancing (high-five, arm hits table), keeps dancing',
    expect: { none: true },
    minutes: 60, seed: 15,
    segments: [{ from: 0, to: 60, motion: 'dancing', hr: [115, 120] }],
    events: [{ at: 20, impact: 3.2 }, { at: 35, impact: 4.0 }, { at: 50, impact: 2.8 }],
  },
  {
    name: 'Trips, gets right back up after 3 seconds',
    expect: { none: true },
    minutes: 40, seed: 16,
    segments: [
      { from: 0, to: 30, motion: 'walking', hr: [100, 100] },
      { from: 30, to: 30.05, motion: 'slumped', hr: [100, 100] },
      { from: 30.05, to: 40, motion: 'walking', hr: [100, 100] },
    ],
    events: [{ at: 30, impact: 3.4 }],
  },
  {
    name: 'Heart-rate sensor glitches (0, 300, missing values)',
    expect: { none: true },
    minutes: 60, seed: 17,
    segments: [{ from: 0, to: 60, motion: 'talking', hr: [90, 90] }],
    hrGlitches: [{ at: 15, value: 0 }, { at: 20, value: 300 }, { at: 25, value: NaN }, { at: 30, value: 30 }],
  },
  {
    name: 'Loose watch: heart rate reads 50 for one minute, then normal',
    expect: { none: true },
    minutes: 40, seed: 18,
    segments: [
      { from: 0, to: 20, motion: 'talking', hr: [90, 90] },
      { from: 20, to: 21, motion: 'talking', hr: [50, 50] },
      { from: 21, to: 40, motion: 'talking', hr: [90, 90] },
    ],
  },
  {
    name: 'Racing heart while sitting still (panic or stimulant)',
    expect: { anyOf: ['heartRate'], within: [30, 36] },
    minutes: 50, seed: 19, stopAtFirst: true,
    segments: [
      { from: 0, to: 25, motion: 'talking', hr: [88, 90] },
      { from: 25, to: 30, motion: 'slumped', hr: [90, 140] },
      { from: 30, to: 50, motion: 'slumped', hr: [140, 140] },
    ],
  },
  {
    name: 'High heart rate from dancing (moving, so not suspicious)',
    expect: { none: true },
    minutes: 90, seed: 20,
    segments: [
      { from: 0, to: 15, motion: 'talking', hr: [85, 88] },
      { from: 15, to: 90, motion: 'dancing', hr: [130, 155] },
    ],
  },
  {
    name: 'Nervous arrival: heart rate 118 for first 10 min, then relaxed 80',
    expect: { none: true },
    minutes: 120, seed: 21,
    segments: [
      { from: 0, to: 10, motion: 'walking', hr: [118, 118] },
      { from: 10, to: 120, motion: 'talking', hr: [82, 78] },
    ],
  },
  {
    name: 'Calm evening: sits with heart rate in the low 60s for an hour',
    expect: { maxCount: 2 },
    minutes: 120, seed: 22,
    segments: [
      { from: 0, to: 30, motion: 'talking', hr: [92, 92] },
      { from: 30, to: 40, motion: 'seated', hr: [92, 62] },
      { from: 40, to: 120, motion: 'seated', hr: [62, 62] },
    ],
  },
  {
    name: 'Sits very still scrolling a phone for an hour',
    expect: { maxCount: 3 },
    minutes: 90, seed: 23,
    segments: [
      { from: 0, to: 30, motion: 'talking', hr: [88, 88] },
      { from: 30, to: 90, motion: 'scrolling', hr: [80, 80] },
    ],
  },
  {
    name: 'Takes the watch off and leaves it on a table (motion only; the app also checks when heart rate stops)',
    expect: { anyOf: ['stillness'], within: [45, 46] },
    minutes: 60, seed: 24, stopAtFirst: true,
    segments: [
      { from: 0, to: 30, motion: 'dancing', hr: [115, 115] },
      { from: 30, to: 60, motion: 'off', hr: null },
    ],
  },
];

const SEEDS = Number(process.env.SEEDS || 1);

// TRACE_DIR=dir writes each scenario's exact inputs and results, so other ports (Kotlin)
// can replay them and prove they make identical decisions.
if (process.env.TRACE_DIR) {
  const fs = await import('node:fs');
  fs.mkdirSync(process.env.TRACE_DIR, { recursive: true });
  scenarios.forEach((sc, i) => {
    for (let k = 0; k < 5; k++) {
      const trace = [];
      const res = runNight({ ...sc, seed: sc.seed + k * 7919, trace });
      const fired = res.fired.map(f => `${f.type}@${f.min.toFixed(4)}`).join(' ');
      fs.writeFileSync(`${process.env.TRACE_DIR}/s${String(i).padStart(2, '0')}-${k}.txt`,
        `# ${sc.name}\nSTOP ${sc.stopAtFirst ? 1 : 0}\nEXPECT ${fired}\n` + trace.join('\n') + '\n');
    }
  });
  console.log(`Wrote traces for ${scenarios.length} scenarios x 5 runs to ${process.env.TRACE_DIR}`);
  process.exit(0);
}
let failures = 0;
const rows = [];
function judge(sc, res) {
  if (sc.expect.none) return res.fired.length === 0;
  if (sc.expect.maxCount != null) return res.fired.length <= sc.expect.maxCount;
  const f = res.fired[0];
  return !!f && sc.expect.anyOf.includes(f.type) && f.min >= sc.expect.within[0] && f.min <= sc.expect.within[1];
}
for (const sc of scenarios) {
  let passed = 0, example = null, lastFail = null; const firstAt = [];
  for (let k = 0; k < SEEDS; k++) {
    const res = runNight({ ...sc, seed: sc.seed + k * 7919 });
    const ok = judge(sc, res);
    if (ok) passed++; else lastFail = res;
    if (sc.expect.anyOf && res.fired[0]) firstAt.push(res.fired[0].min);
    if (k === 0) example = res;
  }
  const show = lastFail || example;
  const types = show.fired.map(f => `${f.type}@${f.min.toFixed(1)}m`).join(', ') || 'nothing';
  const ok = passed === SEEDS;
  if (!ok) failures++;
  rows.push(`${ok ? 'PASS' : 'FAIL'}  ${sc.name}` + (SEEDS > 1 ? `  [${passed}/${SEEDS} runs]` : '') +
    `\n      ${lastFail ? 'failing run' : 'example'}: ${types}  (baseline ${show.baseline ? Math.round(show.baseline) : '-'} bpm)` +
    (firstAt.length > 1 ? `\n      first check-in across runs: earliest ${Math.min(...firstAt).toFixed(1)}m, median ${firstAt.sort((a,b)=>a-b)[firstAt.length>>1].toFixed(1)}m, latest ${Math.max(...firstAt).toFixed(1)}m` : ''));
}

// Response-test rule
const rule = [
  [1.8, 0, 1.8, true, 'same speed as baseline'],
  [3.9, 0, 1.8, true, '2.2x baseline, just under the limit'],
  [4.4, 0, 1.8, false, 'slower than baseline + 2.5 s'],
  [2.0, 3, 1.8, false, 'fast but 3 wrong taps'],
  [2.0, 2, 1.8, true, 'fast with 2 wrong taps'],
  [6.0, 0, 3.0, true, 'slow baseline person, 2x'],
  [7.0, 0, 3.0, false, 'slow baseline person, 2.3x'],
  [5.0, 0, null, true, 'no baseline recorded: never fails'],
  [Infinity, 0, 1.8, true, 'bad timing value: does not fail on its own'],
];
for (const [s, e, b, want, label] of rule) {
  const got = passes(s, e, b);
  const ok = got === want;
  if (!ok) failures++;
  rows.push(`${ok ? 'PASS' : 'FAIL'}  Response rule: ${label} → ${got ? 'pass' : 'fail'}`);
}

console.log(rows.join('\n'));
console.log(`\n${failures === 0 ? 'All scenarios passed.' : failures + ' scenario(s) failed.'}`);
process.exit(failures ? 1 : 0);
