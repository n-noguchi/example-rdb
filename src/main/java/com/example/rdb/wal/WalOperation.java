package com.example.rdb.wal;

public enum WalOperation {
    BEGIN,
    INSERT,
    UPDATE,
    DELETE,
    COMMIT,
    ABORT
}
