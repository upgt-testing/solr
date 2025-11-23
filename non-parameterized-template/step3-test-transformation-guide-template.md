# Step 3: Test Transformation Guide Template

## Table of Contents
1. [Introduction & Philosophy](#introduction--philosophy)
2. [Prerequisites & Setup](#prerequisites--setup)
3. [Test Organization and Naming Convention](#test-organization-and-naming-convention)
4. [Core Transformation Rules](#core-transformation-rules)
5. [API Mapping Tables (TO FILL)](#api-mapping-tables-to-fill)
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
Transform existing `{MINI_CLUSTER_CLASS}` tests to `{PROCESS_BASED_CLUSTER_CLASS}` to enable:
- **Process-based testing** - Each node runs in separate JVM for realistic testing
- **Multi-version testing** - Test upgrades between different versions
- **Rolling upgrade scenarios** - Simulate production upgrade procedures
- **Version compatibility** - Verify protocol compatibility across versions

### Key Principle
**Most server-side operations have client-side RPC equivalents.** The goal is to maximize test logic preservation by finding client-side APIs that provide equivalent functionality.

### Critical Transformation Mindset

**EVERY REDUCED VERSION IS MEANINGFUL!**

If you cannot transform 100% of a test, transform what you CAN. A test with 30% preserved logic is infinitely better than 0%. Never skip a test just because:
- It uses a custom class (analyze what the class actually does)
- It has some internal access (transform the accessible parts)
- It seems "too complex" (reduce to essential behavior)

**Transform as much as possible, comment out as little as necessary.**

### Transformation Hierarchy
When encountering server-side operations, try these approaches in order:

1. **{HIGH_LEVEL_CLIENT_API}** - High-level client operations
2. **{MID_LEVEL_CLIENT_API}** - Mid-level operations
3. **{LOW_LEVEL_RPC_API}** - Low-level RPC interface
4. **{ADMIN_TOOLS_API}** - Administrative operations
5. **{MONITORING_API}** - For metrics and runtime statistics
6. **Comment Out** - Only if truly no client-side equivalent exists

### Why {PROCESS_BASED_CLUSTER_CLASS}?

**{MINI_CLUSTER_CLASS} limitations:**
- All nodes run in same JVM - cannot test different versions
- Direct object access - not realistic for production scenarios
- In-process - cannot simulate true process failures and restarts

**{PROCESS_BASED_CLUSTER_CLASS} benefits:**
- True process isolation - realistic testing
- Multi-version support - essential for upgrade testing
- Client-only access - forces use of public APIs (more realistic)
- Better represents production environments
- Node identity persistence - nodes maintain address/port across restarts

---

## Prerequisites & Setup

### System Properties (Automatic!)
{PROCESS_BASED_CLUSTER_CLASS} automatically reads distributions from system properties:

```bash
# No manual setup needed! Just pass system properties to Maven:
mvn test -Dtest=YourTransformedTest \
  -{PROJECT_ARTIFACT}.start.home=/path/to/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/path/to/{PROJECT_ARTIFACT}-{VERSION_2} \
  -pl {PROJECT_ROOT}
```

**Backward compatibility:** Environment variables (`{ENV_VAR_HOME}`, `{ENV_VAR_UPGRADE_HOME}`) still work as fallback.

### Test Configuration
```java
import {PACKAGE_PATH}.{PROCESS_BASED_CLUSTER_CLASS};

// No @Before setup needed! System properties are read automatically!
// We must add timeout 120s to allow for process startup time
@Test(timeout=120000)
public void testSomething() {
    // Just build - automatic!
    {PROCESS_BASED_CLUSTER_CLASS} cluster =
        new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
            .num{NODE_TYPE_2_NAME}s(3)
            .build();  // Automatically reads system properties!
}
```

### Running Transformed Tests
```bash
# Run with system properties (recommended)
mvn test -Dtest=YourTransformedTest \
  -{PROJECT_ARTIFACT}.start.home=/opt/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/opt/{PROJECT_ARTIFACT}-{VERSION_2} \
  -pl {PROJECT_ROOT}
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
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/
├── Test{Feature1}.java                      [ORIGINAL - {MINI_CLUSTER_CLASS}]
├── Test{Feature1}_ProcessBased.java         [TRANSFORMED - ProcessBased]
├── Test{Feature2}.java                      [ORIGINAL - {MINI_CLUSTER_CLASS}]
├── Test{Feature2}_ProcessBased.java         [TRANSFORMED - ProcessBased]
└── {SUBPACKAGE}/
    ├── Test{Feature3}.java                  [ORIGINAL - {MINI_CLUSTER_CLASS}]
    └── Test{Feature3}_ProcessBased.java     [TRANSFORMED - ProcessBased]
```

### Package and Class Declaration

The transformed test uses the **same package** as the original:

```java
// Original: Test{Feature}.java
package {PACKAGE_PATH};

public class Test{Feature} {
  // ... {MINI_CLUSTER_CLASS} tests
}
```

```java
// Transformed: Test{Feature}_ProcessBased.java
package {PACKAGE_PATH};  // Same package!

/**
 * ProcessBased version of {@link Test{Feature}}.
 *
 * Transformed from {MINI_CLUSTER_CLASS} to {PROCESS_BASED_CLUSTER_CLASS} to enable
 * process-based testing and multi-version upgrade scenarios.
 *
 * @see Test{Feature} Original test using {MINI_CLUSTER_CLASS}
 */
public class Test{Feature}_ProcessBased {
  // ... {PROCESS_BASED_CLUSTER_CLASS} tests
}
```

### Test Execution Patterns

```bash
# Run original test only
mvn test -Dtest=Test{Feature}

# Run transformed test only
mvn test -Dtest=Test{Feature}_ProcessBased

# Run ALL ProcessBased tests across the codebase
mvn test -Dtest="*_ProcessBased"

# Run both versions for comparison
mvn test -Dtest=Test{Feature},Test{Feature}_ProcessBased
```

---

## Core Transformation Rules

### Rule 1: Maximize Test Logic Preservation
**Preserve as much of the original test logic as possible** by finding client-side equivalents for server-side operations.

✅ **DO**: Find client API that provides same functionality
❌ **DON'T**: Remove test logic unless absolutely necessary

### Rule 2: Use the API Hierarchy
Always try to find client-side equivalents in this order:
1. {HIGH_LEVEL_CLIENT_API} (highest level)
2. {MID_LEVEL_CLIENT_API} (mid-level)
3. {LOW_LEVEL_RPC_API} (RPC level)
4. {ADMIN_TOOLS_API} (admin operations)
5. {MONITORING_API} (monitoring)

### Rule 3: Comment Out Only When Necessary
Only comment out operations when:
- No client-side API exists
- Operation accesses internal storage
- Operation manipulates JVM-internal state

### Rule 4: Document Minimally
Add comments only for:
- Non-obvious transformations
- Commented-out logic (explain why and what was removed)
- Workarounds or limitations

---

## API Mapping Tables (TO FILL)

**INSTRUCTIONS**: Fill in these tables with your project's specific API mappings. These tables will guide your transformation process.

### Table 1: {MINI_CLUSTER_CLASS} → {PROCESS_BASED_CLUSTER_CLASS}

| {MINI_CLUSTER_CLASS} Method | {PROCESS_BASED_CLUSTER_CLASS} | Status | Notes |
|------------------------------|-------------------------------|--------|-------|
| **Cluster Creation** |
| `new Builder(conf).build()` | `new Builder(conf).build()` | ✓ | Automatically reads system properties |
| **Client Access** |
| `{GET_CLIENT_METHOD}()` | `{GET_CLIENT_METHOD}()` | ✓ | Same API |
| `getURI()` | `getURI()` | ✓ | Same API |
| **Configuration** |
| `getConfiguration(int n)` | `getConfiguration(int n)` | ⚠️ | Returns base config, not process-specific |
| **Node Management** |
| `restart{NODE_TYPE_1_NAME}(int i)` | `restart{NODE_TYPE_1_NAME}(int i)` | ✓ | Same API |
| `restart{NODE_TYPE_2_NAME}(int i)` | `restart{NODE_TYPE_2_NAME}(int i)` | ✓ | Same API |
| `shutdown{NODE_TYPE_1_NAME}(int i)` | `shutdown{NODE_TYPE_1_NAME}(int i)` | ✓ | Same API |
| `shutdown{NODE_TYPE_2_NAME}(int i)` | `shutdown{NODE_TYPE_2_NAME}(int i)` | ✓ | Same API |
| `start{NODE_TYPE_2_NAME}(config, ...)` | `start{NODE_TYPE_2_NAME}(int i)` | ⚠️ | Different signature |
| `waitActive()` or `waitClusterUp()` | `waitClusterUp()` | ✓ | Same or similar |
| **Direct Object Access** |
| `get{NODE_TYPE_1_NAME}()` | ❌ | ✗ | Use client APIs (see below) |
| `get{NODE_TYPE_1_NAME}(int i)` | ❌ | ✗ | Use client APIs (see below) |
| `get{NODE_TYPE_2_NAME}(int i)` | ❌ | ✗ | Use client APIs (see below) |
| `get{NODE_TYPE_2_NAME}s()` | ❌ | ✗ | Use client APIs (see below) |
| **Cluster Control** |
| `trigger{HEARTBEAT_METHOD}()` | ⚠️ | ⚠️ | Use `Thread.sleep()` + wait for state change |
| `shutdown()` | `shutdown()` | ✓ | Same API |
| **Cluster State** |
| `getNum{NODE_TYPE_2_NAME}s()` | `getNum{NODE_TYPE_2_NAME}s()` | ✓ | Same API |
| `isClusterUp()` | `isClusterUp()` | ✓ | Same API |

### Table 2: {SERVER_COMPONENT_1} (Server-Side) → Client-Side APIs

**INSTRUCTIONS**: Fill in this table with mappings for your primary server component (e.g., Master, Coordinator, NameNode).

**Context**: `{SERVER_COMPONENT_1}` is a private, server-side class managing {SERVER_COMPONENT_1_ROLE}. All its operations should have client-side equivalents through RPC.

| {SERVER_COMPONENT_1} Method | Client-Side API | API Layer | Code Example |
|-----------------------------|-----------------|-----------|--------------|
| **{OPERATION_CATEGORY_1} (e.g., Data Operations)** |
| `{METHOD_1}(...)` | `{CLIENT_API}.{CLIENT_METHOD_1}(...)` | {API_LAYER} | `{CODE_EXAMPLE}` |
| `{METHOD_2}(...)` | `{CLIENT_API}.{CLIENT_METHOD_2}(...)` | {API_LAYER} | `{CODE_EXAMPLE}` |
| **{OPERATION_CATEGORY_2} (e.g., Metadata Operations)** |
| `{METHOD_3}(...)` | `{CLIENT_API}.{CLIENT_METHOD_3}(...)` | {API_LAYER} | `{CODE_EXAMPLE}` |
| **{OPERATION_CATEGORY_3} (e.g., Administrative Operations)** |
| `{METHOD_4}(...)` | `{CLIENT_API}.{CLIENT_METHOD_4}(...)` | {API_LAYER} | `{CODE_EXAMPLE}` |
| **Internal Operations** (❌ No client API) |
| `{INTERNAL_METHOD_1}()` | ❌ | - | Internal storage - comment out |
| `{INTERNAL_METHOD_2}()` | ❌ | - | Internal state - comment out |

### Table 3: {SERVER_COMPONENT_2} (Server-Side) → Client-Side APIs

**INSTRUCTIONS**: Fill in this table for your secondary server component (e.g., Worker, RegionServer, DataNode).

| {SERVER_COMPONENT_2} Method | Client-Side API | API Layer | Status | Notes |
|-----------------------------|-----------------|-----------|--------|-------|
| **{OPERATION_CATEGORY_4}** |
| `{METHOD_5}(...)` | `{CLIENT_API}.{CLIENT_METHOD_5}(...)` | {API_LAYER} | ✓ | {NOTES} |
| **Statistics/Monitoring** |
| `{STATS_METHOD}()` | `{CLIENT_API}.{CLIENT_STATS_METHOD}()` | {API_LAYER} | ✓ | {NOTES} |
| **Internal Operations** (❌ No client API) |
| `{INTERNAL_METHOD_3}()` | ❌ | - | ✗ | Internal dataset - comment out |

### Table 4: Admin Operations → {ADMIN_TOOLS_API}

**INSTRUCTIONS**: Fill in administrative operations and their client-side equivalents.

| Operation | {ADMIN_TOOLS} Command/API | Code Example | Alternative API |
|-----------|---------------------------|--------------|-----------------|
| **Upgrade Operations** |
| Start upgrade | `{UPGRADE_START_COMMAND}` | `{CODE_EXAMPLE}` | `{ALTERNATIVE}` |
| Finalize upgrade | `{UPGRADE_FINALIZE_COMMAND}` | `{CODE_EXAMPLE}` | `{ALTERNATIVE}` |
| Query upgrade status | `{UPGRADE_QUERY_COMMAND}` | `{CODE_EXAMPLE}` | `{ALTERNATIVE}` |
| **{ADMIN_OPERATION_CATEGORY_1}** |
| {OPERATION_NAME} | `{COMMAND}` | `{CODE_EXAMPLE}` | `{ALTERNATIVE}` |
| **{ADMIN_OPERATION_CATEGORY_2}** |
| {OPERATION_NAME} | `{COMMAND}` | `{CODE_EXAMPLE}` | `{ALTERNATIVE}` |

### Table 5: Monitoring/Metrics → {MONITORING_API}

**INSTRUCTIONS**: Fill in how to access monitoring data from client side.

| Server-Side Check | Client-Side Alternative | Access Method | Example |
|------------------|-------------------------|---------------|---------|
| **{METRIC_CATEGORY_1}** |
| {METRIC_NAME} | `{CLIENT_METHOD}` | {ACCESS_METHOD} | `{CODE_EXAMPLE}` |
| **{METRIC_CATEGORY_2}** |
| {METRIC_NAME} | `{CLIENT_METHOD}` | {ACCESS_METHOD} | `{CODE_EXAMPLE}` |

### Table 6: Common Test Utilities

**INSTRUCTIONS**: Map test utility methods to their alternatives.

| {MINI_CLUSTER_CLASS} Utility | {PROCESS_BASED_CLUSTER_CLASS} Alternative | Notes |
|------------------------------|-------------------------------------------|-------|
| `cluster.trigger{HEARTBEAT_METHOD}()` | `Thread.sleep(5000)` + verify state | Wait for natural heartbeat cycle |
| `{TEST_UTIL}.{METHOD}()` | `{ALTERNATIVE}` | {NOTES} |

---

## Step-by-Step Transformation Process

### Step 0: Create Transformed Test File

**Goal**: Set up the new test file with proper naming and location.

1. **Locate the original test:**
   ```bash
   # Example: Original test
   {PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/Test{Feature}.java
   ```

2. **Create new file with `_ProcessBased` suffix in the SAME directory:**
   ```bash
   # New transformed test (same directory!)
   {PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/Test{Feature}_ProcessBased.java
   ```

3. **Copy original test content to new file:**
   ```bash
   cp Test{Feature}.java Test{Feature}_ProcessBased.java
   ```

4. **Update class name and add Javadoc:**
   ```java
   package {PACKAGE_PATH};  // Same package as original!

   /**
    * ProcessBased version of {@link Test{Feature}}.
    *
    * Transformed from {MINI_CLUSTER_CLASS} to {PROCESS_BASED_CLUSTER_CLASS} to enable
    * process-based testing and multi-version upgrade scenarios.
    *
    * @see Test{Feature} Original test using {MINI_CLUSTER_CLASS}
    */
   public class Test{Feature}_ProcessBased {  // Note: _ProcessBased suffix
     // ... test methods
   }
   ```

5. **Checklist before proceeding:**
   - [ ] New file created in same directory as original
   - [ ] Class name has `_ProcessBased` suffix
   - [ ] Package declaration is identical to original
   - [ ] Javadoc references original test with `@see` tag
   - [ ] File compiles (even if tests fail)

### Step 1: Analyze Test Dependencies

**Goal**: Understand what server-side operations the test uses.

1. **Scan for direct object access patterns:**
   ```bash
   # Search for common patterns (customize for your project)
   grep -E "cluster\.(get{NODE_TYPE_1_NAME}|get{NODE_TYPE_2_NAME})" YourTest.java
   grep -E "\.{INTERNAL_METHOD_PATTERN}\(\)" YourTest.java
   ```

2. **Categorize operations:**
   - ✅ **Already client-side**: {HIGH_LEVEL_CLIENT_API} operations, {ADMIN_TOOLS_API} calls
   - ⚠️ **Has client equivalent**: {SERVER_COMPONENT_1} methods, some {NODE_TYPE_1_NAME} methods
   - ❌ **No client equivalent**: Internal storage, internal state, JVM state

3. **Plan transformation:**
   - List all operations that need transformation
   - Find client equivalents in mapping tables
   - Identify operations that must be commented out

### Step 2: Transform Import Statements

```java
// BEFORE
import {PACKAGE_PATH}.{MINI_CLUSTER_CLASS};
import {PACKAGE_PATH}.{NODE_TYPE_1_CLASS};
import {PACKAGE_PATH}.{NODE_TYPE_2_CLASS};

// AFTER
import {PACKAGE_PATH}.{PROCESS_BASED_CLUSTER_CLASS};
// Remove: import {PACKAGE_PATH}.{MINI_CLUSTER_CLASS};
// Keep node imports only if used in type declarations that can't be removed
```

### Step 3: Transform Cluster Setup

#### Basic Cluster Creation

```java
// BEFORE ({MINI_CLUSTER_CLASS})
{CONFIG_CLASS} conf = new {CONFIG_CLASS}();
{MINI_CLUSTER_CLASS} cluster = new {MINI_CLUSTER_CLASS}.Builder(conf)
    .num{NODE_TYPE_2_NAME}s(3)
    .build();
cluster.waitClusterUp();

// AFTER ({PROCESS_BASED_CLUSTER_CLASS}) - AUTOMATIC!
{CONFIG_CLASS} conf = new {CONFIG_CLASS}();
// No environment variable checks needed! Automatic!

{PROCESS_BASED_CLUSTER_CLASS} cluster =
    new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
        .num{NODE_TYPE_2_NAME}s(3)
        .format(true)
        .build();  // Automatically reads system properties!
cluster.waitClusterUp();
```

**Note:** System properties are passed via Maven:
```bash
mvn test -Dtest=MyTest \
  -{PROJECT_ARTIFACT}.start.home=/path/to/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/path/to/{PROJECT_ARTIFACT}-{VERSION_2}
```

#### Multi-Version Cluster (for upgrade tests)

```java
// All ProcessBased tests support upgrades automatically!
// Just build the cluster - it reads system properties automatically

{PROCESS_BASED_CLUSTER_CLASS} cluster =
    new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
        .num{NODE_TYPE_2_NAME}s(3)
        .format(true)
        .build();  // Starts with {PROJECT_ARTIFACT}.start.home

// Later, perform rolling upgrade to {PROJECT_ARTIFACT}.upgrade.home
String upgradeHome = cluster.getUpgradeDistributionPath();
for (int i = 0; i < 3; i++) {
    cluster.shutdown{NODE_TYPE_2_NAME}(i);
    cluster.change{NODE_TYPE_2_NAME}Version(i, upgradeHome);
    cluster.start{NODE_TYPE_2_NAME}(i);
}
```

### Step 4: Transform Operations Using Mapping Tables

**INSTRUCTIONS**: Use your filled-in mapping tables to guide these transformations.

#### Example 1: {HIGH_LEVEL_CLIENT_API} Operations (No Change)
```java
// These work identically - no transformation needed
{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
// Use {CLIENT_VAR} for operations...
```

#### Example 2: {SERVER_COMPONENT_1} Access → Client API
```java
// BEFORE: Direct server-side access
{SERVER_COMPONENT_1} {SERVER_VAR} = cluster.get{NODE_TYPE_1_NAME}();
{SOME_TYPE} result = {SERVER_VAR}.{SERVER_METHOD}({ARGS});

// AFTER: Client-side equivalent (use your mapping table!)
{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
{SOME_TYPE} result = {CLIENT_VAR}.{CLIENT_METHOD}({ARGS});
```

#### Example 3: Internal Storage Check → Comment Out
```java
// BEFORE: Internal storage verification
{INTERNAL_STORAGE_TYPE} storage = cluster.get{NODE_TYPE_1_NAME}().{GET_INTERNAL_STORAGE}();
// ... verification code

// AFTER: Comment out with documentation
// TRANSFORMATION NOTE: Internal storage verification removed.
// {GET_INTERNAL_STORAGE}() provides access to internal storage,
// which is not available via any client API.
// Original test verified {WHAT_WAS_VERIFIED}.
// No client-side alternative available.
//
// Original code:
// {INTERNAL_STORAGE_TYPE} storage = cluster.get{NODE_TYPE_1_NAME}().{GET_INTERNAL_STORAGE}();
// ... (commented out verification code)
```

### Step 5: Transform Cleanup Code

```java
// BEFORE
@After
public void tearDown() {
    if (cluster != null) {
        cluster.shutdown();
    }
}

// AFTER (same, but can use try-with-resources if supported)
@After
public void tearDown() {
    if (cluster != null) {
        cluster.shutdown();
    }
}

// OR use try-with-resources in the test method:
@Test
public void testSomething() throws Exception {
    try ({PROCESS_BASED_CLUSTER_CLASS} cluster =
            new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
                .num{NODE_TYPE_2_NAME}s(3)
                .build()) {  // Automatic - reads system properties!
        // Test logic here
    }
}
```

### Step 6: Validate Transformation

Run through this checklist:

- [ ] All imports updated
- [ ] All direct object access either transformed or commented out
- [ ] Cluster creation includes distribution path (via system properties)
- [ ] Test logic preserved as much as possible
- [ ] Only truly internal operations commented out
- [ ] Comments added for non-obvious transformations
- [ ] Test compiles without errors
- [ ] System properties documented in test javadoc

---

## Common Transformation Patterns

### Pattern 1: {HIGH_LEVEL_CLIENT_API} Operations
**Status**: ✅ No transformation needed

```java
// Works identically in both frameworks
{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
// Use {CLIENT_VAR} for operations...
```

### Pattern 2: Administrative Operations
```java
// Use {ADMIN_TOOLS_API} or admin client API
// (Refer to your Table 4: Admin Operations mapping)
```

### Pattern 3: Waiting for State Changes
```java
// BEFORE: Trigger immediate state propagation
cluster.trigger{HEARTBEAT_METHOD}();

// AFTER: Wait for natural propagation
Thread.sleep({HEARTBEAT_INTERVAL_MS});  // Wait for heartbeat interval
// Or use GenericTestUtils.waitFor()
GenericTestUtils.waitFor(() -> {
    try {
        // Check desired state via client API
        return {CHECK_CONDITION};
    } catch (Exception e) {
        return false;
    }
}, 500, 30000);
```

### Pattern 4: Node Information
```java
// BEFORE: Direct node access
{NODE_TYPE_2_CLASS} node = cluster.get{NODE_TYPE_2_NAME}(0);
{INFO_TYPE} info = node.{GET_INFO_METHOD}();

// AFTER: Via cluster statistics (use your mapping table!)
{INFO_TYPE} info = {CLIENT_VAR}.{GET_NODE_INFO_METHOD}();
```

---

## Inserting Cluster Upgrade Method Calls

### Overview

When transforming tests to support rolling upgrades, you need to insert `cluster.upgrade()` method calls at appropriate points in the test. This section explains how to identify upgrade points and handle the critical pattern of **closing resources before upgrade and reopening them afterward**.

### Why Resource Management is Critical

During a rolling upgrade, cluster nodes are restarted with new software versions. This restart **breaks active connections** between the client and the nodes.

**Key principle**: Any active connection/stream/resource that spans an upgrade point must be:
1. **Closed** before calling `cluster.upgrade()`
2. **Reopened** after `cluster.upgrade()` completes

### Why Node Identity Preservation is Critical

In addition to closing resources, **node identity must be preserved** during upgrades.

**What is Node Identity?**
- RPC port
- HTTP port
- Network address
- Node ID
- Configuration directory

**Why It Matters:**

If a node's identity changes during restart/upgrade:
- ❌ Other nodes think it's a NEW node joining
- ❌ Original node appears DEAD or DECOMMISSIONED
- ❌ Cluster triggers unnecessary rebalancing
- ❌ Data may be unnecessarily replicated
- ❌ Upgrade test fails to represent production behavior

**How {PROCESS_BASED_CLUSTER_CLASS} Preserves Identity:**

The cluster automatically:
1. **Persists port allocations** to disk before first startup
2. **Reuses persisted ports** during restart/upgrade
3. **Maintains work directory** with configuration
4. **Validates port availability** before restart
5. **Throws error** if identity cannot be preserved

**Example:**

```java
// Initial startup
cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
    .num{NODE_TYPE_2_NAME}s(3)
    .build();
// Node 0 gets RPC port 50001, persisted to disk

// Later: Rolling upgrade
InetSocketAddress addrBefore = cluster.get{NODE_TYPE_2_NAME}Address(0);
// addrBefore = localhost:50001

cluster.shutdown{NODE_TYPE_2_NAME}(0);
cluster.change{NODE_TYPE_2_NAME}Version(0, upgradeVersion);
cluster.start{NODE_TYPE_2_NAME}(0);
// Node 0 loads persisted port 50001, starts with SAME address

InetSocketAddress addrAfter = cluster.get{NODE_TYPE_2_NAME}Address(0);
// addrAfter = localhost:50001

assert addrBefore.equals(addrAfter);  // Identity preserved!
```

**Automatic vs Manual:**

✅ **ProcessBased (Automatic)**:
- Ports persisted automatically
- Identity preserved across restarts
- No manual tracking needed
- Errors if identity cannot be preserved

❌ **Manual Mini Cluster**:
- Must manually track ports
- Easy to accidentally change ports
- No validation of identity preservation
- Silent failures possible

### Identifying Upgrade Points

An upgrade point is a logical location in your test where you want to simulate a rolling upgrade. Common upgrade points include:

1. **Mid-operation** - Testing that data created before upgrade is accessible after upgrade
2. **Between distinct test phases** - After setup operations but before verification
3. **After creating test data** - Testing upgrade with existing data
4. **During long-running operations** - Testing upgrade resilience

### Step-by-Step: Inserting Upgrade Calls

#### Step 1: Identify the Upgrade Point

Look for a logical point in the test where upgrade makes sense:

```java
// BEFORE: Original test without upgrade
{CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
{CLIENT_VAR}.{WRITE_DATA}(data1);
// <-- Potential upgrade point
{CLIENT_VAR}.{WRITE_DATA}(data2);
{CLIENT_VAR}.{CLOSE_RESOURCE}();
```

#### Step 2: Close Resources Before Upgrade

If a resource is open at the upgrade point, close it first:

```java
// AFTER: With upgrade point inserted
{CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
{CLIENT_VAR}.{WRITE_DATA}(data1);

// === ROLLING UPGRADE POINT ===
// CRITICAL: Close the resource before upgrade since nodes will be restarted
{CLIENT_VAR}.{CLOSE_RESOURCE}();
System.out.println("Closed resource before rolling upgrade");
```

#### Step 3: Call cluster.upgrade()

```java
// Perform rolling upgrade
cluster.upgrade();  // Executes full rolling upgrade procedure
System.out.println("Rolling upgrade completed successfully");
```

**Note**: The `cluster.upgrade()` method:
- Automatically follows the official upgrade procedure
- Handles node restarts in correct order
- Waits for cluster stabilization
- Ensures data availability

#### Step 4: Reopen Resources After Upgrade

If you need to continue operations, reopen the resource:

```java
// Reopen the resource after upgrade
{CLIENT_VAR}.{REOPEN_RESOURCE}({ARGS});
System.out.println("Reopened resource after upgrade");

// Continue operations
{CLIENT_VAR}.{WRITE_DATA}(data2);
{CLIENT_VAR}.{CLOSE_RESOURCE}();
```

### Complete Example Pattern

```java
@Test
public void testOperation() throws Exception {
    {CONFIG_CLASS} conf = new {CONFIG_CLASS}();

    {PROCESS_BASED_CLUSTER_CLASS} cluster =
        new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
            .num{NODE_TYPE_2_NAME}s(3)
            .build();
    {CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();

    try {
        cluster.waitClusterUp();

        // Create resource and perform first operation
        {CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
        {CLIENT_VAR}.{WRITE_DATA}(data1);

        // === ROLLING UPGRADE POINT ===
        // STEP 1: Close the resource before upgrade
        {CLIENT_VAR}.{CLOSE_RESOURCE}();
        System.out.println("Closed resource before rolling upgrade");

        // STEP 2: Capture node identities BEFORE upgrade
        Map<Integer, InetSocketAddress> preUpgradeAddrs = new HashMap<>();
        for (int i = 0; i < cluster.getNum{NODE_TYPE_2_NAME}s(); i++) {
            preUpgradeAddrs.put(i, cluster.get{NODE_TYPE_2_NAME}Address(i));
        }

        // STEP 3: Perform rolling upgrade
        cluster.upgrade();
        System.out.println("Rolling upgrade completed successfully");

        // STEP 4: Verify node identities PRESERVED
        for (int i = 0; i < cluster.getNum{NODE_TYPE_2_NAME}s(); i++) {
            InetSocketAddress postAddr = cluster.get{NODE_TYPE_2_NAME}Address(i);
            InetSocketAddress preAddr = preUpgradeAddrs.get(i);
            if (!postAddr.equals(preAddr)) {
                throw new AssertionError(
                    "Node " + i + " identity changed during upgrade! " +
                    "Before: " + preAddr + ", After: " + postAddr);
            }
        }
        System.out.println("Node identities preserved across upgrade");

        // STEP 5: Reopen resource
        {CLIENT_VAR}.{REOPEN_RESOURCE}({ARGS});
        System.out.println("Reopened resource after upgrade");

        // Continue operations after upgrade
        {CLIENT_VAR}.{WRITE_DATA}(data2);

        // Verify all data
        {VERIFY_DATA}();

        {CLIENT_VAR}.{CLOSE_RESOURCE}();

    } finally {
        {CLIENT_VAR}.close();
        cluster.shutdown();
    }
}
```

### Common Mistakes to Avoid

#### ❌ Mistake 1: Not closing resources before upgrade

```java
// WRONG - Resource remains open during upgrade
{CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
{CLIENT_VAR}.{WRITE_DATA}(data1);
cluster.upgrade();  // Connection will break!
{CLIENT_VAR}.{WRITE_DATA}(data2);  // This will fail!
```

#### ✅ Correct Pattern

```java
// CORRECT - Close, upgrade, reopen
{CLIENT_VAR}.{CREATE_RESOURCE}({ARGS});
{CLIENT_VAR}.{WRITE_DATA}(data1);
{CLIENT_VAR}.{CLOSE_RESOURCE}();

cluster.upgrade();

{CLIENT_VAR}.{REOPEN_RESOURCE}({ARGS});
{CLIENT_VAR}.{WRITE_DATA}(data2);
{CLIENT_VAR}.{CLOSE_RESOURCE}();
```

#### ❌ Mistake 3: Not verifying node identity preservation

```java
// WRONG - Assumes identity preserved without verification
cluster.upgrade();
// Continue testing without checking if nodes maintained their addresses
```

#### ✅ Correct Pattern

```java
// CORRECT - Verify identity preservation
Map<Integer, InetSocketAddress> preUpgradeAddrs = new HashMap<>();
for (int i = 0; i < cluster.getNum{NODE_TYPE_2_NAME}s(); i++) {
    preUpgradeAddrs.put(i, cluster.get{NODE_TYPE_2_NAME}Address(i));
}

cluster.upgrade();

// Verify all nodes kept same addresses
for (int i = 0; i < cluster.getNum{NODE_TYPE_2_NAME}s(); i++) {
    InetSocketAddress postAddr = cluster.get{NODE_TYPE_2_NAME}Address(i);
    assert postAddr.equals(preUpgradeAddrs.get(i)) :
        "Node " + i + " address changed!";
}
```

---

## Upgrade Checkpoint Test Methods

### Overview

**Recommended Approach**: Instead of hardcoding a single upgrade point in each test, generate multiple test methods with checkpoint suffixes. Each test method tests the same logic but with upgrade at a different checkpoint. This provides comprehensive upgrade coverage with Maven Surefire compatibility.

**Key Benefits**:
- Single test logic → multiple test methods with different checkpoints
- 100% reproducible (deterministic checkpoint execution)
- Comprehensive coverage (standard + test-specific checkpoints)
- Guaranteed cleanup between executions
- Easy Maven execution: can run specific checkpoint with `-Dtest=Test#method_CHECKPOINT`
- Compatible with Maven Surefire single-method execution

### Base Class: ProcessBasedUpgradeTestBase

All ProcessBased tests should extend `ProcessBasedUpgradeTestBase`, which provides:

1. **@Before cleanup**: Kills orphaned processes, cleans old directories
2. **@After cleanup**: Closes client, shuts down cluster, verifies cleanup
3. **checkpoint(name)**: Performs upgrade if name matches parameter
4. **shouldUpgrade(name)**: Checks if upgrade should happen

**Location**: `{PACKAGE_PATH}.ProcessBasedUpgradeTestBase`

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
public void testOperation() {
  cluster = ...;
  checkpoint("AFTER_CLUSTER_START");

  createResource();
  checkpoint("AFTER_CREATE");

  writeData();
  checkpoint("AFTER_WRITE");

  verify();
}
```

**Identified checkpoints:**
- `NO_UPGRADE` (standard)
- `AFTER_CLUSTER_START` (standard + in test)
- `AFTER_CREATE` (test-specific)
- `AFTER_WRITE` (test-specific)

#### Step 2: Generate Test Methods

Create one test method per checkpoint with naming pattern `testMethodName_CHECKPOINT_NAME()`:

```java
// Add imports
import {PACKAGE_PATH}.ProcessBasedUpgradeTestBase;
import {PACKAGE_PATH}.UpgradeCheckpoints;

// Extend base class (NO parameterization annotations)
public class Test{Feature}_ProcessBased extends ProcessBasedUpgradeTestBase {

  @Test
  public void testOperation_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = UpgradeCheckpoints.NO_UPGRADE;

    // Full test logic
    cluster = ...;
    checkpoint("AFTER_CLUSTER_START");
    createResource();
    checkpoint("AFTER_CREATE");
    writeData();
    checkpoint("AFTER_WRITE");
    verify();
  }

  @Test
  public void testOperation_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = UpgradeCheckpoints.AFTER_CLUSTER_START;

    // Same full test logic - upgrade happens at AFTER_CLUSTER_START
    cluster = ...;
    checkpoint("AFTER_CLUSTER_START");
    createResource();
    checkpoint("AFTER_CREATE");
    writeData();
    checkpoint("AFTER_WRITE");
    verify();
  }

  @Test
  public void testOperation_AFTER_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_CREATE";

    // Same full test logic - upgrade happens at AFTER_CREATE
    cluster = ...;
    checkpoint("AFTER_CLUSTER_START");
    createResource();
    checkpoint("AFTER_CREATE");
    writeData();
    checkpoint("AFTER_WRITE");
    verify();
  }

  @Test
  public void testOperation_AFTER_WRITE() throws Exception {
    upgradeCheckpoint = "AFTER_WRITE";

    // Same full test logic - upgrade happens at AFTER_WRITE
    cluster = ...;
    checkpoint("AFTER_CLUSTER_START");
    createResource();
    checkpoint("AFTER_CREATE");
    writeData();
    checkpoint("AFTER_WRITE");
    verify();
  }
}
```

#### Step 3: Code Duplication Note

Note that each test method contains **full duplication** of the test logic. This is intentional:
- Makes each test method independently runnable
- Clear what each checkpoint variant does
- Compatible with Maven Surefire single-method execution
- No shared state between methods (base class handles cleanup)

**BEFORE** (manual cleanup):
```java
@Test
public void testSomething() throws Exception {
  {CONFIG_CLASS} conf = new {CONFIG_CLASS}();
  {PROCESS_BASED_CLUSTER_CLASS} cluster = new Builder(conf).build();
  {CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();

  try {
    // test logic
  } finally {
    {CLIENT_VAR}.close();
    cluster.shutdown();
  }
}
```

**AFTER** (automatic cleanup via base class, with checkpoint test methods):
```java
@Test
public void testSomething_NO_UPGRADE() throws Exception {
  upgradeCheckpoint = UpgradeCheckpoints.NO_UPGRADE;

  // Use conf, cluster, {CLIENT_VAR} from base class
  cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf).build();
  {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();

  // test logic with checkpoints
  {CLIENT_VAR}.{OPERATION}({ARGS});
  checkpoint(UpgradeCheckpoints.AFTER_{OPERATION});

  // No try-finally needed - @After handles cleanup!
}

@Test
public void testSomething_AFTER_CLUSTER_START() throws Exception {
  upgradeCheckpoint = UpgradeCheckpoints.AFTER_CLUSTER_START;

  // Same test logic
  cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf).build();
  {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
  {CLIENT_VAR}.{OPERATION}({ARGS});
  checkpoint(UpgradeCheckpoints.AFTER_{OPERATION});
}
```

#### Step 4: Replace Hardcoded cluster.upgrade() with checkpoint()

If the original test had a hardcoded `cluster.upgrade()` call, replace it with `checkpoint()` calls at appropriate points. The checkpoint() method in the base class will perform the upgrade only if the current test method's `upgradeCheckpoint` field matches the checkpoint name.

**BEFORE** (hardcoded upgrade point):
```java
{CLIENT_VAR}.{OPERATION_1}({ARGS});

// === ROLLING UPGRADE POINT ===
{CLIENT_VAR}.{CLOSE}();
cluster.upgrade();
{CLIENT_VAR}.{REOPEN}();

{CLIENT_VAR}.{OPERATION_2}({ARGS});
```

**AFTER** (checkpoint-based, same logic in all test methods):
```java
// This code appears in EVERY test method variant (NO_UPGRADE, AFTER_OPERATION_1, etc.)
{CLIENT_VAR}.{OPERATION_1}({ARGS});
checkpoint("AFTER_{OPERATION_1}");

// Close before potential upgrade
{CLIENT_VAR}.{CLOSE}();
checkpoint("AFTER_CLOSE");

// Reopen (always needed, regardless of upgrade)
{CLIENT_VAR}.{REOPEN}();
checkpoint("AFTER_REOPEN");

{CLIENT_VAR}.{OPERATION_2}({ARGS});
checkpoint("AFTER_{OPERATION_2}");

// The upgrade only happens if upgradeCheckpoint matches a checkpoint name
// - In testMethod_NO_UPGRADE(): no upgrade happens
// - In testMethod_AFTER_OPERATION_1(): upgrade happens at AFTER_OPERATION_1
// - In testMethod_AFTER_CLOSE(): upgrade happens at AFTER_CLOSE
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
   - Use UpgradeCheckpoints constants for standard checkpoints
   - Use descriptive strings for test-specific checkpoints
   - Be descriptive: "AFTER_BALANCER_RUN" not "CHECKPOINT_7"

**Example checkpoint identification:**
```java
// Original test method with checkpoint() calls
@Test
public void testFoo() {
  cluster = ...;
  checkpoint("AFTER_CLUSTER_START");      // Found #1

  createTable();
  checkpoint("AFTER_CREATE_TABLE");       // Found #2

  writeData();
  checkpoint("AFTER_WRITE");              // Found #3

  verify();
}
```

**Generated test methods:**
- `testFoo_NO_UPGRADE()` - standard
- `testFoo_AFTER_CLUSTER_START()` - standard + found in test
- `testFoo_AFTER_CREATE_TABLE()` - test-specific
- `testFoo_AFTER_WRITE()` - test-specific

**Common checkpoint categories**:
- Cluster lifecycle: `AFTER_CLUSTER_START`
- {OPERATION_CATEGORY_1}: `AFTER_{OPERATION_1}`, `AFTER_{OPERATION_2}`
- Resource lifecycle: `AFTER_CLOSE`, `AFTER_REOPEN`
- Verification: `BEFORE_VERIFICATION`, `AFTER_VERIFICATION`
- Identity verification: `AFTER_IDENTITY_VERIFICATION`, `BEFORE_IDENTITY_CHECK`

### Running Checkpoint Test Methods

**Run all test methods (all checkpoints for all tests)**:
```bash
mvn test -Dtest=Test{Feature}_ProcessBased \
  -{PROJECT_ARTIFACT}.start.home=/opt/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/opt/{PROJECT_ARTIFACT}-{VERSION_2}
```

**Run specific checkpoint for specific test**:
```bash
mvn test -Dtest=Test{Feature}_ProcessBased#testMethod_AFTER_{OPERATION} \
  -{PROJECT_ARTIFACT}.start.home=/opt/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/opt/{PROJECT_ARTIFACT}-{VERSION_2}
```

**Run all checkpoints for one test method** (using wildcard):
```bash
mvn test -Dtest='Test{Feature}_ProcessBased#testMethod_*' \
  -{PROJECT_ARTIFACT}.start.home=/opt/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/opt/{PROJECT_ARTIFACT}-{VERSION_2}
```

**Run all baseline (NO_UPGRADE) tests**:
```bash
mvn test -Dtest='Test{Feature}_ProcessBased#*_NO_UPGRADE' \
  -{PROJECT_ARTIFACT}.start.home=/opt/{PROJECT_ARTIFACT}-{VERSION_1}
```

**Run all tests with specific checkpoint across all methods**:
```bash
mvn test -Dtest='Test{Feature}_ProcessBased#*_AFTER_CLUSTER_START' \
  -{PROJECT_ARTIFACT}.start.home=/opt/{PROJECT_ARTIFACT}-{VERSION_1} \
  -{PROJECT_ARTIFACT}.upgrade.home=/opt/{PROJECT_ARTIFACT}-{VERSION_2}
```

---

## When to Comment Out Logic

### Only comment out operations that are:

1. **Internal Storage Operations**
   - {INTERNAL_STORAGE_1} access
   - {INTERNAL_STORAGE_2} inspection
   - Storage directory verification

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

### Example: Internal Storage Check

```java
// TRANSFORMATION NOTE: Internal storage structure verification removed.
// The {INTERNAL_STORAGE_CLASS} class is internal to the {NODE_TYPE_1_NAME} process and not
// accessible via any client API. The original test verified {WHAT_WAS_VERIFIED}.
// No client-side alternative available - this verification requires direct access
// to the {NODE_TYPE_1_NAME}'s storage.
//
// Original code:
// {INTERNAL_STORAGE_TYPE} storage = cluster.get{NODE_TYPE_1_NAME}().{GET_STORAGE}();
// ... (verification code)
```

### When NOT to Comment Out

Do NOT comment out if there's a client-side equivalent:

❌ **WRONG**:
```java
// TRANSFORMATION NOTE: Cannot access {NODE_TYPE_1_NAME} directly
// Original code:
// boolean {STATE} = cluster.get{NODE_TYPE_1_NAME}().{GET_STATE}();
```

✅ **CORRECT**:
```java
// Use client API instead of direct node access
{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
boolean {STATE} = {CLIENT_VAR}.{GET_STATE}();
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

- [ ] **System properties ready**
  ```bash
  # System properties will be passed when running tests:
  mvn test -Dtest=MyTest \
    -{PROJECT_ARTIFACT}.start.home=/path/to/{PROJECT_ARTIFACT}-{VERSION_1} \
    -{PROJECT_ARTIFACT}.upgrade.home=/path/to/{PROJECT_ARTIFACT}-{VERSION_2}
  ```

- [ ] **Test compiles without errors**
  ```bash
  mvn test-compile -pl {PROJECT_ROOT}
  ```

- [ ] **Imports are correct**
  - {PROCESS_BASED_CLUSTER_CLASS} imported
  - Unnecessary node imports removed
  - Client API imports added

### During Test Execution

- [ ] **Cluster starts successfully**
  - Check logs for "Cluster started successfully"
  - Verify all nodes are up

- [ ] **Client accessible**
  - Can get client instance
  - Can perform basic operations

- [ ] **Core assertions pass**
  - Main test logic validates correctly
  - Data integrity checks pass

### After Test Execution

- [ ] **Test passes (or fails as expected)**
  - If original test passed, transformed test should pass
  - If failure, verify it's not due to transformation

- [ ] **Cluster cleans up properly**
  - No orphaned processes
  - Test directories cleaned up

- [ ] **Review transformation quality**
  - Maximum logic preserved?
  - Only necessary operations commented out?
  - Appropriate documentation added?

---

## Best Practices

### DO ✅

1. **Consult mapping tables first** - Before assuming something is unsupported, check all mapping tables

2. **Use the API hierarchy** - Try {HIGH_LEVEL_CLIENT_API} → {MID_LEVEL_CLIENT_API} → {LOW_LEVEL_RPC_API} → {ADMIN_TOOLS_API} → {MONITORING_API} in order

3. **Preserve test intent** - Even if implementation changes, maintain what the test is verifying

4. **System properties are automatic** - No manual environment checks needed!

5. **Keep transformations minimal** - Change only what's necessary

6. **Document significant changes** - But only non-obvious ones

7. **Test both single-version and multi-version scenarios** when applicable

8. **Verify node identity preservation** - Always check addresses/ports unchanged after restart/upgrade

9. **Capture identity snapshot before upgrades** - Store node addresses to verify preservation

### DON'T ❌

1. **Don't give up on transformation too early** - Most operations have client equivalents

2. **Don't remove test logic without checking mapping tables**

3. **Don't use {MINI_CLUSTER_CLASS}-specific test utilities** - Many have client API equivalents

4. **Don't over-document** - Only comment what's not obvious

5. **Don't mix {MINI_CLUSTER_CLASS} and {PROCESS_BASED_CLUSTER_CLASS}** in same test

6. **Don't manually check environment variables** - System properties are handled automatically!

### Performance Considerations

1. **Process startup is slower** - {PROCESS_BASED_CLUSTER_CLASS} takes longer to start than {MINI_CLUSTER_CLASS}
   - Be patient with cluster startup
   - Consider increasing timeouts for slow systems

2. **{HEARTBEAT_METHOD}s are real-time** - Can't artificially trigger them
   - Use `Thread.sleep()` or `GenericTestUtils.waitFor()`
   - Account for natural heartbeat intervals

3. **RPC overhead** - All operations go through RPC
   - Slightly slower than in-process calls
   - Not significant for most tests

---

## Quick Reference Decision Tree

```
Found server-side operation?
    │
    ├─> Is it already client-side? ({CLIENT_VAR}.{METHOD}())
    │   └─> ✅ Use as-is, no transformation needed
    │
    ├─> Check {SERVER_COMPONENT_1} table (Table 2)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use client API
    │   └─> Not found?
    │       └─> Continue...
    │
    ├─> Check {SERVER_COMPONENT_2} table (Table 3)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use client API
    │   └─> Not found?
    │       └─> Continue...
    │
    ├─> Check Admin operations table (Table 4)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use {ADMIN_TOOLS_API} or admin API
    │   └─> Not found?
    │       └─> Continue...
    │
    ├─> Check Monitoring table (Table 5)
    │   ├─> Found equivalent?
    │   │   └─> ✅ Use {MONITORING_API} or client statistics API
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
2. ✅ Runs with {PROCESS_BASED_CLUSTER_CLASS}
3. ✅ Preserves maximum test logic
4. ✅ Uses client APIs for all accessible operations
5. ✅ Comments out only truly inaccessible operations
6. ✅ Includes minimal, clear documentation
7. ✅ Passes when original test passed

### Key Takeaways

- **Most operations have client equivalents** - Consult mapping tables thoroughly
- **Use the API hierarchy** - {HIGH_LEVEL_CLIENT_API} → {MID_LEVEL_CLIENT_API} → {LOW_LEVEL_RPC_API} → {ADMIN_TOOLS_API} → {MONITORING_API}
- **Only comment out internal storage/JVM operations** - Everything else has an API
- **Document sparingly** - Only non-obvious transformations
- **Test thoroughly** - Verify core test logic preserved

---

## Placeholder Reference

**Replace these placeholders throughout this document and in your transformations:**

### Project/Cluster
- `{PROJECT_NAME}` - Full project name
- `{PROJECT_ARTIFACT}` - Artifact name (lowercase)
- `{MINI_CLUSTER_CLASS}` - Original cluster class
- `{PROCESS_BASED_CLUSTER_CLASS}` - New cluster class
- `{PROJECT_ROOT}` - Maven module path
- `{PACKAGE_PATH}` - Java package path

### Node Types
- `{NODE_TYPE_1_NAME}` - Display name (e.g., "Master")
- `{NODE_TYPE_1_CLASS}` - Java class name
- `{NODE_TYPE_2_NAME}` - Display name (e.g., "RegionServer")
- `{NODE_TYPE_2_CLASS}` - Java class name

### Client/API
- `{CLIENT_CLASS}` - Client class name
- `{CLIENT_VAR}` - Client variable name
- `{GET_CLIENT_METHOD}` - Method to get client
- `{HIGH_LEVEL_CLIENT_API}` - High-level API name
- `{MID_LEVEL_CLIENT_API}` - Mid-level API name
- `{LOW_LEVEL_RPC_API}` - Low-level RPC API name
- `{ADMIN_TOOLS_API}` - Admin tools API name
- `{MONITORING_API}` - Monitoring API name

### Server Components
- `{SERVER_COMPONENT_1}` - Primary server component class
- `{SERVER_COMPONENT_1_ROLE}` - Role description
- `{SERVER_COMPONENT_2}` - Secondary server component class
- `{SERVER_VAR}` - Server variable name

### Configuration
- `{CONFIG_CLASS}` - Configuration class name
- `{ENV_VAR_HOME}` - Environment variable for home
- `{ENV_VAR_UPGRADE_HOME}` - Environment variable for upgrade home

### Versions
- `{VERSION_1}` - First version number
- `{VERSION_2}` - Second version number

### Operations
- `{OPERATION_CATEGORY_1}`, `{OPERATION_CATEGORY_2}`, etc. - Operation categories
- `{METHOD_X}` - Server-side method names
- `{CLIENT_METHOD_X}` - Client-side method names
- `{HEARTBEAT_METHOD}` - Heartbeat method name
- `{HEARTBEAT_INTERVAL_MS}` - Heartbeat interval in milliseconds

### Testing
- `{INTERNAL_METHOD_PATTERN}` - Regex pattern for internal methods
- `{INTERNAL_STORAGE_CLASS}` - Internal storage class name
- `{GET_STORAGE}` - Method to get storage
- `{CHECK_CONDITION}` - Condition to check
- `{VERIFY_DATA}` - Data verification method

---

**End of Template**

This template provides comprehensive guidance for transforming tests from {MINI_CLUSTER_CLASS} to {PROCESS_BASED_CLUSTER_CLASS}. Fill in all placeholders and mapping tables to customize for your specific distributed system.
