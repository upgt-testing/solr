# Step 3: Test Transformation Guide for Apache Solr

## Table of Contents
1. [Introduction & Philosophy](#introduction--philosophy)
2. [Prerequisites & Setup](#prerequisites--setup)
3. [Test Organization and Naming Convention](#test-organization-and-naming-convention)
4. [Core Transformation Rules](#core-transformation-rules)
5. [API Mapping Tables](#api-mapping-tables)
6. [Step-by-Step Transformation Process](#step-by-step-transformation-process)
7. [Common Transformation Patterns](#common-transformation-patterns)
8. [Inserting Cluster Upgrade Method Calls](#inserting-cluster-upgrade-method-calls)
9. [Upgrade Checkpoint Test Methods](#upgrade-checkpoint-test-methods)
10. [When to Comment Out Logic](#when-to-comment-out-logic)
11. [Testing Checklist](#testing-checklist)
12. [Best Practices](#best-practices)
13. [Quick Reference Decision Tree](#quick-reference-decision-tree)

---

## Introduction & Philosophy

### Purpose
Transform existing `MiniSolrCloudCluster` tests to `ProcessBasedMiniSolrCloudCluster` to enable:
- **Process-based testing** - Each Solr node runs in separate JVM for realistic testing
- **Multi-version testing** - Test upgrades between different Solr versions
- **Rolling upgrade scenarios** - Simulate production upgrade procedures
- **Version compatibility** - Verify protocol compatibility across versions (8.x ↔ 9.x)

### Key Principle
**Most server-side operations have client-side HTTP/SolrJ equivalents.** The goal is to maximize test logic preservation by finding client-side APIs that provide equivalent functionality.

### Critical Transformation Mindset

**EVERY REDUCED VERSION IS MEANINGFUL!**

If you cannot transform 100% of a test, transform what you CAN. A test with 30% preserved logic is infinitely better than 0%. Never skip a test just because:
- It uses a custom class (analyze what the class actually does)
- It has some internal access (transform the accessible parts)
- It seems "too complex" (reduce to essential behavior)

**Transform as much as possible, comment out as little as necessary.**

### Transformation Hierarchy
When encountering server-side operations, try these approaches in order:

1. **CloudSolrClient** - High-level client operations
2. **Collections API / ConfigSet API** - Administrative operations
3. **SolrJ Request APIs** - Mid-level operations
4. **HTTP Admin APIs** - Low-level admin operations
5. **ZkStateReader** - For cluster state and live nodes
6. **Metrics / JMX** - For monitoring and statistics
7. **Comment Out** - Only if truly no client-side equivalent exists

### Why ProcessBasedMiniSolrCloudCluster?

**MiniSolrCloudCluster limitations:**
- All nodes run in same JVM - cannot test different Solr versions
- Direct object access - not realistic for production scenarios
- In-process - cannot simulate true process failures and restarts

**ProcessBasedMiniSolrCloudCluster benefits:**
- True process isolation - realistic testing
- Multi-version support - essential for upgrade testing (8.x → 9.x)
- Client-only access - forces use of public APIs (more realistic)
- Better represents production environments
- Node identity persistence - nodes maintain address/port across restarts

---

## Prerequisites & Setup

### System Properties (Automatic!)
ProcessBasedMiniSolrCloudCluster automatically reads distributions from system properties:

```bash
# No manual setup needed! Just pass system properties to Gradle:
gradle test --tests YourTransformedTest \
  -Dsolr.start.home=/path/to/solr-9.8.0 \
  -Dsolr.upgrade.home=/path/to/solr-9.9.0 \
  -p solr/test-framework
```

**Backward compatibility:** Environment variables (`SOLR_START_HOME`, `SOLR_UPGRADE_HOME`) still work as fallback.

### Test Configuration
```java
import org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster;

// No @Before setup needed! System properties are read automatically!
// Add timeout 120s to allow for process startup time
@Test(timeout=120000)
public void testSomething() throws Exception {
    // Just build - automatic!
    ProcessBasedMiniSolrCloudCluster cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(3)
            .withStartVersionFromSystemProperty()
            .build();  // Automatically reads system properties!
    cluster.start();
}
```

### Running Transformed Tests
```bash
# Run with system properties (recommended)
gradle test --tests YourTransformedTest \
  -Dsolr.start.home=/opt/solr-9.8.0 \
  -Dsolr.upgrade.home=/opt/solr-9.9.0

# Or with environment variables (fallback)
export SOLR_START_HOME=/opt/solr-9.8.0
export SOLR_UPGRADE_HOME=/opt/solr-9.9.0
gradle test --tests YourTransformedTest
```

---

## Test Organization and Naming Convention

### File Location Strategy
**Place transformed tests in the SAME directory as the original tests**, using a naming suffix to distinguish them.

This approach provides:
- ✅ Side-by-side comparison of original and transformed tests
- ✅ Tests alphabetically adjacent in file listings
- ✅ Clear visual distinction via suffix
- ✅ Original package structure preserved
- ✅ Both versions can coexist long-term

### Naming Convention: `_ProcessBased` Suffix

```
Original Test:     Test{Feature}.java
Transformed Test:  Test{Feature}_ProcessBased.java

Location:          Same directory, same package
```

### Directory Structure Examples

```
solr/core/src/test/org/apache/solr/cloud/
├── TestCloudRestart.java                      [ORIGINAL - MiniSolrCloudCluster]
├── TestCloudRestart_ProcessBased.java         [TRANSFORMED - ProcessBased]
├── TestCollectionAPI.java                     [ORIGINAL - MiniSolrCloudCluster]
├── TestCollectionAPI_ProcessBased.java        [TRANSFORMED - ProcessBased]
└── upgrade/
    ├── TestUpgradeScenarios.java              [ORIGINAL - MiniSolrCloudCluster]
    └── TestUpgradeScenarios_ProcessBased.java [TRANSFORMED - ProcessBased]
```

### Package and Class Declaration

The transformed test uses the **same package** as the original:

```java
// Original: TestCloudRestart.java
package org.apache.solr.cloud;

public class TestCloudRestart extends SolrCloudTestCase {
  // ... MiniSolrCloudCluster tests
}
```

```java
// Transformed: TestCloudRestart_ProcessBased.java
package org.apache.solr.cloud;  // Same package!

import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;

/**
 * ProcessBased version of {@link TestCloudRestart}.
 *
 * Transformed from MiniSolrCloudCluster to ProcessBasedMiniSolrCloudCluster to enable
 * process-based testing and multi-version upgrade scenarios.
 *
 * @see TestCloudRestart Original test using MiniSolrCloudCluster
 */
public class TestCloudRestart_ProcessBased extends ProcessBasedUpgradeTestBase {
  // ... ProcessBasedMiniSolrCloudCluster tests
}
```

### Test Execution Patterns

```bash
# Run original test only
gradle test --tests TestCloudRestart

# Run transformed test only
gradle test --tests TestCloudRestart_ProcessBased

# Run ALL ProcessBased tests across the codebase
gradle test --tests "*_ProcessBased"

# Run both versions for comparison
gradle test --tests "TestCloudRestart*"
```

---

## Core Transformation Rules

### Rule 1: Maximize Test Logic Preservation
**Preserve as much of the original test logic as possible** by finding client-side equivalents for server-side operations.

✅ **DO**: Find client API that provides same functionality
❌ **DON'T**: Remove test logic unless absolutely necessary

### Rule 2: Use the API Hierarchy
Always try to find client-side equivalents in this order:
1. CloudSolrClient (highest level)
2. Collections API / ConfigSet API (admin operations)
3. SolrJ Request APIs (mid-level)
4. HTTP Admin APIs (low-level)
5. ZkStateReader (cluster state)
6. Metrics / JMX (monitoring)

### Rule 3: Comment Out Only When Necessary
Only comment out operations when:
- No client-side API exists
- Operation accesses internal storage (CoreContainer internals)
- Operation manipulates JVM-internal state

### Rule 4: Document Minimally
Add comments only for:
- Non-obvious transformations
- Commented-out logic (explain why and what was removed)
- Workarounds or limitations

---

## API Mapping Tables

### Table 1: MiniSolrCloudCluster → ProcessBasedMiniSolrCloudCluster

| MiniSolrCloudCluster Method | ProcessBasedMiniSolrCloudCluster | Status | Notes |
|------------------------------|----------------------------------|--------|-------|
| **Cluster Creation** |
| `new Builder().build()` | `new Builder().build()` | ✓ | Automatically reads system properties |
| `new Builder(numNodes, baseDir)` | `new Builder().withNodeCount(n)` | ⚠️ | Different API - use withNodeCount() |
| `.addConfig()` | Upload after build with `uploadConfigSet()` | ⚠️ | Config upload happens after cluster starts |
| N/A | `.withStartVersionFromSystemProperty()` | ✓ | New - reads solr.start.home |
| N/A | `.withUpgradeVersionFromSystemProperty()` | ✓ | New - reads solr.upgrade.home |
| N/A | `cluster.start()` | ✓ | New - must call after build() |
| **Client Access** |
| `getSolrClient()` | `getSolrClient()` | ✓ | Same API |
| `getSolrClient(String collection)` | `getSolrClient(String collection)` | ✓ | Same API |
| **ZooKeeper Access** |
| `getZkClient()` | `getZkClient()` | ✓ | Same API |
| `getZkStateReader()` | `getZkStateReader()` | ✓ | Same API |
| `getZkServer()` | `getZkServer()` | ✓ | Same API |
| **Configuration** |
| `uploadConfigSet(Path, String)` | `uploadConfigSet(Path, String)` | ✓ | Same API |
| `deleteAllConfigSets()` | `deleteAllConfigSets()` | ✓ | Same API |
| **Node Management** |
| `startJettySolrRunner()` | `startJettySolrRunner()` | ✓ | Same API |
| `startJettySolrRunner(JettySolrRunner)` | `startJettySolrRunner(int i)` | ⚠️ | Different signature - use index |
| `stopJettySolrRunner(int i)` | `stopJettySolrRunner(int i)` | ✓ | Same API |
| `stopJettySolrRunner(JettySolrRunner)` | Use index instead | ⚠️ | Get index first, then use int version |
| `waitForAllNodes(int timeout)` | `waitForAllNodes(int timeout)` | ✓ | Same API |
| **Direct Object Access** |
| `getJettySolrRunners()` | ❌ | ✗ | Use client APIs instead |
| `getJettySolrRunner(int i)` | ❌ | ✗ | Use client APIs instead |
| **Cluster Control** |
| `deleteAllCollections()` | `deleteAllCollections()` | ✓ | Same API |
| `shutdown()` | `shutdown()` | ✓ | Same API |
| **Cluster State** |
| `getBaseUrl()` | `getJettyBaseUrl(int i)` | ⚠️ | Returns URL for specific node |
| Implicit node count | `getNumJettys()` | ⚠️ | Explicit method call |

### Table 2: JettySolrRunner (Server-Side) → Client-Side APIs

**Context**: `JettySolrRunner` is a server-side class managing individual Solr nodes. Most operations have client-side equivalents.

| JettySolrRunner Method | Client-Side API | API Layer | Code Example |
|------------------------|-----------------|-----------|--------------|
| **Node Information** |
| `getBaseUrl()` | `cluster.getJettyBaseUrl(i)` | Cluster | `String url = cluster.getJettyBaseUrl(0);` |
| `getNodeName()` | Check ZkStateReader.getLiveNodes() | ZK | `Set<String> liveNodes = zkStateReader.getClusterState().getLiveNodes();` |
| `isRunning()` | Check in live_nodes | ZK | `boolean live = zkStateReader.getClusterState().getLiveNodes().contains(nodeName);` |
| **Core/Collection Info** |
| `getCoreContainer()` | ❌ | - | Internal - comment out |
| `getCoreContainer().getCores()` | Collections API | Client | `CollectionAdminRequest.listCollections(solrClient)` |
| **Internal Operations** (❌ No client API) |
| `getCoreContainer().getMetrics()` | ❌ | - | Internal metrics - comment out or use JMX |
| Direct core access | ❌ | - | Internal - comment out |

### Table 3: Collections API (Already Client-Side)

These operations are **already client-side** in MiniSolrCloudCluster tests - no transformation needed!

| Operation | Collections API | Already Client-Side? |
|-----------|----------------|----------------------|
| Create collection | `CollectionAdminRequest.createCollection()` | ✓ Yes |
| Delete collection | `CollectionAdminRequest.deleteCollection()` | ✓ Yes |
| Reload collection | `CollectionAdminRequest.reloadCollection()` | ✓ Yes |
| Split shard | `CollectionAdminRequest.splitShard()` | ✓ Yes |
| Add replica | `CollectionAdminRequest.addReplica()` | ✓ Yes |
| Delete replica | `CollectionAdminRequest.deleteReplica()` | ✓ Yes |
| Cluster status | `CollectionAdminRequest.clusterStatus()` | ✓ Yes |
| List collections | `CollectionAdminRequest.listCollections()` | ✓ Yes |

### Table 4: ConfigSet API (Already Client-Side)

| Operation | ConfigSet API | Already Client-Side? |
|-----------|--------------|----------------------|
| Upload config | `ConfigSetAdminRequest.Upload` | ✓ Yes |
| Download config | `ConfigSetAdminRequest.Download` | ✓ Yes |
| List configs | `ConfigSetAdminRequest.List` | ✓ Yes |
| Delete config | `ConfigSetAdminRequest.Delete` | ✓ Yes |

### Table 5: Cluster State & Monitoring

| Server-Side Check | Client-Side Alternative | Access Method | Example |
|------------------|------------------------|---------------|---------|
| **Cluster State** |
| Node live status | `ZkStateReader.getLiveNodes()` | ZK | `zkStateReader.getClusterState().getLiveNodes()` |
| Collection state | `ZkStateReader.getCollectionState()` | ZK | `DocCollection state = zkStateReader.getCollectionState("myCol")` |
| Shard state | Collection state | ZK | `Collection<Slice> shards = collectionState.getSlices()` |
| Replica state | Shard state | ZK | `Collection<Replica> replicas = shard.getReplicas()` |
| **Metrics** |
| CoreContainer metrics | Metrics API or JMX | HTTP | Use `/admin/metrics` endpoint or JMX if needed |
| Per-core metrics | Metrics API | HTTP | `/solr/{core}/admin/mbeans?stats=true` |

### Table 6: Common Test Utilities

| MiniSolrCloudCluster Pattern | ProcessBasedMiniSolrCloudCluster Alternative | Notes |
|------------------------------|----------------------------------------------|-------|
| Direct jetty.getCoreContainer() | Use Collections API or ZkStateReader | Access cluster state via client |
| waitForActiveCollection() | Same method available | ✓ Already client-side |
| waitForState() | Same method available | ✓ Already client-side |
| Trigger replica recovery | Use Collections API | `CollectionAdminRequest.forceLeader()` or wait naturally |

---

## Step-by-Step Transformation Process

### Step 0: Create Transformed Test File

**Goal**: Set up the new test file with proper naming and location.

1. **Locate the original test:**
   ```bash
   # Example: Original test
   solr/core/src/test/org/apache/solr/cloud/TestCloudRestart.java
   ```

2. **Create new file with `_ProcessBased` suffix in the SAME directory:**
   ```bash
   # New transformed test (same directory!)
   solr/core/src/test/org/apache/solr/cloud/TestCloudRestart_ProcessBased.java
   ```

3. **Copy original test content to new file:**
   ```bash
   cp TestCloudRestart.java TestCloudRestart_ProcessBased.java
   ```

4. **Update class name and add Javadoc:**
   ```java
   package org.apache.solr.cloud;  // Same package as original!

   import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;

   /**
    * ProcessBased version of {@link TestCloudRestart}.
    *
    * Transformed from MiniSolrCloudCluster to ProcessBasedMiniSolrCloudCluster to enable
    * process-based testing and multi-version upgrade scenarios.
    *
    * @see TestCloudRestart Original test using MiniSolrCloudCluster
    */
   public class TestCloudRestart_ProcessBased extends ProcessBasedUpgradeTestBase {
     // ... test methods
   }
   ```

5. **Checklist before proceeding:**
   - [ ] New file created in same directory as original
   - [ ] Class name has `_ProcessBased` suffix
   - [ ] Package declaration is identical to original
   - [ ] Javadoc references original test with `@see` tag
   - [ ] Extends ProcessBasedUpgradeTestBase
   - [ ] File compiles (even if tests fail)

### Step 1: Analyze Test Dependencies

**Goal**: Understand what server-side operations the test uses.

1. **Scan for direct object access patterns:**
   ```bash
   # Search for common patterns
   grep -E "getJettySolrRunner|getCoreContainer" TestCloudRestart.java
   grep -E "\.getCores\(\)" TestCloudRestart.java
   ```

2. **Categorize operations:**
   - ✅ **Already client-side**: CloudSolrClient operations, Collections API calls
   - ⚠️ **Has client equivalent**: CoreContainer.getCores() → Collections API
   - ❌ **No client equivalent**: Internal storage, internal state, JVM state

3. **Plan transformation:**
   - List all operations that need transformation
   - Find client equivalents in mapping tables
   - Identify operations that must be commented out

### Step 2: Transform Import Statements

```java
// BEFORE
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.embedded.JettySolrRunner;

// AFTER
import org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
// Remove: import org.apache.solr.cloud.MiniSolrCloudCluster;
// Remove: import org.apache.solr.embedded.JettySolrRunner; (if not needed)
```

### Step 3: Transform Cluster Setup

#### Basic Cluster Creation

```java
// BEFORE (MiniSolrCloudCluster)
MiniSolrCloudCluster cluster = new MiniSolrCloudCluster.Builder(3, createTempDir())
    .addConfig("conf", configPath)
    .configure();

// AFTER (ProcessBasedMiniSolrCloudCluster) - AUTOMATIC!
// No environment variable checks needed! Automatic!

cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
    .withNodeCount(3)
    .withStartVersionFromSystemProperty()
    .build();  // Automatically reads system properties!

cluster.start();  // Start the cluster

// Upload config after cluster starts
cluster.uploadConfigSet(configPath, "conf");
```

**Note:** System properties are passed via Gradle:
```bash
gradle test --tests MyTest \
  -Dsolr.start.home=/path/to/solr-9.8.0 \
  -Dsolr.upgrade.home=/path/to/solr-9.9.0
```

#### Multi-Version Cluster (for upgrade tests)

```java
// All ProcessBased tests support upgrades automatically!
// Just build the cluster - it reads system properties automatically

cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
    .withNodeCount(3)
    .withStartVersionFromSystemProperty()
    .withUpgradeVersionFromSystemProperty()
    .build();  // Starts with solr.start.home

cluster.start();

// Later, perform rolling upgrade to solr.upgrade.home
cluster.upgrade();  // Built-in rolling upgrade!
```

### Step 4: Transform Operations Using Mapping Tables

#### Example 1: CloudSolrClient Operations (No Change)
```java
// These work identically - no transformation needed
CloudSolrClient client = cluster.getSolrClient();
client.add("myCollection", docs);
client.commit("myCollection");
```

#### Example 2: Node Access → Client API
```java
// BEFORE: Direct server-side access
JettySolrRunner jetty = cluster.getJettySolrRunner(0);
String baseUrl = jetty.getBaseUrl();

// AFTER: Client-side equivalent
// Note: ProcessBasedMiniSolrCloudCluster uses real Solr processes (bin/solr),
// not JettySolrRunner objects. Use cluster methods to get node information.
String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
```

#### Example 3: CoreContainer Check → Comment Out
```java
// BEFORE: Internal storage verification
CoreContainer cc = cluster.getJettySolrRunner(0).getCoreContainer();
SolrCore core = cc.getCore("myCore");
// ... verification code

// AFTER: Comment out with documentation
// TRANSFORMATION NOTE: Internal CoreContainer verification removed.
// getCoreContainer() provides access to internal Solr core management,
// which is not available via any client API.
// Original test verified core loading state.
// No client-side alternative available.
//
// Original code:
// CoreContainer cc = cluster.getJettySolrRunner(0).getCoreContainer();
// SolrCore core = cc.getCore("myCore");
// ... (commented out verification code)
```

### Step 5: Transform Cleanup Code

```java
// BEFORE (if using try-finally)
@Test
public void testSomething() throws Exception {
    MiniSolrCloudCluster cluster = ...;
    CloudSolrClient client = cluster.getSolrClient();
    try {
        // test logic
    } finally {
        cluster.shutdown();
    }
}

// AFTER (using ProcessBasedUpgradeTestBase - NO try-finally needed!)
@Test
public void testSomething_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;

    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .build();
    cluster.start();
    solrClient = cluster.getSolrClient();

    // test logic with checkpoints
    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // No try-finally needed - @After handles cleanup automatically!
}
```

### Step 6: Validate Transformation

Run through this checklist:

- [ ] All imports updated
- [ ] All direct object access either transformed or commented out
- [ ] Cluster creation uses ProcessBasedMiniSolrCloudCluster.Builder
- [ ] Test extends ProcessBasedUpgradeTestBase
- [ ] Test logic preserved as much as possible
- [ ] Only truly internal operations commented out
- [ ] Comments added for non-obvious transformations
- [ ] Test compiles without errors
- [ ] System properties documented in test javadoc

---

## Common Transformation Patterns

### Pattern 1: CloudSolrClient Operations
**Status**: ✅ No transformation needed

```java
// Works identically in both frameworks
CloudSolrClient client = cluster.getSolrClient();
client.add("collection1", docs);
client.commit("collection1");
QueryResponse rsp = client.query("collection1", new SolrQuery("*:*"));
```

### Pattern 2: Collections API Operations
**Status**: ✅ No transformation needed

```java
// Already client-side - works identically
CollectionAdminRequest.createCollection("test", "conf", 1, 3)
    .process(client);

CollectionAdminRequest.deleteCollection("test")
    .process(client);
```

### Pattern 3: ZooKeeper State Checking
```java
// BEFORE: Via JettySolrRunner
JettySolrRunner jetty = cluster.getJettySolrRunner(0);
String nodeName = jetty.getNodeName();

// AFTER: Via ZkStateReader
ZkStateReader zkStateReader = cluster.getZkStateReader();
Set<String> liveNodes = zkStateReader.getClusterState().getLiveNodes();
// nodeName is in liveNodes set
```

### Pattern 4: Waiting for Cluster State
```java
// Both frameworks support this - no change needed
cluster.waitForActiveCollection("myCollection", 1, 3);

// Or using ZkStateReader
zkStateReader.waitForState("myCollection", 30, TimeUnit.SECONDS,
    (liveNodes, collectionState) -> {
        return collectionState != null && collectionState.isActive();
    });
```

---

## Inserting Cluster Upgrade Method Calls

### Overview

When transforming tests to support rolling upgrades, you need to insert `cluster.upgrade()` method calls at appropriate points in the test. This section explains how to identify upgrade points and handle the critical pattern of **closing resources before upgrade and reopening them afterward**.

### Why Resource Management is Critical

During a rolling upgrade, Solr nodes are restarted with new software versions. This restart **breaks active HTTP connections** between the client and the nodes.

**Key principle**: Any active connection/stream/resource that spans an upgrade point must be:
1. **Closed** before calling `cluster.upgrade()`
2. **Reopened** after `cluster.upgrade()` completes

### Why Node Identity Preservation is Critical

In addition to closing resources, **node identity must be preserved** during upgrades.

**What is Node Identity for Solr?**
- Jetty HTTP port
- Node name (registered in ZooKeeper live_nodes)
- Work directory
- Configuration directory

**Why It Matters:**

If a node's identity changes during restart/upgrade:
- ❌ ZooKeeper thinks it's a NEW node joining
- ❌ Original node appears DEAD
- ❌ Cluster triggers unnecessary replica placement
- ❌ Data may be unnecessarily replicated
- ❌ Upgrade test fails to represent production behavior

**How ProcessBasedMiniSolrCloudCluster Preserves Identity:**

The cluster automatically:
1. **Persists port allocations** to disk before first startup
2. **Reuses persisted ports** during restart/upgrade
3. **Maintains work directory** with configuration
4. **Validates port availability** before restart
5. **Throws error** if identity cannot be preserved

**Example:**

```java
// Initial startup
cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
    .numJettys(3)
    .build();
// Jetty 0 gets HTTP port 50001, persisted to disk

// Later: Rolling upgrade
String urlBefore = cluster.getJettyBaseUrl(0);
// urlBefore = http://localhost:50001/solr

cluster.stopJettySolrRunner(0);
cluster.changeJettyVersion(0, upgradeVersion);
cluster.startJettySolrRunner(0);
// Jetty 0 loads persisted port 50001, starts with SAME URL

String urlAfter = cluster.getJettyBaseUrl(0);
// urlAfter = http://localhost:50001/solr

assert urlBefore.equals(urlAfter);  // Identity preserved!
```

### Identifying Upgrade Points

An upgrade point is a logical location in your test where you want to simulate a rolling upgrade. Common upgrade points include:

1. **Mid-operation** - Testing that data indexed before upgrade is queryable after upgrade
2. **Between distinct test phases** - After collection creation but before indexing
3. **After indexing test data** - Testing upgrade with existing documents
4. **After commit** - Ensuring committed data survives upgrade

### Step-by-Step: Inserting Upgrade Calls

#### Step 1: Identify the Upgrade Point

Look for a logical point in the test where upgrade makes sense:

```java
// BEFORE: Original test without upgrade
solrClient.add("myCollection", docs1);
solrClient.commit("myCollection");
// <-- Potential upgrade point
solrClient.add("myCollection", docs2);
solrClient.commit("myCollection");
```

#### Step 2: Close Resources Before Upgrade

If a resource is open at the upgrade point, close it first:

```java
// AFTER: With upgrade point inserted
solrClient.add("myCollection", docs1);
solrClient.commit("myCollection");

// === ROLLING UPGRADE POINT ===
// CRITICAL: Close the client before upgrade since nodes will be restarted
solrClient.close();
System.out.println("Closed client before rolling upgrade");
```

#### Step 3: Call cluster.upgrade()

```java
// Perform rolling upgrade
cluster.upgrade();  // Executes full rolling upgrade procedure
System.out.println("Rolling upgrade completed successfully");
```

**Note**: The `cluster.upgrade()` method:
- Automatically upgrades all Solr nodes in sequence
- Waits for each node to be healthy before proceeding
- Ensures cluster remains available throughout upgrade

#### Step 4: Reopen Resources After Upgrade

If you need to continue operations, reopen the resource:

```java
// Reopen the client after upgrade
solrClient = cluster.getSolrClient();
System.out.println("Reopened client after upgrade");

// Continue operations
solrClient.add("myCollection", docs2);
solrClient.commit("myCollection");
```

### Complete Example Pattern

```java
@Test
public void testIndexing() throws Exception {
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    solrClient = cluster.getSolrClient();

    try {
        cluster.waitForAllNodes(30);

        // Create collection
        CollectionAdminRequest.createCollection("myCollection", "conf", 1, 3)
            .process(solrClient);

        // Index first batch of documents
        solrClient.add("myCollection", createDocs(100));
        solrClient.commit("myCollection");

        // === ROLLING UPGRADE POINT ===
        // STEP 1: Close the client before upgrade
        solrClient.close();
        System.out.println("Closed client before rolling upgrade");

        // STEP 2: Capture node identities BEFORE upgrade
        Map<Integer, String> preUpgradeUrls = new HashMap<>();
        for (int i = 0; i < cluster.getNodeCount(); i++) {
            preUpgradeUrls.put(i, cluster.getJettySolrRunnerBaseUrl(i));
        }

        // STEP 3: Perform rolling upgrade
        cluster.upgrade();
        System.out.println("Rolling upgrade completed successfully");

        // STEP 4: Verify node identities PRESERVED
        for (int i = 0; i < cluster.getNodeCount(); i++) {
            String postUrl = cluster.getJettySolrRunnerBaseUrl(i);
            String preUrl = preUpgradeUrls.get(i);
            if (!postUrl.equals(preUrl)) {
                throw new AssertionError(
                    "Node " + i + " URL changed during upgrade! " +
                    "Before: " + preUrl + ", After: " + postUrl);
            }
        }
        System.out.println("Node identities preserved across upgrade");

        // STEP 5: Reopen client
        solrClient = cluster.getSolrClient();
        System.out.println("Reopened client after upgrade");

        // Continue operations after upgrade
        solrClient.add("myCollection", createDocs(100));
        solrClient.commit("myCollection");

        // Verify all data
        QueryResponse rsp = solrClient.query("myCollection",
            new SolrQuery("*:*"));
        assertEquals(200, rsp.getResults().getNumFound());

    } finally {
        // Cleanup handled by ProcessBasedUpgradeTestBase
    }
}
```

### Common Mistakes to Avoid

#### ❌ Mistake 1: Not closing resources before upgrade

```java
// WRONG - Client remains open during upgrade
solrClient.add("col", docs1);
cluster.upgrade();  // Connections will break!
solrClient.add("col", docs2);  // This will fail!
```

#### ✅ Correct Pattern

```java
// CORRECT - Close, upgrade, reopen
solrClient.add("col", docs1);
solrClient.close();

cluster.upgrade();

solrClient = cluster.getSolrClient();
solrClient.add("col", docs2);
```

#### ❌ Mistake 2: Not verifying node identity preservation

```java
// WRONG - Assumes identity preserved without verification
cluster.upgrade();
// Continue testing without checking if nodes maintained their URLs
```

#### ✅ Correct Pattern

```java
// CORRECT - Verify identity preservation
Map<Integer, String> preUpgradeUrls = new HashMap<>();
for (int i = 0; i < cluster.getNumJettys(); i++) {
    preUpgradeUrls.put(i, cluster.getJettyBaseUrl(i));
}

cluster.upgrade();

// Verify all nodes kept same URLs
for (int i = 0; i < cluster.getNumJettys(); i++) {
    String postUrl = cluster.getJettyBaseUrl(i);
    assert postUrl.equals(preUpgradeUrls.get(i)) :
        "Jetty " + i + " URL changed!";
}
```

---

## Upgrade Checkpoint Test Methods

### Overview

**Recommended Approach**: Instead of hardcoding a single upgrade point in each test, generate multiple test methods with checkpoint suffixes. Each test method tests the same logic but with upgrade at a different checkpoint. This provides comprehensive upgrade coverage.

**Key Benefits**:
- Single test logic → multiple test methods with different checkpoints
- 100% reproducible (deterministic checkpoint execution)
- Comprehensive coverage (standard + test-specific checkpoints)
- Guaranteed cleanup between executions
- Easy Gradle execution: can run specific checkpoint with `--tests Test#method_CHECKPOINT`

### Base Class: ProcessBasedUpgradeTestBase

All ProcessBased tests should extend `ProcessBasedUpgradeTestBase`, which provides:

1. **@Before cleanup**: Kills orphaned processes, cleans old directories
2. **@After cleanup**: Closes client, shuts down cluster, verifies cleanup
3. **checkpoint(name)**: Performs upgrade if name matches parameter
4. **shouldUpgrade(name)**: Checks if upgrade should happen

**Location**: `org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase`

### Transformation Steps

#### Step 1: Identify Checkpoints for Each Test Method

For each original test method, identify:
1. **Standard checkpoints** (always include):
   - `NO_UPGRADE` - Baseline test without upgrade
   - `AFTER_CLUSTER_START` - Upgrade immediately after cluster starts

2. **Test-specific checkpoints** (from actual checkpoint() calls):
   - Look for all `checkpoint("NAME")` calls in the test method
   - Each unique checkpoint name becomes a test method variant

**Example:**
```java
// Original test method
@Test
public void testIndexing() {
  cluster = ...;
  checkpoint("AFTER_CLUSTER_START");

  createCollection();
  checkpoint("AFTER_COLLECTION_CREATE");

  indexDocs();
  checkpoint("AFTER_INDEX");

  queryDocs();
}
```

**Identified checkpoints:**
- `NO_UPGRADE` (standard)
- `AFTER_CLUSTER_START` (standard + in test)
- `AFTER_COLLECTION_CREATE` (test-specific)
- `AFTER_INDEX` (test-specific)

#### Step 2: Generate Test Methods

Create one test method per checkpoint with naming pattern `testMethodName_CHECKPOINT_NAME()`:

```java
// Add imports
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;

// Extend base class
public class TestIndexing_ProcessBased extends ProcessBasedUpgradeTestBase {

  @Test
  public void testIndexing_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;

    // Full test logic
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .build();
    cluster.start();
    checkpoint("AFTER_CLUSTER_START");
    createCollection();
    checkpoint("AFTER_COLLECTION_CREATE");
    indexDocs();
    checkpoint("AFTER_INDEX");
    queryDocs();
  }

  @Test
  public void testIndexing_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;

    // Same full test logic - upgrade happens at AFTER_CLUSTER_START
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    checkpoint("AFTER_CLUSTER_START");
    createCollection();
    checkpoint("AFTER_COLLECTION_CREATE");
    indexDocs();
    checkpoint("AFTER_INDEX");
    queryDocs();
  }

  @Test
  public void testIndexing_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";

    // Same full test logic - upgrade happens at AFTER_COLLECTION_CREATE
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    checkpoint("AFTER_CLUSTER_START");
    createCollection();
    checkpoint("AFTER_COLLECTION_CREATE");
    indexDocs();
    checkpoint("AFTER_INDEX");
    queryDocs();
  }

  @Test
  public void testIndexing_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = "AFTER_INDEX";

    // Same full test logic - upgrade happens at AFTER_INDEX
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    checkpoint("AFTER_CLUSTER_START");
    createCollection();
    checkpoint("AFTER_COLLECTION_CREATE");
    indexDocs();
    checkpoint("AFTER_INDEX");
    queryDocs();
  }
}
```

#### Step 3: Code Duplication Note

Note that each test method contains **full duplication** of the test logic. This is intentional:
- Makes each test method independently runnable
- Clear what each checkpoint variant does
- Compatible with Gradle single-method execution
- No shared state between methods (base class handles cleanup)

**BEFORE** (manual cleanup):
```java
@Test
public void testSomething() throws Exception {
  ProcessBasedMiniSolrCloudCluster cluster = new Builder().build();
  CloudSolrClient client = cluster.getSolrClient();

  try {
    // test logic
  } finally {
    client.close();
    cluster.shutdown();
  }
}
```

**AFTER** (automatic cleanup via base class):
```java
@Test
public void testSomething_NO_UPGRADE() throws Exception {
  upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;

  // Use cluster, solrClient from base class
  cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
      .withNodeCount(1)
      .withStartVersionFromSystemProperty()
      .build();
  cluster.start();
  solrClient = cluster.getSolrClient();

  // test logic with checkpoints
  CollectionAdminRequest.createCollection("test", "conf", 1, 3)
      .process(solrClient);
  checkpoint("AFTER_COLLECTION_CREATE");

  // No try-finally needed - @After handles cleanup!
}

@Test
public void testSomething_AFTER_CLUSTER_START() throws Exception {
  upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;

  // Same test logic
  cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
      .withNodeCount(1)
      .withStartVersionFromSystemProperty()
      .withUpgradeVersionFromSystemProperty()
      .build();
  cluster.start();
  solrClient = cluster.getSolrClient();
  CollectionAdminRequest.createCollection("test", "conf", 1, 3)
      .process(solrClient);
  checkpoint("AFTER_COLLECTION_CREATE");
}
```

### Checkpoint Selection Guidelines

For each original test method, generate test method variants for:

1. **Standard checkpoints** (always include):
   - `NO_UPGRADE` - Baseline test without upgrade
   - `AFTER_CLUSTER_START` - Upgrade immediately after cluster starts

2. **Test-specific checkpoints** (from actual checkpoint() calls):
   - Scan the test method for all `checkpoint("NAME")` calls
   - Generate a test method variant for each unique checkpoint name

3. **Naming conventions**:
   - Use SolrUpgradeCheckpoints constants for standard checkpoints
   - Use descriptive strings for test-specific checkpoints
   - Be descriptive: "AFTER_COMMIT" not "CHECKPOINT_3"

**Common checkpoint categories for Solr**:
- Cluster lifecycle: `AFTER_CLUSTER_START`
- Collection operations: `AFTER_COLLECTION_CREATE`, `AFTER_SHARD_SPLIT`
- Indexing: `AFTER_INDEX`, `AFTER_COMMIT`
- Querying: `AFTER_QUERY`, `BEFORE_VERIFICATION`

### Running Checkpoint Test Methods

**Run all test methods (all checkpoints for all tests)**:
```bash
gradle test --tests TestIndexing_ProcessBased \
  -Dsolr.start.home=/opt/solr-9.8.0 \
  -Dsolr.upgrade.home=/opt/solr-9.9.0
```

**Run specific checkpoint for specific test**:
```bash
gradle test --tests TestIndexing_ProcessBased.testIndexing_AFTER_INDEX \
  -Dsolr.start.home=/opt/solr-9.8.0 \
  -Dsolr.upgrade.home=/opt/solr-9.9.0
```

**Run all checkpoints for one test method** (using wildcard):
```bash
gradle test --tests 'TestIndexing_ProcessBased.testIndexing_*' \
  -Dsolr.start.home=/opt/solr-9.8.0 \
  -Dsolr.upgrade.home=/opt/solr-9.9.0
```

**Run all baseline (NO_UPGRADE) tests**:
```bash
gradle test --tests '*_ProcessBased.*_NO_UPGRADE' \
  -Dsolr.start.home=/opt/solr-9.8.0
```

---

## When to Comment Out Logic

### Only comment out operations that are:

1. **Internal Storage Operations**
   - CoreContainer internal state
   - SolrCore internal structures
   - Storage directory verification (low-level file checks)

2. **In-Process Manipulation**
   - Direct object field modification
   - Mock object injection
   - Reflection on private fields

3. **JVM-Level Operations**
   - Memory manipulation
   - Thread state inspection (beyond public APIs)
   - ClassLoader manipulation

### Comment Template

Use this template when commenting out unsupported logic:

```java
// TRANSFORMATION NOTE: [Brief explanation of what was removed]
// [Why it was removed - what makes it inaccessible via client APIs]
// [What the original code verified/tested]
// [Suggestion for alternative verification if applicable, or "No client-side alternative available"]
//
// Original code:
// [indented commented-out code]
```

### Example: Internal CoreContainer Check

```java
// TRANSFORMATION NOTE: Internal CoreContainer verification removed.
// The CoreContainer class is internal to the JettySolrRunner process and not
// accessible via any client API. The original test verified that cores were
// properly loaded after node restart.
// No client-side alternative available - this verification requires direct access
// to the JettySolrRunner's CoreContainer.
//
// Original code:
// CoreContainer cc = cluster.getJettySolrRunner(0).getCoreContainer();
// assertEquals(3, cc.getCores().size());
```

### When NOT to Comment Out

Do NOT comment out if there's a client-side equivalent:

❌ **WRONG**:
```java
// TRANSFORMATION NOTE: Cannot access JettySolrRunner directly
// Original code:
// boolean running = cluster.getJettySolrRunner(0).isRunning();
```

✅ **CORRECT**:
```java
// Use ZkStateReader instead of direct node access
ZkStateReader zkStateReader = cluster.getZkStateReader();
Set<String> liveNodes = zkStateReader.getClusterState().getLiveNodes();
boolean running = !liveNodes.isEmpty();
```

---

## Testing Checklist

### Before Running Test

- [ ] **File organization correct**
  - Transformed test in same directory as original
  - File name has `_ProcessBased` suffix
  - Package declaration identical to original
  - Class name matches file name with `_ProcessBased` suffix
  - Javadoc includes `@see` reference to original test
  - Extends ProcessBasedUpgradeTestBase

- [ ] **System properties ready**
  ```bash
  gradle test --tests MyTest \
    -Dsolr.start.home=/path/to/solr-9.8.0 \
    -Dsolr.upgrade.home=/path/to/solr-9.9.0
  ```

- [ ] **Test compiles without errors**
  ```bash
  gradle compileTestJava
  ```

- [ ] **Imports are correct**
  - ProcessBasedMiniSolrCloudCluster imported
  - ProcessBasedUpgradeTestBase imported
  - Unnecessary JettySolrRunner imports removed
  - Client API imports added

### During Test Execution

- [ ] **Cluster starts successfully**
  - Check logs for "Cluster started successfully"
  - Verify all nodes are up in ZooKeeper

- [ ] **Client accessible**
  - Can get client instance
  - Can perform basic operations (create collection)

- [ ] **Core assertions pass**
  - Main test logic validates correctly
  - Data integrity checks pass

### After Test Execution

- [ ] **Test passes (or fails as expected)**
  - If original test passed, transformed test should pass
  - If failure, verify it's not due to transformation

- [ ] **Cluster cleans up properly**
  - No orphaned Jetty processes (`jps | grep Jetty`)
  - Test directories cleaned up

- [ ] **Review transformation quality**
  - Maximum logic preserved?
  - Only necessary operations commented out?
  - Appropriate documentation added?

---

## Best Practices

### DO ✅

1. **Consult mapping tables first** - Before assuming something is unsupported, check all mapping tables

2. **Use the API hierarchy** - Try CloudSolrClient → Collections API → ZkStateReader → Metrics in order

3. **Preserve test intent** - Even if implementation changes, maintain what the test is verifying

4. **System properties are automatic** - No manual environment checks needed!

5. **Keep transformations minimal** - Change only what's necessary

6. **Document significant changes** - But only non-obvious ones

7. **Test both single-version and multi-version scenarios** when applicable

8. **Verify node identity preservation** - Always check URLs unchanged after restart/upgrade

9. **Capture identity snapshot before upgrades** - Store node URLs to verify preservation

10. **Extend ProcessBasedUpgradeTestBase** - Automatic cleanup and checkpoint support

### DON'T ❌

1. **Don't give up on transformation too early** - Most operations have client equivalents

2. **Don't remove test logic without checking mapping tables**

3. **Don't use MiniSolrCloudCluster-specific test utilities without checking alternatives**

4. **Don't over-document** - Only comment what's not obvious

5. **Don't mix MiniSolrCloudCluster and ProcessBasedMiniSolrCloudCluster** in same test

6. **Don't manually check environment variables** - System properties are handled automatically!

7. **Don't forget to close resources before checkpoints** - Connections break during node restarts

### Performance Considerations

1. **Process startup is slower** - ProcessBasedMiniSolrCloudCluster takes longer to start
   - Be patient with cluster startup (30-60 seconds)
   - Consider increasing timeouts for slow systems

2. **Operations are real-time** - Can't artificially trigger background operations
   - Use `Thread.sleep()` or `waitFor()` utilities
   - Account for natural operation timing

3. **HTTP overhead** - All operations go through HTTP/SolrJ
   - Slightly slower than in-process calls
   - Not significant for most tests

---

## Quick Reference Decision Tree

```
Found server-side operation?
    │
    ├─> Is it already client-side? (CloudSolrClient, Collections API)
    │   └─> ✅ Use as-is, no transformation needed
    │
    ├─> Check JettySolrRunner table (Table 2)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use client API
    │   └─> Not found?
    │       └─> Continue...
    │
    ├─> Check Collections/ConfigSet API tables (Tables 3 & 4)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use Collections/ConfigSet API
    │   └─> Not found?
    │       └─> Continue...
    │
    ├─> Check Cluster State table (Table 5)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use ZkStateReader or Metrics API
    │   └─> Not found?
    │       └─> Continue...
    │
    └─> No client-side equivalent exists
        └─> ❌ Comment out with documentation template
```

---

## Summary

### Transformation Success Criteria

A successful transformation:
1. ✅ Compiles without errors
2. ✅ Runs with ProcessBasedMiniSolrCloudCluster
3. ✅ Preserves maximum test logic
4. ✅ Uses client APIs for all accessible operations
5. ✅ Comments out only truly inaccessible operations
6. ✅ Includes minimal, clear documentation
7. ✅ Passes when original test passed
8. ✅ Extends ProcessBasedUpgradeTestBase for automatic cleanup

### Key Takeaways

- **Most operations have client equivalents** - Consult mapping tables thoroughly
- **Use the API hierarchy** - CloudSolrClient → Collections API → ZkStateReader → Metrics
- **Only comment out internal storage/JVM operations** - Everything else has an API
- **Document sparingly** - Only non-obvious transformations
- **Test thoroughly** - Verify core test logic preserved
- **Extend ProcessBasedUpgradeTestBase** - Automatic cleanup and upgrade support
- **Use checkpoint test methods** - Comprehensive upgrade coverage with named test methods

---

**End of Guide**

This comprehensive guide provides all the information needed to transform Apache Solr tests from MiniSolrCloudCluster to ProcessBasedMiniSolrCloudCluster, enabling process-based testing and multi-version upgrade scenarios.
