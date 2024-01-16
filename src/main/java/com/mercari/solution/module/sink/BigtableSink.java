package com.mercari.solution.module.sink;

import com.google.bigtable.v2.Mutation;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.common.collect.Lists;
import com.google.datastore.v1.Entity;
import com.google.gson.Gson;
import com.google.protobuf.ByteString;
import com.mercari.solution.config.SinkConfig;
import com.mercari.solution.module.FCollection;
import com.mercari.solution.module.SinkModule;
import com.mercari.solution.util.DateTimeUtil;
import com.mercari.solution.util.TemplateUtil;
import com.mercari.solution.util.converter.*;
import com.mercari.solution.util.schema.*;
import freemarker.template.Template;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableIO;
import org.apache.beam.sdk.io.gcp.bigtable.BigtableWriteResult;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


public class BigtableSink implements SinkModule {

    private static final Logger LOG = LoggerFactory.getLogger(BigtableSink.class);

    private static class BigtableSinkParameters implements Serializable {

        private String projectId;
        private String instanceId;
        private String tableId;

        private List<String> rowKeyFields;
        private String columnFamily;
        private String columnQualifier;

        private String rowKeyTemplate;
        private String columnFamilyTemplate;
        private String columnQualifierTemplate;

        private Format format;
        private MutationOp mutationOp;
        private TimestampType timestampType;
        private List<ColumnSetting> columnSettings;
        private String separator;


        public String getProjectId() {
            return projectId;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public String getTableId() {
            return tableId;
        }

        public List<String> getRowKeyFields() {
            return rowKeyFields;
        }

        public String getColumnFamily() {
            return columnFamily;
        }

        public String getColumnQualifier() {
            return columnQualifier;
        }

        public String getRowKeyTemplate() {
            return rowKeyTemplate;
        }

        public String getColumnFamilyTemplate() {
            return columnFamilyTemplate;
        }

        public String getColumnQualifierTemplate() {
            return columnQualifierTemplate;
        }

        public Format getFormat() {
            return format;
        }

        public MutationOp getMutationOp() {
            return mutationOp;
        }

        public TimestampType getTimestampType() {
            return timestampType;
        }

        public String getSeparator() {
            return separator;
        }

        public List<ColumnSetting> getColumnSettings() {
            return columnSettings;
        }

        public void setDefaults() {
            if(format == null) {
                format = Format.string;
            }
            if(columnQualifier == null) {
                columnQualifier = "body";
            }
            if(separator == null) {
                separator = "#";
            }
            if(mutationOp == null) {
                mutationOp = MutationOp.SET_CELL;
            }
            if(timestampType == null) {
                timestampType = TimestampType.insertedtime;
            }
            if(columnSettings == null) {
                columnSettings = new ArrayList<>();
            } else {
                for(var setting : columnSettings) {
                    setting.setDefaults(format, columnFamily, mutationOp);
                }
            }
        }

        public void validate() {
            final List<String> errorMessages = new ArrayList<>();
            if(projectId == null) {
                errorMessages.add("BigtableSink module requires `projectId` parameter.");
            }
            if(instanceId == null) {
                errorMessages.add("BigtableSink module requires `instanceId` parameter.");
            }
            if(tableId == null) {
                errorMessages.add("BigtableSink module requires `tableId` parameter.");
            }
            if((rowKeyFields == null || rowKeyFields.size() == 0) && rowKeyTemplate == null) {
                errorMessages.add("BigtableSink module requires `rowKeyFields` or `rowKeyTemplate` parameter.");
            }
            if(columnFamily == null && columnFamilyTemplate == null) {
                errorMessages.add("BigtableSink module requires `columnFamily` or `columnFamilyTemplate` parameter.");
            }
            if(columnSettings != null) {
                for(var setting : columnSettings) {
                    errorMessages.addAll(setting.validate());
                }
            }

            if(errorMessages.size() > 0) {
                throw new IllegalArgumentException(errorMessages.stream().collect(Collectors.joining(", ")));
            }
        }

    }

