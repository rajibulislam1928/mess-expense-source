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
const { rowToEvent, compareEvents, hasAmbiguousRemoteOverwrite, conflictCopiesMatch, refreshPendingEvent, captureActiveTrackedDraft, capturePendingTrackedDrafts, applyActiveTrackedDraft } = context;
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

// A Firebase refresh must preserve the live expense field being edited, even if the
// snapshot was assembled before that latest keystroke reached the sync diff.
const expenseDraft = captureActiveTrackedDraft(validState, { type: 'number', dataset: { kind: 'morning', i: '0' }, value: '325.50' });
const refreshedState = JSON.parse(JSON.stringify(validState));
if (!applyActiveTrackedDraft(refreshedState, expenseDraft) || refreshedState.spend[0].morning !== 325.5)
  throw new Error('A remote refresh must retain the focused, unsaved expense draft');
const mealInput = { type: 'number', dataset: { kind: 'dinner', i: '0', d: '3' }, value: '2' };
const mealDraft = captureActiveTrackedDraft(validState, mealInput);
const refreshedMeals = JSON.parse(JSON.stringify(validState));
if (!applyActiveTrackedDraft(refreshedMeals, mealDraft) || refreshedMeals.meals[0][3][1] !== 2)
  throw new Error('A remote refresh must retain the focused, unsaved meal draft');
const reorderedState = JSON.parse(JSON.stringify(validState));
reorderedState.members = ['B', 'A'];
reorderedState.meals = [JSON.parse(JSON.stringify(validState.meals[0])), JSON.parse(JSON.stringify(validState.meals[0]))];
reorderedState.spend = [{ morning: 0, night: 0, extra: 0 }, { morning: 0, night: 0, extra: 0 }];
if (!applyActiveTrackedDraft(reorderedState, expenseDraft) || reorderedState.spend[1].morning !== 325.5)
  throw new Error('A remote member reorder must keep the draft attached to the same member');
const reorderedMeals = JSON.parse(JSON.stringify(reorderedState));
if (!applyActiveTrackedDraft(reorderedMeals, mealDraft) || reorderedMeals.meals[1][3][1] !== 2)
  throw new Error('A remote member reorder must preserve the focused meal draft too');
if (captureActiveTrackedDraft(validState, { type: 'text', dataset: { kind: 'name', i: '0' }, value: 'A' }) !== null)
  throw new Error('Only focused meal/expense number fields are draft-preserved');
// A same-roster refresh must update cells in place and leave the focused editor node/value intact.
const liveMealInput = { type: 'number', dataset: { kind: 'dinner', i: '0', d: '3' }, value: '7' };
const otherMealInput = { type: 'number', dataset: { kind: 'lunch', i: '0', d: '3' }, value: '0' };
const otherExpenseInput = { type: 'number', dataset: { kind: 'morning', i: '0' }, value: '0' };
context.document = { querySelectorAll: () => [liveMealInput, otherMealInput, otherExpenseInput], activeElement: liveMealInput };
const remoteWithChanges = JSON.parse(JSON.stringify(validState));
remoteWithChanges.meals[0][3][0] = 4;
remoteWithChanges.spend[0].morning = 50;
if (!applyActiveTrackedDraft(remoteWithChanges, mealDraft)) throw new Error('Live meal draft should apply to refreshed state');
context.updateTrackedInputsFromState(remoteWithChanges, mealDraft);
if (liveMealInput.value !== '7') throw new Error('In-place refresh must not replace or clear the active meal editor value');
if (otherMealInput.value !== 4 || otherExpenseInput.value !== 50)
  throw new Error('In-place refresh must still update other meal and expense cells');

