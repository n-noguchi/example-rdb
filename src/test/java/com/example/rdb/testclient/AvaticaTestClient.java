package com.example.rdb.testclient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Test-only external client for validating an Example RDB Avatica endpoint.
 */
public final class AvaticaTestClient {

    private AvaticaTestClient() {
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args.length == 0
                ? "jdbc:avatica:remote:url=http://localhost:8765;serialization=PROTOBUF"
                : args[0];

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT 1 AS connection_test")) {
                if (!result.next() || result.getInt("connection_test") != 1) {
                    throw new AssertionError("Unexpected query result from " + jdbcUrl);
                }
            }
        }

        System.out.println("Avatica JDBC test client completed successfully.");
    }
}
