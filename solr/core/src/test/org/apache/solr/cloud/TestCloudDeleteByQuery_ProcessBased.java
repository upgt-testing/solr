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
import static com.carrotsearch.randomizedtesting.RandomizedTest.getRandom;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.apache.solr.SolrTestCaseJ4.params;
import static com.carrotsearch.randomizedtesting.RandomizedTest.rarely;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomInt;

import static org.hamcrest.Matchers.containsString;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import static org.apache.lucene.tests.util.LuceneTestCase.expectThrows;
import static org.hamcrest.MatcherAssert.assertThat;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased upgrade test for delete-by-query functionality in SolrCloud.
 *
 * <p>Transformed from {@link TestCloudDeleteByQuery} to support checkpoint-based upgrade testing
 * with real Solr processes.
 */
public class TestCloudDeleteByQuery_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final int NUM_SHARDS = 2;
  private static final int REPLICATION_FACTOR = 2;
  private static final int NUM_SERVERS = 5;

  private static final String COLLECTION_NAME = "test_col";

  /** A collection specific client for operations at the cloud level */
  private CloudSolrClient collectionClient;

  /** A client for talking directly to the leader of shard1 */
  private SolrClient sOneLeaderClient;

  /** A client for talking directly to the leader of shard2 */
  private SolrClient sTwoLeaderClient;

  /** A client for talking directly to a passive replica of shard1 */
  private SolrClient sOneNonLeaderClient;

  /** A client for talking directly to a passive replica of shard2 */
  private SolrClient sTwoNonLeaderClient;

  /** A client for talking directly to a node that has no piece of the collection */
  private SolrClient noCollectionClient;

  /** id field doc routing prefix for shard1 */
  private static final String S_ONE_PRE = "abc!";

  /** id field doc routing prefix for shard2 */
  private static final String S_TWO_PRE = "XYZ!";

  @Test
  public void testMalformedDBQs_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestMalformedDBQs();
  }

  @Test
  public void testMalformedDBQs_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestMalformedDBQs();
  }

  @Test
  public void testMalformedDBQs_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    runTestMalformedDBQs();
  }

  @Test
  public void testDBQWithUnsupportedQuery_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestDBQWithUnsupportedQuery();
  }

  @Test
  public void testDBQWithUnsupportedQuery_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestDBQWithUnsupportedQuery();
  }

  @Test
  public void testDBQWithUnsupportedQuery_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    runTestDBQWithUnsupportedQuery();
  }

  private void setupCluster() throws Exception {
    final String configName = "solrCloudCollectionConfig";
    final Path configDir =
        Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "_default", "conf");

    // Create cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(NUM_SERVERS)
            .withStartVersionFromSystemProperty()
            .withUpgradeVersionFromSystemProperty()
            .build();
    cluster.start();    // Upload config
    cluster.uploadConfigSet(configDir, configName);

    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Create collection with custom properties
    Map<String, String> collectionProperties = new HashMap<>();
    collectionProperties.put("config", "solrconfig-tlog.xml");
    collectionProperties.put("schema", "schema15.xml"); // string id for doc routing prefix

    CollectionAdminRequest.createCollection(
            COLLECTION_NAME, configName, NUM_SHARDS, REPLICATION_FACTOR)
        .setProperties(collectionProperties)
        .process(solrClient);

    collectionClient = new CloudSolrClient.Builder(
        java.util.List.of(cluster.getZkHost()), java.util.Optional.empty())
        .build();
    collectionClient.setDefaultCollection(COLLECTION_NAME);

    checkpoint("AFTER_COLLECTION_CREATE");

    // Initialize clients for specific replicas
    initializeReplicaClients();

    // Verify routing prefixes
    verifyRoutingPrefixes();
  }

  private void initializeReplicaClients() throws Exception {
    ZkStateReader zkStateReader = ((org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider)
        solrClient.getClusterStateProvider()).getZkStateReader();

    // Build URL map from node index
    HashMap<String, String> urlMap = new HashMap<>();
    for (int i = 0; i < NUM_SERVERS; i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      // Extract node key from URL (similar to original test's approach)
      String nodeKey = baseUrl.replace("http://", "").replace("/", "_");
      urlMap.put(nodeKey, baseUrl);
    }

    ClusterState clusterState = zkStateReader.getClusterState();
    for (Slice slice : clusterState.getCollection(COLLECTION_NAME).getSlices()) {
      String shardName = slice.getName();
      Replica leader = slice.getLeader();
      assertNotNull("slice has null leader: " + slice, leader);
      assertNotNull("slice leader has null node name: " + slice, leader.getNodeName());

      // Find leader URL
      String leaderUrl = null;
      String leaderNodeKey = leader.getNodeName().replace(":", "_").replace("/", "_");
      for (Map.Entry<String, String> entry : urlMap.entrySet()) {
        if (entry.getKey().contains(leaderNodeKey.split("_")[1])) { // Match port
          leaderUrl = entry.getValue();
          break;
        }
      }
      assertNotNull(
          "could not find URL for " + shardName + " leader: " + leader.getNodeName(), leaderUrl);
      assertEquals(
          "expected two total replicas for: " + slice.getName(), 2, slice.getReplicas().size());

      String passiveUrl = null;

      for (Replica replica : slice.getReplicas()) {
        if (!replica.equals(leader)) {
          String replicaNodeKey = replica.getNodeName().replace(":", "_").replace("/", "_");
          for (Map.Entry<String, String> entry : urlMap.entrySet()) {
            if (entry.getKey().contains(replicaNodeKey.split("_")[1])) { // Match port
              passiveUrl = entry.getValue();
              break;
            }
          }
          assertNotNull(
              "could not find URL for " + shardName + " replica: " + replica.getNodeName(),
              passiveUrl);
        }
      }
      assertNotNull("could not find URL for " + shardName + " replica", passiveUrl);

      if (shardName.equals("shard1")) {
        sOneLeaderClient = new HttpSolrClient.Builder(leaderUrl + "/" + COLLECTION_NAME).build();
        sOneNonLeaderClient = new HttpSolrClient.Builder(passiveUrl + "/" + COLLECTION_NAME).build();
      } else if (shardName.equals("shard2")) {
        sTwoLeaderClient = new HttpSolrClient.Builder(leaderUrl + "/" + COLLECTION_NAME).build();
        sTwoNonLeaderClient = new HttpSolrClient.Builder(passiveUrl + "/" + COLLECTION_NAME).build();
      } else {
        fail("unexpected shard: " + shardName);
      }
    }

    // Find a node not hosting the collection
    for (int i = 0; i < NUM_SERVERS; i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      boolean hostsCollection = false;
      for (Slice slice : clusterState.getCollection(COLLECTION_NAME).getSlices()) {
        for (Replica replica : slice.getReplicas()) {
          if (replica.getBaseUrl().equals(baseUrl)) {
            hostsCollection = true;
            break;
          }
        }
        if (hostsCollection) break;
      }
      if (!hostsCollection) {
        noCollectionClient = new HttpSolrClient.Builder(baseUrl + "/" + COLLECTION_NAME).build();
        break;
      }
    }

    assertNotNull(sOneLeaderClient);
    assertNotNull(sTwoLeaderClient);
    assertNotNull(sOneNonLeaderClient);
    assertNotNull(sTwoNonLeaderClient);
    assertNotNull(noCollectionClient);
  }

  private void verifyRoutingPrefixes() throws Exception {
    // Sanity check that our S_ONE_PRE & S_TWO_PRE really do map to shard1 & shard2 with default
    // routing
    assertEquals(
        0,
        collectionClient
            .add(doc(f("id", S_ONE_PRE + getRandom().nextInt()), f("expected_shard_s", "shard1")))
            .getStatus());
    assertEquals(
        0,
        collectionClient
            .add(doc(f("id", S_TWO_PRE + getRandom().nextInt()), f("expected_shard_s", "shard2")))
            .getStatus());
    assertEquals(0, collectionClient.commit().getStatus());
    SolrDocumentList docs =
        collectionClient
            .query(
                params(
                    "q", "*:*",
                    "fl", "id,expected_shard_s,[shard]"))
            .getResults();
    assertEquals(2, docs.getNumFound());
    assertEquals(2, docs.size());
    for (SolrDocument doc : docs) {
      String expected = doc.getFirstValue("expected_shard_s").toString();
      String docShard = doc.getFirstValue("[shard]").toString();
      assertTrue(
          "shard routing prefixes don't seem to be aligned anymore, "
              + "did someone change the default routing rules? "
              + "and/or the the default shard name rules? "
              + "and/or the numShards used by this test? ... "
              + expected
              + " is not the same as [shard] == '"
              + docShard
              + "' ... for docId == "
              + doc.getFirstValue("id"),
          docShard.equals(expected));
    }
  }

  private void clearCloudCollection() throws Exception {
    assertEquals(0, collectionClient.deleteByQuery("*:*").getStatus());
    assertEquals(0, collectionClient.commit().getStatus());
  }

  private void runTestMalformedDBQs() throws Exception {
    setupCluster();

    clearCloudCollection();

    // Test with all clients
    testMalformedDBQ(collectionClient, "COLLECTION_CLIENT");
    testMalformedDBQ(sOneLeaderClient, "S_ONE_LEADER_CLIENT");
    testMalformedDBQ(sTwoLeaderClient, "S_TWO_LEADER_CLIENT");
    testMalformedDBQ(sOneNonLeaderClient, "S_ONE_NON_LEADER_CLIENT");
    testMalformedDBQ(sTwoNonLeaderClient, "S_TWO_NON_LEADER_CLIENT");
    testMalformedDBQ(noCollectionClient, "NO_COLLECTION_CLIENT");

    closeReplicaClients();
  }

  private void runTestDBQWithUnsupportedQuery() throws Exception {
    setupCluster();

    clearCloudCollection();

    // See SOLR-17677 for context
    final var unsupportedQueryExamples =
        new String[] {
          "{!join from=expected_shard_s to=expected_shard_s v=\"expected_shard_s:5\"}",
          "{!graph from=expected_shard_s to=expected_shard_s v=\"expected_shard_s:5\"}",
          "{!hash_range f=\"foo_i\" l=\"0\" u=\"12345\"}"
        };

    update(params()).add(doc(f("id", UUID.randomUUID().toString()))).process(collectionClient);
    for (String queryStr : unsupportedQueryExamples) {
      log.info("Testing unsupported DBQ query: {}", queryStr);
      SolrException e =
          expectThrows(
              SolrException.class,
              () -> {
                update(params()).deleteByQuery(queryStr).process(collectionClient);
              });
      assertEquals("Unexpected status code for DBQ with query " + queryStr, 400, e.code());
      final var expectedStr =
          "Query [" + queryStr + "] is not supported in delete-by-query operations";
      assertThat(e.getMessage(), containsString(expectedStr));
    }

    final var acceptableJoin =
        "{!join method=dvWithScore score=None from=expected_shard_s to=expected_shard_s v=\"expected_shard_s:5\"}";
    final var response = update(params()).deleteByQuery(acceptableJoin).process(collectionClient);
    assertEquals(0, response.getStatus());

    closeReplicaClients();
  }

  private void testMalformedDBQ(SolrClient client, String clientName) {
    assertNotNull(clientName + " client not initialized", client);
    SolrException e =
        expectThrows(
            SolrException.class,
            "Expected DBQ failure for " + clientName,
            () -> update(params()).deleteByQuery("foo_i:not_a_num").process(client));
    assertEquals(
        "not the expected DBQ failure for " + clientName + ": " + e.getMessage(), 400, e.code());
  }

  private void closeReplicaClients() {
    if (collectionClient != null) {
      try {
        collectionClient.close();
      } catch (Exception e) {
        log.warn("Error closing collection client", e);
      }
      collectionClient = null;
    }
    if (sOneLeaderClient != null) {
      try {
        sOneLeaderClient.close();
      } catch (Exception e) {
        log.warn("Error closing sOneLeaderClient", e);
      }
      sOneLeaderClient = null;
    }
    if (sTwoLeaderClient != null) {
      try {
        sTwoLeaderClient.close();
      } catch (Exception e) {
        log.warn("Error closing sTwoLeaderClient", e);
      }
      sTwoLeaderClient = null;
    }
    if (sOneNonLeaderClient != null) {
      try {
        sOneNonLeaderClient.close();
      } catch (Exception e) {
        log.warn("Error closing sOneNonLeaderClient", e);
      }
      sOneNonLeaderClient = null;
    }
    if (sTwoNonLeaderClient != null) {
      try {
        sTwoNonLeaderClient.close();
      } catch (Exception e) {
        log.warn("Error closing sTwoNonLeaderClient", e);
      }
      sTwoNonLeaderClient = null;
    }
    if (noCollectionClient != null) {
      try {
        noCollectionClient.close();
      } catch (Exception e) {
        log.warn("Error closing noCollectionClient", e);
      }
      noCollectionClient = null;
    }
  }

  public static UpdateRequest update(SolrParams params, SolrInputDocument... docs) {
    UpdateRequest r = new UpdateRequest();
    r.setParams(new ModifiableSolrParams(params));
    r.add(Arrays.asList(docs));
    return r;
  }

  public static SolrInputDocument doc(SolrInputField... fields) {
    SolrInputDocument doc = new SolrInputDocument();
    for (SolrInputField f : fields) {
      doc.put(f.getName(), f);
    }
    return doc;
  }

  public static SolrInputField f(String fieldName, Object... values) {
    SolrInputField f = new SolrInputField(fieldName);
    f.setValue(values);
    return f;
  }
}
