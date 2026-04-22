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
package com.dremio.exec.catalog.dataplane.test;

import static com.dremio.exec.catalog.conf.AzureAuthenticationType.ACCESS_KEY;
import static com.dremio.io.file.Path.AZURE_DFS_AUTHORITY_SUFFIX;
import static com.dremio.io.file.UriSchemes.AZURE_ABFSS_SCHEME;
import static com.dremio.io.file.UriSchemes.AZURE_WASBS_SCHEME;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import com.azure.storage.file.datalake.models.ListPathsOptions;
import com.azure.storage.file.datalake.models.PathItem;
import com.dremio.exec.catalog.conf.NessieAuthType;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.catalog.conf.StorageProviderType;
import com.dremio.plugins.dataplane.store.NessiePluginConfig;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;

public class AzureDataplaneStorage implements DataplaneStorage {

  protected static final String AZURE_STORAGE_DATAPLANE_ACCOUNT_NAME =
      Preconditions.checkNotNull(System.getenv("AZURE_STORAGE_DATAPLANE_ACCOUNT_NAME"));
  protected static final String AZURE_STORAGE_DATAPLANE_ACCOUNT_KEY =
      Preconditions.checkNotNull(System.getenv("AZURE_STORAGE_DATAPLANE_ACCOUNT_KEY"));

  /**
   * Since Azure containers for ITDataplane are not mocked (i.e. they use a real Azure Bucket),
   * bucket names are compared across all dev pushes submitted to jenkins. This JVM-defined uuid
   * will distinguish bucket names across all commit pushes across dremio org.
   */
  private final String uuid = UUID.randomUUID().toString();

  private final String primaryBucketName = "test-dataplane-" + uuid + "-primary";
  private final String alternateBucketName = "test-dataplane-" + uuid + "-alternate";

  private DataLakeServiceClient azurebfsClient;

  @Override
  public void start() {
    azurebfsClient =
        new DataLakeServiceClientBuilder()
            .credential(
                new StorageSharedKeyCredential(
                    AZURE_STORAGE_DATAPLANE_ACCOUNT_NAME, AZURE_STORAGE_DATAPLANE_ACCOUNT_KEY))
            .endpoint(String.format("https://" + accountUrlWithDfsEndpoint() + "/"))
            .buildClient();

    azurebfsClient.createFileSystem(primaryBucketName);
    azurebfsClient.createFileSystem(alternateBucketName);
  }

  @Override
  public StorageType getType() {
    return StorageType.AZURE;
  }

  @Override
  public void close() throws Exception {
    azurebfsClient.deleteFileSystem(primaryBucketName);
    azurebfsClient.deleteFileSystem(alternateBucketName);
  }

  @Override
  public String getBucketName(BucketSelection bucketSelection) {
    switch (bucketSelection) {
      case PRIMARY_BUCKET:
        return primaryBucketName;
      case ALTERNATE_BUCKET:
        return alternateBucketName;
      default:
        throw new IllegalStateException("Unexpected value: " + bucketSelection);
    }
  }

  @Override
  public boolean doesObjectExist(BucketSelection bucketSelection, String objectPath) {
    return azurebfsClient
        .getFileSystemClient(getBucketName(bucketSelection))
        .getFileClient(stripPrefix(bucketSelection, objectPath))
        .exists();
  }

  @Override
  public void putObject(String objectPath, File file) {
    // TODO: Derive container name from the path
    azurebfsClient
        .getFileSystemClient(getBucketName(BucketSelection.PRIMARY_BUCKET))
        .getFileClient(stripPrefix(BucketSelection.PRIMARY_BUCKET, objectPath))
        .uploadFromFile(file.getPath());
  }

  @Override
  public void deleteObject(BucketSelection bucketSelection, String objectPath) {
    azurebfsClient
        .getFileSystemClient(getBucketName(bucketSelection))
        .getFileClient(objectPath)
        .delete();
  }

