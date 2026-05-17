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
package com.dremio.service.reflection;

import static com.dremio.common.utils.SqlUtils.quotedCompound;
import static com.dremio.dac.options.ReflectionUserOptions.REFLECTION_ENABLE_SUBSTITUTION;
import static com.dremio.options.OptionValue.OptionType.SYSTEM;
import static com.dremio.service.reflection.DatasetHashUtils.computeDatasetHash;
import static com.dremio.service.reflection.ReflectionOptions.MAX_NUM_REFLECTIONS_LIMIT;
import static com.dremio.service.reflection.ReflectionOptions.MAX_NUM_REFLECTIONS_LIMIT_ENABLED;
import static com.dremio.service.reflection.ReflectionOptions.REFLECTION_MANAGER_REFRESH_DELAY_MILLIS;
import static com.dremio.service.reflection.ReflectionOptions.REFLECTION_PERIODIC_WAKEUP_ONLY;
import static com.dremio.service.reflection.ReflectionOptions.SUGGEST_REFLECTION_BASED_ON_TYPE;
import static com.dremio.service.reflection.analysis.ReflectionSuggester.ReflectionSuggestionType.ALL;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;
import static java.time.Instant.ofEpochMilli;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.SqlUtils;
import com.dremio.config.DremioConfig;
import com.dremio.context.RequestContext;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.DremioTranslatableTable;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.exec.planner.acceleration.descriptor.MaterializationDescriptor;
import com.dremio.exec.server.MaterializationDescriptorProvider;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.sys.accel.AccelerationListManager;
import com.dremio.exec.store.sys.accel.AccelerationListManager.ReflectionLineageInfo;
import com.dremio.exec.store.sys.accel.AccelerationManager.ExcludedReflectionsProvider;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.service.job.DeleteJobCountsRequest;
import com.dremio.service.job.UsedReflections;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.RefreshMethod;
import com.dremio.service.reflection.ReflectionService.BaseReflectionService;
import com.dremio.service.reflection.analysis.AutonomousReflectionIngestTask;
import com.dremio.service.reflection.analysis.ReflectionAnalyzer;
import com.dremio.service.reflection.analysis.ReflectionAnalyzer.TableStats;
import com.dremio.service.reflection.analysis.ReflectionSuggester;
import com.dremio.service.reflection.analysis.ReflectionSuggester.ReflectionSuggestionType;
import com.dremio.service.reflection.descriptor.DescriptorHelper;
import com.dremio.service.reflection.descriptor.DescriptorHelper.ExpansionHelper;
import com.dremio.service.reflection.descriptor.DescriptorHelperImpl;
import com.dremio.service.reflection.descriptor.MaterializationCache;
import com.dremio.service.reflection.descriptor.MaterializationCacheViewer;
import com.dremio.service.reflection.descriptor.MaterializationDescriptorFactory;
import com.dremio.service.reflection.descriptor.MaterializationDescriptorProviderImpl;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationId;
import com.dremio.service.reflection.proto.MaterializationPlan;
import com.dremio.service.reflection.proto.MaterializationPlanId;
import com.dremio.service.reflection.proto.ReflectionEntry;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionGoalState;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.ReflectionType;
import com.dremio.service.reflection.proto.RefreshRequest;
import com.dremio.service.reflection.refresh.RefreshHelper;
import com.dremio.service.reflection.store.DependenciesStore;
import com.dremio.service.reflection.store.ExternalReflectionStore;
import com.dremio.service.reflection.store.MaterializationPlanStore;
import com.dremio.service.reflection.store.MaterializationStore;
import com.dremio.service.reflection.store.ReflectionChangeNotificationHandler;
import com.dremio.service.reflection.store.ReflectionEntriesStore;
import com.dremio.service.reflection.store.ReflectionGoalsStore;
import com.dremio.service.reflection.store.RefreshRequestsStore;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.inject.Provider;
import org.apache.arrow.memory.BufferAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements CRUD for reflections. Owns the {@link MaterializationCache} and {@link
 * ReflectionManager}. MaterializationCache is needed by the planner to get access to the reflection
 * query trees for matching. ReflectionManager orchestrates the jobs to keep the reflections up to
 * date.
 *
 * <p>ReflectionServiceImpl runs on every coordinator and maintains its own MaterializationCache.
 * One coordinator is elected as the task leader to also run the {@link ReflectionManager}.
 */
public class ReflectionServiceImpl extends BaseReflectionService {

  private static final Logger logger = LoggerFactory.getLogger(ReflectionServiceImpl.class);

  public static final String LOCAL_TASK_LEADER_NAME = "reflectionsrefresh";

