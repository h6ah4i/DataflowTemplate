package com.mercari.solution.util.converter;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class RecordToCsvConverter {

    public static String convert(final GenericRecord record, final List<String> fields) {
        final List<?> values = fields
                .stream()
                .map(record::get)
                .collect(Collectors.toList());
        final StringBuilder sb = new StringBuilder();
        try(final CSVPrinter printer = new CSVPrinter(sb, CSVFormat.DEFAULT)) {
            printer.printRecord(values);
            printer.flush();
            return sb.toString().trim();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
