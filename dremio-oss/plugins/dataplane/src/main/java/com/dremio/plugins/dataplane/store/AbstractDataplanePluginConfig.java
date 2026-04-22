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
package com.dremio.plugins.dataplane.store;

import static com.dremio.plugins.azure.AbstractAzureStorageConf.AccountKind.STORAGE_V2;
import static com.dremio.plugins.gcs.GoogleStoragePlugin.GCS_OUTPUT_STREAM_UPLOAD_CHUNK_SIZE_DEFAULT;

import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.AzureAuthenticationType;
import com.dremio.exec.catalog.conf.DefaultCtasFormatSelection;
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.DoNotDisplay;
import com.dremio.exec.catalog.conf.GCSAuthType;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.catalog.conf.StorageProviderType;
import com.dremio.exec.store.SourceProvidedStoragePluginConfig;
import com.dremio.exec.store.dfs.CacheProperties;
import com.dremio.exec.store.dfs.FileSystemConf;
import com.dremio.exec.store.dfs.SchemaMutability;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.plugins.azure.AzureStorageFileSystem;
import com.dremio.plugins.gcs.GoogleBucketFileSystem;
import com.dremio.plugins.s3.store.S3FileSystem;
import com.dremio.plugins.utils.PluginCloudStorageProviderUtil;
import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration;
import com.google.common.base.Strings;
import io.protostuff.Tag;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Provider;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import org.apache.hadoop.fs.azurebfs.services.SharedKeyCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDataplanePluginConfig
    // TODO: DX-92696: Remove the inheritance of DataplanePlugins from FileSystemConf.
    extends FileSystemConf<AbstractDataplanePluginConfig, DataplanePlugin>
    implements SourceProvidedStoragePluginConfig {

  private static final Logger logger = LoggerFactory.getLogger(AbstractDataplanePluginConfig.class);

  // Tag 1 is used for nessieEndpoint only in Nessie

  // Tag 2 is used for nessieAccessToken only in Nessie

  @Tag(3)
  @DisplayMetadata(label = "AWS access key")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String awsAccessKey;

  @Tag(4)
  @Secret
  @DisplayMetadata(label = "AWS access secret")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public SecretRef awsAccessSecret;

  @Tag(6)
  @DisplayMetadata(label = "Connection properties")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public List<Property> propertyList = new ArrayList<>();

  @Tag(7)
  @DisplayMetadata(label = "IAM role to assume")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String assumedRoleARN;

  // Tag 8 is used for credentialType in subclasses

  @Tag(9)
  @DisplayMetadata(label = "Enable asynchronous access when possible")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public boolean asyncEnabled = true;

  @Tag(10)
  @DisplayMetadata(label = "Enable local caching when possible")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public boolean isCachingEnabled = true;

  @Tag(11)
  @Min(value = 1, message = "Max percent of total available cache space must be between 1 and 100")
  @Max(
      value = 100,
      message = "Max percent of total available cache space must be between 1 and 100")
  @DisplayMetadata(label = "Max percent of total available cache space to use when possible")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public int maxCacheSpacePct = 100;

  @Tag(12)
  @DoNotDisplay
  @DisplayMetadata(label = "Default CTAS format")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public DefaultCtasFormatSelection defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;

  // Tag 13 is reserved

  // Tag 14 is used for nessieAuthType only in Nessie

  // Tag 15 is used for awsProfile only in Nessie

  // Tag 16 is used for secure only in Nessie

  @Tag(17)
  @DisplayMetadata(label = "Storage provider")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  // Warning: This is public for easy Json deserialization. Do not access it directly, use
  // getStorageProvider() instead.
  public StorageProviderType storageProvider;

  @Tag(18)
  @DisplayMetadata(label = "Azure storage account name")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String azureStorageAccount;

  @Tag(20)
  @DisplayMetadata(label = "Authentication method")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public AzureAuthenticationType azureAuthenticationType;

  @Tag(21)
  @Secret
  @DisplayMetadata(label = "Shared access key")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public SecretRef azureAccessKey;

  @Tag(22)
  @DisplayMetadata(label = "Application ID")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String azureApplicationId;

  @Tag(23)
  @Secret
  @DisplayMetadata(label = "Client secret")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public SecretRef azureClientSecret;

  @Tag(24)
  @DisplayMetadata(label = "OAuth 2.0 token endpoint")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String azureOAuthTokenEndpoint;

  @Tag(25)
  @DisplayMetadata(label = "Google project ID")
  public String googleProjectId;

  @Tag(27)
  @DisplayMetadata(label = "Authentication method")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public GCSAuthType googleAuthenticationType;

  @Tag(28)
  @DisplayMetadata(label = "Private key ID")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String googlePrivateKeyId;

  @Tag(29)
  @Secret
  @DisplayMetadata(label = "Private key")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public SecretRef googlePrivateKey;

  @Tag(30)
  @DisplayMetadata(label = "Client email")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String googleClientEmail;

  @Tag(31)
  @DisplayMetadata(label = "Client ID")
  @NotMetadataImpacting // Dataplane plugins don't have metadata refresh, so all properties are not
  // metadata impacting
  public String googleClientId;

  // Tags 32 through 36 are defined in other Plugin
  // Tags 37 through 39 are defined in NessiePlugin for OAuth inputs

  @Override
  public CacheProperties getCacheProperties() {
    return new CacheProperties() {
      @Override
      public boolean isCachingEnabled(OptionManager optionManager) {
        return isCachingEnabled;
      }

      @Override
      public int cacheMaxSpaceLimitPct() {
        return maxCacheSpacePct;
      }
    };
  }

  @Override
  public abstract DataplanePlugin newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider);

  @Override
  public boolean isAsyncEnabled() {
    return asyncEnabled;
  }

  public StorageProviderType getStorageProvider() {
    // Legacy plugin configs before supporting multiple storage types won't have this property set,
    // assume AWS if not set.
    if (storageProvider == null) {
      return StorageProviderType.AWS;
    }
    return storageProvider;
  }

  public abstract String getRootPath();

  @Override
  public Path getPath() {
    // This is not actually allowed to be unset. This is called in FileSystemPlugin's constructor
    // and we don't want to fail there since the error message is not as clear. Empty paths will
    // fail instead during DataplanePlugin#start when calling validateRootPath.
    if (Strings.isNullOrEmpty(getRootPath())) {
      return null;
    }

    return Path.of(getRootPath());
  }

  @Override
  public boolean isImpersonationEnabled() {
    return false;
  }

  @Override
  public String getConnection() {
    switch (getStorageProvider()) {
      case AWS:
        return CloudFileSystemScheme.S3_FILE_SYSTEM_SCHEME.getScheme() + ":///";
      case AZURE:
        return CloudFileSystemScheme.AZURE_STORAGE_FILE_SYSTEM_SCHEME.getScheme() + "/";
      case GOOGLE:
        return CloudFileSystemScheme.GOOGLE_CLOUD_FILE_SYSTEM.getScheme() + ":///";
      default:
        throw new IllegalStateException("Unexpected value: " + getStorageProvider());
    }
  }

  @Override
  public boolean isPartitionInferenceEnabled() {
    return false;
  }

  @Override
  public SchemaMutability getSchemaMutability() {
    return SchemaMutability.USER_TABLE;
  }

  @Override
  public List<Property> getProperties() {
    final List<Property> properties =
        propertyList != null ? new ArrayList<>(propertyList) : new ArrayList<>();

    switch (getStorageProvider()) {
      case AWS:
        PluginCloudStorageProviderUtil.validateAndSetPropertiesForAws(
            properties, S3FileSystem.class.getName());
        break;
      case AZURE:
        String azureMode = getAzureMode(properties);
        PluginCloudStorageProviderUtil.validateAndSetPropertiesForAzure(
            properties,
            AzureStorageFileSystem.class.getName(),
            getRootPath(),
            azureMode,
            azureStorageAccount,
            azureAuthenticationType,
            azureAccessKey,
            azureClientSecret,
            azureApplicationId,
            azureOAuthTokenEndpoint,
            SharedKeyCredentials.class.getName());
        break;
      case GOOGLE:
        PluginCloudStorageProviderUtil.validateAndSetPropertiesForGcs(
            properties,
            googleProjectId,
            GoogleHadoopFileSystemConfiguration.GCS_OUTPUT_STREAM_UPLOAD_CHUNK_SIZE.getKey(),
            GCS_OUTPUT_STREAM_UPLOAD_CHUNK_SIZE_DEFAULT,
            GoogleBucketFileSystem.class.getName(),
            googleAuthenticationType,
            googleClientId,
            googleClientEmail,
            googlePrivateKeyId,
            googlePrivateKey);
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + getStorageProvider());
    }

    return properties;
  }

  /** gets the azure mode property. If the property is not present, returns the default value. */
  protected String getAzureMode(List<Property> properties) {
    return STORAGE_V2.name();
  }

  @Override
  public String getDefaultCtasFormat() {
    return defaultCtasFormat.getDefaultCtasFormat();
  }

  public abstract String getSourceTypeName();

  private static void addPropertyIfNotPresent(List<Property> propertyList, Property property) {
    if (propertyList.stream().noneMatch(p -> p.name.equals(property.name))) {
      propertyList.add(property);
    }
  }
}