  public static final String ACCELERATOR_STORAGEPLUGIN_NAME = "__accelerator";
  private static final String REFLECTION_MANAGER_FACTORY =
      "dremio.reflection.reflection-manager-factory.class";

  private MaterializationDescriptorProvider materializationDescriptorProvider;
  private final Provider<SchedulerService> schedulerService;
  private final Provider<JobsService> jobsService;
  private final Provider<NamespaceService> namespaceService;
  private final Provider<CatalogService> catalogService;
  private final Provider<SabotContext> sabotContext;
  private final Provider<ReflectionStatusService> reflectionStatusService;
  private final Supplier<ReflectionSettings> reflectionSettingsSupplier;
  private final ExecutorService executorService;
  private final BufferAllocator allocator;
  private final DatasetEventHub datasetEventHub;
  private final ReflectionGoalsStore userStore;
  private final ReflectionEntriesStore internalStore;
  private final MaterializationStore materializationStore;
  private final MaterializationPlanStore materializationPlanStore;
  private final ExternalReflectionStore externalReflectionStore;
  private final DependenciesStore dependenciesStore;
  private final RefreshRequestsStore requestsStore;
  private final boolean isMaster;
  private final DescriptorHelper descriptorHelper;

  private MaterializationCache materializationCache;
  private ReflectionManagerWakeupHandler wakeupHandler;
  private boolean isMasterLessEnabled;

  private final Supplier<ReflectionValidator> reflectionValidatorSupplier;

  private final Provider<RequestContext> requestContextProvider;

  private ReflectionManager reflectionManager = null;

  private final Supplier<ReflectionManagerFactory> reflectionManagerFactorySupplier;
  private final SabotConfig sabotConfig;

  public ReflectionServiceImpl(
      final SabotConfig config,
      final Provider<LegacyKVStoreProvider> storeProvider,
      final Provider<SchedulerService> schedulerService,
      final Provider<JobsService> jobsService,
      final Provider<CatalogService> catalogService,
      final Provider<SabotContext> sabotContext,
      final Provider<ReflectionStatusService> reflectionStatusService,
      final ExecutorService executorService,
      final boolean isMaster,
      final BufferAllocator allocator,
      final Provider<RequestContext> requestContextProvider,
      final DatasetEventHub datasetEventHub,
      final Provider<ReflectionChangeNotificationHandler> changeNotificationHandlerProvider) {
    this.schedulerService =
        Preconditions.checkNotNull(schedulerService, "scheduler service required");
    this.jobsService = Preconditions.checkNotNull(jobsService, "jobs service required");
    this.catalogService = Preconditions.checkNotNull(catalogService, "catalog service required");
    this.sabotContext = Preconditions.checkNotNull(sabotContext, "acceleration plugin required");
    this.reflectionStatusService =
        Preconditions.checkNotNull(reflectionStatusService, "reflection status service required");
    this.executorService = Preconditions.checkNotNull(executorService, "executor service required");
    this.namespaceService = () -> sabotContext.get().getNamespaceService(SYSTEM_USERNAME);
    this.isMaster = isMaster;
    this.allocator = allocator.newChildAllocator(getClass().getName(), 0, Long.MAX_VALUE);
    this.requestContextProvider = requestContextProvider;

    userStore = new ReflectionGoalsStore(storeProvider, changeNotificationHandlerProvider);
    internalStore = new ReflectionEntriesStore(storeProvider);
    materializationStore = new MaterializationStore(storeProvider);
    materializationPlanStore = new MaterializationPlanStore(storeProvider);
    externalReflectionStore = new ExternalReflectionStore(storeProvider);
    dependenciesStore = new DependenciesStore(storeProvider);
    requestsStore = new RefreshRequestsStore(storeProvider);
    this.descriptorHelper = createDescriptorHelper(storeProvider, config);

    this.reflectionManagerFactorySupplier =
        Suppliers.memoize(
            () -> {
              final ReflectionManagerFactory defaultReflectionManagerFactory =
                  new ReflectionManagerFactory(
                      sabotContext,
                      storeProvider,
                      jobsService,
                      catalogService,
                      namespaceService,
                      executorService,
                      userStore,
                      internalStore,
                      externalReflectionStore,
                      materializationStore,
                      materializationPlanStore,
                      this::wakeupManager,
                      descriptorHelper,
                      datasetEventHub,
                      requestsStore,
                      dependenciesStore,
                      allocator,
                      () -> materializationCache);
              return config.getInstance(
                  ReflectionManagerFactory.REFLECTION_MANAGER_FACTORY,
                  ReflectionManagerFactory.class,
                  defaultReflectionManagerFactory,
                  defaultReflectionManagerFactory);
            });

    this.reflectionSettingsSupplier =
        Suppliers.memoize(() -> reflectionManagerFactorySupplier.get().newReflectionSettings());
    this.reflectionValidatorSupplier =
        Suppliers.memoize(() -> reflectionManagerFactorySupplier.get().newReflectionValidator());
    this.datasetEventHub = datasetEventHub;
    this.sabotConfig = config;
  }

