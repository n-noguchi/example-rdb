package com.example.rdb.schema;

import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;

import java.util.List;

public class ListEnumerable<T> extends AbstractEnumerable<T> implements Enumerable<T> {

    private final List<T> list;

    public ListEnumerable(List<T> list) {
        this.list = list;
    }

    @Override
    public Enumerator<T> enumerator() {
        return new ListEnumerator<>(list);
    }

    private static class ListEnumerator<T> implements Enumerator<T> {
        private final List<T> list;
        private int index = -1;

        ListEnumerator(List<T> list) {
            this.list = list;
        }

        @Override
        public T current() {
            return list.get(index);
        }

        @Override
        public boolean moveNext() {
            index++;
            return index < list.size();
        }

        @Override
        public void reset() {
            index = -1;
        }

        @Override
        public void close() {
        }
    }
}
