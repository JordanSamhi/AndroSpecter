'use strict';

// Build on the destination OS/architecture: the Java runtime is native, like Electron.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const desktop = path.resolve(__dirname, '..');
const root = path.dirname(desktop);
const args = process.argv.slice(2);
if (args.some(arg => arg !== '--dir')) throw new Error('Supported option: --dir. Build on each target OS and architecture.');
const jar = path.join(root, 'cli/target/androspecter-cli.jar');
if (!fs.existsSync(jar)) throw new Error('Build the backend first: mvn -f cli/pom.xml package');

let javaHome = process.env.JAVA_HOME;
if (!javaHome && process.platform === 'darwin') {
  const located = spawnSync('/usr/libexec/java_home', ['-v', '17+'], { encoding: 'utf8' });
  if (located.status === 0) javaHome = located.stdout.trim();
}
const tool = name => javaHome ? path.join(javaHome, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
const info = spawnSync(tool('java'), ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
const text = (info.stdout || '') + (info.stderr || '');
const version = /version\s+"(?:1\.)?(\d+)/.exec(text);
if (info.error || info.status !== 0 || !version || Number(version[1]) < 17) throw new Error('Packaging needs a full JDK 17 or newer. Set JAVA_HOME.');
const arch = /\bos\.arch\s*=\s*(\S+)/.exec(text)?.[1];
const normalizedArch = { aarch64: 'arm64', arm64: 'arm64', amd64: 'x64', x86_64: 'x64' }[arch];
if (normalizedArch !== process.arch) throw new Error('The JDK architecture must match Node/Electron. Set JAVA_HOME to a matching JDK.');

const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'androspecter-package-'));
try {
  const runtime = path.join(staging, 'runtime');
  console.log(`Bundling Java ${version[1]} for ${process.platform}/${process.arch}`);
  const linked = spawnSync(tool('jlink'), [
    '--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs',
    '--strip-debug', '--no-header-files', '--no-man-pages', '--output', runtime
  ], { stdio: 'inherit' });
  if (linked.error || linked.status !== 0) throw new Error('Could not build the Java runtime. JAVA_HOME must point to a full JDK with jlink.');
  const config = JSON.parse(fs.readFileSync(path.join(desktop, 'package.json'), 'utf8')).build;
  config.directories.output = path.join(desktop, 'dist');
  config.extraResources = [
    { from: jar, to: 'androspecter-cli.jar' },
    { from: runtime, to: 'runtime' }
  ];
  if (process.platform === 'darwin' && !process.env.CSC_NAME && !process.env.CSC_LINK) {
    config.mac.identity = '-';
    console.log('Using an ad-hoc signature for local use. Public macOS distribution requires signing and notarization.');
  }
  const configPath = path.join(staging, 'builder.json');
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
  const built = spawnSync(process.execPath, [require.resolve('electron-builder/cli.js'), '--config', configPath, ...args], { cwd: desktop, stdio: 'inherit' });
  if (built.error) throw built.error;
  process.exitCode = built.status ?? 1;
  if (built.status === 0) console.log(`Desktop distribution: ${path.join(desktop, 'dist')}`);
} finally {
  fs.rmSync(staging, { recursive: true, force: true });
}
