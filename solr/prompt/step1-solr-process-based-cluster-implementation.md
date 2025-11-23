# Step 1: Process-Based Mini Cluster Implementation for Apache Solr

## Executive Summary

This document outlines the plan to create `ProcessBasedMiniSolrCloudCluster` that provides process-based testing capabilities for Apache Solr. Unlike the existing `MiniSolrCloudCluster` which runs all nodes in the same JVM, this implementation runs each Solr node in separate JVM processes, enabling testing of version compatibility and upgrade scenarios.

**Project Information:**
- **Project Name**: Apache Solr
- **Original Cluster Class**: `MiniSolrCloudCluster`
- **New Cluster Class**: `ProcessBasedMiniSolrCloudCluster`
- **Project Root**: `solr/test-framework`

**Node Architecture:**
- **Solr Node** - Class: `JettySolrRunner` - Role: Solr server node handling indexing, search, and replication (peer-to-peer architecture)
- **ZooKeeper** - External/shared instance with fixed version (not managed by ProcessBased cluster)

---

## Goals

1. **Process Isolation**: Each Solr node runs in its own JVM process
2. **Version Flexibility**: Support running different Solr versions for different nodes
3. **API Compatibility**: Provide similar API to `MiniSolrCloudCluster` where possible
4. **Client-Side Only**: Support only client-side operations (HTTP/SolrJ based)
5. **Testing Focus**: Enable version upgrade and compatibility testing
6. **Node Identity Persistence**: Nodes maintain same identity (address, port, configuration) across restarts and upgrades

## Non-Goals (Initial Phase)

- Performance optimization - correctness over speed
- Hot-swap/runtime version changes - versions set at cluster creation
- ZooKeeper version management - use external/shared ZooKeeper
- Cross-version internal state compatibility testing (may be future work)
- Components outside the core cluster (monitoring tools, external dependencies)

---

## Architecture Overview

### Current `MiniSolrCloudCluster` Architecture

```
┌─────────────────────────────────────────┐
│          Test JVM Process               │
│  ┌─────────────────────────────────┐   │
│  │     MiniSolrCloudCluster        │   │
│  │  ┌──────────┐  ┌──────────┐    │   │
│  │  │ Jetty 1  │  │ Jetty 2  │    │   │
│  │  │ (object) │  │ (object) │    │   │
│  │  └──────────┘  └──────────┘    │   │
│  │                                 │   │
│  │  Direct method calls possible   │   │
│  └─────────────────────────────────┘   │
│  Shared classpath & version            │
└─────────────────────────────────────────┘
```

### New `ProcessBasedMiniSolrCloudCluster` Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Test JVM Process                         │
│  ┌───────────────────────────────────────────────────┐     │
│  │     ProcessBasedMiniSolrCloudCluster              │     │
│  │                                                     │     │
│  │  ┌────────────────┐  ┌────────────────┐          │     │
│  │  │ Jetty 1 Proc   │  │ Jetty 2 Proc   │          │     │
│  │  │ Mgr (Control)  │  │ Mgr (Control)  │          │     │
│  │  └────────┬───────┘  └────────┬───────┘          │     │
│  │           │ HTTP                │ HTTP            │     │
│  └───────────┼─────────────────────┼─────────────────┘     │
│              │                     │                        │
└──────────────┼─────────────────────┼────────────────────────┘
               │                     │
       ┌───────▼────────┐    ┌──────▼─────────┐
       │  JettySolr 1   │    │  JettySolr 2   │
       │  Process       │    │  Process       │
       │                │    │                │
       │ Solr 9.8.0     │    │ Solr 9.9.0     │
       │ (Isolated CP)  │    │ (Isolated CP)  │
       └────────────────┘    └────────────────┘
```

**Key Components:**

1. **ProcessBasedMiniSolrCloudCluster**: Main cluster coordinator (in test JVM)
2. **Process Managers**: Start/stop/monitor individual Solr node processes
3. **Node Launchers**: Entry points for Solr node processes
4. **HTTP Clients**: Communicate with nodes via HTTP/SolrJ protocols
5. **Configuration Manager**: Generate and distribute configs per node
6. **Classpath Isolator**: Ensure each process uses correct Solr version
7. **Node Identity Manager**: Ensures nodes maintain consistent identity across restarts
   - Persists port allocations
   - Preserves node configurations
   - Maintains address consistency

---

## Detailed Design

### 1. Class Structure

#### 1.1 Main Classes

```
ProcessBasedMiniSolrCloudCluster (standalone implementation)
├── ProcessNodeManager
│   └── JettySolrRunnerProcessManager
├── SolrVersionRegistry
├── ProcessConfigurationGenerator
└── ProcessLauncher
    └── JettySolrRunnerProcessLauncher (Main class for subprocess)
