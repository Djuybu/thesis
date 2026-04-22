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

import static com.dremio.exec.catalog.CatalogConstants.SYSTEM_TABLE_SOURCE_NAME;
import static com.dremio.exec.catalog.CatalogOptions.SOURCE_ASYNC_MODIFICATION_ENABLED;
import static com.dremio.exec.catalog.CatalogOptions.SOURCE_ASYNC_MODIFICATION_TIMEOUT_MINUTES;
import static com.dremio.service.namespace.NamespaceUtils.isHomeSpace;

import com.dremio.catalog.exception.SourceAlreadyExistsException;
import com.dremio.common.DeferredException;
import com.dremio.common.exceptions.UserException;
import com.dremio.config.DremioConfig;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.proto.CatalogRPC;
import com.dremio.exec.proto.CatalogRPC.RpcType;
import com.dremio.exec.proto.CatalogRPC.SourceWrapper;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.GeneralRPCProtos;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType;
import com.dremio.exec.rpc.FutureBitCommand;
import com.dremio.exec.rpc.RpcFuture;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.server.SabotQueryContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.MissingPluginConf;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.exec.store.ischema.InfoSchemaConf;
import com.dremio.options.OptionManager;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.ClusterCoordinator.Role;
import com.dremio.service.coordinator.DistributedSemaphore.DistributedLease;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEvent;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventTopic;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEvents;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusSubscriber;
import com.dremio.service.namespace.source.SourceNamespaceService;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceChangeState;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.SourceInternalData;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.ModifiableSchedulerService;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.users.SystemUser;
import com.dremio.service.users.UserService;
import com.dremio.services.fabric.ProxyConnection;
import com.dremio.services.fabric.api.FabricRunnerFactory;
import com.dremio.services.fabric.api.FabricService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.ByteString;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtobufIOUtil;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.inject.Provider;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.tools.RuleSet;
import org.apache.calcite.tools.RuleSets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CatalogServiceImpl, manages all sources and exposure of Catalog for table retrieval.
 *
 * <p>Has several helper classes to get its job done: - Catalog is the consumable interface for
 * interacting with sources/storage plugins. - ManagedStoragePlugin keeps tracks the system state
 * for each plugin - PluginsManager is a glorified map that includes all the StoragePugins.
 */
public class CatalogServiceImpl implements CatalogService {
  private static final Logger logger = LoggerFactory.getLogger(CatalogServiceImpl.class);

  protected final Provider<CatalogSabotContext> catalogSabotContextProvider;
  // Do not use this SabotQueryContext. This is only necessary for passing to MetadataSynchronizer
  // and will be replaced by proper dependency injection in the future.
  protected final Provider<SabotQueryContext> sabotQueryContextProviderDoNotUse;
  protected final Provider<SchedulerService> scheduler;
  private final Provider<? extends Provider<ConnectionConf<?, ?>>> sysTableConfProvider;
  private final Provider<? extends Provider<ConnectionConf<?, ?>>> sysFlightTableConfProvider;
  private final Provider<FabricService> fabric;
  protected final Provider<ConnectionReader> connectionReaderProvider;

  protected LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore;
  protected NamespaceService systemNamespace;
  private PluginsManager pluginsManager;
  private BufferAllocator allocator;
  private FabricRunnerFactory tunnelFactory;
  private CatalogProtocol protocol;
  private final Provider<BufferAllocator> bufferAllocator;
  private final Provider<LegacyKVStoreProvider> kvStoreProvider;
  protected final Provider<DatasetListingService> datasetListingService;
  protected final Provider<OptionManager> optionManager;
  protected final Provider<MetadataRefreshInfoBroadcaster> broadcasterProvider;
  protected final DremioConfig config;
  protected final EnumSet<Role> roles;
  protected final CatalogServiceMonitor monitor;
  protected final Provider<ExecutorService> executorServiceProvider;
  protected ExecutorService executorService;
  private final Set<String>
      inFluxSources; // will contain any sources influx(i.e actively being modified). Otherwise
  // empty.
  protected final Predicate<String> isInFluxSource;
  protected final Provider<ModifiableSchedulerService> modifiableSchedulerServiceProvider;
  protected volatile ModifiableSchedulerService modifiableSchedulerService;
  private final Provider<VersionedDatasetAdapterFactory> versionedDatasetAdapterFactoryProvider;
  private final Provider<CatalogStatusEvents> catalogStatusEventsProvider;
  private final Provider<NamespaceService.Factory> namespaceServiceFactoryProvider;
  private final Provider<UserService> userServiceProvider;

