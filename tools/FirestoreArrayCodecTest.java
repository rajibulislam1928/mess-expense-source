package com.messexpense.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host-side regression test for Firestore's prohibition on nested arrays. */
public final class FirestoreArrayCodecTest {
    public static void main(String[] args) {
        List<Object> day = Arrays.asList(1, 0);
        List<Object> week = Arrays.asList(day, Arrays.asList(0, 2), Arrays.asList(1, 1),
                Arrays.asList(0, 0), Arrays.asList(1, 0), Arrays.asList(0, 1), Arrays.asList(1, 1));
        List<Object> meals = Arrays.asList(week, week);
        Map<String, Object> state = new HashMap<>();
        state.put("startDate", "2026-10-26");
        state.put("members", Arrays.asList("Rajibul", "Naimul"));
        state.put("meals", meals);
        state.put("spend", Arrays.asList(Map.of("morning", 0, "night", 0, "extra", 0)));
        Map<String, Object> event = new HashMap<>();
        event.put("id", "event_1"); event.put("time", "2026-10-29T10:00:00Z"); event.put("device", "phone");
        event.put("patch", Arrays.asList(Map.of("path", "", "value", state)));

        // Match FirebaseBridge: the top-level patch remains a legal Firestore list;
        // arrays inside its operation map are recursively represented as marker maps.
        List<Object> safePatch = new ArrayList<>();
        safePatch.add(Map.of("path", "", "value", FirestoreArrayCodec.encode(state)));
        Map<String, Object> payload = new HashMap<>(event);
        payload.put("patch", safePatch);
        assertNoNestedLists(payload, false);
        assertNoReservedFirestoreFieldNames(payload);
        Map<?, ?> encodedOp = (Map<?, ?>) ((List<?>) payload.get("patch")).get(0);
        Object decodedState = FirestoreArrayCodec.decode(encodedOp.get("value"));
        if (!state.equals(decodedState)) throw new AssertionError("Round-trip changed nested tracker state\n" + decodedState);

        // Preserve ordinary map/list values and handle empty arrays without confusing markers.
        Object empty = FirestoreArrayCodec.decode(FirestoreArrayCodec.encode(new ArrayList<>()));
        if (!(empty instanceof List<?>) || !((List<?>) empty).isEmpty()) throw new AssertionError("Empty array round-trip failed");
        System.out.println("PASS: nested Firestore event arrays encode without nested lists and round-trip exactly");
    }

    private static void assertNoReservedFirestoreFieldNames(Object value) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.startsWith("__") || key.endsWith("__"))
                    throw new AssertionError("Firestore-reserved field name: " + key);
                assertNoReservedFirestoreFieldNames(entry.getValue());
            }
        } else if (value instanceof List<?>) {
            for (Object child : (List<?>) value) assertNoReservedFirestoreFieldNames(child);
        }
    }

    private static void assertNoNestedLists(Object value, boolean insideList) {
        if (value instanceof List<?>) {
            if (insideList) throw new AssertionError("Nested list remains in encoded Firestore value");
            for (Object child : (List<?>) value) assertNoNestedLists(child, true);
        } else if (value instanceof Map<?, ?>) {
            for (Object child : ((Map<?, ?>) value).values()) assertNoNestedLists(child, false);
        }
    }
}
