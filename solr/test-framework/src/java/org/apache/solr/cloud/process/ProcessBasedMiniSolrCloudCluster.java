/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud.process;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider;
import org.apache.solr.cloud.ZkConfigSetService;
import org.apache.solr.cloud.ZkTestServer;
import org.apache.solr.common.cloud.SolrZkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-based Mini Solr Cloud Cluster for upgradable integration testing.
 *
 * <p>This cluster runs each Solr node in a separate JVM process with isolated classpath, enabling:
 *
 * <ul>
 *   <li>Multi-version testing (8.x, 9.x, etc.)
 *   <li>Rolling upgrades between versions
 *   <li>Node identity preservation across restarts
 *   <li>Complete process isolation
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 * ProcessBasedMiniSolrCloudCluster cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
 *     .withNodeCount(3)
 *     .withStartVersion("9.7.0")
 *     .withUpgradeVersion("9.8.0")
 *     .build();
 *
 * cluster.start();
 * // ... run tests ...
 * cluster.upgrade();
 * // ... run upgrade tests ...
 * cluster.shutdown();
 * </pre>
 */
public class ProcessBasedMiniSolrCloudCluster implements AutoCloseable {
  private static final Logger log =
      LoggerFactory.getLogger(ProcessBasedMiniSolrCloudCluster.class);

  private final int nodeCount;
  private final SolrVersionRegistry versionRegistry;
  private final DirectoryManager directoryManager;
  private final PortAllocator portAllocator;
  private final ZkTestServer zkTestServer;
  private final Properties baseNodeProps;

  private final Map<String, SolrProcessManager> nodes;
  private SolrDistribution currentDistribution;
  private CloudSolrClient solrClient;
  private SolrZkClient zkClient;

  private volatile boolean started = false;

  /**
   * Create a ProcessBasedMiniSolrCloudCluster.
   *
   * @param builder Builder with configuration
   */
  private ProcessBasedMiniSolrCloudCluster(Builder builder) throws Exception {
    this.nodeCount = builder.nodeCount;
    this.versionRegistry = builder.versionRegistry;
    this.baseNodeProps = builder.baseNodeProps != null ? builder.baseNodeProps : new Properties();
    this.nodes = new HashMap<>();

    // Create directory manager
    this.directoryManager = new DirectoryManager();

    // Create port allocator with persistence directory
    File portPersistDir = new File(directoryManager.getClusterDir(), "ports");
    this.portAllocator = new PortAllocator(portPersistDir);

    // Start embedded ZooKeeper
    log.info("Starting embedded ZooKeeper for cluster");
    this.zkTestServer = new ZkTestServer(directoryManager.getClusterDir().toPath());
    this.zkTestServer.run();

    log.info("Created ProcessBasedMiniSolrCloudCluster with {} nodes", nodeCount);
  }

  /**
   * Start the cluster with the initial version.
   *
   * @throws Exception if startup fails
   */
  public void start() throws Exception {
    if (started) {
      log.warn("Cluster already started");
      return;
    }

    log.info("Starting ProcessBasedMiniSolrCloudCluster with {} nodes", nodeCount);

    // Get start version distribution
    this.currentDistribution = versionRegistry.getDistribution("start");
    if (currentDistribution == null) {
      throw new IllegalStateException("Start version distribution not configured");
    }

    log.info(
        "Starting cluster with Solr version {}", currentDistribution.getVersion());

    // Start all nodes
    for (int i = 0; i < nodeCount; i++) {
      startJettySolrRunner(i);
    }

    // Initialize clients
    initializeClients();

    started = true;
    log.info("ProcessBasedMiniSolrCloudCluster started successfully");
  }

  /**
   * Start a specific Jetty node.
   *
   * @param nodeIndex Node index (0-based)
   * @return The node ID
   * @throws IOException if startup fails
   */
  public String startJettySolrRunner(int nodeIndex) throws IOException {
    String nodeId = "jetty" + nodeIndex;

    if (nodes.containsKey(nodeId)) {
      log.warn("Node {} already exists, stopping it first", nodeId);
      stopJettySolrRunner(nodeIndex);
    }

    log.info("Starting node {} with distribution {}", nodeId, currentDistribution.getVersion());

    // Allocate port for this node
    int jettyPort = portAllocator.allocatePort(nodeId);

    // Create work directory
    File workDir = directoryManager.createNodeDir(nodeId);

    // Create process manager
    SolrProcessManager manager =
        new SolrProcessManager(
            nodeId,
            nodeIndex,
            currentDistribution,
            workDir,
            jettyPort,
            getZkHost(),
            baseNodeProps);

    // Start the process
    manager.start();

    // Track the node
    nodes.put(nodeId, manager);

    log.info("Started node {} on port {}", nodeId, jettyPort);
    return nodeId;
  }

