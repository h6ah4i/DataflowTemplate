package com.mercari.solution.util.pipeline.processing.processor.window;

import com.google.gson.JsonObject;
import com.mercari.solution.util.pipeline.processing.ProcessingBuffer;
import com.mercari.solution.util.pipeline.processing.ProcessingState;
import com.mercari.solution.util.pipeline.processing.processor.Processor;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.values.KV;
import org.apache.commons.lang.NotImplementedException;
import org.joda.time.Instant;

import java.util.*;

public class Avg extends WindowProcessor {

    private final Map<String, Integer> bufferSizes;

    public Avg(final String name, final String condition, final Boolean ignore, final JsonObject params) {

        super(name, condition, ignore, params);

        if(ranges.size() == 0) {
            throw new IllegalArgumentException("Avg step: " + name + " requires ranges over size zero");
        }

        this.bufferSizes = super.getBufferSizes();
    }

    public static Avg of(final String name, final String condition, final Boolean ignore, final JsonObject params) {
        return new Avg(name, condition, ignore, params);
    }

    @Override
    public Op getOp() {
        return Op.avg;
    }

    @Override
    public Integer getOffsetMax() {
        return rangeMax;
    }

    @Override
    public List<Schema.Field> getOutputFields(Map<String, Schema.FieldType> inputTypes) {
        final List<Schema.Field> outputFields = new ArrayList<>();
        if(fields.size() > 0) {
            for(final String field : fields) {
                for(final KV<Integer, Integer> range : ranges) {
                    final String outputFieldName = createOutputName(field, range);
                    outputFields.add(Schema.Field.of(outputFieldName, Schema.FieldType.DOUBLE).withNullable(true));
                }
            }
        } else if(expressions.size() > 0) {
            for(int no=0; no<expressions.size(); no++) {
                for(final KV<Integer, Integer> range : ranges) {
                    final String outputFieldName = createOutputName(no, range);
                    outputFields.add(Schema.Field.of(outputFieldName, Schema.FieldType.DOUBLE).withNullable(true));
                }
            }
        } else {
            throw new IllegalArgumentException();
        }
        return outputFields;
    }

    @Override
    public Map<String, Schema.FieldType> getBufferTypes(Map<String, Schema.FieldType> inputTypes) {
        final Map<String, Schema.FieldType> bufferTypes = new HashMap<>();
        if(this.fields.size() > 0) {
            for(final String field : fields) {
                final Schema.FieldType fieldType = inputTypes.get(field);
                if(fieldType == null) {
                    throw new IllegalArgumentException("Avg step: " + name + " input field: " + field + " not found");
                }
                bufferTypes.put(field, fieldType);
            }
        } else {
            for(final String variable : bufferSizes.keySet()) {
                final Schema.FieldType fieldType = inputTypes.get(variable);
                if(fieldType == null) {
                    throw new IllegalArgumentException("Avg step: " + name + " input variable: " + variable + " not found");
                }
                bufferTypes.put(variable, fieldType);
            }
        }
        return bufferTypes;
    }

    @Override
    public void setup() {
        super.setup();
    }

    @Override
    public Map<String, Object> process(ProcessingBuffer buffer, ProcessingState state, Instant timestamp) {

        final Map<String, Object> outputs = new HashMap<>();

        for(KV<Integer,Integer> range : ranges) {
            if(this.fields.size() > 0) {
                for(final String field : fields) {
                    int count = 0;
                    double sum = 0;
                    final double[] values = buffer.getColVector(field, range.getKey(), range.getValue() - range.getKey(), getSizeUnit());
                    for(double value : values) {
                        if(Double.isNaN(value)) {
                            continue;
                        }
                        count += 1;
                        sum += value;
                    }
                    final String outputName = createOutputName(field, range);
                    outputs.put(outputName, sum / count);
                }
            } else {
                for(int e=0; e<expressions.size(); e++) {
                    int count = 0;
                    double sum = 0;
                    final Set<String> variables = variablesList.get(e);
                    for(int r=range.getKey(); r<range.getValue(); r++) {
                        final Map<String, Double> values = new HashMap<>();
                        for(final String input : this.bufferSizes.keySet()) {
                            if(variables.contains(input)) {
                                final Double firstLagValue = buffer.getAsDouble(input, 0);
                                values.put(input, firstLagValue);
                            }
                            final int size = this.bufferSizes.get(input) - rangeMax + 1;
                            for(int variableIndex=0; variableIndex<size; variableIndex++) {
                                final int index = r + variableIndex;
                                final String variableName = Processor.createVariableName(input, DEFAULT_VARIABLE_NAME_SEPARATOR, variableIndex);
                                final Double variableValue = buffer.getAsDouble(input, index);
                                values.put(variableName, variableValue);
                            }
                        }

                        try {
                            final double output = expressionList.get(e).setVariables(values).evaluate();
                            if(!Double.isNaN(output) && !Double.isInfinite(output)) {
                                count += 1;
                                sum += output;
                            }
                        } catch (final NullPointerException | ArithmeticException ee) {
                            LOG.debug("Avg step: " + name + " failed evaluate expression: " + expressions.get(e) + " for variables: " + values + " cause: " + ee);
                        }
                    }
                    final String outputName = createOutputName(e, range);
                    outputs.put(outputName, sum / count);
                }

            }
        }

        return outputs;
    }

    @Override
    public Map<String, Object> process(Map<String, Object> input, Instant timestamp) {
        throw new NotImplementedException("");
    }

    @Override
    public List<Map<String, Object>> process(List<Map<String, Object>> inputs, Instant timestamp) {
        throw new NotImplementedException("");
    }

}
