package com.example.rdb.index;

import java.util.Arrays;

public class CoveringEntry implements Comparable<CoveringEntry> {

    private final long rowId;
    private final IndexKey key;
    private final Object[] allValues;

    public CoveringEntry(long rowId, IndexKey key, Object[] allValues) {
        this.rowId = rowId;
        this.key = key;
        this.allValues = allValues;
    }

    public long getRowId() {
        return rowId;
    }

    public IndexKey getKey() {
        return key;
    }

    public Object[] getAllValues() {
        return allValues;
    }

    @Override
    public int compareTo(CoveringEntry other) {
        int cmp = this.key.compareTo(other.key);
        if (cmp != 0) return cmp;
        return Long.compare(this.rowId, other.rowId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CoveringEntry other)) return false;
        return rowId == other.rowId && key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(rowId) * 31 + key.hashCode();
    }

    @Override
    public String toString() {
        return "CoveringEntry{rowId=" + rowId + ", key=" + key + ", values=" + Arrays.toString(allValues) + "}";
    }
}
