'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');
const root = path.resolve(__dirname, '../src');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const script = fs.readFileSync(path.join(root, 'renderer.js'), 'utf8');
const graphScript = fs.readFileSync(path.join(root, 'graph.js'), 'utf8');
const methods = ['<demo.Main: void onCreate(android.os.Bundle)>', '<demo.Worker: void run()>', '<demo.Output: void send()>'];
const graph = { schemaVersion: 1, analysisId: '9d58ec11-ffdd-4b7e-a5ac-45a64acefe8e', methods, edges: [{ source: methods[0], target: methods[1], kind: 'VIRTUAL', callSite: '</code><img src=x onerror="window.compromised=true">', callSiteIndex: 7 }, { source: methods[1], target: methods[2], kind: 'STATIC', callSite: 'send()', callSiteIndex: 8 }] };
const metadata = { packageName: 'demo.<img onerror="window.compromised=true">', versionName: '1.0', versionCode: 1, minSdkVersion: 21, targetSdkVersion: 35, usesPermissions: [{ name: 'android.permission.INTERNET', tag: 'uses-permission' }], components: [{ name: 'demo.Main', kind: 'ACTIVITY', declaredExported: true }] };
const provenance = { command: 'java -jar androspecter-cli.jar graph', options: { packages: [] } };
const env = { javaOk: true, jarOk: true, platformsOk: true, java: '/runtime/bin/java', javaVersion: '17', errors: [] };
async function until(predicate) { for (let count = 0; count < 100; count++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); } throw new Error('UI did not reach expected state'); }
function setup(t, overrides = {}) {
  const calls = [];
  const dom = new JSDOM(html, { url: 'androspecter://app/', runScripts: 'outside-only', pretendToBeVisual: true });
  t.after(() => dom.window.close());
  const w = dom.window;
  w.HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  w.HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
  w.HTMLElement.prototype.scrollIntoView = function () {};
  w.androspecter = { platform: 'darwin', bootstrap: async () => ({ version: '2.0.0', settings: { java: '', platforms: '/sdk/platforms', memoryGb: 4, timeoutSeconds: 300 }, environment: env }), chooseApk: async () => ({ path: '/apps/demo.apk', name: 'demo.apk' }), metadata: async () => ({ data: metadata, provenance }), graph: async options => { calls.push(['graph', options]); return { data: graph, provenance }; }, inspect: async options => { calls.push(['inspect', options]); return { data: { classCount: 1, methodCount: 2, invocationCount: null, classes: [{ name: 'demo.Main', methodCount: 2, methods: methods.slice(0, 2) }] }, provenance }; }, importGraph: async () => ({ data: graph, provenance, name: 'saved.json' }), exportResult: async (kind, format) => { calls.push(['export', kind, format]); return '/exports/graph.' + format; }, copyCommand: async kind => calls.push(['copy', kind]), cancel: async () => {}, chooseSetting: async () => '/chosen', saveSettings: async settings => ({ settings, environment: env }), checkEnvironment: async () => env, onUpdate: () => () => {}, ...overrides };
  w.eval(graphScript); w.eval(script);
  return { w, doc: w.document, calls, get: id => w.document.getElementById(id) };
}

test('opening an APK loads actual manifest fields without executing manifest markup', async t => {
  const ui = setup(t); await until(() => ui.get('app-version').textContent === 'v2.0.0');
  ui.get('open-apk').click(); await until(() => !ui.get('overview-loaded').hidden);
  assert.equal(ui.get('summary-package').textContent, metadata.packageName);
  assert.match(ui.get('permission-list').textContent, /android.permission.INTERNET/);
  assert.equal(ui.get('permission-count').textContent, '1');
  assert.equal(ui.get('summary-package').querySelector('img'), null);
  assert.equal(ui.w.compromised, undefined);
  assert.equal(ui.get('inspect-code').disabled, false);
  assert.equal(ui.get('export-menu-toggle').disabled, false);
});

