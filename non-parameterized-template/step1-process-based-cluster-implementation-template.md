# Step 1: Process-Based Mini Cluster Implementation Template

## Template Instructions

This template guides you through implementing a process-based version of your existing mini cluster testing framework. Replace all placeholders marked with `{PLACEHOLDER}` with your project-specific values.

**Quick Start:**
1. Search for all `{PLACEHOLDER}` markers in this document
2. Replace each with your project-specific value
3. Use the resulting document as your implementation guide

---

## Executive Summary

This document outlines the plan to create `{PROCESS_BASED_CLUSTER_CLASS}` that extends the existing `{MINI_CLUSTER_CLASS}` functionality but runs each node in separate JVM processes. This enables testing version compatibility and upgrade scenarios by allowing different nodes to run different versions.

**Project Information:**
- **Project Name**: `{PROJECT_NAME}` (e.g., "Apache HBase", "Apache ZooKeeper")
- **Original Cluster Class**: `{MINI_CLUSTER_CLASS}` (e.g., "MiniHBaseCluster", "MiniZKCluster")
- **New Cluster Class**: `{PROCESS_BASED_CLUSTER_CLASS}` (e.g., "ProcessBasedMiniHBaseCluster")
- **Project Root**: `{PROJECT_ROOT}` (e.g., "hbase-server", "zookeeper-server")

**Node Types in Your Cluster:**
- **Node Type 1**: `{NODE_TYPE_1_NAME}` - Class: `{NODE_TYPE_1_CLASS}` - Role: `{NODE_TYPE_1_ROLE}`
- **Node Type 2**: `{NODE_TYPE_2_NAME}` - Class: `{NODE_TYPE_2_CLASS}` - Role: `{NODE_TYPE_2_ROLE}`
- *(Add more node types as needed)*

---

## Goals

1. **Process Isolation**: Each node runs in its own JVM process
2. **Version Flexibility**: Support running different versions for different nodes
3. **API Compatibility**: Extend existing `{MINI_CLUSTER_CLASS}` API where possible
4. **Client-Side Only**: Support only client-side operations (RPC/HTTP based)
5. **Testing Focus**: Enable version upgrade and compatibility testing
6. **Node Identity Persistence**: Nodes maintain same identity (address, port, configuration) across restarts and upgrades

## Non-Goals (Initial Phase)

- Performance optimization - correctness over speed
- Hot-swap/runtime version changes - versions set at cluster creation
- Cross-version internal state compatibility testing (may be future work)
- Components outside the core cluster (e.g., monitoring tools, external dependencies)

---

## Architecture Overview

### Current `{MINI_CLUSTER_CLASS}` Architecture

```
┌─────────────────────────────────────────┐
│          Test JVM Process               │
│  ┌─────────────────────────────────┐   │
│  │     {MINI_CLUSTER_CLASS}        │   │
│  │  ┌──────────┐  ┌──────────┐    │   │
│  │  │{NODE_1}  │  │{NODE_2}  │    │   │
│  │  │ (object) │  │ (object) │    │   │
│  │  └──────────┘  └──────────┘    │   │
│  │                                 │   │
│  │  Direct method calls possible   │   │
│  └─────────────────────────────────┘   │
│  Shared classpath & version            │
└─────────────────────────────────────────┘
```

### New `{PROCESS_BASED_CLUSTER_CLASS}` Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Test JVM Process                         │
│  ┌───────────────────────────────────────────────────┐     │
│  │     {PROCESS_BASED_CLUSTER_CLASS}                 │     │
│  │                                                     │     │
│  │  ┌────────────────┐  ┌────────────────┐          │     │
│  │  │ {NODE_1} Proc  │  │ {NODE_2} Proc  │          │     │
│  │  │ Mgr (Control)  │  │ Mgr (Control)  │          │     │
│  │  └────────┬───────┘  └────────┬───────┘          │     │
│  │           │ RPC                 │ RPC             │     │
│  └───────────┼─────────────────────┼─────────────────┘     │
│              │                     │                        │
└──────────────┼─────────────────────┼────────────────────────┘
               │                     │
       ┌───────▼────────┐    ┌──────▼─────────┐
       │  {NODE_1}      │    │  {NODE_2}      │
       │  Process       │    │  Process       │
       │                │    │                │
       │ Version 1.x    │    │ Version 2.x    │
       │ (Isolated CP)  │    │ (Isolated CP)  │
       └────────────────┘    └────────────────┘
```

**Key Components:**

1. **{PROCESS_BASED_CLUSTER_CLASS}**: Main cluster coordinator (in test JVM)
2. **Process Managers**: Start/stop/monitor individual node processes
3. **Node Launchers**: Entry points for node processes
4. **RPC Clients**: Communicate with nodes via RPC protocols
5. **Configuration Manager**: Generate and distribute configs per node
6. **Classpath Isolator**: Ensure each process uses correct version
7. **Node Identity Manager**: Ensures nodes maintain consistent identity across restarts
   - Persists port allocations
   - Preserves node configurations
   - Maintains address consistency

---

## Detailed Design

### 1. Class Structure

#### 1.1 Main Classes

```
{PROCESS_BASED_CLUSTER_CLASS} (extends {MINI_CLUSTER_CLASS})
├── ProcessNodeManager
│   ├── {NODE_TYPE_1_CLASS}ProcessManager
│   ├── {NODE_TYPE_2_CLASS}ProcessManager
│   └── ... (one manager per node type)
├── {PROJECT_NAME}VersionRegistry
├── ProcessConfigurationGenerator
└── ProcessLauncher
    ├── {NODE_TYPE_1_CLASS}ProcessLauncher (Main class for subprocess)
    ├── {NODE_TYPE_2_CLASS}ProcessLauncher (Main class for subprocess)
    └── ... (one launcher per node type)