  /**
   * Stop a specific Jetty node.
   *
   * @param nodeIndex Node index (0-based)
   * @throws IOException if shutdown fails
   */
  public void stopJettySolrRunner(int nodeIndex) throws IOException {
    String nodeId = "jetty" + nodeIndex;
    SolrProcessManager manager = nodes.get(nodeId);

    if (manager == null) {
      log.warn("Node {} not found", nodeId);
      return;
    }

    log.info("Stopping node {}", nodeId);
    manager.stop();
    nodes.remove(nodeId);
    log.info("Stopped node {}", nodeId);
  }

  /**
   * Restart a specific Jetty node with the current distribution.
   *
   * @param nodeIndex Node index (0-based)
   * @throws IOException if restart fails
   */
  public void restartJettySolrRunner(int nodeIndex) throws IOException {
    log.info("Restarting node jetty{}", nodeIndex);
    stopJettySolrRunner(nodeIndex);
    startJettySolrRunner(nodeIndex);
  }

  /**
   * Upgrade all nodes to the upgrade version using a rolling upgrade strategy.
   *
   * @throws Exception if upgrade fails
   */
  public void upgrade() throws Exception {
    log.info("Starting cluster upgrade");

    // Get upgrade distribution
    SolrDistribution upgradeDistribution = versionRegistry.getDistribution("upgrade");
    if (upgradeDistribution == null) {
      throw new IllegalStateException("Upgrade version distribution not configured");
    }

    log.info(
        "Upgrading cluster from {} to {}",
        currentDistribution.getVersion(),
        upgradeDistribution.getVersion());

    // Update current distribution
    this.currentDistribution = upgradeDistribution;

    // Perform rolling upgrade - restart each node one at a time
    for (int i = 0; i < nodeCount; i++) {
      log.info("Upgrading node jetty{} to version {}", i, upgradeDistribution.getVersion());

      restartJettySolrRunner(i);

      // Wait for cluster to stabilize
      waitForClusterStability();

      log.info("Node jetty{} upgraded successfully", i);
    }

    log.info("Cluster upgrade completed successfully");
  }

  /**
   * Upgrade a single node to the upgrade version.
   *
   * @param nodeIndex Node index (0-based)
   * @throws Exception if upgrade fails
   */
  public void upgradeJettySolrRunner(int nodeIndex) throws Exception {
    log.info("Upgrading node jetty{}", nodeIndex);

    // Get upgrade distribution
    SolrDistribution upgradeDistribution = versionRegistry.getDistribution("upgrade");
    if (upgradeDistribution == null) {
      throw new IllegalStateException("Upgrade version distribution not configured");
    }

    // Update current distribution for this node
    this.currentDistribution = upgradeDistribution;

    // Restart the node
    restartJettySolrRunner(nodeIndex);

    log.info("Node jetty{} upgraded to version {}", nodeIndex, upgradeDistribution.getVersion());
  }

  /**
   * Shutdown the cluster.
   *
   * @throws Exception if shutdown fails
   */
  public void shutdown() throws Exception {
    log.info("Shutting down ProcessBasedMiniSolrCloudCluster");

    // Close clients
    if (solrClient != null) {
      try {
        solrClient.close();
      } catch (IOException e) {
        log.error("Error closing SolrClient", e);
      }
      solrClient = null;
    }

    if (zkClient != null) {
      zkClient.close();
      zkClient = null;
    }

    // Stop all nodes
    List<String> nodeIds = new ArrayList<>(nodes.keySet());
    for (String nodeId : nodeIds) {
      SolrProcessManager manager = nodes.get(nodeId);
      try {
        manager.stop();
      } catch (IOException e) {
        log.error("Error stopping node {}", nodeId, e);
      }
    }
    nodes.clear();

    // Stop ZooKeeper
    if (zkTestServer != null) {
      try {
        zkTestServer.shutdown();
      } catch (Exception e) {
        log.error("Error shutting down ZooKeeper", e);
      }
    }

    // Clean up directories
    directoryManager.cleanup();

    started = false;
    log.info("ProcessBasedMiniSolrCloudCluster shutdown complete");
  }

  @Override
  public void close() {
    try {
      shutdown();
    } catch (Exception e) {
      log.error("Error during close()", e);
      // Rethrow as RuntimeException to avoid AutoCloseable signature issues
      throw new RuntimeException("Failed to close ProcessBasedMiniSolrCloudCluster", e);
    }
  }

  /**
   * Initialize Solr and ZooKeeper clients.
   *
   * @throws Exception if initialization fails
   */
  private void initializeClients() throws Exception {
    String zkHost = getZkHost();

    // Create ZooKeeper client
    this.zkClient =
        new SolrZkClient.Builder()
            .withUrl(zkHost)
            .withTimeout(30000, TimeUnit.MILLISECONDS)
            .build();

    // Create Solr client
    this.solrClient =
        new CloudSolrClient.Builder(List.of(zkHost), java.util.Optional.empty()).build();

    log.info("Initialized clients with ZK host: {}", zkHost);
  }