```

#### 1.2 ProcessBasedMiniSolrCloudCluster

**Responsibilities:**
- HIGHEST PRIORITY: Provide similar API to `MiniSolrCloudCluster` where possible
- Follow the same creation/startup/shutdown patterns
- Manage lifecycle of all Solr node processes
- Provide client-side API access only (HTTP/SolrJ based)
- Throw UnsupportedOperationException for direct object access methods

**Builder Options:**
```java
Builder solrDistribution(int nodeIndex, String solrHome)
Builder allNodesSolrDistribution(String solrHome)
Builder enableProcessIsolation(boolean enable) // default: true
Builder numJettys(int num)  // Matches MiniSolrCloudCluster API
Builder jettyConfig(JettyConfig config)
```

**Unsupported Methods (will throw UnsupportedOperationException):**
```java
// Direct object access - not available in process-based cluster
getJettySolrRunners()                     // returns List<JettySolrRunner>
getJettySolrRunner(int index)             // returns JettySolrRunner object
```

**Supported Methods:**
```java
// Client-side operations
getSolrClient()                           // returns CloudSolrClient
getSolrClient(String collection)          // returns CloudSolrClient for collection
getZkClient()                             // returns SolrZkClient
getZkStateReader()                        // returns ZkStateReader
getZkServer()                             // returns ZkTestServer reference

// Node management (preserves identity on restart)
startJettySolrRunner()                    // start new node
startJettySolrRunner(JettySolrRunner jetty) // restart existing node
stopJettySolrRunner(int index)            // stop node by index
stopJettySolrRunner(JettySolrRunner jetty) // stop specific node

// Cluster operations
waitForAllNodes(int timeout)              // waits via ZK live nodes check
uploadConfigSet(Path configDir, String configName)
deleteAllCollections()
deleteAllConfigSets()
shutdown()                                // stops all processes

// Upgrade support
upgrade()                                 // performs rolling upgrade
changeJettyVersion(int index, String solrHome) // change version for specific node
```

#### 1.3 ProcessNodeManager

**Base class for managing Solr node processes:**

```java
abstract class ProcessNodeManager {
    protected Process process;
    protected Properties nodeProps;
    protected String solrHome;
    protected File workDir;
    protected int nodeIndex;
    protected int jettyPort;

    abstract void start() throws IOException;
    abstract void stop() throws IOException;
    abstract boolean isHealthy() throws IOException;
    abstract String getBaseUrl();

    protected void waitForProcessReady(long timeoutMs);
    protected void killProcess();
    protected List<String> buildClasspath();
}
```

**JettySolrRunnerProcessManager:**
- Starts JettySolrRunner process with isolated classpath
- Monitors health via HTTP ping
- Handles Solr-specific configuration
- Manages Jetty-specific ports (HTTP, SSL if configured)

#### 1.4 SolrVersionRegistry

**Manages Solr distribution locations:**

```java
class SolrVersionRegistry {
    private Map<String, SolrDistribution> distributions;

    void register(String version, String solrHome);
    SolrDistribution get(String version);
    List<File> getJars(String version);
    List<File> getDependencies(String version);
}

class SolrDistribution {
    String version;
    File solrHome;
    List<File> coreJars;      // solr-core, solr-solrj jars
    List<File> webappJars;    // from server/solr-webapp/webapp/WEB-INF/lib
    List<File> dependencies;   // all server/lib/*.jar
}
```

**Classpath Construction Strategy:**
```
For each Solr node process:
1. JVM bootstrap classpath (Java runtime)
2. Test framework jars (JUnit, shared test utilities)
3. Node-specific jars from solrHome/dist/
4. Node-specific webapp libs from solrHome/server/solr-webapp/webapp/WEB-INF/lib/
5. Node-specific server libs from solrHome/server/lib/
6. Configuration directory
```

**Dependency Hell Mitigation:**
- Each process gets completely isolated classpath
- No shared classes between processes (except JVM and test framework)
- Communication only via HTTP/SolrJ
- Use separate temp directories for each node
- Version-specific native libraries via java.library.path

#### 1.5 ProcessConfigurationGenerator

**Generates configuration files for each node:**

```java
class ProcessConfigurationGenerator {
    Properties generateNodeConfig(
        Properties baseProps,
        int nodeIndex,
        File workDir,
        int jettyPort);