  public MaterializationDescriptorProvider getMaterializationDescriptor() {
    return materializationDescriptorProvider;
  }

  @Override
  public void start() {
    logger.info("Reflection Service Starting");
    this.isMasterLessEnabled = sabotContext.get().getDremioConfig().isMasterlessEnabled();

    this.materializationCache =
        new MaterializationCache(
            descriptorHelper,
            reflectionStatusService.get(),
            catalogService.get(),
            getOptionManager(),
            materializationStore,
            internalStore);
    this.materializationDescriptorProvider =
        new MaterializationDescriptorProviderImpl(
            materializationCache,
            descriptorHelper,
            getOptionManager(),
            catalogService,
            sabotContext,
            materializationStore,
            userStore,
            reflectionStatusService.get());

    final SchedulerService scheduler = schedulerService.get();
    // only start the managers on the master node
    if (isMaster) {
      if (!isMasterLessEnabled) {
        // if it is masterful mode just init
        taskLeaderInit();
      }
      // sends a wakeup event every reflection_manager_refresh_delay
      // does not relinquish
      // important to use schedule once and reschedule since option value might
      // change dynamically
      scheduler.schedule(
          Schedule.Builder.singleShotChain()
              .startingAt(ofEpochMilli(System.currentTimeMillis() + getRefreshTimeInMillis()))
              .asClusteredSingleton(LOCAL_TASK_LEADER_NAME)
              .sticky()
              .withCleanup(this::doCleanup)
              .build(),
          new Runnable() {
            @Override
            public void run() {
              if (scheduler.isRollingUpgradeInProgress(LOCAL_TASK_LEADER_NAME)) {
                logger.info(
                    "Reflection Service - Postponing taskLeaderInit as rolling upgrade is in progress");
              } else {
                logger.debug("Periodic refresh");
                if (wakeupHandler == null) {
                  taskLeaderInit();
                }
                wakeupManager("periodic refresh", true);
              }
              schedulerService
                  .get()
                  .schedule(
                      Schedule.Builder.singleShotChain()
                          .startingAt(
                              ofEpochMilli(System.currentTimeMillis() + getRefreshTimeInMillis()))
                          .asClusteredSingleton(LOCAL_TASK_LEADER_NAME)
                          .sticky()
                          .withCleanup(() -> doCleanup())
                          .build(),
                      this);
            }
          });

      startAutonomousReflectionIngestTask();
    }
  }

  private void startAutonomousReflectionIngestTask() {
    // Ingest settings live in dremio.conf (DremioConfig), not SabotConfig.
    final DremioConfig dremioConfig = sabotContext.get().getDremioConfig();
    if (!dremioConfig.hasPath(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_ENABLED)
        || !dremioConfig.getBoolean(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_ENABLED)) {
      logger.debug(
          "Autonomous reflection ingest task is disabled (set {} = true to enable)",
          DremioConfig.AUTONOMOUS_REFLECTION_INGEST_ENABLED);
      return;
    }
    String ingestUrl = dremioConfig.getString(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_URL);
    String ingestToken =
        dremioConfig.hasPath(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_TOKEN)
            ? dremioConfig.getString(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_TOKEN)
            : "";
    long intervalMin =
        dremioConfig.hasPath(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_INTERVAL_MINUTES)
            ? dremioConfig.getLong(DremioConfig.AUTONOMOUS_REFLECTION_INGEST_INTERVAL_MINUTES)
            : 5L;

    AutonomousReflectionIngestTask ingestTask =
        new AutonomousReflectionIngestTask(
            jobsService, schedulerService, ingestUrl, ingestToken, intervalMin);
    ingestTask.start();
    logger.info(
        "Autonomous reflection ingest task started (url={}, interval={}min)",
        ingestUrl,
        intervalMin);
  }

  @VisibleForTesting
  DescriptorHelper createDescriptorHelper(
      final Provider<LegacyKVStoreProvider> storeProvider, final SabotConfig config) {
    return new DescriptorHelperImpl(
        catalogService,
        sabotContext,
        materializationStore,
        internalStore,
        externalReflectionStore,
        userStore,
        materializationPlanStore,
        dependenciesStore,
        new ReflectionSettingsImpl(
            namespaceService, catalogService, storeProvider, this::getOptionManager),
        config.getInstance(
            "dremio.reflection.materialization.descriptor.factory",
            MaterializationDescriptorFactory.class,
            ReflectionUtils::getMaterializationDescriptor),
        ReflectionUtils.createReflectionUtils(config));
  }

