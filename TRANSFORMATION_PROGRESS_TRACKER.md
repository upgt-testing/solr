# Solr Test Transformation Progress Tracker

This file tracks the progress of transforming MiniSolrCloudCluster tests to ProcessBasedMiniSolrCloudCluster for upgrade testing.

## References
- [Step 1: ProcessBasedMiniSolrCloudCluster Implementation (Addendum)](solr/prompt/step1-addendum-real-solr-processes.md)
- [Step 2: Upgrade Test Base Generation](solr/prompt/step2-solr-upgrade-base-test-generation.md)
- [Step 3: Test Transformation Guide](solr/prompt/step3-solr-test-transformation-guide.md)

## Status Legend
- ⏳ **Not Started** - Test has not been transformed yet
- ✅ **Finished** - Test successfully transformed to ProcessBased upgrade test
- ⏸️ **Blocked** - Transformation blocked due to technical issues
- ❌ **Skipped** - Test intentionally skipped (not suitable for upgrade testing)
- 🔄 **Rechecked** - Previously skipped test that has been rechecked for transformation feasibility

## Summary Statistics

| Metric | Count |
|--------|-------|
| **Total Target Tests** | 65 |
| ✅ Finished | 0 |
| ⏸️ Blocked | 0 |
| ❌ Skipped | 20 |
| ⏳ Remaining | 65 |
| 🔄 Rechecked | 0 |

**Progress: 0 / 65 (0%)**

---

## Workflow Rules

1. Always pick the **first test** in this tracker that is not yet marked as ✅, ⏸️, or ❌.
2. For that test:
   - (a) Transform it to an upgrade test following step3-solr-test-transformation-guide.md. The transformed tests must use checkpoint-based testing with multiple test methods for different upgrade points.
   - (b) Compile the test and fix compilation errors.
   - (c) Update this tracker to mark it as: ✅ Finished, ⏸️ Blocked, or ❌ Skipped (with notes).
3. After finishing one test (including tracker update), **automatically move on to the next test**.
4. Only stop when all tests are ✅/⏸️/❌, or user explicitly says "STOP" or "RESET".

---

## Recheck Workflow Rules

When rechecking previously skipped (❌) tests:

1. **Selection**: Pick tests marked with ❌ **Skipped** that may be reconsidered for transformation.
2. **Re-evaluation**: For each selected test:
   - (a) Review the original skip reason in the Notes column.
   - (b) Re-assess if transformation is now feasible (e.g., new workarounds, reduced transformation approach, API changes).
   - (c) If transformation is possible, proceed with transformation following the standard workflow rules.
   - (d) If transformation is still not feasible, mark as 🔄 **Rechecked** with updated notes explaining why it remains unsuitable.
3. **Status Update**: Update this tracker with one of the following outcomes:
   - ✅ **Finished** - Successfully transformed after recheck
   - 🔄 **Rechecked** - Reviewed but still not suitable (update notes with recheck findings)
4. **Documentation**: Always update the Notes column with:
   - Date of recheck (e.g., "Rechecked 2025-11-23")
   - New findings or reasons for continued unsuitability
   - Any partial transformation attempts or alternative approaches considered
5. **Continuation**: After rechecking one test, move on to the next test in the recheck queue or wait for user direction.

---

## Transformation Guidelines for Solr Tests

### What to Transform
- Tests that primarily use CloudSolrClient, Collections API, ConfigSet API
- Tests that verify cluster state via ZkStateReader
- Tests focused on client-visible behavior (indexing, querying, admin operations)
- Tests that validate distributed search/indexing functionality

### What to Skip
- Please transform as much as possible, but skip tests that are fundamentally incompatible with ProcessBased approach:
   - Tests requiring direct JettySolrRunner object access with no client API equivalent
   - Tests requiring internal CoreContainer access
   - Tests requiring in-JVM class injection or mocking
   - Tests heavily dependent on internal metrics not exposed via client APIs

### Transformation Approach
- **File Location**: Place transformed tests in same directory as original with `_ProcessBased` suffix
- **Naming Convention**: `Test{Feature}_ProcessBased.java`
- **Package**: Same package as original test
- **Base Class**: Extend `ProcessBasedUpgradeTestBase`
- **Checkpoints**: Use checkpoint-based testing with multiple test methods:
  - `testMethod_NO_UPGRADE()` - baseline test
  - `testMethod_AFTER_CLUSTER_START()` - upgrade after cluster starts
  - Additional checkpoints as appropriate (AFTER_COLLECTION_CREATE, AFTER_INDEX, etc.)