    File writeSolrXmlToFile(String solrXml, File dir);
    File writePropertiesToFile(Properties props, File dir);
}
```

**Configuration File Strategy:**
- Only set necessary properties that are programmatically set by tests
- Each node gets a unique configuration directory
- Write solr.xml config files
- Auto-assign ports (scan for free ports)
- Set up isolated data directories
- Include version-specific configuration adjustments (8.x vs 9.x)

#### 1.6 ProcessLauncher (Entry Points)

**JettySolrRunnerProcessLauncher** (runs in subprocess):
```java
public class JettySolrRunnerProcessLauncher {
    public static void main(String[] args) {
        // Parse args: --solr-home, --node-index, --jetty-port, --zk-host, etc.
        // Load configuration
        // Start JettySolrRunner
        // Set up signal handlers
        // Wait/run indefinitely
    }
}
```

**Process Launch Command Example:**
```bash
java \
  -cp <solr-dist-jars>:<server-libs>:<webapp-libs> \
  -Djava.library.path=<solr-home>/server/lib/native \
  -Dsolr.install.dir=<solr-home> \
  org.apache.solr.cloud.ProcessLauncher \
  --solr-home /tmp/minisolr/jetty0 \
  --jetty-port 8983 \
  --zk-host localhost:9983 \
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
- **Port Persistence**: Use the same Jetty ports after restart/upgrade
- **Address Consistency**: Bind to the same network addresses
- **Configuration Preservation**: Maintain node-specific settings
- **Node Name Preservation**: Keep internal node identifiers consistent

1. **Validate distributions**
   - Check solrHome exists
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

3. **Start ZooKeeper** (if internal)
   - Start embedded ZkTestServer
   - Wait for ZK to be ready

4. **Start Solr nodes**
   - For each node: build classpath, start process
   - Wait for HTTP server to be ready
   - Perform health check
   - Wait for node to register in ZK

5. **Wait for cluster ready**
   - All nodes registered in ZK live_nodes
   - Cluster out of initialization state
   - Can perform basic operations (create collection)

#### 2.2 Process Health Monitoring

**Health Check Mechanisms:**
- **Process-level**: Check process.isAlive()
- **HTTP-level**: Ping /solr/admin/ping endpoint
- **ZK-level**: Check node in live_nodes
- **Functional**: Basic operations (create collection, query)

**Implementation:**
```java
class HealthMonitor {
    boolean checkSolrHealth(String baseUrl) {
        // Try HTTP ping to /solr/admin/ping
        HttpClient client = new HttpClient();
        HttpResponse response = client.get(baseUrl + "/solr/admin/ping");
        return response.status == 200;
    }

    boolean checkZkRegistration(String nodeName, ZkStateReader zkStateReader) {
        return zkStateReader.getClusterState().getLiveNodes().contains(nodeName);
    }
}
```

#### 2.3 Process Shutdown Sequence

1. **Graceful shutdown** (first attempt):
   - Send shutdown command via HTTP admin API if possible
   - Wait for process exit (with timeout)

2. **Force shutdown** (if graceful fails):
   - Send SIGTERM to process
   - Wait for exit (with timeout)

3. **Kill** (last resort):
   - Send SIGKILL
   - Clean up resources

4. **Cleanup**:
   - Delete temp directories (optional)
   - Close HTTP clients
   - Release ports

---

### 3. Dependency Management

#### 3.1 Classpath Isolation Strategy

**Challenge**: Different Solr versions have overlapping dependencies but potentially different versions (e.g., Guava, Jetty, Log4j2).

**Solution: Complete Process Isolation**
- Each node process has fully isolated classpath
- No class sharing except JVM and coordination mechanism
- Communication only via HTTP/SolrJ