  /** Cleanup when we lose task leader status. May not be called if node is terminated. */
  private void doCleanup() {
    logger.info("Reflection Service - taskLeaderCleanup - lost ownership of RM schedule");
    reflectionManager = null;
    wakeupHandler = null;
  }

  private synchronized void taskLeaderInit() {
    if (wakeupHandler != null) {
      logger.info("Reflection Service - taskLeaderInit already done");
      return;
    }
    logger.info("Reflection Service - taskLeaderInit");

    final FileSystemPlugin accelerationPlugin =
        sabotContext
            .get()
            .getCatalogService()
            .getSource(ReflectionServiceImpl.ACCELERATOR_STORAGEPLUGIN_NAME);

    reflectionManager =
        reflectionManagerFactorySupplier
            .get()
            .newReflectionManager(reflectionSettingsSupplier.get(), requestContextProvider);

    wakeupHandler =
        reflectionManagerFactorySupplier
            .get()
            .newWakeupHandler(executorService, reflectionManager, requestContextProvider);
  }

  public RefreshHelper getRefreshHelper() {

    return new RefreshHelper() {

      @Override
      public ReflectionSettings getReflectionSettings() {
        return reflectionSettingsSupplier.get();
      }

      @Override
      public MaterializationStore getMaterializationStore() {
        return materializationStore;
      }

      @Override
      public MaterializationPlanStore getMaterializationPlanStore() {
        return materializationPlanStore;
      }

      @Override
      public CatalogService getCatalogService() {
        return catalogService.get();
      }

      @Override
      public DependenciesStore getDependenciesStore() {
        return dependenciesStore;
      }

      @Override
      public DescriptorHelper getDescriptorHelper() {
        return descriptorHelper;
      }
    };
  }

  @Override
  public ExcludedReflectionsProvider getExcludedReflectionsProvider(
      boolean allowEmptyExclusionList) {
    // Ideally there should be no requirement that the REFRESH REFLECTION job has to be planned
    // on the same coordinator as the task leader
    DependencyManager dependencyManager =
        Optional.ofNullable(reflectionManager)
            .map(ReflectionManager::getDependencyManager)
            .orElse(null);
    if (dependencyManager == null && allowEmptyExclusionList) {
      return new ExcludedReflectionsProvider() {
        @Override
        public List<String> getExcludedReflections(String rId) {
          return ImmutableList.of();
        }
      };
    }
    Preconditions.checkNotNull(
        dependencyManager, "REFLECTION REFRESH job not running on task leader");
    return dependencyManager.getExcludedReflectionsProvider();
  }

  private long getRefreshTimeInMillis() {
    return getOptionManager().getOption(REFLECTION_MANAGER_REFRESH_DELAY_MILLIS);
  }

  public MaterializationCache getMaterializationCache() {
    return materializationCache;
  }

  @Override
  public void close() throws Exception {
    allocator.close();
  }

  @Override
  public ReflectionId create(ReflectionGoal goal) {
    long maxAllowed = getOptionManager().getOption(MAX_NUM_REFLECTIONS_LIMIT);
    if (getOptionManager().getOption(MAX_NUM_REFLECTIONS_LIMIT_ENABLED)
        && userStore.getNumAllNotDeleted() >= maxAllowed) {
      throw UserException.validationError()
          .message("Maximum number of allowed reflections exceeded (%d)", maxAllowed)
          .buildSilently();
    }

    try {
      Preconditions.checkArgument(goal.getId() == null, "new reflection shouldn't have an ID");
      Preconditions.checkState(goal.getTag() == null, "new reflection shouldn't have a version");
      reflectionValidatorSupplier.get().validate(goal, false);
    } catch (Exception e) {
      throw UserException.validationError()
          .message("Invalid reflection: %s", e.getMessage())
          .build(logger);
    }

    final ReflectionId reflectionId = new ReflectionId(UUID.randomUUID().toString());
    goal.setId(reflectionId);

    userStore.save(goal);

    logger.debug("Created reflection goal for {}", ReflectionUtils.getId(goal));
    wakeupManager("reflection goal created");
    return reflectionId;
  }

