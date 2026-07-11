package com.example.rdb.schema;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExampleSchema extends AbstractSchema {

    private final Map<String, ExampleTable> tables = new LinkedHashMap<>();

    public void addTable(ExampleTable table) {
        tables.put(table.getTableName(), table);
    }

    public ExampleTable removeTable(String name) {
        return tables.remove(name);
    }

    @Override
    protected Map<String, Table> getTableMap() {
        return new HashMap<>(tables);
    }

    public ExampleTable getExampleTable(String name) {
        return tables.get(name);
    }

    public Map<String, ExampleTable> getTables() {
        return tables;
    }
}
