'use strict';
(() => {
  const api = window.androspecter;
  const $ = id => document.getElementById(id);
  const state = { view: 'overview', apk: null, metadata: null, inspect: null, graph: null, model: null, focused: null, selectedEdges: null, settings: null, environment: null, busy: false, picking: false, started: 0, timer: null, logs: '', logCount: 0, neighborLimit: 4, phase: '', version: '' };
  const titles = { overview: 'Overview', code: 'Code inspection', graph: 'Call graph' };
  const resultKind = () => ({ overview: 'metadata', code: 'inspect', graph: 'graph' })[state.view];
  const formatNumber = value => new Intl.NumberFormat().format(value);
  const display = value => value === null || value === undefined ? 'Not declared' : String(value);
  const permissionName = value => value.name || 'Unresolved permission name';
  const cleanError = error => String(error?.message || error || 'The operation could not be completed.').replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '');

  /** Creates a DOM node; all result text is assigned as text, never executable markup. */
  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function icon(name, small = false) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.classList.add('icon'); if (small) svg.classList.add('small');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use'); use.setAttribute('href', '#i-' + name); svg.append(use); return svg;
  }
  function notice(text, error = false) { $('notice-text').textContent = text; $('notice').classList.toggle('error', error); $('notice').hidden = !text; }
  function status(text, variant = '') { $('run-status-text').textContent = text; $('run-status-dot').className = 'status-dot ' + variant; }
  function appendLog(text) {
    const value = String(text);
    state.logs = (state.logs + value + (value.endsWith('\n') ? '' : '\n')).slice(-180000);
    state.logCount += value.split('\n').filter(Boolean).length;
    $('activity-output').textContent = state.logs;
    $('activity-count').textContent = formatNumber(state.logCount);
    $('activity-output').scrollTop = $('activity-output').scrollHeight;
  }
  function setBusy(value, phase = '') {
    state.busy = value; document.body.classList.toggle('is-busy', value);
    $('cancel-run').hidden = !value;
    if (value) {
      state.phase = phase; state.started = Date.now(); status(phase, 'running');
      clearInterval(state.timer);
      state.timer = setInterval(() => { $('run-elapsed').textContent = Math.floor((Date.now() - state.started) / 1000) + 's'; }, 500);
    } else { clearInterval(state.timer); state.timer = null; $('run-elapsed').textContent = ''; }
    updateControls();
  }
  function updateControls() {
    const locked = state.busy || state.picking;
    const canRun = !locked && Boolean(state.apk);
    for (const id of ['open-apk', 'welcome-open', 'import-graph', 'welcome-import', 'graph-import', 'open-settings', 'setup-settings']) $(id).disabled = locked;
    $('inspect-code').disabled = !canRun;
    $('build-graph').disabled = !canRun;
    for (const id of ['code-packages', 'code-invocations', 'graph-packages', 'graph-algorithm']) $(id).disabled = locked;
    const current = state[resultKind()];
    $('export-menu-toggle').disabled = locked || !current;
    $('copy-command').disabled = locked || !current || !current.provenance?.command;
    document.querySelectorAll('.graph-export').forEach(node => { node.hidden = state.view !== 'graph'; });
    $('show-path').disabled = !state.focused;
  }
  function showView(view) {
    state.view = view; $('view-title').textContent = titles[view];
    for (const name of ['overview', 'code', 'graph']) $(name + '-view').hidden = name !== view;
    document.querySelectorAll('[data-view]').forEach(button => {
      const active = button.dataset.view === view; button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    });
    $('main-content').scrollTop = 0; closeExport(); updateControls();
    if (view === 'graph' && state.model) requestAnimationFrame(drawConnections);
  }
  function resetResults() {
    state.metadata = state.inspect = state.graph = state.model = state.focused = null;
    state.selectedEdges = null; state.neighborLimit = 4;
    $('overview-empty').hidden = false; $('overview-loaded').hidden = true;
    $('code-empty').hidden = false; $('code-loaded').hidden = true;
    $('graph-empty').hidden = false; $('graph-loaded').hidden = true;
    $('code-nav-count').textContent = ''; $('graph-nav-count').textContent = '';
    $('method-search').value = ''; $('code-search').value = ''; $('path-target').value = '';
    $('path-result').replaceChildren(); $('path-finder').hidden = true;
    notice(''); closeExport(); updateControls();
  }
  function showApk() {
    $('apk-name').textContent = state.apk?.name || 'No APK selected';
    $('apk-name').title = state.apk?.path || '';
    $('apk-package').textContent = state.metadata?.data?.packageName || (state.apk ? 'Ready for analysis' : 'Your workspace starts here');
    $('apk-card').classList.toggle('selected', Boolean(state.apk));
  }
  /** Executes one UI operation and preserves a previously completed result if the new run fails. */
  async function perform(label, operation, success) {
    if (state.busy || state.picking) return;
    notice(''); closeExport(); setBusy(true, label); appendLog('\n' + label);
    let successText = 'Ready';
    try {
      const result = await operation();
      if (result === null || result === undefined) { successText = 'Ready'; return; }
      if (success) await success(result);
      successText = label.replace(/…$/, '') + ' · completed';
      appendLog('Completed.');
    } catch (error) {
      const message = cleanError(error);
      const cancelled = /cancelled|canceled/i.test(message) || error?.code === 'CANCELLED';
      const timedOut = /timed.?out|time limit|deadline/i.test(message) || error?.code === 'TIMED_OUT';
      successText = cancelled ? 'Analysis cancelled' : timedOut ? 'Time limit reached' : 'Operation failed';
      appendLog(successText + ': ' + message);
      notice((cancelled ? 'The operation was cancelled.' : message) + (state[resultKind()] ? ' The previous completed result is still available.' : ''), !cancelled);
      if (!cancelled) $('run-status-dot').className = 'status-dot error';
      status(successText, cancelled ? '' : 'error');
      return;
    } finally {
      setBusy(false);
      if (!$('notice').classList.contains('error') || $('notice').hidden) status(successText);
    }
  }
  async function chooseApk() {
    if (state.busy || state.picking) return;
    let picked;
    state.picking = true; updateControls();
    try { picked = await api.chooseApk(); }
    catch (error) { notice(cleanError(error), true); return; }
    finally { state.picking = false; updateControls(); }
    if (!picked) return;
    resetResults(); state.apk = picked; showApk(); showView('overview');
    await readMetadata();
  }
  async function readMetadata() {
    if (!state.apk) return;
    await perform('Reading manifest…', () => api.metadata(), result => { state.metadata = result; renderMetadata(); showApk(); });
  }
  async function importGraph() {
    await perform('Opening saved graph…', () => api.importGraph(), result => {
      resetResults(); state.apk = null; showApk();
      $('apk-name').textContent = result.name || 'Saved call graph';
      $('apk-package').textContent = 'Imported GraphJson';
      state.graph = result; prepareGraph(); showView('graph');
    });
  }
  function packages(id) { return $(id).value.split(',').map(value => value.trim()).filter(Boolean); }
  function stat(label, value, hint) {
    const card = element('div', 'stat-card'); card.append(element('p', 'stat-label', label), element('strong', '', value)); if (hint) card.append(element('span', 'stat-hint', hint)); return card;
  }
  function emptyList(container, message) { container.replaceChildren(element('p', 'no-items', message)); }
  function renderMetadata() {
    const data = state.metadata.data;
    $('overview-empty').hidden = true; $('overview-loaded').hidden = false;
    $('summary-package').textContent = data.packageName;
    $('summary-version').textContent = 'Version ' + display(data.versionName) + ' · code ' + display(data.versionCode);
    const permissions = Array.isArray(data.usesPermissions) ? data.usesPermissions : [];
    const components = Array.isArray(data.components) ? data.components : [];
    $('metadata-stats').replaceChildren(stat('Minimum SDK', display(data.minSdkVersion), 'Declared API level'), stat('Target SDK', display(data.targetSdkVersion), 'Declared API level'), stat('Permissions', formatNumber(permissions.length), 'Manifest requests'), stat('Components', formatNumber(components.length), 'Manifest declarations'));
    $('permission-count').textContent = formatNumber(permissions.length); $('component-count').textContent = formatNumber(components.length);
    const plist = $('permission-list'); plist.replaceChildren();
    if (!permissions.length) emptyList(plist, 'No permissions declared.');
    for (const permission of permissions.slice(0, 500)) {
      const row = element('div', 'detail-row'); row.append(element('strong', '', permissionName(permission)));
      if (permission.maxSdkVersion !== null && permission.maxSdkVersion !== undefined) row.append(element('small', '', 'Maximum SDK: ' + permission.maxSdkVersion));
      plist.append(row);
    }
    if (permissions.length > 500) plist.append(element('p', 'no-items', 'Showing 500 declarations. Export JSON for the full list.'));
    const clist = $('component-list'); clist.replaceChildren();
    if (!components.length) emptyList(clist, 'No components declared.');
    for (const component of components.slice(0, 500)) {
      const row = element('div', 'detail-row'); row.append(element('strong', '', component.name || 'Unresolved component name'));
      const meta = element('div', 'component-meta'); meta.append(element('span', 'kind-label', String(component.kind).replaceAll('_', ' ')), element('span', '', 'Exported: ' + display(component.declaredExported)));
      row.append(meta); clist.append(row);
    }
    if (components.length > 500) clist.append(element('p', 'no-items', 'Showing 500 declarations. Export JSON for the full list.'));
  }
  function renderCode() {
    const data = state.inspect.data;
    $('code-empty').hidden = true; $('code-loaded').hidden = false;
    $('code-nav-count').textContent = formatNumber(data.classCount);
    $('code-stats').replaceChildren(stat('Application classes', formatNumber(data.classCount)), stat('Declared methods', formatNumber(data.methodCount)), stat('Invocation sites', data.invocationCount === null || data.invocationCount === undefined ? 'Not requested' : formatNumber(data.invocationCount)));
    const scope = state.inspect.provenance?.options?.packages || [];
    $('code-scope').textContent = scope.length ? 'Scope: ' + scope.join(', ') : 'All loaded application classes';
    renderClasses();
  }
  function renderClasses() {
    if (!state.inspect) return;
    const query = $('code-search').value.trim().toLowerCase();
    const classes = state.inspect.data.classes.filter(value => value.name.toLowerCase().includes(query) || (value.methods || []).some(method => method.toLowerCase().includes(query)));
    const list = $('class-list'); list.replaceChildren();
    if (!classes.length) emptyList(list, query ? 'No classes or methods match your search.' : 'No application classes were found in this scope.');
    for (const clazz of classes.slice(0, 150)) {
      const details = element('details', 'class-row');
      const summary = element('summary'); summary.append(element('span', 'class-name', clazz.name), element('span', 'count-label', formatNumber(clazz.methodCount) + ' methods')); details.append(summary);
      let populated = false;
      details.addEventListener('toggle', () => {
        if (!details.open || populated) return;
        populated = true;
        const items = element('ul', 'class-methods');
        const methods = (clazz.methods || []).filter(method => !query || clazz.name.toLowerCase().includes(query) || method.toLowerCase().includes(query));
        for (const method of methods.slice(0, 250)) items.append(element('li', '', method));
        if (!methods.length) items.append(element('li', '', 'No methods in this class.'));
        if (methods.length > 250) items.append(element('li', '', 'Showing 250 methods. Narrow your search or export the full result.'));
        details.append(items);
      });
      if (query && !clazz.name.toLowerCase().includes(query)) details.open = true;
      list.append(details);
    }
    $('class-count').textContent = 'Showing ' + formatNumber(Math.min(classes.length, 150)) + ' of ' + formatNumber(classes.length) + ' matching classes' + (classes.length > 150 ? ' · narrow your search to see more' : '');
  }
  function prepareGraph() {
    state.model = window.GraphModel.create(state.graph.data);
    state.focused = state.model.methods.find(method => /onCreate\(/.test(method)) || state.model.methods[0] || null;
    state.selectedEdges = null; state.neighborLimit = 4;
    $('graph-loaded').hidden = false; $('graph-empty').hidden = true;
    $('graph-nav-count').textContent = formatNumber(state.model.methods.length);
    $('graph-totals').textContent = formatNumber(state.model.methods.length) + ' methods · ' + formatNumber(state.model.edges.length) + ' edge occurrences';
    $('graph-analysis-id').textContent = state.graph.data.analysisId || ''; $('graph-analysis-id').title = 'Analysis ID: ' + (state.graph.data.analysisId || 'Unavailable');
    const provenance = state.graph.provenance || {};
    const imported = provenance.imported === true || !state.apk;
    $('graph-origin').textContent = imported ? 'Imported graph' : 'Graph ready';
    // Describe the saved result, independently of controls for a future analysis.
    const optionsUsed = provenance.options || {};
    const scope = Array.isArray(optionsUsed.packages)
      ? optionsUsed.packages.length ? 'Packages: ' + optionsUsed.packages.join(', ') : 'No package filter'
      : 'Package filter unknown';
    $('graph-result-context').textContent = imported
      ? 'Original APK, algorithm, package filters, and completion status are unknown.'
      : (optionsUsed.algorithm || 'Algorithm unknown') + ' · ' + scope;
    const options = $('graph-method-options'); options.replaceChildren();
    // Autocomplete is deliberately bounded; any full method signature can still be pasted.
    for (const method of state.model.methods.slice(0, 500)) { const option = element('option'); option.value = method; options.append(option); }
    renderMethods(); renderNeighborhood();
  }
  function renderMethods() {
    if (!state.model) return;
    const result = state.model.search($('method-search').value);
    const list = $('method-list'); list.replaceChildren();
    for (const method of result.methods) {
      const labels = state.model.labels.get(method); const button = element('button', 'method-item'); button.type = 'button'; button.title = method; button.dataset.method = method; button.classList.toggle('selected', method === state.focused); button.setAttribute('aria-pressed', String(method === state.focused));
      button.append(element('strong', '', labels.method), element('small', '', labels.className || 'Unknown class'));
      button.addEventListener('click', () => selectMethod(method)); list.append(button);
    }
    if (!result.methods.length) emptyList(list, state.model.methods.length ? 'No methods match.' : 'The selected graph has no methods.');
    $('method-count').textContent = formatNumber(result.methods.length) + ' of ' + formatNumber(result.total) + ' matches' + (result.total > result.methods.length ? ' · refine search' : '');
  }
  function selectMethod(method) {
    if (!state.model?.outgoing.has(method)) return;
    state.focused = method; state.selectedEdges = null; state.neighborLimit = 4;
    $('path-result').replaceChildren();
    renderMethods(); renderNeighborhood(); updateControls();
  }
  function graphNode(method, focused = false) {
    const labels = state.model.labels.get(method);
    const node = element(focused ? 'div' : 'button', 'graph-node' + (focused ? ' focused' : ''));
    node.title = method; node.dataset.graphNode = focused ? 'focus' : method;
    node.append(element('strong', '', labels.method), element('small', '', labels.shortClass));
    if (!focused) { node.type = 'button'; node.addEventListener('click', () => selectMethod(method)); }
    return node;
  }
  function renderNeighbors(direction, container) {
    const neighbors = state.model.neighbors(state.focused, direction);
    container.replaceChildren();
    if (!neighbors.length) container.append(element('div', 'no-neighbors', direction === 'incoming' ? 'No callers in this graph.' : 'No callees in this graph.'));
    for (const neighbor of neighbors.slice(0, state.neighborLimit)) {
      const group = element('div', 'neighbor-group'); group.append(graphNode(neighbor.signature));
      const inspect = element('button', 'neighbor-inspect', neighbor.edges.length + (neighbor.edges.length === 1 ? ' call · details' : ' calls · details'));
      inspect.type = 'button'; inspect.title = 'Inspect call sites between these methods';
      inspect.addEventListener('click', () => { state.selectedEdges = neighbor.edges; renderEdges(); $('edge-details').scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }); group.append(inspect); container.append(group);
    }
    return neighbors;
  }
  function renderNeighborhood() {
    $('focused-method').replaceChildren();
    if (!state.model || !state.focused) {
      $('callers-list').replaceChildren(); $('callees-list').replaceChildren(); $('selected-signature').textContent = ''; $('callers-count').textContent = '0'; $('callees-count').textContent = '0'; $('graph-connections').replaceChildren(); $('edge-list').replaceChildren();
      $('graph-neighborhood-note').textContent = 'No methods or edges remain in the selected graph. Try widening the package scope.';
      $('show-more-neighbors').hidden = true; return;
    }
    $('focused-method').append(graphNode(state.focused, true));
    const incoming = renderNeighbors('incoming', $('callers-list'));
    const outgoing = renderNeighbors('outgoing', $('callees-list'));
    $('callers-count').textContent = formatNumber(incoming.length); $('callees-count').textContent = formatNumber(outgoing.length);
    const shown = Math.min(incoming.length, state.neighborLimit) + Math.min(outgoing.length, state.neighborLimit);
    const total = incoming.length + outgoing.length;
    $('graph-neighborhood-note').textContent = 'Showing ' + shown + ' of ' + formatNumber(total) + ' direct neighbor connections. Select any method to continue exploring.' + (total > shown ? ' Additional neighbors are available through method search and exports.' : '');
    $('show-more-neighbors').hidden = state.neighborLimit >= 12 || (incoming.length <= state.neighborLimit && outgoing.length <= state.neighborLimit);
    $('selected-signature').textContent = state.focused;
    renderEdges(); requestAnimationFrame(drawConnections);
  }
  function renderEdges() {
    const list = $('edge-list'); list.replaceChildren();
    if (!state.focused) return;
    if (!state.selectedEdges) { list.append(element('div', 'edge-row', 'Use a connection’s “details” action to inspect its call kind, Jimple statement, and position.')); return; }
    for (const edge of state.selectedEdges.slice(0, 30)) {
      const row = element('div', 'edge-row');
      row.append(element('span', 'pill', edge.kind || 'Unknown kind'), element('span', '', edge.callSiteIndex < 0 ? 'Position unavailable' : 'Jimple unit ' + edge.callSiteIndex));
      row.append(element('div', 'edge-label', edge.source + ' → ' + edge.target));
      row.append(element('code', '', edge.callSite === null || edge.callSite === undefined ? 'Call-site statement unavailable.' : edge.callSite)); list.append(row);
    }
    if (state.selectedEdges.length > 30) list.append(element('div', 'edge-row', 'Showing 30 of ' + state.selectedEdges.length + ' edge occurrences. Export JSON for all call sites.'));
  }
  function drawConnections() {
    const svg = $('graph-connections'); svg.replaceChildren();
    if (state.view !== 'graph' || !state.focused || $('graph-loaded').hidden) return;
    const canvas = $('graph-canvas').getBoundingClientRect(); const focus = $('focused-method').firstElementChild?.getBoundingClientRect(); if (!focus || !canvas.width) return;
    svg.setAttribute('viewBox', '0 0 ' + canvas.width + ' ' + canvas.height);
    for (const [side, container] of [['left', $('callers-list')], ['right', $('callees-list')]]) {
      for (const node of container.querySelectorAll('.graph-node')) {
        const rect = node.getBoundingClientRect();
        const from = side === 'left' ? { x: rect.right - canvas.left, y: rect.top + rect.height / 2 - canvas.top } : { x: focus.right - canvas.left, y: focus.top + focus.height / 2 - canvas.top };
        const to = side === 'left' ? { x: focus.left - canvas.left, y: focus.top + focus.height / 2 - canvas.top } : { x: rect.left - canvas.left, y: rect.top + rect.height / 2 - canvas.top };
        const middle = (from.x + to.x) / 2;
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', `M${from.x},${from.y} C${middle},${from.y} ${middle},${to.y} ${to.x},${to.y}`); path.setAttribute('stroke', '#b5d0c6'); path.setAttribute('stroke-width', '1.4'); path.setAttribute('fill', 'none'); svg.append(path);
        const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'path'); arrow.setAttribute('d', `M${to.x - 4},${to.y - 3} L${to.x},${to.y} L${to.x - 4},${to.y + 3}`); arrow.setAttribute('stroke', '#97baac'); arrow.setAttribute('stroke-width', '1.2'); arrow.setAttribute('fill', 'none'); svg.append(arrow);
      }
    }
  }
  function findPath() {
    const result = $('path-result'); result.replaceChildren(); const target = $('path-target').value.trim();
    if (!state.model || !state.focused) return;
    if (!state.model.outgoing.has(target)) { result.textContent = 'Choose a target method present in this graph. Suggestions show the first 500 methods; you can paste any full signature.'; return; }
    const path = window.GraphModel.shortestPath(state.model, state.focused, target);
    if (!path) { result.textContent = 'No directed path was found in this graph. Excluded packages and incomplete analysis can affect reachability.'; return; }
    result.append(element('p', '', (path.length - 1) + ' calls on the shortest graph path. This is structural reachability, not a proven execution.'));
    for (const [index, method] of path.slice(0, 100).entries()) {
      const button = element('button', 'path-step', (index + 1) + '. ' + method); button.type = 'button'; button.addEventListener('click', () => selectMethod(method)); result.append(button);
    }
    if (path.length > 100) result.append(element('p', '', 'Showing the first 100 methods on this path.'));
  }
  function closeExport() { $('export-menu').hidden = true; $('export-menu-toggle').setAttribute('aria-expanded', 'false'); }
  async function exportResult(format) {
    const kind = resultKind(); closeExport();
    await perform('Exporting result…', () => api.exportResult(kind, format), path => notice('Saved to ' + path));
  }
  function updateEnvironment() {
    const env = state.environment; if (!env) return;
    const critical = !env.javaOk || !env.jarOk;
    const sdkMissing = !env.platformsOk;
    $('settings-indicator').className = 'status-dot ' + (critical ? 'error' : sdkMissing ? 'warning' : '');
    $('setup-banner').hidden = !critical && !sdkMissing;
    $('setup-message').textContent = !env.javaOk ? 'Java 17 or newer is needed to start an analysis.' : !env.jarOk ? 'The AndroSpecter analysis backend is missing.' : 'Choose an Android SDK to enable code inspection and graph construction.';
    const list = $('environment-check'); list.replaceChildren();
    for (const [name, ok, detail] of [['Java runtime', env.javaOk, env.javaOk ? env.javaVersion || 'Ready' : 'Not available'], ['Analysis backend', env.jarOk, env.jarOk ? 'Ready' : 'Build or reinstall the desktop app'], ['Android platforms', env.platformsOk, env.platformsOk ? 'Ready' : 'Required for APK code analysis']]) {
      const row = element('div', 'environment-row'); row.append(element('span', 'status-dot ' + (ok ? '' : 'warning')), element('strong', '', name), element('span', '', detail)); list.append(row);
    }
    if (env.java) $('environment-check').title = env.java;
    for (const error of (env.errors || [])) list.append(element('p', 'settings-error', error));
  }
  function openSettings() {
    if (state.busy || !state.settings) return;
    $('setting-java').value = state.settings.java || ''; $('setting-platforms').value = state.settings.platforms || '';
    $('setting-memory').value = String(state.settings.memoryGb || 4); $('setting-timeout').value = String(state.settings.timeoutSeconds ?? 300);
    $('settings-error').hidden = true; updateEnvironment(); $('settings-dialog').showModal();
  }
  async function saveSettings(event) {
    event.preventDefault(); if (state.busy) return;
    const next = { java: $('setting-java').value.trim(), platforms: $('setting-platforms').value.trim(), memoryGb: Number($('setting-memory').value), timeoutSeconds: Number($('setting-timeout').value) };
    $('save-settings').disabled = true; $('settings-error').hidden = true;
    try {
      const result = await api.saveSettings(next); state.settings = result.settings; state.environment = result.environment; updateEnvironment(); $('settings-dialog').close(); notice('Settings saved.');
      if (state.apk && !state.metadata && state.environment.javaOk && state.environment.jarOk) await readMetadata();
    } catch (error) { $('settings-error').textContent = cleanError(error); $('settings-error').hidden = false; }
    finally { $('save-settings').disabled = false; }
  }
  function toggleActivity(open) { $('activity-drawer').hidden = !open; $('toggle-activity').setAttribute('aria-expanded', String(open)); if (open) $('activity-output').scrollTop = $('activity-output').scrollHeight; }
  function debounce(fn, wait = 120) { let timer; return (...args) => { clearTimeout(timer); timer = setTimeout(() => fn(...args), wait); }; }

  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => showView(button.dataset.view)));
  document.querySelectorAll('[data-go]').forEach(button => button.addEventListener('click', () => showView(button.dataset.go)));
  for (const id of ['open-apk', 'welcome-open']) $(id).addEventListener('click', chooseApk);
  for (const id of ['import-graph', 'welcome-import', 'graph-import']) $(id).addEventListener('click', importGraph);
  for (const id of ['open-settings', 'setup-settings']) $(id).addEventListener('click', openSettings);
  $('dismiss-notice').addEventListener('click', () => notice(''));
  $('inspect-code').addEventListener('click', () => perform('Inspecting code…', () => api.inspect({ packages: packages('code-packages'), invocations: $('code-invocations').checked }), result => { state.inspect = result; renderCode(); }));
  $('build-graph').addEventListener('click', () => perform('Building call graph…', () => api.graph({ packages: packages('graph-packages'), algorithm: $('graph-algorithm').value }), result => { state.graph = result; prepareGraph(); }));
  $('code-search').addEventListener('input', debounce(renderClasses));
  $('method-search').addEventListener('input', debounce(renderMethods));
  $('show-more-neighbors').addEventListener('click', () => { state.neighborLimit = 12; renderNeighborhood(); });
  $('show-path').addEventListener('click', () => { $('path-finder').hidden = !$('path-finder').hidden; if (!$('path-finder').hidden) $('path-target').focus(); });
  $('find-path').addEventListener('click', findPath); $('path-target').addEventListener('keydown', event => { if (event.key === 'Enter') findPath(); });
  $('export-menu-toggle').addEventListener('click', () => { const open = $('export-menu').hidden; $('export-menu').hidden = !open; $('export-menu-toggle').setAttribute('aria-expanded', String(open)); });
  document.querySelectorAll('[data-export]').forEach(button => button.addEventListener('click', () => exportResult(button.dataset.export)));
  $('copy-command').addEventListener('click', async () => { try { await api.copyCommand(resultKind()); notice('Analysis command copied to the clipboard.'); } catch (error) { notice(cleanError(error), true); } });
  document.addEventListener('click', event => { if (!event.target.closest('.export-wrap')) closeExport(); });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') closeExport();
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'o' && !$('settings-dialog').open) { event.preventDefault(); chooseApk(); }
    if ((event.metaKey || event.ctrlKey) && event.key === ',' && !$('settings-dialog').open) { event.preventDefault(); openSettings(); }
  });
  $('cancel-run').addEventListener('click', async () => { $('cancel-run').disabled = true; status('Stopping analysis…', 'running'); try { await api.cancel(); } catch (error) { notice(cleanError(error), true); } finally { $('cancel-run').disabled = false; } });
  $('toggle-activity').addEventListener('click', () => toggleActivity($('activity-drawer').hidden)); $('close-activity').addEventListener('click', () => toggleActivity(false));
  $('clear-activity').addEventListener('click', () => { state.logs = ''; state.logCount = 0; $('activity-output').textContent = 'Activity cleared.'; $('activity-count').textContent = ''; });
  $('close-settings').addEventListener('click', () => $('settings-dialog').close());
  $('settings-form').addEventListener('submit', saveSettings);
  document.querySelectorAll('[data-setting]').forEach(button => button.addEventListener('click', async () => { try { const path = await api.chooseSetting(button.dataset.setting); if (path) $('setting-' + button.dataset.setting).value = path; } catch (error) { $('settings-error').textContent = cleanError(error); $('settings-error').hidden = false; } }));
  $('check-environment').addEventListener('click', async () => {
    $('check-environment').disabled = true;
    try { state.environment = await api.checkEnvironment(); updateEnvironment(); $('settings-error').textContent = 'Checked the saved settings. Save any edited paths to validate them.'; $('settings-error').hidden = false; }
    catch (error) { $('settings-error').textContent = cleanError(error); $('settings-error').hidden = false; }
    finally { $('check-environment').disabled = false; }
  });
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(drawConnections).observe($('graph-canvas'));
  window.addEventListener('resize', drawConnections);
  if (!api) { status('Desktop bridge unavailable', 'error'); notice('Launch AndroSpecter with the desktop application to open files and run analyses.', true); updateControls(); return; }
  document.body.classList.add(api.platform === 'darwin' ? 'is-mac' : api.platform === 'win32' ? 'is-win' : 'is-linux');
  if (api.platform !== 'darwin') $('open-shortcut').textContent = 'Ctrl O';
  const unsubscribe = api.onUpdate(update => {
    if (update.type === 'log') appendLog(update.text);
    else if (update.type === 'phase' && state.busy) { state.phase = update.phase; status(update.phase, 'running'); }
  });
  window.addEventListener('beforeunload', () => { if (typeof unsubscribe === 'function') unsubscribe(); clearInterval(state.timer); });
  api.bootstrap().then(value => {
    state.version = value.version; state.settings = value.settings; state.environment = value.environment;
    $('app-version').textContent = 'v' + value.version; updateEnvironment(); updateControls(); status('Ready to explore');
  }).catch(error => { notice(cleanError(error), true); status('Setup failed', 'error'); updateControls(); });
})();