---

## Target Tests by Package

### solr/core: org.apache.solr.api

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | NodeConfigClusterPluginsSourceTest | |

### solr/core: org.apache.solr.cli

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestSolrCLIRunExample | |

### solr/core: org.apache.solr.cloud

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | CloudExitableDirectoryReaderTest | |
| ⏳ | ConcurrentCreateRoutedAliasTest | |
| ⏳ | DistribDocExpirationUpdateProcessorTest | |
| ⏳ | MultiSolrCloudTestCaseTest | |
| ⏳ | OverseerCollectionConfigSetProcessorTest | |
| ⏳ | OverseerTest | |
| ⏳ | TestAuthenticationFramework | |
| ⏳ | TestCloudDeleteByQuery | |
| ⏳ | TestCloudPhrasesIdentificationComponent | |
| ⏳ | TestCloudPseudoReturnFields | |
| ⏳ | TestConfigSetsAPIExclusivity | |
| ⏳ | TestConfigSetsAPIZkFailure | |
| ⏳ | TestGracefulJettyShutdown | |
| ⏳ | TestMiniSolrCloudClusterSSL | |
| ⏳ | TestRandomFlRTGCloud | |
| ⏳ | TestRequestForwarding | |
| ⏳ | TestSSLRandomization | |
| ⏳ | TestStressCloudBlindAtomicUpdates | |
| ⏳ | TestStressLiveNodes | |
| ⏳ | TestTolerantUpdateProcessorCloud | |
| ⏳ | TestTolerantUpdateProcessorRandomCloud | |
| ⏳ | TestWaitForStateWithJettyShutdowns | |
| ⏳ | ZkSolrClientTest | |

### solr/core: org.apache.solr.cloud.api.collections

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | BackupRestoreApiErrorConditionsTest | |
| ⏳ | ConcurrentDeleteAndCreateCollectionTest | |
| ⏳ | TestCollectionAPI | |
| ⏳ | TestCollectionsAPIViaSolrCloudCluster | |
| ⏳ | TestLocalFSCloudBackupRestore | |

### solr/core: org.apache.solr.cluster.placement.impl

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | NodeConfigPlacementPluginTest | |
| ⏳ | PlacementPluginIntegrationTest | |

### solr/core: org.apache.solr.filestore

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestDistribFileStore | |

### solr/core: org.apache.solr.handler

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | PingRequestHandlerTest | |

### solr/core: org.apache.solr.handler.admin

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | DaemonStreamApiTest | |

### solr/core: org.apache.solr.handler.component

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | SearchHandlerTest | |
| ⏳ | ShardsAllowListTest | |

### solr/core: org.apache.solr.metrics

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | SolrMetricsIntegrationTest | |

### solr/core: org.apache.solr.pkg

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestPackages | |

### solr/core: org.apache.solr.response

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestRawTransformer | |

### solr/core: org.apache.solr.search

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestCoordinatorRole | |

### solr/core: org.apache.solr.search.facet

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestCloudJSONFacetJoinDomain | |
| ⏳ | TestCloudJSONFacetSKG | |
| ⏳ | TestCloudJSONFacetSKGEquiv | |

### solr/core: org.apache.solr.search.join

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | ShardToShardJoinAbstract | |

### solr/core: org.apache.solr.search.stats

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestDistribIDF | |

### solr/core: org.apache.solr.security

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | AuditLoggerIntegrationTest | |

### solr/core: org.apache.solr.util

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestCborDataFormat | |

### solr/cross-dc-manager: org.apache.solr.crossdc.manager

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | DeleteByQueryToIdTest | |
| ⏳ | RetryQueueIntegrationTest | |
| ⏳ | SimpleSolrIntegrationTest | |
| ⏳ | SolrAndKafkaIntegrationTest | |
| ⏳ | SolrAndKafkaMultiCollectionIntegrationTest | |
| ⏳ | SolrAndKafkaReindexTest | |
| ⏳ | ZkConfigIntegrationTest | |

### solr/modules/hadoop-auth: org.apache.solr.security.hadoop

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestSolrCloudWithDelegationTokens | |
| ⏳ | TestSolrCloudWithSecureImpersonation | |
| ⏳ | TestZkAclsWithHadoopAuth | |

