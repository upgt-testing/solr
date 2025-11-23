# Step 2: Generate Upgrade Base Test Class for Apache Solr

## Purpose

This document provides a complete AI agent prompt for generating a base test class for checkpoint-based upgrade testing of Apache Solr. The generated class provides automatic lifecycle management, checkpoint-based upgrade testing, and complete test isolation.

---

## AI Agent Prompt

**Copy the section below and provide to an AI code generation agent:**

```
Generate a JUnit base test class for checkpoint-based upgrade testing with the following requirements:

### SYSTEM INFORMATION

**Cluster Type**: distributed search and indexing cluster
Description: Apache Solr distributed search platform with SolrCloud for distributed indexing and querying

**Cluster Class**: org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster
Full package name: org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster

**Client/Connection Class**: org.apache.solr.client.solrj.impl.CloudSolrClient
Full package name: org.apache.solr.client.solrj.impl.CloudSolrClient

**Configuration Class**: N/A (Solr uses JettyConfig + Properties)
Note: Solr doesn't have a single Configuration object like Hadoop. Configuration is passed via Properties and JettyConfig.

**Package Name**: org.apache.solr.cloud.upgrade

**File Location**: solr/test-framework/src/test/java/org/apache/solr/cloud/upgrade/

### CLUSTER LIFECYCLE

**Cluster Initialization Pattern**:
```java
import org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster;

cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
    .withNodeCount(3)
    .withStartVersionFromSystemProperty()
    .build();
cluster.start();
cluster.waitForAllNodes(30);
```

**Client Initialization Pattern**:
```java
import org.apache.solr.client.solrj.impl.CloudSolrClient;

CloudSolrClient solrClient = cluster.getSolrClient();
```

**Cluster Shutdown Pattern**:
```java
cluster.shutdown();  // Shuts down all Jetty nodes
```

**Client Shutdown Pattern**:
```java
if (solrClient != null) {
    solrClient.close();
}
```

### UPGRADE MECHANISM

**Upgrade Method**: Rolling upgrade of real Solr processes

Description: ProcessBasedMiniSolrCloudCluster supports rolling upgrades by restarting each Solr node process (bin/solr) with a different version. The upgrade() method performs a complete rolling upgrade of all nodes.

**Upgrade Invocation**:
```java
// Automatic rolling upgrade of all nodes to upgrade version
cluster.upgrade();
```

**Node Identity Preservation Requirement**:
CRITICAL: Nodes MUST preserve their identity during upgrade:
- Same Solr HTTP port
- Same node name (registered in ZooKeeper)
- Same work directory
- Same configuration

Example verification:
```java
import java.util.Map;
import java.util.HashMap;

// Before upgrade - capture node identities
Map<Integer, String> preUpgradeUrls = new HashMap<>();
for (int i = 0; i < cluster.getNodeCount(); i++) {
    preUpgradeUrls.put(i, cluster.getJettySolrRunnerBaseUrl(i));
}

// Perform upgrade
cluster.upgrade();

// After upgrade - verify identity preserved
for (int i = 0; i < cluster.getNodeCount(); i++) {
    String postUrl = cluster.getJettySolrRunnerBaseUrl(i);
    String preUrl = preUpgradeUrls.get(i);
    if (!postUrl.equals(preUrl)) {
        throw new AssertionError(
            "Node " + i + " URL changed during upgrade: " +
            preUrl + " -> " + postUrl + ". " +
            "Node identity was not preserved!");
    }
}
```

**Pre-upgrade Health Check**:
```java
import org.apache.solr.common.cloud.ZkStateReader;

// Verify all nodes are live in ZooKeeper before upgrade
ZkStateReader zkStateReader = cluster.getZkStateReader();
if (zkStateReader.getClusterState().getLiveNodes().size() < cluster.getNodeCount()) {
    throw new IllegalStateException("Not all nodes are live before upgrade");
}
```

**Post-upgrade Health Check**:
```java
// Wait for all nodes to be live after upgrade
cluster.waitForAllNodes(30);

// CRITICAL: Verify node identities preserved
verifyNodeIdentitiesPreserved();
```

**Node Identity Verification Pattern**:
```java
// Store pre-upgrade node identities (in setupTest or before upgrade)
Map<Integer, String> preUpgradeUrls = new HashMap<>();
for (int i = 0; i < cluster.getNodeCount(); i++) {
    preUpgradeUrls.put(i, cluster.getJettySolrRunnerBaseUrl(i));
}

