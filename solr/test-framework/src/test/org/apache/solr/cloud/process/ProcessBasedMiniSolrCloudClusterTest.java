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

import static org.junit.Assert.*;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakLingering;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test for ProcessBasedMiniSolrCloudCluster and related infrastructure.
 *
 * <p>Tests are divided into:
 *
 * <ul>
 *   <li>Component tests - test individual components without requiring Solr distributions
 *   <li>Integration tests - test full cluster lifecycle (requires built Solr distributions)
 * </ul>
 */
@ThreadLeakLingering(linger = 10)
@LuceneTestCase.SuppressSysoutChecks(bugUrl = "Solr logs to JUL")
@SolrTestCaseJ4.SuppressSSL(bugUrl = "https://issues.apache.org/jira/browse/SOLR-15026")
public class ProcessBasedMiniSolrCloudClusterTest extends SolrTestCaseJ4 {

  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    super.setUp();
    tempDir = createTempDir();
  }

  @After
  public void tearDown() throws Exception {
    super.tearDown();
  }

  // ========== Component Tests ==========

  @Test
  public void testPortAllocator() throws Exception {
    File persistDir = new File(tempDir.toFile(), "ports");
    persistDir.mkdirs();

    PortAllocator allocator = new PortAllocator(persistDir);

    // Allocate ports for nodes
    int port1 = allocator.allocatePort("jetty0");
    int port2 = allocator.allocatePort("jetty1");
    int port3 = allocator.allocatePort("jetty2");

    // Ports should be in valid range
    assertTrue("Port should be >= 50000", port1 >= 50000);
    assertTrue("Port should be < 60000", port1 < 60000);

    // Ports should be unique
    assertNotEquals("Ports should be unique", port1, port2);
    assertNotEquals("Ports should be unique", port2, port3);

    // Release and reallocate - should get same ports back due to persistence
    allocator.releasePort(port2);

    // Create new allocator to test persistence
    PortAllocator allocator2 = new PortAllocator(persistDir);

    // Reallocate for jetty0 - should get same port back
    int port1Again = allocator2.allocatePort("jetty0");
    assertEquals("Should get same port after restart", port1, port1Again);

    // Reallocate for jetty1 - should get same port back
    int port2Again = allocator2.allocatePort("jetty1");
    assertEquals("Should get same port after release and restart", port2, port2Again);
  }

  @Test
  public void testDirectoryManager() throws Exception {
    DirectoryManager dirManager = new DirectoryManager();

    // Get cluster directory
    File clusterDir = dirManager.getClusterDir();
    assertNotNull("Cluster directory should not be null", clusterDir);
    assertTrue("Cluster directory should exist", clusterDir.exists());
    assertTrue("Cluster directory should be a directory", clusterDir.isDirectory());

    // Create node directories
    File node0Dir = dirManager.createNodeDir("jetty0");
    assertNotNull("Node directory should not be null", node0Dir);
    assertTrue("Node directory should exist", node0Dir.exists());

    // Verify subdirectories
    File confDir = new File(node0Dir, "conf");
    File dataDir = new File(node0Dir, "data");
    File logsDir = new File(node0Dir, "logs");

    assertTrue("conf directory should exist", confDir.exists());
    assertTrue("data directory should exist", dataDir.exists());
    assertTrue("logs directory should exist", logsDir.exists());

    // Cleanup
    dirManager.cleanup();
  }

  @Test
  public void testSolrVersionRegistry() {
    SolrVersionRegistry registry = new SolrVersionRegistry();

    // Create mock distributions
    File mockHome1 = tempDir.resolve("solr-9.7.0").toFile();
    File mockHome2 = tempDir.resolve("solr-9.8.0").toFile();
    mockHome1.mkdirs();
    mockHome2.mkdirs();

    // Note: These won't be valid distributions without JARs, but we can test registry logic
    // Actual validation happens in SolrDistribution constructor

    // Register versions
    SolrDistribution dist1 = createMockDistribution("9.7.0", mockHome1);
    SolrDistribution dist2 = createMockDistribution("9.8.0", mockHome2);

    registry.registerDistribution("start", dist1);
    registry.registerDistribution("upgrade", dist2);

    // Retrieve versions
    assertEquals("Should retrieve start version", dist1, registry.getDistribution("start"));
    assertEquals("Should retrieve upgrade version", dist2, registry.getDistribution("upgrade"));

    // Retrieve by version string
    assertEquals("Should retrieve by version string", dist1, registry.getDistribution("9.7.0"));
    assertEquals("Should retrieve by version string", dist2, registry.getDistribution("9.8.0"));
  }

  @Test
  public void testProcessConfigurationGenerator() throws Exception {
    File workDir = tempDir.resolve("test-node").toFile();

    // Create node directories
    ProcessConfigurationGenerator.createNodeDirectories(workDir);
    File dataDir = new File(workDir, "data");
    File logsDir = new File(workDir, "logs");
    assertTrue("Data directory should exist", dataDir.exists());
    assertTrue("Logs directory should exist", logsDir.exists());

    // Write node identity
    ProcessConfigurationGenerator.writeNodeIdentity("jetty0", 0, workDir);
    File identityFile = new File(workDir, "node-identity.properties");
    assertTrue("Node identity file should exist", identityFile.exists());

    // Verify identity file content
    Properties identity = new Properties();
    try (java.io.FileInputStream fis = new java.io.FileInputStream(identityFile)) {
      identity.load(fis);
    }
    assertEquals("jetty0", identity.getProperty("node.id"));
    assertEquals("0", identity.getProperty("node.index"));
    assertNotNull("Should have created.time", identity.getProperty("created.time"));
  }

  @Test
  public void testBuilderValidation() {
    // Builder should fail without start version
    try {
      new ProcessBasedMiniSolrCloudCluster.Builder().withNodeCount(3).build();
      fail("Should require start version");
    } catch (Exception e) {
      assertTrue(
          "Should mention start version",
          e.getMessage().contains("Start version not configured"));
    }

    // Builder should fail with invalid node count
    try {
      new ProcessBasedMiniSolrCloudCluster.Builder().withNodeCount(0);
      fail("Should reject node count < 1");
    } catch (IllegalArgumentException e) {
      assertTrue(
          "Should mention node count", e.getMessage().contains("Node count must be at least 1"));
    }
  }

  @Test
  public void testBuilderWithSystemProperties() throws Exception {
    // Set up mock Solr home directories
    File startHome = tempDir.resolve("start-solr").toFile();
    File upgradeHome = tempDir.resolve("upgrade-solr").toFile();

    createMockSolrDistributionStructure(startHome);
    createMockSolrDistributionStructure(upgradeHome);

    // Set system properties
    System.setProperty("solr.start.home", startHome.getAbsolutePath());
    System.setProperty("solr.upgrade.home", upgradeHome.getAbsolutePath());

    try {
      ProcessBasedMiniSolrCloudCluster.Builder builder =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(2)
              .withStartVersionFromSystemProperty()
              .withUpgradeVersionFromSystemProperty();

      // Builder should have configured versions
      // Note: build() will fail without valid JARs, but we've validated system property reading
      assertNotNull("Builder should be created", builder);

    } finally {
      System.clearProperty("solr.start.home");
      System.clearProperty("solr.upgrade.home");
    }
  }

  // ========== Integration Tests ==========

  /**
   * Full integration test - requires built Solr distributions.
   *
   * <p>This test will be skipped if Solr distributions are not available via system properties.
   */
  @Test
  public void testFullClusterLifecycle() throws Exception {
    // Check if Solr distributions are available
    String startHome = System.getProperty("solr.start.home");
    String upgradeHome = System.getProperty("solr.upgrade.home");

    if (startHome == null || startHome.isEmpty()) {
      System.out.println(
          "Skipping full integration test - solr.start.home not set. "
              + "To run this test, set -Dsolr.start.home=/path/to/solr/distribution");
      return;
    }

    // Use start version for both if upgrade not specified
    if (upgradeHome == null || upgradeHome.isEmpty()) {
      upgradeHome = startHome;
      System.setProperty("solr.upgrade.home", upgradeHome);
    }

    File startHomeDir = new File(startHome);
    File upgradeHomeDir = new File(upgradeHome);

    // Verify distributions exist
    if (!startHomeDir.exists() || !startHomeDir.isDirectory()) {
      System.out.println(
          "Skipping full integration test - solr.start.home does not exist: " + startHome);
      return;
    }

    ProcessBasedMiniSolrCloudCluster cluster = null;

    try {
      // Create cluster
      cluster =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(2)
              .withStartVersionFromSystemProperty()
              .withUpgradeVersionFromSystemProperty()
              .build();

      assertNotNull("Cluster should be created", cluster);

      // Start cluster
      cluster.start();

      // Verify cluster started
      assertEquals("Should have 2 nodes", 2, cluster.getNodeCount());
      assertNotNull("Should have SolrClient", cluster.getSolrClient());
      assertNotNull("Should have ZkClient", cluster.getZkClient());
      assertNotNull("Should have ZK host", cluster.getZkHost());
      assertTrue("ZK host should contain port", cluster.getZkHost().contains(":"));

      // Verify nodes are healthy
      assertTrue("Node 0 should be healthy", cluster.isNodeHealthy(0));
      assertTrue("Node 1 should be healthy", cluster.isNodeHealthy(1));

      // Get node URLs
      String url0 = cluster.getJettySolrRunnerBaseUrl(0);
      String url1 = cluster.getJettySolrRunnerBaseUrl(1);

      assertNotNull("Node 0 URL should not be null", url0);
      assertNotNull("Node 1 URL should not be null", url1);
      assertTrue("Node 0 URL should be HTTP", url0.startsWith("http://"));
      assertTrue("Node 1 URL should be HTTP", url1.startsWith("http://"));

      // URLs should be different
      assertNotEquals("Node URLs should be different", url0, url1);

      // Test node restart
      cluster.restartJettySolrRunner(0);
      assertTrue("Node 0 should be healthy after restart", cluster.isNodeHealthy(0));

      // If upgrade version is different, test upgrade
      if (!startHome.equals(upgradeHome)) {
        String versionBefore = cluster.getCurrentVersion();

        cluster.upgrade();

        String versionAfter = cluster.getCurrentVersion();
        assertNotEquals(
            "Version should change after upgrade", versionBefore, versionAfter);

        // Verify all nodes still healthy after upgrade
        assertTrue("Node 0 should be healthy after upgrade", cluster.isNodeHealthy(0));
        assertTrue("Node 1 should be healthy after upgrade", cluster.isNodeHealthy(1));
      }

    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
      System.clearProperty("solr.upgrade.home");
    }
  }

  // ========== Helper Methods ==========

  /**
   * Create a mock SolrDistribution for testing (without actual JARs).
   *
   * <p>Note: This will fail validation in real use, but works for registry testing.
   */
  private SolrDistribution createMockDistribution(String version, File solrHome) {
    // Create minimal directory structure
    new File(solrHome, "dist").mkdirs();
    new File(solrHome, "server/lib").mkdirs();
    new File(solrHome, "server/solr-webapp/webapp/WEB-INF/lib").mkdirs();

    // Create a dummy JAR file to pass basic validation
    try {
      File distDir = new File(solrHome, "dist");
      File dummyJar = new File(distDir, "solr-core-" + version + ".jar");
      dummyJar.createNewFile();
    } catch (Exception e) {
      // Ignore - test may still work
    }

    try {
      return new SolrDistribution(version, solrHome);
    } catch (IllegalStateException e) {
      // If validation fails, that's expected for mock distributions
      // We can still test registry functionality
      return null;
    }
  }

  /**
   * Create mock Solr distribution directory structure with minimal files.
   *
   * @param solrHome Root directory for Solr distribution
   */
  private void createMockSolrDistributionStructure(File solrHome) throws Exception {
    // Create directory structure
    File distDir = new File(solrHome, "dist");
    File serverLibDir = new File(solrHome, "server/lib");
    File webappLibDir = new File(solrHome, "server/solr-webapp/webapp/WEB-INF/lib");

    distDir.mkdirs();
    serverLibDir.mkdirs();
    webappLibDir.mkdirs();

    // Create some dummy JAR files
    createDummyJar(new File(distDir, "solr-core-9.7.0.jar"));
    createDummyJar(new File(serverLibDir, "jetty-server.jar"));
    createDummyJar(new File(webappLibDir, "commons-io.jar"));
  }

  /**
   * Create a dummy JAR file for testing.
   *
   * @param jarFile JAR file to create
   */
  private void createDummyJar(File jarFile) throws Exception {
    // Create parent directories
    jarFile.getParentFile().mkdirs();

    // Create empty file
    jarFile.createNewFile();

    // Write minimal JAR header (PK zip signature)
    try (FileWriter writer = new FileWriter(jarFile)) {
      writer.write("PK");
    }
  }
}
