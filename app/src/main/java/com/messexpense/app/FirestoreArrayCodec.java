package com.messexpense.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reversible Firestore-safe encoding for nested arrays (Firestore forbids arrays inside arrays). */
final class FirestoreArrayCodec {
    private static final String MARKER = "mess_expense_array_v1";
    private FirestoreArrayCodec() {}

    static Object encode(Object value) {
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            Map<String, Object> items = new HashMap<>();
            for (int i = 0; i < list.size(); i++) items.put(Integer.toString(i), encode(list.get(i)));
            Map<String, Object> descriptor = new HashMap<>();
            descriptor.put("length", list.size());
            descriptor.put("items", items);
            Map<String, Object> encoded = new HashMap<>();
            encoded.put(MARKER, descriptor);
            return encoded;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), encode(entry.getValue()));
            }
            return result;
        }
        return value;
    }

    static Object decode(Object value) {
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(decode(item));
            return result;
        }
        if (!(value instanceof Map<?, ?>)) return value;
        Map<?, ?> map = (Map<?, ?>) value;
        Object descriptorValue = map.size() == 1 ? map.get(MARKER) : null;
        if (descriptorValue instanceof Map<?, ?>) {
            Map<?, ?> descriptor = (Map<?, ?>) descriptorValue;
            Object lengthValue = descriptor.get("length");
            Object itemsValue = descriptor.get("items");
            if (descriptor.size() == 2 && lengthValue instanceof Number && itemsValue instanceof Map<?, ?>) {
                int length = ((Number) lengthValue).intValue();
                Map<?, ?> items = (Map<?, ?>) itemsValue;
                if (length >= 0 && items.size() == length) {
                    List<Object> result = new ArrayList<>(length);
                    boolean valid = true;
                    for (int i = 0; i < length; i++) {
                        String key = Integer.toString(i);
                        if (!items.containsKey(key)) { valid = false; break; }
                        result.add(decode(items.get(key)));
                    }
                    if (valid) return result;
                }
            }
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) result.put(String.valueOf(entry.getKey()), decode(entry.getValue()));
        return result;
    }
}
