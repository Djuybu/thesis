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
package com.dremio.plugins.utils;

import static com.dremio.exec.catalog.conf.AWSStorageConfProperties.CREATE_FILE_STATUS_CHECK;
import static com.dremio.exec.catalog.conf.AWSStorageConfProperties.DIRECTORY_MARKER_POLICY;
import static com.dremio.exec.catalog.conf.AWSStorageConfProperties.DIRECTORY_MARKER_POLICY_KEEP;
import static com.dremio.exec.catalog.conf.AzureAuthenticationType.ACCESS_KEY;
import static com.dremio.exec.catalog.conf.AzureAuthenticationType.AZURE_ACTIVE_DIRECTORY;
import static com.dremio.hadoop.security.alias.DremioCredentialProvider.DREMIO_SCHEME_PREFIX;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.conf.AzureAuthenticationType;
import com.dremio.exec.catalog.conf.AzureStorageConfProperties;
import com.dremio.exec.catalog.conf.GCSAuthType;
import com.dremio.exec.catalog.conf.GcsStorageConfProperties;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.SecretRef;
import com.google.common.base.Strings;
import java.util.List;

public class PluginCloudStorageProviderUtil {

  public static void validateAndSetPropertiesForAws(
      List<Property> properties, String fileSystemClassName) {
    properties.add(new Property("fs.dremioS3.impl", fileSystemClassName));
    properties.add(new Property("fs.dremioS3.impl.disable.cache", "true"));
    // Disable features of S3AFileSystem which incur unnecessary performance overhead.
    // User-provided values for these properties take precedence.
    addPropertyIfNotPresent(properties, new Property(CREATE_FILE_STATUS_CHECK, "false"));
    addPropertyIfNotPresent(
        properties, new Property(DIRECTORY_MARKER_POLICY, DIRECTORY_MARKER_POLICY_KEEP));
  }

  public static void validateAndSetPropertiesForAzure(
      List<Property> properties,
      String fileSystemClassName,
      String mode,
      String azureStorageAccount,
      AzureAuthenticationType azureAuthenticationType,
      SecretRef azureAccessKey,
      SecretRef azureClientSecret,
      String azureApplicationId,
      String azureOAuthTokenEndpoint,
      String keyCredentialClassName) {
    if (Strings.isNullOrEmpty(azureStorageAccount)) {
      throw UserException.validationError()
          .message(
              "Failure creating an Azure connection. You must provide an Azure storage account name [azureStorageAccount].")
          .build();
    }
    properties.add(new Property("fs.dremioAzureStorage.impl", fileSystemClassName));
    properties.add(new Property("fs.dremioAzureStorage.impl.disable.cache", "true"));
    properties.add(new Property(AzureStorageConfProperties.MODE, mode));
    properties.add(new Property(AzureStorageConfProperties.ACCOUNT, azureStorageAccount));
    applyAzureAuthenticationProperties(
        properties,
        azureAuthenticationType,
        azureAccessKey,
        azureClientSecret,
        azureApplicationId,
        azureOAuthTokenEndpoint,
        keyCredentialClassName);
  }

  public static void validateAndSetPropertiesForAzure(
      List<Property> properties,
      String fileSystemClassName,
      String rootPath,
      String mode,
      String azureStorageAccount,
      AzureAuthenticationType azureAuthenticationType,
      SecretRef azureAccessKey,
      SecretRef azureClientSecret,
      String azureApplicationId,
      String azureOAuthTokenEndpoint,
      String keyCredentialClassName) {
    properties.add(new Property(AzureStorageConfProperties.ROOT_PATH, rootPath));
    validateAndSetPropertiesForAzure(
        properties,
        fileSystemClassName,
        mode,
        azureStorageAccount,
        azureAuthenticationType,
        azureAccessKey,
        azureClientSecret,
        azureApplicationId,
        azureOAuthTokenEndpoint,
        keyCredentialClassName);
  }

  public static void validateAndSetPropertiesForGcs(
      List<Property> properties,
      String googleProjectId,
      String hadoopKey,
      String outputSize,
      String fileSystemClassName,
      GCSAuthType authType,
      String googleClientId,
      String googleClientEmail,
      String googlePrivateKeyId,
      SecretRef googlePrivateKey) {
    if (Strings.isNullOrEmpty(googleProjectId)) {
      throw UserException.validationError()
          .message(
              "Failure creating a GCS connection. You must provide a Google project ID [googleProjectId].")
          .build();
    }

    properties.add(new Property("fs.dremiogcs.impl", fileSystemClassName));
    properties.add(new Property("fs.dremiogcs.impl.disable.cache", "true"));
    addPropertyIfNotPresent(properties, new Property(hadoopKey, outputSize));
    properties.add(new Property(GcsStorageConfProperties.DREMIO_PROJECT_ID, googleProjectId));
    applyGoogleAuthenticationProperties(
        properties,
        authType,
        googleClientId,
        googleClientEmail,
        googlePrivateKeyId,
        googlePrivateKey);
  }

