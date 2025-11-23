# ProcessBasedMiniSolrCloudCluster

Process-based Mini Solr Cloud Cluster for upgradable integration testing.

## Overview

This package provides a process-based alternative to `MiniSolrCloudCluster` that runs each Solr node in a separate JVM process with isolated classpath. This enables:

- **Multi-version testing**: Run different Solr versions (8.x, 9.x, etc.) in the same test
- **Rolling upgrades**: Upgrade nodes from one version to another while maintaining cluster availability
- **Node identity preservation**: Nodes maintain same ports and configuration across restarts
- **Complete process isolation**: No classpath conflicts between versions

## Architecture

### Core Components

1. **PortAllocator** - Manages port allocation with persistence to ensure nodes get the same ports across restarts
2. **DirectoryManager** - Manages temporary directory structure for cluster and node workspaces
3. **SolrDistribution** - Represents a Solr installation, discovers JARs from dist/, server/lib/, webapp/lib/
4. **SolrVersionRegistry** - Registry for managing multiple Solr distributions (start, upgrade versions)
5. **ClasspathBuilder** - Builds isolated classpaths for node processes
6. **ProcessConfigurationGenerator** - Generates node configurations, solr.xml, and identity files
7. **HealthMonitor** - HTTP-based health checks via /admin/ping endpoint
8. **ProcessNodeManager** - Abstract base class for process lifecycle management
9. **JettySolrRunnerProcessManager** - Concrete implementation for managing JettySolrRunner processes
10. **JettySolrRunnerProcessLauncher** - Entry point that runs in each subprocess
11. **ProcessBasedMiniSolrCloudCluster** - Main cluster orchestrator

### Directory Structure

```
/tmp/process-minisolr-{timestamp}/
├── ports/
│   └── port-allocations.properties  # Port persistence
├── jetty0/
│   ├── conf/
│   │   ├── node.properties
│   │   ├── solr.xml
│   │   └── node-identity.properties
│   ├── data/
│   ├── logs/
│   └── pid
├── jetty1/
│   └── ...
└── jetty2/
    └── ...
```

## Usage

### Basic Example

```java
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;

// Create and start a cluster
ProcessBasedMiniSolrCloudCluster cluster =
    new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersion("9.7.0", new File("/path/to/solr-9.7.0"))
        .build();

cluster.start();

// Use the cluster
CloudSolrClient client = cluster.getSolrClient();
// ... perform operations ...

// Shutdown
cluster.shutdown();
```

### Using System Properties

```java
// Set system properties:
// -Dsolr.start.home=/path/to/solr-9.7.0
// -Dsolr.upgrade.home=/path/to/solr-9.8.0

ProcessBasedMiniSolrCloudCluster cluster =
    new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(3)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();

cluster.start();
// ... use cluster at version 9.7.0 ...

cluster.upgrade();
// ... use cluster at version 9.8.0 ...

cluster.shutdown();
```

### Advanced Usage

```java
ProcessBasedMiniSolrCloudCluster cluster =
    new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(4)
        .withStartVersion("9.7.0", new File("/path/to/solr-9.7.0"))
        .withUpgradeVersion("9.8.0", new File("/path/to/solr-9.8.0"))
        .withBaseNodeProps(customProperties)
        .build();

cluster.start();

// Get node information
String nodeUrl = cluster.getJettySolrRunnerBaseUrl(0);
boolean healthy = cluster.isNodeHealthy(0);
String currentVersion = cluster.getCurrentVersion(); // "9.7.0"

// Restart a specific node
cluster.restartJettySolrRunner(0);

// Upgrade a single node
cluster.upgradeJettySolrRunner(0);

// Upgrade all nodes (rolling upgrade)
cluster.upgrade();

String newVersion = cluster.getCurrentVersion(); // "9.8.0"

// Access clients
CloudSolrClient solrClient = cluster.getSolrClient();
SolrZkClient zkClient = cluster.getZkClient();

cluster.shutdown();
```

## Testing

The test suite includes:

1. **Component Tests** - Test individual components without requiring Solr distributions:
   - Port allocation and persistence
   - Directory management
   - Version registry
   - Configuration generation
   - Builder validation

2. **Integration Tests** - Test full cluster lifecycle (requires Solr distributions):
   - Cluster creation and startup
   - Node health monitoring
   - Node restart
   - Rolling upgrades

### Running Tests

```bash
# Run all tests
./gradlew :solr:test-framework:test --tests ProcessBasedMiniSolrCloudClusterTest

# Run with Solr distributions for full integration test
./gradlew :solr:test-framework:test --tests ProcessBasedMiniSolrCloudClusterTest \
  -Dsolr.start.home=/path/to/solr-9.7.0 \
  -Dsolr.upgrade.home=/path/to/solr-9.8.0
```

## Key Features

### Port Persistence

Ports are allocated from range 50000-59999 and persisted to disk:

```properties
# ports/port-allocations.properties
jetty0=50001
jetty1=50002
jetty2=50003
```

This ensures nodes get the same ports across restarts, maintaining node identity in ZooKeeper.

