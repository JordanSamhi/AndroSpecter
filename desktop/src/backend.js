'use strict';

const fs = require('node:fs/promises');
const { createReadStream, createWriteStream } = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { createHash } = require('node:crypto');
const { Transform } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { spawn } = require('node:child_process');

const RESULT_LIMIT = 64 * 1024 * 1024;
const LOG_LIMIT = 1024 * 1024;
const TAIL_LIMIT = 12000;
const ALGORITHMS = new Set(['CHA', 'RTA', 'VTA', 'SPARK']);

function failure(message, code = 'ANALYSIS_FAILED') {
  return Object.assign(new Error(message), { code });
}
function text(value, name, fallback = '') {
  if (value === undefined) return fallback;
  if (typeof value !== 'string' || value.length > 4096 || value.includes('\0')) throw failure(`Invalid ${name}.`, 'INVALID_INPUT');
  return value.trim();
}
function settingsOf(settings = {}) {
  if (!settings || typeof settings !== 'object' || Array.isArray(settings)) throw failure('Invalid settings.', 'INVALID_INPUT');
  const java = text(settings.java, 'Java executable'), platforms = text(settings.platforms, 'Android platforms directory');
  const memoryGb = settings.memoryGb ?? 4, timeoutSeconds = settings.timeoutSeconds ?? 300;
  if (!Number.isInteger(memoryGb) || memoryGb < 1 || memoryGb > 64) throw failure('Memory must be a whole number between 1 and 64 GB.', 'INVALID_INPUT');
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 0 || timeoutSeconds > 86400) throw failure('Timeout must be a whole number from 0 to 86400 seconds.', 'INVALID_INPUT');
  return { java, platforms, memoryGb, timeoutSeconds };
}
function optionsOf(options = {}) {
  if (!options || typeof options !== 'object' || Array.isArray(options)) throw failure('Invalid analysis options.', 'INVALID_INPUT');
  const packages = options.packages ?? [];
  if (!Array.isArray(packages) || packages.length > 100) throw failure('Choose at most 100 package prefixes.', 'INVALID_INPUT');
  const normalized = packages.map(value => {
    const prefix = text(value, 'package prefix').replace(/\.$/, '');
    if (!prefix || prefix.startsWith('.') || prefix.includes('..') || /[\s/:<>]/.test(prefix)) throw failure(`Invalid package prefix: ${prefix}`, 'INVALID_INPUT');
    return prefix;
  });
  const algorithm = options.algorithm ?? 'CHA';
  if (!ALGORITHMS.has(algorithm)) throw failure('Choose CHA, RTA, VTA, or SPARK.', 'INVALID_INPUT');
  if (options.invocations !== undefined && typeof options.invocations !== 'boolean') throw failure('Invalid invocation setting.', 'INVALID_INPUT');
  return { packages: [...new Set(normalized)], algorithm, invocations: options.invocations ?? false };
}
async function readableFile(file, name) {
  const value = text(file, name);
  if (!value) throw failure(`Choose ${name} first.`, 'INVALID_INPUT');
  try {
    const stat = await fs.stat(value);
    if (!stat.isFile()) throw new Error('Not a file');
    await fs.access(value, fs.constants.R_OK);
    return { path: path.resolve(value), stat };
  } catch { throw failure(`${name} is not a readable file: ${value}`, 'INVALID_INPUT'); }
}
async function platformsReady(directory) {
  if (!directory) return false;
  try {
    const entries = await fs.readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      if (!/^android-/.test(entry.name) || !(entry.isDirectory() || entry.isSymbolicLink())) continue;
      try { await readableFile(path.join(directory, entry.name, 'android.jar'), 'Android platform'); return true; } catch { /* Try the next installed platform. */ }
    }
  } catch { /* Missing or inaccessible SDK. */ }
  return false;
}
function commandLine(executable, args, platform) {
  // Windows uses PowerShell syntax; POSIX shells use single-quoted arguments.
  const quote = value => `'${String(value).replace(/'/g, platform === 'win32' ? "''" : "'\\''")}'`;
  return `${platform === 'win32' ? '& ' : ''}${[executable, ...args].map(quote).join(' ')}`;
}

/**
 * Runs the standalone CLI in one isolated JVM per operation. Results and APK copies live
 * in private temporary directories until discarded or shutdown. No command uses a shell.
 */