// After upgrade, verify identities match
for (int i = 0; i < cluster.getNodeCount(); i++) {
    String postUrl = cluster.getJettySolrRunnerBaseUrl(i);
    String preUrl = preUpgradeUrls.get(i);
    if (!postUrl.equals(preUrl)) {
        throw new AssertionError(
            "Node " + i + " URL changed during upgrade: " +
            preUrl + " -> " + postUrl + ". " +
            "This indicates node identity was not preserved!");
    }
}
```

### PROCESS/RESOURCE CLEANUP

**Process Pattern to Kill**: JettySolrRunner|start\.jar
Regular expression to match Solr process names in jps output

**Process Cleanup Command**:
```bash
# Kill orphaned Solr processes
jps | grep -E 'JettySolrRunner|start\.jar' | awk '{print $1}' | xargs -r kill -9
```

**Temporary Directory Pattern**: process-minisolr-*
Pattern to match cluster temporary directories

**Directory Cleanup Logic**:
```java
import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

File tmpDir = new File(System.getProperty("java.io.tmpdir"));
File[] oldDirs = tmpDir.listFiles((dir, name) ->
    name.startsWith("process-minisolr-") &&
    name.matches(".*\\d{13}$"));  // Match timestamp suffix

if (oldDirs != null) {
    long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
    for (File dir : oldDirs) {
        try {
            FileTime creationTime = Files.readAttributes(
                dir.toPath(),
                java.nio.file.attribute.BasicFileAttributes.class
            ).creationTime();
            if (creationTime.toMillis() < cutoffTime) {
                deleteDirectory(dir);
            }
        } catch (IOException e) {
            // Log but continue with other directories
        }
    }
}
```

### CHECKPOINT CONFIGURATION

**Checkpoint Constants Class**: SolrUpgradeCheckpoints
Class name for checkpoint constants

**Common Checkpoint Names**: NO_UPGRADE, AFTER_CLUSTER_START, AFTER_COLLECTION_CREATE, AFTER_INDEX, AFTER_COMMIT, AFTER_QUERY
List of common checkpoint names (comma-separated)

**Checkpoint No-Upgrade Constant**: NO_UPGRADE
Constant name for baseline test (no upgrade)

### ADDITIONAL REQUIREMENTS

**Additional Managed Resources**:
- ZkStateReader (org.apache.solr.common.cloud.ZkStateReader) - ZooKeeper state reader
- SolrZkClient (org.apache.solr.common.cloud.SolrZkClient) - ZooKeeper client
- Multiple CloudSolrClient instances (per-collection clients)

**Additional Cleanup Steps**:
1. Close all collection-specific CloudSolrClient instances
2. Close main CloudSolrClient
3. Close ZkStateReader (if directly managed)
4. Shutdown cluster (handles internal ZK client cleanup)
5. Verify no orphaned Jetty processes

**Special Considerations**:
- Solr relies heavily on ZooKeeper - ensure ZK cleanup
- Collections may have ongoing background operations - wait for quiescence before shutdown
- ConfigSets in ZK may persist across cluster restarts - clean if needed for test isolation
- Port allocation must be persistent across upgrades (handled by cluster)
- Jetty 9 (Solr 8.x) vs Jetty 10 (Solr 9.x) have different startup characteristics

### PLATFORM COMPATIBILITY

**Operating Systems**: Linux, macOS
Target operating systems

**Process Management Approach**:
Use jps (Java Process Status) and kill -9 on Unix platforms. Process management is Unix-centric.

For cross-platform support in the future, could use ProcessHandle API (Java 9+) but initial implementation focuses on Linux/macOS.

### GENERATED CLASS STRUCTURE

Please generate a base test class with the following structure:

1. **Class Header**:
   - Apache License header
   - Package declaration: org.apache.solr.cloud.upgrade
   - Comprehensive JavaDoc explaining:
     - Purpose of the base class
     - Usage pattern with named checkpoint test methods
     - Example test implementation showing checkpoint usage
     - Test isolation guarantees
     - Cleanup guarantees
     - Note about closing resources before checkpoint() calls

2. **Protected Fields**:
   - upgradeCheckpoint (String) - Set directly by test methods
   - cluster (ProcessBasedMiniSolrCloudCluster) - Cluster instance
   - solrClient (CloudSolrClient) - Main client instance
   - collectionClients (Map<String, CloudSolrClient>) - Per-collection clients
   - preUpgradeUrls (Map<Integer, String>) - Node URLs before upgrade for verification

3. **@Before setupTest() Method**:
   - Log setup start with checkpoint name (if upgradeCheckpoint is set)
   - Clean up orphaned Jetty processes from previous failed runs
   - Clean up old cluster directories (older than 1 hour)
   - Initialize fresh collectionClients map
   - Set cluster = null, solrClient = null (defensive)
   - Initialize preUpgradeUrls map
   - Log setup completion
   - NOTE: upgradeCheckpoint field is set directly by each test method, no reflection needed

4. **@After tearDownTest() Method**:
   - Log teardown start with checkpoint name
   - Close all collection clients (independent try-catch for each)
   - Close main solrClient (with try-catch, null check, finally block)
   - Shutdown cluster with cleanup (with try-catch, null check, finally block)
   - Wait for processes to terminate (Thread.sleep with reasonable timeout)
   - Verify cleanup success (no orphaned Jetty processes)
   - Force cleanup if verification fails
   - Log teardown completion
   - **CRITICAL**: Use independent try-catch blocks for each cleanup step to ensure all cleanup runs even if one step fails

5. **checkpoint(String name) Method**:
   - Check if upgrade should happen at this checkpoint (call shouldUpgrade(name))
   - If no upgrade needed, return immediately
   - Log checkpoint name
   - **CRITICAL: Capture pre-upgrade node URLs** for identity verification
   - Verify cluster health before upgrade (check ZK live nodes)
   - Perform upgrade using cluster.upgrade()
   - Verify cluster health after upgrade (waitForAllNodes)
   - **Verify node identities preserved**: Check that all nodes maintain same URLs
   - Log any identity violations as ERRORS
   - Log upgrade completion
   - **JavaDoc should warn**: "IMPORTANT: Close all streams, clients, and resources before calling checkpoint() to avoid broken connections during node restarts"

6. **shouldUpgrade(String name) Method**:
   - Return false if upgradeCheckpoint is null
   - Return false if upgradeCheckpoint equals "NO_UPGRADE"
   - Return true if upgradeCheckpoint.equals(name)
   - Return false otherwise

7. **Private Helper Methods**:
   - cleanupOrphanedProcesses(): Execute process cleanup command, handle platform differences
   - cleanupOldClusterDirectories(): Find and delete old directories (>1 hour old)
   - deleteDirectory(File): Recursive directory deletion utility
   - verifyCleanup(): Execute jps and verify no Jetty processes remain
   - verifyNodeIdentitiesPreserved(): Compare post-upgrade URLs with pre-upgrade snapshot
   - NOTE: No reflection-based parameter syncing needed - test methods set upgradeCheckpoint directly

8. **verifyNodeIdentitiesPreserved() Method**:
   - Compare preUpgradeUrls map with current node URLs
   - Throw AssertionError if any node URL changed
   - Log verification results (success or failure details)

9. **Best Practices**:
   - Use SLF4J Logger for all logging
   - Each cleanup step in @After must be in independent try-catch block
   - Set fields to null in finally blocks after cleanup
   - Log all major steps (setup, cleanup, upgrade, verification)
   - Defensive cleanup in @Before (kill orphaned processes from failed previous runs)
   - Comprehensive JavaDoc with usage examples
   - Automatic System property handling for start and upgrade Solr home paths
   - Platform-aware process cleanup (Unix-centric: jps + kill -9)
   - Node identity preservation verification (verify URLs unchanged)
   - Pre-upgrade identity snapshot (store node URLs before upgrade)
   - Post-upgrade identity validation (compare with snapshot)

### OUTPUT FORMAT

Generate:
1. Complete Java source file with Apache license header
2. Necessary import statements (minimize unused imports)
3. All methods with comprehensive JavaDoc comments
4. Inline comments for complex logic
5. Proper exception handling and logging
6. Example usage in class-level JavaDoc showing:
   - How to extend this base class
   - How to create named checkpoint test methods (testMethod_NO_UPGRADE, testMethod_AFTER_CLUSTER_START, etc.)
   - How to use checkpoint() in tests
   - How to set upgradeCheckpoint at the start of each test method

The generated class should be production-ready and follow Java best practices for Solr test code.
```

