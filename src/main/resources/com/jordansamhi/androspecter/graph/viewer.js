(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) {
    root.AndroSpecterGraph = api;
    if (root.document) api.mount(root.document);
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  const MAX_NODES = 60;
  const MAX_RELATIONSHIPS = 180;
  const SEARCH_PAGE_SIZE = 50;
  const DETAIL_PAGE_SIZE = 20;
  const SVG = 'http://www.w3.org/2000/svg';

  function methodParts(signature) {
    const separator = signature.indexOf(': ');
    if (signature.startsWith('<') && signature.endsWith('>') && separator > 1) {
      const declaration = signature.slice(separator + 2, -1);
      const space = declaration.indexOf(' ');
      return { className: signature.slice(1, separator), label: space < 0 ? declaration : declaration.slice(space + 1) };
    }
    return { className: '', label: signature || '(empty method identifier)' };
  }

  // One pass over nodes and edges. Parallel call sites share a visual relationship,
  // while their original edge objects remain available in the inspector.
  function buildIndex(graph) {
    if (graph.viewerVersion !== 1) throw new Error('Unsupported viewer payload version.');
    const nodes = new Map();
    for (const id of graph.methods) {
      const parts = methodParts(id);
      nodes.set(id, { id, ...parts, searchText: id.toLowerCase(), incoming: [], outgoing: [], incomingCount: 0, outgoingCount: 0 });
    }
    const relationships = [];
    const bySource = new Map();
    for (const edge of graph.edges) {
      const sourceId = graph.methods[edge[0]], targetId = graph.methods[edge[1]], kind = graph.kinds[edge[2]];
      const source = nodes.get(sourceId), target = nodes.get(targetId);
      if (!source || !target) throw new Error('An edge endpoint is missing from the graph.');
      if (kind === undefined) throw new Error('An edge kind is missing from the graph.');
      let byTarget = bySource.get(edge[0]);
      if (!byTarget) { byTarget = new Map(); bySource.set(edge[0], byTarget); }
      let byKind = byTarget.get(edge[1]);
      if (!byKind) { byKind = new Map(); byTarget.set(edge[1], byKind); }
      let relationship = byKind.get(edge[2]);
      if (!relationship) {
        relationship = { id: relationships.length, source: sourceId, target: targetId, kind, edges: [] };
        byKind.set(edge[2], relationship);
        relationships.push(relationship);
        source.outgoing.push(relationship);
        target.incoming.push(relationship);
      }
      relationship.edges.push(edge);
      source.outgoingCount++;
      target.incomingCount++;
    }
    return { graph, nodes, methods: Array.from(nodes.values()), relationships };
  }

  function positiveInteger(value, name) {
    if (!Number.isSafeInteger(value) || value < 1) throw new Error(name + ' must be a positive integer.');
    return value;
  }

  // Search scans the full node index, but retains only the requested result page.
  function searchMethods(index, query, offset = 0, limit = SEARCH_PAGE_SIZE) {
    positiveInteger(limit, 'limit');
    if (!Number.isSafeInteger(offset) || offset < 0) throw new Error('offset must be a non-negative integer.');
    const needle = query.trim().toLowerCase();
    const methods = [];
    let total = 0;
    for (const method of index.methods) {
      if (method.searchText.includes(needle)) {
        if (total >= offset && methods.length < limit) methods.push(method);
        total++;
      }
    }
    return { methods, total, offset, limit };
  }

  // A directed one-hop view gives exact omitted counts without expanding a large
  // transitive closure. Both directions receive space before either fills the cap.
  function selectNeighborhood(index, focus, direction = 'both', maxNodes = MAX_NODES, maxRelationships = MAX_RELATIONSHIPS) {
    positiveInteger(maxNodes, 'maxNodes');
    positiveInteger(maxRelationships, 'maxRelationships');
    if (!['both', 'callers', 'callees'].includes(direction)) throw new Error('Unknown neighborhood direction.');
    const node = index.nodes.get(focus);
    if (!node) return { focus, methodIds: [], relationships: [], levels: new Map(), totalMethods: 0, totalRelationships: 0, totalOccurrences: 0, shownOccurrences: 0, truncated: false };
    const incoming = direction === 'callees' ? [] : node.incoming;
    const outgoing = direction === 'callers' ? [] : node.outgoing;
    const callerIds = Array.from(new Set(incoming.map(edge => edge.source))).filter(id => id !== focus);
    const calleeIds = Array.from(new Set(outgoing.map(edge => edge.target))).filter(id => id !== focus);
    const candidates = new Set([focus, ...callerIds, ...calleeIds]);
    const selected = new Set([focus]);
    const levels = new Map([[focus, 0]]);
    let callerPosition = 0, calleePosition = 0;
    while (selected.size < maxNodes && (callerPosition < callerIds.length || calleePosition < calleeIds.length)) {
      if (callerPosition < callerIds.length) {
        const id = callerIds[callerPosition++];
        if (!selected.has(id)) { selected.add(id); levels.set(id, -1); }
      }
      if (selected.size < maxNodes && calleePosition < calleeIds.length) {
        const id = calleeIds[calleePosition++];
        if (!selected.has(id)) { selected.add(id); levels.set(id, 1); }
      }
    }
    const relevant = new Map();
    for (const relationship of incoming) relevant.set(relationship.id, relationship);
    for (const relationship of outgoing) relevant.set(relationship.id, relationship);
    const relationships = [];
    let totalOccurrences = 0, shownOccurrences = 0;
    for (const relationship of relevant.values()) {
      totalOccurrences += relationship.edges.length;
      if (relationships.length < maxRelationships && selected.has(relationship.source) && selected.has(relationship.target)) {
        relationships.push(relationship);
        shownOccurrences += relationship.edges.length;
      }
    }
    return { focus, methodIds: Array.from(selected), relationships, levels, totalMethods: candidates.size,
      totalRelationships: relevant.size, totalOccurrences, shownOccurrences,
      truncated: selected.size < candidates.size || relationships.length < relevant.size };
  }

  function mount(document) {
    const data = document.getElementById('graph-data');
    if (!data) return;
    const get = id => document.getElementById(id);
    function element(tag, text, className) {
      const result = document.createElement(tag);
      if (text !== undefined) result.textContent = text;
      if (className) result.className = className;
      return result;
    }
    function svgElement(tag, attributes, text) {
      const result = document.createElementNS(SVG, tag);
      for (const [name, value] of Object.entries(attributes || {})) result.setAttribute(name, String(value));
      if (text !== undefined) result.textContent = text;
      return result;
    }
    function clear(node) { node.replaceChildren(); }
    function shorten(text, limit) { return text.length > limit ? text.slice(0, limit - 1) + '…' : text; }
    function activate(node, action) {
      node.addEventListener('click', action);
      node.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); action(); }
      });
    }

    try {
      const index = buildIndex(JSON.parse(data.textContent));
      const svg = get('graph-svg');
      const stage = get('graph-stage');
      const edgeLayer = get('graph-edges'), nodeLayer = get('graph-nodes');
      const details = get('method-details');
      const initial = index.methods.find(node => node.incoming.length || node.outgoing.length);
      let focus = initial ? initial.id : (index.methods.length ? index.methods[0].id : null);
      let searchOffset = 0, searchTimer = null, currentView = null;
      let bounds = { x: -300, y: -200, width: 600, height: 400 };
      let viewport = { ...bounds }, drag = null, dragged = false;
      const number = value => value.toLocaleString();
      get('graph-summary').textContent = number(index.nodes.size) + ' methods · ' + number(index.graph.edges.length)
        + ' call edges · ' + number(index.relationships.length) + ' relationships';

      function renderSearch() {
        let result = searchMethods(index, get('method-search').value, searchOffset);
        if (searchOffset && searchOffset >= result.total) {
          searchOffset = Math.max(0, Math.floor((result.total - 1) / SEARCH_PAGE_SIZE) * SEARCH_PAGE_SIZE);
          result = searchMethods(index, get('method-search').value, searchOffset);
        }
        const list = get('method-list');
        clear(list);
        for (const method of result.methods) {
          const button = element('button', undefined, 'method-option');
          button.type = 'button';
          button.title = method.id;
          button.setAttribute('aria-current', String(method.id === focus));
          button.append(element('span', method.label), element('span', method.className || method.id, 'class-label'));
          button.addEventListener('click', () => select(method.id));
          list.append(button);
        }
        get('search-status').textContent = result.total ? number(searchOffset + 1) + '–' + number(searchOffset + result.methods.length)
          + ' of ' + number(result.total) + ' matching methods' : 'No matching methods.';
        get('search-previous').disabled = searchOffset === 0;
        get('search-next').disabled = searchOffset + result.methods.length >= result.total;
      }

      function neighborSection(title, groups, endpoint) {
        const ids = Array.from(new Set(groups.map(group => group[endpoint])));
        details.append(element('h3', title + ' (' + number(ids.length) + ')'));
        if (!ids.length) { details.append(element('p', 'None in this snapshot.', 'muted')); return; }
        const list = element('div', undefined, 'neighbor-list');
        const status = element('p', undefined, 'muted');
        const controls = element('div', undefined, 'pagination');
        const previous = element('button', 'Previous'), next = element('button', 'Next');
        previous.type = next.type = 'button';
        controls.append(previous, next);
        let offset = 0;
        function page() {
          clear(list);
          for (const id of ids.slice(offset, offset + DETAIL_PAGE_SIZE)) {
            const button = element('button', id || '(empty method identifier)');
            button.type = 'button';
            button.addEventListener('click', () => select(id));
            list.append(button);
          }
          status.textContent = (offset + 1) + '–' + Math.min(ids.length, offset + DETAIL_PAGE_SIZE) + ' of ' + number(ids.length);
          previous.disabled = offset === 0;
          next.disabled = offset + DETAIL_PAGE_SIZE >= ids.length;
        }
        previous.addEventListener('click', () => { offset -= DETAIL_PAGE_SIZE; page(); });
        next.addEventListener('click', () => { offset += DETAIL_PAGE_SIZE; page(); });
        details.append(list, status, controls);
        page();
      }

      function showMethod(id) {
        clear(details);
        const method = index.nodes.get(id);
        if (!method) { details.append(element('p', 'This graph contains no methods.', 'muted')); return; }
        details.append(element('p', 'Method signature', 'muted'), element('pre', method.id, 'signature'));
        const counts = element('div', undefined, 'details-counts');
        counts.append(element('span', number(method.incomingCount) + ' incoming call edges', 'badge'),
          element('span', number(method.outgoingCount) + ' outgoing call edges', 'badge'));
        details.append(counts, element('p', 'Counts include parallel call sites and self calls. Select an arrow for its kind and statements.', 'muted'));
        neighborSection('Direct callers', method.incoming, 'source');
        neighborSection('Direct callees', method.outgoing, 'target');
      }

      function showRelationship(relationship) {
        clear(details);
        details.append(element('p', 'Caller → callee', 'muted'));
        for (const id of [relationship.source, relationship.target]) {
          const button = element('button', id || '(empty method identifier)', 'signature');
          button.type = 'button';
          button.addEventListener('click', () => select(id));
          details.append(button);
        }
        details.append(element('h3', 'Kind'), element('pre', relationship.kind, 'signature'),
          element('p', number(relationship.edges.length) + ' parallel call edge occurrence(s).', 'muted'));
        const list = element('div');
        const status = element('p', undefined, 'muted');
        const controls = element('div', undefined, 'pagination');
        const previous = element('button', 'Previous'), next = element('button', 'Next');
        previous.type = next.type = 'button';
        controls.append(previous, next);
        let offset = 0;
        function page() {
          clear(list);
          relationship.edges.slice(offset, offset + DETAIL_PAGE_SIZE).forEach((edge, position) => {
            const entry = element('div', undefined, 'call-site');
            entry.append(element('small', 'Occurrence ' + (offset + position + 1) + ' · statement index '
              + (edge[4] < 0 ? 'unavailable' : edge[4])));
            entry.append(element('pre', edge[3] === null ? '(statement unavailable)' : edge[3]));
            list.append(entry);
          });
          status.textContent = (offset + 1) + '–' + Math.min(relationship.edges.length, offset + DETAIL_PAGE_SIZE)
            + ' of ' + number(relationship.edges.length) + ' call edges';
          previous.disabled = offset === 0;
          next.disabled = offset + DETAIL_PAGE_SIZE >= relationship.edges.length;
        }
        previous.addEventListener('click', () => { offset -= DETAIL_PAGE_SIZE; page(); });
        next.addEventListener('click', () => { offset += DETAIL_PAGE_SIZE; page(); });
        details.append(list, status, controls);
        page();
      }

      function applyViewport() { svg.setAttribute('viewBox', [viewport.x, viewport.y, viewport.width, viewport.height].join(' ')); }
      function fit() {
        const ratio = Math.max(stage.clientWidth, 1) / Math.max(stage.clientHeight, 1);
        let width = bounds.width + 100, height = bounds.height + 100;
        if (width / height < ratio) width = height * ratio; else height = width / ratio;
        viewport = { x: bounds.x + bounds.width / 2 - width / 2, y: bounds.y + bounds.height / 2 - height / 2, width, height };
        applyViewport();
      }
      function zoom(factor, fractionX = 0.5, fractionY = 0.5) {
        const width = Math.min(100000, Math.max(150, viewport.width * factor));
        const actual = width / viewport.width;
        viewport = { x: viewport.x + viewport.width * fractionX * (1 - actual), y: viewport.y + viewport.height * fractionY * (1 - actual),
          width, height: viewport.height * actual };
        applyViewport();
      }

      function renderGraph() {
        clear(edgeLayer); clear(nodeLayer);
        currentView = selectNeighborhood(index, focus, get('direction').value);
        get('graph-empty').hidden = currentView.methodIds.length !== 0;
        const positions = new Map();
        for (const level of [-1, 0, 1]) {
          const ids = currentView.methodIds.filter(id => currentView.levels.get(id) === level);
          ids.forEach((id, position) => positions.set(id, { x: level * 430, y: (position - (ids.length - 1) / 2) * 100 }));
        }
        const parallel = new Map();
        for (const relationship of currentView.relationships) {
          // Tuple serialization avoids collisions from arbitrary method text.
          const key = JSON.stringify([relationship.source, relationship.target]);
          if (!parallel.has(key)) parallel.set(key, []);
          parallel.get(key).push(relationship);
        }
        for (const siblings of parallel.values()) siblings.forEach((relationship, position) => {
          const source = positions.get(relationship.source), target = positions.get(relationship.target);
          const shift = (position - (siblings.length - 1) / 2) * 22;
          let path, labelX, labelY;
          if (relationship.source === relationship.target) {
            const extent = 80 + position * 20;
            path = 'M ' + (source.x + 95) + ' ' + (source.y - 29) + ' C ' + (source.x + 230 + extent) + ' ' + (source.y - extent)
              + ', ' + (source.x + 230 + extent) + ' ' + (source.y + extent) + ', ' + (source.x + 140) + ' ' + (source.y + 10);
            labelX = source.x + 245 + extent; labelY = source.y;
          } else {
            const rightward = target.x > source.x;
            const sx = source.x + (rightward ? 140 : -140), tx = target.x + (rightward ? -145 : 145);
            const hasReverse = parallel.has(JSON.stringify([relationship.target, relationship.source]));
            const curvature = source.x === target.x ? 220 + Math.abs(shift) : shift + (hasReverse ? (rightward ? 20 : -20) : 0);
            const mx = (sx + tx) / 2 + (source.x === target.x ? curvature : 0);
            path = 'M ' + sx + ' ' + source.y + ' Q ' + mx + ' ' + ((source.y + target.y) / 2 + curvature) + ' ' + tx + ' ' + target.y;
            labelX = (sx + 2 * mx + tx) / 4; labelY = (source.y + target.y) / 2 + curvature / 2 - 7;
          }
          const description = relationship.kind + ' · ' + relationship.edges.length + ' call edge(s)';
          const edge = svgElement('path', { d: path, class: 'graph-edge', tabindex: 0, role: 'button', 'aria-label': description });
          edge.append(svgElement('title', {}, relationship.source + ' → ' + relationship.target + '\n' + description));
          activate(edge, () => { if (!dragged) showRelationship(relationship); });
          edgeLayer.append(edge, svgElement('text', { x: labelX, y: labelY, 'text-anchor': 'middle', class: 'edge-label' },
            shorten(relationship.kind, 18) + (relationship.edges.length > 1 ? ' ×' + relationship.edges.length : '')));
        });
        for (const id of currentView.methodIds) {
          const point = positions.get(id), method = index.nodes.get(id);
          const node = svgElement('g', { transform: 'translate(' + point.x + ',' + point.y + ')',
            class: 'graph-node' + (id === focus ? ' selected' : ''), tabindex: 0, role: 'button', 'aria-label': id || '(empty method identifier)' });
          node.append(svgElement('title', {}, id), svgElement('rect', { x: -140, y: -29, width: 280, height: 58, rx: 8 }),
            svgElement('text', { x: -128, y: -3 }, shorten(method.label, 34)),
            svgElement('text', { x: -128, y: 17, class: 'node-class' }, shorten(method.className || method.id, 42)));
          activate(node, () => { if (!dragged) select(id); });
          nodeLayer.append(node);
        }
        const points = Array.from(positions.values());
        if (points.length) {
          const left = Math.min(...points.map(point => point.x)) - 160;
          const right = Math.max(...points.map(point => point.x)) + 160;
          const top = Math.min(...points.map(point => point.y)) - 70;
          const bottom = Math.max(...points.map(point => point.y)) + 70;
          // Include actual paths and labels (especially self loops) in the fit box.
          const edgeBounds = edgeLayer.getBBox();
          bounds = { x: Math.min(left, edgeBounds.x), y: Math.min(top, edgeBounds.y),
            width: Math.max(right, edgeBounds.x + edgeBounds.width) - Math.min(left, edgeBounds.x),
            height: Math.max(bottom, edgeBounds.y + edgeBounds.height) - Math.min(top, edgeBounds.y) };
        }
        const status = get('view-status');
        status.classList.toggle('truncated', currentView.truncated);
        status.textContent = 'Showing ' + number(currentView.methodIds.length) + ' of ' + number(currentView.totalMethods)
          + ' methods and ' + number(currentView.relationships.length) + ' of ' + number(currentView.totalRelationships)
          + ' relationships in this one-hop neighborhood (' + number(currentView.shownOccurrences) + ' of '
          + number(currentView.totalOccurrences) + ' call edge occurrences).'
          + (currentView.truncated ? ' Display limit reached; omitted methods and call edges remain searchable and available in Selection.' : '')
          + ' Only calls incident to the selected method are drawn.';
        fit();
      }

      function select(id) { focus = id; renderSearch(); showMethod(id); renderGraph(); }
      get('method-search').addEventListener('input', () => {
        clearTimeout(searchTimer);
        searchOffset = 0;
        searchTimer = setTimeout(renderSearch, 120);
      });
      get('search-previous').addEventListener('click', () => { searchOffset = Math.max(0, searchOffset - SEARCH_PAGE_SIZE); renderSearch(); });
      get('search-next').addEventListener('click', () => { searchOffset += SEARCH_PAGE_SIZE; renderSearch(); });
      get('direction').addEventListener('change', renderGraph);
      get('fit-graph').addEventListener('click', fit);
      get('zoom-in').addEventListener('click', () => zoom(0.8));
      get('zoom-out').addEventListener('click', () => zoom(1.25));
      svg.addEventListener('wheel', event => {
        event.preventDefault();
        const box = svg.getBoundingClientRect();
        zoom(event.deltaY > 0 ? 1.12 : 0.89, (event.clientX - box.left) / box.width, (event.clientY - box.top) / box.height);
      }, { passive: false });
      svg.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        dragged = false;
        drag = { x: event.clientX, y: event.clientY, viewport: { ...viewport }, pointer: event.pointerId };
      });
      svg.addEventListener('pointermove', event => {
        if (!drag || event.pointerId !== drag.pointer) return;
        const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
        if (!dragged && Math.abs(dx) + Math.abs(dy) < 5) return;
        dragged = true;
        svg.setPointerCapture(event.pointerId);
        svg.classList.add('dragging');
        viewport.x = drag.viewport.x - dx * drag.viewport.width / Math.max(svg.clientWidth, 1);
        viewport.y = drag.viewport.y - dy * drag.viewport.height / Math.max(svg.clientHeight, 1);
        applyViewport();
      });
      function endDrag(event) {
        if (drag && event.pointerId === drag.pointer) {
          if (svg.hasPointerCapture(event.pointerId)) svg.releasePointerCapture(event.pointerId);
          drag = null;
          svg.classList.remove('dragging');
          setTimeout(() => { dragged = false; }, 0);
        }
      }
      svg.addEventListener('pointerup', endDrag);
      svg.addEventListener('pointercancel', endDrag);
      svg.addEventListener('pointerleave', event => { if (drag && !dragged) endDrag(event); });
      const view = document.defaultView;
      if (view && view.ResizeObserver) new view.ResizeObserver(fit).observe(stage);
      select(focus);
    } catch (failure) {
      get('graph-summary').textContent = 'Cannot display graph';
      const details = get('method-details');
      clear(details);
      details.append(element('p', failure.message || String(failure), 'error'));
    }
  }

  return { buildIndex, searchMethods, selectNeighborhood, mount, MAX_NODES, MAX_RELATIONSHIPS };
});
