package com.mercari.solution.config;

import com.google.gson.JsonObject;
import com.mercari.solution.util.AvroSchemaUtil;
import com.mercari.solution.util.aws.S3Util;
import com.mercari.solution.util.converter.RecordToRowConverter;
import com.mercari.solution.util.converter.RowToRecordConverter;
import com.mercari.solution.util.gcp.StorageUtil;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.SerializableFunction;

import java.io.Serializable;
import java.util.List;

public class SourceConfig implements Serializable {

    public enum Module {
        storage,
        bigquery,
        spanner,
        datastore,
        jdbc,
        pubsub,
        spannerBackup
    }

    private String name;
    private Module module;
    private Boolean microbatch;
    private InputSchema schema;
    private JsonObject parameters;
    private List<String> wait;
    private String timestampAttribute;
    private String timestampDefault;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Module getModule() {
        return module;
    }

    public void setModule(Module module) {
        this.module = module;
    }

    public Boolean getMicrobatch() {
        return microbatch;
    }

    public void setMicrobatch(Boolean microbatch) {
        this.microbatch = microbatch;
    }

    public InputSchema getSchema() {
        return schema;
    }

    public void setSchema(InputSchema schema) {
        this.schema = schema;
    }

    public JsonObject getParameters() {
        return parameters;
    }

    public void setParameters(JsonObject parameters) {
        this.parameters = parameters;
    }

    public List<String> getWait() {
        return wait;
    }

    public void setWait(List<String> wait) {
        this.wait = wait;
    }

    public String getTimestampAttribute() {
        return timestampAttribute;
    }

    public void setTimestampAttribute(String timestampAttribute) {
        this.timestampAttribute = timestampAttribute;
    }

    public String getTimestampDefault() {
        if(timestampDefault == null) {
            return "1970-01-01T00:00:00Z";
        }
        return timestampDefault;
    }

    public void setTimestampDefault(String timestampDefault) {
        this.timestampDefault = timestampDefault;
    }

    public static Schema convertSchema(final InputSchema inputSchema) {
        if(inputSchema.getAvroSchema() != null && inputSchema.getAvroSchema().startsWith("gs://")) {
            final String schemaString = StorageUtil.readString(inputSchema.getAvroSchema());
            return RecordToRowConverter.convertSchema(AvroSchemaUtil.convertSchema(schemaString));
        } else if(inputSchema.getFields() != null && inputSchema.getFields().size() > 0) {
            return convertSchema(inputSchema.getFields());
        } else {
            throw new IllegalArgumentException("SourceConfig does not contain schema.");
        }
    }

    public static org.apache.avro.Schema convertAvroSchema(final InputSchema inputSchema) {
        if(inputSchema.getAvroSchema() != null && inputSchema.getAvroSchema().startsWith("gs://")) {
            final String schemaString = StorageUtil.readString(inputSchema.getAvroSchema());
            return AvroSchemaUtil.convertSchema(schemaString);
        } else if(inputSchema.getFields() != null && inputSchema.getFields().size() > 0) {
            return RowToRecordConverter.convertSchema(convertSchema(inputSchema.getFields()));
        } else {
            throw new IllegalArgumentException("SourceConfig does not contain schema.");
        }
    }

    private static Schema convertSchema(final List<InputSchemaField> fields) {
        if(fields == null || fields.size() == 0) {
            return null;
        }
        Schema.Builder builder = Schema.builder();
        for(final InputSchemaField field : fields) {
            builder.addField(field.getName(), convertFieldType(field));
        }
        return builder.build();
    }

    public static org.apache.avro.Schema convertAvroSchema(final List<InputSchemaField> fields) {
        final Schema schema = convertSchema(fields);
        if(schema == null) {
            return null;
        }
        return RowToRecordConverter.convertSchema(schema);
    }

    private static Schema.FieldType convertFieldType(final InputSchemaField field) {
        return convertFieldType(field, field.getMode());
    }

    private static Schema.FieldType convertFieldType(final InputSchemaField field, final String mode) {
        if("repeated".equals(mode.trim().toLowerCase())) {
            return Schema.FieldType.array(convertFieldType(field, "nullable"));
        }
        final boolean nullable;
        if(mode == null) {
            nullable = true;
        } else {
            nullable = "nullable".equals(mode.trim().toLowerCase());
        }
        switch (field.getType().trim().toLowerCase()) {
            case "bytes":
                return Schema.FieldType.BYTES.withNullable(nullable);
            case "string":
                return Schema.FieldType.STRING.withNullable(nullable);
            case "int32":
            case "integer":
                return Schema.FieldType.INT32.withNullable(nullable);
            case "int64":
            case "long":
                return Schema.FieldType.INT64.withNullable(nullable);
            case "float32":
            case "float":
                return Schema.FieldType.FLOAT.withNullable(nullable);
            case "float64":
            case "double":
                return Schema.FieldType.DOUBLE.withNullable(nullable);
            case "bool":
            case "boolean":
                return Schema.FieldType.BOOLEAN.withNullable(nullable);
            case "datetime":
                return Schema.FieldType.DATETIME.withNullable(nullable);
            case "struct":
            case "record":
                return Schema.FieldType.row(convertSchema(field.getFields())).withNullable(nullable);
            case "decimal":
                return Schema.FieldType.DECIMAL.withNullable(nullable);
            case "date":
                return CalciteUtils.DATE.withNullable(nullable);
            case "time":
                return CalciteUtils.TIME.withNullable(nullable);
            case "timestamp":
                return CalciteUtils.TIMESTAMP.withNullable(nullable);
            default:
                throw new IllegalArgumentException("Field[" + field.getName() + "] type " + field.getType() + " is not supported !");
        }
    }

    public class InputSchema implements Serializable {

        private String avroSchema;
        private List<InputSchemaField> fields;

        public String getAvroSchema() {
            return avroSchema;
        }

        public void setAvroSchema(String avroSchema) {
            this.avroSchema = avroSchema;
        }

        public List<InputSchemaField> getFields() {
            return fields;
        }

        public void setFields(List<InputSchemaField> fields) {
            this.fields = fields;
        }

    }

    public class InputSchemaField implements Serializable {

        private String name;
        private String type;
        private String mode;
        private List<InputSchemaField> fields;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public List<InputSchemaField> getFields() {
            return fields;
        }

        public void setFields(List<InputSchemaField> fields) {
            this.fields = fields;
        }
    }

}

