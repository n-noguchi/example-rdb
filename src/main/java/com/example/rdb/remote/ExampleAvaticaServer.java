package com.example.rdb.remote;

import com.example.rdb.ExampleRdb;
import org.apache.calcite.avatica.remote.Driver;
import org.apache.calcite.avatica.remote.LocalService;
import org.apache.calcite.avatica.server.HttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Publishes an {@link ExampleRdb} instance through the Avatica HTTP protocol. */
public final class ExampleAvaticaServer implements AutoCloseable {

    public static final int DEFAULT_PORT = 8765;

    private final ExampleRdb database;
    private final HttpServer server;

    public ExampleAvaticaServer(Path dataDir, int port) throws SQLException {
        this.database = new ExampleRdb(dataDir);
        LocalService service = new LocalService(new ExampleJdbcMeta(database));
        this.server = new HttpServer.Builder<>()
                .withPort(port)
                .withHandler(service, Driver.Serialization.PROTOBUF)
                .build();
    }

    public void start() {
        server.start();
    }

    public int getPort() {
        return server.getPort();
    }

    public ExampleRdb getDatabase() {
        return database;
    }

    @Override
    public void close() throws IOException {
        server.stop();
        database.close();
    }

    public static void main(String[] args) throws Exception {
        ServerOptions options = ServerOptions.parse(args);
        ExampleAvaticaServer server = new ExampleAvaticaServer(options.dataDir(), options.port());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeQuietly(server), "avatica-server-shutdown"));
        server.start();
        System.out.printf("Example RDB Avatica server listening on http://0.0.0.0:%d (data: %s)%n",
                server.getPort(), options.dataDir().toAbsolutePath());
        server.server.join();
    }

    private static void closeQuietly(ExampleAvaticaServer server) {
        try {
            server.close();
        } catch (IOException ignored) {
            // Shutdown must not prevent JVM termination.
        }
    }

    private record ServerOptions(Path dataDir, int port) {
        private static ServerOptions parse(String[] args) {
            Path dataDir = Path.of("data");
            int port = DEFAULT_PORT;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--data-dir" -> dataDir = Path.of(requireValue(args, ++i, "--data-dir"));
                    case "--port" -> port = parsePort(requireValue(args, ++i, "--port"));
                    case "--help", "-h" -> {
                        System.out.println("Usage: ExampleAvaticaServer [--data-dir PATH] [--port 1-65535]");
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new ServerOptions(dataDir, port);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static int parsePort(String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) throw new IllegalArgumentException();
                return port;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Port must be between 1 and 65535: " + value, e);
            }
        }
    }
}
