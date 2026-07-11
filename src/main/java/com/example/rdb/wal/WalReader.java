package com.example.rdb.wal;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WalReader {

    private final Path walFilePath;

    public WalReader(Path walFilePath) {
        this.walFilePath = walFilePath;
    }

    public List<WalRecord> readAll() throws IOException {
        List<WalRecord> records = new ArrayList<>();
        if (!Files.exists(walFilePath)) {
            return records;
        }
        try (BufferedReader reader = Files.newBufferedReader(walFilePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                WalRecord record = parseJson(line);
                if (record != null) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    private WalRecord parseJson(String json) {
        try {
            SimpleJsonParser parser = new SimpleJsonParser(json);
            Map<String, Object> map = parser.parseObject();
            long lsn = ((Number) map.get("lsn")).longValue();
            int txId = ((Number) map.get("txId")).intValue();
            WalOperation op = WalOperation.valueOf((String) map.get("op"));
            String table = (String) map.get("table");

            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) map.get("values");

            return new WalRecord(lsn, txId, op, table, values);
        } catch (Exception e) {
            return null;
        }
    }

    static class SimpleJsonParser {
        private final String json;
        private int pos;

        SimpleJsonParser(String json) {
            this.json = json;
            this.pos = 0;
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            skipChar('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                skipChar(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                } else if (c == '}') {
                    pos++;
                    break;
                }
                break;
            }
            return map;
        }

        private Object parseValue() {
            skipWhitespace();
            char c = peek();
            if (c == '"') {
                return parseString();
            } else if (c == '{') {
                return parseObject();
            } else if (c == 'n') {
                pos += 4;
                return null;
            } else if (c == 't') {
                pos += 4;
                return true;
            } else if (c == 'f') {
                pos += 5;
                return false;
            } else {
                return parseNumber();
            }
        }

        private String parseString() {
            skipChar('"');
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    char esc = json.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < json.length()) {
                char c = json.charAt(pos);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                    pos++;
                } else {
                    break;
                }
            }
            String numStr = json.substring(start, pos);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private char peek() {
            if (pos >= json.length()) return '\0';
            return json.charAt(pos);
        }

        private void skipChar(char expected) {
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == expected) {
                pos++;
            }
        }

        private void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }
    }
}
