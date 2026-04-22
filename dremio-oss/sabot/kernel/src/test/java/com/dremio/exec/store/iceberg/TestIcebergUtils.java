/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.iceberg;

import static com.dremio.exec.ExecConstants.ICEBERG_CATALOG_TYPE_KEY;
import static com.dremio.exec.store.iceberg.IcebergUtils.*;
import static com.dremio.exec.store.iceberg.model.IcebergBaseCommand.DREMIO_JOB_ID_ICEBERG_PROPERTY;
import static org.apache.hadoop.fs.FileSystem.FS_DEFAULT_NAME_KEY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.types.TypeProtos;
import com.dremio.common.types.Types;
import com.dremio.common.util.MajorTypeHelper;
import com.dremio.exec.catalog.conf.AzureStorageConfProperties;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.hadoop.HadoopFileSystemConfigurationAdapter;
import com.dremio.exec.planner.sql.PartitionTransform;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.model.IcebergCatalogType;
import com.dremio.io.file.FileSystem;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.ExpireSnapshots;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Test class for IcebergUtils.java class */
public class TestIcebergUtils {
  private static final Configuration CONF = new Configuration();
  private static FileSystem fs;

  @BeforeClass
  public static void initStatics() throws Exception {
    CONF.set(ICEBERG_CATALOG_TYPE_KEY, IcebergCatalogType.HADOOP.name());
    fs = HadoopFileSystem.get(com.dremio.io.file.Path.of("/"), CONF);
  }

