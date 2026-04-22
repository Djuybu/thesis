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

import static com.dremio.exec.planner.sql.handlers.BaseTestCreateFolderHandler.extractNamespaceKeyFromSqlNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SqlNodeUtil;
import com.dremio.exec.planner.sql.parser.ReferenceType;
import com.dremio.exec.planner.sql.parser.SqlCreateFolder;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
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
public class TestCreateFolderHandler {
  private static final String DEFAULT_CONTEXT = "@dremio";
  protected static final String NON_EXISTENT_SOURCE_NAME = "non_exist";
  private static final String DEFAULT_SOURCE_NAME = "dataplane_source_1";
  private static final String NON_VERSIONED_SOURCE_NAME = "s3Source";
  private static final String DEFAULT_FOLDER_NAME = "myFolder";
  private static final List<String> SINGLE_FOLDER_PATH = Arrays.asList(DEFAULT_FOLDER_NAME);
  private static final List<String> NON_VERSIONED_SOURCE_PATH =
      Arrays.asList(NON_VERSIONED_SOURCE_NAME, DEFAULT_FOLDER_NAME);
  private static final List<String> DEFAULT_FOLDER_PATH =
      Arrays.asList(DEFAULT_SOURCE_NAME, DEFAULT_FOLDER_NAME);
  private static final List<String> NON_EXISTENT_FOLDER_PATH =
      Arrays.asList(NON_EXISTENT_SOURCE_NAME, DEFAULT_FOLDER_NAME);
  private static final String DEV_BRANCH_NAME = "dev";

  private static final VersionContext DEV_VERSION = VersionContext.ofBranch("dev");

  @Mock private Catalog catalog;
  @Mock private UserSession userSession;
  @InjectMocks private CreateFolderHandler handler;