  @Override
  public ReflectionId createExternalReflection(
      String name, List<String> datasetPath, List<String> targetDatasetPath) {
    ReflectionId id = new ReflectionId(UUID.randomUUID().toString());
    try {
      Catalog catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
      DatasetConfig datasetConfig =
          CatalogUtil.getDatasetConfig(catalog, new NamespaceKey(datasetPath));
      if (datasetConfig == null) {
        throw UserException.validationError()
            .message(String.format("Dataset %s not found", quotedCompound(datasetPath)))
            .build(logger);
      }

      DatasetConfig targetDatasetConfig =
          CatalogUtil.getDatasetConfig(catalog, new NamespaceKey(targetDatasetPath));
      if (targetDatasetConfig == null) {
        throw UserException.validationError()
            .message(String.format("Dataset %s not found", quotedCompound(targetDatasetPath)))
            .build(logger);
      }
      ExternalReflection externalReflection =
          new ExternalReflection()
              .setId(id.getId())
              .setName(name)
              .setQueryDatasetId(datasetConfig.getId().getId())
              .setQueryDatasetHash(computeDatasetHash(datasetConfig, catalog, true))
              .setTargetDatasetId(targetDatasetConfig.getId().getId())
              .setTargetDatasetHash(computeDatasetHash(targetDatasetConfig, catalog, true));

      // check that we are able to get a MaterializationDescriptor before storing it
      MaterializationDescriptor descriptor =
          ReflectionUtils.getMaterializationDescriptor(externalReflection, catalog);
      if (descriptor == null) {
        throw UserException.validationError()
            .message("Failed to validate external reflection " + name)
            .build(logger);
      }

      // validate that we can convert to a materialization
      try (ExpansionHelper helper = descriptorHelper.getExpansionHelper().apply(catalog)) {
        descriptor.getMaterializationFor(helper.getConverter());
      }
      externalReflectionStore.addExternalReflection(externalReflection);
      return id;
    } catch (NamespaceException e) {
      throw UserException.validationError(e).build(logger);
    }
  }

  @Override
  public Optional<ExternalReflection> getExternalReflectionById(String id) {
    return Optional.ofNullable(externalReflectionStore.get(id));
  }

  @Override
  public Iterable<ExternalReflection> getExternalReflectionByDatasetPath(List<String> datasetPath) {
    EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
    DatasetConfig datasetConfig =
        CatalogUtil.getDatasetConfig(catalog, new NamespaceKey(datasetPath));
    if (datasetConfig == null) {
      throw UserException.validationError()
          .message(String.format("Dataset %s not found", quotedCompound(datasetPath)))
          .build(logger);
    }
    return externalReflectionStore.findByDatasetId(datasetConfig.getId().getId());
  }

  @Override
  public Iterable<ExternalReflection> getAllExternalReflections() {
    return externalReflectionStore.getExternalReflections();
  }

  @Override
  public void dropExternalReflection(String id) {
    externalReflectionStore.deleteExternalReflection(id);
    try {
      jobsService
          .get()
          .deleteJobCounts(
              DeleteJobCountsRequest.newBuilder()
                  .setReflections(UsedReflections.newBuilder().addReflectionIds(id).build())
                  .build());
    } catch (Exception e) {
      logger.error("Unable to delete job counts for external reflection : {}", id, e);
    }
  }

  @Override
  public void update(ReflectionGoal goal, ChangeCause changeCause) {
    try {
      Preconditions.checkNotNull(goal, "reflection goal required");
      Preconditions.checkNotNull(goal.getId(), "reflection id required");
      Preconditions.checkNotNull(goal.getTag(), "reflection version required");

      ReflectionGoal currentGoal =
          getGoal(goal.getId())
              .orElseThrow(
                  () ->
                      UserException.validationError()
                          .message("Reflection not found: %s", goal.getId())
                          .build(logger));

      if (currentGoal.getState() == ReflectionGoalState.DELETED) {
        throw UserException.validationError()
            .message("Cannot update a deleted reflection")
            .build(logger);
      }

      if (currentGoal.getType() != goal.getType()) {
        throw UserException.validationError()
            .message("Cannot change the type of an existing reflection")
            .build(logger);
      }

      if (!currentGoal.getDatasetId().equals(goal.getDatasetId())) {
        throw UserException.validationError()
            .message("Cannot change the dataset id of an existing reflection")
            .build(logger);
      }

      if (goal.getState() == ReflectionGoalState.ENABLED) {
        reflectionValidatorSupplier.get().validate(goal, false);
      }

      // check if anything has changed - if not, don't bother updating
      if (currentGoal.getName().equals(goal.getName())
          && currentGoal.getState() == goal.getState()
          && ReflectionUtils.areReflectionDetailsEqual(
              currentGoal.getDetails(), goal.getDetails())) {
        return;
      }

      // Inherit the createdAt time from the current goal.
      goal.setCreatedAt(currentGoal.getCreatedAt());
    } catch (Exception e) {
      throw UserException.validationError()
          .message("Invalid reflection: %s", e.getMessage())
          .build(logger);
    }

    userStore.save(goal);
    wakeupManager("reflection goal updated");
  }