---

## Expected Generated Class Structure

The AI agent will generate a class similar to:

```java
/**
 * Base test class for checkpoint-based upgrade testing of ProcessBasedMiniSolrCloudCluster.
 *
 * <p>This class provides automatic lifecycle management, checkpoint-based upgrade testing,
 * and complete test isolation for Apache Solr upgrade scenarios.
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * public class TestMyFeature extends ProcessBasedUpgradeTestBase {
 *
 *   @Test
 *   public void testFeature_NO_UPGRADE() throws Exception {
 *     upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
 *
 *     cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
 *         .withNodeCount(3)
 *         .withStartVersionFromSystemProperty()
 *         .build();
 *     cluster.start();
 *     solrClient = cluster.getSolrClient();
 *     checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);
 *
 *     // Create collection
 *     CollectionAdminRequest.createCollection("test", "conf", 1, 3)
 *         .process(solrClient);
 *     checkpoint("AFTER_COLLECTION_CREATE");
 *
 *     // Test logic...
 *     // No try-finally needed - @After handles cleanup!
 *   }
 *
 *   @Test
 *   public void testFeature_AFTER_CLUSTER_START() throws Exception {
 *     upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
 *
 *     // Same full test logic as above - upgrade happens at AFTER_CLUSTER_START
 *     cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
 *         .withNodeCount(3)
 *         .withStartVersionFromSystemProperty()
 *         .build();
 *     cluster.start();
 *     solrClient = cluster.getSolrClient();
 *     checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);
 *     // ... rest of test
 *   }
 *
 *   @Test
 *   public void testFeature_AFTER_COLLECTION_CREATE() throws Exception {
 *     upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
 *
 *     // Same full test logic - upgrade happens at AFTER_COLLECTION_CREATE
 *     // ...
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Test Execution:</strong></p>
 * <pre>
 * # Run specific checkpoint
 * gradle test --tests TestMyFeature.testFeature_AFTER_CLUSTER_START \
 *   -Dsolr.start.home=/opt/solr-9.8.0 \
 *   -Dsolr.upgrade.home=/opt/solr-9.9.0
 *
 * # Run all checkpoints for one test method
 * gradle test --tests 'TestMyFeature.testFeature_*'
 *
 * # Run all baseline (NO_UPGRADE) tests
 * gradle test --tests '*_NO_UPGRADE'
 * </pre>
 *
 * <h3>Guarantees:</h3>
 * <ul>
 *   <li>Complete isolation between checkpoint executions</li>
 *   <li>Automatic cleanup of processes and directories</li>
 *   <li>Verification of cleanup success</li>
 *   <li>Force cleanup if verification fails</li>
 *   <li>Node identity preservation verification during upgrades</li>
 * </ul>
 *
 * <h3>System Properties:</h3>
 * <ul>
 *   <li><code>solr.start.home</code> - Path to initial Solr installation</li>
 *   <li><code>solr.upgrade.home</code> - Path to upgraded Solr installation</li>
 * </ul>
 */
public abstract class ProcessBasedUpgradeTestBase {
    // ... full implementation
}
```

