// Real-browser check of the virtual clock (src/core/animscreen.ts HARNESS_JS / prepareHtml). Needs Playwright + Chromium; NOT part of `npm test`.
//   NODE_PATH=$(npm root -g) PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node tests/browser/harness.test.js
const fs = require('fs'), path = require('path'), vm = require('vm'), assert = require('assert'); const ts = require('typescript');
const root = path.join(__dirname, '..', '..', 'src'); const cache = {};
function load(file) { file = path.resolve(file); if (cache[file]) return cache[file].exports; const out = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2019 } }).outputText; const mod = { exports: {} }; cache[file] = mod; const req = (p) => (p.startsWith('.') ? load(path.resolve(path.dirname(file), p + '.ts')) : require(p)); vm.runInNewContext(out, { module: mod, exports: mod.exports, require: req, console, Date, Math, JSON, String, Number, Array, Object, isFinite, Boolean }); return mod.exports; }
const A = load(path.join(root, 'core', 'animscreen.ts'));
const { chromium } = require('playwright');
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
let passed = 0; const ok = (c, m) => { assert.ok(c, m); passed++; };
const near = (a, b, tol, m) => { assert.ok(Math.abs(a - b) <= tol, `${m}: ${a} vs ${b}`); passed++; };
(async () => {
  const browser = await chromium.launch(); const ctx = await browser.newContext({ viewport: { width: 240, height: 320 } });
  const open = async (html, scripts = true) => { const p = await ctx.newPage(); await p.setContent(A.prepareHtml(html, scripts)); await p.waitForFunction('document.readyState==="complete"'); if (!scripts) await p.evaluate(A.HARNESS_JS); return p; };
  const left = (p, sel) => p.evaluate((s) => document.querySelector(s).getBoundingClientRect().left, sel);

  // 1. CSS keyframes advance only when stepped, deterministically, from time zero
  let p = await open('<style>body{margin:0}#b{position:absolute;top:0;width:10px;height:10px;background:red;animation:mv 2s linear infinite}@keyframes mv{from{left:0}to{left:200px}}</style><div id=b></div>');
  await p.waitForTimeout(400);   // real time passes; nothing may move
  await p.evaluate('__meow.step(0)'); near(await left(p, '#b'), 0, 0.5, 'css anim starts at 0 regardless of real time');
  await p.evaluate('__meow.step(500)'); near(await left(p, '#b'), 50, 1, 'css anim at 500 ms');
  await p.evaluate('__meow.step(500)'); near(await left(p, '#b'), 100, 1, 'css anim at 1000 ms');
  await p.evaluate('__meow.step(1000)'); near(await left(p, '#b'), 0, 1, 'css anim loops at 2000 ms');
  let m = await p.evaluate('__meow.measure()'); ok(m.anims === 1 && m.infiniteCycle === 2000, 'measure: one infinite 2 s animation ' + JSON.stringify(m));
  await p.waitForTimeout(300); near(await left(p, '#b'), 0, 1, 'real time does not move a stepped animation'); await p.close();

  // 2. finite animation with delay: measure and final state
  p = await open('<style>body{margin:0}#b{position:absolute;top:0;width:10px;height:10px;animation:mv 1s ease-in 500ms 1 forwards}@keyframes mv{from{left:0}to{left:100px}}</style><div id=b></div>');
  await p.evaluate('__meow.step(0)'); m = await p.evaluate('__meow.measure()'); ok(m.finiteEnd === 1500 && m.infiniteCycle === 0, 'finite end includes the delay ' + JSON.stringify(m));
  await p.evaluate('__meow.step(400)'); near(await left(p, '#b'), 0, 0.5, 'still in the delay'); await p.evaluate('__meow.step(1200)'); near(await left(p, '#b'), 100, 0.5, 'fill forwards at the end'); await p.close();

  // 3. JS: requestAnimationFrame, setTimeout, setInterval, Date, performance.now all follow the virtual clock
  p = await open('<style>body{margin:0}#r{position:absolute;top:0;left:0;width:10px;height:10px;background:blue}</style><div id=r></div><div id=n>0</div><script>' +
    'var t0=performance.now();function f(ts){document.getElementById("r").style.left=((ts-t0)/10)+"px";requestAnimationFrame(f)}requestAnimationFrame(f);' +
    'var c=0;setInterval(function(){document.getElementById("n").textContent=String(++c)},250);window.d0=Date.now();</script>');
  await p.waitForTimeout(300); ok((await p.evaluate('document.getElementById("n").textContent')) === '0', 'no real-time ticking');
  await p.evaluate('__meow.step(0)'); await p.evaluate('__meow.step(1000)'); ok((await p.evaluate('document.getElementById("n").textContent')) === '4', 'interval fired 4 times in 1 s');
  near(await left(p, '#r'), 100, 1, 'rAF timestamp is virtual (1000 ms -> 100 px)'); ok((await p.evaluate('Date.now()-d0')) === 1000, 'Date.now is virtual');
  m = await p.evaluate('__meow.measure()'); ok(m.raf === 1 && m.intervals === 1, 'a running rAF loop and an interval are reported ' + JSON.stringify(m)); await p.close();

  // 4. scripts off: the page's own script must not run, CSS animation can still be stepped
  p = await open('<style>body{margin:0}#b{position:absolute;top:0;width:10px;height:10px;animation:mv 2s linear infinite}@keyframes mv{from{left:0}to{left:200px}}</style><div id=b></div><div id=s>untouched</div><script>document.getElementById("s").textContent="script ran"</script>', false);
  ok((await p.evaluate('document.getElementById("s").textContent')) === 'untouched', 'page script is blocked when scripts are off');
  await p.evaluate('__meow.step(0)'); await p.evaluate('__meow.step(1000)'); near(await left(p, '#b'), 100, 1, 'css animation still steps with scripts off'); await p.close();

  // 5. images: data URI loads, ready() waits for it; lazy images are made eager
  p = await open('<body style="margin:0"><img id=i src="' + PNG + '" width=20 height=20><img id=l loading=lazy src="' + PNG + '" width=10 height=10 style="margin-top:900px"></body>');
  await p.waitForFunction('__meow.ready()', null, { timeout: 4000 }); ok(await p.evaluate('document.getElementById("i").naturalWidth===1'), 'data: image decoded'); ok(await p.evaluate('document.getElementById("l").complete'), 'lazy image forced to load'); await p.close();

  // 6. a transition started from a timer is captured at the right moment
  p = await open('<style>body{margin:0}#t{position:absolute;top:0;left:0;width:10px;height:10px;transition:left 1s linear}</style><div id=t></div><script>setTimeout(function(){document.getElementById("t").style.left="100px"},500)</script>');
  await p.evaluate('__meow.step(0)'); await p.evaluate('__meow.step(500)'); await p.evaluate('__meow.step(500)'); near(await left(p, '#t'), 50, 6, 'transition halfway 500 ms after it started'); await p.evaluate('__meow.step(600)'); near(await left(p, '#t'), 100, 0.5, 'transition finished'); await p.close();

  // 7. capture to pixels: the screenshot of a stepped frame is deterministic
  p = await open('<style>body{margin:0;background:#101820}#b{position:absolute;top:100px;width:40px;height:40px;background:#ffd166;animation:mv 1s linear infinite}@keyframes mv{from{left:0}to{left:200px}}</style><div id=b></div>');
  const shot = async () => (await p.screenshot()).toString('base64');
  await p.evaluate('__meow.step(0)'); await p.evaluate('__meow.step(250)'); const a1 = await shot(); await p.waitForTimeout(200); const a2 = await shot(); ok(a1 === a2, 'two screenshots of the same virtual time are identical'); await p.evaluate('__meow.step(250)'); ok((await shot()) !== a1, 'next step differs'); await p.close();
  await browser.close(); console.log(`harness (real Chromium): ${passed} checks passed`);
})().catch((e) => { console.error('FAIL', e.message); process.exit(1); });
