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
package com.dremio.exec.catalog.udf;

import static com.dremio.exec.store.sys.udf.UserDefinedFunctionSerde.fromProto;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.FunctionManagingPlugin;
import com.dremio.exec.catalog.SourceCatalog;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.planner.sql.DremioSqlOperatorTable;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.sys.udf.UserDefinedFunction;
import com.dremio.exec.store.sys.udf.UserDefinedFunctionSerde;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.function.FunctionNamespaceService;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import com.google.common.collect.Iterators;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

public class UserDefinedFunctionCatalogImpl implements UserDefinedFunctionCatalog {
  private final SchemaConfig schemaConfig;
  private final OptionManager optionManager;
  private final FunctionNamespaceService userNamespaceService;
  private final SourceCatalog sourceCatalog;
  private final CatalogService catalogService;

  public UserDefinedFunctionCatalogImpl(
      SchemaConfig schemaConfig,
      OptionManager optionManager,
      FunctionNamespaceService userNamespaceService,
      CatalogService catalogService,
      SourceCatalog sourceCatalog) {
    this.schemaConfig = schemaConfig;
    this.optionManager = optionManager;
    this.userNamespaceService = userNamespaceService;
    this.sourceCatalog = sourceCatalog;
    this.catalogService = catalogService;
  }

  @Override
  public void createFunction(CatalogEntityKey key, UserDefinedFunction userDefinedFunction) {
    createOrUpdateFunction(key, userDefinedFunction, false);
  }

  @Override
  public void updateFunction(CatalogEntityKey key, UserDefinedFunction userDefinedFunction) {
    createOrUpdateFunction(key, userDefinedFunction, true);
  }

  private void createOrUpdateFunction(
      CatalogEntityKey key, UserDefinedFunction userDefinedFunction, boolean isUpdate) {
    // TODO - Block any path that is not in a space or home space.
    try {
      StoragePlugin plugin = null;
      try {
        plugin = sourceCatalog.getSource(key.getRootEntity());
      } catch (UserException ue) {
        // ignored.
      }
      if (plugin != null && plugin.isWrapperFor(FunctionManagingPlugin.class)) {
        FunctionManagingPlugin managingPlugin = plugin.unwrap(FunctionManagingPlugin.class);
        if (!isUpdate) {
          managingPlugin.createFunction(
              getKeyWithVersionContext(key), schemaConfig, userDefinedFunction);
        } else {
          managingPlugin.updateFunction(
              getKeyWithVersionContext(key), schemaConfig, userDefinedFunction);
        }
      } else {
        ensureNoVersionContext(key);

        DremioSqlOperatorTable systemFunctionTable = DremioSqlOperatorTable.instance();
        String udfName = key.toNamespaceKey().getName();
        boolean nameConflictWithSystemFunction =
            key.toNamespaceKey().getPathComponents().size() == 1
                && systemFunctionTable.getOperatorList().stream()
                    .anyMatch(operator -> operator.getName().equalsIgnoreCase(udfName));
        if (nameConflictWithSystemFunction) {
          throw UserException.validationError()
              .message(
                  "There is a system function with name %s", key.getLeaf().toUpperCase(Locale.ROOT))
              .buildSilently();
        }

        FunctionConfig newFunctionConfig = UserDefinedFunctionSerde.toProto(userDefinedFunction);
        if (isUpdate) {
          try {
            FunctionConfig oldFunctionConfig =
                userNamespaceService.getFunction(key.toNamespaceKey());
            newFunctionConfig.setTag(oldFunctionConfig.getTag()).setId(oldFunctionConfig.getId());
          } catch (NamespaceException ignore) {
            // It's fine that the tag is not copied over if the existing function not found.
          }
        }

        userNamespaceService.addOrUpdateFunction(key.toNamespaceKey(), newFunctionConfig);
      }
    } catch (UserException ue) {
      throw ue;
    } catch (Exception exception) {
      // TODO
      throw new RuntimeException(exception);
    }
  }

