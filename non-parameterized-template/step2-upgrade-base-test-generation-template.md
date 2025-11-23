# Step 2: Generate Upgrade Base Test Class Template

## Purpose

This template helps you generate a base test class for checkpoint-based upgrade testing of your distributed cluster system. The generated class provides automatic lifecycle management, checkpoint-based upgrade testing, and complete test isolation.

## How to Use This Template

1. Fill in all placeholders marked with `{PLACEHOLDER_NAME}`
2. Replace example values with your specific cluster details
3. Provide the completed template content to an AI programming agent (or use as your implementation guide)
4. Review and adjust the generated code as needed

---

## AI Agent Prompt Template

**Copy the section below and fill in the placeholders, then provide to an AI agent:**

```
Generate a JUnit base test class for checkpoint-based upgrade testing with the following requirements:

### SYSTEM INFORMATION

**Cluster Type**: {CLUSTER_TYPE_DESCRIPTION}
Description: Brief description of what this cluster does
Example: "distributed key-value store", "distributed coordination service", "distributed file system"

**Cluster Class**: {PROCESS_BASED_CLUSTER_CLASS_FQN}
Full package name: {FULL_PACKAGE_NAME}.{PROCESS_BASED_CLUSTER_CLASS}
Example: "org.apache.hbase.test.ProcessBasedMiniHBaseCluster"

**Client/Connection Class**: {CLIENT_CLASS_FQN}
Full package name: {FULL_PACKAGE_NAME}.{CLIENT_CLASS}
Example: "org.apache.hbase.client.Connection"

**Configuration Class**: {CONFIG_CLASS_FQN}
Full package name: {CONFIG_PACKAGE}.{CONFIG_CLASS}
Example: "org.apache.hadoop.conf.Configuration"

**Package Name**: {PACKAGE_NAME}
Package for upgrade tests
Example: "org.apache.hbase.test.upgrade"

**File Location**: {FILE_PATH}
Relative path from project root
Example: "hbase-server/src/test/java/org/apache/hbase/test/upgrade/"

### CLUSTER LIFECYCLE

**Cluster Initialization Pattern**:
{CLUSTER_INIT_CODE}
Example:
```java
Configuration conf = new {CONFIG_CLASS}();
cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
    .num{NODE_TYPE_2_NAME}s(3)
    .format(true)
    .build();
