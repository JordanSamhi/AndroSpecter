'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { create, describe, shortestPath } = require('../src/graph');
const a = '<sample.Main: void onCreate(android.os.Bundle)>', b = '<sample.Worker: void run()>', c = '<sample.Output: void send()>', isolated = '<sample.Other: void idle()>';
const edge = (source, target, callSiteIndex = 0) => ({ source, target, kind: 'STATIC', callSite: 'invoke', callSiteIndex });
function fixture() { return { schemaVersion: 1, analysisId: '9d58ec11-ffdd-4b7e-a5ac-45a64acefe8e', methods: [a, b, c, isolated], edges: [edge(a, b), edge(a, b, 1), edge(b, c), edge(c, a)] }; }

test('indexes preserve parallel edges and distinguish unique neighbors', () => {
  const graph = create(fixture());
  assert.equal(graph.outgoing.get(a).length, 2);
  assert.equal(graph.incoming.get(b).length, 2);
  assert.equal(graph.neighbors(a, 'outgoing').length, 1);
  assert.equal(graph.neighbors(a, 'outgoing')[0].edges.length, 2);
  assert.deepEqual(graph.neighbors(isolated, 'incoming'), []);
});
test('shortest paths handle cycles, zero-edge self paths, isolation and missing methods', () => {
  const graph = create(fixture());
  assert.deepEqual(shortestPath(graph, a, c), [a, b, c]);
  assert.deepEqual(shortestPath(graph, a, a), [a]);
  assert.deepEqual(shortestPath(graph, c, b), [c, a, b]);
  assert.equal(shortestPath(graph, a, isolated), null);
  assert.equal(shortestPath(graph, 'missing', a), null);
});
test('search is case-insensitive and bounds rendering while preserving match totals', () => {
  const graph = create(fixture());
  assert.deepEqual(graph.search('SAMPLE', 2), { total: 4, methods: [a, b] });
  assert.deepEqual(graph.search('ONCREATE'), { total: 1, methods: [a] });
  assert.deepEqual(graph.search('not found'), { total: 0, methods: [] });
});
test('invalid schema and dangling endpoints are rejected before rendering', () => {
  assert.throws(() => create({ ...fixture(), schemaVersion: 2 }), /Unsupported/);
  assert.throws(() => create({ ...fixture(), edges: [edge(a, 'absent')] }), /unknown edge endpoint/);
  assert.throws(() => create({ ...fixture(), methods: [a, null] }), /invalid method/);
});
test('method labels preserve original hostile strings as plain data', () => {
  const signature = '<demo.<img onerror=alert(1)>: void run()>';
  assert.equal(describe(signature).signature, signature);
  assert.equal(describe(a).method, 'onCreate(android.os.Bundle)');
  assert.equal(describe(a).shortClass, 'Main');
  assert.equal(describe('unusual method').method, 'unusual method');
});
