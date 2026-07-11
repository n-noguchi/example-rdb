package com.example.rdb.wal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WalRecord {

    private final long lsn;
    private final int txId;
    private final WalOperation operation;
    private final String tableName;
    private final Map<String, Object> values;

    public WalRecord(long lsn, int txId, WalOperation operation, String tableName, Map<String, Object> values) {
        this.lsn = lsn;
        this.txId = txId;
        this.operation = operation;
        this.tableName = tableName;
        this.values = values != null ? new LinkedHashMap<>(values) : null;
    }

    public static WalRecord begin(long lsn, int txId) {
        return new WalRecord(lsn, txId, WalOperation.BEGIN, null, null);
    }

    public static WalRecord commit(long lsn, int txId) {
        return new WalRecord(lsn, txId, WalOperation.COMMIT, null, null);
    }

    public static WalRecord abort(long lsn, int txId) {
        return new WalRecord(lsn, txId, WalOperation.ABORT, null, null);
    }

    public static WalRecord insert(long lsn, int txId, String tableName, Map<String, Object> values) {
        return new WalRecord(lsn, txId, WalOperation.INSERT, tableName, values);
    }

    public long getLsn() {
        return lsn;
    }

    public int getTxId() {
        return txId;
    }

    public WalOperation getOperation() {
        return operation;
    }

    public String getTableName() {
        return tableName;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public Object getValue(String columnName) {
        return values != null ? values.get(columnName) : null;
    }

    @Override
    public String toString() {
        return "WalRecord{lsn=" + lsn + ", txId=" + txId + ", op=" + operation
                + (tableName != null ? ", table=" + tableName : "")
                + (values != null ? ", values=" + values : "")
                + "}";
    }
}