  @Override
  public void dropFunction(CatalogEntityKey key) {
    try {
      StoragePlugin plugin = null;
      try {
        plugin = sourceCatalog.getSource(key.getRootEntity());
      } catch (UserException ue) {
        // ignored.
      }
      if (plugin != null && plugin.isWrapperFor(FunctionManagingPlugin.class)) {
        FunctionManagingPlugin managingPlugin = plugin.unwrap(FunctionManagingPlugin.class);
        managingPlugin.dropFunction(getKeyWithVersionContext(key), schemaConfig);
      } else {
        ensureNoVersionContext(key);
        userNamespaceService.deleteFunction(key.toNamespaceKey());
      }
    } catch (Exception exception) {
      // TODO
      throw new RuntimeException(exception);
    }
  }

  @Override
  public UserDefinedFunction getFunction(CatalogEntityKey key) {
    try {
      FunctionManagingPlugin managingPlugin;
      try {
        StoragePlugin plugin = sourceCatalog.getSource(key.getRootEntity());
        if (plugin != null && plugin.isWrapperFor(FunctionManagingPlugin.class)) {
          managingPlugin = plugin.unwrap(FunctionManagingPlugin.class);
          Optional<FunctionConfig> function =
              managingPlugin.getFunction(getKeyWithVersionContext(key));
          if (function.isPresent()) {
            return fromProto(function.get());
          }
          if (key.hasTableVersionContext()) {
            throw UserException.resourceError()
                .message("Cannot find function with name %s", key)
                .buildSilently();
          }
        }
      } catch (UserException ue) {
        // ignored when source is not found

        // TODO (DX-103012): stop unraveling the user exception
        if (ue.getErrorType() != UserBitShared.DremioPBError.ErrorType.VALIDATION) {
          throw ue;
        }
      }
      // If the function is not found in the plugin, check the user namespace.
      ensureNoVersionContext(key);
      return fromProto(userNamespaceService.getFunction(key.toNamespaceKey()));
    } catch (NamespaceNotFoundException e) {
      throw UserException.resourceError()
          .message("Cannot find function with name %s.", key)
          .buildSilently();
    } catch (NamespaceException e) {
      // TODO
      throw new RuntimeException(e);
    }
  }

  @Override
  public Iterable<UserDefinedFunction> getAllFunctions() {
    List<Iterator<UserDefinedFunction>> functionList = new ArrayList<>();
    functionList.add(
        userNamespaceService.getFunctions().stream()
            .map(UserDefinedFunctionSerde::fromProto)
            .iterator());
    Stream<VersionedPlugin> versionedPlugins = catalogService.getAllVersionedPlugins();
    versionedPlugins.forEach(
        v -> {
          try {
            functionList.add(
                getFunctions(v).stream().map(UserDefinedFunctionSerde::fromProto).iterator());
          } catch (UnsupportedOperationException ex) {
            // ignore
          } catch (UserException ue) {
            // ignore when UserException is unsupported
            if (ue.getErrorType() != UserBitShared.DremioPBError.ErrorType.UNSUPPORTED_OPERATION) {
              throw ue;
            }
          }
        });

    return () -> Iterators.concat(functionList.iterator());
  }

  protected List<FunctionConfig> getFunctions(VersionedPlugin versionedPlugin) {
    try {
      return versionedPlugin.getFunctions(VersionContext.NOT_SPECIFIED);
    } catch (UnsupportedOperationException ex) {
      // ignore
    }
    return List.of();
  }

  private CatalogEntityKey getKeyWithVersionContext(CatalogEntityKey key) {
    if (!key.hasTableVersionContext()) {
      return CatalogEntityKey.newBuilder()
          .keyComponents(key.getKeyComponents())
          .tableVersionContext(TableVersionContext.NOT_SPECIFIED)
          .build();
    }

    return key;
  }

  protected OptionManager getOptionManager() {
    return optionManager;
  }

  protected SourceCatalog getCatalog() {
    return sourceCatalog;
  }

  protected FunctionNamespaceService getNamespaceService() {
    return userNamespaceService;
  }

  protected void ensureNoVersionContext(CatalogEntityKey key) {
    if (key.hasTableVersionContext()) {
      throw UserException.validationError()
          .message(
              "Version context '%s' not supported in '%s'",
              key.getTableVersionContext(), key.getRootEntity())
          .buildSilently();
    }
  }
}