test('an imported graph needs no APK, supports navigation, paths, edge details, and exports', async t => {
  const ui = setup(t); await until(() => ui.get('app-version').textContent === 'v2.0.0');
  ui.get('import-graph').click(); await until(() => !ui.get('graph-loaded').hidden && !ui.get('export-menu-toggle').disabled);
  assert.equal(ui.get('apk-name').textContent, 'saved.json');
  assert.match(ui.get('graph-result-context').textContent, /Original APK, algorithm, package filters, and completion status are unknown/);
  assert.equal(ui.get('build-graph').disabled, true);
  assert.equal(ui.get('inspect-code').disabled, true);
  assert.equal(ui.get('selected-signature').textContent, methods[0]);
  assert.equal(ui.get('callees-count').textContent, '1');
  ui.get('callees-list').querySelector('.neighbor-inspect').click();
  assert.match(ui.get('edge-list').textContent, /Jimple unit 7/);
  assert.match(ui.get('edge-list').textContent, /<img/);
  assert.equal(ui.get('edge-list').querySelector('img'), null);
  ui.get('show-path').click(); ui.get('path-target').value = methods[2]; ui.get('find-path').click();
  assert.match(ui.get('path-result').textContent, /2 calls/);
  assert.equal(ui.get('path-result').querySelectorAll('.path-step').length, 3);
  ui.get('callees-list').querySelector('.graph-node').click();
  assert.equal(ui.get('selected-signature').textContent, methods[1]);
  ui.get('export-menu-toggle').click(); ui.doc.querySelector('[data-export="html"]').click();
  await until(() => ui.calls.some(call => call[0] === 'export'));
  assert.deepEqual(ui.calls.find(call => call[0] === 'export'), ['export', 'graph', 'html']);
});

test('failed replacement preserves the previous complete graph and exposes failure', async t => {
  let count = 0;
  const ui = setup(t, { graph: async () => { count++; if (count === 2) throw new Error('Analysis timed out'); return { data: graph, provenance: { ...provenance, options: { algorithm: 'SPARK', packages: ['demo.original'] } } };  } });
  await until(() => ui.get('app-version').textContent === 'v2.0.0');
  ui.get('open-apk').click(); await until(() => !ui.get('overview-loaded').hidden && !ui.get('build-graph').disabled);
  ui.doc.querySelector('[data-view="graph"]').click(); ui.get('build-graph').click();
  await until(() => !ui.get('graph-loaded').hidden && !ui.get('build-graph').disabled);
  assert.equal(ui.get('graph-result-context').textContent, 'SPARK · Packages: demo.original');
  ui.get('graph-algorithm').value = 'RTA'; ui.get('graph-packages').value = 'demo.changed';
  assert.equal(ui.get('graph-result-context').textContent, 'SPARK · Packages: demo.original');
  ui.get('build-graph').click(); await until(() => ui.get('notice-text').textContent.includes('previous completed result'));
  assert.equal(ui.get('graph-result-context').textContent, 'SPARK · Packages: demo.original');
  assert.equal(ui.get('graph-loaded').hidden, false);
  assert.match(ui.get('notice-text').textContent, /timed out/);
  assert.equal(ui.get('run-status-text').textContent, 'Time limit reached');
  assert.equal(ui.get('export-menu-toggle').disabled, false);
});

test('code inspection passes explicit scope and lazy renders method inventory', async t => {
  const ui = setup(t); await until(() => ui.get('app-version').textContent === 'v2.0.0');
  ui.get('open-apk').click(); await until(() => !ui.get('overview-loaded').hidden && !ui.get('inspect-code').disabled);
  ui.doc.querySelector('[data-view="code"]').click(); ui.get('code-packages').value = 'demo, another.package'; ui.get('code-invocations').checked = true; ui.get('inspect-code').click();
  await until(() => !ui.get('code-loaded').hidden && !ui.get('inspect-code').disabled);
  const options = ui.calls.find(call => call[0] === 'inspect')[1];
  assert.deepEqual(Array.from(options.packages), ['demo', 'another.package']); assert.equal(options.invocations, true);
  assert.equal(ui.get('class-list').querySelector('ul'), null);
  ui.get('class-list').querySelector('details').open = true;
  await until(() => ui.get('class-list').querySelector('ul'));
  assert.equal(ui.get('class-list').querySelectorAll('li').length, 2);
});

test('cancelling an operation returns control without a fabricated result', async t => {
  let rejectGraph;
  const ui = setup(t, { graph: () => new Promise((resolve, reject) => { rejectGraph = reject; }), cancel: async () => rejectGraph(new Error('CANCELLED: Operation cancelled')) });
  await until(() => ui.get('app-version').textContent === 'v2.0.0'); ui.get('open-apk').click();
  await until(() => !ui.get('overview-loaded').hidden && !ui.get('build-graph').disabled); ui.doc.querySelector('[data-view="graph"]').click(); ui.get('build-graph').click();
  await until(() => rejectGraph); assert.equal(ui.get('open-apk').disabled, true); ui.get('cancel-run').click();
  await until(() => !ui.get('build-graph').disabled);
  assert.equal(ui.get('graph-loaded').hidden, true); assert.equal(ui.get('cancel-run').hidden, true); assert.equal(ui.get('export-menu-toggle').disabled, true); assert.match(ui.get('notice-text').textContent, /cancelled/);
});