  /**
   * Wait for the cluster to stabilize after changes.
   *
   * <p>This is a simple implementation that just waits for all nodes to be healthy.
   */
  private void waitForClusterStability() throws IOException, InterruptedException {
    log.debug("Waiting for cluster stability");

    int maxAttempts = 60;
    int attempt = 0;

    while (attempt < maxAttempts) {
      boolean allHealthy = true;

      for (SolrProcessManager manager : nodes.values()) {
        if (!manager.isHealthy()) {
          allHealthy = false;
          break;
        }
      }

      if (allHealthy) {
        log.debug("Cluster is stable");
        return;
      }

      Thread.sleep(1000);
      attempt++;
    }

    throw new IOException("Cluster did not stabilize within timeout");
  }

  /**
   * Upload a config set to ZooKeeper.
   *
   * @param configDir Local directory containing config files
   * @param configName Name for the config set in ZooKeeper
   * @throws IOException if upload fails
   */
  public void uploadConfigSet(Path configDir, String configName) throws IOException {
    log.info("Uploading config set {} from {}", configName, configDir);

    try {
      ZkConfigSetService configService = new ZkConfigSetService(zkClient);
      configService.uploadConfig(configName, configDir, true);
      log.info("Config set {} uploaded successfully", configName);
    } catch (IOException e) {
      log.error("Failed to upload config set {}", configName, e);
      throw e;
    }
  }

  // Getters

  /**
   * Get the ZooKeeper connection string.
   *
   * @return ZooKeeper host string (e.g., "localhost:9983")
   */
  public String getZkHost() {
    return "localhost:" + zkTestServer.getPort();
  }

  /**
   * Get a CloudSolrClient for interacting with the cluster.
   *
   * @return CloudSolrClient instance
   */
  public CloudSolrClient getSolrClient() {
    return solrClient;
  }

  /**
   * Get the SolrZkClient for direct ZooKeeper access.
   *
   * @return SolrZkClient instance
   */
  public SolrZkClient getZkClient() {
    return zkClient;
  }

  /**
   * Get the ZkTestServer instance.
   *
   * @return ZkTestServer
   */
  public ZkTestServer getZkServer() {
    return zkTestServer;
  }

  /**
   * Get the number of nodes in the cluster.
   *
   * @return Node count
   */
  public int getNodeCount() {
    return nodeCount;
  }

  /**
   * Get the base URL for a specific node.
   *
   * @param nodeIndex Node index (0-based)
   * @return Base URL (e.g., "http://127.0.0.1:50001/solr")
   */
  public String getJettySolrRunnerBaseUrl(int nodeIndex) {
    String nodeId = "jetty" + nodeIndex;
    SolrProcessManager manager = nodes.get(nodeId);
    if (manager == null) {
      throw new IllegalArgumentException("Node jetty" + nodeIndex + " not found");
    }
    return manager.getBaseUrl();
  }

  /**
   * Get all node base URLs.
   *
   * @return List of base URLs
   */
  public List<String> getAllNodeBaseUrls() {
    return nodes.values().stream()
        .map(SolrProcessManager::getBaseUrl)
        .collect(Collectors.toList());
  }

  /**
   * Get the current Solr distribution version.
   *
   * @return Version string
   */
  public String getCurrentVersion() {
    return currentDistribution != null ? currentDistribution.getVersion() : null;
  }

  /**
   * Check if a specific node is healthy.
   *
   * @param nodeIndex Node index (0-based)
   * @return true if node is healthy
   * @throws IOException if health check fails
   */
  public boolean isNodeHealthy(int nodeIndex) throws IOException {
    String nodeId = "jetty" + nodeIndex;
    SolrProcessManager manager = nodes.get(nodeId);
    if (manager == null) {
      return false;
    }
    return manager.isHealthy();
  }

  /**
   * Get the cluster directory.
   *
   * @return Cluster directory
   */
  public File getClusterDir() {
    return directoryManager.getClusterDir();
  }

  // Builder

  /**
   * Builder for ProcessBasedMiniSolrCloudCluster.
   *
   * <p>Usage:
   *
   * <pre>
   * ProcessBasedMiniSolrCloudCluster cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
   *     .withNodeCount(3)
   *     .withStartVersion("9.7.0")
   *     .withUpgradeVersion("9.8.0")
   *     .build();
   * </pre>
   */
  public static class Builder {
    private int nodeCount = 1;
    private SolrVersionRegistry versionRegistry;
    private Properties baseNodeProps;