  private static void addPropertyIfNotPresent(List<Property> propertyList, Property property) {
    if (propertyList.stream().noneMatch(p -> p.name.equals(property.name))) {
      propertyList.add(property);
    }
  }

  private static void applyAzureAuthenticationProperties(
      List<Property> properties,
      AzureAuthenticationType azureAuthenticationType,
      SecretRef azureAccessKey,
      SecretRef azureClientSecret,
      String azureApplicationId,
      String azureOAuthTokenEndpoint,
      String keyCredentialClassName) {
    if (azureAuthenticationType == null) {
      throw UserException.validationError()
          .message(
              "Failure creating an Azure connection. You must provide an authentication method [azureAuthenticationType].")
          .build();
    }

    switch (azureAuthenticationType) {
      case ACCESS_KEY:
        if (SecretRef.isNullOrEmpty(azureAccessKey)) {
          throw UserException.validationError()
              .message(
                  "Failure creating an Azure connection. You must provide a shared access key [azureAccessKey].")
              .build();
        }

        properties.add(
            new Property(AzureStorageConfProperties.CREDENTIALS_TYPE, ACCESS_KEY.name()));
        properties.add(
            new Property(
                AzureStorageConfProperties.KEY,
                SecretRef.toConfiguration(azureAccessKey, DREMIO_SCHEME_PREFIX)));
        properties.add(
            new Property(
                AzureStorageConfProperties.AZURE_SHAREDKEY_SIGNER_TYPE, keyCredentialClassName));
        break;
      case AZURE_ACTIVE_DIRECTORY:
        if (Strings.isNullOrEmpty(azureApplicationId)
            || SecretRef.isNullOrEmpty(azureClientSecret)
            || Strings.isNullOrEmpty(azureOAuthTokenEndpoint)) {
          throw UserException.validationError()
              .message(
                  "Failure creating an Azure connection. You must provide an application ID, client secret, and OAuth endpoint [azureApplicationId, azureClientSecret, azureOAuthTokenEndpoint].")
              .build();
        }

        properties.add(
            new Property(
                AzureStorageConfProperties.CREDENTIALS_TYPE, AZURE_ACTIVE_DIRECTORY.name()));
        properties.add(new Property(AzureStorageConfProperties.CLIENT_ID, azureApplicationId));
        properties.add(
            new Property(
                AzureStorageConfProperties.CLIENT_SECRET,
                SecretRef.toConfiguration(azureClientSecret, DREMIO_SCHEME_PREFIX)));
        properties.add(
            new Property(AzureStorageConfProperties.TOKEN_ENDPOINT, azureOAuthTokenEndpoint));
        break;
      default:
        throw new IllegalStateException("Unrecognized credential type: " + azureAuthenticationType);
    }
  }

  private static void applyGoogleAuthenticationProperties(
      List<Property> properties,
      GCSAuthType googleAuthenticationType,
      String googleClientId,
      String googleClientEmail,
      String googlePrivateKeyId,
      SecretRef googlePrivateKey) {
    if (googleAuthenticationType == null) {
      throw UserException.validationError()
          .message(
              "Failure creating a GCS connection. You must provide an authentication method [googleAuthenticationType].")
          .build();
    }
    switch (googleAuthenticationType) {
      case SERVICE_ACCOUNT_KEYS:
        if (Strings.isNullOrEmpty(googleClientId)
            || Strings.isNullOrEmpty(googleClientEmail)
            || Strings.isNullOrEmpty(googlePrivateKeyId)
            || SecretRef.isNullOrEmpty(googlePrivateKey)) {
          throw UserException.validationError()
              .message(
                  "Failure creating a GCS connection. You must provide a client email, client ID, private key ID, and private key [googleClientEmail, googleClientId, googlePrivateKeyId, googlePrivateKey].")
              .build();
        }

        properties.add(new Property(GcsStorageConfProperties.DREMIO_KEY_FILE, "true"));
        properties.add(new Property(GcsStorageConfProperties.DREMIO_CLIENT_ID, googleClientId));
        properties.add(
            new Property(GcsStorageConfProperties.DREMIO_CLIENT_EMAIL, googleClientEmail));
        properties.add(
            new Property(GcsStorageConfProperties.DREMIO_PRIVATE_KEY_ID, googlePrivateKeyId));
        properties.add(
            new Property(
                GcsStorageConfProperties.DREMIO_PRIVATE_KEY,
                SecretRef.toConfiguration(googlePrivateKey, DREMIO_SCHEME_PREFIX)));
        break;
      case AUTO:
        properties.add(new Property(GcsStorageConfProperties.DREMIO_KEY_FILE, "false"));
        break;
      default:
        throw new IllegalStateException(
            "Unrecognized credential type: " + googleAuthenticationType);
    }
  }
}
