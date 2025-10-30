package com.modelcontextprotocol.sdk.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and writer that supports the subset of JSON used by the
 * demo server. It understands objects, arrays, strings, numbers, booleans and
 * null values. Numbers without a decimal point are returned as {@link Long},
 * otherwise {@link Double} is used.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String json) {
        return new Parser(json).parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("JSON is not an object");
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String s) {
            writeString(builder, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(String.valueOf(value));
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof List<?> list) {
            builder.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, element);
            }
            builder.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass());
        }
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {

        private final String json;
        private int index;

        Parser(String json) {
            this.json = json;
        }

        Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                throw error("Unexpected end of input");
            }
            char ch = json.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek('}')) {
                    index++;
                    break;
                }
                throw error("Expected ',' or '}' in object");
            }
            return object;
        }

        private List<Object> parseArray() {
            List<Object> array = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                Object value = parseValue();
                array.add(value);
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                if (peek(']')) {
                    index++;
                    break;
                }
                throw error("Expected ',' or ']' in array");
            }
            return array;
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < json.length()) {
                char ch = json.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (index >= json.length()) {
                        throw error("Unexpected end of input in escape sequence");
                    }
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> builder.append(parseUnicode());
                        default -> throw error("Unsupported escape sequence: \\" + escaped);
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw error("Unterminated string literal");
        }

        private char parseUnicode() {
            if (index + 4 > json.length()) {
                throw error("Invalid unicode escape");
            }
            int codePoint = 0;
            for (int i = 0; i < 4; i++) {
                char digit = json.charAt(index++);
                codePoint <<= 4;
                if (digit >= '0' && digit <= '9') {
                    codePoint += digit - '0';
                } else if (digit >= 'a' && digit <= 'f') {
                    codePoint += digit - 'a' + 10;
                } else if (digit >= 'A' && digit <= 'F') {
                    codePoint += digit - 'A' + 10;
                } else {
                    throw error("Invalid hex digit in unicode escape");
                }
            }
            return (char) codePoint;
        }

        private Object parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < json.length() && Character.isDigit(json.charAt(index))) {
                index++;
            }
            boolean isFractional = false;
            if (peek('.')) {
                isFractional = true;
                index++;
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                isFractional = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            String literal = json.substring(start, index);
            if (literal.isEmpty() || "-".equals(literal) || ".".equals(literal) || "-.".equals(literal)) {
                throw error("Invalid number literal");
            }
            try {
                if (isFractional) {
                    return Double.valueOf(literal);
                }
                return Long.valueOf(literal);
            } catch (NumberFormatException ex) {
                throw error("Invalid number literal");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (json.startsWith(literal, index)) {
                index += literal.length();
                return value;
            }
            throw error("Unexpected token");
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + index);
        }
    }
}
