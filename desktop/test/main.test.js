'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { DesktopController, writeExport, assetForUrl, authorizeSender } = require('../src/main');

async function folder(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androspecter-shell-test-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return fs.realpath(dir);
}
function controller(overrides = {}) {
  const settings = { java: '', platforms: '', memoryGb: 4, timeoutSeconds: 300 };
  return new DesktopController({ backend: { check: async () => ({}), cancel: async () => {}, shutdown: async () => {},
    discard: async () => {}, ...overrides.backend }, dialog: overrides.dialog || {}, clipboard: overrides.clipboard || {},
    window: () => null, settingsFile: '/unused', settings, version: '2.0.0' });
}

test('only bundled application assets can be loaded by the private protocol', () => {
  assert.equal(assetForUrl('androspecter://app/'), 'index.html');
  assert.equal(assetForUrl('androspecter://app/graph.js'), 'graph.js');
  for (const url of ['https://app/index.html', 'file:///etc/passwd', 'androspecter://other/renderer.js',
    'androspecter://app:123/index.html', 'androspecter://user@app/index.html',
    'androspecter://app/%2e%2e/settings.json', 'androspecter://app/settings.json', 'androspecter://app/__proto__']) {
    assert.equal(assetForUrl(url), null, url);
  }
});

test('IPC rejects foreign windows, child frames, and navigation to other origins', () => {
  const mainFrame = { url: 'androspecter://app/' };
  const contents = { mainFrame };
  assert.doesNotThrow(() => authorizeSender({ sender: contents, senderFrame: mainFrame }, contents));
  assert.throws(() => authorizeSender({ sender: {}, senderFrame: mainFrame }, contents));
  assert.throws(() => authorizeSender({ sender: contents, senderFrame: { url: mainFrame.url } }, contents));
  mainFrame.url = 'https://example.com/';
  assert.throws(() => authorizeSender({ sender: contents, senderFrame: mainFrame }, contents));
});

test('exports cannot replace input files via direct paths, symlinks, hard links, or directory aliases', async t => {
  const dir = await folder(t);
  const source = path.join(dir, 'original.apk');
  await fs.writeFile(source, 'ORIGINAL');
  await assert.rejects(writeExport(source, 'OVERWRITE', [source]), /input file/);
  const symbolic = path.join(dir, 'symbolic.json');
  let canSymlink = true;
  try { await fs.symlink(source, symbolic); }
  catch (error) {
    if (process.platform !== 'win32' || error.code !== 'EPERM') throw error;
    canSymlink = false;
    t.diagnostic('Windows account cannot create symlinks; direct-path and hard-link protections remain tested.');
  }
  if (canSymlink) await assert.rejects(writeExport(symbolic, 'OVERWRITE', [source]), /symbolic link/);
  const hard = path.join(dir, 'hard.json');
  await fs.link(source, hard);
  await assert.rejects(writeExport(hard, 'OVERWRITE', [source]), /input file/);
  const alias = path.join(dir, 'alias');
  if (canSymlink) {
    await fs.symlink(dir, alias, 'dir');
    await assert.rejects(writeExport(path.join(alias, 'original.apk'), 'OVERWRITE', [source]), /input file/);
  }
  assert.equal(await fs.readFile(source, 'utf8'), 'ORIGINAL');
  const output = path.join(dir, 'result.json');
  await fs.writeFile(output, 'OLD RESULT');
  assert.equal(await writeExport(output, 'NEW RESULT', [source]), output);
  assert.equal(await fs.readFile(output, 'utf8'), 'NEW RESULT');
  assert.equal((await fs.readdir(dir)).filter(name => name.startsWith('.androspecter')).length, 0);
});

test('cancelled source dialogs preserve the current APK and results', async () => {
  const app = controller({ dialog: { showOpenDialog: async () => ({ canceled: true, filePaths: [] }) } });
  const old = { data: { old: true } };
  app.apk = '/existing.apk';
  app.results.set('graph', old);
  assert.equal(await app.chooseApk(), null);
  assert.equal(app.apk, '/existing.apk');
  assert.equal(app.results.get('graph'), old);
});