class Backend {
  /**
   * Creates an idle backend; this does not spawn Java.
   * @param {{jar:string,defaultJava?:string,emit?:(update:object)=>void,version?:string,
   * spawn?:Function,platform?:string,env?:object,temporaryRoot?:string,killGraceMs?:number}} options
   * CLI location, optional bundled Java, notifications, and optional process adapters for tests.
   */
  constructor(options) {
    if (!options || typeof options.jar !== 'string' || !options.jar) throw new Error('A CLI JAR path is required.');
    this.jar = path.resolve(options.jar);
    this.defaultJava = options.defaultJava;
    this.emit = options.emit || (() => {});
    this.version = options.version || '2.0.0';
    this.spawn = options.spawn || spawn;
    this.platform = options.platform || process.platform;
    this.env = options.env || process.env;
    this.temporaryRoot = options.temporaryRoot || os.tmpdir();
    this.killGraceMs = options.killGraceMs ?? 1500;
    this.results = new Map();
    this.directories = new Set();
    this.job = null;
    this.closed = false;
  }

  /**
   * Checks Java 17+, the bundled CLI, and installed Android platform JARs. Missing SDKs
   * are reported separately because metadata and imported graphs do not require one.
   * @param {object} settings Java/platform paths and resource limits.
   * @returns {Promise<object>} Paths, version, readiness flags, and readable diagnostics.
   */
  async check(settings = {}) {
    return this._exclusive(async job => {
      const config = settingsOf(settings);
      const errors = [];
      let java = config.java, javaVersion = '', javaOk = false, jarOk = false;
      try { const found = await this._java(config, job); java = found.java; javaVersion = found.javaVersion; javaOk = true; }
      catch (error) { this._assertActive(job); errors.push(error.message); }
      try { await readableFile(this.jar, 'AndroSpecter CLI JAR'); jarOk = true; } catch { errors.push('The analysis engine is missing. Build the CLI or reinstall the desktop application.'); }
      const platformsOk = await platformsReady(config.platforms);
      if (!platformsOk) errors.push('Choose the Android SDK platforms folder containing android-*/android.jar. Metadata and saved graphs work without it.');
      this._assertActive(job);
      return { java, javaVersion, javaOk, jarOk, platformsOk, errors };
    });
  }

  /**
   * Reads metadata, inspects classes, or builds a graph from an APK snapshot.
   * @param {'metadata'|'inspect'|'graph'} kind Analysis to execute.
   * @param {string} apk Readable APK path; never modified.
   * @param {object} settings Java, platforms, memoryGb, and timeoutSeconds (0 disables deadline).
   * @param {object} [options] Package prefixes, graph algorithm, and invocation inspection flag.
   * @returns {Promise<object>} Detached JSON data, owned result paths, and reproducibility metadata.
   * @throws {Error} On invalid input, concurrent operation, cancellation, timeout, or CLI failure.
   */
  async run(kind, apk, settings, options = {}) {
    return this._exclusive(async job => {
      if (!['metadata', 'inspect', 'graph'].includes(kind)) throw failure('Unknown analysis type.', 'INVALID_INPUT');
      const config = settingsOf(settings), selected = optionsOf(options);
      const input = await readableFile(apk, 'APK');
      if (kind !== 'metadata' && !(await platformsReady(config.platforms))) throw failure('Set an Android SDK platforms folder containing android-*/android.jar in Settings.', 'SDK_MISSING');
      await readableFile(this.jar, 'AndroSpecter CLI JAR');
      const runtime = await this._java(config, job);
      const directory = await this._directory();
      try {
        this._notify({ type: 'phase', phase: 'Preparing APK' });
        const snapshot = path.join(directory, 'input.apk');
        const apkSha256 = await this._snapshot(input.path, snapshot, job);
        const output = path.join(directory, `${kind}.json`);
        const buildArguments = source => {
          const args = [kind, '--apk', source, '--format', 'json'];
          if (kind !== 'metadata') {
            args.push('--platforms', path.resolve(config.platforms), '--timeout', String(config.timeoutSeconds));
            for (const prefix of selected.packages) args.push('--package', prefix);
          }
          if (kind === 'inspect') { args.push('--list-methods'); if (selected.invocations) args.push('--invocations'); }
          if (kind === 'graph') args.push('--algorithm', selected.algorithm);
          return args;
        };
        const prefix = [`-Xmx${config.memoryGb}g`, '-jar', this.jar];
        this._notify({ type: 'phase', phase: { metadata: 'Reading manifest', inspect: 'Inspecting code', graph: 'Building call graph' }[kind] });
        const execution = await this._execute(runtime.java, [...prefix, ...buildArguments(snapshot), '-o', output], { job, timeoutMs: config.timeoutSeconds * 1000, log: true });
        this._assertActive(job);
        const data = await this._readResult(output);
        this._assertActive(job);
        const result = {
          kind, data, output, directory,
          provenance: { schemaVersion: 1, version: this.version, kind, startedAt: job.startedAt, finishedAt: new Date().toISOString(), apk: input.path, apkSha256,
            options: kind === 'metadata' ? {} : selected, settings: { ...config, java: runtime.java },
            javaVersion: runtime.javaVersion, diagnostics: execution.stderr, command: commandLine(runtime.java, [...prefix, ...buildArguments(input.path)], this.platform),
            commandShell: this.platform === 'win32' ? 'PowerShell' : 'POSIX',
            scope: kind === 'metadata' ? 'Manifest declarations; absent or unresolved values are null.' : selected.packages.length
              ? kind === 'graph' ? 'Induced subgraph: edges crossing selected packages are omitted.' : 'Inspection restricted to the selected application package prefixes.'
              : 'No package filter applied.',
            completion: 'CLI completed successfully. Static results are not proof of runtime behavior; inspect diagnostics for backend limitations.' }
        };
        this.results.set(directory, result);
        return result;
      } catch (error) { await this._remove(directory); throw error; }
    });
  }

