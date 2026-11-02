// Line-by-line JavaScript mirror of apple/Shared/Detection.swift and the Tuning values in
// apple/Shared/Models.swift. Used to run long simulated nights quickly on any machine.
// If you change the Swift logic, change this file to match (and the Swift tests too).

export const Tuning = {
  heartRateDropFraction: 0.30,
  heartRateRiseFraction: 0.45,
  heartRateSustainSeconds: 120,
  heartRateOutShare: 0.8,
  heartRateSnoozeMinutes: 20,
  acceptedMinutes: 60,
  calmIntensityG: 0.1,
  calmWindowMinutes: 60,
  calmLagMinutes: 20,
  calmMinReadings: 60,
  impactG: 2.5,
  postImpactStillSeconds: 10,
  stillnessMinutes: 15,
  movingMeanG: 0.04,
  failMultiplier: 2.2,
  failMinExtraSeconds: 2.5,
  maxTestErrors: 2,
};

const median = xs => { const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };

export class HeartRateWatcher {
  constructor(start) { this.start = start; this.calm = []; this.recent = []; this.accepted = null; this.snoozedUntil = -Infinity; this.baseline = null; }
  add(bpm, now, secondsSinceMovement, intensity) {
    if (!Number.isFinite(bpm) || !(bpm > 20) || !(bpm < 250)) return false;
    if (intensity < Tuning.calmIntensityG) this.calm.push({ t: now, bpm });
    this.calm = this.calm.filter(c => now - c.t <= Tuning.calmWindowMinutes * 60);
    this.baseline = this.currentBaseline(now);
    let out = false;
    if (this.baseline !== null) {
      const base = this.baseline;
      const tooLow = bpm < base * (1 - Tuning.heartRateDropFraction);
      const tooHighWhileStill = secondsSinceMovement > 60 && bpm > base * (1 + Tuning.heartRateRiseFraction);
      out = tooLow || tooHighWhileStill;
    }
    this.recent.push({ t: now, bpm, out });
    this.recent = this.recent.filter(r => now - r.t <= Tuning.heartRateSustainSeconds);
    if (now < this.snoozedUntil) return false;
    const first = this.recent[0];
    if (!first || now - first.t < Tuning.heartRateSustainSeconds - 10) return false;
    const share = this.recent.filter(r => r.out).length / this.recent.length;
    return share >= Tuning.heartRateOutShare;
  }
  currentBaseline(now) {
    const lagged = this.calm.filter(c => now - c.t >= Tuning.calmLagMinutes * 60).map(c => c.bpm);
    let base = null;
    if (lagged.length >= Tuning.calmMinReadings) base = median(lagged);
    else if (now - this.start < (Tuning.calmLagMinutes + 20) * 60) {
      const early = this.calm.filter(c => now - c.t >= 120).map(c => c.bpm);
      if (early.length >= Tuning.calmMinReadings) base = median(early);
    }
    if (base !== null) this.lastCalmBaseline = base;
    if (base === null && this.lastCalmBaseline != null) base = this.lastCalmBaseline;
    if (this.accepted && now < this.accepted.until) base = this.accepted.bpm;
    return base;
  }
  snooze(now) {
    if (this.recent.length) this.accepted = { bpm: median(this.recent.map(r => r.bpm)), until: now + Tuning.acceptedMinutes * 60 };
    this.snoozedUntil = now + Tuning.heartRateSnoozeMinutes * 60;
    this.recent = [];
  }
}

export class MotionWatcher {
  constructor(start) { this.window = []; this.lastMovementAt = start; this.impactAt = null; this.intensity = 0; }
  secondsSinceMovement(now) { return now - this.lastMovementAt; }
  add(g, now) {
    if (!Number.isFinite(g) || g < 0) return null;
    this.window.push(g);
    if (this.window.length > 100) this.window.shift();
    this.intensity += (Math.min(g, 1.5) - this.intensity) / 600;
    if (g > Tuning.impactG) this.impactAt = now;
    const recent = this.window.slice(-20);
    const recentMean = recent.reduce((a, b) => a + b, 0) / recent.length;
    const inImpactWindow = this.impactAt !== null && now - this.impactAt < 2;
    if (recentMean > Tuning.movingMeanG && !inImpactWindow) this.lastMovementAt = now;
    if (this.impactAt !== null && now - this.impactAt >= Tuning.postImpactStillSeconds) {
      const hit = this.impactAt; this.impactAt = null;
      if (now - this.lastMovementAt >= Tuning.postImpactStillSeconds - 2) return 'stumble';
    }
    if (now - this.lastMovementAt > Tuning.stillnessMinutes * 60) { this.lastMovementAt = now; return 'stillness'; }
    return null;
  }
}

export function passes(seconds, errors, baseline) {
  if (baseline == null || !(baseline > 0) || !Number.isFinite(seconds)) return true;
  const limit = Math.max(baseline * Tuning.failMultiplier, baseline + Tuning.failMinExtraSeconds);
  return seconds <= limit && errors <= Tuning.maxTestErrors;
}
