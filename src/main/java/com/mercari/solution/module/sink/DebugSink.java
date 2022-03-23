package com.mercari.solution.module.sink;

import com.google.cloud.spanner.Struct;
import com.google.datastore.v1.Entity;
import com.google.gson.Gson;
import com.mercari.solution.config.SinkConfig;
import com.mercari.solution.module.FCollection;
import com.mercari.solution.module.SinkModule;
import com.mercari.solution.util.TemplateUtil;
import com.mercari.solution.util.converter.*;
import freemarker.template.Template;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class DebugSink  implements SinkModule {

    private static final Logger LOG = LoggerFactory.getLogger(DebugSink.class);

    private class DebugSinkParameters implements Serializable {

        private LogLevel logLevel;
        private String logTemplate;

        public LogLevel getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(LogLevel logLevel) {
            this.logLevel = logLevel;
        }

        public String getLogTemplate() {
            return logTemplate;
        }

        public void setLogTemplate(String logTemplate) {
            this.logTemplate = logTemplate;
        }
    }

    private enum LogLevel implements Serializable {
        trace,
        debug,
        info,
        warn,
        error
    }

    @Override
    public String getName() { return "debug"; }

    @Override
    public Map<String, FCollection<?>> expand(FCollection<?> input, SinkConfig config, List<FCollection<?>> waits, List<FCollection<?>> sideInputs) {
        return Collections.singletonMap(config.getName(), write(input, config, waits, sideInputs));
    }

    private FCollection<?> write(final FCollection<?> input, final SinkConfig config, final List<FCollection<?>> waits, final List<FCollection<?>> sideInputs) {
        final DebugSinkParameters parameters = new Gson().fromJson(config.getParameters(), DebugSinkParameters.class);
        setDefaultParameters(parameters);

        switch (input.getDataType()) {
            case AVRO: {
                final FCollection<GenericRecord> inputCollection = (FCollection<GenericRecord>) input;
                final PCollection output = inputCollection.getCollection()
                        .apply(config.getName(), ParDo.of(new DebugDoFn<>(
                                config.getName(),
                                parameters.getLogTemplate(),
                                parameters.getLogLevel(),
                                RecordToJsonConverter::convert)));
                return FCollection.update(input, output);
            }
            case ROW: {
                final FCollection<Row> inputCollection = (FCollection<Row>) input;
                final PCollection output = inputCollection.getCollection()
                        .apply(config.getName(), ParDo.of(new DebugDoFn<>(
                                config.getName(),
                                parameters.getLogTemplate(),
                                parameters.getLogLevel(),
                                RowToJsonConverter::convert)));
                return FCollection.update(input, output);
            }
            case STRUCT: {
                final FCollection<Struct> inputCollection = (FCollection<Struct>) input;
                final PCollection output = inputCollection.getCollection()
                        .apply(config.getName(), ParDo.of(new DebugDoFn<>(
                                config.getName(),
                                parameters.getLogTemplate(),
                                parameters.getLogLevel(),
                                StructToJsonConverter::convert)));
                return FCollection.update(input, output);
            }
            case ENTITY: {
                final FCollection<Entity> inputCollection = (FCollection<Entity>) input;
                final PCollection output = inputCollection.getCollection()
                        .apply(config.getName(), ParDo.of(new DebugDoFn<>(
                                config.getName(),
                                parameters.getLogTemplate(),
                                parameters.getLogLevel(),
                                EntityToJsonConverter::convert)));
                return FCollection.update(input, output);
            }
            default: {
                throw new IllegalArgumentException("Debug sink not supported data type: " + input.getDataType());
            }
        }
    }

    private void setDefaultParameters(final DebugSinkParameters parameters) {
        if(parameters.getLogLevel() == null) {
            parameters.setLogLevel(LogLevel.debug);
        }
    }


    private static class DebugDoFn<T> extends DoFn<T, Void> {

        private final String name;
        private final String templateText;
        private final LogLevel logLevel;

        private final JsonConverter<T> converter;

        private transient Template template;

        DebugDoFn(final String name, final String templateText, final LogLevel logLevel, final JsonConverter<T> converter) {
            this.name = name;
            this.templateText = templateText;
            this.logLevel = logLevel;
            this.converter = converter;
        }

        @Setup
        public void setup() {
            if(templateText != null) {
                this.template = TemplateUtil.createSafeTemplate(name, templateText);
            } else {
                this.template = null;
            }
        }

        @ProcessElement
        public void processElement(final ProcessContext c, final BoundedWindow window) {
            final String text;
            final String json = converter.convert(c.element());
            if(this.template == null) {
                text = json;
            } else {
                final Map<String, Object> data = new HashMap<>();
                data.put("data", json);
                data.put("timestamp", c.timestamp().toString());

                data.put("paneTiming", c.pane().getTiming().toString());
                data.put("paneIsFirst", Boolean.valueOf(c.pane().isFirst()).toString());
                data.put("paneIsLast", Boolean.valueOf(c.pane().isLast()).toString());
                if (!c.pane().isFirst() || !c.pane().isLast()) {
                    data.put("paneIndex", c.pane().getIndex());
                } else {
                    data.put("paneIndex", -1);
                }

                data.put("windowMaxTimestamp", window.maxTimestamp().toString());
                if(window == GlobalWindow.INSTANCE) {
                    data.put("windowStart", "");
                    data.put("windowEnd", "");
                } else if(window instanceof IntervalWindow) {
                    final IntervalWindow iw = ((IntervalWindow)window);
                    data.put("windowStart", iw.start().toString());
                    data.put("windowEnd", iw.end().toString());
                } else {
                    data.put("windowStart", "");
                    data.put("windowEnd", "");
                }

                text = TemplateUtil.executeStrictTemplate(template, data);
            }
            switch (logLevel) {
                case trace:
                    LOG.trace(text);
                    break;
                case debug:
                    LOG.debug(text);
                    break;
                case info:
                    LOG.info(text);
                    break;
                case warn:
                    LOG.warn(text);
                    break;
                case error:
                    LOG.error(text);
                    break;
            }
        }

    }

    private interface JsonConverter<T> extends Serializable {
        String convert(final T value);
    }

}
