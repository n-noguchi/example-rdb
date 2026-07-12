package com.example.rdb.cli;

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.types.Types.MinorType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

@Command(
        name = "import",
        mixinStandardHelpOptions = true,
        description = "Bulk import CSV/TSV data into Example RDB via Arrow Flight SQL"
)
public class ImportCommand implements Callable<Integer> {

    @Option(names = {"--endpoint"}, description = "Flight endpoint (e.g. grpc://host:8815)", required = true)
    String endpoint;

    @Option(names = {"--table"}, description = "Target table name", required = true)
    String tableName;

    @Option(names = {"--file"}, description = "Input file path")
    String filePath;

    @Option(names = {"--stdin"}, description = "Read from stdin")
    boolean stdin;

    @Option(names = {"--format"}, description = "Input format: csv, tsv", defaultValue = "csv")
    String format;

    @Option(names = {"--header"}, negatable = true, description = "First line is header (default: true)")
    boolean header = true;

    @Option(names = {"--jdbc-url"}, description = "JDBC URL for metadata retrieval")
    String jdbcUrl;

    @Option(names = {"--validate-only"}, description = "Validate without sending data")
    boolean validateOnly;

    @Option(names = {"--null-string"}, description = "NULL marker", defaultValue = "\\N")
    String nullString;

    @Option(names = {"--verbose"}, description = "Verbose output")
    boolean verbose;

    @Override
    public Integer call() throws Exception {
        boolean hasHeader = header;

        if (filePath == null && !stdin) {
            System.err.println("Error: --file or --stdin is required");
            return 2;
        }

        System.out.println("Target table : " + tableName);
        System.out.println("Input        : " + (stdin ? "stdin" : filePath));
        System.out.println("Format       : " + format);

        TableMeta meta = fetchTableMetadata();
        if (meta == null) {
            System.err.println("Error: cannot get metadata for table '" + tableName + "'");
            return 4;
        }
        if (verbose) {
            System.out.println("Columns      : " + meta.columnNames);
        }

        // Parse and validate all rows
        List<String[]> allRows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int lineNum = 0;

        CSVFormat csvFormat = "tsv".equalsIgnoreCase(format)
                ? CSVFormat.DEFAULT.builder().setDelimiter('\t').build()
                : CSVFormat.DEFAULT;
        try (Reader reader = stdin ? new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)
                                   : new FileReader(filePath, StandardCharsets.UTF_8);
             CSVParser parser = csvFormat.parse(reader)) {

            boolean headerSkipped = false;
            for (CSVRecord record : parser) {
                if (hasHeader && !headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                lineNum++;
                if (record.size() != meta.columnNames.size()) {
                    errors.add(String.format("line %d: expected %d columns but found %d",
                            lineNum, meta.columnNames.size(), record.size()));
                    if (errors.size() >= 100) break;
                    continue;
                }
                String[] values = new String[record.size()];
                for (int i = 0; i < record.size(); i++) {
                    values[i] = record.get(i);
                }
                allRows.add(values);
            }
        }

        // Validate types
        for (int i = 0; i < allRows.size() && errors.size() < 100; i++) {
            String[] values = allRows.get(i);
            for (int col = 0; col < values.length; col++) {
                String err = validateValue(values[col], meta.columnTypes.get(col),
                        meta.columnNames.get(col), i + 1);
                if (err != null) errors.add(err);
            }
        }

        if (!errors.isEmpty()) {
            System.err.println("\nValidation failed. No data was sent.\n");
            for (String err : errors) System.err.println("  " + err);
            System.err.println("\nErrors: " + errors.size());
            return 3;
        }

        System.out.println("Rows validated: " + String.format("%,d", allRows.size()));

        if (validateOnly) {
            System.out.println("Result        : VALIDATION_SUCCESS (no data sent)");
            return 0;
        }

        // Send via Flight SQL executeIngest
        long startTime = System.currentTimeMillis();

        String flightEndpoint = endpoint.startsWith("grpc://") ? endpoint : "grpc://" + endpoint;
        String host = extractHost(flightEndpoint);
        int port = extractPort(flightEndpoint);

        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {
            Location location = Location.forGrpcInsecure(host, port);
            FlightSqlClient client = new FlightSqlClient(
                    FlightClient.builder(allocator, location).build());

            Schema schema = buildArrowSchema(meta);

            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
                populateVectors(root, allRows, meta);
                root.setRowCount(allRows.size());

                FlightSqlClient.ExecuteIngestOptions ingestOptions = new FlightSqlClient.ExecuteIngestOptions(
                        tableName,
                        FlightSql.CommandStatementIngest.TableDefinitionOptions.newBuilder()
                                .setIfExists(FlightSql.CommandStatementIngest.TableDefinitionOptions.TableExistsOption.TABLE_EXISTS_OPTION_APPEND)
                                .setIfNotExist(FlightSql.CommandStatementIngest.TableDefinitionOptions.TableNotExistOption.TABLE_NOT_EXIST_OPTION_FAIL)
                                .build(),
                        false,
                        "rdb",
                        null,
                        java.util.Map.of()
                );

                long rowsIngested = client.executeIngest(root, ingestOptions);
                long elapsed = System.currentTimeMillis() - startTime;

                System.out.println("Rows uploaded : " + String.format("%,d", rowsIngested));
                System.out.println("Elapsed       : " + formatDuration(elapsed));
                System.out.println("Result        : SUCCESS");
            }

            client.close();
        }