cluster.waitClusterUp();
```

**Client Initialization Pattern**:
{CLIENT_INIT_CODE}
Example:
```java
{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
```

**Cluster Shutdown Pattern**:
{CLUSTER_SHUTDOWN_CODE}
Example:
```java
cluster.shutdown();  // Shuts down all nodes
```

**Client Shutdown Pattern**:
{CLIENT_SHUTDOWN_CODE}
Example:
```java
{CLIENT_VAR}.close();
```

### UPGRADE MECHANISM

**Upgrade Method**: {UPGRADE_METHOD}
Description of how upgrades are performed
Example: "Rolling upgrade", "Blue-green deployment", "In-place upgrade"

**Upgrade Invocation**:
{UPGRADE_INVOCATION_CODE}
Example:
```java
cluster.upgrade();  // Performs rolling upgrade of all nodes
```

**Node Identity Preservation Requirement**:
{NODE_IDENTITY_PRESERVATION_REQUIREMENT}
Critical requirement: Nodes MUST preserve their identity during upgrade:
- Same ports (RPC, HTTP, data transfer)
- Same network addresses
- Same node IDs
- Same configuration directory

Example verification:
```java
// Before upgrade
InetSocketAddress addr1 = cluster.get{NODE_TYPE_2_NAME}Address(0);
String nodeId1 = cluster.get{NODE_TYPE_2_NAME}NodeId(0);

// Perform upgrade
cluster.upgrade();

// After upgrade - verify identity preserved
InetSocketAddress addr2 = cluster.get{NODE_TYPE_2_NAME}Address(0);
String nodeId2 = cluster.get{NODE_TYPE_2_NAME}NodeId(0);

assert addr1.equals(addr2) : "Node address changed during upgrade!";
assert nodeId1.equals(nodeId2) : "Node ID changed during upgrade!";
```

**Pre-upgrade Health Check**:
{PRE_UPGRADE_HEALTH_CHECK}
Example:
```java
if (!cluster.isClusterUp()) {
    throw new IllegalStateException("Cluster is not healthy before upgrade");
}
```

**Post-upgrade Health Check**:
{POST_UPGRADE_HEALTH_CHECK}
Example:
```java
cluster.waitClusterUp();  // Wait for cluster to stabilize after upgrade

// CRITICAL: Verify node identities preserved
verifyNodeIdentitiesPreserved();
```

**Node Identity Verification Pattern**:
{NODE_IDENTITY_VERIFICATION_PATTERN}
Example:
```java
// Store pre-upgrade node identities
Map<Integer, InetSocketAddress> preUpgradeAddresses = new HashMap<>();
for (int i = 0; i < cluster.getNum{NODE_TYPE_2_NAME}s(); i++) {
    preUpgradeAddresses.put(i, cluster.get{NODE_TYPE_2_NAME}Address(i));
}

// After upgrade, verify identities match
for (int i = 0; i < cluster.getNum{NODE_TYPE_2_NAME}s(); i++) {
    InetSocketAddress postAddr = cluster.get{NODE_TYPE_2_NAME}Address(i);
    InetSocketAddress preAddr = preUpgradeAddresses.get(i);
    if (!postAddr.equals(preAddr)) {
        throw new AssertionError(
            "Node " + i + " address changed during upgrade: " +
            preAddr + " -> " + postAddr + ". " +
            "This indicates node identity was not preserved!");
    }
}
```

### PROCESS/RESOURCE CLEANUP

**Process Pattern to Kill**: {PROCESS_PATTERN}
Regular expression to match process names
Example: "{NODE_TYPE_1_CLASS}|{NODE_TYPE_2_CLASS}", "HMaster|HRegionServer", "QuorumPeerMain"

**Process Cleanup Command**:
{PROCESS_CLEANUP_COMMAND}
Shell command to kill orphaned processes
Example:
```bash
jps | grep -E '{PROCESS_PATTERN}' | awk '{print $1}' | xargs -r kill -9
```

**Temporary Directory Pattern**: {TEMP_DIR_PATTERN}
Pattern to match cluster temporary directories
Example: "process-mini{CLUSTER_TYPE}-*", "process-minihbase-*", "process-minizk-*"

**Directory Cleanup Logic**:
{DIRECTORY_CLEANUP_LOGIC}
Java code to find and delete old cluster directories
Example:
```java
File tmpDir = new File(System.getProperty("java.io.tmpdir"));
File[] oldDirs = tmpDir.listFiles((dir, name) ->
    name.startsWith("process-mini{CLUSTER_TYPE}-") &&
    name.matches(".*\\d{13}$"));  // Match timestamp suffix
if (oldDirs != null) {
    for (File dir : oldDirs) {
        deleteDirectory(dir);
    }
}
```

### CHECKPOINT CONFIGURATION

**Checkpoint Constants Class**: {CHECKPOINT_CLASS_NAME}
Class name for checkpoint constants
Example: "UpgradeCheckpoints", "{PROJECT_NAME}UpgradeCheckpoints"

**Common Checkpoint Names**: {CHECKPOINT_NAMES}
List of common checkpoint names (comma-separated)
Example: "NO_UPGRADE, AFTER_CLUSTER_START, AFTER_CREATE, AFTER_WRITE, AFTER_READ, AFTER_FLUSH, AFTER_CLOSE"

**Checkpoint No-Upgrade Constant**: {NO_UPGRADE_CONSTANT}
Constant name for baseline test (no upgrade)
Example: "NO_UPGRADE", "BASELINE", "SKIP_UPGRADE"

### ADDITIONAL REQUIREMENTS

**Additional Managed Resources**:
{ADDITIONAL_RESOURCES}
List any additional resources that need cleanup beyond cluster and client
Example:
```
- Admin ({ADMIN_CLASS_FQN}) - Administrative client
- Table ({TABLE_CLASS_FQN}) - Open table handles
- Scanner ({SCANNER_CLASS_FQN}) - Open scanner handles
```

**Additional Cleanup Steps**:
{ADDITIONAL_CLEANUP_STEPS}
Additional cleanup operations in order
Example:
```
1. Close all open table handles
2. Close all open scanner handles
3. Flush pending mutations
4. Close admin client
5. Close main client connection
```

**Special Considerations**:
{SPECIAL_CONSIDERATIONS}
Any special considerations for your cluster
Example:
- "HBase requires ZooKeeper cleanup in addition to cluster cleanup"
- "Must wait for balancer to stabilize before shutdown"
- "May need to disable coprocessors during upgrade"

### PLATFORM COMPATIBILITY

**Operating Systems**: {TARGET_OS}
Target operating systems
Example: "Linux, macOS", "Linux only", "Cross-platform (Linux/Mac/Windows)"

**Process Management Approach**:
{PROCESS_MANAGEMENT_APPROACH}
How to handle process management across platforms
Example: "Use jps and kill -9 on Unix, TaskKill on Windows", "Use platform-independent process handles"

### GENERATED CLASS STRUCTURE

Please generate a base test class with the following structure:

1. **Class Header**:
   - Apache License header (if applicable)
   - Package declaration: {PACKAGE_NAME}
   - Comprehensive JavaDoc explaining:
     - Purpose of the base class
     - Usage pattern with named checkpoint test methods
     - Example test implementation
     - Test isolation guarantees
     - Cleanup guarantees

2. **Protected Fields**:
   - upgradeCheckpoint (String) - Set directly by test methods
   - cluster ({PROCESS_BASED_CLUSTER_CLASS}) - Cluster instance
   - {CLIENT_VAR} ({CLIENT_CLASS}) - Client instance
   - conf ({CONFIG_CLASS}) - Configuration instance
   - {Additional resources from ADDITIONAL_RESOURCES}

3. **@Before setupTest() Method**:
   - Log setup start with checkpoint name (if upgradeCheckpoint is set)
   - Clean up orphaned processes from previous failed runs
   - Clean up old cluster directories (older than 1 hour)
   - Initialize fresh configuration
   - Set cluster = null, {CLIENT_VAR} = null (defensive)
   - Log setup completion
   - NOTE: upgradeCheckpoint field is set directly by each test method, no reflection needed

4. **@After tearDownTest() Method**:
   - Log teardown start with checkpoint name
   - Close {CLIENT_VAR} (with try-catch, null check, finally block)
   - Shutdown cluster with cleanup (with try-catch, null check, finally block)
   - Handle additional resources from {ADDITIONAL_CLEANUP_STEPS}
   - Wait for processes to terminate (Thread.sleep with reasonable timeout)
   - Verify cleanup success (no orphaned processes)
   - Force cleanup if verification fails
   - Log teardown completion
   - **CRITICAL**: Use independent try-catch blocks for each cleanup step to ensure all cleanup runs even if one step fails

5. **checkpoint(String name) Method**:
   - Check if upgrade should happen at this checkpoint (call shouldUpgrade(name))
   - If no upgrade needed, return immediately
   - Log checkpoint name
   - Verify cluster health before upgrade using {PRE_UPGRADE_HEALTH_CHECK}
   - Perform upgrade using {UPGRADE_INVOCATION_CODE}
   - Verify cluster health after upgrade using {POST_UPGRADE_HEALTH_CHECK}
   - **Verify node identities preserved**: Check that all nodes maintain same addresses/ports
   - Log any identity violations as ERRORS
   - Log upgrade completion
   - **JavaDoc should warn**: "IMPORTANT: Close all streams and resources before calling checkpoint() to avoid broken pipelines during node restarts"

6. **shouldUpgrade(String name) Method**:
   - Return false if upgradeCheckpoint is null
   - Return false if upgradeCheckpoint equals {NO_UPGRADE_CONSTANT}
   - Return true if upgradeCheckpoint.equals(name)
   - Return false otherwise

7. **Private Helper Methods**:
   - cleanupOrphanedProcesses(): Execute process cleanup command {PROCESS_CLEANUP_COMMAND}, handle platform differences
   - cleanupOldClusterDirectories(): Use {DIRECTORY_CLEANUP_LOGIC} to find and delete old directories
   - deleteDirectory(File): Recursive directory deletion utility
   - verifyCleanup(): Execute jps and verify no processes match {PROCESS_PATTERN}
   - NOTE: No reflection-based parameter syncing needed - test methods set upgradeCheckpoint directly

8. **verifyNodeIdentitiesPreserved() Method**:
   - Store node addresses before upgrade (in setupTest or checkpoint)
   - After upgrade, verify all nodes have same addresses
   - Throw AssertionError if any node identity changed
   - Log verification results

9. **Best Practices**:
   - Use SLF4J Logger for all logging
   - Each cleanup step in @After must be in independent try-catch block
   - Set fields to null in finally blocks after cleanup
   - Log all major steps (setup, cleanup, upgrade, verification)
   - Defensive cleanup in @Before (kill orphaned processes from failed previous runs)
   - Comprehensive JavaDoc with usage examples
   - Automatic System property handling for start and end versions, directory paths and any other relevant configs
   - Platform-aware process cleanup (handle {TARGET_OS} requirements)
   - Node identity preservation verification (verify ports/addresses unchanged)
   - Pre-upgrade identity snapshot (store node addresses before upgrade)
   - Post-upgrade identity validation (compare with snapshot)

### OUTPUT FORMAT

Generate:
1. Complete Java source file with Apache license header (if applicable)
2. Necessary import statements (minimize unused imports)
3. All methods with comprehensive JavaDoc comments
4. Inline comments for complex logic
5. Proper exception handling and logging
6. Example usage in class-level JavaDoc showing:
   - How to extend this base class
   - How to create named checkpoint test methods
   - How to use checkpoint() in tests

The generated class should be production-ready and follow Java best practices.
```

---

## Example: Filled Template

Here's an example of this template filled out for HBase:

```markdown
**Cluster Type**: distributed column-oriented database
**Cluster Class**: org.apache.hadoop.hbase.test.ProcessBasedMiniHBaseCluster
**Client/Connection Class**: org.apache.hadoop.hbase.client.Connection
**Configuration Class**: org.apache.hadoop.conf.Configuration
**Package Name**: org.apache.hadoop.hbase.test.upgrade
**File Location**: hbase-server/src/test/java/org/apache/hadoop/hbase/test/upgrade/

**Cluster Initialization Pattern**:
```java
Configuration conf = new Configuration();
cluster = new ProcessBasedMiniHBaseCluster.Builder(conf)
    .numRegionServers(3)
    .build();
cluster.waitClusterUp();
```

**Client Initialization Pattern**:
```java
connection = ConnectionFactory.createConnection(cluster.getConfiguration());
```

**Cluster Shutdown Pattern**:
```java
cluster.shutdown();
```

**Client Shutdown Pattern**:
```java
connection.close();
```

**Upgrade Method**: Rolling upgrade
**Upgrade Invocation**:
```java
cluster.upgrade();
```

**Pre-upgrade Health Check**:
```java
if (!cluster.isClusterUp()) {
    throw new IllegalStateException("Cluster is not healthy");
}
```

**Post-upgrade Health Check**:
```java
cluster.waitClusterUp();
```

**Process Pattern to Kill**: HMaster|HRegionServer
**Process Cleanup Command**:
```bash
jps | grep -E 'HMaster|HRegionServer' | awk '{print $1}' | xargs -r kill -9
```

**Temporary Directory Pattern**: process-minihbase-*
**Checkpoint No-Upgrade Constant**: NO_UPGRADE
**Target OS**: Linux, macOS
```

---

## Generated Class Example

The AI agent will generate a class like this:

```java
/**
 * Base test class for checkpoint-based upgrade testing of {PROCESS_BASED_CLUSTER_CLASS}.
 *
 * <p>This class provides automatic lifecycle management, checkpoint-based upgrade testing,
 * and complete test isolation. Each test execution is fully isolated with guaranteed
 * cleanup between runs.
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * public class TestMyFeature extends ProcessBasedUpgradeTestBase {
 *
 *   @Test
 *   public void testFeature_NO_UPGRADE() throws Exception {
 *     upgradeCheckpoint = UpgradeCheckpoints.NO_UPGRADE;
 *
 *     cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf).build();
 *     {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
 *     checkpoint(UpgradeCheckpoints.AFTER_CLUSTER_START);
 *
 *     // Create resource
 *     {CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
 *     checkpoint("AFTER_CREATE");
 *
 *     // Test logic...
 *     // No try-finally needed - @After handles cleanup!
 *   }
 *
 *   @Test
 *   public void testFeature_AFTER_CLUSTER_START() throws Exception {
 *     upgradeCheckpoint = UpgradeCheckpoints.AFTER_CLUSTER_START;
 *
 *     // Same full test logic as above - upgrade happens at AFTER_CLUSTER_START
 *     cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf).build();
 *     {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
 *     checkpoint(UpgradeCheckpoints.AFTER_CLUSTER_START);
 *     {CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
 *     checkpoint("AFTER_CREATE");
 *     // ...
 *   }
 *
 *   @Test
 *   public void testFeature_AFTER_CREATE() throws Exception {
 *     upgradeCheckpoint = "AFTER_CREATE";
 *
 *     // Same full test logic - upgrade happens at AFTER_CREATE
 *     // ...
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Test Execution:</strong></p>
 * <pre>
 * # Run specific checkpoint
 * mvn test -Dtest=TestMyFeature#testFeature_AFTER_CLUSTER_START
 *
 * # Run all checkpoints for one test method
 * mvn test -Dtest='TestMyFeature#testFeature_*'
 *
 * # Run all baseline (NO_UPGRADE) tests
 * mvn test -Dtest='TestMyFeature#*_NO_UPGRADE'
 * </pre>
 *
 * <h3>Guarantees:</h3>
 * <ul>
 *   <li>Complete isolation between checkpoint executions</li>
 *   <li>Automatic cleanup of processes and directories</li>
 *   <li>Verification of cleanup success</li>
 *   <li>Force cleanup if verification fails</li>
 * </ul>
 */
public abstract class ProcessBasedUpgradeTestBase {
    // ... full implementation
}
```

---

## Tips for Best Results

1. **Be Specific**: Provide actual code snippets, not pseudocode
2. **Include Imports**: Mention key imports the AI should include
3. **Error Handling**: Specify how errors should be handled in cleanup
4. **Logging Level**: Indicate when to use INFO vs DEBUG vs ERROR
5. **Timeouts**: Specify reasonable timeout values for async operations
6. **Thread Safety**: Mention if any thread-safety considerations exist
7. **Resource Ordering**: Specify the order for cleanup (e.g., clients before cluster)
8. **Platform Differences**: Be explicit about OS-specific behavior

---

## Validation Checklist

After generation, verify the base class has:

- [ ] Complete JavaDoc with usage example showing named checkpoint test methods
- [ ] @Before method that performs cleanup and initialization
- [ ] @After method with independent try-catch blocks for each cleanup step
- [ ] checkpoint(String) method with health checks before and after upgrade
- [ ] shouldUpgrade(String) method with proper logic
- [ ] Process cleanup logic that matches your cluster (jps | grep pattern)
- [ ] Directory cleanup with age-based filtering (e.g., older than 1 hour)
- [ ] Cleanup verification method that checks for orphaned processes
- [ ] Proper null checks before all cleanup operations
- [ ] Fields set to null in finally blocks after cleanup
- [ ] Logger with appropriate log levels (INFO for major steps, DEBUG for details)
- [ ] No resource leaks in helper methods
- [ ] Platform-appropriate process management (handle Unix vs Windows)
- [ ] Comprehensive exception handling (independent try-catch per cleanup step)

---

## Notes

- This template is designed for JUnit 4 with standard @Test methods (no parameterization)
- For JUnit 5, adapt @Before/@After to @BeforeEach/@AfterEach
- For TestNG, adapt to @BeforeMethod/@AfterMethod
- Test methods set `upgradeCheckpoint` field directly at the start of each test
- Each test method should contain full test logic (not shared/helper methods) for clarity
- Consider adding timeout annotations for long-running tests (@Test(timeout = 300000))
- Add @Rule or @ClassRule if your framework supports them for additional cleanup
- Test method naming: `testMethodName_CHECKPOINT_NAME()` format

---

## Placeholder Reference

**Replace these placeholders throughout:**

- `{CLUSTER_TYPE_DESCRIPTION}` - What the cluster does (e.g., "distributed key-value store")
- `{PROCESS_BASED_CLUSTER_CLASS}` - New cluster class name
- `{PROCESS_BASED_CLUSTER_CLASS_FQN}` - Fully qualified class name
- `{CLIENT_CLASS}` - Client/connection class name
- `{CLIENT_CLASS_FQN}` - Fully qualified client class name
- `{CLIENT_VAR}` - Client variable name (e.g., "connection", "fs", "client")
- `{CONFIG_CLASS}` - Configuration class name
- `{CONFIG_CLASS_FQN}` - Fully qualified configuration class name
- `{CONFIG_PACKAGE}` - Package containing configuration class
- `{PACKAGE_NAME}` - Package for upgrade tests
- `{FULL_PACKAGE_NAME}` - Full package path
- `{FILE_PATH}` - File location relative to project root
- `{CLUSTER_INIT_CODE}` - Code to initialize cluster
- `{CLIENT_INIT_CODE}` - Code to initialize client
- `{CLUSTER_SHUTDOWN_CODE}` - Code to shutdown cluster
- `{CLIENT_SHUTDOWN_CODE}` - Code to shutdown client
- `{GET_CLIENT_METHOD}` - Method name to get client (e.g., "getConnection", "getFileSystem")
- `{UPGRADE_METHOD}` - Upgrade method description
- `{UPGRADE_INVOCATION_CODE}` - Code to invoke upgrade
- `{PRE_UPGRADE_HEALTH_CHECK}` - Health check before upgrade
- `{POST_UPGRADE_HEALTH_CHECK}` - Health check after upgrade
- `{PROCESS_PATTERN}` - Regex pattern to match processes
- `{PROCESS_CLEANUP_COMMAND}` - Shell command to kill processes
- `{TEMP_DIR_PATTERN}` - Pattern to match temp directories
- `{DIRECTORY_CLEANUP_LOGIC}` - Java code to cleanup directories
- `{CHECKPOINT_CLASS_NAME}` - Checkpoint constants class name
- `{CHECKPOINT_NAMES}` - Common checkpoint names
- `{NO_UPGRADE_CONSTANT}` - No-upgrade constant name
- `{ADDITIONAL_RESOURCES}` - Additional resources to manage
- `{ADDITIONAL_CLEANUP_STEPS}` - Additional cleanup steps
- `{SPECIAL_CONSIDERATIONS}` - Special considerations
- `{TARGET_OS}` - Target operating systems
- `{PROCESS_MANAGEMENT_APPROACH}` - Process management approach
- `{NODE_TYPE_1_CLASS}` - First node type class name
- `{NODE_TYPE_2_CLASS}` - Second node type class name
- `{NODE_TYPE_2_NAME}` - Second node type display name
- `{PROJECT_NAME}` - Project name
- `{CLUSTER_TYPE}` - Cluster type abbreviation
- `{ADMIN_CLASS_FQN}` - Admin client class FQN (if applicable)
- `{TABLE_CLASS_FQN}` - Table class FQN (if applicable)
- `{SCANNER_CLASS_FQN}` - Scanner class FQN (if applicable)

---

**End of Template**

Use this template to generate a comprehensive base test class for your checkpoint-based upgrade testing framework.
