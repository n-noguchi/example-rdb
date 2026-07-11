package com.example.rdb.wal;

import com.example.rdb.schema.ExampleSchema;
import com.example.rdb.schema.ExampleTable;
import com.example.rdb.storage.ArrowStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckpointManager {

    private final ArrowStorage storage;
    private final ExampleSchema schema;
    private final WalManager walManager;
    private final Path tablesDir;
    private final long intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;

    public CheckpointManager(ArrowStorage storage, ExampleSchema schema,
                             WalManager walManager, Path tablesDir, long intervalSeconds) {
        this.storage = storage;
        this.schema = schema;
        this.walManager = walManager;
        this.tablesDir = tablesDir;
        this.intervalSeconds = intervalSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "checkpoint-thread");
            t.setDaemon(true);
            return t;
        });
        this.running = new AtomicBoolean(false);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::checkpointSafe, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void checkpoint() throws IOException {
        for (Map.Entry<String, ExampleTable> entry : schema.getTables().entrySet()) {
            String tableName = entry.getKey();
            ExampleTable table = entry.getValue();
            Path arrowFile = tablesDir.resolve(tableName + ".arrow");
            storage.writeTable(arrowFile, table);
        }
        walManager.rotateSegment();
        walManager.deleteOldSegments(walManager.getCurrentSegment());
    }

    private void checkpointSafe() {
        try {
            checkpoint();
        } catch (IOException e) {
            System.err.println("Checkpoint failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