    public enum Format implements Serializable {
        bytes,
        string,
        avro
    }

    public enum MutationOp implements Serializable {
        SET_CELL,
        DELETE_FROM_COLUMN,
        DELETE_FROM_FAMILY,
        DELETE_FROM_ROW
    }

    public enum TimestampType implements Serializable {
        eventtime,
        insertedtime
    }

    public static class ColumnSetting implements Serializable {

        private String field;
        private String columnFamily;
        private String columnQualifier;
        private Boolean exclude;
        private Format format;
        private MutationOp mutationOp;

        public String getField() {
            return field;
        }

        public String getColumnFamily() {
            return columnFamily;
        }

        public String getColumnQualifier() {
            return columnQualifier;
        }

        public Boolean getExclude() {
            return exclude;
        }

        public Format getFormat() {
            return format;
        }

        public MutationOp getMutationOp() {
            return mutationOp;
        }

        public void setDefaults(final Format format, final String defaultColumnFamily, final MutationOp defaultMutationOp) {
            if (columnQualifier == null) {
                columnQualifier = field;
            }
            if (columnFamily == null) {
                columnFamily = defaultColumnFamily;
            }
            if (exclude == null) {
                exclude = false;
            }
            if (this.format == null) {
                this.format = format;
            }
            if (this.mutationOp == null) {
                this.mutationOp = defaultMutationOp;
            }
        }

        public List<String> validate() {
            final List<String> errorMessages = new ArrayList<>();
            if (field == null) {
                errorMessages.add("BigtableSink module's mappings parameter requires `field` parameter.");
            }
            return errorMessages;
        }
    }



    public String getName() { return "bigtable"; }

    @Override
    public Map<String, FCollection<?>> expand(List<FCollection<?>> inputs, SinkConfig config, List<FCollection<?>> waits) {
        if(inputs == null || inputs.size() != 1) {
            throw new IllegalArgumentException("bigtable sink module requires input parameter");
        }
        final FCollection<?> input = inputs.get(0);
        return Collections.singletonMap(config.getName(), BigtableSink.write(input, config, waits));
    }

    public static FCollection<?> write(final FCollection<?> input, final SinkConfig config) {
        return write(input, config, null);
    }

    public static FCollection<?> write(final FCollection<?> input, final SinkConfig config, final List<FCollection<?>> waits) {
        final BigtableSinkParameters parameters = new Gson().fromJson(config.getParameters(), BigtableSinkParameters.class);
        if(parameters == null) {
            throw new IllegalArgumentException("bigtable sink module parameters must not be null!");
        }
        parameters.validate();
        parameters.setDefaults();

        try {
            config.outputAvroSchema(input.getAvroSchema());
        } catch (Exception e) {
            LOG.error("Failed to output avro schema for " + config.getName() + " to path: " + config.getOutputAvroSchema(), e);
        }

        final PCollection output = switch (input.getDataType()) {
            case AVRO -> {
                final FCollection<GenericRecord> inputCollection = (FCollection<GenericRecord>) input;
                final Write<GenericRecord, String, Schema> write = new Write<>(
                        parameters, input.getAvroSchema().toString(),
                        AvroSchemaUtil::convertSchema, AvroSchemaUtil::getAsString, RecordToMapConverter::convert,
                        RecordToBigtableConverter::convert, (s, r) -> r, AvroSchemaUtil::convertSchema);
                yield inputCollection.getCollection().apply(config.getName(), write);
            }
            case ROW -> {
                final FCollection<Row> inputCollection = (FCollection<Row>) input;
                final Write<Row, org.apache.beam.sdk.schemas.Schema, org.apache.beam.sdk.schemas.Schema> write = new Write<>(
                        parameters, input.getSchema(),
                        s -> s, RowSchemaUtil::getAsString, RowToMapConverter::convert,
                        RowToBigtableConverter::convert, RowToRecordConverter::convert, RowToRecordConverter::convertSchema);
                yield inputCollection.getCollection().apply(config.getName(), write);
            }
            case STRUCT -> {
                final FCollection<Struct> inputCollection = (FCollection<Struct>) input;
                final Write<Struct, Type, Type> write = new Write<>(
                        parameters, input.getSpannerType(),
                        t -> t, StructSchemaUtil::getAsString, StructToMapConverter::convert,
                        StructToBigtableConverter::convert, StructToRecordConverter::convert, StructToRecordConverter::convertSchema);
                yield inputCollection.getCollection().apply(config.getName(), write);
            }
            case ENTITY -> {
                final FCollection<Entity> inputCollection = (FCollection<Entity>) input;
                final Write<Entity, org.apache.beam.sdk.schemas.Schema, org.apache.beam.sdk.schemas.Schema> write = new Write<>(
                        parameters, input.getSchema(),
                        s -> s, EntitySchemaUtil::getAsString, EntityToMapConverter::convert,
                        EntityToBigtableConverter::convert, EntityToRecordConverter::convert, RowToRecordConverter::convertSchema);
                yield inputCollection.getCollection().apply(config.getName(), write);
            }
            default -> throw new IllegalStateException();
        };
        return FCollection.update(input, output);
    }