```

#### 1.2 {PROCESS_BASED_CLUSTER_CLASS}

**Responsibilities:**
- HIGHEST PRIORITY: We MUST follow and transform `{MINI_CLUSTER_CLASS}` API to support / un-support methods as needed
- Follow the same creation/startup/shutdown patterns as `{MINI_CLUSTER_CLASS}`
- Manage lifecycle of all node processes
- Provide client-side API access only. If not possible, then those methods should tag as unsupported and throw `UnsupportedOperationException`.
- Throw UnsupportedOperationException for direct object access methods

**New Builder Options (If original builder exists, extend it):**
For example:
```java
Builder {PROJECT_ARTIFACT}Distribution(int nodeIndex, String {PROJECT_ARTIFACT}Home)
Builder allNodes{PROJECT_ARTIFACT}Distribution(String {PROJECT_ARTIFACT}Home)
Builder enableProcessIsolation(boolean enable) // default: true
```

**Unsupported Methods (will throw UnsupportedOperationException):**
For example:
```java
// List methods that return server-side objects
get{NODE_TYPE_1_NAME}(int index)           // returns {NODE_TYPE_1_CLASS} object
get{NODE_TYPE_2_NAME}(int index)           // returns {NODE_TYPE_2_CLASS} object
get{NODE_TYPE_1_NAME}s()                   // returns List<{NODE_TYPE_1_CLASS}>
// Add other methods that provide direct object access
```

**Supported Methods:**
For example: 
```java
// Client-side operations
{GET_CLIENT_METHOD}()                      // returns client object
getURI()                                   // returns cluster URI
get{NODE_TYPE_1_NAME}RpcAddress()          // returns InetSocketAddress
waitClusterUp()                            // waits via RPC health checks
shutdown()                                 // stops all processes
restart{NODE_TYPE_1_NAME}()                // stops and restarts process
restart{NODE_TYPE_2_NAME}()                // stops and restarts process
// Add other client-side methods
```

#### 1.3 ProcessNodeManager

**Base class for managing node processes:**

```java
abstract class ProcessNodeManager {
    protected Process process;
    protected Configuration nodeConfig;
    protected String {PROJECT_ARTIFACT}Home;
    protected File workDir;
    protected int nodeIndex;

    abstract void start() throws IOException;
    abstract void stop() throws IOException;
    abstract boolean isHealthy() throws IOException;
    abstract InetSocketAddress getRpcAddress();

    protected void waitForProcessReady(long timeoutMs);
    protected void killProcess();
    protected List<String> buildClasspath();
}
```

**{NODE_TYPE_1_CLASS}ProcessManager:**
- Starts {NODE_TYPE_1_NAME} process with isolated classpath
- Monitors health via RPC
- Handles node-specific configuration

**{NODE_TYPE_2_CLASS}ProcessManager:**
- Starts {NODE_TYPE_2_NAME} process with isolated classpath
- Monitors health via RPC
- Handles node-specific configuration

#### 1.4 {PROJECT_NAME}VersionRegistry

**Manages distribution locations:**

```java
class {PROJECT_NAME}VersionRegistry {
    private Map<String, {PROJECT_NAME}Distribution> distributions;

    void register(String version, String {PROJECT_ARTIFACT}Home);
    {PROJECT_NAME}Distribution get(String version);
    List<File> getJars(String version);
    List<File> getDependencies(String version);
}

class {PROJECT_NAME}Distribution {
    String version;
    File {PROJECT_ARTIFACT}Home;
    List<File> coreJars;      // {PROJECT_ARTIFACT}-core, {PROJECT_ARTIFACT}-client jars
    List<File> dependencies;   // all lib/*.jar
}
```

**Classpath Construction Strategy:**
```
For each node process:
1. JVM bootstrap classpath (Java runtime)
2. Test framework jars (JUnit, shared test utilities)
3. Node-specific jars from {PROJECT_ARTIFACT}Home/{JAR_LOCATION}/
4. Node-specific dependencies from {PROJECT_ARTIFACT}Home/{LIB_LOCATION}/
5. Configuration directory
```

**Dependency Hell Mitigation:**
- Each process gets completely isolated classpath
- No shared classes between processes (except JVM and test framework)
- Communication only via RPC/sockets
- Use separate temp directories for each node
- Version-specific native libraries via java.library.path

#### 1.5 ProcessConfigurationGenerator

**Generates configuration files for each node:**

```java
class ProcessConfigurationGenerator {
    Configuration generate{NODE_TYPE_1_NAME}Config(
        Configuration baseConfig,
        int nodeIndex,
        File workDir);

    Configuration generate{NODE_TYPE_2_NAME}Config(
        Configuration baseConfig,
        int nodeIndex,
        File workDir,
        List<InetSocketAddress> dependencies);

    File writeConfigToFile(Configuration conf, File dir);
}
```

**Configuration File Strategy:**
- Only set necessary properties that are programmatically set by tests rather than write full config to config files
- Each node gets a unique configuration directory
- Write {CONFIG_FILE_FORMAT} config files (e.g., XML, properties, YAML)
- Auto-assign ports (scan for free ports)
- Set up isolated data directories
- Include version-specific configuration adjustments

#### 1.6 ProcessLauncher (Entry Points)

**{NODE_TYPE_1_CLASS}ProcessLauncher** (runs in subprocess):
```java
public class {NODE_TYPE_1_CLASS}ProcessLauncher {
    public static void main(String[] args) {
        // Parse args: --config-dir, --node-index, etc.
        // Load configuration
        // Start {NODE_TYPE_1_CLASS}
        // Set up signal handlers
        // Wait/run indefinitely
    }
}
```

**{NODE_TYPE_2_CLASS}ProcessLauncher** (runs in subprocess):
```java
public class {NODE_TYPE_2_CLASS}ProcessLauncher {
    public static void main(String[] args) {
        // Parse args: --config-dir, --node-index, etc.
        // Load configuration
        // Start {NODE_TYPE_2_CLASS}
        // Set up signal handlers
        // Wait/run indefinitely
    }
}
```

**Process Launch Command Example:**
```bash
java \
  -cp {CLASSPATH_PATTERN} \
  -Djava.library.path={NATIVE_LIB_PATH} \
  {LAUNCHER_CLASS_FQN} \
  --config-dir /tmp/minicluster/{NODE_DIR}/conf \
  --node-index 0
