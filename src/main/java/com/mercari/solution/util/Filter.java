package com.mercari.solution.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.joda.time.Instant;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

public class Filter implements Serializable {

    public enum Type implements Serializable {
        AND,
        OR,
        TRUE,
        FALSE
    }

    public enum Op implements Serializable {
        EQUAL("="),
        NOT_EQUAL("!="),
        GREATER(">"),
        GREATER_OR_EQUAL(">="),
        LESSER("<"),
        LESSER_OR_EQUAL("<="),
        IN("in"),
        NOT_IN("not in"),
        TRUE("true"),
        FALSE("false");

        private String name;

        Op(final String name) {
            this.name = name;
        }

        public static Op of(final String name) {
            for(final Op op : values()) {
                if(op.name.equals(name.trim().toLowerCase())) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Filter.Op: " + name + " not found.");
        }
    }

    public static class ConditionNode implements Serializable {

        private Type type;
        private List<ConditionNode> nodes;
        private List<ConditionLeaf> leaves;

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public List<ConditionNode> getNodes() {
            return nodes;
        }

        public void setNodes(List<ConditionNode> nodes) {
            this.nodes = nodes;
        }

        public List<ConditionLeaf> getLeaves() {
            return leaves;
        }

        public void setLeaves(List<ConditionLeaf> leaves) {
            this.leaves = leaves;
        }

        @Override
        public String toString() {
            return String.format("Type: %s, Leaves: %s", this.type, Optional.ofNullable(this.leaves).orElse(new ArrayList<>())
                    .stream()
                    .map(s -> String.format("%s %s %s", s.key, s.op, s.value))
                    .collect(Collectors.joining(" .")));
        }

    }

    public static class ConditionLeaf implements Serializable {

        private String key;
        private Op op;
        private JsonElement value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Op getOp() {
            return op;
        }

        public void setOp(Op op) {
            this.op = op;
        }

        public JsonElement getValue() {
            return value;
        }

        public void setValue(JsonElement value) {
            this.value = value;
        }

    }

    public static ConditionNode parse(final JsonElement jsonElement) {
        if(jsonElement == null || jsonElement.isJsonNull()) {
            final ConditionNode node = new ConditionNode();
            node.setType(Type.TRUE);
            return node;
        }

        if(jsonElement.isJsonPrimitive()) {
            throw new IllegalArgumentException("Illegal condition json: " + jsonElement.toString());
        }

        if(jsonElement.isJsonObject()) {
            return parse(jsonElement.getAsJsonObject());
        } else if(jsonElement.isJsonArray()) {
            final List<ConditionLeaf> leaves = new ArrayList<>();
            for(JsonElement child : jsonElement.getAsJsonArray()) {
                if(!child.isJsonObject()) {
                    throw new IllegalArgumentException("Simple conditions must be jsonObject. json: " + child.toString());
                }
                final ConditionLeaf leaf = createLeaf(child.getAsJsonObject());
                leaves.add(leaf);
            }
            ConditionNode node = new ConditionNode();
            node.setType(Type.AND);
            node.setLeaves(leaves);
            return node;
        } else {
            throw new IllegalArgumentException();
        }
    }


    public static ConditionNode parse(final JsonObject jsonObject) {
        if(!jsonObject.has("and") && !jsonObject.has("or")) {
            final List<ConditionLeaf> leaves = new ArrayList<>();
            final ConditionLeaf leaf = createLeaf(jsonObject);
            leaves.add(leaf);
            ConditionNode node = new ConditionNode();
            node.setType(Type.AND);
            node.setLeaves(leaves);
            return node;
        } else if(jsonObject.has("and") && jsonObject.has("or")) {
            throw new IllegalArgumentException("Condition must contain only one of `and` or `or`. Condition json: " + jsonObject.toString());
        }

        final Type type = jsonObject.has("and") ? Type.AND : Type.OR;
        final JsonElement conditions = jsonObject.has("and") ? jsonObject.get("and") : jsonObject.get("or");
        if(!conditions.isJsonArray()) {
            throw new IllegalArgumentException("Condition `and`, `or` parameter must be array. Condition json: " + conditions.toString());
        }
        final List<ConditionNode> nodes = new ArrayList<>();
        final List<ConditionLeaf> leaves = new ArrayList<>();
        for(final JsonElement condition : conditions.getAsJsonArray()) {
            final JsonObject child = condition.getAsJsonObject();
            if(child.has("and") || child.has("or")) {
                final ConditionNode node = parse(child);
                nodes.add(node);
            } else {
                final ConditionLeaf leaf = createLeaf(child);
                leaves.add(leaf);
            }
        }

        final ConditionNode node = new ConditionNode();
        node.setType(type);
        node.setNodes(nodes);
        node.setLeaves(leaves);
        return node;
    }

    public static <T> boolean filter(final T element, final Getter<T> getter, final ConditionNode condition) {
        final List<Boolean> bits = new ArrayList<>();

        if(condition.getLeaves() != null && condition.getLeaves().size() > 0) {
            for(ConditionLeaf leaf : condition.getLeaves()) {
                final Object value = getter.getValue(element, leaf.getKey());
                bits.add(is(value, leaf));
            }
        }
        if(condition.getNodes() != null && condition.getNodes().size() > 0) {
            for(ConditionNode node : condition.getNodes()) {
                bits.add(filter(element, getter, node));
            }
        }

        if(bits.size() == 0) {
            return false;
        }

        return is(condition.getType(), bits);
    }

