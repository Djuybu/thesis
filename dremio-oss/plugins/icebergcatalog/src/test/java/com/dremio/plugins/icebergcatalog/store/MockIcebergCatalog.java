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
package com.dremio.plugins.icebergcatalog.store;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.view.ViewBuilder;

public class MockIcebergCatalog extends RESTCatalog implements SupportsNamespaces, Catalog {
  private int numNamespacesCreated = 0;
  private final Set<Namespace> namespaces;
  private final Map<Namespace, Map<String, String>> persistedNamespaceMetadata;
  private final Set<TableIdentifier> tableIds;
  private final Set<TableIdentifier> viewIds;
  private final String rootCatalogLocationUri;

  private boolean shouldFailGetterActions = false;

  public MockIcebergCatalog(Set<TableIdentifier> tableIdentifiers, String rootCatalogLocationUri) {
    this.tableIds = tableIdentifiers;
    this.namespaces = getNamespacesFromTables(tableIdentifiers);
    this.rootCatalogLocationUri = rootCatalogLocationUri;
    this.persistedNamespaceMetadata = buildNamespaceMetadata(namespaces, rootCatalogLocationUri);
    this.viewIds = new HashSet<>();
  }

  public void setShouldFailGetterActions(boolean shouldFailGetterActions) {
    this.shouldFailGetterActions = shouldFailGetterActions;
  }

