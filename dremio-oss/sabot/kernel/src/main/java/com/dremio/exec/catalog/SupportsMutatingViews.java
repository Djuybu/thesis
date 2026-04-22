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

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogUnsupportedOperationException;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceKey;
import java.io.IOException;

/** Interface for plugins that support reading, writing, & dropping views. */
public interface SupportsMutatingViews extends SupportsReadingViews {
  /**
   * Create or update a view
   *
   * @param tableSchemaPath describes list representation of dotted schema path of a view
   * @param schemaConfig contains information needed by Catalog implementations
   * @param view representation of a view
   * @param viewOptions contains properties used for create/update view
   * @param attributes attributes that can be applied to a namespace entity
   * @return
   * @throws IOException
   * @throws CatalogUnsupportedOperationException
   * @throws CatalogEntityAlreadyExistsException
   * @throws CatalogEntityNotFoundException is thrown if the namespace or the view is not found.
   */
  boolean createOrUpdateView(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      View view,
      ViewOptions viewOptions,
      NamespaceAttribute... attributes)
      throws IOException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException;

  /**
   * Drop a view
   *
   * @param tableSchemaPath describes list representation of dotted schema path of a view
   * @param viewOptions contains properties used for dropping a view
   * @param schemaConfig contains information needed by Catalog implementations
   * @throws IOException
   * @throws CatalogEntityNotFoundException
   */
  void dropView(NamespaceKey tableSchemaPath, ViewOptions viewOptions, SchemaConfig schemaConfig)
      throws IOException, CatalogEntityNotFoundException;
}
