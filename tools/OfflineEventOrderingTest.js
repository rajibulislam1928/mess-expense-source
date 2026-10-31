// Host-side regression checks for the production event parsing/order/conflict helpers.
// These functions are embedded in index.html, so evaluate that exact source rather than a copy.
const fs = require('fs');
const vm = require('vm');
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const pathsStart = html.indexOf('function pathsOverlap(a, b) {');
const start = html.indexOf('function safePatchOperation(op) {');
const end = html.indexOf('async function configureBackgroundSync(events) {', start);
if (pathsStart < 0 || start < 0 || end < 0) throw new Error('Could not locate production event helpers');
const context = {};
vm.createContext(context);
vm.runInContext(html.slice(pathsStart, start) + html.slice(start, end), context);
const { rowToEvent, compareEvents, hasAmbiguousRemoteOverwrite } = context;
const op = (path, value = 1) => ({ path, value });
const event = (id, serverTime, time, path) => ({
  id, serverTime, time, device: id, patch: [op(path)]
});

const older = event('older', 1000, '2026-10-31T12:00:02Z', 'spend.0.morning');
const newer = event('newer', 2000, '2026-10-31T12:00:01Z', 'spend.0.morning');
if (compareEvents(older, newer) >= 0) throw new Error('Server timestamp must win over client clock ordering');
if (hasAmbiguousRemoteOverwrite([older, newer])) throw new Error('Distinct server timestamps are ordered, not ambiguous');
const missing = event('missing', null, '2026-10-31T12:00:03Z', 'spend.0.morning');
if (!hasAmbiguousRemoteOverwrite([newer, missing])) throw new Error('Missing server timestamp must preserve conflict choice');
const tied = event('tied', 2000, '2026-10-31T12:00:04Z', 'spend.0.morning');
if (!hasAmbiguousRemoteOverwrite([newer, tied])) throw new Error('Tied server timestamps must preserve conflict choice');
if (hasAmbiguousRemoteOverwrite([older, event('other', null, '2026-10-31T12:00:03Z', 'meals.0.0.0')]))
  throw new Error('Disjoint fields do not need an ordering conflict');
const row = rowToEvent(['newer', newer.time, newer.device, JSON.stringify(newer.patch), 2000]);
if (!row || row.serverTime !== 2000) throw new Error('FirebaseBridge serverTime column must parse');
const legacyRow = rowToEvent(['legacy', newer.time, 'old-phone', JSON.stringify([op('spend.0.morning')])]);
if (!legacyRow || legacyRow.serverTime !== null) throw new Error('Legacy event without serverTime must remain readable');
console.log('PASS: production event helpers honor serverTime, read legacy events, and retain conflicts for uncertain ordering');
