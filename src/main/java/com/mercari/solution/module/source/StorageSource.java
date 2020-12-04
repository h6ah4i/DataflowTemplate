package com.mercari.solution.module.source;

import com.google.gson.Gson;
import com.mercari.solution.config.SourceConfig;
import com.mercari.solution.module.DataType;
import com.mercari.solution.module.FCollection;
import com.mercari.solution.util.AvroSchemaUtil;
import com.mercari.solution.util.aws.S3Util;
import com.mercari.solution.util.gcp.StorageUtil;
import com.mercari.solution.util.converter.CsvToRowConverter;
import com.mercari.solution.util.converter.JsonToRowConverter;
import com.mercari.solution.util.converter.TextToRowConverter;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.aws.options.AwsOptions;
import org.apache.beam.sdk.io.parquet.ParquetIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.*;

import java.io.Serializable;
import java.util.Objects;

public class StorageSource {

    private class StorageSourceParameters implements Serializable {

        private String input;
        private String format;
        private String compression;
        private String filterPrefix;

        public String getInput() {
            return input;
        }

        public void setInput(String input) {
            this.input = input;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getCompression() {
            return compression;
        }

        public void setCompression(String compression) {
            this.compression = compression;
        }

        public String getFilterPrefix() {
            return filterPrefix;
        }

        public void setFilterPrefix(String filterPrefix) {
            this.filterPrefix = filterPrefix;
        }

    }

    public static FCollection batch(final PBegin begin, final SourceConfig config) {
        final StorageSourceParameters parameters = new Gson().fromJson(config.getParameters(), StorageSourceParameters.class);
        switch (parameters.getFormat().toLowerCase()) {
            case "avro":
            case "parquet": {
                final StorageAvroBatchSource sourceAvro = new StorageAvroBatchSource(config);
                final PCollection<GenericRecord> outputAvro = begin.apply(config.getName(), sourceAvro);
                return FCollection.of(config.getName(), outputAvro, DataType.AVRO, sourceAvro.schema);
            }
            case "csv":
            case "json": {
                final StorageTextBatchSource sourceText = new StorageTextBatchSource(config);
                final PCollection<Row> outputText = begin.apply(config.getName(), sourceText);
                return FCollection.of(config.getName(), outputText, DataType.ROW, sourceText.schema);
            }
            default:
                throw new IllegalArgumentException();
        }
    }

    public static StorageMicrobatchRead microbatch(final SourceConfig config) {
        return new StorageMicrobatchRead(config);
    }

    public static class StorageAvroBatchSource
            extends PTransform<PBegin, PCollection<GenericRecord>> {

        private org.apache.avro.Schema schema;

        private final StorageSourceParameters parameters;
        private final SourceConfig.InputSchema inputSchema;

        public StorageSourceParameters getParameters() {
            return parameters;
        }

        private StorageAvroBatchSource(final SourceConfig config) {
            this.inputSchema = config.getSchema();
            this.parameters = new Gson().fromJson(config.getParameters(), StorageSourceParameters.class);
        }

        public PCollection<GenericRecord> expand(final PBegin begin) {

            final PipelineOptions options = begin.getPipeline().getOptions();
            final String input = parameters.getInput();
            final String format = parameters.getFormat();

            switch (format.trim().toLowerCase()) {
                case "avro": {
                    final org.apache.avro.Schema avroSchema = getAvroSchema(parameters.getInput(), inputSchema, options);
                    this.schema = avroSchema;//RecordToRowConverter.convertSchema(avroSchema);
                    return begin
                            .apply("ReadAvro", AvroIO
                                    .readGenericRecords(avroSchema)
                                    .from(input));
                }
                case "parquet": {
                    final org.apache.avro.Schema parquetSchema = getParquetSchema(parameters.getInput(), inputSchema, options);
                    this.schema = parquetSchema;//RecordToRowConverter.convertSchema(parquetSchema);
                    return begin
                            .apply("ReadParquet", ParquetIO
                                    .read(parquetSchema)
                                    .from(input));
                }
                default:
                    throw new IllegalArgumentException("");
            }
        }
    }