  public CatalogServiceImpl(
      Provider<SabotContext> sabotContextProvider,
      Provider<SchedulerService> scheduler,
      Provider<? extends Provider<ConnectionConf<?, ?>>> sysTableConfProvider,
      Provider<? extends Provider<ConnectionConf<?, ?>>> sysFlightTableConfProvider,
      Provider<FabricService> fabric,
      Provider<ConnectionReader> connectionReaderProvider,
      Provider<BufferAllocator> bufferAllocator,
      Provider<LegacyKVStoreProvider> kvStoreProvider,
      Provider<DatasetListingService> datasetListingService,
      Provider<OptionManager> optionManager,
      Provider<MetadataRefreshInfoBroadcaster> broadcasterProvider,
      DremioConfig config,
      EnumSet<Role> roles,
      Provider<ModifiableSchedulerService> modifiableSchedulerService,
      Provider<VersionedDatasetAdapterFactory> versionedDatasetAdapterFactoryProvider,
      Provider<CatalogStatusEvents> catalogStatusEventsProvider,
      Provider<ExecutorService> executorServiceProvider,
      Provider<NamespaceService.Factory> namespaceServiceFactoryProvider,
      Provider<UserService> userServiceProvider) {
    this(
        sabotContextProvider,
        scheduler,
        sysTableConfProvider,
        sysFlightTableConfProvider,
        fabric,
        connectionReaderProvider,
        bufferAllocator,
        kvStoreProvider,
        datasetListingService,
        optionManager,
        broadcasterProvider,
        config,
        roles,
        CatalogServiceMonitor.DEFAULT,
        modifiableSchedulerService,
        versionedDatasetAdapterFactoryProvider,
        catalogStatusEventsProvider,
        executorServiceProvider,
        namespaceServiceFactoryProvider,
        userServiceProvider);
  }

  @VisibleForTesting
  CatalogServiceImpl(
      Provider<SabotContext> sabotContextProvider,
      Provider<SchedulerService> scheduler,
      Provider<? extends Provider<ConnectionConf<?, ?>>> sysTableConfProvider,
      Provider<? extends Provider<ConnectionConf<?, ?>>> sysFlightTableConfProvider,
      Provider<FabricService> fabric,
      Provider<ConnectionReader> connectionReaderProvider,
      Provider<BufferAllocator> bufferAllocator,
      Provider<LegacyKVStoreProvider> kvStoreProvider,
      Provider<DatasetListingService> datasetListingService,
      Provider<OptionManager> optionManager,
      Provider<MetadataRefreshInfoBroadcaster> broadcasterProvider,
      DremioConfig config,
      EnumSet<Role> roles,
      final CatalogServiceMonitor monitor,
      Provider<ModifiableSchedulerService> modifiableSchedulerService,
      Provider<VersionedDatasetAdapterFactory> versionedDatasetAdapterFactoryProvider,
      Provider<CatalogStatusEvents> catalogStatusEventsProvider,
      Provider<ExecutorService> executorServiceProvider,
      Provider<NamespaceService.Factory> namespaceServiceFactoryProvider,
      Provider<UserService> userServiceProvider) {
    this.catalogSabotContextProvider = sabotContextProvider::get;
    this.sabotQueryContextProviderDoNotUse = sabotContextProvider::get;
    this.scheduler = scheduler;
    this.sysTableConfProvider = sysTableConfProvider;
    this.sysFlightTableConfProvider = sysFlightTableConfProvider;
    this.fabric = fabric;
    this.connectionReaderProvider = connectionReaderProvider;
    this.bufferAllocator = bufferAllocator;
    this.kvStoreProvider = kvStoreProvider;
    this.datasetListingService = datasetListingService;
    this.optionManager = optionManager;
    this.broadcasterProvider = broadcasterProvider;
    this.config = config;
    this.roles = roles;
    this.monitor = new CatalogServiceLockMonitor();
    this.versionedDatasetAdapterFactoryProvider = versionedDatasetAdapterFactoryProvider;
    this.inFluxSources = ConcurrentHashMap.newKeySet();
    this.isInFluxSource = this::isInFluxSource;
    this.modifiableSchedulerServiceProvider = modifiableSchedulerService;
    this.catalogStatusEventsProvider = catalogStatusEventsProvider;
    this.executorServiceProvider = executorServiceProvider;
    this.namespaceServiceFactoryProvider = namespaceServiceFactoryProvider;
    this.userServiceProvider = userServiceProvider;
  }