test('source changes clear old results only after selecting a valid APK', async t => {
  const dir = await folder(t);
  const apk = path.join(dir, 'new.apk');
  await fs.writeFile(apk, 'APK');
  const discarded = [];
  const app = controller({ dialog: { showOpenDialog: async () => ({ canceled: false, filePaths: [apk] }) },
    backend: { discard: async result => discarded.push(result) } });
  const old = { data: { old: true } };
  app.results.set('graph', old);
  app.graphInput = '/old-graph.json';
  assert.deepEqual(await app.chooseApk(), { path: apk, name: 'new.apk' });
  assert.deepEqual(discarded, [old]);
  assert.equal(app.results.size, 0);
  assert.equal(app.graphInput, null);
});

test('failed graph import leaves the previous workspace usable', async () => {
  const app = controller({ dialog: { showOpenDialog: async () => ({ canceled: false, filePaths: ['/invalid.json'] }) },
    backend: { importGraph: async () => { throw new Error('Invalid graph JSON'); } } });
  app.apk = '/original.apk';
  app.results.set('metadata', { data: { packageName: 'original' } });
  await assert.rejects(app.importGraph(), /Invalid graph/);
  assert.equal(app.apk, '/original.apk');
  assert.equal(app.results.get('metadata').data.packageName, 'original');
});

test('busy analyses reject new source selection and preserve prior result on failure', async () => {
  let reject;
  const app = controller({ backend: { run: () => new Promise((_resolve, fail) => { reject = fail; }) } });
  app.apk = '/original.apk';
  const old = { data: { old: true } };
  app.results.set('graph', old);
  const running = app.run('graph');
  await Promise.resolve();
  await assert.rejects(app.chooseApk(), /current operation/);
  reject(new Error('Timed out'));
  await assert.rejects(running, /Timed out/);
  assert.equal(app.results.get('graph'), old);
  assert.equal(app.busy, false);
});

test('shutdown cancels and joins the active operation before cleaning temporary output', async () => {
  const calls = [];
  let reject;
  const app = controller({ backend: {
    run: () => new Promise((_resolve, fail) => { reject = fail; }),
    cancel: async () => { calls.push('cancel'); reject(new Error('Cancelled')); },
    shutdown: async () => { calls.push('cleanup'); }
  } });
  app.apk = '/original.apk';
  const running = app.run('graph').catch(error => { calls.push('settled'); return error; });
  await Promise.resolve();
  await app.shutdown();
  await running;
  assert.deepEqual(calls, ['cancel', 'settled', 'cleanup']);
  await assert.rejects(app.run('metadata'), /closing/);
});

test('run exports include detached provenance and the selected result, not an arbitrary renderer file', async t => {
  const dir = await folder(t);
  const destination = path.join(dir, 'run.json');
  const app = controller({ dialog: { showSaveDialog: async () => ({ canceled: false, filePath: destination }) } });
  app.results.set('graph', { data: { schemaVersion: 1, methods: [], edges: [] },
    provenance: { apkSha256: 'abc', command: 'java -jar cli.jar graph' } });
  assert.equal(await app.exportResult('graph', 'run'), destination);
  const saved = JSON.parse(await fs.readFile(destination, 'utf8'));
  assert.equal(saved.provenance.apkSha256, 'abc');
  assert.equal(saved.kind, 'graph');
  assert.deepEqual(saved.data.methods, []);
  await assert.rejects(app.exportResult('metadata', 'html'), /Unknown export/);
  await assert.rejects(app.exportResult('/etc/passwd', 'json'), /Unknown export/);
});

test('clipboard copying is restricted to a recorded result command', async () => {
  let text;
  const app = controller({ clipboard: { writeText: value => { text = value; } } });
  app.results.set('graph', { provenance: { command: "java -jar 'path with spaces.jar' graph" } });
  await app.copyCommand('graph');
  assert.equal(text, "java -jar 'path with spaces.jar' graph");
  await assert.rejects(app.copyCommand('echo injected'), /No command/);
});

test('concurrent shutdown callers both wait until backend cleanup completes', async () => {
  let finish;
  let cleaned = false;
  const app = controller({ backend: { shutdown: () => new Promise(resolve => { finish = () => { cleaned = true; resolve(); }; }) } });
  const first = app.shutdown();
  await Promise.resolve();
  let secondFinished = false;
  const second = app.shutdown().then(() => { secondFinished = true; });
  await Promise.resolve();
  assert.equal(secondFinished, false);
  finish();
  await Promise.all([first, second]);
  assert.equal(cleaned, true);
  assert.equal(secondFinished, true);
});
