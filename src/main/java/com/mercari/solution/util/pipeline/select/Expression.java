package com.mercari.solution.util.pipeline.select;

import com.google.gson.JsonObject;
import com.mercari.solution.util.domain.math.ExpressionUtil;
import org.apache.beam.sdk.schemas.Schema;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Expression implements SelectFunction {

    private final String name;
    private final String expressionString;
    private final Set<String> expressionVariables;

    private final Schema.FieldType outputFieldType;
    private final boolean ignore;

    private transient net.objecthunter.exp4j.Expression expression;

    Expression(String name, String expressionString, Set<String> expressionVariables, boolean ignore) {
        this.name = name;
        this.expressionString = expressionString;
        this.expressionVariables = expressionVariables;

        this.outputFieldType = Schema.FieldType.DOUBLE.withNullable(true);
        this.ignore = ignore;
    }

    public static Expression of(String name, JsonObject jsonObject, boolean ignore) {
        if(!jsonObject.has("expression")) {
            throw new IllegalArgumentException("SelectField expression: " + name + " requires expression parameter");
        }
        final String expression = jsonObject.get("expression").getAsString();
        final Set<String> expressionVariables = ExpressionUtil.estimateVariables(expression);
        return new Expression(name, expression, expressionVariables, ignore);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean ignore() {
        return ignore;
    }

    @Override
    public Schema.FieldType getOutputFieldType() {
        return outputFieldType;
    }

    @Override
    public void setup() {
        this.expression = ExpressionUtil.createDefaultExpression(expressionString, expressionVariables);
    }

    @Override
    public Object apply(Map<String, Object> input) {
        final Map<String, Double> values = new HashMap<>();
        for(final String variableName : expressionVariables) {
            final Object variable = input.get(variableName);
            final Double value = ExpressionUtil.getAsDouble(variable);
            values.put(variableName, value);
        }
        return this.expression.setVariables(values).evaluate();
    }

}