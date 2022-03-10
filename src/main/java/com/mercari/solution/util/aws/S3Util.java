package com.mercari.solution.util.aws;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.google.common.io.ByteStreams;
import com.mercari.solution.util.gcp.StorageUtil;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileStream;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.beam.sdk.io.aws.options.AwsOptions;
import org.apache.parquet.avro.AvroSchemaConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.DelegatingSeekableInputStream;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.SeekableInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class S3Util {

    public static AmazonS3 storage(final AwsOptions options) {
        return AmazonS3ClientBuilder
                .standard()
                .withCredentials(options.getAwsCredentialsProvider())
                .withRegion(options.getAwsRegion())
                .build();
    }

    public static AmazonS3 storage(final String accessKey, final String secretKey, final String region) {
        return AmazonS3ClientBuilder
                .standard()
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .withRegion(region)
                .build();
    }

    public static String readString(final String s3Path,
                                    final AwsOptions options) {

        final String[] paths = parseS3Path(s3Path);
        final AmazonS3 s3 = storage(options);
        return readString(s3, paths[0], paths[1]);
    }

    public static byte[] readBytes(final String s3Path, final AwsOptions options) {
        final String[] paths = parseS3Path(s3Path);
        final AmazonS3 s3 = storage(options);
        return readBytes(s3, paths[0], paths[1]);
    }

    public static byte[] readBytes(final AmazonS3 s3, final String s3Path) {
        final String[] paths = parseS3Path(s3Path);
        return readBytes(s3, paths[0], paths[1]);
    }

    public static void writeBytes(
            final String s3Path,
            final byte[] content,
            final String type,
            final Map<String, Object> fields,
            final Map<String, String> metadata,
            final AwsOptions options) {

        writeBytes(storage(options), s3Path, content, type, fields, metadata);
    }

    public static void writeBytes(
            final AmazonS3 s3,
            final String s3Path,
            final byte[] content,
            final String type,
            final Map<String, Object> attributes,
            final Map<String, String> metadata) {

        final String[] paths = parseS3Path(s3Path);
        final ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentType(type);
        objectMetadata.setUserMetadata(metadata);

        try(final InputStream is = new ByteArrayInputStream(content)) {
            final PutObjectRequest request = new PutObjectRequest(paths[0], paths[1], is, objectMetadata);
            for(Map.Entry<String, Object> entry : attributes.entrySet()) {
                switch (entry.getKey()) {
                    case "storageClass":
                        request.setStorageClass((String)entry.getValue());
                    case "objectLockMode":
                        request.setObjectLockMode((String)entry.getValue());
                    case "bucketKeyEnabled":
                        request.setBucketKeyEnabled((Boolean)entry.getValue());
                    case "redirectLocation":
                        request.setRedirectLocation((String)entry.getValue());
                }
            }
            s3.putObject(request);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<S3ObjectSummary> listFiles(
            final String s3Path,
            final AwsOptions options) {

        final AmazonS3 s3 = storage(options);
        final String[] paths = parseS3Path(s3Path);
        return listFiles(s3, paths[0], paths[1]);
    }

    public static void copy(final AmazonS3 s3, final String sourcePath, final String destinationPath, final Map<String, Object> attributes) {
        final String[] sourcePaths = parseS3Path(sourcePath);
        final String[] destinationPaths = parseS3Path(destinationPath);
        final CopyObjectRequest request = new CopyObjectRequest();
        request.setSourceBucketName(sourcePaths[0]);
        request.setSourceKey(sourcePaths[1]);
        request.setDestinationBucketName(destinationPaths[0]);
        request.setDestinationKey(destinationPaths[1]);
        for(Map.Entry<String, Object> entry : attributes.entrySet()) {
            switch (entry.getKey()) {
                case "storageClass":
                    request.setStorageClass((String)entry.getValue());
                case "objectLockMode":
                    request.setObjectLockMode((String)entry.getValue());
                case "bucketKeyEnabled":
                    request.setBucketKeyEnabled((Boolean)entry.getValue());
                case "redirectLocation":
                    request.setRedirectLocation((String)entry.getValue());
            }
        }
        s3.copyObject(request);
    }

    private static List<S3ObjectSummary> listFiles(final AmazonS3 s3, final String bucket, final String prefix) {
        final String p = prefix.endsWith("*") ? prefix.replace("*", "") : prefix;
        try {
            final ObjectListing listing = s3.listObjects(bucket, p);
            final List<S3ObjectSummary> objects = listing.getObjectSummaries();
            while(listing.isTruncated()) {
                objects.addAll(s3.listNextBatchOfObjects(listing).getObjectSummaries());
            }
            return objects;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readString(final AmazonS3 s3,
                                     final String bucket,
                                     final String object) {

        final byte[] bytes = readBytes(s3, bucket, object);
        return new String(bytes);
    }

    private static byte[] readBytes(final AmazonS3 s3, final String bucket, final String object) {
        try {
            return ByteStreams.toByteArray(s3.getObject(bucket, object).getObjectContent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String[] parseS3Path(String s3Path) {
        if(s3Path == null) {
            throw new IllegalArgumentException("gcsPath must not be null");
        }
        if(!s3Path.startsWith("s3://")) {
            throw new IllegalArgumentException("s3Path must start with s3://");
        }
        final String[] paths = s3Path.replaceAll("s3://", "").split("/", 2);
        if(paths.length != 2) {
            throw new IllegalArgumentException("Illegal gcsPath: " + s3Path);
        }
        return paths;
    }

    public static Schema getAvroSchema(final String s3Path,
                                       final AwsOptions options) {
        final AmazonS3 s3 = storage(options);
        final String[] paths = parseS3Path(s3Path);
        return getAvroSchema(s3, paths[0], paths[1]);
    }

    public static Schema getAvroSchema(final S3ObjectSummary object,
                                       final AwsOptions options) {
        final AmazonS3 s3 = storage(options);
        return getAvroSchema(s3, object.getBucketName(), object.getKey());
    }

    public static Schema getAvroSchema(final AmazonS3 s3, final String bucket, final String object) {
        final DatumReader<GenericRecord> datumReader = new GenericDatumReader<>();
        try(final InputStream is = s3.getObject(bucket, object).getObjectContent();
            final DataFileStream<GenericRecord> dataFileReader = new DataFileStream<>(is, datumReader)) {
            return dataFileReader.getSchema();
        } catch (Exception e) {
            return null;
        }
    }

    public static Schema getParquetSchema(final String s3Path,
                                          final AwsOptions options) {
        final AmazonS3 s3 = storage(options);
        final String[] paths = parseS3Path(s3Path);
        return getParquetSchema(s3, paths[0], paths[1]);
    }

    public static Schema getParquetSchema(final S3ObjectSummary object,
                                          final AwsOptions options) {
        final AmazonS3 s3 = storage(options);
        return getParquetSchema(s3, object.getBucketName(), object.getKey());
    }

    public static Schema getParquetSchema(final AmazonS3 s3, final String bucket, final String object) {
        final byte[] bytes;
        try {
            bytes = readBytes(s3, bucket, object);
        } catch (AmazonS3Exception e) {
            return null;
        }
        try(final ParquetFileReader f = ParquetFileReader.open(new ParquetStream(bytes))) {
            return new AvroSchemaConverter().convert(f.getFooter().getFileMetaData().getSchema());
        } catch (Exception e) {
            return null;
        }
    }

    public static class ParquetStream implements InputFile {
        private final byte[] data;

        public class SeekableByteArrayInputStream extends ByteArrayInputStream {

            public SeekableByteArrayInputStream(byte[] buf) {
                super(buf);
            }

            public void setPos(int pos) {
                this.pos = pos;
            }

            public int getPos() {
                return this.pos;
            }
        }

        public ParquetStream(final byte[] data) {
            this.data = data;
        }

        @Override
        public long getLength() {
            return this.data.length;
        }

        @Override
        public SeekableInputStream newStream() {
            return new DelegatingSeekableInputStream(new ParquetStream.SeekableByteArrayInputStream(this.data)) {

                @Override
                public void seek(long newPos) {
                    ((StorageUtil.ParquetStream.SeekableByteArrayInputStream) this.getStream()).setPos(new Long(newPos).intValue());
                }

                @Override
                public long getPos() {
                    return new Integer(((StorageUtil.ParquetStream.SeekableByteArrayInputStream) this.getStream()).getPos()).longValue();
                }
            };
        }
    }

}