  /**
   * Validates and normalizes a saved GraphJson document through the CLI, without Soot or an SDK.
   * @param {string} file Existing graph JSON; never modified.
   * @param {object} settings Java and resource limits; SDK settings are ignored.
   * @returns {Promise<object>} Detached graph and import provenance; original analysis settings are unknown.
   */
  async importGraph(file, settings) {
    return this._exclusive(async job => {
      const config = settingsOf(settings), input = await readableFile(file, 'Graph JSON');
      if (input.stat.size > RESULT_LIMIT) throw failure('Graph JSON exceeds the desktop 64 MiB limit. Use the CLI to filter or export this graph.', 'RESULT_TOO_LARGE');
      await readableFile(this.jar, 'AndroSpecter CLI JAR');
      const runtime = await this._java(config, job), directory = await this._directory();
      try {
        const snapshot = path.join(directory, 'input.json'), output = path.join(directory, 'graph.json');
        const inputSha256 = await this._snapshot(input.path, snapshot, job);
        const prefix = [`-Xmx${config.memoryGb}g`, '-jar', this.jar];
        const args = ['graph', '--input-json', snapshot, '--format', 'json', '-o', output];
        this._notify({ type: 'phase', phase: 'Opening saved graph' });
        const execution = await this._execute(runtime.java, [...prefix, ...args], { job, timeoutMs: config.timeoutSeconds * 1000, log: true });
        const data = await this._readResult(output); this._assertActive(job);
        const result = { kind: 'graph', data, output, directory,
          provenance: { schemaVersion: 1, version: this.version, kind: 'graph', imported: true, input: input.path, inputSha256,
            startedAt: job.startedAt, finishedAt: new Date().toISOString(), options: {}, settings: { java: runtime.java, memoryGb: config.memoryGb, timeoutSeconds: config.timeoutSeconds },
            javaVersion: runtime.javaVersion, diagnostics: execution.stderr, command: commandLine(runtime.java, [...prefix, 'graph', '--input-json', input.path, '--format', 'json'], this.platform),
            commandShell: this.platform === 'win32' ? 'PowerShell' : 'POSIX', scope: 'Imported graph: original APK, algorithm, filters, and completion status are unknown.' } };
        this.results.set(directory, result);
        return result;
      } catch (error) { await this._remove(directory); throw error; }
    });
  }

  /**
   * Exports an owned graph through the CLI without reanalyzing its APK. Returned files
   * belong to this backend; callers must copy them before discarding the result.
   * @param {object} result A graph result returned by this backend.
   * @param {'json'|'dot'|'html'} format Export format.
   * @param {object} settings Java and resource limits; no SDK required.
   * @returns {Promise<string>} Owned export path.
   */
  async exportGraph(result, format, settings) {
    return this._exclusive(async job => {
      if (!result || this.results.get(result.directory) !== result || result.kind !== 'graph') throw failure('Build or open a graph first.', 'INVALID_INPUT');
      if (!['json', 'dot', 'html'].includes(format)) throw failure('Unknown graph export format.', 'INVALID_INPUT');
      if (format === 'json') return result.output;
      const config = settingsOf(settings), runtime = await this._java(config, job);
      const output = path.join(result.directory, `export.${format}`);
      this._notify({ type: 'phase', phase: 'Exporting graph' });
      await this._execute(runtime.java, [`-Xmx${config.memoryGb}g`, '-jar', this.jar, 'graph', '--input-json', result.output, '--format', format, '-o', output, '--overwrite'], { job, timeoutMs: config.timeoutSeconds * 1000, log: true });
      this._assertActive(job);
      return output;
    });
  }

