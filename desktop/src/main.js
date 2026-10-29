'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { pathToFileURL } = require('node:url');
const { readSettings, writeSettings } = require('./settings');

const APP_ORIGIN = 'androspecter://app';
const ASSETS = Object.freeze({ '/': 'index.html', '/index.html': 'index.html', '/renderer.js': 'renderer.js',
  '/graph.js': 'graph.js', '/styles.css': 'styles.css', '/icon.png': 'icon.png' });
const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";

/**
 * Resolve only bundled, explicitly permitted assets for the private application protocol.
 * @param {string} requestUrl Incoming custom-protocol URL.
 * @returns {string|null} Bundled filename, or null for any untrusted origin/path.
 */
function assetForUrl(requestUrl) {
  try {
    const url = new URL(requestUrl);
    if (url.protocol !== 'androspecter:' || url.hostname !== 'app' || url.port || url.username || url.password) return null;
    return Object.hasOwn(ASSETS, url.pathname) ? ASSETS[url.pathname] : null;
  } catch { return null; }
}

/**
 * Reject IPC from an untrusted frame, including child frames sharing the application window.
 * @param {object} event Electron IPC event.
 * @param {object} contents The current window's WebContents.
 * @throws {Error} When sender, main-frame identity, or URL does not match the local UI.
 */
function authorizeSender(event, contents) {
  if (!contents || event.sender !== contents || event.senderFrame !== contents.mainFrame
      || !['/', '/index.html'].some(suffix => event.senderFrame.url === APP_ORIGIN + suffix)) {
    throw new Error('This request did not originate from the application window.');
  }
}

/**
 * Write an export atomically while protecting selected input files and their aliases.
 * Existing symlink destinations are refused; real paths and hard-link identities are checked.
 * @param {string} destination Native save-dialog destination.
 * @param {string|Buffer} content Export bytes or text.
 * @param {string[]} protectedInputs Selected input paths that must remain unchanged.
 * @returns {Promise<string>} Canonical destination path.
 * @throws {Error} If the destination aliases an input or the write fails.
 */