// Exercise the actual applyState branch: same-roster sync must not rebuild the table nodes.
const applyStart = html.indexOf('function applyState(next, message) {');
if (applyStart < 0) throw new Error('Could not locate production applyState');
let depth = 0, applyEnd = -1, opened = false;
for (let i = html.indexOf('{', applyStart); i < html.length; i++) {
  if (html[i] === '{') { depth++; opened = true; }
  else if (html[i] === '}' && opened && --depth === 0) { applyEnd = i + 1; break; }
}
if (applyEnd < 0) throw new Error('Could not extract production applyState');
vm.runInContext(html.slice(applyStart, applyEnd), context);
context.state = JSON.parse(JSON.stringify(validState));
context.validSharedState = () => true;
context.num = value => Number(value) || 0;
context.localStorage = { setItem: () => {} };
context.STORAGE_KEY = 'test';
context.renderDates = () => {};
context.refreshMeals = () => {};
context.refreshExpenses = () => {};
context.renderMembers = () => {};
context.renderSummary = () => {};
context.renderMeals = () => { throw new Error('Same-roster sync replaced the meal table'); };
context.renderExpenses = () => { throw new Error('Same-roster sync replaced the expense table'); };
context.queueFirebaseSave = () => {};
context.setAuthStatus = () => {};
context.applyingRemoteState = false;
context.activeTrackedDraft = null;
context.document.activeElement = liveMealInput;
liveMealInput.value = '7';
context.applyState(remoteWithChanges, null);
if (liveMealInput.value !== '7' || context.state.meals[0][3][1] !== 7)
  throw new Error('Production applyState must preserve the actively edited meal value during sync');
if (otherMealInput.value !== 4 || otherExpenseInput.value !== 50)
  throw new Error('Production applyState must apply remote values to other table cells');
const liveExpenseInput = { type: 'number', dataset: { kind: 'morning', i: '0' }, value: '325.5' };
context.state = JSON.parse(JSON.stringify(validState));
context.activeTrackedDraft = null;
context.document.activeElement = liveExpenseInput;
context.document.querySelectorAll = () => [liveExpenseInput, otherMealInput];
const remoteExpense = JSON.parse(JSON.stringify(validState));
remoteExpense.spend[0].morning = 12;
context.applyState(remoteExpense, null);
if (liveExpenseInput.value !== '325.5' || context.state.spend[0].morning !== 325.5)
  throw new Error('Production applyState must preserve the actively edited expense value during sync');

// If Android/WebView reports body focus during the callback, a typed but not-yet-saved
// DOM value must still win over a remote refresh for either tracked input kind.
const inactiveMealInput = { type: 'number', dataset: { kind: 'dinner', i: '0', d: '3' }, value: '6' };
const inactiveExpenseInput = { type: 'number', dataset: { kind: 'morning', i: '0' }, value: '91' };
context.document.activeElement = { type: 'body' };
context.document.querySelectorAll = () => [inactiveMealInput, inactiveExpenseInput];
const pendingDomDrafts = capturePendingTrackedDrafts(context.state);
if (pendingDomDrafts.length !== 2) throw new Error('DOM/state-mismatched meal and expense drafts should survive even without focused input');
const remoteForUnfocused = JSON.parse(JSON.stringify(validState));
remoteForUnfocused.meals[0][3][1] = 2;
remoteForUnfocused.spend[0].morning = 12;
context.state = JSON.parse(JSON.stringify(validState));
context.activeTrackedDraft = null;
context.applyState(remoteForUnfocused, null);
if (context.state.meals[0][3][1] !== 6 || context.state.spend[0].morning !== 91)
  throw new Error('Production applyState must preserve changed DOM drafts when WebView focus is lost');
if (inactiveMealInput.value !== '6' || inactiveExpenseInput.value !== '91')
  throw new Error('In-place remote refresh must not replace changed unfocused DOM values');
// An active but unchanged field is not a draft and should receive another member's synced update.
const unchangedInput = { type: 'number', dataset: { kind: 'morning', i: '0' }, value: '0' };
context.document.activeElement = unchangedInput;
context.document.querySelectorAll = () => [unchangedInput];
context.state = JSON.parse(JSON.stringify(validState));
const remoteUnchangedField = JSON.parse(JSON.stringify(validState));
remoteUnchangedField.spend[0].morning = 30;
context.applyState(remoteUnchangedField, null);
if (context.state.spend[0].morning !== 30 || unchangedInput.value !== 30)
  throw new Error('An unchanged focused input must accept the remote value instead of becoming a stale draft');

const row = rowToEvent(['newer', newer.time, newer.device, JSON.stringify(newer.patch), 2000]);
if (!row || row.serverTime !== 2000) throw new Error('FirebaseBridge serverTime column must parse');
const legacyRow = rowToEvent(['legacy', newer.time, 'old-phone', JSON.stringify([op('spend.0.morning')])]);
if (!legacyRow || legacyRow.serverTime !== null) throw new Error('Legacy event without serverTime must remain readable');
console.log('PASS: sync conflicts, stale retries, and remote refreshes preserve focused or pending meal and expense drafts');
