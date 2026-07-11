package com.example.rdb.wal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public class WalWriter {

    private final Path walFilePath;
    private BufferedWriter writer;
    private long nextLsn;

    public WalWriter(Path walFilePath, long startLsn) throws IOException {
        this.walFilePath = walFilePath;
        this.nextLsn = startLsn;
        Files.createDirectories(walFilePath.getParent());
        this.writer = Files.newBufferedWriter(walFilePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized WalRecord append(int txId, WalOperation op, String tableName, Map<String, Object> values) throws IOException {
        long lsn = nextLsn++;
        WalRecord record;
        if (values != null) {
            record = new WalRecord(lsn, txId, op, tableName, new LinkedHashMap<>(values));
        } else {
            record = new WalRecord(lsn, txId, op, tableName, null);
        }

        String json = toJson(record);
        writer.write(json);
        writer.newLine();
        writer.flush();
        return record;
    }

    public WalRecord appendBegin(int txId) throws IOException {
        return append(txId, WalOperation.BEGIN, null, null);
    }

    public WalRecord appendInsert(int txId, String tableName, Map<String, Object> values) throws IOException {
        return append(txId, WalOperation.INSERT, tableName, values);
    }

    public WalRecord appendDelete(int txId, String tableName, Map<String, Object> values) throws IOException {
        return append(txId, WalOperation.DELETE, tableName, values);
    }

    public WalRecord appendCommit(int txId) throws IOException {
        return append(txId, WalOperation.COMMIT, null, null);
    }

    public WalRecord appendAbort(int txId) throws IOException {
        return append(txId, WalOperation.ABORT, null, null);
    }

    public long getNextLsn() {
        return nextLsn;
    }

    public void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private String toJson(WalRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"lsn\":").append(record.getLsn()).append(',');
        sb.append("\"txId\":").append(record.getTxId()).append(',');
        sb.append("\"op\":\"").append(record.getOperation().name()).append('"');
        if (record.getTableName() != null) {
            sb.append(",\"table\":\"").append(escapeJson(record.getTableName())).append('"');
        }
        if (record.getValues() != null && !record.getValues().isEmpty()) {
            sb.append(",\"values\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : record.getValues().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(entry.getKey())).append("\":");
                appendValue(sb, entry.getValue());
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append('"').append(escapeJson(value.toString())).append('"');
        }
    }

    private String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public Path getFilePath() {
        return walFilePath;
    }
}
