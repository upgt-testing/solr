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
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;

/**
 * ProcessBased test for Collection API operations.
 *
 * <p>Simplified/reduced version of TestCollectionAPI focusing on core Collection API operations:
 * create, list, modify, cluster status, validation.
 *
 * <p>Note: Original TestCollectionAPI extends AbstractFullDistribZkTestBase (1389 lines) which is
 * incompatible with ProcessBased testing. This reduced version preserves key Collection API test
 * logic using checkpoint-based upgrade testing.
 */
public class TestCollectionAPI_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final String COLLECTION_NAME = "testcollection";
  private static final String COLLECTION_NAME1 = "testcollection1";

  /**
   * Tests basic Collection API operations: create, list, modify, cluster status.
   */
  @Test
  public void testCollectionAPI_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestCollectionAPI();
  }

  @Test
  public void testCollectionAPI_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestCollectionAPI();
  }

  @Test
  public void testCollectionAPI_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    doTestCollectionAPI();
  }

  private void doTestCollectionAPI() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(2)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset
    Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
    cluster.uploadConfigSet(configDir, "conf1");

    // Create first collection (2 shards, 2 replicas)
    CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", 2, 2)
        .process(solrClient);

    // Create second collection (1 shard, 1 replica)
    CollectionAdminRequest.createCollection(COLLECTION_NAME1, "conf1", 1, 1)
        .process(solrClient);

    checkpoint("AFTER_COLLECTION_CREATE");

    // Test list collections
    testListCollection();

    // Test cluster status
    testClusterStatusNoCollection();
    testClusterStatusWithCollection();

    // Test modify collection
    testModifyCollection();

    // Test validation
    testCollectionNameValidation();
  }

  private void testListCollection() throws Exception {
    List<String> collectionList = CollectionAdminRequest.listCollections(solrClient);
    assertTrue(
        "Collection list should contain " + COLLECTION_NAME,
        collectionList.contains(COLLECTION_NAME));
    assertTrue(
        "Collection list should contain " + COLLECTION_NAME1,
        collectionList.contains(COLLECTION_NAME1));
  }

  private void testClusterStatusNoCollection() throws Exception {
    NamedList<Object> rsp =
        CollectionAdminRequest.getClusterStatus().process(solrClient).getResponse();

    NamedList<?> cluster = (NamedList<?>) rsp.get("cluster");
    assertNotNull("Cluster state should not be null", cluster);

    Map<?, ?> collections = (Map<?, ?>) cluster.get("collections");
    assertNotNull("Collections should not be null in cluster state", collections);
    assertTrue(
        "Should have at least 2 collections, got: " + collections.size(),
        collections.size() >= 2);
  }

  private void testClusterStatusWithCollection() throws Exception {
    NamedList<Object> rsp =
        CollectionAdminRequest.getClusterStatus()
            .setCollectionName(COLLECTION_NAME)
            .process(solrClient)
            .getResponse();

    NamedList<?> cluster = (NamedList<?>) rsp.get("cluster");
    assertNotNull("Cluster state should not be null", cluster);

    Map<?, ?> collections = (Map<?, ?>) cluster.get("collections");
    assertNotNull("Collections should not be null in cluster state", collections);
    assertEquals("Should have exactly 1 collection in filtered response", 1, collections.size());
    assertTrue(
        "Collection should be " + COLLECTION_NAME, collections.containsKey(COLLECTION_NAME));

    Map<?, ?> collection = (Map<?, ?>) collections.get(COLLECTION_NAME);
    assertNotNull("Collection details should not be null", collection);

    // Verify shards
    Map<?, ?> shards = (Map<?, ?>) collection.get("shards");
    assertNotNull("Shards should not be null", shards);
    assertEquals("Should have 2 shards", 2, shards.size());
  }

  private void testModifyCollection() throws Exception {
    // Modify collection replicationFactor property
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("action", CollectionParams.CollectionAction.MODIFYCOLLECTION.toString());
    params.set("collection", COLLECTION_NAME);
    params.set("replicationFactor", 25);
    QueryRequest request = new QueryRequest(params);
    request.setPath("/admin/collections");
    solrClient.request(request);

    // Verify the modification
    NamedList<Object> rsp =
        CollectionAdminRequest.getClusterStatus()
            .setCollectionName(COLLECTION_NAME)
            .process(solrClient)
            .getResponse();

    NamedList<?> cluster = (NamedList<?>) rsp.get("cluster");
    Map<?, ?> collections = (Map<?, ?>) cluster.get("collections");
    Map<?, ?> collectionProperties = (Map<?, ?>) collections.get(COLLECTION_NAME);
    assertEquals(
        "Replication factor should be updated to 25",
        "25",
        collectionProperties.get("replicationFactor"));

    // Remove the property
    params = new ModifiableSolrParams();
    params.set("action", CollectionParams.CollectionAction.MODIFYCOLLECTION.toString());
    params.set("collection", COLLECTION_NAME);
    params.set("replicationFactor", "");
    request = new QueryRequest(params);
    request.setPath("/admin/collections");
    solrClient.request(request);

    // Verify property removed
    rsp =
        CollectionAdminRequest.getClusterStatus()
            .setCollectionName(COLLECTION_NAME)
            .process(solrClient)
            .getResponse();
    cluster = (NamedList<?>) rsp.get("cluster");
    collections = (Map<?, ?>) cluster.get("collections");
    collectionProperties = (Map<?, ?>) collections.get(COLLECTION_NAME);
    assertTrue(
        "Replication factor property should be removed or null",
        collectionProperties.get("replicationFactor") == null
            || "".equals(collectionProperties.get("replicationFactor")));
  }

  private void testCollectionNameValidation() throws Exception {
    // Test invalid collection names
    String[] invalidNames = {
      ".leadingPeriod",
      "-leadingHyphen",
      "_leadingUnderscore",
      "contains space",
      "contains/slash"
    };

    for (String invalidName : invalidNames) {
      try {
        CollectionAdminRequest.createCollection(invalidName, "conf1", 1, 1).process(solrClient);
        fail("Should have failed to create collection with invalid name: " + invalidName);
      } catch (Exception e) {
        // Expected - invalid collection name should fail
        assertTrue(
            "Error message should indicate invalid collection name for: " + invalidName,
            e.getMessage().contains("Invalid") || e.getMessage().contains("invalid"));
      }
    }
  }

  /**
   * Tests collection recreation after failure - verifies that failed collection creation can be
   * retried.
   */
  @Test
  public void testRecreateCollectionAfterFailure_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestRecreateCollectionAfterFailure();
  }

  @Test
  public void testRecreateCollectionAfterFailure_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestRecreateCollectionAfterFailure();
  }

  private void doTestRecreateCollectionAfterFailure() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset
    Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
    cluster.uploadConfigSet(configDir, "conf1");

    final String collName = "recreateAfterFailure";

    // Try to create a collection with invalid configuration (should fail)
    try {
      CollectionAdminRequest.createCollection(collName, "nonexistent_config", 1, 1)
          .process(solrClient);
      fail("Should have failed with non-existent config");
    } catch (Exception e) {
      // Expected failure
    }

    // Verify collection was not created
    ClusterState clusterState = solrClient.getClusterState();
    DocCollection coll = clusterState.getCollectionOrNull(collName);
    assertTrue("Collection should not exist after failed creation", coll == null);

    // Now create it successfully
    CollectionAdminRequest.createCollection(collName, "conf1", 1, 1).process(solrClient);

    // Verify collection exists
    clusterState = solrClient.getClusterState();
    coll = clusterState.getCollectionOrNull(collName);
    assertNotNull("Collection should exist after successful creation", coll);
  }
}