  @Override
  public void start() throws Exception {
    this.allocator = bufferAllocator.get().newChildAllocator("catalog-protocol", 0, Long.MAX_VALUE);
    this.systemNamespace = namespaceServiceFactoryProvider.get().get(SystemUser.SYSTEM_USERNAME);
    this.sourceDataStore = kvStoreProvider.get().getStore(CatalogSourceDataCreator.class);
    this.modifiableSchedulerService = modifiableSchedulerServiceProvider.get();
    this.executorService = executorServiceProvider.get();
    this.modifiableSchedulerService.start();
    this.pluginsManager = newPluginsManager();
    pluginsManager.start();
    this.protocol =
        new CatalogProtocol(allocator, new CatalogChangeListener(), config.getSabotConfig());
    tunnelFactory = fabric.get().registerProtocol(protocol);

    boolean isDistributedCoordinator =
        config.isMasterlessEnabled() && roles.contains(Role.COORDINATOR);
    if (roles.contains(Role.MASTER) || isDistributedCoordinator) {
      final CountDownLatch wasRun = new CountDownLatch(1);
      final String taskName =
          optionManager.get().getOption(ExecConstants.CATALOG_SERVICE_LOCAL_TASK_LEADER_NAME);
      final Cancellable task =
          scheduler
              .get()
              .schedule(
                  Schedule.SingleShotBuilder.now()
                      .asClusteredSingleton(taskName)
                      .inLockStep()
                      .build(),
                  () -> {
                    try {
                      if (createSourceIfMissing(
                          new SourceConfig()
                              .setConfig(new InfoSchemaConf().toBytesString())
                              .setName("INFORMATION_SCHEMA")
                              .setType("INFORMATION_SCHEMA")
                              .setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY))) {
                        logger.debug("Refreshing 'INFORMATION_SCHEMA' source");
                        refreshSource(
                            new NamespaceKey("INFORMATION_SCHEMA"),
                            CatalogService.NEVER_REFRESH_POLICY,
                            SourceUpdateType.FULL);
                      }

                      if (optionManager.get().getOption(ExecConstants.ENABLE_SYSFLIGHT_SOURCE)) {
                        if (sysFlightTableConfProvider != null) {
                          logger.info("Creating SysFlight source plugin.");
                          // check if the sys plugin config type is matching as expected with the
                          // flight config type {SYSFLIGHT},
                          // if not, delete and recreate with flight config.
                          if (getPluginsManager().get(SYSTEM_TABLE_SOURCE_NAME) != null
                              && !getPluginsManager()
                                  .get(SYSTEM_TABLE_SOURCE_NAME)
                                  .getConnectionConf()
                                  .getType()
                                  .equals(sysFlightTableConfProvider.get().get().getType())) {
                            deleteSource(SYSTEM_TABLE_SOURCE_NAME);
                          }
                          createSourceIfMissing(
                              new SourceConfig()
                                  .setConnectionConf(sysFlightTableConfProvider.get().get())
                                  .setName(SYSTEM_TABLE_SOURCE_NAME)
                                  .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY));
                        }
                      } else {
                        // check if the sys plugin config type is matching as expected with the old
                        // system table config type {SYS},
                        // if not, delete and recreate with old system table config type.
                        if (getPluginsManager().get(SYSTEM_TABLE_SOURCE_NAME) != null
                            && !getPluginsManager()
                                .get(SYSTEM_TABLE_SOURCE_NAME)
                                .getConnectionConf()
                                .getType()
                                .equals(sysTableConfProvider.get().get().getType())) {
                          deleteSource(
                              SYSTEM_TABLE_SOURCE_NAME,
                              SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
                        }
                        createSourceIfMissing(
                            new SourceConfig()
                                .setConnectionConf(sysTableConfProvider.get().get())
                                .setName(SYSTEM_TABLE_SOURCE_NAME)
                                .setMetadataPolicy(CatalogService.NEVER_REFRESH_POLICY));
                      }

                      logger.debug("Refreshing {} source", SYSTEM_TABLE_SOURCE_NAME);
                      refreshSource(
                          new NamespaceKey(SYSTEM_TABLE_SOURCE_NAME),
                          CatalogService.NEVER_REFRESH_POLICY,
                          SourceUpdateType.FULL);
                    } finally {
                      wasRun.countDown();
                    }
                  });
      if (task.isScheduled()) {
        // wait till task is done only if the task is scheduled locally
        logger.debug("Awaiting local lock step schedule completion for task {}", taskName);
        wasRun.await();
        logger.debug("Lock step schedule completed for task {}", taskName);
      } else {
        logger.debug("Lock step schedule for task {} completed remotely", taskName);
      }
    }
  }

  protected PluginsManager newPluginsManager() {
    return new PluginsManager(
        catalogSabotContextProvider.get(),
        sabotQueryContextProviderDoNotUse.get(),
        systemNamespace,
        catalogSabotContextProvider.get().getOrphanageFactory().get(),
        datasetListingService.get(),
        optionManager.get(),
        config,
        sourceDataStore,
        scheduler.get(),
        connectionReaderProvider.get(),
        monitor,
        broadcasterProvider,
        isInFluxSource,
        modifiableSchedulerService,
        kvStoreProvider,
        namespaceServiceFactoryProvider.get());
  }

  public void communicateChange(SourceConfig config, RpcType rpcType) {
    final Set<NodeEndpoint> endpoints = new HashSet<>();
    endpoints.add(catalogSabotContextProvider.get().getEndpoint());

    List<RpcFuture<Ack>> futures = new ArrayList<>();
    SourceWrapper wrapper =
        SourceWrapper.newBuilder()
            .setBytes(
                ByteString.copyFrom(
                    ProtobufIOUtil.toByteArray(
                        config, SourceConfig.getSchema(), LinkedBuffer.allocate())))
            .build();
    ClusterCoordinator clusterCoordinator =
        this.catalogSabotContextProvider.get().getClusterCoordinator();
    for (NodeEndpoint e :
        Iterables.concat(
            clusterCoordinator.getCoordinatorEndpoints(),
            clusterCoordinator.getExecutorEndpoints())) {
      if (!endpoints.add(e)) {
        continue;
      }

      SendSource send = new SendSource(wrapper, rpcType);
      tunnelFactory.getCommandRunner(e.getAddress(), e.getFabricPort()).runCommand(send);
      logger.info("Sending [{}] to {}:{}", config.getName(), e.getAddress(), e.getUserPort());
      futures.add(send.getFuture());
    }

    try {
      Futures.successfulAsList(futures)
          .get(
              optionManager.get().getOption(CatalogOptions.CHANGE_COMMUNICATION_WAIT_SECONDS),
              TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e1) {
      // Error is ignored here as plugin propagation is best effort
      logger.warn("Failure while communicating source change [{}].", config.getName(), e1);
    }
  }

  private static class SendSource extends FutureBitCommand<Ack, ProxyConnection> {
    private final SourceWrapper wrapper;
    private final RpcType rpcType;

    public SendSource(SourceWrapper wrapper, RpcType rpcType) {
      this.wrapper = wrapper;
      this.rpcType = rpcType;
    }

    @Override
    public void doRpcCall(RpcOutcomeListener<Ack> outcomeListener, ProxyConnection connection) {
      connection.send(outcomeListener, rpcType, wrapper, Ack.class);
    }
  }

  class CatalogChangeListener {
    void sourceUpdate(SourceConfig config) {
      try {
        logger.info("Received source update for [{}]", config.getName());
        pluginsManager.getSynchronized(config, isInFluxSource);
      } catch (Exception ex) {
        logger.warn("Failure while synchronizing source [{}].", config.getName(), ex);
      }
    }

    void sourceDelete(SourceConfig config) {
      try {
        logger.info("Received delete source for [{}]", config.getName());

        pluginsManager.closeAndRemoveSource(config);
      } catch (Exception ex) {
        logger.warn("Failure while synchronizing source [{}].", config.getName(), ex);
      }
    }
  }

  /**
   * Delete all sources except those that are specifically referenced
   *
   * @param rootsToSaveSet Root sources to save.
   * @throws NamespaceException
   */
  @VisibleForTesting
  public void deleteExcept(Set<String> rootsToSaveSet) throws NamespaceException {
    for (SourceConfig source : systemNamespace.getSources()) {
      if (rootsToSaveSet.contains(source.getName())) {
        continue;
      }

      deleteSource(
          source,
          CatalogUser.from(SystemUser.SYSTEM_USERNAME),
          SourceRefreshOption.WAIT_FOR_DATASETS_CREATION,
          null);
    }
  }

  @VisibleForTesting
  public boolean refreshSource(
      NamespaceKey source, MetadataPolicy metadataPolicy, SourceUpdateType updateType) {
    ManagedStoragePlugin plugin = getPluginsManager().get(source.getRoot());
    if (plugin == null) {
      throw UserException.validationError()
          .message("Unknown source %s", source.getRoot())
          .build(logger);
    } else if (MissingPluginConf.TYPE.equals(plugin.getConfig().getType())) {
      return false;
    }

    return plugin.refresh(updateType, metadataPolicy);
  }

  @VisibleForTesting
  public void synchronizeSources() {
    getPluginsManager().synchronizeSources(isInFluxSource);
  }

  @Override
  public void close() throws Exception {
    DeferredException ex = new DeferredException();
    ex.suppressingClose(getPluginsManager());
    ex.suppressingClose(protocol);
    ex.suppressingClose(allocator);
    ex.close();
  }

  // used only internally, doing synchronous creation.
  private boolean createSourceIfMissing(SourceConfig config, NamespaceAttribute... attributes) {
    Preconditions.checkArgument(config.getTag() == null);
    try {
      return createSourceIfMissingWithThrow(config);
    } catch (ConcurrentModificationException ex) {
      // someone else got there first, ignore this failure.
      logger.error("Two source creations occurred simultaneously, ignoring the failed one.", ex);
      return false;
    }
  }

  @Override
  public boolean createSourceIfMissingWithThrow(SourceConfig config)
      throws ConcurrentModificationException {
    Preconditions.checkArgument(config.getTag() == null);
    if (!getPluginsManager().hasPlugin(config.getName())) {
      createSource(
          config,
          CatalogUser.from(SystemUser.SYSTEM_USERNAME),
          SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
      return true;
    }
    return false;
  }

  private void createSource(
      SourceConfig config,
      CatalogIdentity subject,
      SourceRefreshOption sourceRefreshOption,
      NamespaceAttribute... attributes) {
    final AtomicReference<AutoCloseable> sourceDistributedLock = new AtomicReference<>();

    try {
      sourceDistributedLock.set(getDistributedLock(config.getName()));
      logger.debug("Obtained distributed lock for source {}", "-source-" + config.getName());
      setinFluxSource(config.getName());

      CompletionStage<Void> future =
          getPluginsManager().create(config, subject.getName(), sourceRefreshOption, attributes);
      future.whenComplete(
          (result, throwable) -> {
            postSourceCreate(sourceDistributedLock.get(), config, throwable);
          });
    } catch (Exception e) {
      postSourceCreate(sourceDistributedLock.get(), config, e);
    }
  }

  private void updateSource(
      SourceConfig config,
      CatalogIdentity subject,
      SourceRefreshOption sourceRefreshOption,
      NamespaceAttribute... attributes) {
    Preconditions.checkArgument(config.getTag() != null);
    final AtomicReference<AutoCloseable> sourceDistributedLock = new AtomicReference<>();

    try {
      sourceDistributedLock.set(getDistributedLock(config.getName()));
      logger.debug("Obtained distributed lock for source {}", "-source-" + config.getName());
      setinFluxSource(config.getName());

      ManagedStoragePlugin plugin = getPluginsManager().get(config.getName());
      if (plugin == null) {
        throw UserException.concurrentModificationError()
            .message("Source not found.")
            .buildSilently();
      }

      final String srcType = plugin.getConfig().getType();
      if (("HOME".equalsIgnoreCase(srcType))
          || ("INTERNAL".equalsIgnoreCase(srcType))
          || ("ACCELERATION".equalsIgnoreCase(srcType))) {
        config.setAllowCrossSourceSelection(true);
      }
      CompletionStage<Void> future =
          plugin.updateSource(config, subject.getName(), sourceRefreshOption, attributes);
      future.whenComplete(
          (result, throwable) -> {
            postSourceUpdate(sourceDistributedLock.get(), config, throwable);
          });
    } catch (Exception ex) {
      postSourceUpdate(sourceDistributedLock.get(), config, ex);
    }
  }

  @VisibleForTesting
  public void deleteSource(String name) {
    deleteSource(name, SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
  }

  @VisibleForTesting
  public void deleteSource(String name, SourceRefreshOption sourceRefreshOption) {
    ManagedStoragePlugin plugin = getPluginsManager().get(name);
    if (plugin == null) {
      return;
    }
    deleteSource(
        plugin.getId().getConfig(),
        CatalogUser.from(SystemUser.SYSTEM_USERNAME),
        sourceRefreshOption,
        null);
  }

  /**
   * To delete the source, we need to: Grab the distributed source lock. Check that we have a valid
   * config.
   *
   * @param config
   * @param subject
   * @param sourceRefreshOption refresh option for the source. It can be background
   *     refresh(asynchronous refresh) or wait refresh (synchronous)
   */
  @WithSpan
  private void deleteSource(
      SourceConfig config,
      CatalogIdentity subject,
      SourceRefreshOption sourceRefreshOption,
      SourceNamespaceService.DeleteCallback callback) {
    NamespaceService namespaceService =
        catalogSabotContextProvider.get().getNamespaceService(subject.getName());
    final AtomicReference<AutoCloseable> sourceDistributedLock = new AtomicReference<>();

    try {
      sourceDistributedLock.set(getDistributedLock(config.getName()));
      logger.debug("Obtained distributed lock for source {}", "-source-" + config.getName());
      setinFluxSource(config.getName());

      if (!getPluginsManager().closeAndRemoveSource(config)) {
        throw UserException.invalidMetadataError()
            .message("Unable to remove source as the provided definition is out of date.")
            .buildSilently();
      }

      // Mark source as being deleted.
      if (optionManager.get().getOption(SOURCE_ASYNC_MODIFICATION_ENABLED)) {
        try {
          config.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_DELETING);
          systemNamespace.addOrUpdateSource(config.getKey(), config);
        } catch (ConcurrentModificationException e) {
          // One retry in case of CME.
          config = systemNamespace.getSource(config.getKey());
          config.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_DELETING);
          systemNamespace.addOrUpdateSource(config.getKey(), config);
        }
      }

      final SourceConfig finalConfig = config;
      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
                  () -> {
                    try {
                      namespaceService.deleteSourceWithCallBack(
                          finalConfig.getKey(), finalConfig.getTag(), callback);
                      sourceDataStore.delete(finalConfig.getKey());
                    } catch (NamespaceException e) {
                      logger.error("Error while running async task for deleting source", e);
                      throw new RuntimeException(e);
                    }
                  },
                  optionManager.get().getOption(SOURCE_ASYNC_MODIFICATION_ENABLED)
                      ? executorService
                      : Runnable::run)
              .whenComplete(
                  (result, throwable) -> {
                    postSourceDelete(sourceDistributedLock.get(), finalConfig, throwable);
                  });
      if (sourceRefreshOption == SourceRefreshOption.WAIT_FOR_DATASETS_CREATION
          || !optionManager.get().getOption(SOURCE_ASYNC_MODIFICATION_ENABLED)) {
        try {
          future.get(
              optionManager.get().getOption(SOURCE_ASYNC_MODIFICATION_TIMEOUT_MINUTES),
              TimeUnit.MINUTES);
        } catch (Exception e) {
          logger.error("error while waiting async source deletion.", e);
          throwUnchecked(e);
        }
      }
    } catch (Exception e) {
      postSourceDelete(sourceDistributedLock.get(), config, e);
    }
  }

  @SuppressWarnings("unchecked")
  @VisibleForTesting
  <T extends Throwable> void throwUnchecked(Throwable t) throws T {
    throw (T) t;
  }

  void postSourceCreate(AutoCloseable distributedLock, SourceConfig config, Throwable e) {
    postSourceModify(distributedLock, config, e, RpcType.REQ_SOURCE_CONFIG);
    if (e instanceof SourceAlreadyExistsException) {
      throw UserException.concurrentModificationError(e)
          .message("Source already exists with name %s.", config.getName())
          .buildSilently();
    } else if (e instanceof ConcurrentModificationException
        || e instanceof IllegalArgumentException
        || e instanceof UserException) {
      throwUnchecked(e);
    } else if (e instanceof Exception) {
      logger.error("Exception encountered: {}", e.getMessage(), e);
      throw UserException.validationError(e)
          .message("Failed to create source with name %s.", config.getName())
          .buildSilently();
    }
  }

  void postSourceUpdate(AutoCloseable distributedLock, SourceConfig config, Throwable e) {
    postSourceModify(distributedLock, config, e, RpcType.REQ_SOURCE_CONFIG);
    if (e instanceof ConcurrentModificationException) {
      throwUnchecked(e);
    } else if (e instanceof Exception) {
      throw UserException.validationError(e)
          .message("Failed to update source with name %s.", config.getName())
          .buildSilently();
    }
  }

  void postSourceDelete(AutoCloseable distributedLock, SourceConfig config, Throwable e) {
    postSourceModify(distributedLock, config, e, RpcType.REQ_DEL_SOURCE);
    if (e instanceof RuntimeException) {
      throwUnchecked(e);
    } else if (e instanceof Exception) {
      throw UserException.validationError(e)
          .message("Failure deleting source [%s].", config.getName())
          .build(logger);
    }
  }

  /** Called after source was changed or deleted. */
  void postSourceModify(
      AutoCloseable distributedLock, SourceConfig config, Throwable e, RpcType rpcType) {
    if (distributedLock == null) {
      // return early if an exception is thrown from getDistributedLock.
      // This could be because the locking server is down, or timeout because it is being modified.
      logger.debug("Failed to get distributed lock for source {}", "-source-" + config.getName());
      throw UserException.connectionError()
          .message("Unable to modify source. Source is being modified.")
          .buildSilently();
    }
    try (distributedLock) {
      // If there was an exception, attempt to roll back the source change to none in case of
      // delete. Note that create/update source change state is managed by the plugin.
      if (e != null
          && rpcType == RpcType.REQ_DEL_SOURCE
          && config.getSourceChangeState() != SourceChangeState.SOURCE_CHANGE_STATE_NONE) {
        try {
          // If the source is not there, nothing to update.
          config = systemNamespace.getSource(config.getKey());
          config.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_NONE);
          systemNamespace.addOrUpdateSource(config.getKey(), config);
        } catch (NamespaceNotFoundException ex) {
          // Expected for delete.
        } catch (ConcurrentModificationException ex) {
          // One retry in case of concurrent updates.
          config = systemNamespace.getSource(config.getKey());
          config.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_NONE);
          systemNamespace.addOrUpdateSource(config.getKey(), config);
        } catch (Exception ex) {
          logger.error("Failed to set source change state", ex);
          throw new RuntimeException(ex);
        }
      }
      if (e == null || rpcType == RpcType.REQ_DEL_SOURCE) {
        communicateChange(config, rpcType);
      }
    } catch (Exception ex) {
      logger.error("Failure while running postSourceModify", ex);
      // This will be raised on the executor thread or current thread.
      throw new RuntimeException(ex);
    } finally {
      logger.debug("Released distributed lock for source {}", "-source-" + config.getName());
      removeinFluxSource(config.getName(), e != null);
    }
  }

  @WithSpan
  private AutoCloseable getDistributedLock(String sourceName) throws Exception {
    long millis = 15_000;
    DistributedLease lease =
        catalogSabotContextProvider
            .get()
            .getClusterCoordinator()
            .getSemaphore("-source-" + sourceName.toLowerCase(), 1)
            .acquire(millis, TimeUnit.MILLISECONDS);
    if (lease == null) {
      throw UserException.resourceError()
          .message(
              "Unable to acquire source change lock for source [%s] within timeout.", sourceName)
          .build(logger);
    }
    return lease;
  }

  /**
   * Get a plugin based on a particular StoragePluginId. If the plugin exists, return it. If it
   * doesn't exist, we will create the plugin. We specifically avoid any calls to the KVStore on
   * this path. If the configuration is older than the currently active plugin with this name, we
   * will fail the provision.
   *
   * @param id
   * @return Plugin with a matching id.
   */
  private ManagedStoragePlugin getPlugin(StoragePluginId id) {
    ManagedStoragePlugin plugin = getPluginsManager().get(id.getName());

    // plugin.matches() here grabs the plugin read lock, guaranteeing that the plugin already exists
    // and has been started
    if (plugin != null && plugin.matches(id.getConfig())) {
      return plugin;
    }

    try {
      logger.debug(
          "Source [{}] does not exist/match the one in-memory, synchronizing from id",
          id.getName());
      if (isInFluxSource(id.getName())) {
        throw UserException.validationError()
            .message("Source [%s] is being modified. Try again.", id.getName())
            .build(logger);
      }
      return getPluginsManager().getSynchronized(id.getConfig(), isInFluxSource);
    } catch (Exception e) {
      logger.error("Failed to get source", e);
      throw UserException.validationError(e)
          .message("Failure getting source [%s].", id.getName())
          .build(logger);
    }
  }

  private ManagedStoragePlugin getPlugin(String name, boolean errorOnMissing) {
    ManagedStoragePlugin plugin = getPluginsManager().get(name);

    final boolean pluginFoundInPlugins = plugin != null;
    Span.current()
        .setAttribute(
            "Catalog.CatalogServiceImpl.getPlugin.pluginFoundInPlugins", pluginFoundInPlugins);

    if (pluginFoundInPlugins) {
      return plugin;
    }

    try {
      logger.debug("Synchronizing source [{}] with namespace", name);
      final boolean isSourceAnInFluxSource = isInFluxSource(name);
      Span.current()
          .setAttribute(
              "Catalog.CatalogServiceImpl.getPlugin.isInFluxSource", isSourceAnInFluxSource);

      if (isSourceAnInFluxSource) {
        if (!errorOnMissing) {
          return null;
        }
        throw UserException.validationError()
            .message("Source [%s] is being modified. Try again.", name)
            .build(logger);
      }
      SourceConfig config = systemNamespace.getSource(new NamespaceKey(name));
      return getPluginsManager().getSynchronized(config, isInFluxSource);
    } catch (NamespaceNotFoundException ex) {
      if (!errorOnMissing) {
        return null;
      }
      throw UserException.validationError(ex)
          .message("Tried to access non-existent source [%s].", name)
          .build(logger);
    } catch (Exception ex) {
      throw UserException.validationError(ex)
          .message("Failure while trying to read source [%s].", name)
          .build(logger);
    }
  }

  @Override
  @VisibleForTesting
  public ManagedStoragePlugin getManagedSource(String name) {
    return getPluginsManager().get(name);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends StoragePlugin> T getSource(StoragePluginId pluginId) {
    return (T) getPlugin(pluginId).unwrap(StoragePlugin.class);
  }

  @Override
  @WithSpan
  public SourceState getSourceState(String name) {
    try {
      ManagedStoragePlugin plugin = getPlugin(name, false);
      if (plugin == null) {
        return SourceState.badState(
            String.format("Source %s could not be found. Please verify the source name.", name),
            "Unable to find source.");
      }
      return plugin.getState();
    } catch (Exception e) {
      return SourceState.badState("", e);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends StoragePlugin> T getSource(String name) {
    return (T) getPlugin(name, true).unwrap(StoragePlugin.class);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T extends StoragePlugin> T getSource(String name, boolean skipStateCheck) {
    return (T) getPlugin(name, true).unwrap(StoragePlugin.class, skipStateCheck);
  }

  private boolean isInFluxSource(String source) {
    return inFluxSources.contains(source);
  }

  private void setinFluxSource(String sourceName) {
    // This is executing inside a Distributed Java semaphore on sourceNameso no one else could have
    // added it
    Preconditions.checkArgument(inFluxSources.add(sourceName));
  }

  private void removeinFluxSource(String sourceName, boolean skipPreCheck) {
    if (!skipPreCheck) {
      // skip precheck if this is called after an exception
      Preconditions.checkArgument(!inFluxSources.isEmpty() && inFluxSources.contains(sourceName));
    }
    // This is executing inside a Distributed Java semaphore on sourceNameso no one else could have
    // removed it
    if (!inFluxSources.remove(sourceName)) {
      logger.debug(
          "Tried to remove source {} from inFluxSources. But it was missing. ", sourceName);
    }
  }

  @Override
  public Catalog getCatalog(MetadataRequestOptions requestOptions) {
    Preconditions.checkNotNull(requestOptions, "request options are required");

    final Catalog decoratedCatalog =
        SourceAccessChecker.secureIfNeeded(requestOptions, createCatalog(requestOptions));
    return new CachingCatalog(decoratedCatalog);
  }

  protected Catalog createCatalog(MetadataRequestOptions requestOptions) {
    return createCatalog(
        requestOptions,
        new CatalogIdentityResolverImpl(catalogSabotContextProvider.get().getUserService()),
        namespaceServiceFactoryProvider.get(),
        new CatalogEntityOwnershipImpl(namespaceServiceFactoryProvider.get().getForSystemUser()),
        new UserOrRoleResolverImpl(userServiceProvider.get()));
  }

  protected Catalog createCatalog(
      MetadataRequestOptions requestOptions,
      CatalogIdentityResolver identityProvider,
      NamespaceService.Factory namespaceServiceFactory,
      CatalogEntityOwnership catalogEntityOwnership,
      UserOrRoleResolver userOrRoleResolver) {
    OptionManager optionManager = requestOptions.getSchemaConfig().getOptions();
    if (optionManager == null) {
      optionManager = catalogSabotContextProvider.get().getOptionManager();
    }

    PluginRetriever retriever = new Retriever();

    return new CatalogImpl(
        requestOptions,
        retriever,
        new SourceModifier(requestOptions.getSchemaConfig().getAuthContext().getSubject()),
        optionManager,
        catalogSabotContextProvider.get().getNamespaceService(SystemUser.SYSTEM_USERNAME),
        namespaceServiceFactory,
        catalogSabotContextProvider.get().getOrphanageFactory().get(),
        catalogSabotContextProvider.get().getDatasetListing(),
        catalogSabotContextProvider.get().getViewCreatorFactoryProvider().get(),
        identityProvider,
        new VersionContextResolverImpl(retriever),
        catalogStatusEventsProvider.get(),
        versionedDatasetAdapterFactoryProvider.get(),
        catalogSabotContextProvider.get().getMetadataIOPoolProvider().get(),
        catalogEntityOwnership,
        userOrRoleResolver);
  }

  @Override
  @WithSpan
  public boolean isSourceConfigMetadataImpacting(SourceConfig config) {
    return getPluginsManager().get(config.getName()).isSourceConfigMetadataImpacting(config);
  }

  private class Retriever implements PluginRetriever {

    @Override
    public ManagedStoragePlugin getPlugin(String name, boolean synchronizeOnMissing) {
      final String pluginName = isHomeSpace(name) ? "__home" : name;
      // Preconditions.checkState(isCoordinator);

      if (!synchronizeOnMissing) {
        // get from in-memory
        return getPluginsManager().get(pluginName);
      }
      // if plugin is missing in-memory, we will synchronize to kvstore
      return CatalogServiceImpl.this.getPlugin(pluginName, true);
    }

    @Override
    public Stream<VersionedPlugin> getAllVersionedPlugins() {
      return getPluginsManager().getAllVersionedPlugins();
    }
  }

  @Override
  public RuleSet getStorageRules(OptimizerRulesContext context, PlannerPhase phase) {
    final ImmutableSet.Builder<RelOptRule> rules = ImmutableSet.builder();
    final Set<SourceType> types = new HashSet<>();

    try {
      for (ManagedStoragePlugin plugin : getPluginsManager().managed()) {
        // we want to check state without acquiring a read lock
        if (plugin.getState().getStatus() == SourceState.SourceStatus.bad) {
          // we shouldn't consider rules for misbehaving plugins.
          continue;
        }

        StoragePluginId pluginId;
        try {
          // getId has a check for plugin state
          pluginId = plugin.getId();
        } catch (UserException e) {
          if (e.getErrorType() == ErrorType.SOURCE_BAD_STATE) {
            // we shouldn't consider rules for misbehaving plugins.
            continue;
          }
          throw e;
        }

        Optional<StoragePluginRulesFactory> factory = plugin.getRulesFactory();
        if (factory.isPresent()) {
          // add instance level rules.
          rules.addAll(factory.get().getRules(context, phase, pluginId));

          // add type level rules.
          if (types.add(pluginId.getType())) {
            rules.addAll(factory.get().getRules(context, phase, pluginId.getType()));
          }
        }
      }
    } catch (InstantiationException
        | IllegalAccessException
        | NoSuchMethodException
        | InvocationTargetException e) {
      throw UserException.validationError(e).message("Failure getting plugin rules.").build(logger);
    }

    ImmutableSet<RelOptRule> rulesSet = rules.build();
    return RuleSets.ofList(rulesSet);
  }

  PluginsManager getPluginsManager() {
    return pluginsManager;
  }

  /**
   * Proxy class for source modifications. This allows us to keep all the logic in the service while
   * restricting access to the service methods to the Catalog instances this service generates.
   */
  public class SourceModifier {
    private final CatalogIdentity subject;

    public SourceModifier(CatalogIdentity subject) {
      this.subject = subject;
    }

    public SourceModifier cloneWith(CatalogIdentity subject) {
      return new SourceModifier(subject);
    }

    public <T extends StoragePlugin> T getSource(String name) {
      return CatalogServiceImpl.this.getSource(name);
    }

    public <T extends StoragePlugin> T getSource(String name, boolean skipStateCheck) {
      return CatalogServiceImpl.this.getSource(name, skipStateCheck);
    }

    public void createSource(
        SourceConfig sourceConfig,
        SourceRefreshOption sourceRefreshOption,
        NamespaceAttribute... attributes) {
      CatalogServiceImpl.this.createSource(sourceConfig, subject, sourceRefreshOption, attributes);
    }

    public void updateSource(
        SourceConfig sourceConfig,
        SourceRefreshOption sourceRefreshOption,
        NamespaceAttribute... attributes) {
      CatalogServiceImpl.this.updateSource(sourceConfig, subject, sourceRefreshOption, attributes);
    }

    public void deleteSource(
        SourceConfig sourceConfig,
        SourceRefreshOption sourceRefreshOption,
        SourceNamespaceService.DeleteCallback callback) {
      CatalogServiceImpl.this.deleteSource(sourceConfig, subject, sourceRefreshOption, callback);
    }
  }

  @Override
  public void communicateChangeToExecutors(
      List<CoordinationProtos.NodeEndpoint> nodeEndpointList,
      SourceConfig config,
      CatalogRPC.RpcType rpcType) {
    List<RpcFuture<GeneralRPCProtos.Ack>> futures = new ArrayList<>();
    CatalogRPC.SourceWrapper wrapper =
        CatalogRPC.SourceWrapper.newBuilder()
            .setBytes(
                ByteString.copyFrom(
                    ProtobufIOUtil.toByteArray(
                        config, SourceConfig.getSchema(), LinkedBuffer.allocate())))
            .build();

    for (CoordinationProtos.NodeEndpoint e : nodeEndpointList) {
      SendSource send = new SendSource(wrapper, rpcType);
      tunnelFactory.getCommandRunner(e.getAddress(), e.getFabricPort()).runCommand(send);
      logger.info("Sending [{}] to {}:{}", config.getName(), e.getAddress(), e.getUserPort());
      futures.add(send.getFuture());
    }

    try {
      Futures.successfulAsList(futures)
          .get(
              optionManager.get().getOption(CatalogOptions.CHANGE_COMMUNICATION_WAIT_SECONDS),
              TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e1) {
      // Error is ignored here as plugin propagation is best effort
      logger.warn("Failure while communicating source change [{}].", config.getName(), e1);
    }
  }

  @Override
  public Stream<VersionedPlugin> getAllVersionedPlugins() {
    return getPluginsManager().getAllVersionedPlugins();
  }

  @Override
  public void subscribe(CatalogStatusEventTopic topic, CatalogStatusSubscriber subscriber) {
    catalogStatusEventsProvider.get().subscribe(topic, subscriber);
  }

  @Override
  public void publish(CatalogStatusEvent event) {
    catalogStatusEventsProvider.get().publish(event);
  }

  private class CatalogServiceLockMonitor implements CatalogServiceMonitor {

    private String name;

    public CatalogServiceLockMonitor() {}

    private CatalogServiceLockMonitor(String name) {
      this.name = name;
    }

    @Override
    public CatalogServiceLockMonitor forPlugin(String name) {
      return new CatalogServiceLockMonitor(name);
    }

    @Override
    public boolean isActiveSourceChange() {
      if (catalogSabotContextProvider.get() == null || name == null) {
        throw new IllegalStateException("Context and name must be set before calling this method");
      }

      try (AutoCloseable l =
          catalogSabotContextProvider
              .get()
              .getClusterCoordinator()
              .getSemaphore("-source-" + name.toLowerCase(), 1)
              .acquire(0, TimeUnit.MILLISECONDS)) {
        // if the lock cannot be acquired, the return value is null, which means that source is
        // being modified
        // otherwise, if we have lock, the source is not being modified. returns false.
        return l == null;
      } catch (Exception e) {
        // error while acquiring lock, there could be several reasons.
        // For example, zookeeper locks could be down. In this case, we do not
        // want to block users to retrieve data. Users will get blocked when
        // they are try to do a source modification which requires zk looks.
        return false;
      }
    }
  }
}
