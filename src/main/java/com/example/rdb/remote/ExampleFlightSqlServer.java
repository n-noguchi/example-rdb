package com.example.rdb.remote;

import com.example.rdb.ExampleRdb;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;

public class ExampleFlightSqlServer implements AutoCloseable {

    private final FlightServer server;
    private final int port;

    public ExampleFlightSqlServer(ExampleRdb database, BufferAllocator allocator, int port) {
        this.port = port;
        ErdbFlightSqlProducer producer = new ErdbFlightSqlProducer(database, allocator, null);
        this.server = FlightServer.builder()
                .allocator(allocator)
                .location(Location.forGrpcInsecure("0.0.0.0", port))
                .producer(producer)
                .build();
    }

    public void start() throws Exception {
        server.start();
        System.out.println("Flight SQL server listening on grpc://0.0.0.0:" + port);
    }

    @Override
    public void close() throws Exception {
        server.close();
    }

    public int getPort() {
        return port;
    }
}
