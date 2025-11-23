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
package org.apache.solr.cloud.upgrade;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.common.cloud.ZkStateReader;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base test class for checkpoint-based upgrade testing of ProcessBasedMiniSolrCloudCluster.
 *
 * <p>This class provides automatic lifecycle management, checkpoint-based upgrade testing, and
 * complete test isolation for Apache Solr upgrade scenarios.
 *
 * <h3>Usage Example:</h3>
 *
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
 *         .withUpgradeVersionFromSystemProperty()
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
 * <p><strong>Test Execution:</strong>
 *
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
 *
 * <ul>
 *   <li>Complete isolation between checkpoint executions
 *   <li>Automatic cleanup of processes and directories
 *   <li>Verification of cleanup success
 *   <li>Force cleanup if verification fails
 *   <li>Node identity preservation verification during upgrades
 * </ul>
 *
 * <h3>System Properties:</h3>
 *
 * <ul>
 *   <li><code>solr.start.home</code> - Path to initial Solr installation
 *   <li><code>solr.upgrade.home</code> - Path to upgraded Solr installation
 * </ul>
 */
public abstract class ProcessBasedUpgradeTestBase {
  private static final Logger log = LoggerFactory.getLogger(ProcessBasedUpgradeTestBase.class);

  // Process patterns for cleanup
  private static final String PROCESS_PATTERN = "JettySolrRunner|start\\.jar";
  private static final String TEMP_DIR_PREFIX = "process-minisolr-";

  /** Checkpoint name where upgrade should occur. Set by test methods before test logic. */
  protected String upgradeCheckpoint;

  /** The ProcessBasedMiniSolrCloudCluster instance under test. */
  protected ProcessBasedMiniSolrCloudCluster cluster;

  /** Main CloudSolrClient for cluster operations. */
  protected CloudSolrClient solrClient;

  /** Per-collection CloudSolrClient instances. */
  protected Map<String, CloudSolrClient> collectionClients;

  /** Node URLs before upgrade for identity verification. */
  protected Map<Integer, String> preUpgradeUrls;

  /**
   * Setup method run before each test.
   *
   * <p>Performs:
   *
   * <ul>
   *   <li>Cleanup of orphaned processes from previous failed runs
   *   <li>Cleanup of old cluster directories (older than 1 hour)
   *   <li>Initialization of data structures
   * </ul>
   *
   * @throws Exception if setup fails
   */
  @Before
  public void setupTest() throws Exception {
    String checkpointInfo = (upgradeCheckpoint != null) ? " [checkpoint: " + upgradeCheckpoint + "]" : "";
    log.info("=== Setup starting{} ===", checkpointInfo);

    // Defensive cleanup - kill orphaned processes from previous failed runs
    cleanupOrphanedProcesses();

    // Clean up old cluster directories (older than 1 hour)
    cleanupOldClusterDirectories();

    // Initialize fresh data structures
    collectionClients = new HashMap<>();
    preUpgradeUrls = new HashMap<>();

    // Defensive initialization
    cluster = null;
    solrClient = null;

    log.info("=== Setup completed{} ===", checkpointInfo);
  }

  /**
   * Teardown method run after each test.
   *
   * <p>Performs comprehensive cleanup with independent try-catch blocks to ensure all cleanup
   * steps run even if one fails:
   *
   * <ul>
   *   <li>Close all collection-specific clients
   *   <li>Close main CloudSolrClient
   *   <li>Shutdown cluster
   *   <li>Wait for processes to terminate
   *   <li>Verify cleanup success
   *   <li>Force cleanup if verification fails
   * </ul>
   *
   * @throws Exception if teardown fails
   */
  @After
  public void tearDownTest() throws Exception {
    String checkpointInfo = (upgradeCheckpoint != null) ? " [checkpoint: " + upgradeCheckpoint + "]" : "";
    log.info("=== Teardown starting{} ===", checkpointInfo);

    // Close all collection clients (independent try-catch for each)
    if (collectionClients != null) {
      for (Map.Entry<String, CloudSolrClient> entry : collectionClients.entrySet()) {
        try {
          if (entry.getValue() != null) {
            entry.getValue().close();
            log.debug("Closed collection client for: {}", entry.getKey());
          }
        } catch (Exception e) {
          log.error("Error closing collection client for {}: {}", entry.getKey(), e.getMessage(), e);
        }
      }
      collectionClients.clear();
      collectionClients = null;
    }

    // Close main solrClient
    if (solrClient != null) {
      try {
        solrClient.close();
        log.debug("Closed main SolrClient");
      } catch (Exception e) {
        log.error("Error closing main SolrClient: {}", e.getMessage(), e);
      } finally {
        solrClient = null;
      }
    }

    // Shutdown cluster
    if (cluster != null) {
      try {
        cluster.shutdown();
        log.debug("Cluster shutdown completed");
      } catch (Exception e) {
        log.error("Error shutting down cluster: {}", e.getMessage(), e);
      } finally {
        cluster = null;
      }
    }

    // Wait for processes to terminate
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Verify cleanup success
    try {
      verifyCleanup();
    } catch (Exception e) {
      log.warn("Cleanup verification failed, forcing cleanup: {}", e.getMessage());
      // Force cleanup if verification fails
      cleanupOrphanedProcesses();
    }

    log.info("=== Teardown completed{} ===", checkpointInfo);
  }