### Process Isolation

Each node runs in a separate JVM with:
- Isolated classpath containing only its version's JARs
- Separate work directory (conf, data, logs)
- Independent JVM heap settings
- Separate process ID tracking

### Health Monitoring

Nodes are monitored via HTTP health checks:
- Endpoint: `http://127.0.0.1:{port}/solr/admin/ping`
- Timeout: 5 seconds connect + 5 seconds read
- Used during startup and upgrade to ensure cluster stability

### Rolling Upgrades

The upgrade process:
1. For each node (sequentially):
   - Stop the node process
   - Update distribution reference
   - Start node with new version's classpath
   - Wait for node to become healthy
   - Wait for cluster stability
2. All nodes upgraded with minimal downtime

## API Reference

### ProcessBasedMiniSolrCloudCluster

#### Lifecycle Methods

- `start()` - Start the cluster with initial version
- `shutdown()` - Shutdown cluster and cleanup resources
- `upgrade()` - Upgrade all nodes to upgrade version (rolling)
- `close()` - Alias for shutdown() (AutoCloseable)

#### Node Management

- `startJettySolrRunner(int nodeIndex)` - Start a specific node
- `stopJettySolrRunner(int nodeIndex)` - Stop a specific node
- `restartJettySolrRunner(int nodeIndex)` - Restart a node with current version
- `upgradeJettySolrRunner(int nodeIndex)` - Upgrade a specific node

#### Information Methods

- `getNodeCount()` - Get number of nodes
- `getCurrentVersion()` - Get current Solr version
- `getZkHost()` - Get ZooKeeper connection string
- `getSolrClient()` - Get CloudSolrClient
- `getZkClient()` - Get SolrZkClient
- `getZkServer()` - Get ZkTestServer
- `getJettySolrRunnerBaseUrl(int nodeIndex)` - Get node base URL
- `getAllNodeBaseUrls()` - Get all node URLs
- `isNodeHealthy(int nodeIndex)` - Check if node is healthy
- `getClusterDir()` - Get cluster work directory

#### Utility Methods

- `uploadConfigSet(Path configDir, String configName)` - Upload config set to ZooKeeper

### Builder

- `withNodeCount(int count)` - Set number of nodes (default: 1)
- `withStartVersion(String version, File solrHome)` - Set start version explicitly
- `withUpgradeVersion(String version, File solrHome)` - Set upgrade version explicitly
- `withStartVersionFromSystemProperty()` - Read start version from solr.start.home
- `withUpgradeVersionFromSystemProperty()` - Read upgrade version from solr.upgrade.home
- `withBaseNodeProps(Properties props)` - Set base properties for all nodes
- `build()` - Build the cluster instance

## Requirements

- Java 11+
- Built Solr distribution(s) with:
  - dist/ directory containing core JARs
  - server/lib/ directory containing server libraries
  - server/solr-webapp/webapp/WEB-INF/lib/ directory containing webapp JARs
- Available port range: 50000-59999
- Disk space for temporary directories

## Limitations

- Only supports JettySolrRunner nodes (no separate node types)
- Uses embedded ZooKeeper (not upgradable)
- Requires valid Solr distributions to be pre-built
- Port range is fixed (50000-59999)
- Currently designed for local testing only (127.0.0.1)

## Implementation Details

### Process Launch Command

Each node is launched with a command like:

```bash
java \
  -Xmx512m \
  -Xms256m \
  -XX:+UseG1GC \
  -Dsolr.install.dir=/path/to/solr \
  -Dsolr.data.dir=/tmp/cluster/jetty0/data \
  -Djetty.port=50001 \
  -DhostPort=50001 \
  -DzkHost=localhost:9983 \
  -Dsolr.log.dir=/tmp/cluster/jetty0/logs \
  -cp {isolated-classpath} \
  org.apache.solr.cloud.process.launcher.JettySolrRunnerProcessLauncher \
  --node-id jetty0 \
  --node-index 0 \
  --jetty-port 50001 \
  --zk-host localhost:9983 \
  --work-dir /tmp/cluster/jetty0 \
  --solr-home /path/to/solr
```

### Classpath Isolation

Each process classpath includes:
1. Test framework JARs (for launcher classes)
2. Configuration directory (for properties)
3. Distribution dist/ JARs
4. Distribution server/lib/ JARs
5. Distribution webapp WEB-INF/lib/ JARs

Elements are separated by platform path separator and completely isolated per process.

## Future Enhancements

Potential improvements:
- Support for external ZooKeeper clusters
- Configurable port ranges
- Support for SSL/TLS
- Support for authentication
- Metrics and monitoring integration
- Support for different JVM configurations per node
- Checkpoint-based testing integration
- Automated test transformation tools

## Related Documentation

- See `solr/prompt/step1-solr-process-based-cluster-implementation.md` for detailed implementation plan
- See `solr/prompt/step2-solr-upgrade-base-test-generation.md` for base test generation guide
- See `solr/prompt/step3-solr-test-transformation-guide.md` for test transformation guide
