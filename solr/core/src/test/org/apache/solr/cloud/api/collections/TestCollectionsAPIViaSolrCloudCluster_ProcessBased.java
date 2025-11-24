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
package org.apache.solr.cloud.api.collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.junit.Test;

/**
 * ProcessBased test of Collections API operations.
 *
 * <p>Simplified version of TestCollectionsAPIViaSolrCloudCluster focusing on collection lifecycle
 * operations with node stop/restart scenarios.
 *
 * <p>Note: Original test relies on JettySolrRunner object manipulation (getJettySolrRunners(),
 * stopJettySolrRunner(jetty), etc.) which ProcessBased doesn't support. This version adapts the
 * test logic to use ProcessBased node index-based operations.
 */
public class TestCollectionsAPIViaSolrCloudCluster_ProcessBased
    extends ProcessBasedUpgradeTestBase {

  private static final int NUM_SHARDS = 2;
  private static final int NUM_REPLICAS = 2;
  private static final int NODE_COUNT = 5;
  private static final String CONFIG_NAME = "solrCloudCollectionConfig";
  private static final Map<String, String> COLLECTION_PROPERTIES =
      Collections.singletonMap("solr.directoryFactory", "solr.StandardDirectoryFactory");

  @Test
  public void testCollectionCreateSearchDelete_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestCollectionCreateSearchDelete();
  }

  @Test
  public void testCollectionCreateSearchDelete_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestCollectionCreateSearchDelete();
  }

  @Test
  public void testCollectionCreateSearchDelete_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    doTestCollectionCreateSearchDelete();
  }

  private void doTestCollectionCreateSearchDelete() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(NODE_COUNT)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();
    cluster.waitForAllNodes(30);
    solrClient = cluster.getSolrClient();

    assertNotNull("ZkServer should not be null", cluster.getZkServer());
    assertEquals("Should have " + NODE_COUNT + " nodes", NODE_COUNT, cluster.getNodeCount());

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset
    Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
    cluster.uploadConfigSet(configDir, CONFIG_NAME);

    final String collectionName = "testcollection";

    // Create collection
    CollectionAdminRequest.createCollection(collectionName, CONFIG_NAME, NUM_SHARDS, NUM_REPLICAS)
        .setProperties(COLLECTION_PROPERTIES)
        .process(solrClient);
    cluster.waitForActiveCollection(collectionName, NUM_SHARDS, NUM_SHARDS * NUM_REPLICAS);

    checkpoint("AFTER_COLLECTION_CREATE");

    // Index and query
    new UpdateRequest().add("id", "1").commit(solrClient, collectionName);
    QueryResponse rsp = solrClient.query(collectionName, new SolrQuery("*:*"));
    assertEquals("Should find 1 document", 1, rsp.getResults().getNumFound());

    // Test node stop/restart - stop a node not hosting replicas
    int nodeToStop = findNodeWithoutReplicas(collectionName);
    if (nodeToStop >= 0) {
      cluster.stopJettySolrRunner(nodeToStop);
      // Wait a bit for the node to stop
      Thread.sleep(2000);

      // Restart the node
      cluster.startJettySolrRunner(nodeToStop);
      cluster.waitForAllNodes(30);
    }

    // Delete collection
    CollectionAdminRequest.deleteCollection(collectionName).process(solrClient);

    // Wait for collection to disappear
    ZkStateReader zkStateReader = ZkStateReader.from(solrClient);
    waitForCollectionToDisappear(collectionName, zkStateReader);

    // Re-create collection
    CollectionAdminRequest.createCollection(collectionName, CONFIG_NAME, NUM_SHARDS, NUM_REPLICAS)
        .setProperties(COLLECTION_PROPERTIES)
        .process(solrClient);
    cluster.waitForActiveCollection(collectionName, NUM_SHARDS, NUM_SHARDS * NUM_REPLICAS);

    // Verify no left-over state
    assertEquals(
        "Should have 0 documents after recreation",
        0,
        solrClient.query(collectionName, new SolrQuery("*:*")).getResults().getNumFound());

    // Index and query again
    new UpdateRequest().add("id", "1").commit(solrClient, collectionName);
    assertEquals(
        "Should find 1 document after recreation",
        1,
        solrClient.query(collectionName, new SolrQuery("*:*")).getResults().getNumFound());
  }

  @Test
  public void testCollectionCreateWithoutCoresThenDelete_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestCollectionCreateWithoutCoresThenDelete();
  }

  @Test
  public void testCollectionCreateWithoutCoresThenDelete_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestCollectionCreateWithoutCoresThenDelete();
  }

  private void doTestCollectionCreateWithoutCoresThenDelete() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(NODE_COUNT)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();
    cluster.waitForAllNodes(30);
    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset
    Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
    cluster.uploadConfigSet(configDir, CONFIG_NAME);

    final String collectionName = "testSolrCloudCollectionWithoutCores";

    // Create collection without cores (CREATE_NODE_SET_EMPTY)
    CollectionAdminRequest.createCollection(collectionName, CONFIG_NAME, NUM_SHARDS, NUM_REPLICAS)
        .setCreateNodeSet(CollectionHandlingUtils.CREATE_NODE_SET_EMPTY)
        .setProperties(COLLECTION_PROPERTIES)
        .process(solrClient);

    cluster.waitForActiveCollection(collectionName, NUM_SHARDS, 0);

    // Verify collection has no cores
    ClusterState clusterState = solrClient.getClusterState();
    DocCollection docCollection = clusterState.getCollection(collectionName);
    int coreCount = 0;
    for (Map.Entry<String, Slice> entry : docCollection.getSlicesMap().entrySet()) {
      coreCount += entry.getValue().getReplicasMap().entrySet().size();
    }
    assertEquals("Collection should have 0 cores", 0, coreCount);

    // Delete collection
    CollectionAdminRequest.deleteCollection(collectionName).process(solrClient);

    // Wait for collection to disappear
    ZkStateReader zkStateReader = ZkStateReader.from(solrClient);
    waitForCollectionToDisappear(collectionName, zkStateReader);
  }

  /**
   * Find a node index that doesn't host any replicas for the given collection.
   * Returns -1 if all nodes host replicas.
   */
  private int findNodeWithoutReplicas(String collectionName) throws Exception {
    ZkStateReader zkStateReader = ZkStateReader.from(solrClient);
    zkStateReader.forceUpdateCollection(collectionName);
    ClusterState clusterState = zkStateReader.getClusterState();

    // Collect all node names hosting replicas
    Collection<Slice> slices = clusterState.getCollection(collectionName).getSlices();
    java.util.Set<String> nodesWithReplicas = new java.util.HashSet<>();
    for (Slice slice : slices) {
      for (Replica replica : slice.getReplicas()) {
        nodesWithReplicas.add(replica.getNodeName());
      }
    }

    // Find a node without replicas
    for (int i = 0; i < cluster.getNodeCount(); i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      // Extract node name from base URL (e.g., "localhost:8983_solr")
      String nodeName = baseUrl.replace("http://", "").replace("https://", "") + "_solr";
      if (!nodesWithReplicas.contains(nodeName)) {
        return i;
      }
    }

    return -1; // All nodes host replicas
  }

  /**
   * Wait for a collection to disappear from cluster state.
   */
  private void waitForCollectionToDisappear(String collectionName, ZkStateReader zkStateReader)
      throws Exception {
    int maxWaitSecs = 330;
    for (int i = 0; i < maxWaitSecs; i++) {
      ClusterState clusterState = zkStateReader.getClusterState();
      if (clusterState.getCollectionOrNull(collectionName) == null) {
        return;
      }
      Thread.sleep(1000);
    }
    throw new IllegalStateException(
        "Collection " + collectionName + " did not disappear within " + maxWaitSecs + " seconds");
  }
}