  /**
   * Requests termination and waits until the active operation has actually settled.
   * Java receives SIGTERM, then a forced termination after a short grace period.
   * @returns {Promise<void>} Resolves when no operation remains active.
   */
  async cancel() {
    const job = this.job;
    if (!job) return;
    job.cancelled = true;
    job.controller.abort();
    if (job.stop) job.stop('CANCELLED');
    await job.done;
  }

  /**
   * Deletes only a result owned by this backend. Active analysis cannot be discarded.
   * @param {object} result Previously returned result; unknown results are ignored.
   * @returns {Promise<void>} Resolves after removing its temporary directory.
   */
  async discard(result) {
    if (this.job) throw failure('Wait for the current operation before discarding a result.', 'BUSY');
    if (result && this.results.get(result.directory) === result) await this._remove(result.directory);
  }

  /**
   * Stops work, prevents new operations, and removes all owned temporary files.
   * @returns {Promise<void>} Resolves after child exit and cleanup.
   */
  async shutdown() {
    this.closed = true;
    await this.cancel();
    await Promise.all([...this.directories].map(directory => this._remove(directory)));
  }

  async _exclusive(operation) {
    if (this.closed) throw failure('The analysis engine is closed.', 'CLOSED');
    if (this.job) throw failure('Another operation is running. Wait for it or cancel it first.', 'BUSY');
    let finish;
    const job = { startedAt: new Date().toISOString(), cancelled: false, controller: new AbortController(), done: new Promise(resolve => { finish = resolve; }) };
    this.job = job;
    try { return await operation(job); }
    catch (error) {
      if (job.cancelled || error.name === 'AbortError') throw failure('Analysis cancelled.', 'CANCELLED');
      throw error;
    } finally { this.job = null; finish(); }
  }
  _assertActive(job) { if (job?.cancelled) throw failure('Analysis cancelled.', 'CANCELLED'); }
  _notify(update) { try { this.emit(update); } catch { /* UI teardown must not interrupt process cleanup. */ } }
  async _directory() {
    await fs.mkdir(this.temporaryRoot, { recursive: true });
    const directory = await fs.mkdtemp(path.join(this.temporaryRoot, 'androspecter-run-'));
    await fs.chmod(directory, 0o700);
    this.directories.add(directory);
    return directory;
  }
  async _remove(directory) {
    if (!this.directories.has(directory)) return;
    await fs.rm(directory, { recursive: true, force: true });
    this.directories.delete(directory); this.results.delete(directory);
  }
  async _snapshot(source, destination, job) {
    this._assertActive(job);
    const hash = createHash('sha256');
    const digest = new Transform({ transform(chunk, _encoding, callback) { hash.update(chunk); callback(null, chunk); } });
    await pipeline(createReadStream(source), digest, createWriteStream(destination, { flags: 'wx', mode: 0o600 }), { signal: job.controller.signal });
    this._assertActive(job);
    return hash.digest('hex');
  }
  async _readResult(output) {
    const { stat } = await readableFile(output, 'Analysis result');
    if (stat.size > RESULT_LIMIT) throw failure('Result exceeds the desktop 64 MiB limit. Use the CLI with a package filter for this APK.', 'RESULT_TOO_LARGE');
    let data;
    try { data = JSON.parse(await fs.readFile(output, 'utf8')); }
    catch { throw failure('The analysis engine returned invalid JSON. Inspect the activity log.'); }
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw failure('The analysis engine returned an invalid result.');
    return data;
  }
  async _java(config, job) {
    this._assertActive(job);
    const executable = this.platform === 'win32' ? 'java.exe' : 'java';
    const candidates = config.java ? [config.java] : [this.defaultJava, this.env.JAVA_HOME && path.join(this.env.JAVA_HOME, 'bin', executable)].filter(Boolean);
    const attempted = new Set();
    let versionFailure = '';
    const probe = async candidate => {
      if (!candidate || attempted.has(candidate)) return null;
      attempted.add(candidate); this._assertActive(job);
      try {
        const response = await this._execute(candidate, ['-version'], { job, timeoutMs: 10000 });
        const output = `${response.stderr}\n${response.stdout}`;
        const match = /(?:openjdk|java)\s+(?:version\s+)?"?(\d+)(?:\.(\d+))?/i.exec(output);
        const major = match && Number(match[1] === '1' ? match[2] : match[1]);
        if (!major || major < 17) { versionFailure = `Java 17 or newer is required${major ? `; found Java ${major}` : ''}.`; return null; }
        return { java: candidate, javaVersion: output.split('\n').find(line => /version|openjdk/i.test(line))?.trim() || `Java ${major}` };
      } catch (error) { if (job?.cancelled) throw error; return null; }
    };
    for (const candidate of candidates) { const found = await probe(candidate); if (found) return found; }
    // A packaged application uses its bundled runtime without invoking macOS's JDK locator.
    if (!config.java && this.platform === 'darwin') {
      try {
        const found = await this._execute('/usr/libexec/java_home', ['-v', '17+'], { job, timeoutMs: 5000 });
        const home = found.stdout.trim();
        if (home && path.isAbsolute(home)) { const runtime = await probe(path.join(home, 'bin', 'java')); if (runtime) return runtime; }
      } catch (error) { if (job?.cancelled) throw error; }
    }
    if (!config.java) { const runtime = await probe(executable); if (runtime) return runtime; }
    throw failure(versionFailure || 'Java 17 or newer was not found. Choose a Java executable in Settings or install a JDK.', 'JAVA_MISSING');
  }

