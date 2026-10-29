'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { Backend } = require('../src/backend');

const graph = { schemaVersion: 1, analysisId: '00000000-0000-0000-0000-000000000001', methods: ['<demo.Main: void start()>', '<demo.Main: void end()>'], edges: [{ source: '<demo.Main: void start()>', target: '<demo.Main: void end()>', kind: 'STATIC', callSite: 'invoke', callSiteIndex: 1 }] };
const childProgram = `
const fs = require('node:fs');
const args = JSON.parse(process.argv[1]), behavior = JSON.parse(process.argv[2]), graph = JSON.parse(process.argv[3]);
if (args.includes('-version') && behavior.mode === 'slow-version') { process.on('SIGTERM', () => {}); process.stderr.write('VERSION_RUNNING'); setInterval(() => {}, 100); }
else if (args.includes('-version')) { process.stderr.write('openjdk version "' + (behavior.javaVersion || '17.0.14') + '"\\n'); }
else if (args[0] === '-v') { process.exitCode = 1; }
else {
 const input = args.includes('--apk') ? args[args.indexOf('--apk') + 1] : args[args.indexOf('--input-json') + 1];
 const format = args[args.indexOf('--format') + 1];
 const output = args[args.indexOf('-o') + 1];
 if (behavior.mode === 'error') { process.stderr.write('fixture engine failure'); process.exitCode = 3; }
 else if (behavior.mode === 'invalid') fs.writeFileSync(output, '{bad JSON');
 else if (behavior.mode === 'slow') { process.on('SIGTERM', () => {}); process.stderr.write('RUNNING\\n'); setInterval(() => {}, 100); }
 else {
  let data;
  if (args.includes('--input-json')) {
   const source = JSON.parse(fs.readFileSync(input, 'utf8'));
   if (source.schemaVersion !== 1 || !Array.isArray(source.methods) || !Array.isArray(source.edges)) { process.stderr.write('Invalid graph JSON'); process.exit(2); }
   data = source;
  } else data = args.includes('graph') ? graph : args.includes('inspect') ? {classCount:1, methodCount:2, classes:[]} : {packageName:'demo.app', snapshot:fs.readFileSync(input,'utf8')};
  if (behavior.mode === 'large-log') process.stderr.write('a'.repeat(2 * 1024 * 1024));
  process.stderr.write('fixture diagnostic\\n');
  fs.writeFileSync(output, format === 'json' ? JSON.stringify(data) : format === 'html' ? '<html>saved graph</html>' : 'digraph {}');
 }
}
`;

async function fixture(t, behavior = {}, overrides = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'androspecter-backend-test-'));
  const jar = path.join(root, 'engine.jar'), apk = path.join(root, "app ';$(touch BAD).apk"), platforms = path.join(root, 'sdk platforms');
  await fs.writeFile(jar, 'fixture engine'); await fs.writeFile(apk, 'original APK bytes');
  await fs.mkdir(path.join(platforms, 'android-35'), { recursive: true }); await fs.writeFile(path.join(platforms, 'android-35', 'android.jar'), 'fixture platform');
  const calls = [], children = [], updates = [];
  const backend = new Backend({ jar, defaultJava: '/fixture/java', platform: 'linux', temporaryRoot: root, killGraceMs: 60,
    spawn(executable, args, options) {
      calls.push({ executable, args, options });
      const child = spawn(process.execPath, ['-e', childProgram, JSON.stringify(args), JSON.stringify(behavior), JSON.stringify(graph)], options);
      children.push(child); child.stderr.on('data', value => { if (value.toString().includes('VERSION_RUNNING')) updates.push({ type: 'fixture-version-ready' }); }); return child;
    }, emit: update => updates.push(update), ...overrides });
  t.after(async () => { await backend.shutdown(); await fs.rm(root, { recursive: true, force: true }); });
  return { root, jar, apk, platforms, backend, calls, children, updates, behavior, settings: { java: '', platforms, memoryGb: 2, timeoutSeconds: 5 } };
}
function assertStopped(child) {
  assert.ok(child.exitCode !== null || child.signalCode !== null, 'the child process must have exited');
  // Windows emulates SIGTERM by terminating the process immediately, so the grace
  // period need not reach SIGKILL. POSIX fixtures deliberately ignore SIGTERM.
  if (process.platform !== 'win32') assert.equal(child.signalCode, 'SIGKILL');
}
async function waitFor(condition) {
  const deadline = Date.now() + 5000;
  while (!condition()) { if (Date.now() > deadline) throw new Error('Timed out waiting for fixture'); await new Promise(resolve => setTimeout(resolve, 10)); }
}

