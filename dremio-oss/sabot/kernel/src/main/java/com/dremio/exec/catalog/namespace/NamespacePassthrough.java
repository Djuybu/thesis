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
package com.dremio.exec.catalog.namespace;

import com.dremio.catalog.model.CatalogEntityId;
import com.dremio.datastore.api.Document;
import com.dremio.datastore.api.FindByCondition;
import com.dremio.service.namespace.EntityNamespaceService;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.DatasetNamespaceService;
import com.dremio.service.namespace.folder.FolderNamespaceService;
import com.dremio.service.namespace.function.FunctionNamespaceService;
import com.dremio.service.namespace.home.HomeNamespaceService;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.SourceNamespaceService;
import com.dremio.service.namespace.space.SpaceNamespaceService;
import com.dremio.service.namespace.split.SplitNamespaceService;

/**
 * A temporary interface to abstract NamespaceService usage out from the architectural layers above
 * Catalog.
 */
public interface NamespacePassthrough
    extends EntityNamespaceService,
        SourceNamespaceService,
        SpaceNamespaceService,
        FunctionNamespaceService,
        HomeNamespaceService,
        FolderNamespaceService,
        DatasetNamespaceService,
        SplitNamespaceService {
  boolean existsById(CatalogEntityId id);

  Iterable<Document<NamespaceKey, NameSpaceContainer>> find(FindByCondition condition);
}