    public static class StorageTextBatchSource
            extends PTransform<PBegin, PCollection<Row>> {

        private Schema schema;

        private final StorageSourceParameters parameters;
        private final SourceConfig.InputSchema inputSchema;

        public StorageSourceParameters getParameters() {
            return parameters;
        }

        private StorageTextBatchSource(final SourceConfig config) {
            this.inputSchema = config.getSchema();
            this.parameters = new Gson().fromJson(config.getParameters(), StorageSourceParameters.class);
        }

        public PCollection<Row> expand(final PBegin begin) {

            final String format = parameters.getFormat();

            switch (format.trim().toLowerCase()) {
                case "csv":
                case "json": {
                    final SerializableFunction<String, Row> func;
                    if (this.inputSchema == null || (inputSchema.getAvroSchema() == null && inputSchema.getFields() == null)) {
                        this.schema = TextToRowConverter.DEFAULT_SCHEMA;
                        func = TextToRowConverter::convert;
                    } else if ("csv".equals(format.trim().toLowerCase())) {
                        this.schema = SourceConfig.convertSchema(inputSchema);
                        func = r -> CsvToRowConverter.convert(schema, r);
                    } else {
                        this.schema = SourceConfig.convertSchema(inputSchema);
                        func = r -> JsonToRowConverter.convert(schema, r);
                    }

                    TextIO.Read read = TextIO.read().from(parameters.getInput());
                    if (parameters.getCompression() != null) {
                        read = read.withCompression(Compression
                                .valueOf(parameters.getCompression().trim().toUpperCase()));
                    }

                    final PCollection<String> lines;
                    if (parameters.getFilterPrefix() != null) {
                        final String filterPrefix = parameters.getFilterPrefix();
                        lines = begin
                                .apply("ReadLine", read)
                                .apply("FilterPrefix", Filter.by(s -> !s.startsWith(filterPrefix)));
                    } else {
                        lines = begin.apply("ReadLine", read);
                    }

                    return lines
                            .apply("ConvertToRow", MapElements.into(TypeDescriptor.of(Row.class)).via(func))
                            .setRowSchema(schema);
                }
                default:
                    throw new IllegalArgumentException("StorageSource only support format avro, csv, json. but: " + format);
            }
        }
    }

    private static class StorageMicrobatchRead extends PTransform<PCollection<Long>, PCollection<Row>> {

        private final SourceConfig config;

        private StorageMicrobatchRead(final SourceConfig config) {
            this.config = config;
        }

        public PCollection<Row> expand(final PCollection<Long> begin) {
            return null;
        }

    }

    private static org.apache.avro.Schema getAvroSchema(
            final SourceConfig.InputSchema inputSchema,
            final AwsOptions awsOptions) {

        if(inputSchema != null) {
            if(inputSchema.getAvroSchema() != null) {
                if(inputSchema.getAvroSchema().startsWith("gs://")) {
                    final String schemaString = StorageUtil.readString(inputSchema.getAvroSchema());
                    return AvroSchemaUtil.convertSchema(schemaString);
                } else if(inputSchema.getAvroSchema().startsWith("s3://")) {
                    final String schemaString = S3Util.readString(inputSchema.getAvroSchema(), awsOptions);
                    return AvroSchemaUtil.convertSchema(schemaString);
                }
            } else if(inputSchema.getFields() != null && inputSchema.getFields().size() > 0) {
                return SourceConfig.convertAvroSchema(inputSchema.getFields());
            }
        }
        return null;
    }

    private static org.apache.avro.Schema getAvroSchema(
            final String input,
            final SourceConfig.InputSchema inputSchema,
            final PipelineOptions options) {

        final AwsOptions awsOptions = options.as(AwsOptions.class);

        org.apache.avro.Schema avroSchema = getAvroSchema(inputSchema, awsOptions);
        if(avroSchema != null) {
            return avroSchema;
        }

        if(input.startsWith("gs://")) {
            avroSchema = StorageUtil.getAvroSchema(input);
            if(avroSchema != null) {
                return avroSchema;
            }
            return StorageUtil.listFiles(input)
                    .stream()
                    .map(StorageUtil::getAvroSchema)
                    .filter(Objects::nonNull)
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException("Avro schema not found!"));
        } else if(input.startsWith("s3://")) {
            avroSchema = S3Util.getAvroSchema(input, awsOptions);
            if(avroSchema != null) {
                return avroSchema;
            }
            return S3Util.listFiles(input, awsOptions)
                    .stream()
                    .map(path -> S3Util.getAvroSchema(path, awsOptions))
                    .filter(Objects::nonNull)
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException("Avro schema not found!"));
        } else {
            throw new IllegalArgumentException("Avro schema not found for input: " + input);
        }

    }

    private static org.apache.avro.Schema getParquetSchema(
            final String input,
            final SourceConfig.InputSchema inputSchema,
            final PipelineOptions options) {

        final AwsOptions awsOptions = options.as(AwsOptions.class);

        org.apache.avro.Schema avroSchema = getAvroSchema(inputSchema, awsOptions);
        if(avroSchema != null) {
            return avroSchema;
        }

        if(input.startsWith("gs://")) {
            avroSchema = StorageUtil.getParquetSchema(input);
            if(avroSchema != null) {
                return avroSchema;
            }
            return StorageUtil.listFiles(input)
                    .stream()
                    .map(StorageUtil::getParquetSchema)
                    .filter(Objects::nonNull)
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException("Avro schema not found!"));
        } else if(input.startsWith("s3://")) {
            avroSchema = S3Util.getParquetSchema(input, awsOptions);
            if(avroSchema != null) {
                return avroSchema;
            }
            return S3Util.listFiles(input, awsOptions)
                    .stream()
                    .map(path -> S3Util.getParquetSchema(path, awsOptions))
                    .filter(Objects::nonNull)
                    .findAny()
                    .orElseThrow(() -> new IllegalStateException("Avro schema not found!"));
        } else {
            throw new IllegalArgumentException("Avro schema not found for input: " + input);
        }

    }

}