  _execute(executable, args, { job, timeoutMs = 0, log = false } = {}) {
    this._assertActive(job);
    return new Promise((resolve, reject) => {
      let child, timer, forceTimer, stopped = null, settled = false, emitted = 0, truncationNoted = false;
      let stdout = '', stderr = '', processError = null;
      const stop = reason => {
        if (settled || stopped) return;
        stopped = reason;
        try { child?.kill('SIGTERM'); } catch { /* It may already have exited. */ }
        forceTimer = setTimeout(() => { try { child?.kill('SIGKILL'); } catch { /* Await close even after a kill race. */ } }, this.killGraceMs);
        forceTimer.unref?.();
      };
      const finish = (error, value) => {
        if (settled) return;
        settled = true; clearTimeout(timer); clearTimeout(forceTimer);
        if (job?.stop === stop) { job.stop = null; job.child = null; }
        if (error) reject(error); else resolve(value);
      };
      const collect = (stream, value) => {
        if (stream === 'stdout') stdout = (stdout + value).slice(-TAIL_LIMIT);
        else stderr = (stderr + value).slice(-TAIL_LIMIT);
        if (!log) return;
        const remaining = LOG_LIMIT - emitted;
        if (remaining > 0) { const shown = value.slice(0, remaining); emitted += shown.length; this._notify({ type: 'log', text: shown }); }
        if (value.length > remaining && !truncationNoted) { truncationNoted = true; this._notify({ type: 'log', text: '\n[Activity output capped at 1 MiB; analysis continues.]\n' }); }
      };
      try { child = this.spawn(executable, args, { shell: false, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: this.env }); }
      catch (error) { finish(failure(`Cannot start the analysis engine: ${error.message}`, 'PROCESS_START_FAILED')); return; }
      if (job) { job.child = child; job.stop = stop; }
      child.stdout?.setEncoding('utf8'); child.stderr?.setEncoding('utf8');
      child.stdout?.on('data', value => collect('stdout', value));
      child.stderr?.on('data', value => collect('stderr', value));
      child.once('error', error => { processError = error; });
      child.once('close', (code, signal) => {
        if (processError) { finish(failure(`Cannot run ${path.basename(executable)}: ${processError.message}`, 'PROCESS_START_FAILED')); return; }
        if (stopped === 'TIMED_OUT') { finish(failure('Analysis timed out and its Java process was stopped. Increase the time limit in Settings.', 'TIMED_OUT')); return; }
        if (stopped === 'CANCELLED' || job?.cancelled) { finish(failure('Analysis cancelled.', 'CANCELLED')); return; }
        if (code !== 0) {
          const details = (stderr || stdout).trim().slice(-4000);
          finish(failure(`Analysis engine ${signal ? `stopped (${signal})` : `exited with code ${code}`}.${details ? `\n${details}` : ''}`, code === 124 ? 'TIMED_OUT' : code === 130 ? 'CANCELLED' : 'ANALYSIS_FAILED')); return;
        }
        finish(null, { stdout, stderr, code });
      });
      if (timeoutMs > 0) { timer = setTimeout(() => stop('TIMED_OUT'), timeoutMs); timer.unref?.(); }
      if (job?.cancelled) stop('CANCELLED');
    });
  }
}

module.exports = { Backend };
