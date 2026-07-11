package com.example.rdb.schema;

import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;

public class MergedEnumerable<T> extends AbstractEnumerable<T> implements Enumerable<T> {

    private final Enumerable<T> first;
    private final Enumerable<T> second;

    public MergedEnumerable(Enumerable<T> first, Enumerable<T> second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public Enumerator<T> enumerator() {
        return new MergedEnumerator<>(first.enumerator(), second.enumerator());
    }

    private static class MergedEnumerator<T> implements Enumerator<T> {
        private final Enumerator<T> first;
        private final Enumerator<T> second;
        private boolean firstExhausted = false;

        MergedEnumerator(Enumerator<T> first, Enumerator<T> second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public T current() {
            return firstExhausted ? second.current() : first.current();
        }

        @Override
        public boolean moveNext() {
            if (!firstExhausted) {
                if (first.moveNext()) {
                    return true;
                }
                firstExhausted = true;
                first.close();
            }
            return second.moveNext();
        }

        @Override
        public void reset() {
            first.reset();
            second.reset();
            firstExhausted = false;
        }

        @Override
        public void close() {
            try { first.close(); } catch (Exception ignored) {}
            try { second.close(); } catch (Exception ignored) {}
        }
    }
}