test('metadata snapshots and hashes the original APK; command arguments never enter a shell', async t => {
  const f = await fixture(t);
  const result = await f.backend.run('metadata', f.apk, { ...f.settings, platforms: '' });
  assert.equal(result.data.packageName, 'demo.app');
  assert.equal(result.provenance.apkSha256, crypto.createHash('sha256').update('original APK bytes').digest('hex'));
  assert.equal(await fs.readFile(f.apk, 'utf8'), 'original APK bytes');
  assert.notEqual(f.calls.at(-1).args[f.calls.at(-1).args.indexOf('--apk') + 1], f.apk);
  assert.equal(f.calls.at(-1).options.shell, false);
  assert.match(result.provenance.command, /touch BAD/);
  assert.ok(!result.provenance.command.includes(result.directory));
  assert.match(result.provenance.diagnostics, /fixture diagnostic/);
  assert.equal(f.updates.some(update => update.type === 'phase'), true);
  // Windows chmod does not implement POSIX owner/group permission bits.
  if (process.platform !== 'win32') assert.equal(await fs.stat(result.directory).then(stat => stat.mode & 0o777), 0o700);
});

test('class inspection and graph options use their distinct CLI arguments', async t => {
  const f = await fixture(t);
  const result = await f.backend.run('inspect', f.apk, f.settings, { packages: ['demo.app.'], invocations: true });
  const args = f.calls.at(-1).args;
  assert.ok(args.includes('--list-methods')); assert.ok(args.includes('--invocations')); assert.ok(!args.includes('--algorithm'));
  assert.equal(args[args.indexOf('--package') + 1], 'demo.app');
  await f.backend.discard(result);
  await assert.rejects(fs.access(result.directory));
  const next = await f.backend.run('graph', f.apk, f.settings, { algorithm: 'SPARK', packages: ['demo.app'] });
  assert.equal(f.calls.at(-1).args[f.calls.at(-1).args.indexOf('--algorithm') + 1], 'SPARK');
  assert.deepEqual(next.data, graph); assert.match(next.provenance.scope, /crossing selected packages/);
});

test('input and settings errors fail before any analysis process starts', async t => {
  const f = await fixture(t);
  for (const settings of [{ ...f.settings, memoryGb: -1 }, { ...f.settings, timeoutSeconds: 0.5 }]) await assert.rejects(f.backend.run('metadata', f.apk, settings), { code: 'INVALID_INPUT' });
  await assert.rejects(f.backend.run('graph', f.apk, f.settings, { algorithm: 'bad' }), { code: 'INVALID_INPUT' });
  await assert.rejects(f.backend.run('inspect', f.apk, { ...f.settings, platforms: f.root }), { code: 'SDK_MISSING' });
  await assert.rejects(f.backend.run('metadata', f.root, f.settings), { code: 'INVALID_INPUT' });
  assert.equal(f.calls.length, 0);
});

test('readiness distinguishes missing platforms, old Java, and missing engine', async t => {
  const f = await fixture(t);
  let status = await f.backend.check({ ...f.settings, platforms: '' });
  assert.equal(status.javaOk, true); assert.equal(status.jarOk, true); assert.equal(status.platformsOk, false);
  f.behavior.javaVersion = '1.8.0_400';
  status = await f.backend.check(f.settings);
  assert.equal(status.javaOk, false); assert.match(status.errors.join(' '), /Java 17/);
  await fs.rm(f.jar); f.behavior.javaVersion = '21.0.1';
  status = await f.backend.check(f.settings);
  assert.equal(status.javaOk, true); assert.equal(status.jarOk, false); assert.equal(status.platformsOk, true);
});

test('cancel waits for process close and prevents a second operation during termination', async t => {
  const f = await fixture(t, { mode: 'slow' });
  const running = assert.rejects(f.backend.run('graph', f.apk, f.settings), { code: 'CANCELLED' });
  await waitFor(() => f.updates.some(update => update.text?.includes('RUNNING')));
  let closed = false; f.children.at(-1).once('close', () => { closed = true; });
  const cancelling = f.backend.cancel();
  await assert.rejects(f.backend.run('metadata', f.apk, f.settings), { code: 'BUSY' });
  await cancelling; await running;
  assert.equal(closed, true); assert.equal(f.backend.job, null); assert.equal(f.backend.directories.size, 0);
  f.behavior.mode = 'normal';
  assert.equal((await f.backend.run('metadata', f.apk, f.settings)).data.packageName, 'demo.app');
});

test('hard timeout kills a non-cooperating process and removes failed result directories', async t => {
  const f = await fixture(t, { mode: 'slow' });
  const started = Date.now();
  await assert.rejects(f.backend.run('inspect', f.apk, { ...f.settings, timeoutSeconds: 1 }), { code: 'TIMED_OUT' });
  assert.ok(Date.now() - started >= 1000); assert.ok(Date.now() - started < 5000);
  assertStopped(f.children.at(-1)); assert.equal(f.backend.directories.size, 0);
});