---

## Validation Checklist

After generation, verify the base class has:

- [ ] Complete JavaDoc with usage example showing named checkpoint test methods
- [ ] @Before method that performs cleanup and initialization
- [ ] @After method with independent try-catch blocks for each cleanup step
- [ ] checkpoint(String) method with health checks before and after upgrade
- [ ] shouldUpgrade(String) method with proper logic
- [ ] Process cleanup logic for JettySolrRunner (jps | grep pattern)
- [ ] Directory cleanup with age-based filtering (>1 hour old)
- [ ] Cleanup verification method that checks for orphaned Jetty processes
- [ ] Node identity preservation verification (URL comparison)
- [ ] Pre-upgrade snapshot of node URLs
- [ ] Post-upgrade validation against snapshot
- [ ] Proper null checks before all cleanup operations
- [ ] Fields set to null in finally blocks after cleanup
- [ ] Logger with appropriate log levels (INFO for major steps, DEBUG for details)
- [ ] No resource leaks in helper methods
- [ ] Platform-appropriate process management (jps + kill -9 for Unix)
- [ ] Comprehensive exception handling (independent try-catch per cleanup step)
- [ ] System property reading for solr.start.home and solr.upgrade.home

---

## Notes

- This template is designed for JUnit 4 with standard @Test methods (no parameterization)
- For JUnit 5, adapt @Before/@After to @BeforeEach/@AfterEach
- Test methods set `upgradeCheckpoint` field directly at the start of each test
- Each test method should contain full test logic (not shared/helper methods) for clarity
- Consider adding timeout annotations for long-running tests (@Test(timeout = 300000))
- Test method naming: `testMethodName_CHECKPOINT_NAME()` format
- Each test method is fully independent and isolated

---

## Example Checkpoint Constants Class

```java
package org.apache.solr.cloud.upgrade;

/**
 * Constants for common upgrade checkpoint names.
 */
public class SolrUpgradeCheckpoints {
    /** Baseline test - no upgrade performed */
    public static final String NO_UPGRADE = "NO_UPGRADE";

    /** Upgrade immediately after cluster starts */
    public static final String AFTER_CLUSTER_START = "AFTER_CLUSTER_START";

    /** Upgrade after creating collection */
    public static final String AFTER_COLLECTION_CREATE = "AFTER_COLLECTION_CREATE";

    /** Upgrade after indexing documents */
    public static final String AFTER_INDEX = "AFTER_INDEX";

    /** Upgrade after commit */
    public static final String AFTER_COMMIT = "AFTER_COMMIT";

    /** Upgrade after query */
    public static final String AFTER_QUERY = "AFTER_QUERY";

    private SolrUpgradeCheckpoints() {
        // Utility class
    }
}
```

---

**End of Template**

Use this template to generate a comprehensive base test class for Apache Solr checkpoint-based upgrade testing framework.