  private static final BatchSchema TEST_SCHEMA =
      BatchSchema.newBuilder()
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "w", Types.optional(TypeProtos.MinorType.TIME)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "x", Types.optional(TypeProtos.MinorType.INT)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "y", Types.optional(TypeProtos.MinorType.VARCHAR)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "z", Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "_", Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "test_", Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "_identity", Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI)))
          .addField(
              MajorTypeHelper.getFieldForNameAndMajorType(
                  "_hour", Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI)))
          .build();

  @Test
  public void testValidIcebergPath() {
    String testUrl =
        "/testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";

    Configuration conf = new Configuration();

    String modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(new Path(testUrl), conf, "dremioS3");
    Assert.assertEquals(
        "s3://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    modifiedFileLocation =
        IcebergUtils.getIcebergPathAndValidateScheme(testUrl, conf, "dremioS3", "s3a");
    Assert.assertEquals(
        "s3a://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    modifiedFileLocation = IcebergUtils.getValidIcebergPath(new Path(testUrl), conf, "dremiogcs");
    Assert.assertEquals(
        "gs://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    Configuration hdfsConf = new Configuration();
    hdfsConf.set(FS_DEFAULT_NAME_KEY, "hdfs://172.25.0.39:8020/");
    modifiedFileLocation = IcebergUtils.getValidIcebergPath(new Path(testUrl), hdfsConf, "hdfs");
    Assert.assertEquals(
        "hdfs://172.25.0.39:8020/testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    modifiedFileLocation = IcebergUtils.getValidIcebergPath(new Path(testUrl), conf, "hdfs");
    Assert.assertEquals(
        "hdfs:///testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    String urlWithScheme =
        "s3://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(new Path(urlWithScheme), conf, "dremioS3");
    Assert.assertEquals(urlWithScheme, modifiedFileLocation);

    urlWithScheme =
        "s3://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation =
        IcebergUtils.getIcebergPathAndValidateScheme(urlWithScheme, conf, "dremioS3", "s3a");
    Assert.assertEquals(
        "'s3' should be replaced with 's3a'",
        "s3a://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    urlWithScheme =
        "s3a://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation =
        IcebergUtils.getIcebergPathAndValidateScheme(urlWithScheme, conf, "s3a", "s3a");
    Assert.assertEquals(
        "'s3a' should not be replaced with 's3'", urlWithScheme, modifiedFileLocation);

    urlWithScheme =
        "gs://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation =
        IcebergUtils.getIcebergPathAndValidateScheme(urlWithScheme, conf, "dremiogcs", "gs");
    Assert.assertEquals(
        "The path should not be updated",
        "gs://testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    urlWithScheme =
        "hdfs://172.25.0.39:8020/testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation = IcebergUtils.getValidIcebergPath(new Path(urlWithScheme), conf, "hdfs");
    Assert.assertEquals(urlWithScheme, modifiedFileLocation);

    urlWithScheme =
        "file:///testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation = IcebergUtils.getValidIcebergPath(new Path(urlWithScheme), conf, "file");
    Assert.assertEquals(urlWithScheme, modifiedFileLocation);
  }

  // Azure sources will write 'abfss' by default, unless the scheme is already present, or if the
  // configuration 'dremio.azure.mode' is STORAGE_V1.
  @Test
  public void testValidIcebergPathAzureSources() {
    String testUrl =
        "/testdir/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";

    // StorageV1 conf. Wasbs will be written
    Configuration azureConf = new Configuration();
    azureConf.set(AzureStorageConfProperties.ACCOUNT, "azurev1databricks2");
    azureConf.set(AzureStorageConfProperties.MODE, "STORAGE_V1");
    String modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(new Path(testUrl), azureConf, "dremioAzureStorage://");
    Assert.assertEquals(
        "wasbs://testdir@azurev1databricks2.blob.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    // StorageV2 in conf. ABFSS will be written
    Configuration datalakeCatalogAzureConf = new Configuration();
    datalakeCatalogAzureConf.set(
        AzureStorageConfProperties.ACCOUNT, "datalakecatalogazuredatabricks2");
    datalakeCatalogAzureConf.set(AzureStorageConfProperties.MODE, "STORAGE_V2");
    modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(
            new Path(testUrl), datalakeCatalogAzureConf, "dremioAzureStorage://");
    Assert.assertEquals(
        "abfss://testdir@datalakecatalogazuredatabricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    // StorageV2 but Force-Write Wasbs enabled. Wasbs will be written.
    Configuration datalakeCatalogAzureConfForceWasbs = new Configuration();
    datalakeCatalogAzureConfForceWasbs.set(
        AzureStorageConfProperties.ACCOUNT, "datalakecatalogazuredatabricks2");
    datalakeCatalogAzureConfForceWasbs.set(AzureStorageConfProperties.MODE, "STORAGE_V2");
    modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(
            new Path(testUrl), datalakeCatalogAzureConfForceWasbs, "dremioAzureStorage://");
    Assert.assertEquals(
        "abfss://testdir@datalakecatalogazuredatabricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    // Storage conf not configured (shouldn't ever happen, but here for safety). Will write abfss.
    Configuration datalakeCatalogAzureConfAbfss = new Configuration();
    datalakeCatalogAzureConfAbfss.set(
        AzureStorageConfProperties.ACCOUNT, "datalakecatalogazuredatabricks2");
    modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(
            new Path(testUrl), datalakeCatalogAzureConfAbfss, "dremioAzureStorage://");
    Assert.assertEquals(
        "abfss://testdir@datalakecatalogazuredatabricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);

    // No conf settings configured, but scheme already exists, so we short-return current scheme.
    Configuration conf = new Configuration();
    String urlWithScheme =
        "wasbs://testdir@azurev1databricks2.blob.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation =
        IcebergUtils.getValidIcebergPath(new Path(urlWithScheme), conf, "dremioAzureStorage://");
    Assert.assertEquals(urlWithScheme, modifiedFileLocation);

    // No conf settings configured, but scheme already exists and a variate. Replace with variate.
    urlWithScheme =
        "wasbs://testdir@azurev1databricks2.blob.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro";
    modifiedFileLocation =
        IcebergUtils.getIcebergPathAndValidateScheme(
            urlWithScheme, azureConf, "dremioAzureStorage://", "abfs");
    Assert.assertEquals(
        "'wasbs' should be replaced with 'abfs'",
        "abfs://testdir@azurev1databricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro",
        modifiedFileLocation);
  }

  // return true (wasbs) because STORAGE_V1 configured
  @Test
  public void testWriteWasbs_StorageV1_NoForce() {
    Configuration conf = new Configuration();
    conf.set(AzureStorageConfProperties.MODE, "STORAGE_V1");
    Assert.assertTrue(writeWasbs(new HadoopFileSystemConfigurationAdapter(conf)));
  }

  // return false (abfss) because STORAGE_V2 configured.
  @Test
  public void testWriteWasbs_StorageV2_NoForce() {
    Configuration conf = new Configuration();
    conf.set(AzureStorageConfProperties.MODE, "STORAGE_V2");
    Assert.assertFalse(writeWasbs(new HadoopFileSystemConfigurationAdapter(conf)));
  }

  // convert .blob. to .dfs. because 'abfss' is the prefix
  @Test
  public void testBlobToDfs_WithAbfss() {
    Path path =
        new Path(
            "abfss://testdir@azurev1databricks2.blob.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro");
    StringBuilder urlBuilder = new StringBuilder("abfss://testdir@azurev1databricks2");

    String authority = syncAzurePrefixSuffix(path, urlBuilder);
    Assert.assertEquals(
        com.dremio.io.file.Path.AZURE_DFS_AUTHORITY_SUFFIX,
        authority.substring(authority.indexOf(".")));
  }

  // convert dfs to blob because 'wasbs' is the prefix
  @Test
  public void testDfsToBlob_WithWasbs() {
    Path path =
        new Path(
            "wasbs://testdir@azurev1databricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro");
    StringBuilder urlBuilder = new StringBuilder("wasbs://testdir@azurev1databricks2");

    String authority = syncAzurePrefixSuffix(path, urlBuilder);
    Assert.assertEquals(
        com.dremio.io.file.Path.AZURE_BLOB_AUTHORITY_SUFFIX,
        authority.substring(authority.indexOf(".")));
  }

  // no conversion necessary. wasbs and blob are current prefix + authority pair.
  @Test
  public void testNoChange_WithWasbs() {
    Path path =
        new Path(
            "wasbs://testdir@azurev1databricks2.blob.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro");
    StringBuilder urlBuilder = new StringBuilder("wasbs://testdir@azurev1databricks2");

    String authority = syncAzurePrefixSuffix(path, urlBuilder);
    Assert.assertEquals(
        com.dremio.io.file.Path.AZURE_BLOB_AUTHORITY_SUFFIX,
        authority.substring(authority.indexOf(".")));
  }

  // no conversion necessary. abfss and dfs are the current prefix+ authority pair
  @Test
  public void testNoChange_WithDfsAndAbfss() {
    Path path =
        new Path(
            "abfss://testdir@azurev1databricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro");
    StringBuilder urlBuilder = new StringBuilder("abfss://testdir@azurev1databricks2");

    String authority = syncAzurePrefixSuffix(path, urlBuilder);
    Assert.assertEquals(
        com.dremio.io.file.Path.AZURE_DFS_AUTHORITY_SUFFIX,
        authority.substring(authority.indexOf(".")));
  }

  // convert blob to dfs because the prefix is 'abfs'
  @Test
  public void testBlobToDfs_WithAbfs() {
    Path path =
        new Path(
            "abfs://testdir@azurev1databricks2.blob.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro");
    StringBuilder urlBuilder = new StringBuilder("abfs://testdir@azurev1databricks2");

    String authority = syncAzurePrefixSuffix(path, urlBuilder);
    Assert.assertEquals(
        com.dremio.io.file.Path.AZURE_DFS_AUTHORITY_SUFFIX,
        authority.substring(authority.indexOf(".")));
  }

  // convert dfs to blob because the prefix is wasb
  @Test
  public void testDfsToBlob_WithWasb() {
    Path path =
        new Path(
            "wasb://testdir@azurev1databricks2.dfs.core.windows.net/Automation/regression/iceberg/alltypes/metadata/snap-6325739561998439041.avro");
    StringBuilder urlBuilder = new StringBuilder("wasb://testdir@azurev1databricks2");

    String authority = syncAzurePrefixSuffix(path, urlBuilder);
    Assert.assertEquals(
        com.dremio.io.file.Path.AZURE_BLOB_AUTHORITY_SUFFIX,
        authority.substring(authority.indexOf(".")));
  }

  @Test
  public void testPartitionStatsPathForInputWithNoScheme() {
    String rootPointer = "/tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals("/tmp/metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithNoSchemeAndWhitespace() {
    String rootPointer = "/tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "/tmp/new metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithNoSchemeAndRelativePath() {
    String rootPointer = "tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals("tmp/metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithNoSchemeAndRelativePathAndWhitespace() {
    String rootPointer = "tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals("tmp/new metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithNoSchemeAndUnnormalizedPath() {
    String rootPointer = "../../tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "../../tmp/metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithNoSchemeAndUnnormalizedPathAndWhitespace() {
    String rootPointer = "../../tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "../../tmp/new metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithFileScheme() {
    String rootPointer = "file:///tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "file:///tmp/metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithFileSchemeAndWhitespace() {
    String rootPointer = "file:///tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "file:///tmp/new metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithHdfsScheme() {
    String rootPointer = "hdfs:///tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "hdfs:///tmp/metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithHdfsSchemeAndWhitespace() {
    String rootPointer = "hdfs:///tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "hdfs:///tmp/new metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithHdfsSchemeAndRelativePath() {
    String rootPointer = "hdfs://tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "hdfs://tmp/metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithHdfsSchemeAndRelativePathWithWhitespace() {
    String rootPointer = "hdfs://tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "hdfs://tmp/new metadata/metadata-12345.json", resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithHdfsSchemeAndHostPort() {
    String rootPointer = "hdfs://some-host:1234/tmp/metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "hdfs://some-host:1234/tmp/metadata/metadata-12345.json",
        resolvePath(rootPointer, metadata));
  }

  @Test
  public void testPartitionStatsPathForInputWithHdfsSchemeAndHostPortWithWhitespace() {
    String rootPointer = "hdfs://some-host:1234/tmp/new metadata/v1.metadata.json";
    String metadata = "metadata-12345.json";
    Assert.assertEquals(
        "hdfs://some-host:1234/tmp/new metadata/metadata-12345.json",
        resolvePath(rootPointer, metadata));
  }

  @Test
  public void testConvertSchemaMilliToMicro() {
    BatchSchema schema =
        BatchSchema.newBuilder()
            .addField(
                MajorTypeHelper.getFieldForNameAndMajorType(
                    "f0", Types.optional(TypeProtos.MinorType.INT)))
            .addField(
                MajorTypeHelper.getFieldForNameAndMajorType(
                    "f1", Types.optional(TypeProtos.MinorType.TIME)))
            .addField(
                MajorTypeHelper.getFieldForNameAndMajorType(
                    "f2", Types.optional(TypeProtos.MinorType.TIMESTAMPMILLI)))
            .build();

    Assert.assertEquals(
        schema.getColumn(0).getType(), org.apache.arrow.vector.types.Types.MinorType.INT.getType());
    Assert.assertEquals(
        schema.getColumn(1).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMEMILLI.getType());
    Assert.assertEquals(
        schema.getColumn(2).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMESTAMPMILLI.getType());

    // convert
    List<Field> fields = IcebergUtils.convertSchemaMilliToMicro(schema.getFields());
    Assert.assertEquals(
        fields.get(0).getType(), org.apache.arrow.vector.types.Types.MinorType.INT.getType());
    Assert.assertEquals(
        fields.get(1).getType(), org.apache.arrow.vector.types.Types.MinorType.TIMEMICRO.getType());
    Assert.assertEquals(
        fields.get(2).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMESTAMPMICRO.getType());

    schema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "f0",
                    FieldType.nullable(
                        org.apache.arrow.vector.types.Types.MinorType.LIST.getType()),
                    Collections.singletonList(
                        Field.nullable(
                            "data",
                            org.apache.arrow.vector.types.Types.MinorType.TIMEMILLI.getType()))))
            .addField(
                new Field(
                    "f1",
                    FieldType.nullable(
                        org.apache.arrow.vector.types.Types.MinorType.LIST.getType()),
                    Collections.singletonList(
                        Field.nullable(
                            "data",
                            org.apache.arrow.vector.types.Types.MinorType.TIMESTAMPMILLI
                                .getType()))))
            .build();

    // convert
    fields = IcebergUtils.convertSchemaMilliToMicro(schema.getFields());
    Assert.assertEquals(
        fields.get(0).getChildren().get(0).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMEMICRO.getType());
    Assert.assertEquals(
        fields.get(1).getChildren().get(0).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMESTAMPMICRO.getType());

    schema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "f0",
                    FieldType.nullable(
                        org.apache.arrow.vector.types.Types.MinorType.STRUCT.getType()),
                    Arrays.asList(
                        Field.nullable(
                            "c0",
                            org.apache.arrow.vector.types.Types.MinorType.TIMEMILLI.getType()),
                        Field.nullable(
                            "c1",
                            org.apache.arrow.vector.types.Types.MinorType.TIMESTAMPMILLI
                                .getType()))))
            .build();

    // convert
    fields = IcebergUtils.convertSchemaMilliToMicro(schema.getFields());
    Assert.assertEquals(
        fields.get(0).getChildren().get(0).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMEMICRO.getType());
    Assert.assertEquals(
        fields.get(0).getChildren().get(1).getType(),
        org.apache.arrow.vector.types.Types.MinorType.TIMESTAMPMICRO.getType());
  }

  @Test
  public void testGetIcebergPartitionSpecFromIdentityTransform() {
    List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("x"));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("x", spec.fields().get(0).name());
    Assert.assertTrue(spec.fields().get(0).transform().isIdentity());
  }

  @Test
  public void testGetIcebergPartitionSpecFromYearTransform() {
    List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.YEAR));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("z_year", spec.fields().get(0).name());
    Assert.assertEquals("year", spec.fields().get(0).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromMonthTransform() {
    List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.MONTH));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("z_month", spec.fields().get(0).name());
    Assert.assertEquals("month", spec.fields().get(0).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromDayTransform() {
    List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.DAY));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("z_day", spec.fields().get(0).name());
    Assert.assertEquals("day", spec.fields().get(0).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromHourTransform() {
    List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.HOUR));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("z_hour", spec.fields().get(0).name());
    Assert.assertEquals("hour", spec.fields().get(0).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromBucketTransform() {
    List<PartitionTransform> transforms =
        ImmutableList.of(
            new PartitionTransform("x", PartitionTransform.Type.BUCKET, ImmutableList.of(10)));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("x_bucket", spec.fields().get(0).name());
    Assert.assertEquals("bucket[10]", spec.fields().get(0).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromTruncateTransform() {
    List<PartitionTransform> transforms =
        ImmutableList.of(
            new PartitionTransform("x", PartitionTransform.Type.TRUNCATE, ImmutableList.of(10)));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(1, spec.fields().size());
    Assert.assertEquals("x_trunc", spec.fields().get(0).name());
    Assert.assertEquals("truncate[10]", spec.fields().get(0).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromMultiTransforms() {
    List<PartitionTransform> transforms =
        ImmutableList.of(
            new PartitionTransform("x", PartitionTransform.Type.TRUNCATE, ImmutableList.of(10)),
            new PartitionTransform("y"),
            new PartitionTransform("z", PartitionTransform.Type.YEAR));
    PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals(3, spec.fields().size());
    Assert.assertEquals("x_trunc", spec.fields().get(0).name());
    Assert.assertEquals("truncate[10]", spec.fields().get(0).transform().toString());
    Assert.assertEquals("y", spec.fields().get(1).name());
    Assert.assertEquals("z_year", spec.fields().get(2).name());
    Assert.assertEquals("year", spec.fields().get(2).transform().toString());
  }

  @Test
  public void testGetIcebergPartitionSpecFromTransformWithInvalidFields() {
    List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("badcol"));
    assertThatThrownBy(
            () -> IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null))
        .isInstanceOf(UserException.class);
  }

  @Test
  public void testGetIcebergPartitionSpecFromTransformWithTimeTypeFails() {
    List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("w"));
    assertThatThrownBy(
            () -> IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null))
        .isInstanceOf(UserException.class);
  }

  @Test
  public void testGetInvalidColumnsForPruning() {
    /*
    If a partition column has identity transformation in all the partition specs, that column can be pruned
    Scenarios:
    1. When a column ("y") has identity transform in all partition specs -> column not included in o/p
    2. When a column ("z") doesn't have identity transform in all partition specs -> column included in o/p
    3. When a column ("x") has identity transform in some partition specs, but not all -> column included in o/p
    4. When a column ("w") has only identity transform, but not in all the partition specs -> column included in o/p
    */
    SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    Schema schema = schemaConverter.toIcebergSchema(TEST_SCHEMA);

    PartitionSpec.Builder builder = PartitionSpec.builderFor(schema);
    PartitionSpec spec1 = builder.withSpecId(0).identity("y").bucket("x", 10).month("z").build();

    builder = PartitionSpec.builderFor(schema);
    PartitionSpec spec2 = builder.withSpecId(1).identity("y").bucket("x", 10).identity("x").build();

    builder = PartitionSpec.builderFor(schema);
    PartitionSpec spec3 =
        builder
            .withSpecId(2)
            .identity("y")
            .bucket("x", 10)
            .identity("x")
            .day("z")
            .identity("w")
            .build();

    Map<Integer, PartitionSpec> partitionSpecMap = new HashMap<>();
    partitionSpecMap.put(0, spec1);
    partitionSpecMap.put(1, spec2);
    partitionSpecMap.put(2, spec3);

    Set<String> op = IcebergUtils.getInvalidColumnsForPruning(partitionSpecMap);
    Assert.assertTrue(op.contains("z"));
    Assert.assertFalse(op.contains("y"));
    Assert.assertTrue(op.contains("x"));
    Assert.assertTrue(op.contains("w"));
  }

  @Test
  public void testGetColumnNameIdentity() {
    final List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("x"));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("x", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameYear() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.YEAR));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("z", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameMonth() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.MONTH));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("z", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameDay() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.DAY));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("z", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameHour() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("z", PartitionTransform.Type.HOUR));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("z", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameBucket() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(
            new PartitionTransform("x", PartitionTransform.Type.BUCKET, ImmutableList.of(10)));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("x", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameTruncate() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(
            new PartitionTransform("x", PartitionTransform.Type.TRUNCATE, ImmutableList.of(10)));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("x", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameUnderscoreHour() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("_", PartitionTransform.Type.HOUR));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("_", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameTestUnderscoreHour() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("test_", PartitionTransform.Type.HOUR));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("test_", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameUnderscoreHourHour() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("_hour", PartitionTransform.Type.HOUR));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("_hour", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameTestUnderscoreIdenitytyHour() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("_identity", PartitionTransform.Type.HOUR));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("_identity", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameUnderscoreIdentity() {
    final List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("_"));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("_", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameTestUnderscoreIdentity() {
    final List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("test_"));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("test_", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameUnderscoreHourIdentity() {
    final List<PartitionTransform> transforms = ImmutableList.of(new PartitionTransform("_hour"));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("_hour", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testGetColumnNameTestUnderscoreIdentityIdentity() {
    final List<PartitionTransform> transforms =
        ImmutableList.of(new PartitionTransform("_identity"));
    final PartitionSpec spec =
        IcebergUtils.getIcebergPartitionSpecFromTransforms(TEST_SCHEMA, transforms, null);
    Assert.assertEquals("_identity", getColumnName(spec.fields().get(0), spec.schema()));
  }

  @Test
  public void testToIcebergValueLong() {
    final Long valueLong = 1_000L;
    // for timestamp conversion happens
    Assert.assertEquals(
        1_000_000L,
        toIcebergValue(
            valueLong,
            TypeProtos.MajorType.newBuilder()
                .setMinorType(TypeProtos.MinorType.TIMESTAMPMILLI)
                .build()));

    // no conversion for the rest of the types
    Assert.assertEquals(
        1_000L,
        toIcebergValue(
            valueLong,
            TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.BIGINT).build()));
    final Integer valueInt = 1_000;
    Assert.assertEquals(
        1_000,
        toIcebergValue(
            valueInt,
            TypeProtos.MajorType.newBuilder().setMinorType(TypeProtos.MinorType.INT).build()));
  }

  @Test
  public void testGetPartitionSpecFromMapIdIsLatest() {
    // return the partitionSpec that default ID specified
    Map<Integer, PartitionSpec> partitionSpecMap = preparePartitionSpecMap();
    PartitionSpec spec = getPartitionSpecFromMap(partitionSpecMap, 1);
    Assert.assertEquals(spec.fields().get(0).name(), "x");
  }

  @Test
  public void testGetPartitionSpecFromMapIdNotLatest() {
    // return the partitionSpec that default ID specified
    Map<Integer, PartitionSpec> partitionSpecMap = preparePartitionSpecMap();
    PartitionSpec spec = getPartitionSpecFromMap(partitionSpecMap, 0);
    Assert.assertEquals(spec.fields().get(0).name(), "w");
  }

  @Test
  public void testGetPartitionSpecFromMapIdIsNull() {
    // return the latest partitionSpec when default ID is null
    Map<Integer, PartitionSpec> partitionSpecMap = preparePartitionSpecMap();
    PartitionSpec spec = getPartitionSpecFromMap(partitionSpecMap, null);
    Assert.assertEquals(spec.fields().get(0).name(), "x");
  }

  @Test
  public void testGetPartitionSpecFromMapIdIsInvalid() {
    // return the latest partitionSpec when default ID is invalid
    Map<Integer, PartitionSpec> partitionSpecMap = preparePartitionSpecMap();
    PartitionSpec spec = getPartitionSpecFromMap(partitionSpecMap, 33);
    Assert.assertEquals(spec.fields().get(0).name(), "x");
  }

  @Test
  public void testViewMetadataVersionV1() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ValidViewMetadataV1.metadata.json").toURI().toString();
    String compressedViewMetadataJsonFileName =
        writeFileInGzipFormat(fileIO, viewMetadataJsonFileName);
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(compressedViewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.V1);
  }

  @Test
  public void testViewMetadataMissingVersionV1() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ViewMetadataMissingVersionV1.metadata.json")
            .toURI()
            .toString();
    String compressedViewMetadataJsonFileName =
        writeFileInGzipFormat(fileIO, viewMetadataJsonFileName);
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(compressedViewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.UNKNOWN);
  }

  @Test
  public void testViewMetadataMissingUuidV1() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ViewMetadataMissingUuidV1.metadata.json")
            .toURI()
            .toString();
    String compressedViewMetadataJsonFileName =
        writeFileInGzipFormat(fileIO, viewMetadataJsonFileName);
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(compressedViewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.UNKNOWN);
  }

  @Test
  public void testViewMetadataUnsupportedVersionV1() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ViewMetadataUnsupportedVersionV1.metadata.json")
            .toURI()
            .toString();
    String compressedViewMetadataJsonFileName =
        writeFileInGzipFormat(fileIO, viewMetadataJsonFileName);
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(compressedViewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.UNKNOWN);
  }

  @Test
  public void testViewMetadataVersionV0() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ValidViewVersionMetadataV0.metadata.json")
            .toURI()
            .toString();
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(viewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.V0);
  }

  @Test
  public void testViewMetadataMissingVersionV0() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ViewVersionMetadataMissingVersionV0.metadata.json")
            .toURI()
            .toString();
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(viewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.UNKNOWN);
  }

  @Test
  public void testViewMetadataMissingViewDefinitionV0() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ViewVersionMetadataMissingVersionV0.metadata.json")
            .toURI()
            .toString();
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(viewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.UNKNOWN);
  }

  @Test
  public void testViewMetadataUnsupportedVersionV0() throws URISyntaxException, IOException {
    FileIO fileIO =
        new DremioFileIO(
            fs, null, null, null, null, new HadoopFileSystemConfigurationAdapter(CONF));
    String viewMetadataJsonFileName =
        Resources.getResource("iceberg/utils/ViewVersionMetadataMissingVersionV0.metadata.json")
            .toURI()
            .toString();
    IcebergViewMetadata.SupportedIcebergViewSpecVersion viewVersion =
        IcebergUtils.findIcebergViewVersion(viewMetadataJsonFileName, fileIO);
    Assert.assertEquals(viewVersion, IcebergViewMetadata.SupportedIcebergViewSpecVersion.UNKNOWN);
  }

  @Test
  public void testRevertSnapshotFiles() {
    Table table = mock(Table.class);
    Snapshot snapshot = mock(Snapshot.class);
    DataFile df = mock(DataFile.class);
    DeleteFiles deleteFiles = mock(DeleteFiles.class);
    ExpireSnapshots expireSnapshots = mock(ExpireSnapshots.class);
    ArgumentCaptor<DataFile> argumentCaptor = ArgumentCaptor.forClass(DataFile.class);

    when(table.newDelete()).thenReturn(deleteFiles);
    when(table.expireSnapshots()).thenReturn(expireSnapshots);
    when(snapshot.snapshotId()).thenReturn(2L);
    when(snapshot.addedDataFiles(any())).thenReturn(ImmutableList.of(df));
    when(deleteFiles.validateFilesExist()).thenReturn(deleteFiles);
    when(expireSnapshots.expireSnapshotId(2L)).thenReturn(expireSnapshots);

    IcebergUtils.revertSnapshotFiles(table, snapshot, "1");

    verify(deleteFiles, times(1)).set(DREMIO_JOB_ID_ICEBERG_PROPERTY, "1");
    verify(deleteFiles).deleteFile(argumentCaptor.capture());
    Assert.assertEquals(argumentCaptor.getValue(), df);
    verify(deleteFiles, times(1)).commit();
    verify(expireSnapshots, times(1)).commit();
  }

  @Test
  public void testFixupDefaultPropertiesDoesNotChangeWhenAlreadySet() {
    // Arrange

    TableMetadata tableMetadata = mock(TableMetadata.class);
    Map<String, String> properties = new HashMap<>();
    properties.put("gc.enabled", "true");
    properties.put("write.metadata.delete-after-commit.enabled", "true");
    when(tableMetadata.properties()).thenReturn(properties);

    // Act
    IcebergUtils.fixupDefaultProperties(tableMetadata);

    // Assert
    verify(tableMetadata, times(0)).replaceProperties(any());
  }

  @Test
  public void testFixupDefaultPropertiesCalled() {
    // Arrange
    TableMetadata tableMetadata = mock(TableMetadata.class);
    Map<String, String> properties = new HashMap<>();
    when(tableMetadata.properties()).thenReturn(properties);

    // Act
    IcebergUtils.fixupDefaultProperties(tableMetadata);

    // Assert
    verify(tableMetadata, times(1)).replaceProperties(any());
  }

  private Map<Integer, PartitionSpec> preparePartitionSpecMap() {
    SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    Schema icebergSchema = schemaConverter.toIcebergSchema(TEST_SCHEMA);
    final Map<Integer, PartitionSpec> input = new HashMap<>();
    String fieldNameTime = "w";
    final PartitionSpec partitionSpec1 =
        PartitionSpec.builderFor(icebergSchema).identity(fieldNameTime).build();
    String fieldNameInt = "x";
    final PartitionSpec partitionSpec2 =
        PartitionSpec.builderFor(icebergSchema).identity(fieldNameInt).build();
    input.put(0, partitionSpec1);
    input.put(1, partitionSpec2);
    return input;
  }

  /* Used specifically to create new resources (files) in resource folder that originally existed as json into the new compressed (.gz) format */
  private static String writeFileInGzipFormat(FileIO fileIO, String viewMetadataJsonFileName)
      throws IOException {
    InputFile inputFile = fileIO.newInputFile(viewMetadataJsonFileName);
    OutputFile outputFile = fileIO.newOutputFile(viewMetadataJsonFileName + ".gz");
    PositionOutputStream pos = null;
    GZIPOutputStream gzipOut = null;
    try {
      pos = outputFile.createOrOverwrite();
      gzipOut = new GZIPOutputStream(pos);
      gzipOut.write(inputFile.newStream().readAllBytes());
    } finally {
      gzipOut.close();
      pos.close();
    }
    return outputFile.location();
  }
}
