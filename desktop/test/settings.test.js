'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { validateSettings, defaultSettings, readSettings, writeSettings } = require('../src/settings');
const defaults = { java: '', platforms: '', memoryGb: 4, timeoutSeconds: 300 };

async function folder(t) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'androspecter-settings-test-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  return dir;
}

test('settings validate resource limits and reject unknown data or invalid paths', () => {
  assert.deepEqual(validateSettings({ ...defaults, java: ' /jdk/bin/java ', extra: 'ignored' }), { ...defaults, java: '/jdk/bin/java' });
  for (const change of [{ memoryGb: 0 }, { memoryGb: 65 }, { memoryGb: 1.5 }, { timeoutSeconds: -1 },
    { timeoutSeconds: 86401 }, { timeoutSeconds: NaN }, { java: ['java'] }, { platforms: 'a\0b' }]) {
    assert.throws(() => validateSettings({ ...defaults, ...change }));
  }
});

test('SDK discovery honors environment roots and requires a platforms directory', async t => {
  const dir = await folder(t);
  const missing = path.join(dir, 'first');
  const sdk = path.join(dir, 'second');
  await fs.mkdir(path.join(sdk, 'platforms'), { recursive: true });
  assert.equal((await defaultSettings({ ANDROID_HOME: missing, ANDROID_SDK_ROOT: sdk }, dir)).platforms, path.join(sdk, 'platforms'));
  assert.equal((await defaultSettings({}, dir)).platforms, '');
});

test('settings writes are atomic and invalid or corrupt persisted values recover defaults', async t => {
  const dir = await folder(t);
  const filename = path.join(dir, 'profile', 'settings.json');
  assert.deepEqual(await readSettings(filename, defaults), defaults);
  const saved = { ...defaults, memoryGb: 8, java: '/jdk/bin/java' };
  assert.deepEqual(await writeSettings(filename, saved), saved);
  assert.deepEqual(await readSettings(filename, defaults), saved);
  if (process.platform !== 'win32') assert.equal((await fs.stat(filename)).mode & 0o777, 0o600);
  await assert.rejects(writeSettings(filename, { ...saved, timeoutSeconds: -1 }));
  assert.deepEqual(await readSettings(filename, defaults), saved);
  await fs.writeFile(filename, '{ broken');
  assert.deepEqual(await readSettings(filename, defaults), defaults);
  assert.deepEqual(await fs.readdir(path.dirname(filename)), ['settings.json']);
});
