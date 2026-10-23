// Optional frontend regression runner: node src/test/resources/graph-html-test.cjs
// Uses only Node's standard library; Maven tests do not require Node to be installed.
'use strict';
const assert = require('node:assert/strict');
const viewer = require('../../main/resources/com/jordansamhi/androspecter/graph/viewer.js');
const payload = (methods, edges, kinds = ['STATIC', 'VIRTUAL']) => ({ viewerVersion: 1, methods, edges, kinds });

// Parallel occurrences, distinct kinds, reciprocal calls, cycles, and self calls.
const graph = payload(['A', 'B', 'C', 'isolated'], [
  [0, 1, 0, 'first', 1], [0, 1, 0, 'second', 2], [0, 1, 1, null, -1],
  [1, 0, 0, null, -1], [1, 2, 0, null, -1], [2, 0, 0, null, -1], [0, 0, 0, 'self', 0]
]);
const index = viewer.buildIndex(graph);
assert.equal(index.relationships.length, 6);
assert.equal(index.nodes.get('A').outgoingCount, 4);
assert.equal(index.nodes.get('A').incomingCount, 3);
assert.equal(index.relationships[0].edges.length, 2);
assert.equal(index.relationships[0].edges[1], graph.edges[1]);
const both = viewer.selectNeighborhood(index, 'A');
assert.deepEqual(new Set(both.methodIds), new Set(['A', 'B', 'C']));
assert.equal(both.totalRelationships, 5);
assert.equal(both.shownOccurrences, 6);
assert.equal(both.totalOccurrences, 6);
assert.equal(both.truncated, false);
assert.ok(both.relationships.every(edge => edge.source === 'A' || edge.target === 'A'));
const callers = viewer.selectNeighborhood(index, 'A', 'callers');
assert.ok(callers.relationships.every(edge => edge.target === 'A'));
assert.equal(callers.totalOccurrences, 3);
const callees = viewer.selectNeighborhood(index, 'A', 'callees');
assert.deepEqual(callees.methodIds, ['A', 'B']);
assert.ok(callees.relationships.every(edge => edge.source === 'A'));
assert.equal(callees.totalOccurrences, 4);
assert.equal(viewer.selectNeighborhood(index, 'A', 'both', 1).totalMethods, 3);
assert.equal(viewer.selectNeighborhood(index, 'A', 'both', 1).relationships.length, 1);
assert.equal(viewer.selectNeighborhood(index, 'A', 'both', 60, 1).truncated, true);
assert.deepEqual(viewer.selectNeighborhood(index, 'isolated').methodIds, ['isolated']);
assert.deepEqual(viewer.selectNeighborhood(index, 'missing').methodIds, []);
assert.equal(viewer.selectNeighborhood(viewer.buildIndex(payload([], [])), null).totalMethods, 0);
assert.throws(() => viewer.selectNeighborhood(index, 'A', 'invalid'));
assert.throws(() => viewer.selectNeighborhood(index, 'A', 'both', 0));

// Opaque names are Map keys and labels, never object properties or executable code.
const unusual = viewer.buildIndex(payload(['__proto__', 'constructor', '</script><svg onload=attack()>', ''],
  [[0, 1, 0, '\0\n', 0], [1, 2, 0, null, -1], [3, 0, 0, null, -1]], ['__proto__']));
assert.equal(unusual.nodes.size, 4);
assert.equal(unusual.relationships[0].kind, '__proto__');
assert.deepEqual(viewer.searchMethods(unusual, 'SCRIPT').methods.map(node => node.id), ['</script><svg onload=attack()>']);
assert.equal(viewer.searchMethods(unusual, '  ').total, 4);
assert.deepEqual(viewer.searchMethods(unusual, '', 2, 1).methods.map(node => node.id), ['</script><svg onload=attack()>']);
assert.equal(viewer.searchMethods(unusual, '', 20).methods.length, 0);
assert.throws(() => viewer.buildIndex({ viewerVersion: 99 }));
assert.throws(() => viewer.buildIndex(payload(['A'], [[0, 1, 0, null, -1]])));
assert.throws(() => viewer.buildIndex(payload(['A'], [[0, 0, 9, null, -1]])));

// A large hub retains every edge, balances incoming/outgoing neighbors, reports
// exact omitted counts, and creates only bounded node/relationship render inputs.
const size = 30000;
const methods = ['hub'];
const edges = [];
for (let position = 1; position <= size; position++) {
  methods.push('method-' + position);
  edges.push(position % 2 ? [0, position, 0, null, -1] : [position, 0, 0, null, -1]);
}
const large = viewer.buildIndex(payload(methods, edges));
const view = viewer.selectNeighborhood(large, 'hub');
assert.equal(large.graph.edges.length, size);
assert.equal(view.methodIds.length, viewer.MAX_NODES);
assert.equal(view.totalMethods, size + 1);
assert.equal(view.totalRelationships, size);
assert.equal(view.totalOccurrences, size);
assert.equal(view.shownOccurrences, viewer.MAX_NODES - 1);
assert.equal(view.truncated, true);
assert.ok(view.methodIds.some(id => view.levels.get(id) === -1));
assert.ok(view.methodIds.some(id => view.levels.get(id) === 1));
assert.ok(view.relationships.length <= viewer.MAX_RELATIONSHIPS);
assert.equal(viewer.searchMethods(large, 'method-30000').methods[0].id, 'method-30000');
assert.deepEqual(viewer.selectNeighborhood(large, 'hub').methodIds, view.methodIds);
console.log('Graph HTML frontend checks passed: direction, multiplicity, isolated nodes, opaque strings, pagination, and 30,001-node bounds.');
