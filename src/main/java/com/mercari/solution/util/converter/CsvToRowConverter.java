package com.mercari.solution.util.converter;

import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.values.Row;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;
import org.joda.time.Instant;

import java.io.IOException;
import java.math.BigDecimal;

public class CsvToRowConverter {

    public static Row convert(final Schema schema, final String text) {
        try(final CSVParser parser = CSVParser.parse(text, CSVFormat.DEFAULT)) {
            if(parser.getRecords().size() != 1) {
                return null;
            }
            final CSVRecord record = parser.getRecords().get(0);
            Row.Builder builder = Row.withSchema(schema);
            for(int i=0; i<schema.getFieldCount(); i++) {
                if(i >= record.size()) {
                    builder.addValue(null);
                    continue;
                }
                builder.addValue(convertValue(schema.getField(i).getType(), record.get(i)));
            }
            return builder.build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object convertValue(final Schema.FieldType fieldType, final String value) {
        if(value == null) {
            return null;
        }
        switch (fieldType.getTypeName()) {
            case BOOLEAN:
                return Boolean.valueOf(value);
            case STRING:
                return value;
            case BYTES:
                return value.getBytes();
            case DECIMAL:
                return BigDecimal.valueOf(0, 0);
            case INT16:
                return Short.valueOf(value);
            case INT32:
                return Integer.valueOf(value);
            case INT64:
                return Long.valueOf(value);
            case FLOAT:
                return Float.valueOf(value);
            case DOUBLE:
                return Double.valueOf(value);
            case DATETIME:
                return Instant.parse(value);
            case LOGICAL_TYPE:
                if(CalciteUtils.DATE.typesEqual(fieldType) || CalciteUtils.NULLABLE_DATE.typesEqual(fieldType)) {
                        return DateTime.parse(value);
                } else if(CalciteUtils.TIME.typesEqual(fieldType) || CalciteUtils.NULLABLE_TIME.typesEqual(fieldType)) {
                    return value;
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported Beam logical type: " + fieldType.getLogicalType().getIdentifier());
                }
            case BYTE:
            case MAP:
            case ROW:
            case ARRAY:
            case ITERABLE:
            default:
                throw new IllegalArgumentException("Csv can not handle data type: " + fieldType.getTypeName());
        }
    }

}
