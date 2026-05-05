package org.kgatilin.gephi.mcp;

import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

final class Json {
    private Json() {
    }

    static String quote(Object value) {
        if (value == null) {
            return "null";
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            return object(map);
        }
        if (value instanceof Iterable<?> iterable) {
            return array(iterable);
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(value(Array.get(value, i)));
            }
            return sb.append(']').toString();
        }
        return quote(value);
    }

    static String object(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> entry = it.next();
            sb.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        return sb.append('}').toString();
    }

    static String array(Iterable<?> values) {
        StringBuilder sb = new StringBuilder("[");
        Iterator<?> it = values.iterator();
        while (it.hasNext()) {
            sb.append(value(it.next()));
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        return sb.append(']').toString();
    }

    static String error(String code, String message) {
        return "{\"error\":" + quote(code) + ",\"message\":" + quote(message) + "}";
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