    /** Create a new builder. */
    public Builder() {
      this.versionRegistry = new SolrVersionRegistry();
    }

    /**
     * Set the number of Solr nodes.
     *
     * @param nodeCount Number of nodes (default: 1)
     * @return This builder
     */
    public Builder withNodeCount(int nodeCount) {
      if (nodeCount < 1) {
        throw new IllegalArgumentException("Node count must be at least 1");
      }
      this.nodeCount = nodeCount;
      return this;
    }

    /**
     * Set the start version from a system property.
     *
     * <p>Reads from system property "solr.start.home"
     *
     * @return This builder
     */
    public Builder withStartVersionFromSystemProperty() {
      String startHome = System.getProperty("solr.start.home");
      if (startHome == null || startHome.isEmpty()) {
        throw new IllegalStateException(
            "System property 'solr.start.home' not set. "
                + "Please set it to the Solr distribution directory for the starting version.");
      }

      File startHomeDir = new File(startHome);
      if (!startHomeDir.exists() || !startHomeDir.isDirectory()) {
        throw new IllegalStateException(
            "solr.start.home points to invalid directory: " + startHome);
      }

      // Auto-detect version from directory (or use a default name)
      String version = detectVersionFromHome(startHomeDir);
      SolrDistribution distribution = new SolrDistribution(version, startHomeDir);
      this.versionRegistry.registerDistribution("start", distribution);

      log.info("Registered start version {} from {}", version, startHome);
      return this;
    }

    /**
     * Set the upgrade version from a system property.
     *
     * <p>Reads from system property "solr.upgrade.home"
     *
     * @return This builder
     */
    public Builder withUpgradeVersionFromSystemProperty() {
      String upgradeHome = System.getProperty("solr.upgrade.home");
      if (upgradeHome == null || upgradeHome.isEmpty()) {
        throw new IllegalStateException(
            "System property 'solr.upgrade.home' not set. "
                + "Please set it to the Solr distribution directory for the upgrade version.");
      }

      File upgradeHomeDir = new File(upgradeHome);
      if (!upgradeHomeDir.exists() || !upgradeHomeDir.isDirectory()) {
        throw new IllegalStateException(
            "solr.upgrade.home points to invalid directory: " + upgradeHome);
      }

      // Auto-detect version from directory (or use a default name)
      String version = detectVersionFromHome(upgradeHomeDir);
      SolrDistribution distribution = new SolrDistribution(version, upgradeHomeDir);
      this.versionRegistry.registerDistribution("upgrade", distribution);

      log.info("Registered upgrade version {} from {}", version, upgradeHome);
      return this;
    }

    /**
     * Set the start version explicitly.
     *
     * @param version Version identifier (e.g., "9.7.0")
     * @param solrHome Path to Solr distribution directory
     * @return This builder
     */
    public Builder withStartVersion(String version, File solrHome) {
      SolrDistribution distribution = new SolrDistribution(version, solrHome);
      this.versionRegistry.registerDistribution("start", distribution);
      log.info("Registered start version {} from {}", version, solrHome);
      return this;
    }

    /**
     * Set the upgrade version explicitly.
     *
     * @param version Version identifier (e.g., "9.8.0")
     * @param solrHome Path to Solr distribution directory
     * @return This builder
     */
    public Builder withUpgradeVersion(String version, File solrHome) {
      SolrDistribution distribution = new SolrDistribution(version, solrHome);
      this.versionRegistry.registerDistribution("upgrade", distribution);
      log.info("Registered upgrade version {} from {}", version, solrHome);
      return this;
    }

    /**
     * Set base node properties to apply to all nodes.
     *
     * @param props Base properties
     * @return This builder
     */
    public Builder withBaseNodeProps(Properties props) {
      this.baseNodeProps = props;
      return this;
    }

    /**
     * Build the ProcessBasedMiniSolrCloudCluster.
     *
     * @return New cluster instance
     * @throws Exception if cluster creation fails
     */
    public ProcessBasedMiniSolrCloudCluster build() throws Exception {
      // Validate that we have at least a start version
      if (versionRegistry.getDistribution("start") == null) {
        throw new IllegalStateException(
            "Start version not configured. Call withStartVersion() or withStartVersionFromSystemProperty()");
      }

      return new ProcessBasedMiniSolrCloudCluster(this);
    }

    /**
     * Attempt to detect version from Solr home directory.
     *
     * @param solrHome Solr home directory
     * @return Version string (or "unknown")
     */
    private String detectVersionFromHome(File solrHome) {
      // Try to read from server/solr-webapp/webapp/META-INF/MANIFEST.MF
      // or just use directory name as fallback
      String dirName = solrHome.getName();
      if (dirName.startsWith("solr-")) {
        return dirName.substring(5); // Remove "solr-" prefix
      }
      return dirName;
    }
  }
}