**Classpath Construction:**
```java
List<File> buildClasspathForNode(SolrDistribution dist) {
    List<File> classpath = new ArrayList<>();

    // 1. Process launcher classes (minimal, from test classpath)
    classpath.add(findProcessLauncherJar());

    // 2. Core libraries from dist/
    classpath.addAll(findJars(dist.solrHome, "dist/solr-*.jar"));

    // 3. Server libraries
    classpath.addAll(findJars(dist.solrHome, "server/lib/*.jar"));
    classpath.addAll(findJars(dist.solrHome, "server/lib/ext/*.jar"));

    // 4. Webapp libraries
    classpath.addAll(findJars(dist.solrHome, "server/solr-webapp/webapp/WEB-INF/lib/*.jar"));

    // 5. Native library path
    nativeLibPath = new File(dist.solrHome, "server/lib/native");

    return classpath;
}
```

#### 3.2 Dependency Conflicts

**Known Issues:**
- **Guava**: Different versions between Solr 8.x and 9.x
- **Jetty**: Version 9 in Solr 8.x, Jetty 10 in Solr 9.x
- **Log4j**: Different versions and configurations
- **Jackson**: Different versions for JSON processing

**Mitigation:**
- Process isolation prevents conflicts
- Each process loads its own dependency versions
- No shared classloader between test JVM and node JVMs

**Testing:**
- Verify different dependency versions work in different nodes
- Test known version mismatch scenarios (8.x → 9.x)

---

### 4. Configuration Management

#### 4.1 Port Allocation and Persistence

**Strategy:**
- Use port range allocation (e.g., 50000-59999)
- Scan for free ports before assignment
- Track assigned ports to avoid conflicts
- **CRITICAL: Persist port allocations to disk for restarts/upgrades**

**Why Port Persistence is Critical:**

When a Solr node restarts or upgrades, it MUST use the same ports. If ports change:
- ❌ Other nodes think a NEW node joined the cluster
- ❌ Original node appears as "dead" or "decommissioned"
- ❌ Cluster may trigger unnecessary rebalancing
- ❌ Upgrade tests fail due to topology changes

**Per-Node Ports:**
- Jetty HTTP port (main Solr port)
- Jetty SSL port (if configured)
- Admin port (if separate)

**Implementation Pattern:**

```java
class PortAllocator {
    private Set<Integer> usedPorts;
    private int nextPort = 50000;
    private File persistenceFile;

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
        File portFile = new File(workDir, nodeId + "/ports.properties");
        if (!portFile.exists()) {
            return null;
        }
        Properties props = new Properties();
        props.load(new FileInputStream(portFile));
        return Integer.parseInt(props.getProperty("jetty.port"));
    }

    /**
     * Persist port allocation to survive restarts/upgrades.
     */
    void persistPort(String nodeId, int port) {
        File portFile = new File(workDir, nodeId + "/ports.properties");
        portFile.getParentFile().mkdirs();
        Properties props = new Properties();
        props.setProperty("jetty.port", String.valueOf(port));
        props.store(new FileOutputStream(portFile),
            "Port allocation for " + nodeId);
    }

    /**
     * Verify port is still available for reuse.
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
/tmp/process-minisolr-<timestamp>/
├── jetty0/
│   ├── ports.properties          # Persisted port allocations
│   │   # jetty.port=50001
│   ├── conf/
│   └── data/
├── jetty1/
│   ├── ports.properties
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
        "Node identity cannot be preserved.");
}
```

#### 4.2 Directory Structure

```
/tmp/process-minisolr-<timestamp>/
├── jetty0/
│   ├── ports.properties          # Persisted port allocations
│   ├── node-identity.properties  # Node ID and metadata
│   ├── conf/
│   │   ├── solr.xml
│   │   └── log4j2.xml
│   ├── data/
│   │   └── (Solr cores/collections)
│   ├── logs/
│   │   └── solr.log
│   └── pid
├── jetty1/
│   ├── ...
└── cluster.properties (cluster-wide config)
```

#### 4.3 Configuration File Generation

**Base Configuration Template:**

Example for solr.xml:
```xml
<solr>
  <str name="host">127.0.0.1</str>
  <int name="hostPort">${jetty.port}</int>
  <str name="hostContext">solr</str>
  <int name="zkClientTimeout">30000</int>
  <str name="coreRootDirectory">${solr.data.dir:}</str>

  <!-- Version-specific properties -->
</solr>
```

Example for system properties passed to JVM:
```
solr.data.dir=${work_dir}/data
solr.install.dir=${solr_home}
jetty.port=${allocated_port}
zkHost=${zk_connect_string}
```