    public static class Write<T,InputSchemaT,RuntimeSchemaT> extends PTransform<PCollection<T>, PCollection<BigtableWriteResult>> {

        private static final Logger LOG = LoggerFactory.getLogger(Write.class);

        private final InputSchemaT inputSchema;
        private final SchemaUtil.SchemaConverter<InputSchemaT,RuntimeSchemaT> schemaConverter;
        private final SchemaUtil.StringGetter<T> stringGetter;
        private final SchemaUtil.MapConverter<T> mapConverter;
        private final MutationConverter<T,RuntimeSchemaT> mutationConverter;
        private final SchemaUtil.DataConverter<Schema,T,GenericRecord> avroConverter;
        private final SchemaUtil.SchemaConverter<InputSchemaT,Schema> avroSchemaConverter;

        private final BigtableSinkParameters parameters;

        private Write(final BigtableSinkParameters parameters,
                      final InputSchemaT inputSchema,
                      final SchemaUtil.SchemaConverter<InputSchemaT,RuntimeSchemaT> schemaConverter,
                      final SchemaUtil.StringGetter<T> stringGetter,
                      final SchemaUtil.MapConverter<T> mapConverter,
                      final MutationConverter<T,RuntimeSchemaT> mutationConverter,
                      final SchemaUtil.DataConverter<Schema,T,GenericRecord> avroConverter,
                      final SchemaUtil.SchemaConverter<InputSchemaT,Schema> avroSchemaConverter) {

            this.parameters = parameters;
            this.inputSchema = inputSchema;
            this.schemaConverter = schemaConverter;
            this.stringGetter = stringGetter;
            this.mapConverter = mapConverter;
            this.mutationConverter = mutationConverter;
            this.avroConverter = avroConverter;
            this.avroSchemaConverter = avroSchemaConverter;
        }

        public PCollection<BigtableWriteResult> expand(final PCollection<T> input) {

            final PCollection<BigtableWriteResult> writeResults = input
                    .apply("ToMutations", ParDo.of(new MutationDoFn<>(
                            parameters.getRowKeyFields(), parameters.getColumnFamily(), parameters.getColumnQualifier(),
                            parameters.getRowKeyTemplate(), parameters.getColumnFamilyTemplate(), parameters.getColumnQualifierTemplate(),
                            parameters.getFormat(), parameters.getMutationOp(), parameters.getTimestampType(),
                            parameters.getColumnSettings(), parameters.getSeparator(),
                            inputSchema, schemaConverter, stringGetter, mapConverter, mutationConverter, avroConverter, avroSchemaConverter)))
                    .apply("WriteBigtable", BigtableIO.write()
                            .withProjectId(parameters.getProjectId())
                            .withInstanceId(parameters.getInstanceId())
                            .withTableId(parameters.getTableId())
                            .withoutValidation()
                            .withWriteResults());

            return writeResults;
        }

    }

