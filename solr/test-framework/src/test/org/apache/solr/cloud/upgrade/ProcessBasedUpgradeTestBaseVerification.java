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

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.junit.Test;

/**
 * Verification test for ProcessBasedUpgradeTestBase.
 *
 * <p>This test demonstrates the usage of the base class and verifies that the checkpoint mechanism
 * works correctly.
 *
 * <p>Run with:
 *
 * <pre>
 * ./gradlew :solr:test-framework:test --tests ProcessBasedUpgradeTestBaseVerification \
 *   -Dsolr.start.home=/path/to/solr-9.7.0 \
 *   -Dsolr.upgrade.home=/path/to/solr-9.8.0 \
 *   -Ptests.useSecurityManager=false
 * </pre>
 */
@LuceneTestCase.SuppressSysoutChecks(bugUrl = "Solr logs expected output during startup")
public class ProcessBasedUpgradeTestBaseVerification extends ProcessBasedUpgradeTestBase {

  /**
   * Baseline test - no upgrade performed.
   *
   * <p>This test verifies that the cluster can start and basic operations work without any
   * upgrade.
   */
  @Test
  public void testBasicClusterOperation_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;

    // Get Solr home from system property
    String solrHome = System.getProperty("solr.start.home");
    assumeTrue(
        "This test requires -Dsolr.start.home=/path/to/solr-distribution",
        solrHome != null && !solrHome.isEmpty());

    File solrHomeDir = new File(solrHome);
    assumeTrue("Solr home must exist: " + solrHome, solrHomeDir.exists());

    System.out.println("=== Testing baseline (NO_UPGRADE) with: " + solrHome);

    // Build cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .build();

    System.out.println("=== Starting cluster...");
    cluster.start();

    // Checkpoint - no upgrade will occur since upgradeCheckpoint = NO_UPGRADE
    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    System.out.println("=== Cluster started successfully!");

    // Verify cluster is running
    assertNotNull("Cluster should not be null", cluster);
    assertEquals("Should have 1 node", 1, cluster.getNodeCount());
    assertNotNull("ZK host should be set", cluster.getZkHost());

    String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
    System.out.println("=== Node 0 base URL: " + baseUrl);
    assertNotNull("Base URL should be set", baseUrl);
    assertTrue("Base URL should be HTTP", baseUrl.startsWith("http://"));

    System.out.println("=== Baseline test passed!");
    // Cleanup handled by @After method
  }

  /**
   * Upgrade test - upgrade immediately after cluster start.
   *
   * <p>This test verifies that rolling upgrade works and that node identity is preserved.
   */
  @Test
  public void testBasicClusterOperation_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;

    // Get Solr homes from system properties
    String startHome = System.getProperty("solr.start.home");
    String upgradeHome = System.getProperty("solr.upgrade.home");

    assumeTrue(
        "This test requires -Dsolr.start.home=/path/to/solr-distribution",
        startHome != null && !startHome.isEmpty());
    assumeTrue(
        "This test requires -Dsolr.upgrade.home=/path/to/solr-distribution",
        upgradeHome != null && !upgradeHome.isEmpty());

    File startHomeDir = new File(startHome);
    File upgradeHomeDir = new File(upgradeHome);

    assumeTrue("Solr start home must exist: " + startHome, startHomeDir.exists());
    assumeTrue("Solr upgrade home must exist: " + upgradeHome, upgradeHomeDir.exists());

    System.out.println("=== Testing upgrade from " + startHome + " to " + upgradeHome);

    // Build cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .withUpgradeVersionFromSystemProperty()
            .build();

    System.out.println("=== Starting cluster with start version...");
    cluster.start();

    String urlBefore = cluster.getJettySolrRunnerBaseUrl(0);
    System.out.println("=== Node 0 URL before upgrade: " + urlBefore);

    // Checkpoint - upgrade WILL occur since upgradeCheckpoint = AFTER_CLUSTER_START
    System.out.println("=== Triggering upgrade at checkpoint: AFTER_CLUSTER_START");
    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    String urlAfter = cluster.getJettySolrRunnerBaseUrl(0);
    System.out.println("=== Node 0 URL after upgrade: " + urlAfter);

    // Verify node identity preserved
    assertEquals(
        "Node URL should be preserved across upgrade", urlBefore, urlAfter);

    System.out.println("=== Upgrade test passed! Node identity preserved.");
    // Cleanup handled by @After method
  }

  /**
   * Test to verify that shouldUpgrade() logic works correctly.
   */
  @Test
  public void testShouldUpgradeLogic() {
    // Test NO_UPGRADE
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    assertFalse(
        "Should not upgrade when checkpoint is NO_UPGRADE",
        shouldUpgrade(SolrUpgradeCheckpoints.AFTER_CLUSTER_START));

    // Test null checkpoint
    upgradeCheckpoint = null;
    assertFalse("Should not upgrade when checkpoint is null", shouldUpgrade("ANYTHING"));

    // Test matching checkpoint
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    assertTrue(
        "Should upgrade when checkpoint matches",
        shouldUpgrade(SolrUpgradeCheckpoints.AFTER_CLUSTER_START));

    // Test non-matching checkpoint
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    assertFalse(
        "Should not upgrade when checkpoint does not match",
        shouldUpgrade(SolrUpgradeCheckpoints.AFTER_INDEX));

    System.out.println("=== shouldUpgrade() logic test passed!");
  }
}
