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
package org.apache.solr.cloud;

import static org.junit.Assert.fail;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.util.SuppressForbidden;
import org.junit.Test;

/**
 * ProcessBased upgrade test for request forwarding in SolrCloud.
 *
 * <p>Tests that queries can be sent to any node (including nodes not hosting the collection) and
 * will be properly forwarded.
 *
 * <p>Transformed from {@link TestRequestForwarding} to support checkpoint-based upgrade testing.
 */
public class TestRequestForwarding_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final String COLLECTION_NAME = "collection1";
  private static final String CONFIG_NAME = "conf1";

  @Test
  public void testMultiCollectionQuery_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTest();
  }

  @Test
  public void testMultiCollectionQuery_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTest();
  }

  @Test
  public void testMultiCollectionQuery_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE;
    runTest();
  }

  private void runTest() throws Exception {
    // System properties for test
    System.setProperty("solr.test.sys.prop1", "propone");
    System.setProperty("solr.test.sys.prop2", "proptwo");

    try {
      // Create cluster with 3 nodes
      cluster =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(3)
              .withStartVersionFromSystemProperty()
              .withUpgradeVersionFromSystemProperty()
              .build();
      cluster.start();
      solrClient = cluster.getSolrClient();

      // Upload configset
      Path configPath = Paths.get(SolrTestCaseJ4.TEST_HOME(), "..", "collection1", "conf");
      cluster.uploadConfigSet(configPath, CONFIG_NAME);

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Create collection with 2 shards, 1 replica (only 2 of 3 nodes will host collection)
      createCollection(COLLECTION_NAME, CONFIG_NAME);

      checkpoint(SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE);

      // Test query forwarding - send queries to ALL nodes (including one without collection)
      testQueryForwarding();

    } finally {
      System.clearProperty("solr.test.sys.prop1");
      System.clearProperty("solr.test.sys.prop2");
    }
  }

  private void createCollection(String name, String config) throws Exception {
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(name, config, 2, 1);
    CollectionAdminResponse response = create.process(solrClient);

    if (response.getStatus() != 0 || response.getErrorMessages() != null) {
      fail("Could not create collection. Response: " + response);
    }

    // Note: waitForActiveCollection not available in ProcessBased
    // Relying on collection creation success and brief pause
    Thread.sleep(2000); // Give collection time to become active
  }

  private void testQueryForwarding() throws Exception {
    // Test queries against all nodes (including the one that doesn't host the collection)
    // Verifies request forwarding works correctly
    for (int i = 0; i < cluster.getNodeCount(); i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);

      String[] queryStrings = {
        "q=cat%3Afootball%5E2", // URL encoded
        "q=cat:football^2" // No URL encoding, contains disallowed character ^
      };

      for (String q : queryStrings) {
        try {
          URL url = createURL(baseUrl + "/" + COLLECTION_NAME + "/select?" + q);
          url.openStream(); // Shouldn't throw any errors
        } catch (Exception ex) {
          throw new RuntimeException(
              "Query '" + q + "' failed on node " + i + " (" + baseUrl + "), ", ex);
        }
      }
    }
  }

  // Restricting the Scope of Forbidden API
  @SuppressForbidden(reason = "java.net.URL#<init> deprecated since Java 20")
  private URL createURL(String url) throws MalformedURLException {
    return new URL(url);
  }
}
