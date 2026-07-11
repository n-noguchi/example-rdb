package com.example.rdb.schema;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.Convention;
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
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ExampleTable extends AbstractTable implements ScannableTable, ModifiableTable {

    private final String tableName;
    private final List<ColumnDef> columns;
    private final List<String> primaryKeyColumns;
    private final List<Object[]> rows;
    private WalAware walAware;

    public ExampleTable(String tableName, List<ColumnDef> columns) {
        this(tableName, columns, List.of());
    }

    public ExampleTable(String tableName, List<ColumnDef> columns, List<String> primaryKeyColumns) {
        this.tableName = tableName;
        this.columns = List.copyOf(columns);
        this.primaryKeyColumns = List.copyOf(primaryKeyColumns);
        this.rows = new ArrayList<>();
        validatePrimaryKeyColumns();
    }

    public interface WalAware {
        void onInsert(String tableName, Map<String, Object> values);
    }

    public void setWalAware(WalAware walAware) {
        this.walAware = walAware;
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
        return new ListEnumerable<>(rows);
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
        rows.add(values);
    }

    public void deleteRow(int index) {
        rows.remove(index);
    }

    public void updateRow(int index, Object[] values) {
        rows.set(index, values);
    }

    public List<Object[]> getRows() {
        return rows;
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

    private Map<String, Object> toMap(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < row.length; i++) {
            map.put(columns.get(i).name, row[i]);
        }
        return map;
    }

    @SuppressWarnings("rawtypes")
    private class WalBackedCollection extends AbstractCollection {

        @Override
        public boolean add(Object e) {
            Object[] row = ensureArray(e);
            validatePrimaryKey(row);
            if (walAware != null) {
                walAware.onInsert(tableName, toMap(row));
            }
            return rows.add(row);
        }

        @Override
        public boolean remove(Object o) {
            Object[] target = ensureArray(o);
            for (int i = 0; i < rows.size(); i++) {
                if (java.util.Arrays.equals(rows.get(i), target)) {
                    rows.remove(i);
                    return true;
                }
            }
            return false;
        }

        @Override
        public Iterator iterator() {
            return rows.iterator();
        }

        @Override
        public int size() {
            return rows.size();
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
        for (Object[] existing : rows) {
            boolean duplicate = indexes.stream().allMatch(index -> Objects.equals(existing[index], values[index]));
            if (duplicate) {
                throw new IllegalArgumentException("Duplicate primary key for table " + tableName);
            }
        }
    }

    private int columnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(columnName)) return i;
        }
        return -1;
    }
}