  private static final SqlCreateFolder NON_EXISTENT_SOURCE_INPUT =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(NON_EXISTENT_FOLDER_PATH, SqlParserPos.ZERO),
          null,
          null);

  private static final SqlCreateFolder DEFAULT_SOURCE_INPUT =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_FOLDER_PATH, SqlParserPos.ZERO),
          null,
          null);

  private static final SqlCreateFolder SINGLE_FOLDER_NAME_NO_USER_SESSION_INPUT =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(SINGLE_FOLDER_PATH, SqlParserPos.ZERO),
          null,
          null);

  private static final SqlCreateFolder SINGLE_FOLDER_NAME_WITH_USER_SESSION_INPUT =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(SINGLE_FOLDER_PATH, SqlParserPos.ZERO),
          null,
          null);

  private static final SqlCreateFolder NON_VERSIONED_SOURCE_INPUT =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(NON_VERSIONED_SOURCE_PATH, SqlParserPos.ZERO),
          null,
          null);

  private static final SqlCreateFolder WITH_REFERENCE_INPUT =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_FOLDER_PATH, SqlParserPos.ZERO),
          ReferenceType.BRANCH,
          new SqlIdentifier(DEV_BRANCH_NAME, SqlParserPos.ZERO));

  private static final SqlCreateFolder WITH_IF_NOT_EXISTS =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(true, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_FOLDER_PATH, SqlParserPos.ZERO),
          ReferenceType.BRANCH,
          new SqlIdentifier(DEV_BRANCH_NAME, SqlParserPos.ZERO));

  private static final SqlCreateFolder WITHOUT_IF_NOT_EXISTS =
      new SqlCreateFolder(
          SqlParserPos.ZERO,
          SqlLiteral.createBoolean(false, SqlParserPos.ZERO),
          new SqlIdentifier(DEFAULT_FOLDER_PATH, SqlParserPos.ZERO),
          ReferenceType.BRANCH,
          new SqlIdentifier(DEV_BRANCH_NAME, SqlParserPos.ZERO));

  /**
   * CREATE FOLDER SQL SYNTAX CREATE FOLDER [ IF NOT EXISTS ]
   * [source.]parentFolderName[.childFolder] [ AT ( REF[ERENCE) | BRANCH ) refValue ]
   */
  @Test
  public void createFolderOnNonExistentSource() throws Exception {
    NamespaceNotFoundException namespaceNotFoundException =
        new NamespaceNotFoundException("Cannot access");
    UserException nonExistUserException =
        UserException.validationError(namespaceNotFoundException)
            .message("Tried to access non-existent source [%s].", NON_EXISTENT_SOURCE_NAME)
            .build();
    when(userSession.getSessionVersionForSource(NON_EXISTENT_SOURCE_NAME))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    when(catalog.createFolder(any())).thenThrow(nonExistUserException);
    NamespaceKey namespaceKey = extractNamespaceKeyFromSqlNode(NON_EXISTENT_SOURCE_INPUT);
    when(catalog.resolveSingle(namespaceKey)).thenReturn(namespaceKey);
    assertThatThrownBy(() -> handler.toResult("", NON_EXISTENT_SOURCE_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Tried to access non-existent source");
    NamespaceKey path =
        SqlNodeUtil.unwrap(NON_EXISTENT_SOURCE_INPUT, SqlCreateFolder.class).getPath();
    verify(catalog).resolveSingle(path);
  }

  @Test
  public void createFolderInExistentSource() throws Exception {
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    NamespaceKey key = extractNamespaceKeyFromSqlNode(DEFAULT_SOURCE_INPUT);
    when(catalog.resolveSingle(key)).thenReturn(key);
    List<SimpleCommandResult> result;
    result = handler.toResult("", DEFAULT_SOURCE_INPUT);
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary).contains("Folder").contains("has been created");
    NamespaceKey path = SqlNodeUtil.unwrap(DEFAULT_SOURCE_INPUT, SqlCreateFolder.class).getPath();
    verify(catalog).resolveSingle(path);
  }

  @Test
  public void createFolderInExistentSourceWithSingleFolderNameWithoutUserSession()
      throws Exception {
    NamespaceNotFoundException namespaceNotFoundException =
        new NamespaceNotFoundException("Cannot access");
    UserException nonExistUserException =
        UserException.validationError(namespaceNotFoundException)
            .message("Tried to access non-existent source [%s].", NON_EXISTENT_SOURCE_NAME)
            .build();
    when(userSession.getSessionVersionForSource(DEFAULT_CONTEXT))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    when(catalog.createFolder(any())).thenThrow(nonExistUserException);
    when(catalog.resolveSingle(
            extractNamespaceKeyFromSqlNode(SINGLE_FOLDER_NAME_NO_USER_SESSION_INPUT)))
        .thenReturn(new NamespaceKey(Arrays.asList(DEFAULT_CONTEXT, DEFAULT_FOLDER_NAME)));

    assertThatThrownBy(() -> handler.toResult("", SINGLE_FOLDER_NAME_NO_USER_SESSION_INPUT))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Tried to access non-existent source");
    verify(catalog)
        .resolveSingle(extractNamespaceKeyFromSqlNode(SINGLE_FOLDER_NAME_NO_USER_SESSION_INPUT));
  }

  @Test
  public void createFolderInExistentSourceWithSingleFolderNameWithUserSession() throws Exception {
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME))
        .thenReturn(VersionContext.NOT_SPECIFIED);
    NamespaceKey unresolvedKey =
        extractNamespaceKeyFromSqlNode(SINGLE_FOLDER_NAME_WITH_USER_SESSION_INPUT);
    NamespaceKey resolvedKey =
        new NamespaceKey(Arrays.asList(DEFAULT_SOURCE_NAME, DEFAULT_FOLDER_NAME));
    when(catalog.resolveSingle(unresolvedKey)).thenReturn(resolvedKey);

    List<SimpleCommandResult> result;
    result = handler.toResult("", SINGLE_FOLDER_NAME_WITH_USER_SESSION_INPUT);
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary).contains("Folder").contains("has been created");
    verify(catalog).resolveSingle(unresolvedKey);
  }

  @Test
  public void createFolderWithReference() throws Exception {
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME)).thenReturn(DEV_VERSION);
    when(catalog.resolveSingle(extractNamespaceKeyFromSqlNode(WITH_REFERENCE_INPUT)))
        .thenReturn(extractNamespaceKeyFromSqlNode(WITH_REFERENCE_INPUT));

    List<SimpleCommandResult> result;
    result = handler.toResult("", WITH_REFERENCE_INPUT);
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary)
        .contains("Folder")
        .contains("has been created")
        .contains(String.format("created at branch %s", DEV_BRANCH_NAME));
    NamespaceKey path = SqlNodeUtil.unwrap(WITH_REFERENCE_INPUT, SqlCreateFolder.class).getPath();
    verify(catalog).resolveSingle(path);
  }

  @Test
  public void createFolderWithIfNotExists() throws Exception {
    String exceptionText = "Folder already exists";
    CatalogEntityAlreadyExistsException alreadyExistsException =
        new CatalogEntityAlreadyExistsException(exceptionText, new Throwable());
    when(catalog.resolveSingle(extractNamespaceKeyFromSqlNode(WITHOUT_IF_NOT_EXISTS)))
        .thenReturn(extractNamespaceKeyFromSqlNode(WITH_IF_NOT_EXISTS));
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME)).thenReturn(DEV_VERSION);
    doThrow(alreadyExistsException).when(catalog).createFolder(any());

    List<SimpleCommandResult> result;
    result = handler.toResult("", WITH_IF_NOT_EXISTS);
    assertThat(result).isNotEmpty();
    assertThat(result.get(0).ok).isTrue();
    assertThat(result.get(0).summary).contains("Folder").contains("already exists");
    NamespaceKey path = SqlNodeUtil.unwrap(WITH_IF_NOT_EXISTS, SqlCreateFolder.class).getPath();
    verify(catalog).resolveSingle(path);
  }

  @Test
  public void createFolderWithoutIfNotExists() throws Exception {
    String exceptionText = "Folder already exists";
    CatalogEntityAlreadyExistsException alreadyExistsException =
        new CatalogEntityAlreadyExistsException(exceptionText, new Throwable());
    when(catalog.resolveSingle(extractNamespaceKeyFromSqlNode(WITHOUT_IF_NOT_EXISTS)))
        .thenReturn(extractNamespaceKeyFromSqlNode(WITHOUT_IF_NOT_EXISTS));
    when(userSession.getSessionVersionForSource(DEFAULT_SOURCE_NAME)).thenReturn(DEV_VERSION);
    doThrow(alreadyExistsException).when(catalog).createFolder(any());

    assertThatThrownBy(() -> handler.toResult("", WITHOUT_IF_NOT_EXISTS))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Folder")
        .hasMessageContaining("already exists");
    NamespaceKey path = SqlNodeUtil.unwrap(WITHOUT_IF_NOT_EXISTS, SqlCreateFolder.class).getPath();
    verify(catalog).resolveSingle(path);
  }
}