**Version-Specific Adjustments:**
- Solr 8.x vs 9.x config key differences
- Deprecated property mappings
- Version-specific feature flags (e.g., Jetty 9 vs 10)

---

### 5. API Design

#### 5.1 Builder API

```java
ProcessBasedMiniSolrCloudCluster cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
    .numJettys(3)
    .jettyConfig(JettyConfig.builder().build())
    .allNodesSolrDistribution("/opt/solr-9.8.0")
    .build();
```

#### 5.2 Supported Operations

**Cluster Management:**
```java
// Startup/shutdown
cluster.waitForAllNodes(30);
cluster.shutdown();

// Process management (with identity preservation)
cluster.stopJettySolrRunner(0);
cluster.startJettySolrRunner(0);  // Preserves ports, address, config

// Status
String baseUrl = cluster.getJettyBaseUrl(0);

// Verify identity preservation
String url1 = cluster.getJettyBaseUrl(0);
cluster.stopJettySolrRunner(0);
cluster.startJettySolrRunner(0);
String url2 = cluster.getJettyBaseUrl(0);
assert url1.equals(url2) : "Node URL changed after restart!";
```

**Client Operations:**
```java
// Client access
CloudSolrClient client = cluster.getSolrClient();
CloudSolrClient collectionClient = cluster.getSolrClient("myCollection");

// ZooKeeper access
ZkStateReader zkStateReader = cluster.getZkStateReader();
SolrZkClient zkClient = cluster.getZkClient();

// Admin operations
cluster.uploadConfigSet(configPath, "myConfig");
cluster.deleteAllCollections();
```

#### 5.3 Unsupported Operations

Methods that require direct object access will throw:
```java
throw new UnsupportedOperationException(
    "Direct object access not supported in ProcessBasedMiniSolrCloudCluster. " +
    "This cluster runs nodes in separate processes. " +
    "Use client-side APIs instead.");
```

**List of Unsupported Methods:**
- `getJettySolrRunners()` - returns List<JettySolrRunner>
- `getJettySolrRunner(int)` - returns JettySolrRunner object
- Any method returning server-side internal objects

---

### 6. Version Upgrade Testing Support

#### 6.1 Upgrade Scenarios

**CRITICAL: Identity Preservation During Upgrades**

During rolling upgrades, each node MUST maintain its identity:

✅ **CORRECT Upgrade Flow:**
1. Shutdown Jetty(0) - Preserves ports.properties and configuration
2. Change version for Jetty(0) - Update software version
3. Start Jetty(0) - **Uses SAME ports, SAME address**
4. Other nodes detect: "Node 0 restarted with new version" ✓

❌ **INCORRECT Upgrade Flow:**
1. Shutdown Jetty(0)
2. Start Jetty(0) with NEW ports/address
3. Other nodes detect: "Node 0 died, new node joined" ✗
4. Cluster rebalances unnecessarily ✗

**Implementation Requirements:**
- `changeJettyVersion()` updates software path only
- Port allocations remain unchanged
- Configuration files preserve node identity
- Work directory persists across version change

**Supported Test Scenarios:**

1. **Rolling Upgrade - Minor Version (9.8 → 9.9)**
   ```java
   // Start with 9.8.0
   cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
       .numJettys(3)
       .allNodesSolrDistribution("/opt/solr-9.8.0")
       .build();

   // Upgrade to 9.9.0
   String upgradeHome = "/opt/solr-9.9.0";
   for (int i = 0; i < 3; i++) {
       cluster.stopJettySolrRunner(i);
       cluster.changeJettyVersion(i, upgradeHome);
       cluster.startJettySolrRunner(i);
       cluster.waitForAllNodes(30);
   }
   ```

2. **Rolling Upgrade - Major Version (8.x → 9.x)**
   ```java
   // Start with 8.11.0
   cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
       .numJettys(3)
       .allNodesSolrDistribution("/opt/solr-8.11.0")
       .build();

   // Upgrade to 9.0.0
   String upgradeHome = "/opt/solr-9.0.0";
   for (int i = 0; i < 3; i++) {
       cluster.stopJettySolrRunner(i);
       cluster.changeJettyVersion(i, upgradeHome);
       cluster.startJettySolrRunner(i);
       cluster.waitForAllNodes(60); // Longer timeout for major version
   }
   ```