test('CLI failure and malformed output never become successful results', async t => {
  const f = await fixture(t, { mode: 'error' });
  await assert.rejects(f.backend.run('metadata', f.apk, f.settings), /fixture engine failure/);
  assert.equal(f.backend.results.size, 0); assert.equal(f.backend.directories.size, 0);
  f.behavior.mode = 'invalid';
  await assert.rejects(f.backend.run('metadata', f.apk, f.settings), /invalid JSON/);
  assert.equal(f.backend.directories.size, 0);
});

test('graph imports and exports validate saved files without requiring an SDK', async t => {
  const f = await fixture(t), saved = path.join(f.root, 'saved graph.json');
  await fs.writeFile(saved, JSON.stringify(graph));
  const config = { ...f.settings, platforms: '' };
  const result = await f.backend.importGraph(saved, config);
  assert.deepEqual(result.data, graph); assert.equal(result.provenance.imported, true); assert.match(result.provenance.scope, /unknown/);
  const html = await f.backend.exportGraph(result, 'html', config);
  assert.equal(await fs.readFile(html, 'utf8'), '<html>saved graph</html>');
  assert.ok(!f.calls.at(-1).args.includes('--platforms'));
  assert.equal(await f.backend.exportGraph(result, 'json', config), result.output);
  await assert.rejects(f.backend.exportGraph({ ...result, output: saved }, 'html', config), { code: 'INVALID_INPUT' });
  await assert.rejects(f.backend.exportGraph(result, 'exe', config), { code: 'INVALID_INPUT' });
  await fs.writeFile(saved, '{"schemaVersion":99}');
  await assert.rejects(f.backend.importGraph(saved, config), /Invalid graph JSON/);
});

test('oversized saved graphs are rejected without starting Java', async t => {
  const f = await fixture(t), file = path.join(f.root, 'large.json');
  const handle = await fs.open(file, 'w'); await handle.truncate(64 * 1024 * 1024 + 1); await handle.close();
  await assert.rejects(f.backend.importGraph(file, f.settings), { code: 'RESULT_TOO_LARGE' }); assert.equal(f.calls.length, 0);
});

test('activity logging is bounded and preserves a tail for failure diagnostics', async t => {
  const f = await fixture(t, { mode: 'large-log' });
  const result = await f.backend.run('metadata', f.apk, f.settings);
  assert.ok(f.updates.filter(update => update.type === 'log').reduce((n, update) => n + update.text.length, 0) < 1024 * 1024 + 200);
  assert.match(f.updates.map(update => update.text || '').join(''), /output capped/);
  assert.ok(result.provenance.diagnostics.length <= 12000); assert.match(result.provenance.diagnostics, /fixture diagnostic/);
});

test('shutdown stops an active JVM before deleting owned files and rejects further runs', async t => {
  const f = await fixture(t, { mode: 'slow' });
  const running = assert.rejects(f.backend.run('metadata', f.apk, f.settings), { code: 'CANCELLED' });
  await waitFor(() => f.updates.some(update => update.text?.includes('RUNNING')));
  await f.backend.shutdown(); await running;
  assert.equal(f.backend.directories.size, 0); assertStopped(f.children.at(-1));
  await assert.rejects(f.backend.run('metadata', f.apk, f.settings), { code: 'CLOSED' });
  assert.equal(await fs.readFile(f.apk, 'utf8'), 'original APK bytes');
});

test('real missing Java executable produces a useful readiness error without hanging', async t => {
  const f = await fixture(t, {}, { spawn });
  const status = await f.backend.check({ ...f.settings, java: path.join(f.root, 'missing-java') });
  assert.equal(status.javaOk, false); assert.match(status.errors.join(' '), /Java 17/);
});


test('readiness probes are cancellable and shutdown waits for their process exit', async t => {
  const f = await fixture(t, { mode: 'slow-version' });
  const checking = assert.rejects(f.backend.check(f.settings), { code: 'CANCELLED' });
  await waitFor(() => f.updates.some(update => update.type === 'fixture-version-ready'));
  await f.backend.shutdown(); await checking;
  assertStopped(f.children.at(-1));
  await assert.rejects(f.backend.check(f.settings), { code: 'CLOSED' });
});

test('bundled macOS Java is preferred without invoking the system JDK locator', async t => {
  const f = await fixture(t, {}, { platform: 'darwin', env: {} });
  assert.equal((await f.backend.check(f.settings)).javaOk, true);
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].executable, '/fixture/java');
});