  @Override
  public Optional<ReflectionEntry> getEntry(ReflectionId reflectionId) {
    return Optional.ofNullable(internalStore.get(reflectionId));
  }

  @Override
  public void saveReflectionEntryField(
      ReflectionEntry reflectionEntry, BiConsumer<ReflectionEntry, ReflectionEntry> updateField) {
    executeWithRetry(
        () -> {
          ReflectionEntry entryToSave = internalStore.get(reflectionEntry.getId());
          if (entryToSave == null) {
            logger.error(
                "Failed to save reflection entry for reflection {} - entry doesn't exist.",
                reflectionEntry.getId().getId());
            return;
          }
          updateField.accept(entryToSave, reflectionEntry);
          internalStore.save(entryToSave);
        },
        reflectionEntry.getId().getId());
  }

  private void executeWithRetry(Runnable action, String reflectionId) {
    int retryCount = 0;
    ConcurrentModificationException lastException = null;

    while (retryCount < 3) {
      try {
        action.run();
        return;
      } catch (ConcurrentModificationException e) {
        retryCount++;
        lastException = e;
      }
    }

    logger.error(
        "Failed to save reflection entry after {} attempts for reflection {}",
        retryCount,
        reflectionId,
        lastException);
  }

  @Override
  public Optional<ReflectionGoal> getGoal(ReflectionId reflectionId) {
    final ReflectionGoal goal = userStore.get(reflectionId);
    if (goal == null || goal.getState() == ReflectionGoalState.DELETED) {
      return Optional.empty();
    }
    return Optional.of(goal);
  }

  @Override
  public void clearAll() {
    final Iterable<ReflectionGoal> reflections = userStore.getAll();
    List<String> deleteReflectionIds = new ArrayList<>();
    for (ReflectionGoal goal : reflections) {
      if (goal.getState() != ReflectionGoalState.DELETED) {
        userStore.save(goal.setState(ReflectionGoalState.DELETED));
        deleteReflectionIds.add(goal.getId().getId());
      }
    }
    try {
      if (deleteReflectionIds.size() > 0) {
        jobsService
            .get()
            .deleteJobCounts(
                DeleteJobCountsRequest.newBuilder()
                    .setReflections(
                        UsedReflections.newBuilder()
                            .addAllReflectionIds(deleteReflectionIds)
                            .build())
                    .build());
      }
    } catch (Exception e) {
      logger.error("Unable to delete job counts for reflection : {}", deleteReflectionIds, e);
    }
  }

  @Override
  public void retryUnavailable() {
    for (ReflectionGoal goal : userStore.getAll()) {
      if (goal.getType() == ReflectionType.EXTERNAL
          || goal.getState() != ReflectionGoalState.ENABLED) {
        continue;
      }
      try {
        if (materializationStore.getLastMaterializationDone(goal.getId()) == null) {
          userStore.save(goal);
        }
      } catch (RuntimeException e) {
        logger.warn("Unable to retry unavailable {}", ReflectionUtils.getId(goal), e);
      }
    }
  }

  @Override
  public void clean() {
    for (Map.Entry<MaterializationPlanId, MaterializationPlan> plan :
        materializationPlanStore.getAll()) {
      try {
        materializationPlanStore.delete(plan.getKey());
      } catch (RuntimeException e) {
        logger.warn("Unable to clean {}", plan.getValue().getId().toString());
      }
    }
  }

  @VisibleForTesting
  public Iterable<DependencyEntry> getDependencies(ReflectionId reflectionId) {
    // Only used for testing so that we can validate reflection dependencies
    return reflectionManager.getDependencyManager().getDependencies(reflectionId);
  }

  private Stream<AccelerationListManager.DependencyInfo> getGoalDependencies(ReflectionGoal goal) {
    ReflectionId goalId = goal.getId();
    final List<DependencyEntry> dependencyEntries =
        reflectionManager.getDependencyManager().getDependencies(goalId);

    return StreamSupport.stream(dependencyEntries.spliterator(), false)
        .map(
            new Function<DependencyEntry, AccelerationListManager.DependencyInfo>() {
              @Override
              public AccelerationListManager.DependencyInfo apply(DependencyEntry entry) {
                return new AccelerationListManager.DependencyInfo(
                    goalId.getId(),
                    entry.getId(),
                    entry.getType().toString(),
                    entry.getPath().toString());
              }
            });
  }