  // Catalog Methods - START

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    return tableIds.stream()
        .filter(
            tableId ->
                (tableId.hasNamespace() && tableId.namespace().equals(namespace)
                    || (!tableId.hasNamespace() && namespace.equals(Namespace.empty()))))
        .collect(Collectors.toList());
  }

  @Override
  public Table createTable(TableIdentifier tableIdentifier, Schema schema) {
    // TODO DX-99752: Add Schema/Tables to our stored objects
    tableIds.add(tableIdentifier);
    return null;
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    if (tableIds.contains(identifier)) {
      tableIds.remove(identifier);
      return true;
    }
    return false;
  }

  @Override
  public boolean dropView(TableIdentifier identifier) {
    if (viewIds.contains(identifier)) {
      viewIds.remove(identifier);
      return true;
    }
    throw new NoSuchViewException("view does not exists");
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    if (tableIds.contains(from) && !tableIds.contains(to)) {
      tableIds.remove(from);
      tableIds.add(to);
    }
  }

  @Override
  public Table loadTable(TableIdentifier identifier) {
    if (tableIds.contains(identifier)) {
      return new MockIcebergTable(identifier, rootCatalogLocationUri);
    }
    return null;
  }

  // Catalog Methods - END

  // ViewCatalog Methods - START

  @Override
  public ViewBuilder buildView(TableIdentifier identifier) {
    if (viewIds.contains(identifier)) {
      throw new AlreadyExistsException("%s already exists", identifier.toString());
    }
    viewIds.add(identifier);
    return new MockViewBuilder();
  }

  @Override
  public boolean viewExists(TableIdentifier identifier) {
    return viewIds.contains(identifier);
  }

  @Override
  public List<TableIdentifier> listViews(Namespace namespace) {
    return viewIds.stream()
        .filter(
            tableId ->
                (tableId.hasNamespace() && tableId.namespace().equals(namespace)
                    || (!tableId.hasNamespace() && namespace.equals(Namespace.empty()))))
        .collect(Collectors.toList());
  }

  // ViewCatalog Methods - END

  // RESTCatalog Methods - START

  @Override
  public boolean tableExists(TableIdentifier tableIdentifier) {
    return tableIds.contains(tableIdentifier);
  }

  // RESTCatalog Methods - END

  // SupportsNamespaces Methods - START

  @Override
  public void createNamespace(Namespace namespace) {
    namespaces.add(namespace);
    persistedNamespaceMetadata.put(
        namespace, buildNamespaceMetadata(namespace, rootCatalogLocationUri));
    numNamespacesCreated++;
  }

  @Override
  public void createNamespace(Namespace namespace, Map<String, String> metadata) {
    namespaces.add(namespace);
    Map<String, String> enhancedMetadata = new HashMap<>(metadata);
    if (enhancedMetadata.get("location") == null) {
      enhancedMetadata.put("location", getLocationForNamespace(namespace, rootCatalogLocationUri));
    }
    persistedNamespaceMetadata.put(namespace, enhancedMetadata);
    numNamespacesCreated++;
  }

  @Override
  public List<Namespace> listNamespaces() {
    return new ArrayList<>(namespaces);
  }

  @Override
  public boolean namespaceExists(Namespace namespace) {
    return namespaces.contains(namespace);
  }

  @Override
  public Map<String, String> loadNamespaceMetadata(Namespace namespace) {
    if (shouldFailGetterActions) {
      throw new NoSuchNamespaceException("Namespace not found.");
    }
    if (!namespaces.contains(namespace)) {
      throw new NoSuchNamespaceException("Namespace not found.");
    }
    return persistedNamespaceMetadata.get(namespace);
  }

  @Override
  public boolean dropNamespace(Namespace namespace) {
    if (namespaces.contains(namespace)) {
      namespaces.remove(namespace);
      persistedNamespaceMetadata.remove(namespace);
      return true;
    }
    return false;
  }

  @Override
  public boolean setProperties(Namespace namespace, Map<String, String> properties)
      throws NoSuchNamespaceException {
    if (namespaces.contains(namespace)) {
      persistedNamespaceMetadata.put(namespace, properties);
      return true;
    }
    throw new NoSuchNamespaceException("Namespace does not exist");
  }

  @Override
  public boolean removeProperties(Namespace namespace, Set<String> properties)
      throws NoSuchNamespaceException {
    if (namespaces.contains(namespace)) {
      Map<String, String> metadata = persistedNamespaceMetadata.get(namespace);
      for (String property : properties) {
        metadata.remove(property);
      }
      persistedNamespaceMetadata.put(namespace, metadata);
      return true;
    }
    throw new NoSuchNamespaceException("Namespace does not exist");
  }

  @Override
  public List<Namespace> listNamespaces(Namespace namespace) {
    if (namespaces.contains(namespace)) {
      return namespaces.stream()
          .filter(
              innerNamespace ->
                  innerNamespace.length() == namespace.length() + 1
                      && Arrays.equals(
                          Arrays.copyOfRange(
                              innerNamespace.levels(), 0, innerNamespace.levels().length - 1),
                          namespace.levels()))
          .collect(Collectors.toList());
    } else if (namespace.isEmpty()) {
      return namespaces.stream()
          .filter(innerNamespace -> innerNamespace.length() == 1)
          .collect(Collectors.toList());
    }
    return ImmutableList.of();
  }

  // SupportsNamespaces Methods - END

  public int getNumNamespacesCreated() {
    return numNamespacesCreated;
  }

  private static Set<Namespace> getNamespacesFromTables(Set<TableIdentifier> tableIdentifiers) {
    Set<Namespace> namespaceSet = new HashSet<>();
    for (TableIdentifier tableIdentifier : tableIdentifiers) {
      namespaceSet.add(tableIdentifier.namespace());
    }
    return namespaceSet;
  }

  private static Map<Namespace, Map<String, String>> buildNamespaceMetadata(
      Set<Namespace> namespaces, String rootCatalogLocationUri) {
    Map<Namespace, Map<String, String>> metadataMap = new HashMap<>();
    for (Namespace namespace : namespaces) {
      metadataMap.put(namespace, buildNamespaceMetadata(namespace, rootCatalogLocationUri));
    }
    return metadataMap;
  }

  private static Map<String, String> buildNamespaceMetadata(
      Namespace namespace, String rootCatalogLocationUri) {
    return Map.of("location", getLocationForNamespace(namespace, rootCatalogLocationUri));
  }

  private static String getLocationForNamespace(
      Namespace namespace, String rootCatalogLocationUri) {
    return rootCatalogLocationUri + "/" + namespace.toString();
  }
}
