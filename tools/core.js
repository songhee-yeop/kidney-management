// 신장 관리 앱 — 순수 로직 (파싱 · eGFR · KFRE). 브라우저와 node 양쪽에서 동작.
(function (root) {
  const FIELDS = [
    // 한 줄이 여러 이름에 걸리면 위에 있는 항목이 우선
    { key: 'acr', re: /(u(?:rine)?[\s.\-]*(?:micro)?alb(?:umin)?\s*\/\s*cr(?:eatinine)?(?:\s*ratio)?|micro\s*alb(?:umin)?\s*\/\s*cr\w*|alb(?:umin)?\s*\/\s*cr(?:eatinine)?\s*ratio|\bu?acr\b|(?:요|소변)?\s*(?:미세)?알부민\s*\/\s*크레아티닌\s*비?)/i, range: [0.1, 20000] },
    { key: 'egfr', re: /(e[\s\-]*gfr|사구체\s*여과율)/i, range: [1, 200] },
    { key: 'cacorr', re: /(corrected\s*ca(?:lcium)?|ca(?:lcium)?\s*\(?\s*corrected|보정\s*칼슘)/i, range: [4, 16] },
    { key: 'bic', re: /(total\s*co2|t\.?\s*co2|hco3-?|bicarbonate|중탄산염?|총\s*이산화탄소|\bco2\b)/i, range: [5, 50] },
    { key: 'uric', re: /(uric\s*acid|요산)/i, range: [0.5, 20], skip: /urine|소변/i },
    { key: 'bun', re: /(\bbun\b|urea\s*nitrogen|요소\s*질소)/i, range: [1, 200], skip: /ratio|\/\s*cr/i },
    { key: 'phos', re: /(phosph\w*|inorganic\s*p(?:hos)?\b|\bi\.?p\b|\bphos\b|무기\s*인|인\s*\(\s*p\s*\))/i, range: [0.5, 15] },
    { key: 'ca', re: /(calcium|\bca\b|칼슘)/i, range: [4, 16], skip: /ion|이온|corrected|보정|urine|소변/i },
    { key: 'k', re: /(potassium|\bk\+?\b|칼륨)/i, range: [1.5, 9], skip: /urine|소변/i },
    { key: 'hb', re: /(hemoglobin|haemoglobin|\bhgb\b|\bhb\b|혈색소)/i, range: [3, 22], skip: /a1c|당화/i },
    { key: 'alb', re: /(albumin|\balb\b|알부민)/i, range: [1, 6.5], skip: /urine|소변|micro|미세|ratio|\/\s*cr|\bu[\s.\-]|globulin|a\/g/i },
    { key: 'cr', re: /(creatinine|\bcr\b|크레아티닌)/i, range: [0.1, 20], skip: /urine|소변|ratio|\bu[\s.\-]|clearance|\/\s*cr|alb|bun/i },
  ];
  const NUM = /(?<![A-Za-z\d.])(\d+(?:\.\d+)?)/g;
  const DATE = /(20\d{2})\s*[.\-\/년]\s*(\d{1,2})\s*[.\-\/월]\s*(\d{1,2})/;

  function matchField(line) {
    for (const f of FIELDS) {
      const m = line.match(f.re);
      if (m && !(f.skip && f.skip.test(line))) return { f, m };
    }
    return null;
  }
  function numbersIn(s) {
    s = s.replace(/\([^)]*\)/g, ' ').replace(/\[[^\]]*\]/g, ' ');
    return [...s.matchAll(NUM)].map(x => parseFloat(x[1]));
  }
  function unitFix(key, v, ctx) {
    const c = ctx.toLowerCase();
    if (key === 'acr' && /mg\s*\/\s*mmol/.test(c)) return v * 8.84;
    if ((key === 'ca' || key === 'cacorr') && /mmol/.test(c) && v < 4) return v * 4.008;
    if (key === 'phos' && /mmol/.test(c) && v < 3) return v * 3.097;
    if (key === 'alb' && /g\s*\/\s*l\b/.test(c) && v > 10) return v / 10;
    if (key === 'cr' && /(µ|u)mol/.test(c) && v > 20) return v / 88.4;
    return v;
  }
  function toISO(m) { return `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`; }

  // 한 묶음(하루치) 텍스트 → {date?, labs}
  function parseLabs(text) {
    const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
    const labs = {};
    const dm = text.match(DATE);
    for (let i = 0; i < lines.length; i++) {
      const hit = matchField(lines[i]);
      if (!hit || labs[hit.f.key] != null) continue;
      const { f, m } = hit;
      const pool = [lines[i].slice(m.index + m[0].length)];
      for (let j = i + 1; j < Math.min(lines.length, i + 4); j++) { if (matchField(lines[j])) break; pool.push(lines[j]); }
      const ctx = pool.join(' ');
      let found = null;
      outer: for (const seg of pool) for (const n of numbersIn(seg)) {
        const v = unitFix(f.key, n, ctx);
        if (v >= f.range[0] && v <= f.range[1]) { found = v; break outer; }
      }
      if (found != null) labs[f.key] = Math.round(found * 1000) / 1000;
    }
    return { date: dm ? toISO(dm) : null, labs };
  }

  // 여러 날짜가 섞인 텍스트 → 날짜별 묶음
  function parseMany(text) {
    const lines = text.split(/\r?\n/);
    const blocks = [];
    let cur = [];
    for (const line of lines) {
      if (DATE.test(line) && !matchField(line) && cur.some(l => matchField(l))) { blocks.push(cur.join('\n')); cur = []; }
      cur.push(line);
    }
    if (cur.length) blocks.push(cur.join('\n'));
    return blocks.map(parseLabs).filter(b => Object.keys(b.labs).length);
  }

  function ageAt(birth, date) {
    const b = new Date(birth), d = new Date(date);
    let a = d.getFullYear() - b.getFullYear();
    if (d.getMonth() < b.getMonth() || (d.getMonth() === b.getMonth() && d.getDate() < b.getDate())) a--;
    return a;
  }
  function ckdEpi2021(scr, age, male) {
    const k = male ? 0.9 : 0.7, a = male ? -0.302 : -0.241;
    return 142 * Math.pow(Math.min(scr / k, 1), a) * Math.pow(Math.max(scr / k, 1), -1.2) * Math.pow(0.9938, age) * (male ? 1 : 1.012);
  }
  // Tangri KFRE, 비북미 보정
  function kfre(p) {
    const full = [p.alb, p.phos, p.bic, p.cacorr].every(v => v != null && !isNaN(v));
    const x = [p.age / 10, p.male, p.egfr / 5, Math.log(p.acr), p.cacorr, p.phos, p.alb, p.bic];
    let b, mu, s2, s5, n;
    if (full) {
      b = [-0.1992, 0.1602, -0.4919, 0.3364, -0.2201, 0.2604, -0.3441, -0.07354];
      mu = [7.036, 0.5642, 7.222, 5.137, 9.355, 3.916, 3.997, 25.57];
      s2 = 0.9827; s5 = 0.9245; n = 8;
    } else {
      b = [-0.2201, 0.2467, -0.5567, 0.4510]; mu = [7.036, 0.5642, 7.222, 5.137];
      s2 = 0.9832; s5 = 0.9365; n = 4;
    }
    let lp = 0; for (let i = 0; i < n; i++) lp += b[i] * (x[i] - mu[i]);
    return { model: n, r2: (1 - Math.pow(s2, Math.exp(lp))) * 100, r5: (1 - Math.pow(s5, Math.exp(lp))) * 100 };
  }
  // 기록 하나 + 프로필 → 계산된 값(eGFR, 보정칼슘, 위험도)
  function evaluate(rec, profile) {
    const male = profile.sex === 'M' ? 1 : 0;
    const age = ageAt(profile.birth, rec.date);
    const L = rec.labs;
    let egfr = L.egfr, egfrCalc = false;
    if (egfr == null && L.cr != null && age > 0) { egfr = ckdEpi2021(L.cr, age, male); egfrCalc = true; }
    let cacorr = L.cacorr;
    if (cacorr == null && L.ca != null && L.alb != null) cacorr = L.ca + 0.8 * (4.0 - L.alb);
    const out = { age, male, egfr, egfrCalc, cacorr, risk: null };
    if (egfr != null && L.acr != null && age > 0) out.risk = kfre({ age, male, egfr, acr: L.acr, alb: L.alb, phos: L.phos, bic: L.bic, cacorr });
    return out;
  }
  function gStage(e) { return e >= 90 ? 'G1' : e >= 60 ? 'G2' : e >= 45 ? 'G3a' : e >= 30 ? 'G3b' : e >= 15 ? 'G4' : 'G5'; }
  function aStage(a) { return a < 30 ? 'A1' : a <= 300 ? 'A2' : 'A3'; }

  const api = { FIELDS, parseLabs, parseMany, ageAt, ckdEpi2021, kfre, evaluate, gStage, aStage };
  if (typeof module !== 'undefined') module.exports = api; else root.Core = api;
})(this);
