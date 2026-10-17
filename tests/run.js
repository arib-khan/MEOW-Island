// Runs the pure-logic tests with plain Node: `npm test`. TypeScript sources are transpiled on the fly.
const fs = require('fs'), path = require('path'), vm = require('vm');
const ts = require('typescript');
const cache = {};
function load(file) {
  file = path.resolve(file);
  if (cache[file]) return cache[file].exports;
  const out = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2019 } }).outputText;
  const mod = { exports: {} }; cache[file] = mod;
  const req = (p) => (p.startsWith('.') ? load(path.resolve(path.dirname(file), p.endsWith('.ts') ? p : p + '.ts')) : require(p));
  vm.runInNewContext(out, { module: mod, exports: mod.exports, require: req, console, Date, Math, JSON, String, Number, Array, Object, isFinite, Boolean });
  return mod.exports;
}
let passed = 0, failed = 0;
global.test = (name, fn) => { try { fn(); passed++; } catch (e) { failed++; console.error('FAIL', name, '\n  ', e.message); } };
global.assert = require('assert');
global.src = (p) => load(path.join(__dirname, '..', 'src', p));
for (const f of fs.readdirSync(__dirname).filter((x) => x.endsWith('.test.js'))) require(path.join(__dirname, f));
console.log(`${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
