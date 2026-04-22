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

import static com.dremio.exec.catalog.conf.ConnectionConf.USE_EXISTING_SECRET_VALUE;
import static com.dremio.test.DremioTest.CLASSPATH_SCAN_RESULT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.AutoCloseables;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.exceptions.UserException;
import com.dremio.concurrent.Runnables;
import com.dremio.concurrent.SafeRunnable;
import com.dremio.config.DremioConfig;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.datastore.adapter.LegacyKVStoreProviderAdapter;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.ConnectionConfUtils;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.catalog.conf.SecretRefImpl;
import com.dremio.exec.catalog.conf.SourceType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.server.options.OptionValidatorListingImpl;
import com.dremio.exec.server.options.SystemOptionManager;
import com.dremio.exec.server.options.SystemOptionManagerImpl;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.options.OptionValue;
import com.dremio.options.TypeValidators;
import com.dremio.options.TypeValidators.PositiveLongValidator;
import com.dremio.options.impl.DefaultOptionManager;
import com.dremio.options.impl.OptionManagerWrapper;
import com.dremio.service.coordinator.ClusterCoordinator;
import com.dremio.service.coordinator.ClusterCoordinator.Role;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.SupportsDecoratingSecrets;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.SourceInternalData;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.ModifiableLocalSchedulerService;
import com.dremio.service.scheduler.ModifiableSchedulerService;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.users.SystemUser;
import com.dremio.services.credentials.CredentialsService;
import com.dremio.services.credentials.NoopSecretsCreator;
import com.dremio.services.credentials.SecretsCreator;
import com.dremio.test.DremioTest;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Provider;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for PluginsManager. */
public class TestPluginsManager {
  private static final String ENCRYPTED_SECRET = "system:encryptedSecret";
  private DremioConfig dremioConfig;
  private SystemOptionManager som;
  private LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore;
  private DatasetListingService mockDatasetListingServiceInUnitTest;
  private MetadataRefreshInfoBroadcaster broadcaster;
  private SecretsCreator secretsCreator;
  private CredentialsService revealSecretService;

  private LegacyKVStoreProvider storeProvider;
  private PluginsManager plugins;
  private SabotContext sabotContext;
  private SchedulerService schedulerService;
  private ModifiableSchedulerService modifiableSchedulerService;
  private NamespaceService.Factory mockNamespaceServiceFactory;
  private NamespaceService mockNamespaceService;
  private Orphanage mockOrphanage;
  private final List<Cancellable> scheduledTasks = new ArrayList<>();

  @Before
  public void setup() throws Exception {
    storeProvider = LegacyKVStoreProviderAdapter.inMemory(DremioTest.CLASSPATH_SCAN_RESULT);
    storeProvider.start();
    mockNamespaceService = mock(NamespaceService.class);
    mockNamespaceServiceFactory = mock(NamespaceService.Factory.class);
    mockNamespaceServiceFactory = mock(NamespaceService.Factory.class);
    mockOrphanage = mock(Orphanage.class);
    when(mockNamespaceService.getAllDatasets(Mockito.any())).thenReturn(Collections.emptyList());

    final DatasetListingService mockDatasetListingService = mock(DatasetListingService.class);
    dremioConfig = DremioConfig.create();
    sabotContext = mock(SabotContext.class);

    // used in c'tor
    when(sabotContext.getClasspathScan()).thenReturn(CLASSPATH_SCAN_RESULT);
    when(mockNamespaceServiceFactory.get(anyString())).thenReturn(mockNamespaceService);
    when(sabotContext.getDatasetListing()).thenReturn(mockDatasetListingService);

    final LogicalPlanPersistence lpp = new LogicalPlanPersistence(CLASSPATH_SCAN_RESULT);
    when(sabotContext.getLpPersistence()).thenReturn(lpp);

    final OptionValidatorListing optionValidatorListing =
        new OptionValidatorListingImpl(CLASSPATH_SCAN_RESULT);
    som = new SystemOptionManagerImpl(optionValidatorListing, lpp, () -> storeProvider, true);
    final OptionManager optionManager =
        OptionManagerWrapper.Builder.newBuilder()
            .withOptionManager(new DefaultOptionManager(optionValidatorListing))
            .withOptionManager(som)
            .build();

    som.start();
    when(sabotContext.getOptionManager()).thenReturn(optionManager);

    // used in start
    when(sabotContext.getKVStoreProvider()).thenReturn(storeProvider);
    when(sabotContext.getConfig()).thenReturn(DremioTest.DEFAULT_SABOT_CONFIG);

    final Set<Role> roles = Sets.newHashSet(ClusterCoordinator.Role.MASTER);

    // used in newPlugin
    when(sabotContext.getRoles()).thenReturn(roles);
    when(sabotContext.isMaster()).thenReturn(true);

    secretsCreator = mock(SecretsCreator.class);
    when(secretsCreator.encrypt(any())).thenReturn(Optional.of(new URI(ENCRYPTED_SECRET)));
    when(secretsCreator.encrypt(eq(ENCRYPTED_SECRET)))
        .thenThrow(new RuntimeException("Double encryption should not occur."));
    when(secretsCreator.isEncrypted(anyString())).thenReturn(false);
    when(secretsCreator.isEncrypted(eq(ENCRYPTED_SECRET))).thenReturn(true);
    when(secretsCreator.cleanup(any())).thenReturn(true);
    when(sabotContext.getSecretsCreator()).thenReturn(() -> secretsCreator);
    // Set up a CredentialsService to always return the secret.
    // This is to verify the secret string stored in the SecretRef is a plain-text or not.
    revealSecretService = mock(CredentialsService.class);
    when(revealSecretService.lookup(anyString()))
        .thenAnswer((Answer<String>) invocation -> invocation.getArgument(0));
    when(sabotContext.getCredentialsServiceProvider()).thenReturn(() -> revealSecretService);

    sourceDataStore = storeProvider.getStore(CatalogSourceDataCreator.class);

    schedulerService = mock(SchedulerService.class);
    mockScheduleInvocation();
    broadcaster = mock(MetadataRefreshInfoBroadcaster.class);
    doNothing().when(broadcaster).communicateChange(any());

    PositiveLongValidator option = ExecConstants.MAX_CONCURRENT_METADATA_REFRESHES;
    modifiableSchedulerService =
        new ModifiableLocalSchedulerService(
            1, "modifiable-scheduler-", option, () -> optionManager) {
          @Override
          public Cancellable schedule(Schedule schedule, Runnable task) {
            Cancellable wakeupTask = super.schedule(schedule, task);
            scheduledTasks.add(wakeupTask);
            return wakeupTask;
          }
        };

    plugins =
        new PluginsManager(
            sabotContext,
            sabotContext,
            mockNamespaceService,
            mockOrphanage,
            mockDatasetListingService,
            optionManager,
            dremioConfig,
            sourceDataStore,
            schedulerService,
            new ConnectionReaderDecorator(
                ConnectionReader.of(sabotContext.getClasspathScan(), ConnectionReaderImpl.class),
                () -> revealSecretService),
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster,
            null,
            modifiableSchedulerService,
            () -> storeProvider,
            mockNamespaceServiceFactory);
    plugins.start();
  }