    public static <T> boolean filter(final T element, final Getter<T> getter, final ConditionNode condition, final Map<String, Object> values) {
        final List<Boolean> bits = new ArrayList<>();

        if(condition.getLeaves() != null && condition.getLeaves().size() > 0) {
            for(ConditionLeaf leaf : condition.getLeaves()) {
                final Object value = Optional.ofNullable(values.get(leaf.getKey())).orElseGet(() -> getter.getValue(element, leaf.getKey()));
                bits.add(is(value, leaf));
            }
        }
        if(condition.getNodes() != null && condition.getNodes().size() > 0) {
            for(ConditionNode node : condition.getNodes()) {
                bits.add(filter(element, getter, node, values));
            }
        }

        if(bits.size() == 0) {
            return false;
        }

        return is(condition.getType(), bits);
    }

    public static boolean filter(final ConditionNode condition, final Map<String, Object> values) {
        final List<Boolean> bits = new ArrayList<>();

        if(condition.getLeaves() != null && condition.getLeaves().size() > 0) {
            for(ConditionLeaf leaf : condition.getLeaves()) {
                final Object value = Optional.ofNullable(values.get(leaf.getKey())).orElse(null);
                bits.add(is(value, leaf));
            }
        }
        if(condition.getNodes() != null && condition.getNodes().size() > 0) {
            for(ConditionNode node : condition.getNodes()) {
                bits.add(filter(node, values));
            }
        }

        if(bits.size() == 0) {
            return false;
        }

        return is(condition.getType(), bits);
    }

    public static boolean is(final Type type, final Collection<Boolean> bits) {
        if(type.equals(Type.AND)) {
            return bits.stream().allMatch(v -> v);
        } else if(type.equals(Type.OR)) {
            return bits.stream().anyMatch(v -> v);
        } else {
            return type.equals(Type.TRUE);
        }
    }

    public static boolean is(final Object value, final ConditionLeaf leaf) {
        if(value == null) {
            if(leaf.getValue() == null || leaf.getValue().isJsonNull()) {
                return leaf.getOp().equals(Op.EQUAL);
            }
            return false;
        } else if(leaf.getValue() == null || leaf.getValue().isJsonNull()) {
            return leaf.getOp().equals(Op.NOT_EQUAL);
        }

        if(leaf.getOp().equals(Op.IN) || leaf.getOp().equals(Op.NOT_IN)) {
            if(!leaf.getValue().isJsonArray()) {
                throw new IllegalArgumentException("Condition `in` or `not in` value must be array. json: " + leaf.getValue().toString());
            }
            for(final JsonElement e : leaf.getValue().getAsJsonArray()) {
                if(value.toString().equals(e.getAsString())) {
                    return leaf.getOp().equals(Op.IN);
                }
            }
            return leaf.getOp().equals(Op.NOT_IN);
        } else {
            final int c;

            if(value instanceof Byte
                    || value instanceof BigInteger
                    || value instanceof BigDecimal) {

                c = new BigDecimal(value.toString()).compareTo(leaf.getValue().getAsBigDecimal());
            } else if(value instanceof Short) {
                c = ((Short)value).compareTo(leaf.getValue().getAsShort());
            } else if(value instanceof Integer) {
                c = ((Integer)value).compareTo(leaf.getValue().getAsInt());
            } else if(value instanceof Long) {
                c = ((Long)value).compareTo(leaf.getValue().getAsLong());
            } else if(value instanceof Float) {
                c = ((Float)value).compareTo(leaf.getValue().getAsFloat());
            } else if(value instanceof Double) {
                c = ((Double)value).compareTo(leaf.getValue().getAsDouble());
            } else if(value instanceof String) {
                c = ((String)value).compareTo(leaf.getValue().getAsString());
            } else if(value instanceof Instant) {
                c = ((Instant)value).compareTo(DateTimeUtil.toJodaInstant(leaf.getValue().getAsString()));
            } else if(value instanceof LocalDate) {
                c = ((LocalDate)value).compareTo(DateTimeUtil.toLocalDate(leaf.getValue().getAsString()));
            } else if(value instanceof LocalTime) {
                c = ((LocalTime)value).compareTo(DateTimeUtil.toLocalTime(leaf.getValue().getAsString()));
            } else if(value instanceof org.apache.avro.util.Utf8) {
                c = ((org.apache.avro.util.Utf8)value).toString().compareTo(leaf.getValue().getAsString());
            } else {
                c = (value).toString().compareTo(leaf.getValue().getAsString());
                //throw new IllegalArgumentException("Condition compare op must be Number or String. : " + value.getClass());
            }

            switch (leaf.getOp()) {
                case EQUAL:
                    return c == 0;
                case NOT_EQUAL:
                    return c != 0;
                case GREATER:
                    return c > 0;
                case GREATER_OR_EQUAL:
                    return c >= 0;
                case LESSER:
                    return c < 0;
                case LESSER_OR_EQUAL:
                    return c <= 0;
                case TRUE:
                    return true;
                case FALSE:
                    return false;
                default:
                    throw new IllegalArgumentException("");
            }
        }
    }

    private static ConditionLeaf createLeaf(final JsonObject jsonObject) {
        if(!jsonObject.has("key") || !jsonObject.has("op") || !jsonObject.has("value")) {
            throw new IllegalArgumentException("Simple conditions must contain `key`,`op`,`value`. json: " + jsonObject);
        }
        final ConditionLeaf leaf = new ConditionLeaf();
        leaf.setKey(jsonObject.get("key").getAsString());
        leaf.setOp(Op.of(jsonObject.get("op").getAsString()));
        leaf.setValue(jsonObject.get("value"));
        return leaf;
    }

    public interface Getter<T> extends Serializable {
        Object getValue(final T value, final String field);
    }

}
