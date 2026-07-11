package com.example.rdb.index;

import java.util.Arrays;
import java.util.Comparator;

public class IndexKey implements Comparable<IndexKey> {

    private final Object[] values;

    public IndexKey(Object[] values) {
        this.values = values;
    }

    public static IndexKey of(Object... values) {
        return new IndexKey(values);
    }

    public Object[] getValues() {
        return values;
    }

    public int size() {
        return values.length;
    }

    public Object get(int i) {
        return values[i];
    }

    @Override
    public int compareTo(IndexKey other) {
        int len = Math.min(values.length, other.values.length);
        for (int i = 0; i < len; i++) {
            int cmp = compareValues(values[i], other.values[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(values.length, other.values.length);
    }

    @SuppressWarnings("unchecked")
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        if (a instanceof Number na && b instanceof Number nb) {
            if (a instanceof Long || b instanceof Long) {
                return Long.compare(na.longValue(), nb.longValue());
            }
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }

        if (a instanceof Comparable ca && b.getClass().isInstance(a)) {
            return ca.compareTo(b);
        }

        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexKey other)) return false;
        return Arrays.deepEquals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.deepHashCode(values);
    }

    @Override
    public String toString() {
        return "IndexKey" + Arrays.deepToString(values);
    }
}
