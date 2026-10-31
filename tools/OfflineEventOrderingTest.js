// Host-side regression checks for the production event parsing/order/conflict helpers.
// These functions are embedded in index.html, so evaluate that exact source rather than a copy.
const fs = require('fs');
const vm = require('vm');
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const helpersStart = html.indexOf('function clone(value) {');
const start = html.indexOf('function safePatchOperation(op) {');
const end = html.indexOf('async function configureBackgroundSync(events) {', start);
if (helpersStart < 0 || start < 0 || end < 0) throw new Error('Could not locate production sync helpers');
const context = {};
vm.createContext(context);
vm.runInContext(html.slice(helpersStart, start) + html.slice(start, end), context);
const { rowToEvent, compareEvents, hasAmbiguousRemoteOverwrite, conflictCopiesMatch, refreshPendingEvent } = context;
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
if (!hasAmbiguousRemoteOverwrite([older, missing], new Set(['older']))) throw new Error('A new legacy event overlapping the baseline must preserve conflict choice');
if (hasAmbiguousRemoteOverwrite([older, missing], new Set(['older', 'missing']))) throw new Error('Old ambiguous events already in the baseline must not create a recurring conflict');
const tied = event('tied', 2000, '2026-10-31T12:00:04Z', 'spend.0.morning');
if (!hasAmbiguousRemoteOverwrite([newer, tied])) throw new Error('Tied server timestamps must preserve conflict choice');
if (hasAmbiguousRemoteOverwrite([older, event('other', null, '2026-10-31T12:00:03Z', 'meals.0.0.0')]))
  throw new Error('Disjoint fields do not need an ordering conflict');
const validState = { startDate: '2026-09-14', members: ['A'], meals: [[[0, 0], [0, 0], [0, 0], [0, 0], [0, 0], [0, 0], [0, 0]]], spend: [{ morning: 0, night: 0, extra: 0 }] };
if (!conflictCopiesMatch(validState, JSON.parse(JSON.stringify(validState)))) throw new Error('Identical valid local/shared copies should resolve the conflict');
const changedState = JSON.parse(JSON.stringify(validState)); changedState.spend[0].morning = 1;
if (conflictCopiesMatch(validState, changedState)) throw new Error('Different copies must keep the conflict choice visible');
const stalePending = { id: 'retry', time: '2026-10-31T12:00:00Z', device: 'phone', patch: [op('spend.0.morning', 100)] };
const latestLocal = JSON.parse(JSON.stringify(validState));
latestLocal.spend[0].morning = 250;
const refreshedPending = refreshPendingEvent(stalePending, [op('spend.0.morning', 250)], latestLocal);
if (!refreshedPending || refreshedPending.id !== stalePending.id || refreshedPending.patch[0].value !== 250)
  throw new Error('A pending retry must carry the latest local edit, not the older queued amount');
const revertedPending = refreshPendingEvent(stalePending, [], validState);
if (!revertedPending || revertedPending.patch[0].value !== 0)
  throw new Error('A pending retry must preserve a local reversion to the shared value');
const row = rowToEvent(['newer', newer.time, newer.device, JSON.stringify(newer.patch), 2000]);
if (!row || row.serverTime !== 2000) throw new Error('FirebaseBridge serverTime column must parse');
const legacyRow = rowToEvent(['legacy', newer.time, 'old-phone', JSON.stringify([op('spend.0.morning')])]);
if (!legacyRow || legacyRow.serverTime !== null) throw new Error('Legacy event without serverTime must remain readable');
console.log('PASS: sync conflict checks and stale pending retries preserve the latest local values');
