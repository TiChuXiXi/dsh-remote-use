package com.dsh.remote.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal pure-Java JSON value model, parser and serializer.
 * <p>Values are plain JDK types: objects = {@code LinkedHashMap}, arrays =
 * {@code ArrayList}, strings = {@code String}, numbers = {@code Long} (integral
 * literals) or {@code Double}, booleans = {@code Boolean}, null = {@code null}.
 * No Android, org.json or third-party APIs are used, so this class also runs on
 * a plain desktop JDK.
 */
public final class Json {

    /** Parse/serialize failure. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String m) {
            super(m);
        }

        public JsonException(String m, Throwable c) {
            super(m, c);
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /** Parse a complete JSON document. Trailing whitespace allowed, trailing garbage is an error. */
    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("input is null");
        }
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw p.err("trailing garbage");
        }
        return v;
    }

    /** Compact serialization. Map keys keep insertion order. */
    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, -1);
        return sb.toString();
    }

    /** Indented serialization, 2 spaces per level. */
    public static String pretty(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v, 0);
        return sb.toString();
    }

    /** New empty ordered object. */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<String, Object>();
    }

    /** New empty array. */
    public static List<Object> arr() {
        return new ArrayList<Object>();
    }

    /**
     * Path walk; returns null when missing. Path elements are object keys, or array
     * indices when the current node is a {@code List} and the element parses as an int.
     */
    public static Object get(Object node, String... path) {
        Object cur = node;
        if (path != null) {
            for (String step : path) {
                if (cur == null || step == null) {
                    return null;
                }
                if (cur instanceof List) {
                    int idx;
                    try {
                        idx = Integer.parseInt(step);
                    } catch (NumberFormatException nfe) {
                        return null;
                    }
                    List<?> l = (List<?>) cur;
                    if (idx < 0 || idx >= l.size()) {
                        return null;
                    }
                    cur = l.get(idx);
                } else if (cur instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) cur;
                    if (!m.containsKey(step)) {
                        return null;
                    }
                    cur = m.get(step);
                } else {
                    return null;
                }
            }
        }
        return cur;
    }

    /** Typed path lookup; null when absent or not a String. */
    public static String str(Object node, String... path) {
        Object v = get(node, path);
        return (v instanceof String) ? (String) v : null;
    }

    /** Typed path lookup; {@code def} when absent or not a number. */
    public static long num(Object node, long def, String... path) {
        Object v = get(node, path);
        return (v instanceof Number) ? ((Number) v).longValue() : def;
    }

    /** Typed path lookup; {@code def} when absent or not a boolean. */
    public static boolean bool(Object node, boolean def, String... path) {
        Object v = get(node, path);
        return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : def;
    }

    /** Typed path lookup; empty map when absent or not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object node, String... path) {
        Object v = get(node, path);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** Typed path lookup; empty list when absent or not an array. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object node, String... path) {
        Object v = get(node, path);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return new ArrayList<Object>();
    }

    // ------------------------------------------------------------------ serialization

    private static void write(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Double) {
            writeDouble(sb, ((Double) v).doubleValue());
        } else if (v instanceof Float) {
            writeDouble(sb, ((Float) v).doubleValue());
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            sb.append(v.toString());
        } else if (v instanceof Number) {
            // BigInteger / BigDecimal: toString() already yields a valid JSON number literal.
            sb.append(v.toString());
        } else if (v instanceof Map) {
            writeMap(sb, (Map<?, ?>) v, depth);
        } else if (v instanceof List) {
            writeList(sb, (List<?>) v, depth);
        } else {
            throw new JsonException("cannot serialize value of type " + v.getClass().getName());
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> m, int depth) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        boolean pretty = depth >= 0;
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            if (pretty) {
                sb.append('\n');
                indent(sb, depth + 1);
            }
            Object k = e.getKey();
            writeString(sb, (k == null) ? "null" : String.valueOf(k));
            sb.append(pretty ? ": " : ":");
            write(sb, e.getValue(), pretty ? depth + 1 : depth);
            first = false;
        }
        if (pretty) {
            sb.append('\n');
            indent(sb, depth);
        }
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> l, int depth) {
        if (l.isEmpty()) {
            sb.append("[]");
            return;
        }
        boolean pretty = depth >= 0;
        sb.append('[');
        boolean first = true;
        for (Object v : l) {
            if (!first) {
                sb.append(',');
            }
            if (pretty) {
                sb.append('\n');
                indent(sb, depth + 1);
            }
            write(sb, v, pretty ? depth + 1 : depth);
            first = false;
        }
        if (pretty) {
            sb.append('\n');
            indent(sb, depth);
        }
        sb.append(']');
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new JsonException("cannot serialize non-finite double: " + d);
        }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) {
            sb.append(Long.toString((long) d));
        } else {
            sb.append(Double.toString(d));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00");
                        sb.append(HEX[(c >> 4) & 0x0F]);
                        sb.append(HEX[c & 0x0F]);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int j = 0; j < level; j++) {
            sb.append("  ");
        }
    }

    // ------------------------------------------------------------------ parsing

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static final class Parser {
        private final String s;
        private final int n;
        private int i;

        Parser(String s) {
            this.s = s;
            this.n = s.length();
            this.i = 0;
        }

        boolean atEnd() {
            return i >= n;
        }

        JsonException err(String msg) {
            return new JsonException(msg + " at position " + i);
        }

        void skipWs() {
            while (i < n) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (atEnd()) {
                throw err("unexpected end of input");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                case 'f':
                case 'n':
                    return parseLiteral();
                default:
                    if (c == '-' || isDigit(c)) {
                        return parseNumber();
                    }
                    throw err("unexpected character '" + c + "'");
            }
        }

        Map<String, Object> parseObject() {
            if (s.charAt(i) != '{') {
                throw err("expected '{'");
            }
            i++;
            LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
            skipWs();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (atEnd()) {
                    throw err("unterminated object");
                }
                if (s.charAt(i) != '"') {
                    throw err("expected string key");
                }
                String key = parseString();
                skipWs();
                if (atEnd() || s.charAt(i) != ':') {
                    throw err("expected ':' after key");
                }
                i++;
                m.put(key, parseValue());
                skipWs();
                if (atEnd()) {
                    throw err("unterminated object");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw err("expected ',' or '}'");
            }
        }

        List<Object> parseArray() {
            if (s.charAt(i) != '[') {
                throw err("expected '['");
            }
            i++;
            ArrayList<Object> a = new ArrayList<Object>();
            skipWs();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                a.add(parseValue());
                skipWs();
                if (atEnd()) {
                    throw err("unterminated array");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return a;
                }
                throw err("expected ',' or ']'");
            }
        }

        String parseString() {
            if (s.charAt(i) != '"') {
                throw err("expected '\"'");
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(i);
                if (c == '"') {
                    i++;
                    return sb.toString();
                }
                if (c == '\\') {
                    i++;
                    if (atEnd()) {
                        throw err("unterminated escape");
                    }
                    char e = s.charAt(i);
                    i++;
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (i + 4 > n) {
                                throw err("incomplete \\u escape");
                            }
                            int v = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = hexVal(s.charAt(i + k));
                                if (d < 0) {
                                    throw err("invalid \\u escape");
                                }
                                v = (v << 4) | d;
                            }
                            i += 4;
                            sb.append((char) v);
                            break;
                        default:
                            throw err("invalid escape '\\" + e + "'");
                    }
                    continue;
                }
                if (c < 0x20) {
                    throw err("control character in string");
                }
                sb.append(c);
                i++;
            }
        }

        Object parseNumber() {
            int start = i;
            if (s.charAt(i) == '-') {
                i++;
            }
            if (atEnd() || !isDigit(s.charAt(i))) {
                throw err("invalid number");
            }
            while (i < n && isDigit(s.charAt(i))) {
                i++;
            }
            boolean fp = false;
            if (i < n && s.charAt(i) == '.') {
                fp = true;
                i++;
                if (atEnd() || !isDigit(s.charAt(i))) {
                    throw err("invalid number");
                }
                while (i < n && isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                fp = true;
                i++;
                if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                if (atEnd() || !isDigit(s.charAt(i))) {
                    throw err("invalid number");
                }
                while (i < n && isDigit(s.charAt(i))) {
                    i++;
                }
            }
            String lit = s.substring(start, i);
            if (!fp) {
                try {
                    return Long.valueOf(lit);
                } catch (NumberFormatException nfe) {
                    // literal exceeds long range; fall back to double below
                }
            }
            double d = Double.parseDouble(lit);
            if (Double.isInfinite(d) || Double.isNaN(d)) {
                throw err("number out of range");
            }
            return Double.valueOf(d);
        }

        Object parseLiteral() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw err("invalid literal");
        }
    }
}