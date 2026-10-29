'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { randomUUID } = require('node:crypto');

/**
 * Validate and normalize the desktop preferences without launching any process.
 * @param {object} value Preferences supplied by the settings form.
 * @returns {{java:string, platforms:string, memoryGb:number, timeoutSeconds:number}} Normalized preferences.
 * @throws {Error} When a value is invalid or outside the supported bounds.
 */
function validateSettings(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid settings.');
  for (const key of ['java', 'platforms']) {
    if (typeof value[key] !== 'string' || value[key].length > 4096 || value[key].includes('\0')) {
      throw new Error(`Choose a valid ${key === 'java' ? 'Java executable' : 'Android platforms folder'}.`);
    }
  }
  const memoryGb = Number(value.memoryGb);
  const timeoutSeconds = Number(value.timeoutSeconds);
  if (!Number.isInteger(memoryGb) || memoryGb < 1 || memoryGb > 64) throw new Error('Memory must be a whole number from 1 to 64 GB.');
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 10 || timeoutSeconds > 86400) {
    throw new Error('Time limit must be a whole number from 10 to 86400 seconds.');
  }
  return { java: value.java.trim(), platforms: value.platforms.trim(), memoryGb, timeoutSeconds };
}

/**
 * Find an existing Android platforms folder using standard SDK locations.
 * @param {object} [env=process.env] Environment to inspect; useful for an isolated launcher or tests.
 * @param {string} [home=os.homedir()] Home folder used for platform-specific defaults.
 * @returns {Promise<object>} Default preferences; an absent SDK remains an empty string.
 */
async function defaultSettings(env = process.env, home = os.homedir()) {
  const roots = [env.ANDROID_HOME, env.ANDROID_SDK_ROOT, path.join(home, 'Library/Android/sdk'),
    path.join(home, 'Android/Sdk'), env.LOCALAPPDATA && path.join(env.LOCALAPPDATA, 'Android/Sdk')].filter(Boolean);
  let platforms = '';
  for (const root of roots) {
    const candidate = path.join(root, 'platforms');
    try { if ((await fs.stat(candidate)).isDirectory()) { platforms = candidate; break; } } catch { /* Try the next SDK root. */ }
  }
  return { java: '', platforms, memoryGb: 4, timeoutSeconds: 300 };
}

/**
 * Load persisted preferences, retaining usable defaults if the file is missing or corrupt.
 * @param {string} filename Preferences JSON filename.
 * @param {object} [defaults] Explicit defaults, otherwise standard SDK discovery is used.
 * @returns {Promise<object>} Validated preferences.
 */
async function readSettings(filename, defaults) {
  const fallback = defaults || await defaultSettings();
  try { return validateSettings({ ...fallback, ...JSON.parse(await fs.readFile(filename, 'utf8')) }); }
  catch { return validateSettings(fallback); }
}

/**
 * Write validated preferences atomically, with owner-only permissions where supported.
 * @param {string} filename Preferences JSON filename in the application's data directory.
 * @param {object} value New preferences.
 * @returns {Promise<object>} The normalized values that were written.
 * @throws {Error} When validation or writing fails.
 */
async function writeSettings(filename, value) {
  const settings = validateSettings(value);
  await fs.mkdir(path.dirname(filename), { recursive: true });
  const temporary = path.join(path.dirname(filename), `.settings-${randomUUID()}.tmp`);
  try {
    await fs.writeFile(temporary, JSON.stringify(settings, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    await fs.rename(temporary, filename);
  } finally { await fs.rm(temporary, { force: true }); }
  return settings;
}

module.exports = { validateSettings, defaultSettings, readSettings, writeSettings };