  @After
  public void shutdown() throws Exception {
    AutoCloseables.close(plugins, modifiableSchedulerService, storeProvider);
  }

  private void mockScheduleInvocation() {
    doAnswer(
            new Answer<Cancellable>() {
              @Override
              public Cancellable answer(InvocationOnMock invocation) {
                final Object[] arguments = invocation.getArguments();
                if (arguments[1] instanceof SafeRunnable) {
                  return mock(Cancellable.class);
                }
                // allow thread that does first piece of work: scheduleMetadataRefresh
                // (that was not part of thread before) go through
                final Runnable r = (Runnable) arguments[1];
                Runnables.executeInSeparateThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        r.run();
                      }
                    });
                return mock(Cancellable.class);
              } // using SafeRunnable, as Runnable is also used to run initial setup that used to
              // run w/o any scheduling
            })
        .when(schedulerService)
        .schedule(any(Schedule.class), any(Runnable.class));
  }

  private static final String INSPECTOR_BASE = "inspector_base";
  private static final String INSPECTOR = "inspector";
  public static final String INSPECTOR_WITH_ALLOWED_DATABASES = "inspector_with_allowed_databases";
  private static final String INSPECTOR_WITH_MIGRATION = "inspector_with_migration";

  private static final EntityPath DELETED_PATH =
      new EntityPath(ImmutableList.of(INSPECTOR, "deleted"));

  private static final DatasetConfig incompleteDatasetConfig = new DatasetConfig();

  private static final EntityPath ENTITY_PATH = new EntityPath(ImmutableList.of(INSPECTOR, "one"));
  private static final DatasetHandle DATASET_HANDLE = () -> ENTITY_PATH;

  @SourceType(value = INSPECTOR_BASE, configurable = false)
  public static class InspectorBase extends ConnectionConf<Inspector, StoragePlugin> {
    private final boolean hasAccessPermission;
    @Secret public SecretRef secret1 = null;
    @Secret public SecretRef secret2 = null;
    @Secret public SecretRef secret3 = null;

    InspectorBase() {
      this(true);
    }

    InspectorBase(boolean hasAccessPermission) {
      this.hasAccessPermission = hasAccessPermission;
    }

    public InspectorBase setSecret1(String secret1) {
      this.secret1 = new SecretRefImpl(secret1);
      return this;
    }

    public InspectorBase setSecret2(String secret2) {
      this.secret2 = new SecretRefImpl(secret2);
      return this;
    }

    public InspectorBase setSecret3(String secret3) {
      this.secret3 = new SecretRefImpl(secret3);
      return this;
    }

    @Override
    public StoragePlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      final ExtendedStoragePlugin mockStoragePlugin = mock(ExtendedStoragePlugin.class);
      try {
        when(mockStoragePlugin.listDatasetHandles()).thenReturn(Collections::emptyIterator);

        when(mockStoragePlugin.getDatasetHandle(eq(DELETED_PATH))).thenReturn(Optional.empty());

        when(mockStoragePlugin.getDatasetHandle(eq(ENTITY_PATH)))
            .thenReturn(Optional.of(DATASET_HANDLE));

        when(mockStoragePlugin.getState()).thenReturn(SourceState.GOOD);

        when(mockStoragePlugin.hasAccessPermission(anyString(), any(), any()))
            .thenReturn(hasAccessPermission);
      } catch (Exception ignored) {
        throw new IllegalStateException("will not throw");
      }

      return mockStoragePlugin;
    }
  }

  @SourceType(value = INSPECTOR, configurable = false)
  public static class Inspector extends InspectorBase {
    Inspector() {
      this(true);
    }

    Inspector(boolean hasAccessPermission) {
      super(hasAccessPermission);
    }

    @Override
    @SuppressWarnings(
        "EqualsHashCode") // .hashCode() is final in ConnectionConf and can't be overridden
    public boolean equals(Object other) {
      // this forces the replace call to always do so
      return false;
    }
  }

  @SourceType(value = INSPECTOR_WITH_ALLOWED_DATABASES, configurable = false)
  public static class InspectorWithAllowedDatabases extends InspectorBase {
    public List<String> allowedDatabases = null;

    InspectorWithAllowedDatabases() {
      this(true);
    }

    InspectorWithAllowedDatabases(boolean hasAccessPermission) {
      super(hasAccessPermission);
    }

    public InspectorWithAllowedDatabases setAllowedDatabases(List<String> allowedDatabases) {
      this.allowedDatabases = allowedDatabases;
      return this;
    }

    @Override
    public List<SourceNameRefreshAction> getNameRefreshActionsForNewConf(
        String source, ConnectionConf<?, ?> other) {
      Preconditions.checkNotNull(other, "other ConnectionConf cannot be null");
      Preconditions.checkArgument(
          this.getClass().isInstance(other), "other ConnectionConf must be the same class");

      InspectorWithAllowedDatabases otherConfig = (InspectorWithAllowedDatabases) other;
      return ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
          source, allowedDatabases, otherConfig.allowedDatabases);
    }
  }

  @SourceType(value = INSPECTOR_WITH_MIGRATION, configurable = false)
  public static class InspectorWithLegacyMigration extends Inspector {
    public String oldField;
    public String newField;

    public InspectorWithLegacyMigration setOldField(String oldField) {
      this.oldField = oldField;
      return this;
    }

    @Override
    public boolean migrateLegacyFormat() {
      if (oldField != null) {
        // Move oldField value to newField.  Null out oldField.
        newField = oldField;
        oldField = null;
        return true;
      }

      return false;
    }
  }

  private PluginsManager newPluginsManager(
      List<SourceConfig> aListOfSourceConfigsToTestMigration, LegacyKVStoreProvider storeProvider)
      throws Exception {
    // Set up option
    final OptionManager optionManagerInUnitTest =
        OptionManagerWrapper.Builder.newBuilder()
            .withOptionManager(
                new DefaultOptionManager(new OptionValidatorListingImpl(CLASSPATH_SCAN_RESULT)))
            .withOptionManager(som)
            .build();

    // Set up an existing list of source configs to test against migration task.
    mockDatasetListingServiceInUnitTest = mock(DatasetListingService.class);
    when(mockDatasetListingServiceInUnitTest.getSources(eq(SystemUser.SYSTEM_USERNAME)))
        .thenReturn(aListOfSourceConfigsToTestMigration);

    storeProvider.start();

    return new PluginsManager(
        sabotContext,
        sabotContext,
        mockNamespaceService,
        mockOrphanage,
        mockDatasetListingServiceInUnitTest,
        optionManagerInUnitTest,
        dremioConfig,
        sourceDataStore,
        schedulerService,
        new ConnectionReaderDecorator(
            ConnectionReader.of(sabotContext.getClasspathScan(), ConnectionReaderImpl.class),
            () -> revealSecretService),
        CatalogServiceMonitor.DEFAULT,
        () -> broadcaster,
        null,
        modifiableSchedulerService,
        () -> storeProvider,
        mockNamespaceServiceFactory);
  }

  private void verifyNoPlainTextPasswordPresent(
      Collection<String> blackList, Collection<String> whiteList, PluginsManager plugins) {
    Collection<String> whiteListCopy = new ArrayList<>(whiteList);
    ConcurrentHashMap<String, ManagedStoragePlugin> map = plugins.getPlugins();
    map.forEach(
        (sourceName, plugin) -> {
          ConnectionConf connectionConf = plugin.getConfig().getConnectionConf(plugins.reader);
          for (Field field : FieldUtils.getAllFields(connectionConf.getClass())) {
            if (SecretRef.class.isAssignableFrom(field.getType())) {
              final SecretRef secretRef;
              try {
                secretRef = (SecretRef) field.get(connectionConf);
              } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
              }
              if (secretRef == null) {
                continue;
              }
              if (secretRef instanceof SupportsDecoratingSecrets) {
                ((SupportsDecoratingSecrets) secretRef).decorateSecrets(revealSecretService);
              }
              assert !blackList.contains(secretRef.get())
                  : String.format(
                      "'%s' contains one of the plain-text passwords %s",
                      secretRef.get(), blackList);
              whiteListCopy.remove(secretRef.get());
            }
          }
        });
    assert whiteListCopy.isEmpty()
        : String.format("Required string(s) %s did not appear in SecretRef", whiteListCopy);
  }

  @Test
  public void permissionCacheShouldClearOnReplace() throws Exception {
    final SourceConfig inspectorConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString());

    // create one; lock required
    final ManagedStoragePlugin plugin;
    plugins.create(
        inspectorConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    plugin = plugins.get(inspectorConfig.getName());
    plugin.startAsync().get();

    final SchemaConfig schemaConfig = mock(SchemaConfig.class);
    when(schemaConfig.getUserName()).thenReturn("user");
    final MetadataRequestOptions requestOptions =
        MetadataRequestOptions.newBuilder()
            .setSchemaConfig(schemaConfig)
            .setNewerThan(1000)
            .build();

    // force a cache of the permissions
    plugin.checkAccess(new NamespaceKey("test"), incompleteDatasetConfig, "user", requestOptions);

    // create a replacement that will always fail permission checks
    final SourceConfig newConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(false).toBytesString());

    plugin.replacePluginWithLockDeprecated(newConfig, 1000, false);

    // will throw if the cache has been cleared
    boolean threw = false;
    try {
      plugin.checkAccess(new NamespaceKey("test"), incompleteDatasetConfig, "user", requestOptions);
    } catch (UserException e) {
      threw = true;
    }

    assertTrue(threw);
  }

  @Test
  public void testSynchronizePluginToSameVersionDifferentTag() throws Exception {
    final SourceConfig inspectorConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    // create one; lock required
    plugins.create(
        inspectorConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin plugin = plugins.get(inspectorConfig.getName());
    plugin.startAsync().get();

    // replace it with same config with different tag
    final SourceConfig newConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("21c31b70-f331-4833-9994-1531930f2dfc");
    final ManagedStoragePlugin newPlugin =
        plugins.getSynchronized(newConfig, (String _pred) -> false);
    assertEquals(newConfig.getTag(), newPlugin.sourceConfig.getTag());
  }

  @Test
  public void testSynchronizePluginToSameVersionDifferentValue() throws Exception {
    final SourceConfig inspectorConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    // create one; lock required
    plugins.create(
        inspectorConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin plugin = plugins.get(inspectorConfig.getName());
    plugin.startAsync().get();

    // replace it with same config with different value
    final SourceConfig newConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("21c31b70-f331-4833-9994-1531930f2dfc")
            .setAccelerationGracePeriod(999L);
    assertThrows(
        IllegalStateException.class,
        () -> plugins.getSynchronized(newConfig, (String _pred) -> false));
  }

  @Test
  public void testSynchronizePluginToNewVersion() throws Exception {
    final SourceConfig inspectorConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    // create one; lock required
    plugins.create(
        inspectorConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin plugin = plugins.get(inspectorConfig.getName());
    plugin.startAsync().get();

    // replace it with same config with different value
    final SourceConfig newConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(1L)
            .setTag("21c31b70-f331-4833-9994-1531930f2dfc")
            .setAccelerationGracePeriod(999L);
    final ManagedStoragePlugin newPlugin =
        plugins.getSynchronized(newConfig, (String _pred) -> false);
    assertEquals(
        newConfig.getAccelerationGracePeriod(),
        newPlugin.sourceConfig.getAccelerationGracePeriod());
  }

  @Test
  public void testPluginsManagerStartupWithSpecialSecrets() throws Exception {
    // Set up source configs
    // Dremio sentinel value for existing secret
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_EXISTING_SECRET_SENTINEL")
            .setConfig(new Inspector(false).setSecret1(USE_EXISTING_SECRET_VALUE).toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    // Will be treated as a plain-text password with system: prefix.
    final SourceConfig config2 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_ENCRYPTED_SECRET")
            .setConfig(new Inspector(false).setSecret1("system:@123").toBytesString());
    aListOfSourceConfigsToTestMigration.add(config2);

    // No secret
    final SourceConfig config3 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_NO_SECRET")
            .setConfig(new Inspector(false).toBytesString());
    aListOfSourceConfigsToTestMigration.add(config3);

    // Will be treated as a plain-text password with https: prefix.
    final SourceConfig config4 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET_VAULT")
            .setConfig(new Inspector(false).setSecret1("https://vault/secret").toBytesString());
    aListOfSourceConfigsToTestMigration.add(config4);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(
                CLASSPATH_SCAN_RESULT); // This is to create a clean Configuration KVStore
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Record numbers of invocation and compare with the numbers after running #start.
      verify(sabotContext, times(1)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(0)).getSources(any());
      verify(secretsCreator, times(0)).encrypt(any());
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      testPluginsManager.start();

      // Verify how many times Migration task needed to encrypt a secret
      // and how many times a KVStore-update-request is called
      verify(sabotContext, times(2)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(3)).getSources(any());
      verify(secretsCreator, times(2)).encrypt(any());
      verify(mockNamespaceService, times(2)).addOrUpdateSource(any(), any());

      verifyNoPlainTextPasswordPresent(
          List.of("system:@123", "https://vault/secret"),
          List.of(ENCRYPTED_SECRET, USE_EXISTING_SECRET_VALUE),
          testPluginsManager);
    }
  }

  @Test
  public void testPluginsManagerStartupWithMultiPlainTextSecretsInTheSameSource() throws Exception {
    // Set up source configs
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET")
            .setConfig(
                new Inspector(false)
                    .setSecret1("password")
                    .setSecret2("https://vault/secret")
                    .toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Record numbers of invocation and compare with the numbers after running #start.
      verify(sabotContext, times(1)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(0)).getSources(any());
      verify(secretsCreator, times(0)).encrypt(any());
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      testPluginsManager.start();

      // Verify how many times Migration task needed to encrypt a secret
      // and how many times a KVStore-update-request is called
      verify(sabotContext, times(2)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(3)).getSources(any());
      verify(secretsCreator, times(2)).encrypt(any());
      verify(mockNamespaceService, times(1)).addOrUpdateSource(any(), any());

      verifyNoPlainTextPasswordPresent(
          List.of("password", "https://vault/secret"),
          List.of(ENCRYPTED_SECRET),
          testPluginsManager);
    }
  }

  @Test
  public void testPluginsManagerStartupWithMultiPlainTextSecrets() throws Exception {
    // Set up source configs
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET")
            .setConfig(
                new Inspector(false).setSecret1("password").setSecret3("password").toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    final SourceConfig config2 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET_2")
            .setConfig(
                new Inspector(false)
                    .setSecret1("https://vault/secret")
                    .setSecret3("password")
                    .toBytesString());
    aListOfSourceConfigsToTestMigration.add(config2);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Record numbers of invocation and compare with the numbers after running #start.
      verify(sabotContext, times(1)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(0)).getSources(any());
      verify(secretsCreator, times(0)).encrypt(any());
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      testPluginsManager.start();

      // Verify how many times Migration task needed to encrypt a secret
      // and how many times a KVStore-update-request is called
      verify(sabotContext, times(2)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(3)).getSources(any());
      verify(secretsCreator, times(4)).encrypt(any());
      verify(mockNamespaceService, times(2)).addOrUpdateSource(any(), any());

      verifyNoPlainTextPasswordPresent(
          List.of("password", "https://vault/secret"),
          List.of(ENCRYPTED_SECRET),
          testPluginsManager);
    }
  }

  @Test
  public void testPluginsManagerStartupCrashes() throws Exception {
    // Set up source configs
    // Assume config1 was encrypted before the crash, config2 has not been encrypted.
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET")
            .setConfig(new Inspector(false).setSecret1(ENCRYPTED_SECRET).toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    final SourceConfig config2 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET_3")
            .setConfig(new Inspector(false).setSecret1("system:@123").toBytesString());
    aListOfSourceConfigsToTestMigration.add(config2);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Record numbers of invocation and compare with the numbers after running #start.
      verify(sabotContext, times(1)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(0)).getSources(any());
      verify(secretsCreator, times(0)).encrypt(any());
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      testPluginsManager.start();

      // Verify how many times Migration task needed to encrypt a secret
      // and how many times a KVStore-update-request is called
      verify(sabotContext, times(2)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(3)).getSources(any());
      verify(secretsCreator, times(1))
          .encrypt(any()); // This verifies double encryption did not happen to config1
      verify(mockNamespaceService, times(1)).addOrUpdateSource(any(), any());

      verifyNoPlainTextPasswordPresent(
          Arrays.asList(
              "system:@123" /*This verifies config2 was encrypted during the second migration*/),
          Arrays.asList(ENCRYPTED_SECRET),
          testPluginsManager);
    }
  }

  @Test
  public void testPluginsManagerStartupWithSecretMigrationAlreadyRan() throws Exception {
    // Set up source configs
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_MIGRATION_SECRET")
            .setConfig(
                new Inspector(false)
                    .setSecret1("password")
                    .setSecret2("https://vault/secret")
                    .toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {
      // Migration runs for the first time:
      testPluginsManager.start();

      // Record numbers of invocation and compare with the numbers after running #start again.
      verify(sabotContext, times(2)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(3)).getSources(any());
      verify(secretsCreator, times(2)).encrypt(any());
      verify(mockNamespaceService, times(1)).addOrUpdateSource(any(), any());

      // Run start again. Configuration store should remember that migration task already ran
      // and skip migration entirely.
      testPluginsManager.start();

      verify(sabotContext, times(3)).getSecretsCreator();
      // If migration is not run, DatasetListingService#getSources will still be called twice more
      // for starting all plugins' connection and applying legacy migrations.
      verify(mockDatasetListingServiceInUnitTest, times(5)).getSources(any());
      // No increment
      verify(secretsCreator, times(2)).encrypt(any());
      // No increment
      verify(mockNamespaceService, times(1)).addOrUpdateSource(any(), any());
    }
  }

  @Test
  public void testPluginsManagerStartupWithNoopSecretCreator() throws Exception {
    // Set up a NOOP secret creator
    SecretsCreator noopSecretsCreator = new NoopSecretsCreator();
    when(sabotContext.getSecretsCreator()).thenReturn(() -> noopSecretsCreator);

    // Set up a source config to confirm there is no impact on Dremio Cloud.
    // Migration shouldn't encrypt the password on Dremio Cloud.
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST_DC_SOURCE")
            .setConfig(new Inspector(false).setSecret1("password").toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Record numbers of invocation and compare with the numbers after running #start.
      verify(sabotContext, times(1)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(0)).getSources(any());
      verify(secretsCreator, times(0)).encrypt(any());
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      testPluginsManager.start();

      // Verify how many times a KVStore-update-request is called
      // Record numbers of invocation and compare with the numbers after running #start.
      verify(sabotContext, times(2)).getSecretsCreator();
      verify(mockDatasetListingServiceInUnitTest, times(2)).getSources(any());
      verify(secretsCreator, times(0)).encrypt(any());
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());
    }
  }

  @Test
  public void testPluginsManagerStartup_runsLegacyMigration_happyPath() throws Exception {
    // Set up a source config that has a deprecated/old field.
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig config1 =
        new SourceConfig()
            .setType(INSPECTOR_WITH_MIGRATION)
            .setName("TEST_MIGRATE_LEGACY_SOURCE")
            .setConfig(new InspectorWithLegacyMigration().setOldField("oldField").toBytesString());
    aListOfSourceConfigsToTestMigration.add(config1);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Verify KVStore-update-request is not yet called.
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      testPluginsManager.start();

      // Verify KVStore-update-request is called once.
      ArgumentCaptor<SourceConfig> captor = ArgumentCaptor.forClass(SourceConfig.class);
      verify(mockNamespaceService, times(1)).addOrUpdateSource(any(), captor.capture());

      // Verify associated InspectorWithLegacyMigration oldField was migrated to newField.
      InspectorWithLegacyMigration capturedInspectorSource =
          captor.getValue().getConnectionConf(testPluginsManager.getReader());
      Assertions.assertNull(capturedInspectorSource.oldField);
      Assertions.assertEquals("oldField", capturedInspectorSource.newField);

      // Verify KVStore-update-request is not called again on second start() call.
      testPluginsManager.start();
      verify(mockNamespaceService, times(1)).addOrUpdateSource(any(), any());
    }
  }

  @Test
  public void testPluginsManagerStartup_runsLegacyMigration_failsToSave() throws Exception {
    // Set up a source config that has a deprecated/old field.
    List<SourceConfig> aListOfSourceConfigsToTestMigration = new ArrayList<>();
    final SourceConfig configToMigrateFormat =
        new SourceConfig()
            .setType(INSPECTOR_WITH_MIGRATION)
            .setName("TEST_MIGRATE_LEGACY_SOURCE")
            .setConfig(new InspectorWithLegacyMigration().setOldField("oldField").toBytesString());
    aListOfSourceConfigsToTestMigration.add(configToMigrateFormat);

    // Test PluginsManager#start
    try (LegacyKVStoreProvider kvStoreProvider =
            LegacyKVStoreProviderAdapter.inMemory(CLASSPATH_SCAN_RESULT);
        PluginsManager testPluginsManager =
            newPluginsManager(aListOfSourceConfigsToTestMigration, kvStoreProvider)) {

      // Verify KVStore-update-request is not yet called.
      verify(mockNamespaceService, times(0)).addOrUpdateSource(any(), any());

      // Simulate failure by having addOrUpdateSource always throw.
      doThrow(ConcurrentModificationException.class)
          .when(mockNamespaceService)
          .addOrUpdateSource(any(), any());
      testPluginsManager.start();

      // Verify the retry logic in place i.e. attempted to update source twice.
      verify(mockNamespaceService, times(2)).addOrUpdateSource(any(), eq(configToMigrateFormat));
    }
  }

  @Test
  public void testCreateSource() throws Exception {
    final SourceConfig newConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST")
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(false).toBytesString());

    boolean userExceptionOccured = false;
    try {
      doThrow(UserException.validationError().message("Already Exists %s", "").buildSilently())
          .when(mockNamespaceService)
          .addOrUpdateSource(newConfig.getKey(), newConfig);
      scheduledTasks.clear();
      plugins.create(newConfig, "testuser", SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    } catch (UserException e) {
      userExceptionOccured = true;
    }
    assertEquals(scheduledTasks.size(), 1);
    assertTrue(scheduledTasks.get(0).isCancelled());
    assertTrue(userExceptionOccured);
  }

  @Test
  public void testCreateSourceFailedWithSecrets() throws Exception {
    // Configure the secrets services to return a URI for each incoming secret
    // and to recognize a prefix when determining whether something is encrypted.
    when(secretsCreator.encrypt(any()))
        .thenAnswer(input -> Optional.of(new URI("secret:///" + input.getArgument(0))));
    when(secretsCreator.isEncrypted(startsWith("secret:///"))).thenReturn(true);
    when(revealSecretService.isSupported(any()))
        .thenAnswer(input -> input.getArgument(0).toString().startsWith("secret:///"));

    final SourceConfig newConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("TEST")
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(false).setSecret1("some-secret").toBytesString());

    doThrow(
            UserException.validationError()
                .message("Failed to create for some reason")
                .buildSilently())
        .when(mockNamespaceService)
        .addOrUpdateSource(newConfig.getKey(), newConfig);
    scheduledTasks.clear();

    assertThrows(
        UserException.class,
        () ->
            plugins.create(newConfig, "testuser", SourceRefreshOption.WAIT_FOR_DATASETS_CREATION));
    assertEquals(scheduledTasks.size(), 1);
    assertTrue(scheduledTasks.get(0).isCancelled());

    // Check that the secret was encrypted (happens before failure) and deleted
    verify(secretsCreator, times(1)).encrypt(eq("some-secret"));
    verify(secretsCreator, times(1)).cleanup(eq(URI.create("secret:///some-secret")));
  }

  @Test
  public void disableMetadataValidityCheck() throws Exception {

    final SourceConfig sourceConfigWithValidityCheck =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("source")
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString());

    final DatasetConfig incompleteDatasetConfig = new DatasetConfig();
    NamespaceKeyWithConfig incompleteDataset =
        new ImmutableNamespaceKeyWithConfig.Builder()
            .setKey(new NamespaceKey(ImmutableList.of("a", "b")))
            .setDatasetConfig(incompleteDatasetConfig)
            .build();

    // create one; lock required
    plugins.create(
        sourceConfigWithValidityCheck,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin pluginWithValidityCheck =
        plugins.get(sourceConfigWithValidityCheck.getName());
    pluginWithValidityCheck.startAsync().get();

    final SchemaConfig schemaConfig = mock(SchemaConfig.class);
    when(schemaConfig.getUserName()).thenReturn("user");

    // Ensure for an incomplete datasetConfig, validity is not checked even if option to disable
    // validity is set
    final MetadataRequestOptions metadataRequestOptions =
        ImmutableMetadataRequestOptions.newBuilder()
            .setNewerThan(0L)
            .setSchemaConfig(SchemaConfig.newBuilder(CatalogUser.from("dremio")).build())
            .setCheckValidity(false)
            .build();
    assertFalse(pluginWithValidityCheck.checkValidity(incompleteDataset, metadataRequestOptions));

    final SourceConfig sourceConfigDisableValidity =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName("source2")
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setDisableMetadataValidityCheck(true)
            .setConfig(new Inspector(true).toBytesString());

    plugins.create(
        sourceConfigDisableValidity,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin pluginWithDisableValidity =
        plugins.get(sourceConfigDisableValidity.getName());
    pluginWithDisableValidity.startAsync().get();

    // Ensure for an incomplete datasetConfig, validity is not checked even if SourceConfig to
    // disable validity is set
    assertFalse(
        pluginWithDisableValidity.checkValidity(
            incompleteDataset,
            ImmutableMetadataRequestOptions.copyOf(metadataRequestOptions)
                .withCheckValidity(true)));

    final ReadDefinition readDefinition = new ReadDefinition();
    readDefinition.setSplitVersion(0L);

    DatasetConfig completeDatasetConfig = new DatasetConfig();
    completeDatasetConfig.setType(DatasetType.PHYSICAL_DATASET);
    completeDatasetConfig.setId(new EntityId("test"));
    completeDatasetConfig.setFullPathList(ImmutableList.of("test", "file", "foobar"));
    completeDatasetConfig.setRecordSchema(
        (new BatchSchema(Collections.emptyList())).toByteString());
    completeDatasetConfig.setReadDefinition(readDefinition);
    completeDatasetConfig.setTotalNumSplits(0);
    NamespaceKeyWithConfig completeDataset =
        new ImmutableNamespaceKeyWithConfig.Builder()
            .setKey(new NamespaceKey(ImmutableList.of("test", "file", "foobar")))
            .setDatasetConfig(completeDatasetConfig)
            .build();

    // Ensure for a complete config, isStillValid is called and expiry is ignored if request option
    // is set.
    assertTrue(pluginWithValidityCheck.checkValidity(completeDataset, metadataRequestOptions));

    // Ensure for a complete config, isStillValid is called and expiry is ignored if request option
    // is not set but source config option is set to disable.
    assertTrue(pluginWithDisableValidity.checkValidity(completeDataset, metadataRequestOptions));

    // Ensure for a complete config, isStillValid is called and expiry is ignored if request option
    // is set to true but source config option is set to disable.
    assertTrue(
        pluginWithDisableValidity.checkValidity(
            completeDataset,
            ImmutableMetadataRequestOptions.copyOf(metadataRequestOptions)
                .withCheckValidity(true)));

    // Ensure for a complete config, isStillValid is called and expiry is checked and fails if
    // request option is set to false and source config option is not set to disable .
    assertFalse(
        pluginWithValidityCheck.checkValidity(
            completeDataset,
            ImmutableMetadataRequestOptions.copyOf(metadataRequestOptions)
                .withCheckValidity(true)));
  }

  @Test
  public void testCreateSourceAsync() throws Exception {
    withSystemOption(CatalogOptions.SOURCE_ASYNC_MODIFICATION_ENABLED, true);

    SourceConfig inspectorConfig =
        new SourceConfig()
            .setType(INSPECTOR)
            .setName(INSPECTOR)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(new Inspector(true).toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    plugins.create(
        inspectorConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin plugin = plugins.get(inspectorConfig.getName());
    SourceState state = plugin.startAsync().get();
    assertEquals(state.getStatus(), SourceState.SourceStatus.good);
  }

  @Test
  public void testIsSourceConfigMetadataImpacting() throws Exception {
    {
      withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, true);

      InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
      existingConfig.setAllowedDatabases(List.of("a", "b", "c"));
      InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
      newConfig.setAllowedDatabases(List.of("x", "y", "z"));
      assertEquals(
          false, testIsSourceConfigMetadataImpactingInternal("source1", existingConfig, newConfig));
    }

    {
      withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, false);

      InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
      existingConfig.setAllowedDatabases(List.of("a", "b", "c"));
      InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
      newConfig.setAllowedDatabases(List.of("x", "y", "z"));
      assertEquals(
          true, testIsSourceConfigMetadataImpactingInternal("source2", existingConfig, newConfig));
    }

    {
      withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, true);

      InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
      existingConfig.setAllowedDatabases(List.of("a", "b", "c"));
      existingConfig.setSecret1("secret1");
      InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
      newConfig.setAllowedDatabases(List.of("a", "b", "c"));
      newConfig.setSecret1("secret1_changed");
      assertEquals(
          true, testIsSourceConfigMetadataImpactingInternal("source3", existingConfig, newConfig));
    }

    {
      withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, false);

      InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
      existingConfig.setAllowedDatabases(List.of("a", "b", "c"));
      existingConfig.setSecret1("secret1");
      InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
      newConfig.setAllowedDatabases(List.of("a", "b", "c"));
      newConfig.setSecret1("secret1_changed");
      assertEquals(
          true, testIsSourceConfigMetadataImpactingInternal("source4", existingConfig, newConfig));
    }
  }

  private boolean testIsSourceConfigMetadataImpactingInternal(
      String name,
      InspectorWithAllowedDatabases existingConfig,
      InspectorWithAllowedDatabases newConfig)
      throws Exception {
    SourceConfig existingSourceConfig =
        new SourceConfig()
            .setType(INSPECTOR_WITH_ALLOWED_DATABASES)
            .setName(name)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(existingConfig.toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    plugins.create(
        existingSourceConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin plugin = plugins.get(existingSourceConfig.getName());

    SourceConfig newSourceConfig =
        new SourceConfig()
            .setType(INSPECTOR_WITH_ALLOWED_DATABASES)
            .setName(name)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(newConfig.toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    return plugin.isSourceConfigMetadataImpacting(newSourceConfig);
  }

  @Test
  public void testReplacePluginNoMetadataImpactingChange() throws Exception {
    withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, true);
    String sourceName = "source1";

    InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
    existingConfig.setAllowedDatabases(List.of("a", "b", "c"));
    InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
    newConfig.setAllowedDatabases(List.of("a", "b", "c"));

    List<SourceNameRefreshAction> actions =
        testReplacePluginWithLockInternal(sourceName, existingConfig, newConfig, false);

    assertEquals(0, actions.size());
  }

  @Test
  public void testReplacePluginMetadataImpactingChange() throws Exception {
    withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, true);
    String sourceName = "source1";

    InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
    existingConfig.setAllowedDatabases(List.of("a", "b", "c", "d"));
    existingConfig.setSecret1("secret1");
    InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
    newConfig.setAllowedDatabases(List.of("a", "b", "e", "f"));
    newConfig.setSecret1("secret1_changed");

    List<SourceNameRefreshAction> actions =
        testReplacePluginWithLockInternal(sourceName, existingConfig, newConfig, false);

    List<SourceNameRefreshAction> expected =
        List.of(
            SourceNameRefreshAction.newDeleteAllAction(),
            SourceNameRefreshAction.newRefreshAllAction());
    assertEquals(expected, actions);
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_Update_AddAndDelete() throws Exception {
    withSystemOption(CatalogOptions.SOURCE_SEAMLESS_UPDATE_ALLOWED_DATABASES, true);
    String sourceName = "source1";

    InspectorWithAllowedDatabases existingConfig = new InspectorWithAllowedDatabases(true);
    existingConfig.setAllowedDatabases(List.of("a", "b", "c", "d"));
    InspectorWithAllowedDatabases newConfig = new InspectorWithAllowedDatabases(true);
    newConfig.setAllowedDatabases(List.of("a", "b", "e", "f"));

    List<SourceNameRefreshAction> actions =
        testReplacePluginWithLockInternal(sourceName, existingConfig, newConfig, false);

    List<List<String>> deleted = List.of(List.of(sourceName, "c"), List.of(sourceName, "d"));
    List<List<String>> added = List.of(List.of(sourceName, "e"), List.of(sourceName, "f"));
    List<SourceNameRefreshAction> expected =
        List.of(
            SourceNameRefreshAction.newDeleteFoldersAction(deleted),
            SourceNameRefreshAction.newRefreshFoldersAction(added));
    assertEquals(expected, actions);
  }

  private List<SourceNameRefreshAction> testReplacePluginWithLockInternal(
      String name,
      InspectorWithAllowedDatabases existingConfig,
      InspectorWithAllowedDatabases newConfig,
      boolean skipEqualityCheck)
      throws Exception {
    SourceConfig existingSourceConfig =
        new SourceConfig()
            .setType(INSPECTOR_WITH_ALLOWED_DATABASES)
            .setName(name)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(existingConfig.toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    plugins.create(
        existingSourceConfig,
        SystemUser.SYSTEM_USERNAME,
        SourceRefreshOption.WAIT_FOR_DATASETS_CREATION);
    final ManagedStoragePlugin plugin = plugins.get(existingSourceConfig.getName());

    SourceConfig newSourceConfig =
        new SourceConfig()
            .setType(INSPECTOR_WITH_ALLOWED_DATABASES)
            .setName(name)
            .setMetadataPolicy(CatalogService.DEFAULT_METADATA_POLICY)
            .setConfig(newConfig.toBytesString())
            .setCtime(0L)
            .setConfigOrdinal(0L)
            .setTag("fcf85527-1f76-4276-8b93-6d76f82d3f4b");

    return plugin.replacePluginWithLock(newSourceConfig, 100, skipEqualityCheck);
  }

  private AutoCloseable withSystemOption(TypeValidators.BooleanValidator option, boolean value) {
    setSystemOption(sabotContext, option, value);
    return () -> resetSystemOption(sabotContext, option);
  }

  static AutoCloseable withSystemOption(
      SabotContext sabotContext, TypeValidators.BooleanValidator option, boolean value) {
    setSystemOption(sabotContext, option, value);
    return () -> resetSystemOption(sabotContext, option);
  }

  static void setSystemOption(
      SabotContext sabotContext, TypeValidators.BooleanValidator option, boolean value) {
    sabotContext
        .getOptionManager()
        .setOption(
            OptionValue.createBoolean(
                OptionValue.OptionType.SYSTEM, option.getOptionName(), value));
  }

  static void resetSystemOption(SabotContext sabotContext, TypeValidators.BooleanValidator option) {
    sabotContext
        .getOptionManager()
        .setOption(
            OptionValue.createBoolean(
                OptionValue.OptionType.SYSTEM,
                option.getOptionName(),
                option.getDefault().getBoolVal()));
  }
}
