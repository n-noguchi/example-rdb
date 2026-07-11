package com.example.rdb.index;

import java.util.List;

public record IndexDefinition(
        String name,
        String tableName,
        List<String> keyColumns,
        List<String> includeColumns,
        boolean unique
) {
    public List<String> allCoveredColumns() {
        var all = new java.util.ArrayList<>(keyColumns);
        for (String inc : includeColumns) {
            if (!all.contains(inc)) {
                all.add(inc);
            }
        }
        return all;
    }

    public boolean covers(List<String> requiredColumns) {
        List<String> covered = allCoveredColumns();
        for (String req : requiredColumns) {
            boolean found = false;
            for (String c : covered) {
                if (c.equalsIgnoreCase(req)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
