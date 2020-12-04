/*
 * Copyright (c) Mercari, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.mercari.solution.util.converter;

import com.google.cloud.ByteArray;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Converter converts Cloud Spanner Struct to Json text row
 */
public class StructToJsonConverter {

    private static final String INTERNAL_USE_FIELD_PREFIX = "__";

    private StructToJsonConverter() {}

    public static String convert(final Struct struct, final List<String> fields) {
        return convert(struct);
    }

    /**
     * Convert Cloud Spanner {@link Struct} object to Json row string.
     *
     * @param struct Cloud Spanner Struct object
     * @return Json row string.
     */
    public static String convert(final Struct struct) {
        return convertStruct(struct).toString();
    }

    public static JsonObject convertStruct(final Struct struct) {
        JsonObject obj = new JsonObject();
        struct.getType().getStructFields().stream()
                .filter(f -> !f.getName().startsWith(INTERNAL_USE_FIELD_PREFIX))
                .forEach(f -> setJsonFieldValue(obj, f, struct));
        return obj;
    }

    private static JsonObject convertStructWithoutFields(final Struct struct, final Collection<String> ignoreFields) {
        final JsonObject obj = new JsonObject();
        struct.getType().getStructFields().stream()
                .filter(f -> !ignoreFields.contains(f.getName()))
                .forEach(f -> setJsonFieldValue(obj, f, struct));
        return obj;
    }

    private static void setJsonFieldValue(final JsonObject obj, final Type.StructField field, final Struct struct) {
        final String fieldName = field.getName();
        final boolean isNullField = struct.isNull(fieldName);
        switch (field.getType().getCode()) {
            case BOOL:
                obj.addProperty(fieldName, isNullField ? null : struct.getBoolean(fieldName));
                break;
            case INT64:
                obj.addProperty(fieldName, isNullField ? null : struct.getLong(fieldName));
                break;
            case FLOAT64:
                obj.addProperty(fieldName, isNullField ? null : struct.getDouble(fieldName));
                break;
            case STRING:
                obj.addProperty(fieldName, isNullField ? null : struct.getString(fieldName));
                break;
            case BYTES:
                //obj.addProperty(fieldName, isNullField ? null : new String(struct.getBytes(fieldName).toByteArray(), StandardCharsets.UTF_8));
                obj.addProperty(fieldName, isNullField ? null : struct.getBytes(fieldName).toBase64());
                //final String base64 = java.util.Base64.getEncoder().encodeToString(struct.getBytes(fieldName).toByteArray());
                //obj.addProperty(fieldName, isNullField ? null : base64);
                break;
            case TIMESTAMP:
                obj.addProperty(fieldName, isNullField ? null : struct.getTimestamp(fieldName).toString());
                break;
            case DATE:
                obj.addProperty(fieldName, isNullField ? null : struct.getDate(fieldName).toString());
                break;
            case STRUCT:
                if(isNullField) {
                    obj.add(field.getName(), null);
                    return;
                }
                Struct childStruct = struct.getStruct(fieldName);
                JsonObject childObj = new JsonObject();
                for(Type.StructField childField : childStruct.getType().getStructFields()) {
                    setJsonFieldValue(childObj, childField, childStruct);
                }
                obj.add(fieldName, childObj);
                break;
            case ARRAY:
                setJsonArrayFieldValue(obj, field, struct);
                break;
        }
    }

    private static void setJsonArrayFieldValue(final JsonObject obj, final Type.StructField field, final Struct struct) {
        if(struct.isNull(field.getName())) {
            obj.add(field.getName(), null);
            return;
        }
        final JsonArray array = new JsonArray();
        switch (field.getType().getArrayElementType().getCode()) {
            case BOOL:
                struct.getBooleanList(field.getName()).stream().filter(Objects::nonNull).forEach(array::add);
                break;
            case INT64:
                struct.getLongList(field.getName()).stream().filter(Objects::nonNull).forEach(array::add);
                break;
            case FLOAT64:
                struct.getDoubleList(field.getName()).stream().filter(Objects::nonNull).forEach(array::add);
                break;
            case STRING:
                struct.getStringList(field.getName()).stream().filter(Objects::nonNull).forEach(array::add);
                break;
            case BYTES:
                struct.getBytesList(field.getName()).stream().filter(Objects::nonNull).map(ByteArray::toBase64).forEach(array::add);
                break;
            case TIMESTAMP:
                struct.getTimestampList(field.getName()).stream().filter(Objects::nonNull).map(Timestamp::toString).forEach(array::add);
                break;
            case DATE:
                struct.getDateList(field.getName()).stream().filter(Objects::nonNull).map(Date::toString).forEach(array::add);
                break;
            case STRUCT:
                struct.getStructList(field.getName()).stream()
                        .filter(Objects::nonNull)
                        .map(StructToJsonConverter::convertStruct)
                        .forEach(array::add);
                break;
            case ARRAY:
                setJsonArrayFieldValue(obj, field, struct);
                break;
        }
        obj.add(field.getName(), array);
    }

}