  @Override
  public void deleteObjects(BucketSelection bucketSelection, List<String> objectPaths) {
    for (String objectPath : objectPaths) {
      deleteObject(bucketSelection, objectPath);
    }
  }

  @Override
  public Stream<String> listObjectNames(
      BucketSelection bucketSelection, String filterPath, Predicate<String> objectNameFilter) {
    return azurebfsClient
        .getFileSystemClient(getBucketName(bucketSelection))
        .listPaths(new ListPathsOptions().setRecursive(true), Duration.ofSeconds(30))
        .stream()
        .map(PathItem::getName)
        .filter(path -> path.startsWith(filterPath))
        .filter(objectNameFilter);
  }

  @Override
  public NessiePluginConfig prepareNessiePluginConfig(
      BucketSelection bucketSelection, String nessieEndpoint) {
    NessiePluginConfig nessiePluginConfig = new NessiePluginConfig();
    nessiePluginConfig.nessieEndpoint = nessieEndpoint;
    nessiePluginConfig.nessieAuthType = NessieAuthType.NONE;
    nessiePluginConfig.secure = true;

    nessiePluginConfig.storageProvider = StorageProviderType.AZURE;
    nessiePluginConfig.azureStorageAccount = AZURE_STORAGE_DATAPLANE_ACCOUNT_NAME;
    nessiePluginConfig.azureRootPath = getBucketName(bucketSelection);
    nessiePluginConfig.azureAuthenticationType = ACCESS_KEY;
    nessiePluginConfig.azureAccessKey = SecretRef.of(AZURE_STORAGE_DATAPLANE_ACCOUNT_KEY);

    return nessiePluginConfig;
  }

  @Override
  public FileIO getFileIO() {
    Configuration conf = new Configuration();
    Map<String, String> props =
        ImmutableMap.of(
            "fs.azure.account.key." + accountUrlWithDfsEndpoint(),
            AZURE_STORAGE_DATAPLANE_ACCOUNT_KEY,
            "fs.azure.account.keyprovider." + accountUrlWithDfsEndpoint(),
            "org.apache.hadoop.fs.azurebfs.services.SimpleKeyProvider");
    props.forEach(conf::set);
    HadoopFileIO hadoopFileIO = new HadoopFileIO(conf);
    hadoopFileIO.initialize(props);
    return hadoopFileIO;
  }

  private String accountUrlWithDfsEndpoint() {
    return String.format(
        "%s.dfs.core.windows.net", AzureDataplaneStorage.AZURE_STORAGE_DATAPLANE_ACCOUNT_NAME);
  }

  @Override
  public String getWarehousePath() {
    return String.format(
        "%s://%s@%s/test_tables",
        AZURE_ABFSS_SCHEME, primaryBucketName, accountUrlWithDfsEndpoint());
  }

  @Override
  public String getWarehousePath(BucketSelection bucketSelection) {
    return String.format(
        "%s://%s@%s/test_tables",
        AZURE_ABFSS_SCHEME, getBucketName(bucketSelection), accountUrlWithDfsEndpoint());
  }

  private String stripPrefix(BucketSelection bucketSelection, String objectPath) {
    if (!StringUtils.startsWithAny(
        objectPath, AZURE_WASBS_SCHEME + "://", AZURE_ABFSS_SCHEME + "://")) {
      return objectPath;
    }
    final String objectPathWithoutScheme =
        StringUtils.removeStart(objectPath, AZURE_ABFSS_SCHEME + "://");
    final String objectPathWithoutSchemeOrBucket =
        StringUtils.removeStart(objectPathWithoutScheme, getBucketName(bucketSelection));
    final String objectPathWithoutSchemeOrBucketOrServer =
        StringUtils.substringAfter(objectPathWithoutSchemeOrBucket, AZURE_DFS_AUTHORITY_SUFFIX);
    return StringUtils.removeStart(objectPathWithoutSchemeOrBucketOrServer, "/");
  }
}
