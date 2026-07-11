package com.example.rdb.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExampleAvaticaServerTest {

    @Test
    void executesQueriesThroughAvatica(@TempDir Path dataDir) throws Exception {
        try (ExampleAvaticaServer server = new ExampleAvaticaServer(dataDir, 0)) {
            server.start();

            String url = "jdbc:avatica:remote:url=http://localhost:" + server.getPort()
                    + ";serialization=PROTOBUF";
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(100))"))
                        .isEqualTo(0);
                statement.executeUpdate("INSERT INTO users VALUES (1, 'Alice')");
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO users VALUES (1, 'Bob')"))
                        .isInstanceOf(Exception.class);
                try (ResultSet result = statement.executeQuery("SELECT name FROM users")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("Alice");
                }
            }
        }
    }

    @Test
    void enforcesCompositePrimaryKeyAndRejectsNull(@TempDir Path dataDir) throws Exception {
        try (ExampleAvaticaServer server = new ExampleAvaticaServer(dataDir, 0)) {
            server.start();
            String url = "jdbc:avatica:remote:url=http://localhost:" + server.getPort()
                    + ";serialization=PROTOBUF";

            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE memberships (user_id INTEGER, group_id INTEGER, "
                        + "CONSTRAINT memberships_pk PRIMARY KEY (user_id, group_id))");
                statement.executeUpdate("INSERT INTO memberships VALUES (1, 10)");
                statement.executeUpdate("INSERT INTO memberships VALUES (1, 20)");

                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO memberships VALUES (1, 10)"))
                        .isInstanceOf(Exception.class);
                assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO memberships VALUES (NULL, 30)"))
                        .isInstanceOf(Exception.class);
            }
        }
    }

    @Test
    void dropsTableThroughAvatica(@TempDir Path dataDir) throws Exception {
        try (ExampleAvaticaServer server = new ExampleAvaticaServer(dataDir, 0)) {
            server.start();
            String url = "jdbc:avatica:remote:url=http://localhost:" + server.getPort()
                    + ";serialization=PROTOBUF";

            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE temporary_data (id INTEGER)");
                statement.executeUpdate("INSERT INTO temporary_data VALUES (1)");
                assertThat(statement.executeUpdate("DROP TABLE temporary_data")).isEqualTo(0);
                assertThat(server.getDatabase().getSchema().getExampleTable("temporary_data")).isNull();
                assertThat(statement.executeUpdate("DROP TABLE IF EXISTS temporary_data")).isEqualTo(0);
                assertThatThrownBy(() -> statement.executeUpdate("DROP TABLE temporary_data"))
                        .isInstanceOf(Exception.class);
            }
        }
    }
}