        return 0;
    }

    private TableMeta fetchTableMetadata() {
        String url = jdbcUrl != null ? jdbcUrl : endpointToJdbcUrl();
        if (verbose) System.out.println("JDBC URL      : " + url);
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(url)) {
            try (java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + " WHERE 1 = 0")) {
                java.sql.ResultSetMetaData md = rs.getMetaData();
                List<String> names = new ArrayList<>();
                List<MinorType> types = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    names.add(md.getColumnName(i));
                    types.add(sqlTypeToArrow(md.getColumnType(i)));
                }
                return new TableMeta(names, types);
            }
        } catch (Exception e) {
            if (verbose) System.err.println("JDBC metadata failed: " + e.getMessage());
            return null;
        }
    }

    private String endpointToJdbcUrl() {
        String stripped = endpoint.replace("grpc://", "").replace("http://", "");
        int colon = stripped.lastIndexOf(':');
        String host = colon > 0 ? stripped.substring(0, colon) : stripped;
        int avaticaPort = colon > 0 ? Integer.parseInt(stripped.substring(colon + 1)) - 50 : 8765;
        return "jdbc:avatica:remote:url=http://" + host + ":" + avaticaPort + ";serialization=PROTOBUF";
    }

    private MinorType sqlTypeToArrow(int sqlType) {
        return switch (sqlType) {
            case java.sql.Types.INTEGER -> MinorType.INT;
            case java.sql.Types.BIGINT -> MinorType.BIGINT;
            case java.sql.Types.DOUBLE, java.sql.Types.FLOAT, java.sql.Types.REAL -> MinorType.FLOAT8;
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> MinorType.BIT;
            default -> MinorType.VARCHAR;
        };
    }

    private String validateValue(String value, MinorType type, String colName, int lineNum) {
        if (value == null || value.equals(nullString)) return null;
        try {
            switch (type) {
                case INT -> Integer.parseInt(value);
                case BIGINT -> Long.parseLong(value);
                case FLOAT8 -> Double.parseDouble(value);
                case BIT -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")
                            && !value.equals("0") && !value.equals("1"))
                        return String.format("line %d: column \"%s\": \"%s\" is not BOOLEAN", lineNum, colName, truncate(value));
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            return String.format("line %d: column \"%s\": \"%s\" is not %s", lineNum, colName, truncate(value), type);
        }
        return null;
    }

    private String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    private CSVFormat buildCsvFormat(boolean hasHeader) {
        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder();
        if ("tsv".equalsIgnoreCase(format)) builder.setDelimiter('\t');
        if (hasHeader) builder.setHeader().setSkipHeaderRecord(true);
        return builder.build();
    }

    private Schema buildArrowSchema(TableMeta meta) {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < meta.columnNames.size(); i++) {
            fields.add(new Field(meta.columnNames.get(i),
                    FieldType.nullable(meta.columnTypes.get(i).getType()), null));
        }
        return new Schema(fields);
    }

    private void populateVectors(VectorSchemaRoot root, List<String[]> rows, TableMeta meta) {
        for (int colIdx = 0; colIdx < meta.columnNames.size(); colIdx++) {
            String colName = meta.columnNames.get(colIdx);
            MinorType type = meta.columnTypes.get(colIdx);
            FieldVector vector = root.getVector(colName);
            for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
                String value = rows.get(rowIdx)[colIdx];
                if (value == null || value.equals(nullString)) {
                    setNull(vector, rowIdx);
                } else {
                    setVectorValue(vector, rowIdx, type, value);
                }
            }
            vector.setValueCount(rows.size());
        }
    }

    private void setVectorValue(FieldVector vector, int index, MinorType type, String value) {
        switch (type) {
            case INT -> ((IntVector) vector).setSafe(index, Integer.parseInt(value));
            case BIGINT -> ((BigIntVector) vector).setSafe(index, Long.parseLong(value));
            case FLOAT8 -> ((Float8Vector) vector).setSafe(index, Double.parseDouble(value));
            case BIT -> ((BitVector) vector).setSafe(index, value.equalsIgnoreCase("true") || value.equals("1") ? 1 : 0);
            default -> ((VarCharVector) vector).setSafe(index, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void setNull(FieldVector vector, int index) {
        if (vector instanceof IntVector v) v.setNull(index);
        else if (vector instanceof BigIntVector v) v.setNull(index);
        else if (vector instanceof Float8Vector v) v.setNull(index);
        else if (vector instanceof VarCharVector v) v.setNull(index);
        else if (vector instanceof BitVector v) v.setNull(index);
    }

    private String extractHost(String uri) {
        String s = uri.replace("grpc://", "").replace("http://", "");
        int c = s.lastIndexOf(':');
        return c > 0 ? s.substring(0, c) : s;
    }

    private int extractPort(String uri) {
        String s = uri.replace("grpc://", "").replace("http://", "");
        int c = s.lastIndexOf(':');
        return c > 0 ? Integer.parseInt(s.substring(c + 1)) : 8815;
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d.%03d", s / 3600, (s / 60) % 60, s % 60, ms % 1000);
    }

    private record TableMeta(List<String> columnNames, List<MinorType> columnTypes) {}
}
