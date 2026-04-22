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

import static java.util.Objects.requireNonNull;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogException;
import com.dremio.catalog.exception.CatalogNoDefaultBranchException;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.planner.sql.handlers.direct.SimpleCommandResult;
import com.dremio.exec.planner.sql.handlers.direct.SqlNodeUtil;
import com.dremio.exec.planner.sql.parser.ReferenceTypeUtils;
import com.dremio.exec.planner.sql.parser.SqlCreateFolder;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.sql.SqlNode;

public class CreateFolderHandler extends BaseVersionHandler<SimpleCommandResult> {

  private final Catalog catalog;

  private final UserSession userSession;

  public CreateFolderHandler(Catalog catalog, UserSession userSession) {
    super(catalog);
    this.catalog = requireNonNull(catalog);
    this.userSession = requireNonNull(userSession);
  }

  @Override
  public List<SimpleCommandResult> toResult(String sql, SqlNode sqlNode) throws Exception {
    final SqlCreateFolder createFolder =
        requireNonNull(SqlNodeUtil.unwrap(sqlNode, SqlCreateFolder.class));
    // If the path has single item, we add context.
    NamespaceKey path = catalog.resolveSingle(createFolder.getPath());
    String sourceName = path.getRoot();
    final boolean ifNotExists = createFolder.getIfNotExists().booleanValue();
    VersionContext statementSourceVersion =
        ReferenceTypeUtils.map(createFolder.getRefType(), createFolder.getRefValue(), null);
    VersionContext sessionVersion = userSession.getSessionVersionForSource(sourceName);
    VersionContext versionContext = statementSourceVersion.orElse(sessionVersion);
    CatalogFolder inputCatalogFolder =
        new ImmutableCatalogFolder.Builder()
            .setFullPath(path.getPathComponents())
            .setVersionContext(versionContext)
            .build();
    try {
      catalog.createFolder(inputCatalogFolder);
    } catch (CatalogEntityAlreadyExistsException e) {
      if (ifNotExists) {
        return Collections.singletonList(SimpleCommandResult.successful(e.getMessage()));
      }
      throw UserException.validationError(e).message(e.getMessage()).buildSilently();
    } catch (CatalogEntityNotFoundException e) {
      throw UserException.validationError(e)
          .message("Reference %s not found in Source %s.", versionContext, sourceName)
          .buildSilently();
    } catch (CatalogNoDefaultBranchException e) {
      throw UserException.validationError(e)
          .message(
              "Unable to resolve source version. Version was not specified and Source %s does not have a default branch set.",
              sourceName)
          .buildSilently();
    } catch (CatalogException e) {
      throw UserException.validationError(e).message(e.getMessage()).buildSilently();
    }

    String successMessage =
        String.format("Folder %s has been created in source %s.", path.getName(), sourceName);
    if (versionContext.isSpecified()) {
      successMessage =
          String.format(
              "Folder %s has been created at %s in source %s.",
              path.getName(), versionContext, sourceName);
    }
    return Collections.singletonList(SimpleCommandResult.successful(successMessage));
  }

  @Override
  public Class<SimpleCommandResult> getResultType() {
    return SimpleCommandResult.class;
  }
}