    private static class MutationDoFn<T,InputSchemaT,RuntimeSchemaT> extends DoFn<T, KV<ByteString, Iterable<Mutation>>> {

        private static final DateTimeUtil.DateTimeTemplateUtils datetimeUtils = new DateTimeUtil.DateTimeTemplateUtils();

        private final List<String> rowKeyFields;
        private final String columnFamily;
        private final String columnQualifier;

        private final String rowKeyTemplate;
        private final String columnFamilyTemplate;
        private final String columnQualifierTemplate;

        private final Format format;
        private final MutationOp mutationOp;
        private final TimestampType timestampType;
        private final String separator;
        private final Map<String, ColumnSetting> columnSettings;

        private final InputSchemaT inputSchema;
        private final SchemaUtil.SchemaConverter<InputSchemaT,RuntimeSchemaT> schemaConverter;
        private final SchemaUtil.StringGetter<T> stringGetter;
        private final SchemaUtil.MapConverter<T> mapConverter;
        private final MutationConverter<T,RuntimeSchemaT> mutationConverter;
        private final SchemaUtil.DataConverter<Schema,T,GenericRecord> avroConverter;
        private final SchemaUtil.SchemaConverter<InputSchemaT,Schema> avroSchemaConverter;

        private transient RuntimeSchemaT runtimeSchema;
        private transient Schema avroSchema;

        private transient Template templateRowKey;
        private transient Template templateColumnFamily;
        private transient Template templateColumnQualifier;


        public MutationDoFn(final List<String> rowKeyFields,
                            final String columnFamily,
                            final String columnQualifier,
                            final String rowKeyTemplate,
                            final String columnFamilyTemplate,
                            final String columnQualifierTemplate,
                            final Format format,
                            final MutationOp mutationOp,
                            final TimestampType timestampType,
                            final List<ColumnSetting> columnSettings,
                            final String separator,
                            final InputSchemaT inputSchema,
                            final SchemaUtil.SchemaConverter<InputSchemaT,RuntimeSchemaT> schemaConverter,
                            final SchemaUtil.StringGetter<T> stringGetter,
                            final SchemaUtil.MapConverter<T> mapConverter,
                            final MutationConverter<T,RuntimeSchemaT> mutationConverter,
                            final SchemaUtil.DataConverter<Schema,T,GenericRecord> avroConverter,
                            final SchemaUtil.SchemaConverter<InputSchemaT,Schema> avroSchemaConverter) {

            this.rowKeyFields = rowKeyFields;
            this.columnFamily = columnFamily;
            this.columnQualifier = columnQualifier;
            this.rowKeyTemplate = rowKeyTemplate;
            this.columnFamilyTemplate = columnFamilyTemplate;
            this.columnQualifierTemplate = columnQualifierTemplate;

            this.format = format;
            this.mutationOp = mutationOp;
            this.timestampType = timestampType;
            this.columnSettings = columnSettings.stream().collect(Collectors.toMap(ColumnSetting::getField, c -> c));
            this.separator = separator;

            this.inputSchema = inputSchema;
            this.schemaConverter = schemaConverter;
            this.stringGetter = stringGetter;
            this.mapConverter = mapConverter;
            this.mutationConverter = mutationConverter;
            this.avroConverter = avroConverter;
            this.avroSchemaConverter = avroSchemaConverter;
        }

        @Setup
        public void setup() {
            this.runtimeSchema = schemaConverter.convert(inputSchema);
            if(rowKeyTemplate != null) {
                this.templateRowKey = TemplateUtil.createStrictTemplate("rowKeyTemplate", rowKeyTemplate);
            } else {
                this.templateRowKey = null;
            }
            if(columnFamilyTemplate != null) {
                this.templateColumnFamily = TemplateUtil.createStrictTemplate("columnFamilyTemplate", columnFamilyTemplate);
            } else {
                this.templateColumnFamily = null;
            }
            if(columnQualifierTemplate != null) {
                this.templateColumnQualifier = TemplateUtil.createStrictTemplate("columnQualifierTemplate", columnQualifierTemplate);;
            } else {
                this.templateColumnQualifier = null;
            }
            this.avroSchema = avroSchemaConverter.convert(inputSchema);
        }

