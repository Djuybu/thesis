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
package com.dremio.exec.planner.sql.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.parser.SqlDropFolder;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import java.util.Arrays;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TestDropFolderHandler {
  private static final String DEFAULT_SOURCE_NAME = "testSource";
  private static final String DEFAULT_FOLDER_NAME = "myFolder";
  private static final List<String> DEFAULT_FOLDER_PATH =
      Arrays.asList(DEFAULT_SOURCE_NAME, DEFAULT_FOLDER_NAME);

  private static final SqlDropFolder DEFAULT_SOURCE_INPUT_IF_EXISTS_STATEMENT =
      new SqlDropFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_FOLDER_PATH, SqlParserPos.ZERO),
          null,
          null);

  private static final SqlDropFolder DEFAULT_SOURCE_INPUT =
      new SqlDropFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_FOLDER_PATH, SqlParserPos.ZERO),
          null,
          null);

  @Mock private Catalog catalog;
  @Mock private UserSession userSession;
  @InjectMocks private DropFolderHandler handler;

  @Test
  public void dropExistingFolder() throws Exception {
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    doNothing().when(catalog).deleteFolder(any(CatalogEntityKey.class), any());
    when(catalog.resolveSingle(any(NamespaceKey.class)))
        .thenReturn(new NamespaceKey(DEFAULT_FOLDER_PATH));
    List<SimpleCommandResult> result;
    result = handler.toResult("", DEFAULT_SOURCE_INPUT);
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary).contains("Folder").contains("has been deleted");
  }

  @Test
  public void dropNonExistingFolderThrowingError() throws Exception {
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    when(catalog.resolveSingle(any(NamespaceKey.class)))
        .thenReturn(new NamespaceKey(DEFAULT_FOLDER_PATH));
    doThrow(new CatalogEntityNotFoundException("Folder does not exist"))
        .when(catalog)
        .deleteFolder(any(CatalogEntityKey.class), any());
    assertThatThrownBy(() -> handler.toResult("", DEFAULT_SOURCE_INPUT))
        .hasMessageContaining("Folder does not exist")
        .isInstanceOf(UserException.class);
  }

  @Test
  public void dropIfExistsNonExistingFolder() throws Exception {
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    when(catalog.resolveSingle(any(NamespaceKey.class)))
        .thenReturn(new NamespaceKey(DEFAULT_FOLDER_PATH));
    doThrow(new CatalogEntityNotFoundException("Folder does not exist"))
        .when(catalog)
        .deleteFolder(any(CatalogEntityKey.class), any());
    List<SimpleCommandResult> result;
    result = handler.toResult("", DEFAULT_SOURCE_INPUT_IF_EXISTS_STATEMENT);
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
  }
}