### solr/modules/jaegertracer-configurator: org.apache.solr.jaeger

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestJaegerConfigurator | |

### solr/modules/jwt-auth: org.apache.solr.security.jwt

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | JWTAuthPluginIntegrationTest | |

### solr/modules/ltr: org.apache.solr.ltr

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestLTROnSolrCloud | |

### solr/modules/opentelemetry: org.apache.solr.opentelemetry

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | OtelTracerConfiguratorTest | |

### solr/solrj: org.apache.solr.client.solrj.impl

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | TestCloudSolrClientConnections | |

### solr/solrj: org.apache.solr.common.cloud

| Status | Test Class | Notes |
|--------|------------|-------|
| ⏳ | PerReplicaStatesIntegrationTest | |
| ⏳ | TestNodesSysPropsCacher | |

---

## Excluded Tests (Framework/Infrastructure Tests)

The following tests are excluded from transformation as they test the testing framework itself rather than Solr functionality:

| Test Class | Reason |
|------------|--------|
| MiniSolrCloudClusterTest | Tests MiniSolrCloudCluster framework itself |
| JettySolrRunnerTest | Tests JettySolrRunner framework |
| TestJettySolrRunner | Tests JettySolrRunner framework |
| ProcessBasedMiniSolrCloudClusterTest | Already ProcessBased - tests ProcessBased framework |
| ProcessBasedMiniSolrCloudClusterDebugTest | Already ProcessBased - debug test |
| RealSolrProcessTest | Already ProcessBased - integration test for bin/solr |
| ProcessBasedUpgradeTestBase | Base class for upgrade tests |
| ProcessBasedUpgradeTestBaseVerification | Verification test for base class |

---

## Transformation Best Practices

### Common Patterns in Solr Tests

1. **Collection Operations**
   - Already client-side via CollectionAdminRequest
   - No transformation needed for basic operations

2. **Indexing and Querying**
   - Use CloudSolrClient (already client-side)
   - No transformation needed

3. **Cluster State Verification**
   - Use ZkStateReader for live nodes, collection state
   - Replace direct node access with ZK state checks

4. **Node Management**
   - Use cluster.stopJettySolrRunner(i) / cluster.startJettySolrRunner(i)
   - Node identity preserved across restarts

5. **Resource Cleanup**
   - No try-finally needed - ProcessBasedUpgradeTestBase handles cleanup
   - Use protected fields: cluster, solrClient, collectionClients

### Checkpoint Placement Strategies

For typical Solr tests, use these checkpoint patterns:

- **NO_UPGRADE** - Always include baseline test
- **AFTER_CLUSTER_START** - Test upgrade immediately after cluster initialization
- **AFTER_COLLECTION_CREATE** - Test upgrade after collection setup
- **AFTER_INDEX** - Test upgrade with existing data
- **AFTER_COMMIT** - Test upgrade with committed data
- **AFTER_QUERY** - Test upgrade after query execution

### Example Transformation

**Original Test:**
```java
public class TestMyFeature extends SolrCloudTestCase {
  @Test
  public void testSomething() throws Exception {
    MiniSolrCloudCluster cluster = new MiniSolrCloudCluster.Builder(3, createTempDir())
        .addConfig("conf", configPath)
        .build();

    // test logic

    cluster.shutdown();
  }
}
```

**Transformed Test:**
```java
public class TestMyFeature_ProcessBased extends ProcessBasedUpgradeTestBase {
  @Test
  public void testSomething_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;

    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .build();
    cluster.start();
    cluster.uploadConfigSet(configPath, "conf");
    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // test logic
    // No shutdown needed - @After handles cleanup
  }

  @Test
  public void testSomething_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;

    // Same test logic - upgrade happens at checkpoint
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    cluster.uploadConfigSet(configPath, "conf");
    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // test logic continues...
  }
}
```

---

## Notes

- **System Properties Required**: All ProcessBased tests require `-Dsolr.start.home` and optionally `-Dsolr.upgrade.home`
- **Test Isolation**: Each test method runs with fresh cluster - complete isolation guaranteed
- **Node Identity**: ProcessBasedMiniSolrCloudCluster preserves node ports/URLs across restarts
- **Process Management**: Uses real bin/solr scripts - no embedded JettySolrRunner
- **Cleanup**: Automatic process and directory cleanup via base class @After method

---

**Last Updated**: 2025-11-23