  @Override
  public Iterator<AccelerationListManager.DependencyInfo> getReflectionDependencies() {
    final Iterable<ReflectionGoal> goalReflections = getAllReflections();
    return StreamSupport.stream(goalReflections.spliterator(), false)
        .flatMap(this::getGoalDependencies)
        .iterator();
  }

  @Override
  public Iterable<ReflectionGoal> getAllReflections() {
    return ReflectionUtils.getAllReflections(userStore);
  }

  @VisibleForTesting
  @Override
  public Iterable<ReflectionGoal> getReflectionsByDatasetPath(CatalogEntityKey path) {
    String datasetId = null;
    final Catalog catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
    // TODO: DX-88987
    // Use Catalog.getDatasetId with full CatalogEntityKey including version context. This class
    // shouldn't have to decide how to act according to the source type.
    if (CatalogUtil.requestedPluginSupportsVersionedTables(path.getRootEntity(), catalog)) {
      DremioTable table = catalog.getTable(path);
      if (table != null) {
        datasetId = table.getDatasetConfig().getId().getId();
      }
    } else {
      datasetId = catalog.getDatasetId(path.toNamespaceKey());
    }

    if (datasetId == null) {
      return Collections.emptyList();
    }
    return getReflectionsByDatasetId(datasetId);
  }

  @VisibleForTesting
  public Iterable<ReflectionGoal> getReflectionGoals(
      final NamespaceKey path, final String reflectionName) {
    EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
    DatasetConfig datasetConfig = CatalogUtil.getDatasetConfig(catalog, path);
    if (datasetConfig == null) {
      Throwables.propagate(new NamespaceNotFoundException(path, "Dataset not found in catalog"));
    }
    return FluentIterable.from(getReflectionsByDatasetId(datasetConfig.getId().getId()))
        .filter(
            new Predicate<ReflectionGoal>() {

              @Override
              public boolean apply(ReflectionGoal input) {
                return reflectionName.equals(input.getName());
              }
            });
  }

  @Override
  @WithSpan
  public Iterable<ReflectionGoal> getReflectionsByDatasetId(String datasetId) {
    return userStore.getByDatasetId(datasetId);
  }

  @Override
  public int getEnabledReflectionCountForDataset(String datasetId) {
    return userStore.getEnabledByDatasetId(datasetId);
  }

  @Override
  public Optional<Materialization> getLastDoneMaterialization(ReflectionId reflectionId) {
    final Materialization materialization =
        materializationStore.getLastMaterializationDone(reflectionId);
    if (materialization == null) {
      return Optional.empty();
    }
    return Optional.of(materialization);
  }

  @Override
  public void setSubstitutionEnabled(boolean enable) {
    getOptionManager()
        .setOption(
            OptionValue.createBoolean(
                SYSTEM, REFLECTION_ENABLE_SUBSTITUTION.getOptionName(), enable));
  }

  @Override
  public boolean isSubstitutionEnabled() {
    return getOptionManager().getOption(REFLECTION_ENABLE_SUBSTITUTION);
  }

  @Override
  public Iterable<Materialization> getMaterializations(ReflectionId reflectionId) {
    return materializationStore.find(reflectionId);
  }

  @VisibleForTesting
  public void remove(ReflectionId id, ChangeCause changeCause) {
    Optional<ReflectionGoal> goal = getGoal(id);
    if (goal.isPresent() && goal.get().getState() != ReflectionGoalState.DELETED) {
      update(goal.get().setState(ReflectionGoalState.DELETED), changeCause);
      try {
        jobsService
            .get()
            .deleteJobCounts(
                DeleteJobCountsRequest.newBuilder()
                    .setReflections(
                        UsedReflections.newBuilder()
                            .addReflectionIds(goal.get().getId().getId())
                            .build())
                    .build());
      } catch (Exception e) {
        logger.error(
            "Unable to delete job counts for reflection : {}", goal.get().getId().getId(), e);
      }

      datasetEventHub.fireRemoveDatasetEvent(new DatasetRemovedEvent(goal.get().getDatasetId()));
    }
  }

  @Override
  public void remove(ReflectionGoal goal, ChangeCause changeCause) {
    update(goal.setState(ReflectionGoalState.DELETED), changeCause);
    try {
      jobsService
          .get()
          .deleteJobCounts(
              DeleteJobCountsRequest.newBuilder()
                  .setReflections(
                      UsedReflections.newBuilder().addReflectionIds(goal.getId().getId()).build())
                  .build());
    } catch (Exception e) {
      logger.error("Unable to delete job counts for reflection : {}", goal.getId().getId(), e);
    }

    datasetEventHub.fireRemoveDatasetEvent(new DatasetRemovedEvent(goal.getDatasetId()));
  }