3. **Mixed Version Cluster**
   ```java
   // Different versions on different nodes
   cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
       .numJettys(3)
       .solrDistribution(0, "/opt/solr-9.8.0")
       .solrDistribution(1, "/opt/solr-9.9.0")
       .solrDistribution(2, "/opt/solr-9.8.0")
       .build();
   ```

#### 6.2 Test Helpers

```java
class UpgradeTestHelper {
    void performRollingUpgrade(
        ProcessBasedMiniSolrCloudCluster cluster,
        String fromVersion,
        String toVersion);

    void verifyVersionCompatibility(
        String solrVersion1,
        String solrVersion2);

    void assertBasicOperationsWork(CloudSolrClient client);
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
- [ ] Create `JettySolrRunnerProcessManager`
  - [ ] Implement Solr-specific startup
  - [ ] Add HTTP health checks
- [ ] Create `ProcessLauncher` base class
  - [ ] Command-line argument parsing
  - [ ] Configuration loading
  - [ ] Logging setup

**Testing:**
- Unit test: Start/stop single Solr process
- Unit test: Process crash detection

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
├── ProcessNodeManager.java
├── JettySolrRunnerProcessManager.java
└── launcher/
    ├── ProcessLauncher.java
    └── JettySolrRunnerProcessLauncher.java
```

#### Task 1.2: Classpath & Version Management
**Priority**: P0
**Estimated Effort**: 3-4 days

**Subtasks:**
- [ ] Create `SolrDistribution` class
  - [ ] Parse Solr installation directory
  - [ ] Discover JAR files in dist/, server/lib/, webapp/lib/
  - [ ] Build classpath string
- [ ] Create `SolrVersionRegistry`
  - [ ] Register multiple distributions
  - [ ] Validate distribution completeness
- [ ] Implement classpath builder
  - [ ] Collect core JARs from dist/
  - [ ] Collect dependencies from server/lib/
  - [ ] Collect webapp JARs
  - [ ] Handle native libraries
- [ ] Test dependency isolation

**Testing:**
- Unit test: Parse Solr distribution
- Unit test: Build classpath for different versions
- Integration test: Start node with Solr 9.8.0
- Integration test: Start node with Solr 9.9.0

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
├── SolrDistribution.java
├── SolrVersionRegistry.java
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
  - [ ] **Persist port allocations to disk**
  - [ ] **Load persisted ports on restart**
  - [ ] **Validate port availability on restart**
- [ ] Create `DirectoryManager`
  - [ ] Set up temp directory structure
  - [ ] Create per-node subdirectories
  - [ ] Implement cleanup on shutdown

**Testing:**
- Unit test: Generate Solr configuration
- Unit test: Port allocation
- Unit test: Port persistence and restoration
- Unit test: Directory structure creation

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
├── ProcessConfigurationGenerator.java
├── PortAllocator.java
└── DirectoryManager.java
```

### Phase 2: ProcessBasedMiniSolrCloudCluster Implementation (Weeks 3-4)

#### Task 2.1: Main Cluster Class
**Priority**: P0
**Estimated Effort**: 4-5 days

**Subtasks:**
- [ ] Create `ProcessBasedMiniSolrCloudCluster` class
  - [ ] Implement Builder pattern (similar to MiniSolrCloudCluster)
  - [ ] Add solrDistribution() builder methods
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
  - [ ] getSolrClient()
  - [ ] getZkStateReader()
  - [ ] waitForAllNodes()
  - [ ] restart methods
- [ ] Throw UnsupportedOperationException for direct access methods

**Testing:**
- Integration test: Start single-node cluster
- Integration test: Start multi-node cluster
- Integration test: Restart nodes
- Integration test: Verify unsupported methods throw exceptions

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
├── ProcessBasedMiniSolrCloudCluster.java
└── integration/
    ├── TestProcessBasedMiniSolrCloudCluster.java
    └── TestProcessBasedMiniSolrCloudClusterAPI.java
```

#### Task 2.2: Health Monitoring & Retry Logic
**Priority**: P1
**Estimated Effort**: 2-3 days

**Subtasks:**
- [ ] Create `HealthMonitor` class
  - [ ] Check process alive status
  - [ ] Verify HTTP connectivity
  - [ ] Check ZK registration
  - [ ] Functional health checks
- [ ] Implement waitForNodeReady()
  - [ ] Wait for HTTP server
  - [ ] Retry with exponential backoff
  - [ ] Timeout handling
