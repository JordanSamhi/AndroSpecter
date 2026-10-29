'use strict';

// Real Electron + real CLI smoke test. Only native file dialogs are automated.
const { _electron: electron, expect } = require('@playwright/test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const desktop = path.resolve(__dirname, '..');
  const root = path.dirname(desktop);
  const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'androspecter-ui-test-'));
  const output = path.join(desktop, 'test-output');
  await fs.mkdir(output, { recursive: true });
  const env = { ...process.env, ANDROSPECTER_USER_DATA: path.join(scratch, 'preferences') };
  delete env.ELECTRON_RUN_AS_NODE;
  const application = await electron.launch({
    ...(process.env.ANDROSPECTER_TEST_EXECUTABLE ? { executablePath: process.env.ANDROSPECTER_TEST_EXECUTABLE } : {}),
    args: process.env.ANDROSPECTER_TEST_EXECUTABLE ? [] : [desktop], env, timeout: 30000
  });
  console.log('Electron process launched.');
  const errors = [];
  try {
    const page = await application.firstWindow();
    console.log('Desktop window opened:', page.url());
    page.setDefaultTimeout(30000);
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    await expect(page.locator('#run-status-text')).not.toHaveText('Starting workspace…');
    console.log('Workspace initialized.');
    await expect(page.locator('#welcome-open')).toBeEnabled();
    if (process.env.ANDROSPECTER_TEST_EXECUTABLE) {
      const runtime = await page.evaluate(() => window.androspecter.checkEnvironment());
      assert.equal(runtime.javaOk, true);
      assert.match(runtime.java, /runtime[\\/]bin[\\/]java(?:\.exe)?$/);
      console.log('Packaged Java runtime verified:', runtime.javaVersion);
    }
    assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
    assert.equal(await page.evaluate(() => typeof window.process), 'undefined');
    await page.screenshot({ path: path.join(output, 'desktop-welcome.png') });

    const fixture = path.join(root, 'src/test/resources/apks/managed-analysis.apk');
    await application.evaluate(({ dialog }, apk) => {
      dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [apk] });
    }, fixture);
    await page.locator('#welcome-open').click();
    await expect(page.locator('#summary-package')).toHaveText('demo.taint');
    await expect(page.locator('#cancel-run')).not.toBeVisible();
    await page.screenshot({ path: path.join(output, 'desktop-overview.png') });

    const platforms = process.env.ANDROSPECTER_ANDROID_PLATFORMS;
    if (platforms) {
      await page.locator('#open-settings').click();
      await page.locator('#setting-platforms').fill(platforms);
      await page.locator('#setting-memory').selectOption('2');
      await page.locator('#setting-timeout').fill('120');
      await page.locator('#save-settings').click();
      await expect(page.locator('#settings-dialog')).not.toBeVisible();

      await page.locator('[data-view="code"]').click();
      await page.locator('#inspect-code').click();
      await expect(page.locator('#code-loaded')).toBeVisible();
      await expect(page.locator('#class-list')).toContainText('demo.taint.MainActivity');
      await page.screenshot({ path: path.join(output, 'desktop-code.png') });

      await page.locator('[data-view="graph"]').click();
      await page.locator('#build-graph').click();
      await expect(page.locator('#graph-loaded')).toBeVisible({ timeout: 120000 });
      await expect(page.locator('#method-list button').first()).toBeVisible();
      await page.locator('#method-search').fill('onCreate');
      await expect(page.locator('#method-list button').first()).toContainText('onCreate');
      await page.locator('#method-list button').first().click();
      await expect(page.locator('#selected-signature')).toContainText('onCreate');
      await page.screenshot({ path: path.join(output, 'desktop-graph.png') });
      await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(1024, 700));
      await expect.poll(() => page.evaluate(() => window.innerWidth)).toBe(1024);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'The minimum window must not overflow horizontally.');
      await page.screenshot({ path: path.join(output, 'desktop-graph-compact.png') });
      await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setSize(1440, 920));
      const signature = await page.locator('#selected-signature').textContent();
      await page.locator('#show-path').click();
      await page.locator('#path-target').fill(signature);
      await page.locator('#find-path').click();
      await expect(page.locator('#path-result')).toContainText('0');

      const graphOutput = path.join(scratch, 'graph.json');
      for (const format of ['json', 'html', 'dot', 'run']) {
        const destination = format === 'json' ? graphOutput : path.join(scratch, `graph.${format === 'run' ? 'run.json' : format}`);
        await application.evaluate(({ dialog }, filePath) => {
          dialog.showSaveDialog = async () => ({ canceled: false, filePath });
        }, destination);
        await page.locator('#export-menu-toggle').click();
        await page.locator(`[data-export="${format}"]`).click();
        await expect.poll(async () => { try { return (await fs.stat(destination)).size; } catch { return 0; } }).toBeGreaterThan(0);
        await expect(page.locator('#export-menu-toggle')).toBeEnabled();
      }
      const graph = JSON.parse(await fs.readFile(graphOutput, 'utf8'));
      assert.ok(graph.methods.length > 0 && graph.edges.length > 0);
      const run = JSON.parse(await fs.readFile(path.join(scratch, 'graph.run.json'), 'utf8'));
      assert.match(run.provenance.apkSha256, /^[a-f0-9]{64}$/);

      // Import validation failure leaves the successful graph in the workspace.
      const invalid = path.join(scratch, 'invalid.json');
      await fs.writeFile(invalid, '{"schemaVersion":999}');
      await application.evaluate(({ dialog }, file) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [file] }); }, invalid);
      await page.locator('#import-graph').click();
      await expect(page.locator('#notice')).toBeVisible();
      await expect(page.locator('#graph-loaded')).toBeVisible();

      // Saved graph opening needs Java but no SDK.
      await page.locator('#open-settings').click();
      await page.locator('#setting-platforms').fill('');
      await page.locator('#save-settings').click();
      await expect(page.locator('#settings-dialog')).not.toBeVisible();
      await application.evaluate(({ dialog }, file) => { dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [file] }); }, graphOutput);
      await page.locator('#import-graph').click();
      await expect(page.locator('#graph-origin')).toContainText(/import|saved/i);
      await expect(page.locator('#graph-loaded')).toBeVisible();
      console.log(`Graph workflow passed: ${graph.methods.length} methods, ${graph.edges.length} edges; JSON/HTML/DOT/run exports and offline import.`);
    } else console.log('SDK-dependent smoke steps skipped; set ANDROSPECTER_ANDROID_PLATFORMS to enable them.');
    assert.deepEqual(errors, [], 'Renderer must have no script/CSP errors.');
    console.log(`Electron smoke passed. Screenshots: ${output}`);
  } finally {
    await Promise.race([application.close(), new Promise(resolve => setTimeout(() => {
      application.process().kill('SIGKILL'); resolve();
    }, 5000).unref())]);
    await fs.rm(scratch, { recursive: true, force: true });
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