        @ProcessElement
        public void processElement(final ProcessContext c) throws IOException {

            // Generate template data
            final T element = c.element();
            final Map<String,Object> data;
            if(templateRowKey == null && templateColumnFamily == null && templateColumnQualifier == null) {
                data = null;
            } else {
                data = mapConverter.convert(element);
                data.put("_DateTimeUtil", datetimeUtils);
                data.put("_EVENTTIME", Instant.ofEpochMilli(c.timestamp().getMillis()));
            }

            // Generate columnFamily
            final String cf;
            if(templateColumnFamily == null) {
                cf = columnFamily;
            } else {
                cf = TemplateUtil.executeStrictTemplate(templateColumnFamily, data);
            }

            // Generate columnQualifier
            final String cq;
            if(templateColumnQualifier == null) {
                cq = columnQualifier;
            } else {
                cq = TemplateUtil.executeStrictTemplate(templateColumnQualifier, data);
            }

            // Generate timestampMicros
            final long timestampMicros;
            switch (timestampType) {
                case insertedtime -> {
                    timestampMicros = org.joda.time.Instant.now().getMillis() * 1000L;
                }
                case eventtime -> {
                    if (c.timestamp().getMillis() >= -1) {
                        timestampMicros = c.timestamp().getMillis() * 1000L;
                    } else {
                        timestampMicros = -1L;
                    }
                }
                default -> {
                    throw new IllegalStateException("Not supported timestampType: " + timestampType);
                }
            }

            // Generate mutations
            final Iterable<Mutation> mutations;
            switch (format) {
                case bytes, string -> {
                    mutations = mutationConverter.convert(runtimeSchema, element, cf, format, mutationOp, columnSettings, timestampMicros);
                }
                case avro -> {
                    final GenericRecord record = avroConverter.convert(avroSchema, element);
                    final byte[] bytes = AvroSchemaUtil.encode(record);
                    final Mutation.SetCell cell = Mutation.SetCell.newBuilder()
                            .setFamilyName(cf)
                            .setColumnQualifier(ByteString.copyFrom(cq, StandardCharsets.UTF_8))
                            .setValue(ByteString.copyFrom(bytes))
                            .build();
                    final Mutation mutation = Mutation.newBuilder().setSetCell(cell).build();
                    mutations = Lists.newArrayList(mutation);
                }
                default -> {
                    throw new IllegalStateException("BigtableSink not supported format: " + format);
                }
            }

            // Generate rowKey
            final String rowKeyString;
            if(rowKeyFields != null && rowKeyFields.size() > 0) {
                final StringBuilder sb = new StringBuilder();
                for(final String field : rowKeyFields) {
                    final String value = stringGetter.getAsString(element, field);
                    sb.append(value);
                    sb.append(separator);
                }
                if(sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - separator.length());
                }
                rowKeyString = sb.toString();
            } else if(rowKeyTemplate != null) {
                rowKeyString = TemplateUtil.executeStrictTemplate(templateRowKey, data);
            } else {
                throw new IllegalStateException("Both rowKeyFields and rowKeyTemplate are null!");
            }
            final ByteString rowKey = ByteString.copyFrom(rowKeyString, StandardCharsets.UTF_8);

            final KV<ByteString, Iterable<Mutation>> output = KV.of(rowKey, mutations);
            c.output(output);
        }

    }


    private interface MutationConverter<T, SchemaT> extends Serializable {
        Iterable<Mutation> convert(final SchemaT schema, final T element,
                                   final String defaultColumnFamily,
                                   final Format defaultFormat,
                                   final MutationOp defaultMutationOp,
                                   final Map<String,ColumnSetting> columnSettings,
                                   final long timestampMicros);
    }

}