- [ ] Implement cluster readiness checks
  - [ ] All nodes healthy
  - [ ] All nodes in ZK live_nodes
  - [ ] Can perform basic operations

**Testing:**
- Unit test: Health check for healthy node
- Unit test: Health check for dead node
- Integration test: Wait for cluster ready
- Integration test: Handle node startup failures

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
└── HealthMonitor.java
```

### Phase 3: Multi-Version Support (Week 5)

#### Task 3.1: Version-Specific Configuration
**Priority**: P1
**Estimated Effort**: 2-3 days

**Subtasks:**
- [ ] Create `VersionConfigAdapter`
  - [ ] Map config keys between Solr 8.x and 9.x
  - [ ] Handle deprecated properties
  - [ ] Version-specific defaults (Jetty 9 vs 10)
- [ ] Test version compatibility
  - [ ] Solr 8.x <-> 9.x property mapping
  - [ ] Handle removed/renamed properties
- [ ] Test minor version compatibility (9.8 <-> 9.9)

**Testing:**
- Unit test: Config key mapping
- Integration test: Mixed-version cluster (8.x + 9.x)
- Integration test: Minor version mixed cluster

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
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
- Integration test: Rolling upgrade (9.8 → 9.9)
- Integration test: Major version upgrade (8.x → 9.x)
- Integration test: Version incompatibility detection

**Files to Create:**
```
solr/test-framework/src/test/java/org/apache/solr/cloud/process/upgrade/
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
solr/test-framework/src/test/java/org/apache/solr/cloud/process/
├── unit/
│   ├── TestProcessNodeManager.java
│   ├── TestSolrVersionRegistry.java
│   ├── TestConfigurationGenerator.java
│   └── TestPortAllocator.java
├── integration/
│   ├── TestProcessBasedMiniSolrCloudClusterBasics.java
│   ├── TestProcessBasedMiniSolrCloudClusterUpgrade.java
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
solr/test-framework/docs/
├── ProcessBasedMiniSolrCloudCluster-UserGuide.md
├── ProcessBasedMiniSolrCloudCluster-DeveloperGuide.md
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
   - Port persistence
   - Directory management
   - Version-specific config

3. **Classpath**
   - JAR discovery
   - Classpath construction
   - Dependency isolation

4. **Health Monitoring**
   - HTTP connectivity checks
   - ZK registration checks
   - Process health checks
   - Retry logic

### Integration Testing

**Test Environments:**
- Single node cluster
- Multi-node cluster (3 nodes)
- Mixed-version cluster

**Test Scenarios:**

1. **Basic Operations**
   ```java
   @Test
   public void testBasicOperations() {
       cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
           .numJettys(3)
           .allNodesSolrDistribution("/opt/solr-9.8.0")
           .build();

       CloudSolrClient client = cluster.getSolrClient();

       // Create collection, index, query
       CollectionAdminRequest.createCollection("test", "conf", 1, 3)
           .process(client);
   }
   ```

2. **Node Restart**
   ```java
   @Test
   public void testNodeRestart() {
       cluster.stopJettySolrRunner(0);
       cluster.startJettySolrRunner(0);
       cluster.waitForAllNodes(30);

       // Verify cluster still functional
   }
   ```

3. **Mixed Versions**
   ```java
   @Test
   public void testMixedVersionCluster() {
       cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
           .numJettys(3)
           .solrDistribution(0, "/opt/solr-9.8.0")
           .solrDistribution(1, "/opt/solr-9.9.0")
           .solrDistribution(2, "/opt/solr-9.8.0")
           .build();

       // Verify all nodes work together
   }
   ```

4. **Rolling Upgrade**
   ```java
   @Test
   public void testRollingUpgrade() {
       // Start cluster with 9.8.0
       cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
           .numJettys(3)
           .allNodesSolrDistribution("/opt/solr-9.8.0")
           .build();

       // Create collection and index data

       // Upgrade each node one by one
       String upgradeHome = "/opt/solr-9.9.0";
       for (int i = 0; i < 3; i++) {
           cluster.stopJettySolrRunner(i);
           cluster.changeJettyVersion(i, upgradeHome);
           cluster.startJettySolrRunner(i);
           cluster.waitForAllNodes(30);
           // Verify data still accessible
       }
   }
   ```

---

## Dependencies & Prerequisites

### Runtime Dependencies

