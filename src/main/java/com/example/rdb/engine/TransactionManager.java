package com.example.rdb.engine;

import com.example.rdb.schema.ExampleTable;
import com.example.rdb.wal.WalManager;

import java.io.IOException;
import java.util.Map;

public class TransactionManager implements ExampleTable.WalAware {

    private final WalManager walManager;

    public TransactionManager(WalManager walManager) {
        this.walManager = walManager;
    }

    @Override
    public void onInsert(String tableName, Map<String, Object> values) {
        try {
            int txId = walManager.beginTransaction();
            walManager.logInsert(txId, tableName, values);
            walManager.commitTransaction(txId);
        } catch (IOException e) {
            throw new RuntimeException("WAL write failed for INSERT on " + tableName, e);
        }
    }

    public int begin() throws IOException {
        return walManager.beginTransaction();
    }

    public void commit(int txId) throws IOException {
        walManager.commitTransaction(txId);
    }

    public void abort(int txId) throws IOException {
        walManager.abortTransaction(txId);
    }
}
