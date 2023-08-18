package com.mercari.solution.util.schema;

import com.google.cloud.Date;
import com.google.firestore.v1.ArrayValue;
import com.google.firestore.v1.MapValue;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Document;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import com.mercari.solution.util.DateTimeUtil;
import org.apache.beam.sdk.schemas.Schema;
import org.joda.time.Instant;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;


public class DocumentSchemaUtil {

    public static Document.Builder toBuilder(final Schema schema, final Document document) {
        final Document.Builder builder = toBuilder(schema, document.getFieldsMap());
        builder.setName(document.getName());
        return builder;
    }

    public static Document.Builder toBuilder(final Schema schema, final MapValue mapValue) {
        return toBuilder(schema, mapValue.getFieldsMap());
    }

    public static Document.Builder toBuilder(final Schema schema, final Map<String, Value> values) {
        final Document.Builder builder = Document.newBuilder();
        for(final Schema.Field field : schema.getFields()) {
            if(values.containsKey(field.getName())) {
                builder.putFields(field.getName(), values.get(field.getName()));
            } else {
                builder.putFields(field.getName(), Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
            }
        }
        return builder;
    }

    public static Document.Builder toBuilder(final Schema schema, final Document entity, final Map<String, String> renameFields) {
        final Document.Builder builder = Document.newBuilder();
        builder.setName(entity.getName());
        final Map<String, Value> values = entity.getFieldsMap();
        for(final Schema.Field field : schema.getFields()) {
            final String getFieldName = renameFields.getOrDefault(field.getName(), field.getName());
            final String setFieldName = field.getName();
            if(values.containsKey(getFieldName)) {
                switch (field.getType().getTypeName()) {
                    case ITERABLE:
                    case ARRAY: {
                        if(field.getType().getCollectionElementType().getTypeName().equals(Schema.TypeName.ROW)) {
                            final List<Value> children = new ArrayList<>();
                            for(final Value child : values.get(getFieldName).getArrayValue().getValuesList()) {
                                if(!Value.ValueTypeCase.MAP_VALUE.equals(child.getValueTypeCase())) {
                                    children.add(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
                                } else {
                                    final Document.Builder document = toBuilder(field.getType().getCollectionElementType().getRowSchema(), child.getMapValue());
                                    final MapValue mapValue = MapValue.newBuilder().putAllFields(document.getFieldsMap()).build();
                                    children.add(Value.newBuilder().setMapValue(mapValue).build());
                                }
                            }
                            builder.putFields(field.getName(), Value.newBuilder()
                                    .setArrayValue(ArrayValue.newBuilder().addAllValues(children))
                                    .build());
                        } else {
                            builder.putFields(field.getName(), values.get(getFieldName));
                        }
                        break;
                    }
                    case ROW: {
                        final Document child = toBuilder(field.getType().getRowSchema(), values.get(getFieldName).getMapValue()).build();
                        builder.putFields(field.getName(), Value.newBuilder().setMapValue(MapValue.newBuilder().putAllFields(child.getFieldsMap())).build());
                        break;
                    }
                    default:
                        builder.putFields(field.getName(), values.get(getFieldName));
                        break;
                }
            } else if(renameFields.containsValue(setFieldName)) {
                final String getOuterFieldName = renameFields.entrySet().stream()
                        .filter(e -> e.getValue().equals(setFieldName))
                        .map(Map.Entry::getKey)
                        .findAny()
                        .orElse(setFieldName);
                if(!values.containsKey(getOuterFieldName) || values.get(getOuterFieldName) == null) {
                    builder.putFields(field.getName(), Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
                    continue;
                }

                switch (field.getType().getTypeName()) {
                    case ITERABLE:
                    case ARRAY: {
                        if(field.getType().getCollectionElementType().getTypeName().equals(Schema.TypeName.ROW)) {
                            final List<Value> children = new ArrayList<>();
                            for(final Value child : values.get(getOuterFieldName).getArrayValue().getValuesList()) {
                                if(child != null && child.getNullValue() != null && child.getMapValue() != null) {
                                    Document.Builder childBuilder = toBuilder(field.getType().getCollectionElementType().getRowSchema(), child.getMapValue());
                                    children.add(Value.newBuilder().setMapValue(MapValue.newBuilder().putAllFields(childBuilder.getFieldsMap())).build());
                                }
                            }
                            builder.putFields(setFieldName, Value.newBuilder().setArrayValue(ArrayValue.newBuilder().addAllValues(children).build()).build());
                        } else {
                            builder.putFields(setFieldName, values.get(getOuterFieldName));
                        }
                        break;
                    }
                    case ROW: {
                        final Document child = toBuilder(field.getType().getRowSchema(), values.get(getOuterFieldName).getMapValue()).build();
                        builder.putFields(setFieldName, Value.newBuilder().setMapValue(MapValue.newBuilder().putAllFields(child.getFieldsMap())).build());
                        break;
                    }
                    default:
                        builder.putFields(setFieldName, values.get(getOuterFieldName));
                        break;
                }
            } else {
                builder.putFields(field.getName(), Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
            }
        }
        return builder;
    }

    public static Document convert(final Schema schema, final Document document) {
        return document;
    }

    public static String getAsString(final Value value) {
        final Object object = getValue(value);
        if(object == null) {
            return null;
        }
        return object.toString();
    }
    public static String getAsString(final Object document, final String fieldName) {
        if(document == null) {
            return null;
        }
        return getAsString((Document) document, fieldName);
    }

    public static String getAsString(final Document document, final String fieldName) {
        if(document == null || fieldName == null) {
            return null;
        }
        if(!document.containsFields(fieldName)) {
            return null;
        }

        final Value value = document.getFieldsOrThrow(fieldName);
        switch(value.getValueTypeCase()) {
            case REFERENCE_VALUE:
            case STRING_VALUE:
                return value.getStringValue();
            case BYTES_VALUE:
                return Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray());
            case INTEGER_VALUE:
                return Long.toString(value.getIntegerValue());
            case DOUBLE_VALUE:
                return Double.toString(value.getDoubleValue());
            case BOOLEAN_VALUE:
                return Boolean.toString(value.getBooleanValue());
            case TIMESTAMP_VALUE:
                return Instant.ofEpochMilli(Timestamps.toMillis(value.getTimestampValue())).toString();
            case GEO_POINT_VALUE:
            case MAP_VALUE:
            case ARRAY_VALUE:
            case VALUETYPE_NOT_SET:
            case NULL_VALUE:
            default:
                return null;
        }
    }

    public static Double getAsDouble(final Document document, final String fieldName) {
        if(!document.getFieldsMap().containsKey(fieldName)) {
            return null;
        }
        final Value value = document.getFieldsOrThrow(fieldName);
        switch(value.getValueTypeCase()) {
            case BOOLEAN_VALUE:
                return value.getBooleanValue() ? 1D : 0D;
            case INTEGER_VALUE:
                return Long.valueOf(value.getIntegerValue()).doubleValue();
            case DOUBLE_VALUE:
                return value.getDoubleValue();
            case STRING_VALUE: {
                try {
                    return Double.valueOf(value.getStringValue());
                } catch (Exception e) {
                    return null;
                }
            }
            case TIMESTAMP_VALUE:
            case GEO_POINT_VALUE:
            case REFERENCE_VALUE:
            case MAP_VALUE:
            case BYTES_VALUE:
            case ARRAY_VALUE:
            case VALUETYPE_NOT_SET:
            case NULL_VALUE:
            default:
                return null;
        }
    }

    public static byte[] getBytes(final Document document, final String fieldName) {
        if(document == null || fieldName == null) {
            return null;
        }
        if(!document.containsFields(fieldName)) {
            return null;
        }

        final Value value = document.getFieldsOrThrow(fieldName);
        switch(value.getValueTypeCase()) {
            case REFERENCE_VALUE:
            case STRING_VALUE:
                return Base64.getDecoder().decode(value.getStringValue());
            case BYTES_VALUE:
                return value.getBytesValue().toByteArray();
            case INTEGER_VALUE:
            case DOUBLE_VALUE:
            case BOOLEAN_VALUE:
            case TIMESTAMP_VALUE:
            case GEO_POINT_VALUE:
            case MAP_VALUE:
            case ARRAY_VALUE:
            case NULL_VALUE:
            case VALUETYPE_NOT_SET:
            default:
                return null;
        }

    }

    public static List<Float> getAsFloatList(final Document document, final String fieldName) {
        if(document == null || fieldName == null) {
            return new ArrayList<>();
        }
        if(!document.containsFields(fieldName)) {
            return new ArrayList<>();
        }

        final Value value = document.getFieldsOrThrow(fieldName);
        switch (value.getValueTypeCase()) {
            case ARRAY_VALUE: {
                return value.getArrayValue().getValuesList().stream().map(v -> {
                    switch (v.getValueTypeCase()) {
                        case DOUBLE_VALUE:
                            return Double.valueOf(v.getDoubleValue()).floatValue();
                        case INTEGER_VALUE:
                            return Long.valueOf(v.getIntegerValue()).floatValue();
                        case STRING_VALUE:
                            return Float.valueOf(v.getStringValue());
                        case BOOLEAN_VALUE:
                            return v.getBooleanValue() ? 1F :  0F;
                        default:
                            return null;
                    }
                }).collect(Collectors.toList());
            }
            default:
                return new ArrayList<>();
        }
    }

    public static Object getValue(final Document document, final String fieldName) {
        if(document == null || fieldName == null) {
            return null;
        }
        if(!document.containsFields(fieldName)) {
            return null;
        }
        final Value value = document.getFieldsOrThrow(fieldName);
        return getValue(value);
    }

    public static Object getValue(final Value value) {
        switch(value.getValueTypeCase()) {
            case STRING_VALUE: return value.getStringValue();
            case BYTES_VALUE: return value.getBytesValue().toByteArray();
            case INTEGER_VALUE: return value.getIntegerValue();
            case DOUBLE_VALUE: return value.getDoubleValue();
            case BOOLEAN_VALUE: return value.getBooleanValue();
            case TIMESTAMP_VALUE: return Instant.ofEpochMilli(Timestamps.toMillis(value.getTimestampValue()));
            case MAP_VALUE: return value.getMapValue();
            case ARRAY_VALUE: {
                return value.getArrayValue().getValuesList()
                        .stream()
                        .map(v -> {
                            if(v == null) {
                                return null;
                            }
                            switch (v.getValueTypeCase()) {
                                case BOOLEAN_VALUE:
                                    return v.getBooleanValue();
                                case INTEGER_VALUE:
                                    return v.getIntegerValue();
                                case BYTES_VALUE:
                                    return v.getBytesValue().toByteArray();
                                case STRING_VALUE:
                                    return v.getStringValue();
                                case DOUBLE_VALUE:
                                    return v.getDoubleValue();
                                case GEO_POINT_VALUE:
                                    return v.getGeoPointValue();
                                case TIMESTAMP_VALUE:
                                    return Instant.ofEpochMilli(Timestamps.toMillis(v.getTimestampValue()));
                                case MAP_VALUE:
                                    return v.getMapValue();
                                case ARRAY_VALUE:
                                case NULL_VALUE:
                                case VALUETYPE_NOT_SET:
                                default:
                                    return null;
                            }
                        })
                        .collect(Collectors.toList());
            }
            case GEO_POINT_VALUE:
                return value.getGeoPointValue();
            case REFERENCE_VALUE:
            case VALUETYPE_NOT_SET:
            case NULL_VALUE:
                return null;
            default:
                throw new IllegalArgumentException(String.format("%s is not supported!", value.getValueTypeCase().name()));
        }
    }

    public static Object getAsPrimitive(Object object, Schema.FieldType fieldType, String field) {
        if(object == null) {
            return null;
        }
        if(!(object instanceof Document)) {
            return null;
        }
        final Document document = (Document) object;
        final Value value = document.getFieldsOrDefault(field, Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
        switch (fieldType.getTypeName()) {
            case INT32:
                return Long.valueOf(value.getIntegerValue()).intValue();
            case INT64:
                return value.getIntegerValue();
            case FLOAT:
                return Double.valueOf(value.getDoubleValue()).floatValue();
            case DOUBLE:
                return value.getDoubleValue();
            case BOOLEAN:
                return value.getBooleanValue();
            case STRING:
                return value.getStringValue();
            case DATETIME:
                return DateTimeUtil.toEpochMicroSecond(value.getTimestampValue());
            case LOGICAL_TYPE: {
                if(RowSchemaUtil.isLogicalTypeDate(fieldType)) {
                    switch (value.getValueTypeCase()) {
                        case STRING_VALUE:
                            return Long.valueOf(DateTimeUtil.toLocalDate(value.getStringValue()).toEpochDay()).intValue();
                        case INTEGER_VALUE:
                            return Long.valueOf(value.getIntegerValue()).intValue();
                        case NULL_VALUE:
                        case VALUETYPE_NOT_SET:
                            return null;
                        default:
                            throw new IllegalStateException();
                    }
                } else if(RowSchemaUtil.isLogicalTypeTime(fieldType)) {
                    switch (value.getValueTypeCase()) {
                        case STRING_VALUE:
                            return Long.valueOf(DateTimeUtil.toLocalTime(value.getStringValue()).toSecondOfDay()).intValue();
                        case INTEGER_VALUE:
                            return Long.valueOf(value.getIntegerValue()).intValue();
                        case NULL_VALUE:
                        case VALUETYPE_NOT_SET:
                            return null;
                        default:
                            throw new IllegalStateException();
                    }
                } else if(RowSchemaUtil.isLogicalTypeEnum(fieldType)) {
                    return value.getStringValue();
                } else {
                    throw new IllegalStateException();
                }
            }
            case ITERABLE:
            case ARRAY: {
                switch (fieldType.getCollectionElementType().getTypeName()) {
                    case INT32:
                    case INT64:
                    case FLOAT:
                    case DOUBLE:
                    case BOOLEAN:
                    case STRING:
                    case DATETIME:
                    case LOGICAL_TYPE:
                    case ITERABLE:
                    case ARRAY:
                    case ROW:
                    case BYTES:
                    case MAP:
                    case BYTE:
                    case DECIMAL:
                    default:
                        throw new IllegalStateException();
                }
            }
            default:
                throw new IllegalStateException();
        }
    }

    public static Object convertPrimitive(Schema.FieldType fieldType, Object primitiveValue) {
        return null;
    }

    public static Document merge(final Schema schema, Document document, final Map<String, ? extends Object> values) {
        Document.Builder builder = Document.newBuilder(document);
        for(final Schema.Field field : schema.getFields()) {
            final Object object = values.get(field.getName());
            final Value value =  toValue(field.getType(), object);
            builder = builder.putFields(field.getName(), value);
        }
        return builder.build();
    }

    public static Document create(final Schema schema, final Map<String, Object> values) {
        final Document.Builder builder = Document.newBuilder();
        for(final Schema.Field field : schema.getFields()) {
            final Value value;
            if(!values.containsKey(field.getName()) || values.get(field.getName()) == null) {
                value = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
            } else {
                final Object object = values.get(field.getName());
                switch (field.getType().getTypeName()) {
                    case BOOLEAN:
                        value = Value.newBuilder().setBooleanValue((Boolean)object).build();
                        break;
                    case STRING:
                        value = Value.newBuilder().setStringValue(object.toString()).build();
                        break;
                    case BYTES:
                        value = Value.newBuilder().setBytesValue(ByteString.copyFrom((byte[]) object)).build();
                        break;
                    case INT32:
                        value = Value.newBuilder().setIntegerValue((Integer) object).build();
                        break;
                    case INT64:
                        value = Value.newBuilder().setIntegerValue((Long) object).build();
                        break;
                    case FLOAT:
                        value = Value.newBuilder().setDoubleValue((Float) object).build();
                        break;
                    case DOUBLE:
                        value = Value.newBuilder().setDoubleValue((Double) object).build();
                        break;
                    case DECIMAL:
                        value = Value.newBuilder().setStringValue(object.toString()).build();
                        break;
                    case DATETIME:
                        value = Value.newBuilder().setTimestampValue(DateTimeUtil.toProtoTimestamp((Long) object)).build();
                        break;
                    case LOGICAL_TYPE: {
                        if(RowSchemaUtil.isLogicalTypeDate(field.getType())) {
                            value = Value.newBuilder().setStringValue(LocalDate.ofEpochDay((Integer) object).toString()).build();
                        } else if(RowSchemaUtil.isLogicalTypeTime(field.getType())) {
                            value = Value.newBuilder().setStringValue(LocalTime.ofNanoOfDay(((Long) object) / 1000L).toString()).build();
                        } else if(RowSchemaUtil.isLogicalTypeEnum(field.getType())) {
                            value = Value.newBuilder().setStringValue(object.toString()).build();
                        } else {
                            throw new IllegalStateException();
                        }
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Not supported type: " + field.getName() + ", type: " + field.getType());
                    }
                }
            }
            builder.putFields(field.getName(), value);
        }
        return builder.build();
    }

    private static Value toValue(Schema.FieldType fieldType, Object object) {
        if(object == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }

        switch (fieldType.getTypeName()) {
            case BOOLEAN:
                return Value.newBuilder().setBooleanValue((Boolean)object).build();
            case STRING:
                return Value.newBuilder().setStringValue(object.toString()).build();
            case BYTES:
                return Value.newBuilder().setBytesValue(ByteString.copyFrom((byte[]) object)).build();
            case BYTE:
                return Value.newBuilder().setIntegerValue((Byte) object).build();
            case INT16:
                return Value.newBuilder().setIntegerValue((Short) object).build();
            case INT32:
                return Value.newBuilder().setIntegerValue((Integer) object).build();
            case INT64:
                return Value.newBuilder().setIntegerValue((Long) object).build();
            case FLOAT:
                return Value.newBuilder().setDoubleValue((Float) object).build();
            case DOUBLE:
                return Value.newBuilder().setDoubleValue((Double) object).build();
            case DATETIME: {
                if(object instanceof Timestamp) {
                    return Value.newBuilder().setTimestampValue(((Timestamp) object)).build();
                } else if(object instanceof Long) {
                    return Value.newBuilder().setTimestampValue(DateTimeUtil.toProtoTimestamp((Long) object)).build();
                } else if(object instanceof Instant) {
                    return Value.newBuilder().setTimestampValue(DateTimeUtil.toProtoTimestamp((Instant) object)).build();
                } else if(object instanceof String) {
                    final Instant instant = DateTimeUtil.toJodaInstant((String) object);
                    return Value.newBuilder().setTimestampValue(DateTimeUtil.toProtoTimestamp(instant)).build();
                } else {
                    throw new IllegalStateException();
                }
            }
            case DECIMAL:
                return Value.newBuilder().setStringValue(object.toString()).build();
            case ROW: {
                final Map<String, Value> childValues = new HashMap<>();
                final Map<String, Object> child = (Map<String, Object>) object;
                final Schema childSchema = fieldType.getCollectionElementType().getRowSchema();
                for(final Map.Entry<String, Object> entry : child.entrySet()) {
                    final Schema.FieldType childFieldType = childSchema.getField(entry.getKey()).getType();
                    final Value fieldValue = toValue(childFieldType, entry.getValue());
                    childValues.put(entry.getKey(), fieldValue);
                }
                return Value.newBuilder().setMapValue(MapValue.newBuilder().putAllFields(childValues).build()).build();
            }
            case ITERABLE:
            case ARRAY:
            default: {
                throw new IllegalArgumentException("Not supported type: " + fieldType + ", value: " + object);
            }
        }
    }

    public static Date convertDate(final Value value) {
        if(Value.ValueTypeCase.STRING_VALUE.equals(value.getValueTypeCase())) {
            final String datestr = value.getStringValue();
            final LocalDate localDate = DateTimeUtil.toLocalDate(datestr);
            return Date.fromYearMonthDay(localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
        } else if(Value.ValueTypeCase.INTEGER_VALUE.equals(value.getValueTypeCase())) {
            final LocalDate localDate = LocalDate.ofEpochDay(value.getIntegerValue());
            return Date.fromYearMonthDay(localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
        } else {
            throw new IllegalArgumentException();
        }

    }

}