1. **Multiple Solr Distributions**
   - User must provide built Solr installations
   - Suggested setup: `/opt/solr-9.8.0/`, `/opt/solr-9.9.0/`, etc.
   - Each installation must be complete (jars + dependencies)

2. **JDK 11+**
   - Solr 9.x requires Java 11+
   - Same JDK for all versions recommended

3. **Sufficient Resources**
   - Each process ~512MB heap
   - For 3-node cluster: ~2GB total

4. **Operating System**
   - Linux: Fully supported
   - macOS: Should work (test needed)
   - Windows: May have issues (lower priority)

### Build Dependencies

- Gradle (Solr build system)
- JUnit 4/5 for tests
- Mockito for unit tests

---

## Risk Assessment & Mitigation

### High Risk Items

#### Risk 1: Dependency Hell
**Description**: Different Solr versions have conflicting dependencies (Jetty 9 vs 10, different Guava versions)

**Impact**: High - Could prevent different versions from running together

**Probability**: Medium

**Mitigation**:
- Complete process isolation (separate JVMs)
- No shared classpath except JVM itself
- Extensive testing of 8.x + 9.x combinations

**Contingency**:
- If isolation fails, use Docker containers instead
- Fall back to version ranges (only test compatible versions)

#### Risk 2: Configuration Incompatibility
**Description**: Config keys/values differ significantly between Solr 8.x and 9.x

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
**Description**: HTTP/Solr protocol differences between versions

**Impact**: High - Nodes can't communicate

**Probability**: Low (Solr maintains good backward compatibility)

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
- [ ] Can start single Solr node process with specific version
- [ ] Process monitoring and health checks work
- [ ] Proper cleanup on shutdown
- [ ] Port allocation and persistence works

### Phase 2 Success Criteria
- [ ] Can start full 3-node cluster
- [ ] Can perform basic operations via CloudSolrClient
- [ ] Can restart nodes without cluster restart
- [ ] Unsupported methods throw clear exceptions

### Phase 3 Success Criteria
- [ ] Can run different nodes with different Solr versions
- [ ] Can perform rolling upgrade (9.8 → 9.9)
- [ ] Can perform major version upgrade (8.x → 9.x)
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
- Week 2: First Solr process started successfully
- Week 4: First full cluster test passes
- Week 5: First mixed-version test passes
- Week 6: Ready for code review

---

## Example Usage

```java
// Basic usage - all nodes same version
ProcessBasedMiniSolrCloudCluster cluster =
    new ProcessBasedMiniSolrCloudCluster.Builder()
        .numJettys(3)
        .allNodesSolrDistribution("/opt/solr-9.8.0")
        .build();

CloudSolrClient client = cluster.getSolrClient();
// Use client for testing...
cluster.shutdown();

// Mixed version usage
ProcessBasedMiniSolrCloudCluster mixedCluster =
    new ProcessBasedMiniSolrCloudCluster.Builder()
        .numJettys(3)
        .solrDistribution(0, "/opt/solr-9.8.0")
        .solrDistribution(1, "/opt/solr-9.9.0")
        .solrDistribution(2, "/opt/solr-9.8.0")
        .build();

// Upgrade scenario
cluster.stopJettySolrRunner(0);
cluster.changeJettyVersion(0, "/opt/solr-9.9.0");
cluster.startJettySolrRunner(0);
cluster.waitForAllNodes(30);
```

---

## Appendix: Solr-Specific Considerations

### Solr Architecture Notes

1. **Peer-to-Peer**: Unlike master-slave architectures, all Solr nodes are equal
2. **SolrCloud**: Uses ZooKeeper for coordination
3. **Collections & Shards**: Distributed indexing and search
4. **ConfigSets**: Shared configurations stored in ZooKeeper

### Version-Specific Considerations

**Solr 8.x:**
- Jetty 9
- Java 8+ required
- Traditional solr.xml format

**Solr 9.x:**
- Jetty 10 (major change)
- Java 11+ required
- Some config key changes
- Security improvements

### Testing Recommendations

1. **Start simple**: Single-node, single-version first
2. **Add complexity**: Multi-node, same version
3. **Mixed versions**: Different versions, same major
4. **Cross-major**: 8.x and 9.x together
5. **Rolling upgrade**: Full upgrade procedure

---

**End of Document**

This implementation plan provides a complete roadmap for creating ProcessBasedMiniSolrCloudCluster to enable comprehensive upgrade testing for Apache Solr.