  @Override
  public Optional<Materialization> getMaterialization(MaterializationId materializationId) {
    return Optional.ofNullable(materializationStore.get(materializationId));
  }

  @Override
  public Materialization getLastMaterialization(ReflectionId reflectionId) {
    return materializationStore.getLastMaterialization(reflectionId);
  }

  @Override
  @WithSpan
  public List<ReflectionGoal> getRecommendedReflections(
      String datasetId, ReflectionSuggestionType type) {
    EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
    DatasetConfig datasetConfig = CatalogUtil.getDatasetConfig(catalog, datasetId);

    if (datasetConfig == null) {
      throw new NotFoundException("Dataset not found");
    }

    ReflectionAnalyzer analyzer =
        new ReflectionAnalyzer(jobsService.get(), catalogService.get(), allocator);
    ReflectionSuggester suggester = new ReflectionSuggester(datasetConfig);

    if (getOptionManager().getOption(SUGGEST_REFLECTION_BASED_ON_TYPE)) {
      TableStats tableStats = analyzer.analyzeForType(datasetId, type);
      return suggester.getReflectionGoals(tableStats, type);
    } else {
      TableStats tableStats = analyzer.analyze(datasetId);
      return suggester.getReflectionGoals(tableStats, ALL);
    }
  }

  @Override
  public ReflectionSettings getReflectionSettings() {
    return reflectionSettingsSupplier.get();
  }

  @Override
  public void requestRefresh(String datasetId) {
    logger.debug("Refresh requested on dataset {}", datasetId);
    RefreshRequest request = requestsStore.get(datasetId);
    if (request == null) {
      request = new RefreshRequest().setDatasetId(datasetId).setRequestedAt(0L);
    }
    request.setRequestedAt(Math.max(System.currentTimeMillis(), request.getRequestedAt()));
    requestsStore.save(datasetId, request);
    wakeupManager("refresh request for dataset " + datasetId);
  }

  @Override
  public Future<?> wakeupManager(String reason) {
    return wakeupManager(reason, false);
  }

  @Override
  public Provider<MaterializationCacheViewer> getCacheViewerProvider() {
    return () -> materializationCache;
  }

  @Override
  public boolean isReflectionIncremental(ReflectionId reflectionId) {
    Optional<ReflectionEntry> entry = getEntry(reflectionId);
    if (entry.isPresent()) {
      return entry.get().getRefreshMethod() == RefreshMethod.INCREMENTAL;
    }

    return false;
  }

  private OptionManager getOptionManager() {
    return sabotContext.get().getOptionManager();
  }

  public ReflectionGoalsStore getRelfectionGoalsStore() {
    return userStore;
  }

  public ReflectionEntriesStore getReflectionEntriesStore() {
    return internalStore;
  }

  private Future<?> wakeupManager(String reason, boolean periodic) {
    // in master-less mode, periodic wake-ups occur only on task leader. Ad-hoc wake-ups can occur
    // on any coordinator,
    // and can cause result in concurrent modification exceptions, when updating kvstore - so,
    // disable them.
    final boolean periodicWakeupOnly =
        getOptionManager().getOption(REFLECTION_PERIODIC_WAKEUP_ONLY) || isMasterLessEnabled;
    if (wakeupHandler != null && (!periodicWakeupOnly || periodic)) {
      return wakeupHandler.handle(reason);
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public Optional<ReflectionManager> getReflectionManager() {
    return Optional.of(reflectionManager);
  }

  @Override
  public Iterator<ReflectionLineageInfo> getReflectionLineage(ReflectionGoal reflectionGoal) {
    EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService.get());
    Map<ReflectionId, Integer> reflectionLineage =
        reflectionManager.computeReflectionLineage(reflectionGoal.getId());
    Stream<ReflectionLineageInfo> reflections =
        reflectionLineage.entrySet().stream()
            .map(
                v -> {
                  try {
                    ReflectionGoal goal = getGoal(v.getKey()).get();
                    String datasetName =
                        Optional.ofNullable(catalog.getTable(goal.getDatasetId()))
                            .map(DremioTranslatableTable::getPath)
                            .map(NamespaceKey::getPathComponents)
                            .map(SqlUtils::quotedCompound)
                            .orElse("Can't find dataset");
                    return new ReflectionLineageInfo(
                        v.getValue(), goal.getId().getId(), goal.getName(), datasetName);
                  } catch (Exception e) {
                    throw UserException.reflectionError(e)
                        .message(
                            String.format(
                                "Unable to get ReflectionInfo for %s", reflectionGoal.getId()))
                        .buildSilently();
                  }
                })
            .sorted(Comparator.comparingInt(ReflectionLineageInfo::getBatchNumber));
    return reflections.iterator();
  }
}