```

---

### 2. Process Management

#### 2.1 Process Startup Sequence

**CRITICAL REQUIREMENT: Node Identity Persistence**

When restarting or upgrading a node, it MUST preserve its identity so that:
- Other nodes recognize it as the SAME node restarting (not a new node joining)
- Cluster topology remains stable
- No unnecessary rebalancing or failover occurs

This requires:
- **Port Persistence**: Use the same ports after restart/upgrade
- **Address Consistency**: Bind to the same network addresses
- **Configuration Preservation**: Maintain node-specific settings
- **Node ID Preservation**: Keep internal node identifiers consistent

1. **Validate distributions**
   - Check {PROJECT_ARTIFACT}Home exists
   - Verify required jars present
   - Validate versions if specified

2. **Generate configurations** (First start only)
   - Create work directories
   - Generate config files
   - Allocate ports and PERSIST to disk

2b. **Restore configurations** (Restart/upgrade)
   - Load persisted port allocations
   - Restore node-specific configurations
   - Verify address bindings available

3. **Start {NODE_TYPE_1_NAME}s**
   - For each node: build classpath, start process
   - Wait for RPC server to be ready
   - Perform health check

4. **Start {NODE_TYPE_2_NAME}s** (and other node types)
   - For each node: build classpath, start process
   - Wait for registration/connection
   - Perform health check

5. **Wait for cluster ready**
   - All nodes registered/connected
   - Cluster out of initialization state
   - Can perform basic operations

#### 2.2 Process Health Monitoring

**Health Check Mechanisms:**
- **Process-level**: Check process.isAlive()
- **RPC-level**: Periodic health check RPC calls
- **Functional**: Basic operations (e.g., write/read test data)

**Implementation:**
```java
class HealthMonitor {
    boolean check{NODE_TYPE_1_NAME}Health(InetSocketAddress addr) {
        // Try {HEALTH_CHECK_METHOD}
    }

    boolean check{NODE_TYPE_2_NAME}Health(InetSocketAddress addr) {
        // Check if node is responsive
    }
}
```

#### 2.3 Process Shutdown Sequence

1. **Graceful shutdown** (first attempt):
   - Send shutdown command via RPC if possible
   - Wait for process exit (with timeout)

2. **Force shutdown** (if graceful fails):
   - Send SIGTERM to process
   - Wait for exit (with timeout)

3. **Kill** (last resort):
   - Send SIGKILL
   - Clean up resources

4. **Cleanup**:
   - Delete temp directories (optional)
   - Close RPC clients
   - Release ports

---

### 3. Dependency Management

#### 3.1 Classpath Isolation Strategy

**Challenge**: Different versions have overlapping dependencies but potentially different versions (e.g., Guava, Protobuf, Log4j).

**Solution: Complete Process Isolation**
- Each node process has fully isolated classpath
- No class sharing except JVM and coordination mechanism
- Communication only via RPC/sockets

**Classpath Construction:**
```java
List<File> buildClasspathForNode({PROJECT_NAME}Distribution dist) {
    List<File> classpath = new ArrayList<>();

    // 1. Process launcher classes (minimal, from test classpath)
    classpath.add(findProcessLauncherJar());

    // 2. Core libraries
    classpath.addAll(findJars(dist.{PROJECT_ARTIFACT}Home, "{CORE_JAR_PATH}"));
    classpath.addAll(findJars(dist.{PROJECT_ARTIFACT}Home, "{CORE_LIB_PATH}"));

    // 3. Component-specific libraries (if applicable)
    classpath.addAll(findJars(dist.{PROJECT_ARTIFACT}Home, "{COMPONENT_JAR_PATH}"));
    classpath.addAll(findJars(dist.{PROJECT_ARTIFACT}Home, "{COMPONENT_LIB_PATH}"));

    // 4. Native library path
    nativeLibPath = new File(dist.{PROJECT_ARTIFACT}Home, "{NATIVE_LIB_PATH}");

    return classpath;
}
```

#### 3.2 Dependency Conflicts

**Known Issues** (customize for your project):
- **Guava**: Different versions across major releases
- **Protobuf**: Version incompatibilities
- **Log4j**: Different versions and configurations
- **{DEPENDENCY_1}**: {DESCRIPTION_OF_ISSUE}
- **{DEPENDENCY_2}**: {DESCRIPTION_OF_ISSUE}

**Mitigation:**
- Process isolation prevents conflicts
- Each process loads its own dependency versions
- No shared classloader between test JVM and node JVMs

**Testing:**
- Verify different dependency versions work in different nodes
- Test known version mismatch scenarios

---

### 4. Configuration Management

#### 4.1 Port Allocation and Persistence

**Strategy:**
- Use port range allocation (e.g., 50000-59999)
- Scan for free ports before assignment
- Track assigned ports to avoid conflicts
- **CRITICAL: Persist port allocations to disk for restarts/upgrades**

**Why Port Persistence is Critical:**

When a node restarts or upgrades, it MUST use the same ports. If ports change:
- ❌ Other nodes think a NEW node joined the cluster
- ❌ Original node appears as "dead" or "decommissioned"
- ❌ Cluster may trigger unnecessary rebalancing
- ❌ Upgrade tests fail due to topology changes

**Per-Node Ports:**
- {NODE_TYPE_1_NAME}: {LIST_OF_PORTS} (e.g., RPC port, HTTP port, service port)
- {NODE_TYPE_2_NAME}: {LIST_OF_PORTS} (e.g., data port, IPC port, HTTP port)

**Implementation Pattern:**

```java
class PortAllocator {
    private Set<Integer> usedPorts;
    private int nextPort = 50000;
    private File persistenceFile;  // NEW: Port persistence

    /**
     * Allocate port for first-time node startup.
     * Port is persisted to disk for future restarts.
     */
    int allocatePort(String nodeId) {
        // Check if port already allocated for this node
        Integer existingPort = loadPersistedPort(nodeId);
        if (existingPort != null && isPortAvailable(existingPort)) {
            usedPorts.add(existingPort);
            return existingPort;
        }

        // Allocate new port
        while (usedPorts.contains(nextPort) || !isPortAvailable(nextPort)) {
            nextPort++;
        }
        usedPorts.add(nextPort);

        // CRITICAL: Persist port allocation
        persistPort(nodeId, nextPort);

        return nextPort++;
    }

