package com.example.rdb.schema;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Schemas;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class ExampleTable extends AbstractTable implements ScannableTable, ModifiableTable {

    private final String tableName;
    private final List<ColumnDef> columns;
    private final List<String> primaryKeyColumns;
    private final List<Object[]> deltaRows;
    private final List<Object[]> deletedRows;
    private Path baseDataPath;
    private BufferAllocator allocator;
    private WalAware walAware;

    public ExampleTable(String tableName, List<ColumnDef> columns) {
        this(tableName, columns, List.of());
    }

    public ExampleTable(String tableName, List<ColumnDef> columns, List<String> primaryKeyColumns) {
        this.tableName = tableName;
        this.columns = List.copyOf(columns);
        this.primaryKeyColumns = List.copyOf(primaryKeyColumns);
        this.deltaRows = new CopyOnWriteArrayList<>();
        this.deletedRows = new CopyOnWriteArrayList<>();
        validatePrimaryKeyColumns();
    }

    public interface WalAware {
        void onInsert(String tableName, Map<String, Object> values);
        void onDelete(String tableName, Map<String, Object> values);
    }

    public void setWalAware(WalAware walAware) {
        this.walAware = walAware;
    }

    public void setBaseDataPath(Path baseDataPath) {
        this.baseDataPath = baseDataPath;
    }

    public Path getBaseDataPath() {
        return baseDataPath;
    }

    public void setAllocator(BufferAllocator allocator) {
        this.allocator = allocator;
    }

    public static class ColumnDef {
        public final String name;
        public final SqlTypeName typeName;

        public ColumnDef(String name, SqlTypeName typeName) {
            this.name = name;
            this.typeName = typeName;
        }
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        for (ColumnDef col : columns) {
            RelDataType type = typeFactory.createSqlType(col.typeName);
            builder.add(col.name, typeFactory.createTypeWithNullability(type, true));
        }
        return builder.build();
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        Enumerable<Object[]> delta = new ListEnumerable<>(deltaRows);

        if (baseDataPath == null || allocator == null) {
            return delta;
        }

        Enumerable<Object[]> base = new ArrowBaseEnumerable();
        return new MergedEnumerable(base, delta);
    }

    private class ArrowBaseEnumerable extends AbstractEnumerable<Object[]> {
        @Override
        public Enumerator<Object[]> enumerator() {
            Enumerator<Object[]> raw = new com.example.rdb.storage.ArrowBatchEnumerable(
                    baseDataPath, columns, allocator).enumerator();
            if (deletedRows.isEmpty()) {
                return raw;
            }
            return new FilteredEnumerator(raw);
        }
    }

    private class FilteredEnumerator implements Enumerator<Object[]> {
        private final Enumerator<Object[]> delegate;

        FilteredEnumerator(Enumerator<Object[]> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object[] current() {
            return delegate.current();
        }

        @Override
        public boolean moveNext() {
            while (delegate.moveNext()) {
                if (!isDeleted(delegate.current())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void reset() {
            delegate.reset();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Override
    public TableModify toModificationRel(
            RelOptCluster cluster,
            RelOptTable table,
            Prepare.CatalogReader catalogReader,
            RelNode child,
            TableModify.Operation operation,
            List<String> updateColumnList,
            List<RexNode> sourceExpressionList,
            boolean flattened) {
        return LogicalTableModify.create(table, catalogReader, child, operation,
                updateColumnList, sourceExpressionList, flattened);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Collection getModifiableCollection() {
        return new WalBackedCollection();
    }

    @Override
    public Type getElementType() {
        return Object[].class;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Expression getExpression(SchemaPlus subSchema, String tableName, Class clazz) {
        return Schemas.tableExpression(subSchema, getElementType(), tableName, clazz);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
        return (Queryable<T>) scan(null).asQueryable();
    }

    public synchronized void addRow(Object[] values) {
        validatePrimaryKey(values);
        deltaRows.add(values);
    }

    public synchronized void deleteRows(List<Object[]> rows) {
        for (Object[] row : rows) {
            removeRowInternal(row);
            if (walAware != null) {
                walAware.onDelete(tableName, toValueMap(row));
            }
        }
    }

    public synchronized void deleteRow(Object[] row) {
        removeRowInternal(row);
    }

    private void removeRowInternal(Object[] row) {
        for (int i = deltaRows.size() - 1; i >= 0; i--) {
            if (rowsEqual(deltaRows.get(i), row)) {
                deltaRows.remove(i);
                return;
            }
        }
        deletedRows.add(row);
    }

    public synchronized void applyUpdates(List<Object[]> oldRows, List<Object[]> newRows) {
        for (int i = 0; i < oldRows.size(); i++) {
            Object[] oldRow = oldRows.get(i);
            Object[] newRow = newRows.get(i);

            removeRowInternal(oldRow);
            validatePrimaryKey(newRow);
            deltaRows.add(newRow);

            if (walAware != null) {
                walAware.onDelete(tableName, toValueMap(oldRow));
                walAware.onInsert(tableName, toValueMap(newRow));
            }
        }
    }

    private boolean isDeleted(Object[] row) {
        for (Object[] deleted : deletedRows) {
            if (rowsEqual(deleted, row)) {
                return true;
            }
        }
        return false;
    }

    private boolean rowsEqual(Object[] a, Object[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!valueEquals(a[i], b[i])) return false;
        }
        return true;
    }

    private boolean valueEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    public synchronized void clearDelta() {
        deltaRows.clear();
        deletedRows.clear();
    }

    public List<Object[]> getDeltaRows() {
        return deltaRows;
    }

    public List<Object[]> getDeletedRows() {
        return deletedRows;
    }

    public List<Object[]> getRows() {
        return deltaRows;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnDef> getColumns() {
        return columns;
    }

    public List<String> getPrimaryKeyColumns() {
        return primaryKeyColumns;
    }

    public List<String> getColumnNames() {
        List<String> names = new ArrayList<>();
        for (ColumnDef col : columns) {
            names.add(col.name);
        }
        return names;
    }

    public Map<String, Object> toValueMap(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < row.length; i++) {
            map.put(columns.get(i).name, row[i]);
        }
        return map;
    }

    private Map<String, Object> toMap(Object[] row) {
        return toValueMap(row);
    }

    @SuppressWarnings("rawtypes")
    private class WalBackedCollection extends AbstractCollection {

        @Override
        public synchronized boolean add(Object e) {
            Object[] row = ensureArray(e);
            validatePrimaryKey(row);
            if (walAware != null) {
                walAware.onInsert(tableName, toMap(row));
            }
            return deltaRows.add(row);
        }

        @Override
        public synchronized boolean remove(Object o) {
            Object[] target = ensureArray(o);
            for (int i = 0; i < deltaRows.size(); i++) {
                if (Arrays.equals(deltaRows.get(i), target)) {
                    deltaRows.remove(i);
                    return true;
                }
            }
            return false;
        }

        @Override
        public Iterator iterator() {
            return deltaRows.iterator();
        }

        @Override
        public int size() {
            return deltaRows.size();
        }

        private Object[] ensureArray(Object e) {
            if (e instanceof Object[]) {
                return (Object[]) e;
            }
            return new Object[]{e};
        }
    }

    private void validatePrimaryKeyColumns() {
        for (String primaryKeyColumn : primaryKeyColumns) {
            if (columnIndex(primaryKeyColumn) < 0) {
                throw new IllegalArgumentException("Primary key column not found: " + primaryKeyColumn);
            }
        }
    }

    private void validatePrimaryKey(Object[] values) {
        if (primaryKeyColumns.isEmpty()) return;
        List<Integer> indexes = primaryKeyColumns.stream().map(this::columnIndex).toList();
        for (int index : indexes) {
            if (index >= values.length || values[index] == null) {
                throw new IllegalArgumentException("Primary key column must not be null: " + columns.get(index).name);
            }
        }
        for (Object[] existing : deltaRows) {
            boolean duplicate = indexes.stream().allMatch(index -> Objects.equals(existing[index], values[index]));
            if (duplicate) {
                throw new IllegalArgumentException("Duplicate primary key for table " + tableName);
            }
        }
    }

    public int columnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(columnName)) return i;
        }
        return -1;
    }
}
