package com.example.rdb.remote;

import com.example.rdb.ExampleRdb;
import com.example.rdb.schema.ExampleTable;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ValueVector;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;

public class ErdbFlightSqlProducer extends NoOpFlightSqlProducer {

    private final ExampleRdb database;
    private final BufferAllocator allocator;

    public ErdbFlightSqlProducer(ExampleRdb database, BufferAllocator allocator, Path dataDir) {
        this.database = database;
        this.allocator = allocator;
    }

    @Override
    public Runnable acceptPutStatementBulkIngest(
            FlightSql.CommandStatementIngest command,
            FlightProducer.CallContext context,
            FlightStream flightStream,
            FlightProducer.StreamListener<PutResult> ackListener) {

        String tableName = command.getTable();
        ExampleTable table = database.getSchema().getExampleTable(tableName);

        if (table == null) {
            ackListener.onError(new RuntimeException("Table not found: " + tableName));
            return () -> {};
        }

        long totalRows = 0;
        while (flightStream.next()) {
            VectorSchemaRoot root = flightStream.getRoot();
            int rowCount = root.getRowCount();
            for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
                Object[] row = extractRow(root, rowIdx, table);
                table.addRow(row);
                totalRows++;
            }
        }

        // Send PutResult with record count
        FlightSql.DoPutUpdateResult result = FlightSql.DoPutUpdateResult.newBuilder()
                .setRecordCount(totalRows)
                .build();
        byte[] resultBytes = result.toByteArray();
        org.apache.arrow.memory.ArrowBuf buf = allocator.buffer(resultBytes.length);
        buf.writeBytes(resultBytes);
        ackListener.onNext(PutResult.metadata(buf));
        buf.close();

        System.out.println("[Flight] Bulk ingest: " + totalRows + " rows into " + tableName);

        return () -> {
            ackListener.onCompleted();
        };
    }

    private Object[] extractRow(VectorSchemaRoot root, int rowIdx, ExampleTable table) {
        List<ExampleTable.ColumnDef> columns = table.getColumns();
        Object[] row = new Object[columns.size()];
        for (int colIdx = 0; colIdx < columns.size(); colIdx++) {
            String colName = columns.get(colIdx).name;
            ValueVector vector = root.getVector(colName);
            if (vector != null && !vector.isNull(rowIdx)) {
                row[colIdx] = vector.getObject(rowIdx);
            }
        }
        return row;
    }
}
