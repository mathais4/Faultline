package io.faultline.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer and parser. Faultline avoids third-party dependencies on purpose so the whole
 * system builds with nothing but a JDK; this is the one piece of "framework" that costs.
 *
 * Parsed values: Map (LinkedHashMap), List, String, Long, Double, Boolean, null.
 */
public final class Json {
    private Json() {}

    /** Anything that can describe itself as a JSON object. */
    public interface Jsonable {
        Map<String, Object> toJson();
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> quote(sb, s);
            case Boolean b -> sb.append(b.booleanValue());
            case Double d -> number(sb, d);
            case Float f -> number(sb, f.doubleValue());
            case Number n -> sb.append(n.toString());
            case Enum<?> e -> quote(sb, e.name());
            case Jsonable j -> write(sb, j.toJson());
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object o : c) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            }
            case Object[] a -> write(sb, java.util.Arrays.asList(a));
            case double[] a -> {
                sb.append('[');
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(',');
                    number(sb, a[i]);
                }
                sb.append(']');
            }
            default -> quote(sb, v.toString());
        }
    }

    private static void number(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
            return;
        }
        String s = Double.toString(d);
        if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0) s = BigDecimal.valueOf(d).toPlainString();
        sb.append(s);
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("expected a JSON object");
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON: " + msg + " at offset " + pos);
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            if (pos >= s.length()) throw error("unexpected end");
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    return number();
            }
        }

        Object literal(String word, Object val) {
            if (!s.startsWith(word, pos)) throw error("bad literal");
            pos += word.length();
            return val;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected string key");
                String k = string();
                skipWs();
                if (peek() != ':') throw error("expected ':'");
                pos++;
                skipWs();
                m.put(k, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') return m;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') return l;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) throw error("bad escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }

        Object number() {
            int start = pos;
            boolean isDouble = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') isDouble = true;
                else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) break;
                pos++;
            }
            if (start == pos) throw error("unexpected character '" + s.charAt(pos) + "'");
            String t = s.substring(start, pos);
            try {
                return isDouble ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
            } catch (NumberFormatException ex) {
                throw error("bad number " + t);
            }
        }

        char peek() {
            if (pos >= s.length()) throw error("unexpected end");
            return s.charAt(pos);
        }
    }

    // ------------------------------------------------------------------ typed accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map<?, ?> mm ? (Map<String, Object>) mm : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String str(Map<String, Object> m, String key, String dflt) {
        Object v = m == null ? null : m.get(key);
        return v == null ? dflt : v.toString();
    }

    public static long lng(Map<String, Object> m, String key, long dflt) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.longValue() : dflt;
    }

    public static int integer(Map<String, Object> m, String key, int dflt) {
        return (int) lng(m, key, dflt);
    }

    public static double dbl(Map<String, Object> m, String key, double dflt) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number n ? n.doubleValue() : dflt;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dflt) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean b ? b : dflt;
    }
}