  /**
   * Checkpoint method for upgrade testing.
   *
   * <p><strong>IMPORTANT:</strong> Close all streams, clients, and resources before calling
   * checkpoint() to avoid broken connections during node restarts.
   *
   * <p>If the current checkpoint name matches the upgradeCheckpoint field, this method:
   *
   * <ul>
   *   <li>Captures pre-upgrade node URLs for identity verification
   *   <li>Verifies cluster health before upgrade
   *   <li>Performs rolling upgrade using cluster.upgrade()
   *   <li>Verifies cluster health after upgrade
   *   <li>Verifies node identities preserved (same URLs)
   * </ul>
   *
   * @param name The checkpoint name
   * @throws Exception if upgrade fails
   */
  protected void checkpoint(String name) throws Exception {
    if (!shouldUpgrade(name)) {
      return;
    }

    log.info("=== Checkpoint: {} (UPGRADE WILL OCCUR) ===", name);

    // CRITICAL: Capture pre-upgrade node URLs for identity verification
    preUpgradeUrls.clear();
    for (int i = 0; i < cluster.getNodeCount(); i++) {
      String url = cluster.getJettySolrRunnerBaseUrl(i);
      preUpgradeUrls.put(i, url);
      log.debug("Pre-upgrade: Node {} URL = {}", i, url);
    }

    // Verify cluster health before upgrade
    log.info("Verifying cluster health before upgrade...");
    ZkStateReader zkStateReader = cluster.getZkStateReader();
    if (zkStateReader.getClusterState().getLiveNodes().size() < cluster.getNodeCount()) {
      throw new IllegalStateException(
          "Not all nodes are live before upgrade. Expected: "
              + cluster.getNodeCount()
              + ", Actual: "
              + zkStateReader.getClusterState().getLiveNodes().size());
    }
    log.info("Cluster health verified: all {} nodes are live", cluster.getNodeCount());

    // Perform rolling upgrade
    log.info("Starting rolling upgrade at checkpoint: {}", name);
    cluster.upgrade();
    log.info("Rolling upgrade completed");

    // Verify cluster health after upgrade
    log.info("Waiting for all nodes to be live after upgrade...");
    cluster.waitForAllNodes(30);
    log.info("All nodes are live after upgrade");

    // Verify node identities preserved
    verifyNodeIdentitiesPreserved();

    log.info("=== Checkpoint {} completed successfully ===", name);
  }

  /**
   * Determines if upgrade should happen at the given checkpoint.
   *
   * @param name The checkpoint name
   * @return true if upgrade should occur
   */
  protected boolean shouldUpgrade(String name) {
    if (upgradeCheckpoint == null) {
      return false;
    }
    if (SolrUpgradeCheckpoints.NO_UPGRADE.equals(upgradeCheckpoint)) {
      return false;
    }
    return upgradeCheckpoint.equals(name);
  }

  /**
   * Verify that node identities were preserved during upgrade.
   *
   * <p>Compares pre-upgrade node URLs with current node URLs. Throws AssertionError if any node
   * URL changed, indicating that node identity was not preserved.
   *
   * @throws AssertionError if any node URL changed
   */
  private void verifyNodeIdentitiesPreserved() {
    log.info("Verifying node identities preserved across upgrade...");

    boolean allPreserved = true;
    StringBuilder errors = new StringBuilder();

    for (int i = 0; i < cluster.getNodeCount(); i++) {
      String postUrl = cluster.getJettySolrRunnerBaseUrl(i);
      String preUrl = preUpgradeUrls.get(i);

      if (!postUrl.equals(preUrl)) {
        allPreserved = false;
        String error =
            "Node " + i + " URL changed during upgrade: " + preUrl + " -> " + postUrl;
        log.error(error);
        errors.append(error).append("\n");
      } else {
        log.debug("Node {} identity preserved: {}", i, postUrl);
      }
    }

    if (!allPreserved) {
      throw new AssertionError(
          "Node identity was not preserved during upgrade!\n" + errors.toString());
    }

    log.info("Node identities successfully preserved across upgrade");
  }