async function writeExport(destination, content, protectedInputs = []) {
  const parent = await fs.realpath(path.dirname(destination));
  const target = path.join(parent, path.basename(destination));
  let targetStat;
  try {
    targetStat = await fs.lstat(target);
    if (targetStat.isSymbolicLink()) throw new Error('Choose a regular file, not a symbolic link, for the export.');
    if (!targetStat.isFile()) throw new Error('Choose a regular file for the export.');
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  for (const input of protectedInputs.filter(Boolean)) {
    let realInput, inputStat;
    try { realInput = await fs.realpath(input); inputStat = await fs.stat(realInput); }
    catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    const samePath = process.platform === 'win32' ? realInput.toLowerCase() === target.toLowerCase() : realInput === target;
    if (samePath || (targetStat && inputStat.dev === targetStat.dev && inputStat.ino === targetStat.ino)) {
      throw new Error('The export cannot replace an input file. Choose a different filename.');
    }
  }
  const temporary = path.join(parent, `.androspecter-export-${randomUUID()}.tmp`);
  try {
    await fs.writeFile(temporary, content, { flag: 'wx', mode: 0o600 });
    await fs.rename(temporary, target);
  } finally { await fs.rm(temporary, { force: true }); }
  return target;
}

/**
 * Native-dialog and analysis-session coordinator. This class never accepts an arbitrary APK
 * path from the renderer: source files are selected through the injected native dialogs.
 */
class DesktopController {
  /**
   * @param {object} dependencies Native UI, backend, settings and version dependencies.
   * @param {object} dependencies.backend CLI backend implementing the desktop contract.
   * @param {object} dependencies.dialog Electron-compatible dialog methods.
   * @param {object} dependencies.clipboard Electron-compatible clipboard methods.
   * @param {Function} dependencies.window Returns the parent BrowserWindow for native dialogs.
   * @param {string} dependencies.settingsFile Local preferences filename.
   * @param {object} dependencies.settings Initial validated preferences.
   * @param {string} dependencies.version Application version.
   */
  constructor({ backend, dialog, clipboard, window, settingsFile, settings, version }) {
    Object.assign(this, { backend, dialog, clipboard, window, settingsFile, settings, version });
    this.apk = null;
    this.graphInput = null;
    this.results = new Map();
    this.busy = false;
    this.closing = false;
    this.operation = null;
    this.shutdownPromise = null;
  }

  /**
   * Serialize native dialogs, setting changes and JVM operations to keep source/results coherent.
   * @param {Function} action Async operation.
   * @returns {Promise<*>} Operation result.
   * @throws {Error} If another operation is running or application shutdown has started.
   */
  async exclusive(action) {
    if (this.closing) throw new Error('The application is closing.');
    if (this.busy) throw new Error('Wait for the current operation to finish, or cancel it.');
    this.busy = true;
    // Defer the action so this.operation exists even for synchronously resolved callbacks.
    this.operation = Promise.resolve().then(action);
    try { return await this.operation; }
    finally { this.busy = false; this.operation = null; }
  }

  /** @returns {Promise<object>} Version, saved preferences, and live backend availability. */
  bootstrap() {
    return this.exclusive(async () => ({ version: this.version, settings: { ...this.settings },
      environment: await this.backend.check(this.settings) }));
  }

  /** @returns {Promise<void>} Remove old result directories once a new source has been selected. */
  async clearResults() {
    const previous = [...this.results.values()];
    this.results.clear();
    await Promise.all(previous.map(result => this.backend.discard(result)));
  }

  /** @returns {Promise<object|null>} Native-selected APK path/name, or null when cancelled. */
  chooseApk() {
    return this.exclusive(async () => {
      const choice = await this.dialog.showOpenDialog(this.window(), { title: 'Open Android APK',
        properties: ['openFile'], filters: [{ name: 'Android APK', extensions: ['apk'] }] });
      if (choice.canceled || !choice.filePaths?.[0]) return null;
      const apk = choice.filePaths[0];
      if (!/\.apk$/i.test(apk) || !(await fs.stat(apk)).isFile()) throw new Error('Choose an Android APK file.');
      await this.clearResults();
      this.apk = apk;
      this.graphInput = null;
      return { path: apk, name: path.basename(apk) };
    });
  }

  /**
   * Run one operation against the selected APK, preserving the previous result if it fails.
   * @param {'metadata'|'inspect'|'graph'} kind CLI operation.
   * @param {object} [options={}] Validated by the backend before Java is launched.
   * @returns {Promise<object>} Detached result data and provenance for the renderer.
   */
  run(kind, options = {}) {
    return this.exclusive(async () => {
      if (!['metadata', 'inspect', 'graph'].includes(kind)) throw new Error('Unknown analysis.');
      if (!this.apk) throw new Error('Open an APK first.');
      const result = await this.backend.run(kind, this.apk, this.settings, options);
      const old = this.results.get(kind);
      this.results.set(kind, result);
      if (old) await this.backend.discard(old);
      return { data: result.data, provenance: result.provenance };
    });
  }

  /** @returns {Promise<object|null>} Imported graph data/provenance/name, or null on dialog cancellation. */
  importGraph() {
    return this.exclusive(async () => {
      const choice = await this.dialog.showOpenDialog(this.window(), { title: 'Open saved call graph',
        properties: ['openFile'], filters: [{ name: 'AndroSpecter graph JSON', extensions: ['json'] }] });
      if (choice.canceled || !choice.filePaths?.[0]) return null;
      const input = choice.filePaths[0];
      const result = await this.backend.importGraph(input, this.settings);
      await this.clearResults();
      this.results.set('graph', result);
      this.apk = null;
      this.graphInput = input;
      return { data: result.data, provenance: result.provenance, name: path.basename(input) };
    });
  }

  /**
   * @param {'java'|'platforms'} kind Setting to choose using a native dialog.
   * @returns {Promise<string|null>} Selected path, or null when cancelled.
   */
  chooseSetting(kind) {
    return this.exclusive(async () => {
      if (!['java', 'platforms'].includes(kind)) throw new Error('Unknown setting.');
      const choice = await this.dialog.showOpenDialog(this.window(), {
        title: kind === 'java' ? 'Choose the Java executable (Java 17 or later)' : 'Choose the Android SDK platforms folder',
        properties: [kind === 'java' ? 'openFile' : 'openDirectory'] });
      return choice.canceled ? null : choice.filePaths?.[0] || null;
    });
  }

  /**
   * @param {object} settings Preferences from the settings form.
   * @returns {Promise<object>} Saved settings and refreshed environment diagnostics.
   */
  saveSettings(settings) {
    return this.exclusive(async () => {
      this.settings = await writeSettings(this.settingsFile, settings);
      return { settings: { ...this.settings }, environment: await this.backend.check(this.settings) };
    });
  }

  /** @returns {Promise<object>} Current Java/JAR/SDK availability. */
  checkEnvironment() { return this.exclusive(() => this.backend.check(this.settings)); }

  /**
   * Save a result chosen by kind; no output source path is supplied by the renderer.
   * @param {'metadata'|'inspect'|'graph'} kind Completed result to save.
   * @param {'json'|'dot'|'html'|'run'} format Graph export or a JSON run record.
   * @returns {Promise<string|null>} Saved path, or null if the save dialog was cancelled.
   */
  exportResult(kind, format) {
    return this.exclusive(async () => {
      if (!['metadata', 'inspect', 'graph'].includes(kind) || !['json', 'dot', 'html', 'run'].includes(format)
          || (kind !== 'graph' && !['json', 'run'].includes(format))) throw new Error('Unknown export format.');
      const result = this.results.get(kind);
      if (!result) throw new Error('Complete this analysis before exporting it.');
      const extension = format === 'run' ? 'json' : format;
      const stem = path.basename(this.apk || this.graphInput || 'androspecter').replace(/\.(apk|json)$/i, '');
      const choice = await this.dialog.showSaveDialog(this.window(), { title: 'Export analysis',
        defaultPath: `${stem}-${kind}${format === 'run' ? '-run' : ''}.${extension}`,
        filters: [{ name: format === 'run' ? 'Run record (JSON)' : format.toUpperCase(), extensions: [extension] }],
        properties: ['showOverwriteConfirmation'] });
      if (choice.canceled || !choice.filePath) return null;
      const destination = choice.filePath;
      let content;
      if (format === 'run') content = JSON.stringify({ schemaVersion: 1, kind, provenance: result.provenance, data: result.data }, null, 2) + '\n';
      else {
        const source = kind === 'graph' ? await this.backend.exportGraph(result, format, this.settings) : result.output;
        content = await fs.readFile(source);
      }
      return writeExport(destination, content, [this.apk, this.graphInput]);
    });
  }

  /**
   * @param {'metadata'|'inspect'|'graph'} kind Result whose recorded command is copied.
   * @returns {Promise<void>} Resolves after placing the command on the native clipboard.
   */
  async copyCommand(kind) {
    const command = this.results.get(kind)?.provenance?.command;
    if (typeof command !== 'string' || !command || command.length > 131072) throw new Error('No command is available for this result.');
    this.clipboard.writeText(command);
  }

  /** @returns {Promise<void>} Resolves once the backend acknowledges cancellation. */
  async cancel() { await this.backend.cancel(); }

  /**
   * Stop Java before temporary results are removed and the Electron process exits.
   * @returns {Promise<void>} Completion of backend shutdown and any active operation.
   */
  async shutdown() {
    if (this.shutdownPromise) return this.shutdownPromise;
    this.closing = true;
    this.shutdownPromise = (async () => {
      await this.backend.cancel();
      if (this.operation) await this.operation.catch(() => {});
      await this.backend.shutdown();
      this.results.clear();
    })();
    return this.shutdownPromise;
  }
}

/**
 * Create the desktop window and register the restricted Electron integration.
 * @returns {Promise<void>} Resolves once the initial native window has loaded.
 */
async function startElectron() {
  const { app, BrowserWindow, dialog, ipcMain, protocol, net, clipboard, Menu, session } = require('electron');
  const { Backend } = require('./backend');
  const packageInfo = require('../package.json');
  if (process.env.ANDROSPECTER_USER_DATA) {
    if (!path.isAbsolute(process.env.ANDROSPECTER_USER_DATA)) throw new Error('ANDROSPECTER_USER_DATA must be an absolute directory.');
    app.setPath('userData', process.env.ANDROSPECTER_USER_DATA);
  }
  protocol.registerSchemesAsPrivileged([{ scheme: 'androspecter', privileges: { standard: true, secure: true, supportFetchAPI: true } }]);
  await app.whenReady();
  app.setName('AndroSpecter');
  app.setAboutPanelOptions({ applicationName: 'AndroSpecter', applicationVersion: packageInfo.version,
    copyright: 'Jordan Samhi', version: packageInfo.version });
  session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  session.defaultSession.setPermissionCheckHandler(() => false);
  protocol.handle('androspecter', async request => {
    const asset = request.method === 'GET' ? assetForUrl(request.url) : null;
    if (!asset) return new Response('Not found', { status: 404 });
    const response = await net.fetch(pathToFileURL(path.join(__dirname, asset)).href);
    const headers = new Headers(response.headers);
    headers.set('Content-Security-Policy', CSP);
    headers.set('X-Content-Type-Options', 'nosniff');
    return new Response(response.body, { status: response.status, headers });
  });
  const settingsFile = path.join(app.getPath('userData'), 'settings.json');
  const settings = await readSettings(settingsFile);
  const window = new BrowserWindow({ width: 1440, height: 920, minWidth: 1024, minHeight: 700,
    title: 'AndroSpecter', backgroundColor: '#f3f6f7', show: false,
    ...(process.platform === 'darwin' ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 20, y: 21 } } : {}),
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true,
      sandbox: true, nodeIntegration: false, webSecurity: true, spellcheck: false } });
  const backend = new Backend({
    jar: app.isPackaged ? path.join(process.resourcesPath, 'androspecter-cli.jar') : path.resolve(__dirname, '../../cli/target/androspecter-cli.jar'),
    defaultJava: app.isPackaged ? path.join(process.resourcesPath, 'runtime/bin', process.platform === 'win32' ? 'java.exe' : 'java') : undefined,
    emit: update => { if (!window.isDestroyed()) window.webContents.send('analysis:update', update); }
  });
  const controller = new DesktopController({ backend, dialog, clipboard, window: () => window,
    settingsFile, settings, version: packageInfo.version });
  const handlers = {
    'app:bootstrap': () => controller.bootstrap(),
    'apk:choose': () => controller.chooseApk(),
    'analysis:metadata': () => controller.run('metadata'),
    'analysis:inspect': options => controller.run('inspect', options),
    'analysis:graph': options => controller.run('graph', options),
    'graph:import': () => controller.importGraph(),
    'settings:choose': kind => controller.chooseSetting(kind),
    'settings:save': next => controller.saveSettings(next),
    'environment:check': () => controller.checkEnvironment(),
    'result:export': (kind, format) => controller.exportResult(kind, format),
    'command:copy': kind => controller.copyCommand(kind),
    'analysis:cancel': () => controller.cancel()
  };
  for (const [channel, handler] of Object.entries(handlers)) {
    ipcMain.handle(channel, (event, ...args) => { authorizeSender(event, window.webContents); return handler(...args); });
  }
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', event => event.preventDefault());
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  const menu = [
    ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
    { label: 'File', submenu: [{ role: 'close' }] },
    { role: 'editMenu' },
    { label: 'View', submenu: [{ role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' },
      { type: 'separator' }, { role: 'togglefullscreen' }] },
    { role: 'windowMenu' },
    ...(process.platform !== 'darwin' ? [{ label: 'Help', submenu: [{ label: 'About AndroSpecter', click: () => app.showAboutPanel() }] }] : [])
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(menu));
  let closing = false, closingPrompt = false, shutdownComplete = false;
  const close = async () => {
    if (closing || closingPrompt) return;
    closingPrompt = true;
    try {
      if (controller.busy) {
        const answer = await dialog.showMessageBox(window, { type: 'question', title: 'Analysis in progress',
          message: 'Cancel the current operation and quit?', buttons: ['Keep working', 'Cancel and quit'], defaultId: 0, cancelId: 0 });
        if (answer.response !== 1) return;
      }
      closing = true;
      await controller.shutdown();
      shutdownComplete = true;
      window.destroy();
      app.quit();
    } finally { closingPrompt = false; }
  };
  window.on('close', event => { if (!shutdownComplete) { event.preventDefault(); void close(); } });
  app.on('before-quit', event => { if (!shutdownComplete) { event.preventDefault(); void close(); } });
  window.once('ready-to-show', () => window.show());
  await window.loadURL(APP_ORIGIN + '/');
}

module.exports = { DesktopController, writeExport, assetForUrl, authorizeSender, startElectron };
// Electron loaders (including packaged apps and automation) may wrap the entry module.
// Only the Electron browser process starts the app; plain Node test imports stay inert.
if (process.versions.electron && process.type === 'browser') {
  startElectron().catch(error => {
    const { dialog, app } = require('electron');
    dialog.showErrorBox('AndroSpecter could not start', error.message);
    app.exit(1);
  });
}
