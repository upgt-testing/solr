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

import java.io.File;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for real Solr processes using bin/solr script.
 *
 * <p>Run with: ./gradlew :solr:test-framework:test --tests RealSolrProcessTest \
 * -Dsolr.start.home=/path/to/solr-9.7.0 -Ptests.useSecurityManager=false
 */
@LuceneTestCase.SuppressSysoutChecks(bugUrl = "Solr logs expected output during startup")
public class RealSolrProcessTest extends LuceneTestCase {

  private ProcessBasedMiniSolrCloudCluster cluster;

  @Before
  public void setUp() throws Exception {
    super.setUp();
  }

  @After
  public void tearDown() throws Exception {
    if (cluster != null) {
      try {
        cluster.shutdown();
      } catch (Exception e) {
        System.err.println("Error shutting down cluster: " + e.getMessage());
      }
    }
    super.tearDown();
  }

  @Test
  public void testSingleNodeClusterStartup() throws Exception {
    // For now, hard-code the path for testing (can be changed to system property later)
    String solrHome = "/Users/allenwang/xlab/solr-test-distributions/solr-9.7.0";

    // Alternative: use system property if provided
    String sysPropHome = System.getProperty("solr.start.home");
    if (sysPropHome != null && !sysPropHome.isEmpty()) {
      solrHome = sysPropHome;
    }

    File solrHomeDir = new File(solrHome);
    assumeTrue("Solr distribution must exist at: " + solrHome, solrHomeDir.exists());

    // Temporarily set the system property so Builder can find it
    System.setProperty("solr.start.home", solrHome);

    System.out.println("=== Testing with Solr distribution at: " + solrHome);

    // Build a simple 1-node cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .build();

    System.out.println("=== Starting cluster...");
    cluster.start();

    System.out.println("=== Cluster started successfully!");

    // Verify cluster is running
    assertNotNull("ZK host should be set", cluster.getZkHost());
    assertEquals("Should have 1 node", 1, cluster.getNodeCount());
    assertTrue("Node should be healthy", cluster.isNodeHealthy(0));

    String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
    System.out.println("=== Node 0 base URL: " + baseUrl);
    assertNotNull("Base URL should be set", baseUrl);
    assertTrue("Base URL should be HTTP", baseUrl.startsWith("http://"));

    System.out.println("=== Test passed! Shutting down...");
  }

  @Test
  public void testSolrDistributionValidation() throws Exception {
    String solrHome = System.getProperty("solr.start.home");
    assumeTrue(
        "This test requires -Dsolr.start.home=/path/to/solr-distribution",
        solrHome != null && !solrHome.isEmpty());

    File solrHomeDir = new File(solrHome);
    assumeTrue("Solr home must exist: " + solrHome, solrHomeDir.exists());

    // Test SolrDistribution validation
    SolrDistribution dist = new SolrDistribution("test-version", solrHomeDir);

    assertEquals("test-version", dist.getVersion());
    assertEquals(solrHomeDir, dist.getSolrHome());
    assertEquals(solrHomeDir.getAbsolutePath(), dist.getSolrHomePath());

    System.out.println("=== SolrDistribution validated successfully for: " + solrHome);
  }
}