  /**
   * Cleanup orphaned Solr processes from previous failed runs.
   *
   * <p>Executes: {@code jps | grep -E 'JettySolrRunner|start\.jar' | awk '{print $1}' | xargs -r
   * kill -9}
   *
   * @throws IOException if cleanup command fails
   */
  private void cleanupOrphanedProcesses() throws IOException {
    log.debug("Checking for orphaned Solr processes...");

    try {
      // First, check if there are any matching processes
      ProcessBuilder checkBuilder = new ProcessBuilder("bash", "-c", "jps | grep -E '" + PROCESS_PATTERN + "'");
      Process checkProcess = checkBuilder.start();

      StringBuilder output = new StringBuilder();
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
        }
      }

      checkProcess.waitFor();

      if (output.length() > 0) {
        log.warn("Found orphaned Solr processes:\n{}", output.toString());

        // Kill the orphaned processes
        ProcessBuilder killBuilder =
            new ProcessBuilder(
                "bash",
                "-c",
                "jps | grep -E '" + PROCESS_PATTERN + "' | awk '{print $1}' | xargs -r kill -9");
        Process killProcess = killBuilder.start();
        int exitCode = killProcess.waitFor();

        if (exitCode == 0) {
          log.info("Successfully cleaned up orphaned Solr processes");
        } else {
          log.warn("Process cleanup command returned exit code: {}", exitCode);
        }
      } else {
        log.debug("No orphaned Solr processes found");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while cleaning up processes", e);
    } catch (Exception e) {
      log.warn("Error during process cleanup (this is non-fatal): {}", e.getMessage());
    }
  }

  /**
   * Cleanup old cluster directories (older than 1 hour).
   *
   * <p>Finds directories matching pattern {@code process-minisolr-*} in system temp directory and
   * deletes those older than 1 hour.
   */
  private void cleanupOldClusterDirectories() {
    log.debug("Checking for old cluster directories...");

    File tmpDir = new File(System.getProperty("java.io.tmpdir"));
    File[] oldDirs =
        tmpDir.listFiles(
            (dir, name) -> name.startsWith(TEMP_DIR_PREFIX) && name.matches(".*\\d{13}$"));

    if (oldDirs != null && oldDirs.length > 0) {
      long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
      int deletedCount = 0;

      for (File dir : oldDirs) {
        try {
          BasicFileAttributes attrs =
              Files.readAttributes(dir.toPath(), BasicFileAttributes.class);
          FileTime creationTime = attrs.creationTime();

          if (creationTime.toMillis() < cutoffTime) {
            deleteDirectory(dir);
            deletedCount++;
            log.debug("Deleted old cluster directory: {}", dir.getName());
          }
        } catch (IOException e) {
          log.debug("Could not delete old directory {}: {}", dir.getName(), e.getMessage());
        }
      }

      if (deletedCount > 0) {
        log.info("Cleaned up {} old cluster directories", deletedCount);
      } else {
        log.debug("No old cluster directories to clean up");
      }
    } else {
      log.debug("No old cluster directories found");
    }
  }

  /**
   * Recursively delete a directory.
   *
   * @param directory Directory to delete
   * @throws IOException if deletion fails
   */
  private void deleteDirectory(File directory) throws IOException {
    if (!directory.exists()) {
      return;
    }

    if (directory.isDirectory()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          deleteDirectory(file);
        }
      }
    }

    if (!directory.delete()) {
      throw new IOException("Failed to delete: " + directory.getAbsolutePath());
    }
  }

  /**
   * Verify that cleanup was successful.
   *
   * <p>Executes {@code jps | grep -E 'JettySolrRunner|start\.jar'} and throws exception if any
   * matching processes are found.
   *
   * @throws Exception if orphaned processes remain
   */
  private void verifyCleanup() throws Exception {
    log.debug("Verifying cleanup...");

    ProcessBuilder builder = new ProcessBuilder("bash", "-c", "jps | grep -E '" + PROCESS_PATTERN + "'");
    Process process = builder.start();

    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append("\n");
      }
    }

    int exitCode = process.waitFor();

    // grep returns 0 if found, 1 if not found
    if (exitCode == 0 && output.length() > 0) {
      throw new Exception("Cleanup verification failed - orphaned processes remain:\n" + output.toString());
    }

    log.debug("Cleanup verified - no orphaned processes");
  }
}
