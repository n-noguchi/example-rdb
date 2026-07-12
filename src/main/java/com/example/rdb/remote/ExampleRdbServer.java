package com.example.rdb.remote;

import java.nio.file.Path;

/**
 * Unified server launcher: Avatica + Flight SQL in a single JVM.
 *
 * Usage:
 *   java ... com.example.rdb.remote.ExampleRdbServer --data-dir ./data --avatica-port 8765 --flight-port 8815
 */
public class ExampleRdbServer {

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("./data");
        int avaticaPort = 8765;
        int flightPort = 8815;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataDir = Path.of(args[++i]);
                case "--avatica-port" -> avaticaPort = Integer.parseInt(args[++i]);
                case "--flight-port" -> flightPort = Integer.parseInt(args[++i]);
                case "--port" -> { avaticaPort = Integer.parseInt(args[++i]); flightPort = avaticaPort + 50; }
            }
        }

        ExampleAvaticaServer avaticaServer = new ExampleAvaticaServer(dataDir, avaticaPort);
        avaticaServer.start();

        ExampleFlightSqlServer flightServer = new ExampleFlightSqlServer(
                avaticaServer.getDatabase(),
                avaticaServer.getDatabase().getStorage().getAllocator(),
                flightPort);
        flightServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                flightServer.close();
                avaticaServer.close();
            } catch (Exception ignored) {
            }
        }));

        System.out.println("Example RDB server started:");
        System.out.println("  Avatica: http://0.0.0.0:" + avaticaPort);
        System.out.println("  Flight:  grpc://0.0.0.0:" + flightPort);
        System.out.println("  Data:    " + dataDir.toAbsolutePath());

        Thread.currentThread().join();
    }
}
