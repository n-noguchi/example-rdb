package com.example.rdb.wal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class WalManager {

    private final Path walDir;
    private WalWriter currentWriter;
    private int currentSegment;
    private int nextTxId;

    public WalManager(Path walDir) throws IOException {
        this.walDir = walDir;
        this.nextTxId = 1;
        Files.createDirectories(walDir);
        this.currentSegment = findLatestSegment() + 1;
        this.currentWriter = new WalWriter(getWalFile(currentSegment), 1);
    }

    private int findLatestSegment() throws IOException {
        int max = 0;
        if (!Files.exists(walDir)) return 0;
        for (Path file : Files.newDirectoryStream(walDir, "wal_*.log")) {
            String name = file.getFileName().toString();
            String numPart = name.substring(4, name.length() - 4);
            try {
                int num = Integer.parseInt(numPart);
                if (num > max) max = num;
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    private Path getWalFile(int segment) {
        return walDir.resolve(String.format("wal_%06d.log", segment));
    }

    public synchronized int beginTransaction() throws IOException {
        int txId = nextTxId++;
        currentWriter.appendBegin(txId);
        return txId;
    }

    public synchronized WalRecord logInsert(int txId, String tableName, java.util.Map<String, Object> values) throws IOException {
        return currentWriter.appendInsert(txId, tableName, values);
    }

    public synchronized WalRecord logDelete(int txId, String tableName, java.util.Map<String, Object> values) throws IOException {
        return currentWriter.appendDelete(txId, tableName, values);
    }

    public synchronized void commitTransaction(int txId) throws IOException {
        currentWriter.appendCommit(txId);
    }

    public synchronized void abortTransaction(int txId) throws IOException {
        currentWriter.appendAbort(txId);
    }

    public synchronized void rotateSegment() throws IOException {
        long startLsn = currentWriter.getNextLsn();
        currentWriter.close();
        currentSegment++;
        currentWriter = new WalWriter(getWalFile(currentSegment), startLsn);
    }

    public List<WalRecord> readAllRecords() throws IOException {
        return new WalReader(getWalFile(currentSegment)).readAll();
    }

    public List<WalRecord> readSegment(int segment) throws IOException {
        return new WalReader(getWalFile(segment)).readAll();
    }

    public List<WalRecord> readAllSegments() throws IOException {
        List<WalRecord> all = new java.util.ArrayList<>();
        for (int seg = 1; seg <= currentSegment; seg++) {
            Path file = getWalFile(seg);
            if (Files.exists(file)) {
                all.addAll(new WalReader(file).readAll());
            }
        }
        return all;
    }

    public void deleteOldSegments(int beforeSegment) throws IOException {
        for (int seg = 1; seg < beforeSegment; seg++) {
            Path file = getWalFile(seg);
            Files.deleteIfExists(file);
        }
    }

    public int getCurrentSegment() {
        return currentSegment;
    }

    public Path getWalDir() {
        return walDir;
    }

    public void close() throws IOException {
        if (currentWriter != null) {
            currentWriter.close();
        }
    }
}
