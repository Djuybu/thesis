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

import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.config.DremioConfig;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.expr.ExpressionSplitCache;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.server.JobResultInfoProvider;
import com.dremio.exec.server.SchemaFetcherFactoryContext;
import com.dremio.exec.server.SimpleJobRunner;
import com.dremio.exec.server.SysFlightChannelProvider;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.dfs.FileSystemWrapper;
import com.dremio.exec.store.dfs.MetadataIOPool;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.exec.store.sys.accesscontrol.AccessControlListingManager;
import com.dremio.exec.store.sys.statistics.StatisticsListManager;
import com.dremio.exec.store.sys.udf.UserDefinedFunctionService;
import com.dremio.exec.work.WorkStats;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.resource.GroupResourceInformation;
import com.dremio.service.catalog.DatasetCatalogServiceGrpc;
import com.dremio.service.catalog.InformationSchemaServiceGrpc;
import com.dremio.service.conduit.client.ConduitProvider;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.users.UserService;
import com.dremio.services.credentials.CredentialsService;
import java.util.Set;
import javax.inject.Provider;
import org.apache.arrow.memory.BufferAllocator;
import org.projectnessie.client.api.NessieApiV2;

/** The version of SabotContext for usage in plugins. */
public interface PluginSabotContext {
  LogicalPlanPersistence getLpPersistence();

  CatalogService getCatalogService();

  Provider<SimpleJobRunner> getJobsRunner();

  FileSystemWrapper getFileSystemWrapper();

  SabotConfig getConfig();

  ScanResult getClasspathScan();

  NamespaceService getNamespaceService(String userName);

  OptionManager getOptionManager();

  Provider<NessieApiV2> getNessieApiProvider();

  Provider<DatasetCatalogServiceGrpc.DatasetCatalogServiceBlockingStub>
      getDatasetCatalogBlockingStub();

  Set<ClusterCoordinator.Role> getRoles();

  ClusterCoordinator getClusterCoordinator();

  ExpressionSplitCache getExpressionSplitCache();

  DremioConfig getDremioConfig();

  BufferAllocator getAllocator();

  LegacyKVStoreProvider getKVStoreProvider();

  OptionValidatorListing getOptionValidatorListing();

  JobResultInfoProvider getJobResultInfoProvider();

  InformationSchemaServiceGrpc.InformationSchemaServiceBlockingStub
      getInformationSchemaServiceBlockingStub();

  CoordinationProtos.NodeEndpoint getEndpoint();

  Provider<WorkStats> getWorkStatsProvider();

  AccelerationListManager getAccelerationListManager();

  AccessControlListingManager getAccessControlListingManager();

  Provider<StatisticsListManager> getStatisticsListManagerProvider();

  Provider<UserDefinedFunctionService> getUserDefinedFunctionListManagerProvider();

  GroupResourceInformation getClusterResourceInformation();

  boolean isCoordinator();

  SchemaFetcherFactoryContext getSchemaFetcherFactoryContext();

  ConduitProvider getConduitProvider();

  MetadataIOPool getMetadataIOPool();

  UserService getUserService();

  Provider<SysFlightChannelProvider> getSysFlightChannelProviderProvider();

  Provider<CredentialsService> getCredentialsServiceProvider();

  boolean isExecutor();

  Provider<ViewCreatorFactory> getViewCreatorFactoryProvider();
}
