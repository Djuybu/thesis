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
package com.dremio.exec.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.catalog.model.dataset.TableVersionType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TestVersionedDatasetId {
  final String tableName = "table";

  final String branchName = "branchName";
  final List<String> tableKey = List.of(tableName);
  final String contentId = "contentId";

  @Test
  public void testAsString() throws JsonProcessingException {
    // Setup
    TableVersionContext sourceVersion =
        new TableVersionContext(TableVersionType.BRANCH, branchName);
    VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(tableKey)
            .setContentId(contentId)
            .setTableVersionContext(sourceVersion)
            .build();

    // Act
    String convertedDatasetId = versionedDatasetId.asString();
    Map<String, Object> jsonData =
        new ObjectMapper().readValue(convertedDatasetId, new TypeReference<>() {});
    // Assert
    assertThat(jsonData).hasSize(3).containsKeys("tableKey", "contentId", "versionContext");
  }

  @Test
  public void testFromString() throws JsonProcessingException {
    // Setup
    TableVersionContext sourceVersion =
        new TableVersionContext(TableVersionType.BRANCH, branchName);

    VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(tableKey)
            .setContentId(contentId)
            .setTableVersionContext(sourceVersion)
            .build();
    // Act
    String convertedDatasetId = versionedDatasetId.asString();

    // Assert
    assertThat(versionedDatasetId).isEqualTo(VersionedDatasetId.fromString(convertedDatasetId));
  }

  @Test
  public void testFromStringInvalid() {
    // Setup
    TableVersionContext sourceVersion =
        new TableVersionContext(TableVersionType.BRANCH, branchName);
    VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(tableKey)
            .setContentId(contentId)
            .setTableVersionContext(sourceVersion)
            .build();
    // Act
    String convertedDatasetId = versionedDatasetId.asString();
    String invalidDatasetId = convertedDatasetId.replace("contentId", "invalidContentIdToken");
    // Assert
    assertThatThrownBy(() -> VersionedDatasetId.fromString(invalidDatasetId))
        .hasMessageContaining("Unrecognized field ");
  }

  @Test
  public void testTimeTravelId() throws JsonProcessingException {
    // Setup
    long timestamp = System.currentTimeMillis();
    TableVersionContext timeTravelVersion =
        new TableVersionContext(TableVersionType.TIMESTAMP, timestamp);

    VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(tableKey)
            .setContentId(null)
            .setTableVersionContext(timeTravelVersion)
            .build();
    // Act
    String convertedDatasetId = versionedDatasetId.asString();

    // Assert
    assertThat(versionedDatasetId.getContentId()).isNull();
    assertThat(versionedDatasetId).isEqualTo(VersionedDatasetId.fromString(convertedDatasetId));
  }

  @Test
  public void testSnapshotId() throws JsonProcessingException {
    // Setup
    String snapshotId = "1000";
    TableVersionContext timeTravelVersion =
        new TableVersionContext(TableVersionType.SNAPSHOT_ID, snapshotId);

    VersionedDatasetId versionedDatasetId =
        VersionedDatasetId.newBuilder()
            .setTableKey(tableKey)
            .setContentId(null)
            .setTableVersionContext(timeTravelVersion)
            .build();
    // Act
    String convertedDatasetId = versionedDatasetId.asString();

    // Assert
    assertThat(versionedDatasetId.getContentId()).isNull();
    assertThat(versionedDatasetId).isEqualTo(VersionedDatasetId.fromString(convertedDatasetId));
  }
}
