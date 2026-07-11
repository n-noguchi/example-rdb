package com.example.rdb.storage;

import com.example.rdb.schema.ExampleTable;
import org.apache.arrow.vector.types.Types.MinorType;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.List;

public class ArrowSchemaConverter {

    public static Schema toArrowSchema(List<ExampleTable.ColumnDef> columns) {
        List<Field> fields = new ArrayList<>();
        for (ExampleTable.ColumnDef col : columns) {
            MinorType minorType = toMinorType(col.typeName);
            Field field = new Field(col.name, FieldType.nullable(minorType.getType()), null);
            fields.add(field);
        }
        return new Schema(fields);
    }

    public static MinorType toMinorType(SqlTypeName sqlTypeName) {
        switch (sqlTypeName) {
            case TINYINT: return MinorType.TINYINT;
            case SMALLINT: return MinorType.SMALLINT;
            case INTEGER: return MinorType.INT;
            case BIGINT: return MinorType.BIGINT;
            case FLOAT: return MinorType.FLOAT4;
            case DOUBLE: return MinorType.FLOAT8;
            case VARCHAR:
            case CHAR: return MinorType.VARCHAR;
            case BOOLEAN: return MinorType.BIT;
            case DATE: return MinorType.DATEDAY;
            case TIME: return MinorType.TIMESEC;
            case TIMESTAMP: return MinorType.TIMESTAMPMICRO;
            default: return MinorType.VARCHAR;
        }
    }

    public static List<ExampleTable.ColumnDef> fromArrowSchema(Schema schema) {
        List<ExampleTable.ColumnDef> columns = new ArrayList<>();
        for (Field field : schema.getFields()) {
            SqlTypeName sqlTypeName = fromArrowType(field.getType());
            columns.add(new ExampleTable.ColumnDef(field.getName(), sqlTypeName));
        }
        return columns;
    }

    public static SqlTypeName fromArrowType(ArrowType arrowType) {
        if (arrowType instanceof ArrowType.Int) {
            ArrowType.Int intType = (ArrowType.Int) arrowType;
            switch (intType.getBitWidth()) {
                case 8: return SqlTypeName.TINYINT;
                case 16: return SqlTypeName.SMALLINT;
                case 32: return SqlTypeName.INTEGER;
                case 64: return SqlTypeName.BIGINT;
                default: return SqlTypeName.INTEGER;
            }
        } else if (arrowType instanceof ArrowType.FloatingPoint) {
            String precision = arrowType.toString();
            if (precision.contains("SINGLE")) {
                return SqlTypeName.FLOAT;
            }
            return SqlTypeName.DOUBLE;
        } else if (arrowType instanceof ArrowType.Utf8) {
            return SqlTypeName.VARCHAR;
        } else if (arrowType instanceof ArrowType.Bool) {
            return SqlTypeName.BOOLEAN;
        } else if (arrowType instanceof ArrowType.Date) {
            return SqlTypeName.DATE;
        } else if (arrowType instanceof ArrowType.Time) {
            return SqlTypeName.TIME;
        } else if (arrowType instanceof ArrowType.Timestamp) {
            return SqlTypeName.TIMESTAMP;
        }
        return SqlTypeName.VARCHAR;
    }
}