    /**
     * Load persisted port for node restart/upgrade.
     * Returns null if this is first startup.
     */
    Integer loadPersistedPort(String nodeId) {
        // Read from {NODE_DIR}/ports.properties
        File portFile = new File(workDir, nodeId + "/ports.properties");
        if (!portFile.exists()) {
            return null;
        }
        Properties props = new Properties();
        props.load(new FileInputStream(portFile));
        return Integer.parseInt(props.getProperty("rpc.port"));
    }

    /**
     * Persist port allocation to survive restarts/upgrades.
     */
    void persistPort(String nodeId, int port) {
        File portFile = new File(workDir, nodeId + "/ports.properties");
        portFile.getParentFile().mkdirs();
        Properties props = new Properties();
        props.setProperty("rpc.port", String.valueOf(port));
        props.setProperty("http.port", String.valueOf(port + 1));
        // ... persist all ports
        props.store(new FileOutputStream(portFile),
            "Port allocation for " + nodeId);
    }

    /**
     * Verify port is still available for reuse.
     * If not available, this is a critical error - cannot change ports!
     */
    boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
```

**Port Persistence File Structure:**

```
/tmp/process-minicluster-<timestamp>/
├── {NODE_TYPE_1_DIR_PREFIX}0/
│   ├── ports.properties          # NEW: Persisted port allocations
│   │   # rpc.port=50001
│   │   # http.port=50002
│   │   # service.port=50003
│   ├── conf/
│   └── data/
├── {NODE_TYPE_2_DIR_PREFIX}0/
│   ├── ports.properties          # NEW: Persisted port allocations
│   └── ...
```

**Restart/Upgrade Flow:**

1. **Shutdown node** - Process stops, but port files remain
2. **Load persisted ports** - Read ports.properties
3. **Verify ports available** - Critical: ports MUST be free
4. **Start with same ports** - Node appears as "same node restarting"
5. **Other nodes detect** - "Node X is back online" (not "New node joined")

**Error Handling:**

If persisted port is not available during restart:
```java
if (!isPortAvailable(persistedPort)) {
    throw new RuntimeException(
        "Cannot restart node " + nodeId + ": " +
        "Port " + persistedPort + " is in use. " +
        "Node identity cannot be preserved. " +
        "Other nodes will see this as a new node, not a restart.");
}
```

#### 4.2 Directory Structure

```
/tmp/process-minicluster-<timestamp>/
├── {NODE_TYPE_1_DIR_PREFIX}0/
│   ├── ports.properties          # NEW: Persisted port allocations
│   ├── node-identity.properties  # NEW: Node ID and metadata
│   ├── conf/
│   │   ├── {CONFIG_FILE_1}
│   │   ├── {CONFIG_FILE_2}
│   │   └── {LOG_CONFIG_FILE}
│   ├── data/
│   │   ├── current/
│   │   └── in_use.lock
│   ├── logs/
│   │   └── {NODE_TYPE_1_NAME}.log
│   └── pid
├── {NODE_TYPE_1_DIR_PREFIX}1/  (if multiple instances)
├── {NODE_TYPE_2_DIR_PREFIX}0/
│   ├── conf/
│   ├── data/
│   ├── logs/
│   └── pid
├── {NODE_TYPE_2_DIR_PREFIX}1/
└── cluster.properties (cluster-wide config)
```

#### 4.3 Configuration File Generation

**Base Configuration Template:**
```
{SHOW_EXAMPLE_CONFIG_FORMAT}

Example for XML-based config:
<configuration>
  <property>
    <name>{CONFIG_KEY_RPC_ADDRESS}</name>
    <value>localhost:${allocated_port}</value>
  </property>
  <property>
    <name>{CONFIG_KEY_DATA_DIR}</name>
    <value>file://${work_dir}/data</value>
  </property>
  <!-- Version-specific properties -->
</configuration>

Example for properties-based config:
{CONFIG_KEY_RPC_ADDRESS}=localhost:${allocated_port}
{CONFIG_KEY_DATA_DIR}=${work_dir}/data

Example for YAML-based config:
{CONFIG_KEY_RPC_ADDRESS}: "localhost:${allocated_port}"
{CONFIG_KEY_DATA_DIR}: "${work_dir}/data"
```

**Version-Specific Adjustments:**
- Version 1.x vs 2.x config key differences
- Deprecated property mappings
- Version-specific feature flags

---

### 5. API Design

#### 5.1 Builder API

```java
{PROCESS_BASED_CLUSTER_CLASS} cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
    .num{NODE_TYPE_2_NAME}s(3)
    .{NODE_TYPE_1_NAME}{PROJECT_NAME}Distribution("{VERSION_1_PATH}")
    .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(0, "{VERSION_2_PATH}")
    .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(1, "{VERSION_3_PATH}")
    .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(2, "{VERSION_2_PATH}")
    .format(true)
    .build();
```

#### 5.2 Supported Operations

**Cluster Management:**
```java
// Startup/shutdown
cluster.start();
cluster.shutdown();
cluster.waitClusterUp();

// Process management (with identity preservation)
cluster.restart{NODE_TYPE_1_NAME}(0);    // Preserves ports, address, config
cluster.restart{NODE_TYPE_2_NAME}(0);    // Preserves ports, address, config
cluster.shutdown{NODE_TYPE_1_NAME}(0);
cluster.shutdown{NODE_TYPE_2_NAME}(0);

// Status
cluster.isClusterUp();
cluster.get{NODE_TYPE_1_NAME}Address(0);
cluster.get{NODE_TYPE_2_NAME}Address(0);

// Verify identity preservation
InetSocketAddress addr1 = cluster.get{NODE_TYPE_2_NAME}Address(0);
cluster.restart{NODE_TYPE_2_NAME}(0);
InetSocketAddress addr2 = cluster.get{NODE_TYPE_2_NAME}Address(0);
assert addr1.equals(addr2) : "Node address changed after restart!";
```

**Client Operations:**
```java
// Client access
{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
URI uri = cluster.getURI();

// Client configuration
Configuration clientConf = cluster.getClientConfiguration();
```

#### 5.3 Unsupported Operations

Methods that require direct object access will throw:
```java
throw new UnsupportedOperationException(
    "Direct object access not supported in {PROCESS_BASED_CLUSTER_CLASS}. " +
    "This cluster runs nodes in separate processes. " +
    "Use client-side APIs instead.");
```

**List of Unsupported Methods** (customize for your cluster):
- `get{NODE_TYPE_1_NAME}(int)` - returns {NODE_TYPE_1_CLASS} object
- `get{NODE_TYPE_2_NAME}(int)` - returns {NODE_TYPE_2_CLASS} object
- `get{NODE_TYPE_2_NAME}s()` - returns node list
- `{INTERNAL_STATE_METHOD_1}` - requires direct object access
- `{INTERNAL_STATE_METHOD_2}` - requires direct object access
- Any other method returning server-side objects

---

### 6. Version Upgrade Testing Support

#### 6.1 Upgrade Scenarios

**CRITICAL: Identity Preservation During Upgrades**

During rolling upgrades, each node MUST maintain its identity:

✅ **CORRECT Upgrade Flow:**
1. Shutdown {NODE_TYPE_2_NAME}(0) - Preserves ports.properties and configuration
2. Change version for {NODE_TYPE_2_NAME}(0) - Update software version
3. Start {NODE_TYPE_2_NAME}(0) - **Uses SAME ports, SAME address**
4. Other nodes detect: "Node 0 restarted with new version" ✓

❌ **INCORRECT Upgrade Flow:**
1. Shutdown {NODE_TYPE_2_NAME}(0)
2. Start {NODE_TYPE_2_NAME}(0) with NEW ports/address
3. Other nodes detect: "Node 0 died, new node joined" ✗
4. Cluster rebalances unnecessarily ✗
5. Upgrade test fails ✗

**Implementation Requirements:**
- `change{NODE_TYPE_2_NAME}Version()` updates software path only
- Port allocations remain unchanged
- Configuration files preserve node identity
- Work directory persists across version change

**Supported Test Scenarios:**

1. **Rolling Upgrade - {NODE_TYPE_2_NAME}s**
   ```java
   // Start with version 1.x
   cluster.{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(0, "{VERSION_1_PATH}");
   cluster.build();

   // Upgrade to version 2.x
   cluster.shutdown{NODE_TYPE_2_NAME}(0);
   cluster.change{NODE_TYPE_2_NAME}Version(0, "{VERSION_2_PATH}");
   cluster.start{NODE_TYPE_2_NAME}(0);
   ```

2. **{NODE_TYPE_1_NAME} Upgrade** (if applicable - e.g., for HA clusters)
   ```java
   // Upgrade standby to version 2.x
   // Perform failover
   // Upgrade old active
   ```

3. **Mixed Version Cluster**
   ```java
   // Different versions on different nodes
   // Test compatibility matrix
   ```

#### 6.2 Test Helpers

```java
class UpgradeTestHelper {
    void performRollingUpgrade(
        {PROCESS_BASED_CLUSTER_CLASS} cluster,
        String fromVersion,
        String toVersion);

    void verifyVersionCompatibility(
        String {NODE_TYPE_1_NAME}Version,
        String {NODE_TYPE_2_NAME}Version);

    void assert{BASIC_OPERATION}Works({CLIENT_CLASS} {CLIENT_VAR});
}
```

---

## Implementation Plan

### Phase 1: Core Infrastructure (Weeks 1-2)

#### Task 1.1: Process Management Foundation
**Priority**: P0
**Estimated Effort**: 3-4 days

**Subtasks:**
- [ ] Create `ProcessNodeManager` base class
  - [ ] Implement start(), stop(), isAlive() methods
  - [ ] Add process monitoring thread
  - [ ] Handle process crashes and restarts
- [ ] Create `{NODE_TYPE_1_CLASS}ProcessManager`
  - [ ] Implement node-specific startup
  - [ ] Add RPC health checks
- [ ] Create `{NODE_TYPE_2_CLASS}ProcessManager`
  - [ ] Implement node-specific startup
  - [ ] Add health monitoring
- [ ] Create `ProcessLauncher` base class
  - [ ] Command-line argument parsing
  - [ ] Configuration loading
  - [ ] Logging setup

**Testing:**
- Unit test: Start/stop single {NODE_TYPE_1_NAME} process
- Unit test: Start/stop single {NODE_TYPE_2_NAME} process
- Unit test: Process crash detection

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
├── ProcessNodeManager.java
├── {NODE_TYPE_1_CLASS}ProcessManager.java
├── {NODE_TYPE_2_CLASS}ProcessManager.java
└── launcher/
    ├── ProcessLauncher.java
    ├── {NODE_TYPE_1_CLASS}ProcessLauncher.java
    └── {NODE_TYPE_2_CLASS}ProcessLauncher.java
```

#### Task 1.2: Classpath & Version Management
**Priority**: P0
**Estimated Effort**: 3-4 days

**Subtasks:**
- [ ] Create `{PROJECT_NAME}Distribution` class
  - [ ] Parse installation directory
  - [ ] Discover JAR files
  - [ ] Build classpath string
- [ ] Create `{PROJECT_NAME}VersionRegistry`
  - [ ] Register multiple distributions
  - [ ] Validate distribution completeness
- [ ] Implement classpath builder
  - [ ] Collect core JARs
  - [ ] Collect dependencies from lib/
  - [ ] Handle native libraries
- [ ] Test dependency isolation

**Testing:**
- Unit test: Parse distribution
- Unit test: Build classpath for different versions
- Integration test: Start node with Version 1.x
- Integration test: Start node with Version 2.x

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
├── {PROJECT_NAME}Distribution.java
├── {PROJECT_NAME}VersionRegistry.java
└── ClasspathBuilder.java
```

#### Task 1.3: Configuration Management
**Priority**: P0
**Estimated Effort**: 2-3 days

**Subtasks:**
- [ ] Create `ProcessConfigurationGenerator`
  - [ ] Generate per-node config files
  - [ ] Write config files to disk
- [ ] Create `PortAllocator`
  - [ ] Scan for available ports
  - [ ] Track allocated ports
  - [ ] Handle allocation failures
  - [ ] **Persist port allocations to disk**
  - [ ] **Load persisted ports on restart**
  - [ ] **Validate port availability on restart**
- [ ] Create `DirectoryManager`
  - [ ] Set up temp directory structure
  - [ ] Create per-node subdirectories
  - [ ] Implement cleanup on shutdown

**Testing:**
- Unit test: Generate {NODE_TYPE_1_NAME} configuration
- Unit test: Generate {NODE_TYPE_2_NAME} configuration
- Unit test: Port allocation
- Unit test: Directory structure creation

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
├── ProcessConfigurationGenerator.java
├── PortAllocator.java
└── DirectoryManager.java
```

### Phase 2: {PROCESS_BASED_CLUSTER_CLASS} Implementation (Weeks 3-4)

#### Task 2.1: Main Cluster Class
**Priority**: P0
**Estimated Effort**: 4-5 days

**Subtasks:**
- [ ] Create `{PROCESS_BASED_CLUSTER_CLASS}` class
  - [ ] Extend/implement {MINI_CLUSTER_CLASS} interface
  - [ ] Implement Builder pattern
  - [ ] Add {PROJECT_ARTIFACT}Distribution() builder methods
- [ ] Implement cluster startup sequence
  - [ ] Validate distributions
  - [ ] Generate configurations
  - [ ] Start nodes in correct order
  - [ ] Wait for cluster ready
- [ ] Implement cluster shutdown sequence
  - [ ] Graceful shutdown
  - [ ] Force shutdown
  - [ ] Cleanup resources
- [ ] Implement supported methods
  - [ ] {GET_CLIENT_METHOD}()
  - [ ] getURI()
  - [ ] waitClusterUp()
  - [ ] restart methods
- [ ] Throw UnsupportedOperationException for direct access methods

**Testing:**
- Integration test: Start single-node cluster
- Integration test: Start multi-node cluster
- Integration test: Restart nodes
- Integration test: Verify unsupported methods throw exceptions

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
├── {PROCESS_BASED_CLUSTER_CLASS}.java
└── integration/
    ├── Test{PROCESS_BASED_CLUSTER_CLASS}.java
    └── Test{PROCESS_BASED_CLUSTER_CLASS}API.java
```

#### Task 2.2: Health Monitoring & Retry Logic
**Priority**: P1
**Estimated Effort**: 2-3 days

**Subtasks:**
- [ ] Create `HealthMonitor` class
  - [ ] Check process alive status
  - [ ] Verify RPC connectivity
  - [ ] Functional health checks
- [ ] Implement waitForNodeReady()
  - [ ] Wait for RPC server
  - [ ] Retry with exponential backoff
  - [ ] Timeout handling
- [ ] Implement cluster readiness checks
  - [ ] All nodes healthy
  - [ ] Out of initialization state
  - [ ] Can perform basic operations

**Testing:**
- Unit test: Health check for healthy node
- Unit test: Health check for dead node
- Integration test: Wait for cluster ready
- Integration test: Handle node startup failures

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
└── HealthMonitor.java
```

### Phase 3: Multi-Version Support (Week 5)

#### Task 3.1: Version-Specific Configuration
**Priority**: P1
**Estimated Effort**: 2-3 days

**Subtasks:**
- [ ] Create `VersionConfigAdapter`
  - [ ] Map config keys between versions
  - [ ] Handle deprecated properties
  - [ ] Version-specific defaults
- [ ] Test version compatibility
  - [ ] Version 1.x <-> Version 2.x property mapping
  - [ ] Handle removed/renamed properties
- [ ] Test minor version compatibility

**Testing:**
- Unit test: Config key mapping
- Integration test: Mixed-version cluster

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
└── VersionConfigAdapter.java
```

#### Task 3.2: Mixed-Version Cluster Testing
**Priority**: P1
**Estimated Effort**: 2-3 days

**Subtasks:**
- [ ] Create test matrix of version combinations
  - [ ] Document supported combinations
  - [ ] Test known compatible versions
  - [ ] Identify incompatible combinations
- [ ] Implement version upgrade test helpers
  - [ ] Rolling upgrade helper
  - [ ] Compatibility verification
- [ ] Create example upgrade tests

**Testing:**
- Integration test: All nodes different versions
- Integration test: Rolling upgrade
- Integration test: Version incompatibility detection

**Files to Create:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/upgrade/
├── TestMixedVersionCluster.java
├── TestRollingUpgrade.java
└── UpgradeTestHelper.java
```

### Phase 4: Testing & Documentation (Week 6)

#### Task 4.1: Comprehensive Test Suite
**Priority**: P0
**Estimated Effort**: 3-4 days

**Subtasks:**
- [ ] Unit tests for all components
- [ ] Integration tests
- [ ] Stress tests
- [ ] Compatibility tests

**Test Categories:**
```
{PROJECT_ROOT}/src/test/java/{PACKAGE_PATH}/process/
├── unit/
│   ├── TestProcessNodeManager.java
│   ├── Test{PROJECT_NAME}VersionRegistry.java
│   ├── TestConfigurationGenerator.java
│   └── TestPortAllocator.java
├── integration/
│   ├── Test{PROCESS_BASED_CLUSTER_CLASS}Basics.java
│   ├── Test{PROCESS_BASED_CLUSTER_CLASS}Failover.java
│   ├── Test{PROCESS_BASED_CLUSTER_CLASS}Upgrade.java
│   └── TestMixedVersionOperations.java
└── compatibility/
    └── TestAPICompatibility.java
```

#### Task 4.2: Documentation
**Priority**: P1
**Estimated Effort**: 2 days

**Subtasks:**
- [ ] Create user guide
- [ ] Create developer guide
- [ ] Update project documentation
- [ ] Create JavaDoc

**Documentation Files:**
```
{PROJECT_ROOT}/docs/
├── {PROCESS_BASED_CLUSTER_CLASS}-UserGuide.md
├── {PROCESS_BASED_CLUSTER_CLASS}-DeveloperGuide.md
└── VersionUpgradeTestingGuide.md
```

---

## Testing Strategy

### Unit Testing

**Coverage Goals**: >80% line coverage for all new classes

**Test Categories:**

1. **Process Management**
   - Start/stop processes
   - Process crash detection
   - Graceful vs force shutdown
   - PID tracking

2. **Configuration**
   - Config file generation
   - Port allocation
   - Directory management
   - Version-specific config

3. **Classpath**
   - JAR discovery
   - Classpath construction
   - Dependency isolation

4. **Health Monitoring**
   - RPC connectivity checks
   - Process health checks
   - Retry logic

### Integration Testing

**Test Environments:**
- Single node cluster
- Multi-node cluster
- High availability cluster (if applicable)
- Mixed-version cluster

**Test Scenarios:**

1. **Basic Operations**
   ```java
   @Test
   public void testBasicOperations() {
       cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
           .num{NODE_TYPE_2_NAME}s(3)
           .allNodes{PROJECT_NAME}Distribution("{VERSION_PATH}")
           .build();

       {CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();

       // Perform basic operations
       {EXAMPLE_BASIC_OPERATION}
   }
   ```

2. **Node Restart**
   ```java
   @Test
   public void testNodeRestart() {
       cluster.restart{NODE_TYPE_2_NAME}(0);
       cluster.waitClusterUp();

       // Verify cluster still functional
   }
   ```

3. **Mixed Versions**
   ```java
   @Test
   public void testMixedVersionCluster() {
       cluster = new {PROCESS_BASED_CLUSTER_CLASS}.Builder(conf)
           .num{NODE_TYPE_2_NAME}s(3)
           .{NODE_TYPE_1_NAME}{PROJECT_NAME}Distribution("{VERSION_1_PATH}")
           .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(0, "{VERSION_2_PATH}")
           .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(1, "{VERSION_3_PATH}")
           .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(2, "{VERSION_2_PATH}")
           .build();

       // Verify all nodes work together
   }
   ```

4. **Rolling Upgrade**
   ```java
   @Test
   public void testRollingUpgrade() {
       // Start cluster with version 1.x
       cluster = builder
           .allNodes{PROJECT_NAME}Distribution("{VERSION_1_PATH}")
           .build();

       // Write test data

       // Upgrade each node one by one
       for (int i = 0; i < 3; i++) {
           cluster.shutdown{NODE_TYPE_2_NAME}(i);
           cluster.change{NODE_TYPE_2_NAME}Version(i, "{VERSION_2_PATH}");
           cluster.start{NODE_TYPE_2_NAME}(i);
           cluster.waitClusterUp();
           // Verify data still accessible
       }
   }
   ```

---

## Dependencies & Prerequisites

### Runtime Dependencies

1. **Multiple {PROJECT_NAME} Distributions**
   - User must provide built installations
   - Suggested setup: `/opt/{PROJECT_ARTIFACT}-1.x/`, `/opt/{PROJECT_ARTIFACT}-2.x/`, etc.
   - Each installation must be complete (jars + dependencies)

2. **JDK 8+**
   - Same JDK for all versions recommended

3. **Sufficient Resources**
   - Each process ~{MEMORY_PER_PROCESS} heap
   - For typical cluster: ~{TOTAL_MEMORY} total

4. **Operating System**
   - Linux: Fully supported
   - macOS: Should work (test needed)
   - Windows: May have issues (lower priority)

### Build Dependencies

- Maven 3.3+
- JUnit 4/5 for tests
- Mockito for unit tests

---

## Risk Assessment & Mitigation

### High Risk Items

#### Risk 1: Dependency Hell
**Description**: Different versions have conflicting dependencies

**Impact**: High - Could prevent different versions from running together

**Probability**: Medium

**Mitigation**:
- Complete process isolation (separate JVMs)
- No shared classpath except JVM itself
- Extensive testing of known problematic dependencies

**Contingency**:
- If isolation fails, use Docker containers instead
- Fall back to version ranges (only test compatible versions)

#### Risk 2: Configuration Incompatibility
**Description**: Config keys/values differ between versions

**Impact**: High - Nodes won't start with wrong config

**Probability**: Medium

**Mitigation**:
- Version-aware config generation
- Test with actual distributions
- Document known incompatibilities

**Contingency**:
- Provide manual config override mechanism
- Version-specific config templates

#### Risk 3: Protocol Incompatibility
**Description**: RPC protocols differ between versions

**Impact**: High - Nodes can't communicate

**Probability**: {LOW/MEDIUM/HIGH}

**Mitigation**:
- Test with known compatible versions first
- Document minimum compatible versions
- Check compatibility matrix

**Contingency**:
- Limit support to compatible version ranges
- Provide clear error messages

---

## Success Criteria

### Phase 1 Success Criteria
- [ ] Can start single {NODE_TYPE_1_NAME} process with specific version
- [ ] Can start single {NODE_TYPE_2_NAME} process with specific version
- [ ] Process monitoring and health checks work
- [ ] Proper cleanup on shutdown

### Phase 2 Success Criteria
- [ ] Can start full cluster
- [ ] Can perform basic operations via client API
- [ ] Can restart nodes without cluster restart
- [ ] Unsupported methods throw clear exceptions

### Phase 3 Success Criteria
- [ ] Can run different nodes with different versions
- [ ] Can perform rolling upgrade
- [ ] Version compatibility matrix documented and tested

### Final Success Criteria
- [ ] All unit tests pass (>80% coverage)
- [ ] All integration tests pass
- [ ] At least 5 version combination tests pass
- [ ] Documentation complete
- [ ] Zero known critical bugs
- [ ] Can run at least one rolling upgrade scenario end-to-end

---

## Timeline

**Total Estimated Duration**: 6 weeks

| Phase | Duration | End Date |
|-------|----------|----------|
| Phase 1: Core Infrastructure | 2 weeks | Week 2 |
| Phase 2: Main Implementation | 2 weeks | Week 4 |
| Phase 3: Multi-Version Support | 1 week | Week 5 |
| Phase 4: Testing & Documentation | 1 week | Week 6 |

**Milestones:**
- Week 2: First process started successfully
- Week 4: First full cluster test passes
- Week 5: First mixed-version test passes
- Week 6: Ready for code review

---

## Example Usage

```java
// Basic usage - all nodes same version
{PROCESS_BASED_CLUSTER_CLASS} cluster =
    new {PROCESS_BASED_CLUSTER_CLASS}.Builder(new Configuration())
        .num{NODE_TYPE_2_NAME}s(3)
        .allNodes{PROJECT_NAME}Distribution("{VERSION_1_PATH}")
        .format(true)
        .build();

{CLIENT_CLASS} {CLIENT_VAR} = cluster.{GET_CLIENT_METHOD}();
// Use client for testing...
cluster.shutdown();

// Mixed version usage
{PROCESS_BASED_CLUSTER_CLASS} mixedCluster =
    new {PROCESS_BASED_CLUSTER_CLASS}.Builder(new Configuration())
        .num{NODE_TYPE_2_NAME}s(3)
        .{NODE_TYPE_1_NAME}{PROJECT_NAME}Distribution("{VERSION_1_PATH}")
        .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(0, "{VERSION_2_PATH}")
        .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(1, "{VERSION_3_PATH}")
        .{NODE_TYPE_2_NAME}{PROJECT_NAME}Distribution(2, "{VERSION_2_PATH}")
        .format(true)
        .build();

// Upgrade scenario
cluster.shutdown{NODE_TYPE_2_NAME}(0);
cluster.change{NODE_TYPE_2_NAME}Version(0, "{VERSION_3_PATH}");
cluster.start{NODE_TYPE_2_NAME}(0);
cluster.waitClusterUp();
```

---

## Placeholder Reference

**Replace these placeholders throughout this document:**

### Project/Cluster
- `{PROJECT_NAME}` - Full project name (e.g., "Apache HBase")
- `{PROJECT_ARTIFACT}` - Artifact name (e.g., "hbase", "zookeeper")
- `{MINI_CLUSTER_CLASS}` - Original cluster class (e.g., "MiniHBaseCluster")
- `{PROCESS_BASED_CLUSTER_CLASS}` - New cluster class (e.g., "ProcessBasedMiniHBaseCluster")
- `{PROJECT_ROOT}` - Maven module path (e.g., "hbase-server")

### Node Types (add more as needed)
- `{NODE_TYPE_1_NAME}` - Display name (e.g., "Master", "NameNode")
- `{NODE_TYPE_1_CLASS}` - Java class (e.g., "HMaster", "NameNode")
- `{NODE_TYPE_1_ROLE}` - Role description (e.g., "Cluster coordinator")
- `{NODE_TYPE_1_DIR_PREFIX}` - Directory prefix (e.g., "master", "nn")
- `{NODE_TYPE_2_NAME}` - Display name (e.g., "RegionServer", "DataNode")
- `{NODE_TYPE_2_CLASS}` - Java class (e.g., "HRegionServer", "DataNode")
- `{NODE_TYPE_2_ROLE}` - Role description (e.g., "Data storage node")
- `{NODE_TYPE_2_DIR_PREFIX}` - Directory prefix (e.g., "rs", "dn")

### Client/API
- `{CLIENT_CLASS}` - Client class (e.g., "Connection", "DistributedFileSystem")
- `{CLIENT_VAR}` - Client variable name (e.g., "conn", "fs")
- `{GET_CLIENT_METHOD}` - Method to get client (e.g., "getConnection", "getFileSystem")
- `{CLIENT_PROTOCOL}` - RPC protocol interface (e.g., "AdminProtos.AdminService")

### Configuration
- `{CONFIG_CLASS}` - Configuration class (e.g., "Configuration", "HBaseConfiguration")
- `{CONFIG_FILE_1}` - Config file name (e.g., "hbase-site.xml", "zoo.cfg")
- `{CONFIG_FILE_2}` - Additional config file (e.g., "core-site.xml")
- `{CONFIG_FILE_FORMAT}` - Format (e.g., "XML", "properties", "YAML")
- `{LOG_CONFIG_FILE}` - Log config file (e.g., "log4j.properties")
- `{CONFIG_KEY_RPC_ADDRESS}` - RPC address config key
- `{CONFIG_KEY_DATA_DIR}` - Data directory config key

### Paths
- `{PACKAGE_PATH}` - Java package path (e.g., "org/apache/hadoop/hdfs")
- `{JAR_LOCATION}` - JAR directory (e.g., "lib", "share/hadoop/hdfs")
- `{LIB_LOCATION}` - Lib directory (e.g., "lib", "share/hadoop/hdfs/lib")
- `{CORE_JAR_PATH}` - Core JARs path
- `{CORE_LIB_PATH}` - Core libs path
- `{COMPONENT_JAR_PATH}` - Component JARs path (if applicable)
- `{COMPONENT_LIB_PATH}` - Component libs path (if applicable)
- `{NATIVE_LIB_PATH}` - Native library path (e.g., "lib/native")

### Versions
- `{VERSION_1_PATH}` - Path to version 1 installation
- `{VERSION_2_PATH}` - Path to version 2 installation
- `{VERSION_3_PATH}` - Path to version 3 installation

### Operations
- `{HEALTH_CHECK_METHOD}` - Health check method (e.g., "getClusterStatus", "getFileInfo")
- `{EXAMPLE_BASIC_OPERATION}` - Example operation for testing
- `{BASIC_OPERATION}` - Generic operation name

### Resources
- `{MEMORY_PER_PROCESS}` - Memory per process (e.g., "512MB")
- `{TOTAL_MEMORY}` - Total memory needed (e.g., "2GB")

### Dependencies
- `{DEPENDENCY_1}`, `{DEPENDENCY_2}` - Known problematic dependencies
- `{DESCRIPTION_OF_ISSUE}` - Description of dependency issue

### Miscellaneous
- `{LIST_OF_PORTS}` - Ports used by node type
- `{SHOW_EXAMPLE_CONFIG_FORMAT}` - Show config file format example
- `{LAUNCHER_CLASS_FQN}` - Fully qualified launcher class name
- `{CLASSPATH_PATTERN}` - Classpath pattern for launch command

---

**End of Template**

This template provides a comprehensive guide for implementing a process-based mini cluster for your distributed system. Customize all placeholders to match your project's specifics.
