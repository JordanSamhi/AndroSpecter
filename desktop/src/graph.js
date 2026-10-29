'use strict';
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.GraphModel = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  /**
   * Splits a Soot method signature into readable labels without interpreting HTML.
   * @param {string} signature Full method signature.
   * @returns {{signature:string,className:string,shortClass:string,method:string}} Display labels.
   */
  function describe(signature) {
    const value = String(signature);
    const match = /^<([^:]+):\s*(.*?)\s+([^\s(]+\([^]*\))>$/.exec(value);
    const className = match ? match[1] : '';
    return { signature: value, className, shortClass: className.split('.').pop() || 'Method', method: match ? match[3] : value };
  }

  /**
   * Builds indexed callers and callees from detached GraphJson data, preserving edge occurrences.
   * @param {object} data A version-one GraphJson document validated by the CLI.
   * @returns {{methods:string[],edges:object[],incoming:Map,outgoing:Map,labels:Map,search:function,neighbors:function}} Read-only-by-convention graph index.
   * @throws {Error} When required graph fields or endpoint membership are invalid.
   */
  function create(data) {
    if (!data || data.schemaVersion !== 1 || !Array.isArray(data.methods) || !Array.isArray(data.edges)) throw new Error('Unsupported call graph. Import a GraphJson version 1 file.');
    const methods = [...new Set(data.methods)];
    if (methods.some(method => typeof method !== 'string')) throw new Error('The graph contains an invalid method.');
    const incoming = new Map(methods.map(method => [method, []]));
    const outgoing = new Map(methods.map(method => [method, []]));
    const labels = new Map(methods.map(method => [method, describe(method)]));
    for (const edge of data.edges) {
      if (!edge || !outgoing.has(edge.source) || !incoming.has(edge.target)) throw new Error('The graph contains an unknown edge endpoint.');
      outgoing.get(edge.source).push(edge);
      incoming.get(edge.target).push(edge);
    }
    return {
      methods, edges: data.edges, incoming, outgoing, labels,
      /** Finds methods by case-insensitive substring; returns total matches and a bounded list. */
      search(query, limit = 120) {
        const needle = String(query).trim().toLowerCase();
        const matches = methods.filter(method => method.toLowerCase().includes(needle));
        return { total: matches.length, methods: matches.slice(0, limit) };
      },
      /** Lists unique neighbors and all associated parallel edges in deterministic order. */
      neighbors(method, direction) {
        const grouped = new Map();
        const edges = (direction === 'incoming' ? incoming : outgoing).get(method) || [];
        for (const edge of edges) {
          const other = direction === 'incoming' ? edge.source : edge.target;
          if (!grouped.has(other)) grouped.set(other, []);
          grouped.get(other).push(edge);
        }
        return [...grouped].map(([signature, occurrences]) => ({ signature, edges: occurrences })).sort((a, b) => a.signature.localeCompare(b.signature));
      }
    };
  }

  /**
   * Finds the shortest directed call path using edge count, including a zero-edge self path.
   * This expresses static graph reachability, not proof of a feasible execution or taint flow.
   * @param {object} graph Index returned by create.
   * @param {string} source Existing source method.
   * @param {string} target Existing target method.
   * @returns {string[]|null} Inclusive path, or null when endpoints are unknown or unreachable.
   */
  function shortestPath(graph, source, target) {
    if (!graph.outgoing.has(source) || !graph.outgoing.has(target)) return null;
    const previous = new Map([[source, null]]);
    const queue = [source];
    for (let cursor = 0; cursor < queue.length; cursor++) {
      const current = queue[cursor];
      if (current === target) {
        const result = [];
        for (let next = target; next !== null; next = previous.get(next)) result.push(next);
        return result.reverse();
      }
      for (const edge of graph.outgoing.get(current)) {
        if (!previous.has(edge.target)) { previous.set(edge.target, current); queue.push(edge.target); }
      }
    }
    return null;
  }
  return Object.freeze({ create, describe, shortestPath });
});
