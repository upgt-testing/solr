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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.ToleratedUpdateError;
import org.apache.solr.common.ToleratedUpdateError.CmdType;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.Test;

/**
 * ProcessBased upgrade test for TolerantUpdateProcessor functionality in SolrCloud.
 *
 * <p>Tests that updates with tolerated errors work correctly across cluster upgrades,
 * including adds, deletes, and mixed operations across different routing scenarios.
 *
 * <p>Transformed from TestTolerantUpdateProcessorCloud to use checkpoint-based upgrade testing.
 */
public class TestTolerantUpdateProcessorCloud_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final int NUM_SHARDS = 2;
  private static final int REPLICATION_FACTOR = 2;
  private static final int NUM_SERVERS = 5;

  private static final String COLLECTION_NAME = "test_col";

  /** id field doc routing prefix for shard1 */
  private static final String S_ONE_PRE = "abc!";

  /** id field doc routing prefix for shard2 */
  private static final String S_TWO_PRE = "XYZ!";

  // Test-scoped clients (not static, created per test)
  private CloudSolrClient collectionClient;
  private SolrClient sOneLeaderClient;
  private SolrClient sTwoLeaderClient;
  private SolrClient sOneNonLeaderClient;
  private SolrClient sTwoNonLeaderClient;
  private SolrClient noCollectionClient;

  @Test
  public void testTolerantUpdateProcessor_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTolerantUpdateProcessorTest();
  }

  @Test
  public void testTolerantUpdateProcessor_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTolerantUpdateProcessorTest();
  }

  @Test
  public void testTolerantUpdateProcessor_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    runTolerantUpdateProcessorTest();
  }

  private void runTolerantUpdateProcessorTest() throws Exception {
    final String configName = "solrCloudCollectionConfig";
    final File configDir = new File(
        System.getProperty("tests.src.home", "solr/core/src") +
        File.separator + "test-files" + File.separator + "solr" +
        File.separator + "collection1" + File.separator + "conf");

    // Create cluster
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(NUM_SERVERS)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    cluster.waitForAllNodes(30);

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset and create collection
    cluster.uploadConfigSet(configDir.toPath(), configName);

    solrClient = cluster.getSolrClient();
    collectionClient = cluster.getSolrClient(COLLECTION_NAME);

    CollectionAdminRequest.createCollection(COLLECTION_NAME, configName, NUM_SHARDS, REPLICATION_FACTOR)
        .withProperty("config", "solrconfig-distrib-update-processor-chains.xml")
        .withProperty("schema", "schema15.xml") // string id for doc routing prefix
        .process(solrClient);

    cluster.waitForActiveCollection(COLLECTION_NAME, NUM_SHARDS, REPLICATION_FACTOR * NUM_SHARDS);

    checkpoint("AFTER_COLLECTION_CREATE");

    // Create specialized clients for different node types
    setupSpecializedClients();

    // Verify shard routing prefixes work as expected
    verifyShardRoutingPrefixes();

    // Run all test scenarios
    testSanityChecks();
    clearCollection();

    testVariousDeletes(collectionClient);
    clearCollection();
    testVariousDeletes(sOneLeaderClient);
    clearCollection();
    testVariousDeletes(sTwoLeaderClient);
    clearCollection();
    testVariousDeletes(sOneNonLeaderClient);
    clearCollection();
    testVariousDeletes(sTwoNonLeaderClient);
    clearCollection();
    testVariousDeletes(noCollectionClient);
    clearCollection();

    testVariousAdds(collectionClient);
    clearCollection();
    testVariousAdds(sOneLeaderClient);
    clearCollection();
    testVariousAdds(sTwoLeaderClient);
    clearCollection();
    testVariousAdds(sOneNonLeaderClient);
    clearCollection();
    testVariousAdds(sTwoNonLeaderClient);
    clearCollection();
    testVariousAdds(noCollectionClient);
    clearCollection();

    testAddsMixedWithDeletes(collectionClient);
    clearCollection();
    testAddsMixedWithDeletes(sOneLeaderClient);
    clearCollection();
    testAddsMixedWithDeletes(sTwoLeaderClient);
    clearCollection();
    testAddsMixedWithDeletes(sOneNonLeaderClient);
    clearCollection();
    testAddsMixedWithDeletes(sTwoNonLeaderClient);
    clearCollection();
    testAddsMixedWithDeletes(noCollectionClient);

    // Close specialized clients
    closeSpecializedClients();
  }

  private void setupSpecializedClients() throws Exception {
    ZkStateReader zkStateReader = cluster.getZkStateReader();

    // Build map of node URLs
    HashMap<String, String> urlMap = new HashMap<>();
    for (int i = 0; i < cluster.getNodeCount(); i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      // Extract host:port_path as key (matching original test's logic)
      String nodeKey = baseUrl.replace("http://", "").replace("https://", "").replace("/", "_");
      urlMap.put(nodeKey, baseUrl);
    }

    zkStateReader.forceUpdateCollection(COLLECTION_NAME);
    ClusterState clusterState = zkStateReader.getClusterState();

    for (Slice slice : clusterState.getCollection(COLLECTION_NAME).getSlices()) {
      String shardName = slice.getName();
      Replica leader = slice.getLeader();
      assertNotNull("slice has null leader: " + slice, leader);
      assertNotNull("slice leader has null node name: " + slice, leader.getNodeName());

      String leaderUrl = findUrlForNode(leader.getNodeName(), urlMap);
      assertNotNull("could not find URL for " + shardName + " leader: " + leader.getNodeName(), leaderUrl);
      assertEquals("expected two total replicas for: " + slice.getName(), 2, slice.getReplicas().size());

      String passiveUrl = null;
      for (Replica replica : slice.getReplicas()) {
        if (!replica.equals(leader)) {
          passiveUrl = findUrlForNode(replica.getNodeName(), urlMap);
          assertNotNull("could not find URL for " + shardName + " replica: " + replica.getNodeName(), passiveUrl);
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

    // Find a node that doesn't host any shard
    assertEquals("Should be exactly one server left (not hosting either shard)", 1, urlMap.size());
    String noCollectionUrl = urlMap.values().iterator().next();
    noCollectionClient = new HttpSolrClient.Builder(noCollectionUrl + "/" + COLLECTION_NAME).build();

    assertNotNull(sOneLeaderClient);
    assertNotNull(sTwoLeaderClient);
    assertNotNull(sOneNonLeaderClient);
    assertNotNull(sTwoNonLeaderClient);
    assertNotNull(noCollectionClient);
  }

  private String findUrlForNode(String nodeName, HashMap<String, String> urlMap) {
    // Try to find a matching URL in the map by checking if the nodeName is in any key
    for (String key : new ArrayList<>(urlMap.keySet())) {
      String url = urlMap.get(key);
      // Check if this URL corresponds to this node
      if (key.contains(nodeName.replace(":", "_").replace("/", "_"))) {
        urlMap.remove(key); // Remove from map so we don't reuse it
        return url;
      }
    }
    return null;
  }

  private void closeSpecializedClients() throws Exception {
    if (sOneLeaderClient != null) sOneLeaderClient.close();
    if (sTwoLeaderClient != null) sTwoLeaderClient.close();
    if (sOneNonLeaderClient != null) sOneNonLeaderClient.close();
    if (sTwoNonLeaderClient != null) sTwoNonLeaderClient.close();
    if (noCollectionClient != null) noCollectionClient.close();
    if (collectionClient != null) collectionClient.close();
  }

  private void clearCollection() throws Exception {
    assertEquals(0, collectionClient.deleteByQuery("*:*").getStatus());
    assertEquals(0, collectionClient.commit().getStatus());
  }

  private void verifyShardRoutingPrefixes() throws Exception {
    // Sanity check that our S_ONE_PRE & S_TWO_PRE really do map to shard1 & shard2 with default routing
    assertEquals(0, collectionClient.add(
        doc(f("id", S_ONE_PRE + "route1"), f("expected_shard_s", "shard1"))).getStatus());
    assertEquals(0, collectionClient.add(
        doc(f("id", S_TWO_PRE + "route2"), f("expected_shard_s", "shard2"))).getStatus());
    assertEquals(0, collectionClient.commit().getStatus());

    SolrDocumentList docs = collectionClient.query(
        params("q", "*:*", "fl", "id,expected_shard_s,[shard]")).getResults();
    assertEquals(2, docs.getNumFound());
    assertEquals(2, docs.size());

    for (SolrDocument doc : docs) {
      String expected = doc.getFirstValue("expected_shard_s").toString();
      String docShard = doc.getFirstValue("[shard]").toString();
      assertTrue("shard routing prefixes don't seem to be aligned anymore, " +
          "did someone change the default routing rules? " +
          "and/or the the default shard name rules? " +
          "and/or the numShards used by this test? ... " +
          expected + " is not the same as [shard] == '" + docShard + "' ... for docId == " +
          doc.getFirstValue("id"),
          docShard.equals(expected));
    }
  }

  private void testSanityChecks() throws Exception {
    // Verify some basic sanity checking of indexing & querying across the collection
    // w/o using our custom update processor chain

    assertEquals(0, collectionClient.add(doc(f("id", S_ONE_PRE + "1"), f("foo_i", 42))).getStatus());
    assertEquals(0, collectionClient.add(doc(f("id", S_TWO_PRE + "2"), f("foo_i", 66))).getStatus());
    assertEquals(0, collectionClient.commit().getStatus());

    for (SolrClient c : Arrays.asList(
        sOneLeaderClient, sTwoLeaderClient,
        sOneNonLeaderClient, sTwoNonLeaderClient,
        noCollectionClient, collectionClient)) {
      assertQueryDocIds(c, true, S_ONE_PRE + "1", S_TWO_PRE + "2");
      assertQueryDocIds(c, false, "id_not_exists");

      // Verify adding 2 broken docs causes a client exception
      SolrException e = expectThrows(SolrException.class,
          "did not get a top level exception when more then 10 docs failed",
          () -> update(params(),
              doc(f("id", S_ONE_PRE + "X"), f("foo_i", "bogus_val_X")),
              doc(f("id", S_TWO_PRE + "Y"), f("foo_i", "bogus_val_Y")))
              .process(c));
      assertEquals("not the type of error we were expecting (" + e.code() + "): " + e, 400, e.code());

      // Verify malformed deleteByQuery's fail
      e = expectThrows(SolrException.class,
          "sanity check for malformed DBQ didn't fail",
          () -> update(params()).deleteByQuery("foo_i:not_a_num").process(c));
      assertEquals("not the expected DBQ failure: " + e.getMessage(), 400, e.code());

      // Verify opportunistic concurrency deletions fail as we expect when docs are / aren't present
      for (UpdateRequest r : new UpdateRequest[]{
          update(params("commit", "true")).deleteById(S_ONE_PRE + "1", -1L),
          update(params("commit", "true")).deleteById(S_TWO_PRE + "2", -1L),
          update(params("commit", "true")).deleteById("id_not_exists", 1L)
      }) {
        e = expectThrows(SolrException.class,
            "sanity check for opportunistic concurrency delete didn't fail",
            () -> r.process(c));
        assertEquals("not the expected opportunistic concurrency failure code: " +
                r.toString() + " => " + e.getMessage(),
            409, e.code());
      }
    }
  }

  protected void testVariousDeletes(SolrClient client) throws Exception {
    assertNotNull("client not initialized", client);

    // 2 docs, one on each shard
    final String docId1 = S_ONE_PRE + "42";
    final String docId2 = S_TWO_PRE + "666";

    UpdateResponse rsp = null;

    // Add 1 doc to each shard
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", docId1), f("foo_i", "2001")),
        doc(f("id", docId2), f("foo_i", "1976")))
        .process(client);
    assertEquals(0, rsp.getStatus());

    // Attempt to delete individual doc id(s) that should fail because of opportunistic concurrency constraints
    for (String id : new String[]{docId1, docId2}) {
      rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
          .deleteById(id, -1L)
          .process(client);
      assertEquals(0, rsp.getStatus());
      assertUpdateTolerantErrors("failed opportunistic concurrent delId=" + id, rsp, delIErr(id));
    }

    // Multiple failed deletes from the same shard (via opportunistic concurrent w/ bogus ids)
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteById(S_ONE_PRE + "X", +1L)
        .deleteById(S_ONE_PRE + "Y", +1L)
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by id for 2 bogus docs",
        rsp, delIErr(S_ONE_PRE + "X"), delIErr(S_ONE_PRE + "Y"));
    assertQueryDocIds(client, true, docId1, docId2);

    // Multiple failed deletes from the diff shards due to opportunistic concurrency constraints
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteById(docId2, -1L)
        .deleteById(docId1, -1L)
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by id for 2 docs",
        rsp, delIErr(docId1), delIErr(docId2));
    assertQueryDocIds(client, true, docId1, docId2);

    // deleteByQuery using malformed query (fail)
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteByQuery("bogus_field:foo")
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by query",
        rsp, delQErr("bogus_field:foo"));
    assertQueryDocIds(client, true, docId1, docId2);

    // Mix 2 deleteByQuery, one malformed (fail), one that doesn't match anything (ok)
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteByQuery("bogus_field:foo")
        .deleteByQuery("foo_i:23")
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by query",
        rsp, delQErr("bogus_field:foo"));
    assertQueryDocIds(client, true, docId1, docId2);

    // Mix 2 deleteById using _version_=-1, one for real doc1 (fail), one for bogus id (ok)
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteById(docId1, -1L)
        .deleteById("bogus", -1L)
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by id: exists",
        rsp, delIErr(docId1));
    assertQueryDocIds(client, true, docId1, docId2);

    // Mix 2 deleteById using _version_=1, one for real doc1 (ok, deleted), one for bogus id (fail)
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteById(docId1, +1L)
        .deleteById("bogusId", +1L)
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by id: bogus",
        rsp, delIErr("bogusId"));
    assertQueryDocIds(client, false, docId1);
    assertQueryDocIds(client, true, docId2);

    // Mix 2 deleteByQuery, one malformed (fail), one that actually removes some docs (ok)
    assertQueryDocIds(client, true, docId2);
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"))
        .deleteByQuery("bogus_field:foo")
        .deleteByQuery("foo_i:1976")
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("failed opportunistic concurrent delete by query",
        rsp, delQErr("bogus_field:foo"));
    assertQueryDocIds(client, false, docId2);
  }

  protected void testVariousAdds(SolrClient client) throws Exception {
    assertNotNull("client not initialized", client);

    UpdateResponse rsp = null;

    // 2 docs that are both on shard1, the first one should fail
    for (int maxErrors : new int[]{-1, 2, 47, 10}) {
      // Regardless of which of these maxErrors values we use, behavior should be the same...
      rsp = update(params("update.chain", "tolerant-chain-max-errors-10",
              "maxErrors", "" + maxErrors, "commit", "true"),
          doc(f("id", S_ONE_PRE + "42"), f("foo_i", "bogus_value")),
          doc(f("id", S_ONE_PRE + "666"), f("foo_i", "1976")))
          .process(client);

      assertEquals(0, rsp.getStatus());
      assertUpdateTolerantAddErrors("single shard, 1st doc should fail", rsp, S_ONE_PRE + "42");
      assertEquals(0, client.commit().getStatus());
      assertQueryDocIds(client, false, S_ONE_PRE + "42");
      assertQueryDocIds(client, true, S_ONE_PRE + "666");

      // ...only diff should be that we get an accurate report of the effective maxErrors
      assertEquals(maxErrors, rsp.getResponseHeader().get("maxErrors"));
    }

    // 2 docs that are both on shard1, the second one should fail
    rsp = update(params("update.chain", "tolerant-chain-max-errors-not-set", "commit", "true"),
        doc(f("id", S_ONE_PRE + "55"), f("foo_i", "1976")),
        doc(f("id", S_ONE_PRE + "77"), f("foo_i", "bogus_val")))
        .process(client);

    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantAddErrors("single shard, 2nd doc should fail", rsp, S_ONE_PRE + "77");
    assertQueryDocIds(client, false, S_ONE_PRE + "77");
    assertQueryDocIds(client, true, S_ONE_PRE + "666", S_ONE_PRE + "55");
    // Since maxErrors is unset, we should get an "unlimited" value back
    assertEquals(-1, rsp.getResponseHeader().get("maxErrors"));

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // 2 docs on 2 diff shards, first of which should fail
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", S_ONE_PRE + "42"), f("foo_i", "bogus_value")),
        doc(f("id", S_TWO_PRE + "666"), f("foo_i", "1976")))
        .process(client);

    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantAddErrors("two shards, 1st doc should fail", rsp, S_ONE_PRE + "42");
    assertEquals(0, client.commit().getStatus());
    assertQueryDocIds(client, false, S_ONE_PRE + "42");
    assertQueryDocIds(client, true, S_TWO_PRE + "666");

    // 2 docs on 2 diff shards, second of which should fail
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", S_ONE_PRE + "55"), f("foo_i", "1976")),
        doc(f("id", S_TWO_PRE + "77"), f("foo_i", "bogus_val")))
        .process(client);

    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantAddErrors("two shards, 2nd doc should fail", rsp, S_TWO_PRE + "77");
    assertQueryDocIds(client, false, S_TWO_PRE + "77");
    assertQueryDocIds(client, true, S_TWO_PRE + "666", S_ONE_PRE + "55");

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // Many docs from diff shards, 1 from each shard should fail
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", S_ONE_PRE + "11")),
        doc(f("id", S_TWO_PRE + "21")),
        doc(f("id", S_ONE_PRE + "12")),
        doc(f("id", S_TWO_PRE + "22"), f("foo_i", "bogus_val")),
        doc(f("id", S_ONE_PRE + "13")),
        doc(f("id", S_TWO_PRE + "23")),
        doc(f("id", S_ONE_PRE + "14")),
        doc(f("id", S_TWO_PRE + "24")),
        doc(f("id", S_ONE_PRE + "15"), f("foo_i", "bogus_val")),
        doc(f("id", S_TWO_PRE + "25")),
        doc(f("id", S_ONE_PRE + "16")),
        doc(f("id", S_TWO_PRE + "26")))
        .process(client);

    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantAddErrors("many docs, 1 from each shard should fail",
        rsp, S_ONE_PRE + "15", S_TWO_PRE + "22");
    assertQueryDocIds(client, false, S_TWO_PRE + "22", S_ONE_PRE + "15");
    assertQueryDocIds(client, true,
        S_ONE_PRE + "11", S_TWO_PRE + "21", S_ONE_PRE + "12",
        S_ONE_PRE + "13", S_TWO_PRE + "23", S_ONE_PRE + "14",
        S_TWO_PRE + "24", S_TWO_PRE + "25", S_ONE_PRE + "16", S_TWO_PRE + "26");

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // Many docs from diff shards, 1 from each shard should fail and 1 w/o uniqueKey
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", S_ONE_PRE + "11")),
        doc(f("id", S_TWO_PRE + "21")),
        doc(f("id", S_ONE_PRE + "12")),
        doc(f("id", S_TWO_PRE + "22"), f("foo_i", "bogus_val")),
        doc(f("id", S_ONE_PRE + "13")),
        doc(f("id", S_TWO_PRE + "23")),
        doc(f("foo_i", "42")), // no "id"
        doc(f("id", S_ONE_PRE + "14")),
        doc(f("id", S_TWO_PRE + "24")),
        doc(f("id", S_ONE_PRE + "15"), f("foo_i", "bogus_val")),
        doc(f("id", S_TWO_PRE + "25")),
        doc(f("id", S_ONE_PRE + "16")),
        doc(f("id", S_TWO_PRE + "26")))
        .process(client);

    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantAddErrors("many docs, 1 from each shard (+ no id) should fail",
        rsp, S_ONE_PRE + "15", "(unknown)", S_TWO_PRE + "22");
    assertQueryDocIds(client, false, S_TWO_PRE + "22", S_ONE_PRE + "15");
    assertQueryDocIds(client, true,
        S_ONE_PRE + "11", S_TWO_PRE + "21", S_ONE_PRE + "12",
        S_ONE_PRE + "13", S_TWO_PRE + "23", S_ONE_PRE + "14",
        S_TWO_PRE + "24", S_TWO_PRE + "25", S_ONE_PRE + "16", S_TWO_PRE + "26");

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // Many docs from diff shards, more than 10 (total) should fail
    SolrException e = expectThrows(SolrException.class,
        "did not get a top level exception when more then 10 docs failed",
        () -> update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
            doc(f("id", S_ONE_PRE + "11")),
            doc(f("id", S_TWO_PRE + "21"), f("foo_i", "bogus_val")),
            doc(f("id", S_ONE_PRE + "12")),
            doc(f("id", S_TWO_PRE + "22"), f("foo_i", "bogus_val")),
            doc(f("id", S_ONE_PRE + "13")),
            doc(f("id", S_TWO_PRE + "23"), f("foo_i", "bogus_val")),
            doc(f("id", S_ONE_PRE + "14"), f("foo_i", "bogus_val")),
            doc(f("id", S_TWO_PRE + "24")),
            doc(f("id", S_ONE_PRE + "15"), f("foo_i", "bogus_val")),
            doc(f("id", S_TWO_PRE + "25")),
            doc(f("id", S_ONE_PRE + "16"), f("foo_i", "bogus_val")),
            doc(f("id", S_TWO_PRE + "26"), f("foo_i", "bogus_val")),
            doc(f("id", S_ONE_PRE + "17")),
            doc(f("id", S_TWO_PRE + "27")),
            doc(f("id", S_ONE_PRE + "18"), f("foo_i", "bogus_val")),
            doc(f("id", S_TWO_PRE + "28"), f("foo_i", "bogus_val")),
            doc(f("id", S_ONE_PRE + "19"), f("foo_i", "bogus_val")),
            doc(f("id", S_TWO_PRE + "29"), f("foo_i", "bogus_val")),
            doc(f("id", S_ONE_PRE + "10")), // may be skipped, more than 10 fails
            doc(f("id", S_TWO_PRE + "20")) // may be skipped, more than 10 fails
        ).process(client));

    {
      // We can't make any reliable assertions about the error message, because
      // it varies based on how the request was routed -- see SOLR-8830
      assertEquals("not the type of error we were expecting (" + e.code() + "): " + e,
          // NOTE: we always expect a 400 because we know that's what we would get from these types
          // of errors on a single node setup -- a 5xx type error isn't something we should have triggered
          400, e.code());

      // Verify that the Exceptions' metadata can tell us what failed.
      NamedList<String> remoteErrMetadata = e.getMetadata();
      assertNotNull("no metadata in: " + e, remoteErrMetadata);
      Set<ToleratedUpdateError> actualKnownErrs = new LinkedHashSet<ToleratedUpdateError>(remoteErrMetadata.size());
      int actualKnownErrsCount = 0;
      for (int i = 0; i < remoteErrMetadata.size(); i++) {
        ToleratedUpdateError err = ToleratedUpdateError.parseMetadataIfToleratedUpdateError(
            remoteErrMetadata.getName(i), remoteErrMetadata.getVal(i));
        if (null == err) {
          // some metadata unrelated to this update processor
          continue;
        }
        actualKnownErrsCount++;
        actualKnownErrs.add(err);
      }
      assertEquals("wrong number of errors in metadata: " + remoteErrMetadata,
          11, actualKnownErrsCount);
      assertEquals("at least one dup error in metadata: " + remoteErrMetadata,
          actualKnownErrsCount, actualKnownErrs.size());
      for (ToleratedUpdateError err : actualKnownErrs) {
        assertEquals("only expected type of error is ADD: " + err, CmdType.ADD, err.getType());
        assertTrue("failed err msg didn't match expected value: " + err,
            err.getMessage().contains("bogus_val"));
      }
    }

    assertEquals(0, client.commit().getStatus()); // need to force since update didn't finish
    assertQueryDocIds(client, false, // explicitly failed
        S_TWO_PRE + "21", S_TWO_PRE + "22", S_TWO_PRE + "23",
        S_ONE_PRE + "14", S_ONE_PRE + "15", S_ONE_PRE + "16", S_TWO_PRE + "26",
        S_ONE_PRE + "18", S_TWO_PRE + "28", S_ONE_PRE + "19", S_TWO_PRE + "29"
        // // we can't assert for sure these docs were skipped
        // // depending on shard we hit, they may have been added async before errors were exceeded
        // , S_ONE_PRE + "10", S_TWO_PRE + "20" // skipped
    );
    assertQueryDocIds(client, true,
        S_ONE_PRE + "11", S_ONE_PRE + "12", S_ONE_PRE + "13",
        S_TWO_PRE + "24", S_TWO_PRE + "25", S_ONE_PRE + "17", S_TWO_PRE + "27");

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // Many docs from diff shards, more than 10 from a single shard (two) should fail
    e = expectThrows(SolrException.class,
        "did not get a top level exception when more then 10 docs failed",
        () -> {
          ArrayList<SolrInputDocument> docs = new ArrayList<SolrInputDocument>(30);
          docs.add(doc(f("id", S_ONE_PRE + "z")));
          docs.add(doc(f("id", S_TWO_PRE + "z")));
          docs.add(doc(f("id", S_ONE_PRE + "y")));
          docs.add(doc(f("id", S_TWO_PRE + "y")));
          for (int i = 0; i < 11; i++) {
            docs.add(doc(f("id", S_ONE_PRE + i)));
            docs.add(doc(f("id", S_TWO_PRE + i), f("foo_i", "bogus_val")));
          }
          docs.add(doc(f("id", S_ONE_PRE + "x"))); // may be skipped, more than 10 fails
          docs.add(doc(f("id", S_TWO_PRE + "x"))); // may be skipped, more than 10 fails

          update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
              docs.toArray(new SolrInputDocument[0]))
              .process(client);
        });

    {
      // We can't make any reliable assertions about the error message, because
      // it varies based on how the request was routed -- see SOLR-8830
      assertEquals("not the type of error we were expecting (" + e.code() + "): " + e,
          // NOTE: we always expect a 400 because we know that's what we would get from these types
          // of errors on a single node setup -- a 5xx type error isn't something we should have triggered
          400, e.code());

      // Verify that the Exceptions' metadata can tell us what failed.
      NamedList<String> remoteErrMetadata = e.getMetadata();
      assertNotNull("no metadata in: " + e, remoteErrMetadata);
      Set<ToleratedUpdateError> actualKnownErrs = new LinkedHashSet<ToleratedUpdateError>(remoteErrMetadata.size());
      int actualKnownErrsCount = 0;
      for (int i = 0; i < remoteErrMetadata.size(); i++) {
        ToleratedUpdateError err = ToleratedUpdateError.parseMetadataIfToleratedUpdateError(
            remoteErrMetadata.getName(i), remoteErrMetadata.getVal(i));
        if (null == err) {
          // some metadata unrelated to this update processor
          continue;
        }
        actualKnownErrsCount++;
        actualKnownErrs.add(err);
      }
      assertEquals("wrong number of errors in metadata: " + remoteErrMetadata,
          11, actualKnownErrsCount);
      assertEquals("at least one dup error in metadata: " + remoteErrMetadata,
          actualKnownErrsCount, actualKnownErrs.size());
      for (ToleratedUpdateError err : actualKnownErrs) {
        assertEquals("only expected type of error is ADD: " + err, CmdType.ADD, err.getType());
        assertTrue("failed id had unexpected prefix: " + err, err.getId().startsWith(S_TWO_PRE));
        assertTrue("failed err msg didn't match expected value: " + err,
            err.getMessage().contains("bogus_val"));
      }
    }

    assertEquals(0, client.commit().getStatus()); // need to force since update didn't finish
    assertQueryDocIds(client, true,
        S_ONE_PRE + "z", S_ONE_PRE + "y", S_TWO_PRE + "z", S_TWO_PRE + "y", // first
        S_ONE_PRE + "0", S_ONE_PRE + "1", S_ONE_PRE + "2", S_ONE_PRE + "3",
        S_ONE_PRE + "4", S_ONE_PRE + "5", S_ONE_PRE + "6", S_ONE_PRE + "7",
        S_ONE_PRE + "8", S_ONE_PRE + "9");
    assertQueryDocIds(client, false, // explicitly failed
        S_TWO_PRE + "0", S_TWO_PRE + "1", S_TWO_PRE + "2", S_TWO_PRE + "3",
        S_TWO_PRE + "4", S_TWO_PRE + "5", S_TWO_PRE + "6", S_TWO_PRE + "7",
        S_TWO_PRE + "8", S_TWO_PRE + "9"
        // // we can't assert for sure these docs were skipped
        // // depending on shard we hit, they may have been added async before errors were exceeded
        // , S_ONE_PRE + "x", S_TWO_PRE + "x", // skipped
    );

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // Many docs from diff shards, more than 10 don't have any uniqueKey specified
    e = expectThrows(SolrException.class,
        "did not get a top level exception when more then 10 docs missing uniqueKey",
        () -> {
          ArrayList<SolrInputDocument> docs = new ArrayList<SolrInputDocument>(30);
          docs.add(doc(f("id", S_ONE_PRE + "z")));
          docs.add(doc(f("id", S_TWO_PRE + "z")));
          docs.add(doc(f("id", S_ONE_PRE + "y")));
          docs.add(doc(f("id", S_TWO_PRE + "y")));
          for (int i = 0; i < 11; i++) {
            // no "id" field
            docs.add(doc(f("foo_i", "" + i)));
          }
          docs.add(doc(f("id", S_ONE_PRE + "x"))); // may be skipped, more than 10 fails
          docs.add(doc(f("id", S_TWO_PRE + "x"))); // may be skipped, more than 10 fails

          update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
              docs.toArray(new SolrInputDocument[0]))
              .process(client);
        });

    {
      // We can't make any reliable assertions about the error message, because
      // it varies based on how the request was routed -- see SOLR-8830
      assertEquals("not the type of error we were expecting (" + e.code() + "): " + e,
          // NOTE: we always expect a 400 because we know that's what we would get from these types
          // of errors on a single node setup -- a 5xx type error isn't something we should have triggered
          400, e.code());

      // Verify that the Exceptions' metadata can tell us what failed.
      NamedList<String> remoteErrMetadata = e.getMetadata();
      assertNotNull("no metadata in: " + e, remoteErrMetadata);
      int actualKnownErrsCount = 0;
      for (int i = 0; i < remoteErrMetadata.size(); i++) {
        ToleratedUpdateError err = ToleratedUpdateError.parseMetadataIfToleratedUpdateError(
            remoteErrMetadata.getName(i), remoteErrMetadata.getVal(i));
        if (null == err) {
          // some metadata unrelated to this update processor
          continue;
        }
        actualKnownErrsCount++;
        assertEquals("only expected type of error is ADD: " + err, CmdType.ADD, err.getType());
        assertTrue("failed id didn't match 'unknown': " + err, err.getId().contains("unknown"));
      }
      assertEquals("wrong number of errors in metadata: " + remoteErrMetadata,
          11, actualKnownErrsCount);
    }

    assertEquals(0, client.commit().getStatus()); // need to force since update didn't finish
    assertQueryDocIds(client, true,
        S_ONE_PRE + "z", S_ONE_PRE + "y", S_TWO_PRE + "z", S_TWO_PRE + "y" // first
        // // we can't assert for sure these docs were skipped or added
        // // depending on shard we hit, they may have been added async before errors were exceeded
        // , S_ONE_PRE + "x", S_TWO_PRE + "x" // skipped
    );

    // Clean slate
    assertEquals(0, client.deleteByQuery("*:*").getStatus());

    // Many docs from diff shards, more than 10 from a single shard (two) should fail but
    // request should still succeed because of maxErrors=-1 param
    ArrayList<SolrInputDocument> docs = new ArrayList<SolrInputDocument>(30);
    ArrayList<ExpectedErr> expectedErrs = new ArrayList<ExpectedErr>(30);
    docs.add(doc(f("id", S_ONE_PRE + "z")));
    docs.add(doc(f("id", S_TWO_PRE + "z")));
    docs.add(doc(f("id", S_ONE_PRE + "y")));
    docs.add(doc(f("id", S_TWO_PRE + "y")));
    for (int i = 0; i < 11; i++) {
      docs.add(doc(f("id", S_ONE_PRE + i)));
      docs.add(doc(f("id", S_TWO_PRE + i), f("foo_i", "bogus_val")));
      expectedErrs.add(addErr(S_TWO_PRE + i));
    }
    docs.add(doc(f("id", S_ONE_PRE + "x")));
    docs.add(doc(f("id", S_TWO_PRE + "x")));

    UpdateResponse rsp = update(params("update.chain", "tolerant-chain-max-errors-10",
            "maxErrors", "-1", "commit", "true"),
        docs.toArray(new SolrInputDocument[0]))
        .process(client);
    assertUpdateTolerantErrors("many docs from shard2 fail, but req should succeed",
        rsp, expectedErrs.toArray(new ExpectedErr[0]));
    assertQueryDocIds(client, true,
        S_ONE_PRE + "z", S_ONE_PRE + "y", S_TWO_PRE + "z", S_TWO_PRE + "y", // first
        S_ONE_PRE + "x", S_TWO_PRE + "x" // later
    );
  }

  protected void testAddsMixedWithDeletes(SolrClient client) throws Exception {
    assertNotNull("client not initialized", client);

    // 3 doc ids, exactly one on shard1
    final String docId1 = S_ONE_PRE + "42";
    final String docId21 = S_TWO_PRE + "42";
    final String docId22 = S_TWO_PRE + "666";

    UpdateResponse rsp = null;

    // Add 2 docs, one to each shard
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", docId1), f("foo_i", "2001")),
        doc(f("id", docId21), f("foo_i", "1976")))
        .process(client);
    assertEquals(0, rsp.getStatus());

    // Add failure on shard2, delete failure on shard1
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", docId22), f("foo_i", "not_a_num")))
        .deleteById(docId1, -1L)
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("shard2 add fail, shard1 delI fail",
        rsp, delIErr(docId1, "version conflict"), addErr(docId22, "not_a_num"));

    // Attempt a request containing 4 errors of various types (add, delI, delQ)
    for (String maxErrors : new String[]{"4", "-1", "100"}) {
      // For all of these maxErrors values, the overall request should still succeed
      rsp = update(params("update.chain", "tolerant-chain-max-errors-10",
              "maxErrors", maxErrors, "commit", "true"),
          doc(f("id", docId22), f("foo_i", "bogus_val")))
          .deleteById(docId1, -1L)
          .deleteByQuery("malformed:[")
          .deleteById(docId21, -1L)
          .process(client);

      assertEquals(0, rsp.getStatus());
      assertUpdateTolerantErrors("failed variety of updates",
          rsp,
          delIErr(docId1, "version conflict"),
          delQErr("malformed:[", "SyntaxError"),
          delIErr(docId21, "version conflict"),
          addErr(docId22, "bogus_val"));
    }

    // Attempt a request containing 4 errors of various types (add, delI, delQ) .. 1 too many
    SolrException e = expectThrows(SolrException.class,
        "did not get a top level exception when more then 4 updates failed",
        () -> update(params("update.chain", "tolerant-chain-max-errors-10",
                "maxErrors", "3", "commit", "true"),
            doc(f("id", docId22), f("foo_i", "bogus_val")))
            .deleteById(docId1, -1L)
            .deleteByQuery("malformed:[")
            .deleteById(docId21, -1L)
            .process(client));

    {
      // We can't make any reliable assertions about the error message, because
      // it varies based on how the request was routed -- see SOLR-8830

      // Likewise, we can't make a firm(er) assertion about the response code...
      assertTrue("not the type of error we were expecting (" + e.code() + "): " + e,
          // should be one these 2 depending on order that the async errors were hit...
          // on a single node setup -- a 5xx type error isn't something we should have triggered
          400 == e.code() || 409 == e.code());

      // Verify that the Exceptions' metadata can tell us what failed.
      NamedList<String> remoteErrMetadata = e.getMetadata();
      assertNotNull("no metadata in: " + e, remoteErrMetadata);
      Set<ToleratedUpdateError> actualKnownErrs = new LinkedHashSet<ToleratedUpdateError>(remoteErrMetadata.size());
      int actualKnownErrsCount = 0;
      for (int i = 0; i < remoteErrMetadata.size(); i++) {
        ToleratedUpdateError err = ToleratedUpdateError.parseMetadataIfToleratedUpdateError(
            remoteErrMetadata.getName(i), remoteErrMetadata.getVal(i));
        if (null == err) {
          // some metadata unrelated to this update processor
          continue;
        }
        actualKnownErrsCount++;
        actualKnownErrs.add(err);
      }
      assertEquals("wrong number of errors in metadata: " + remoteErrMetadata,
          4, actualKnownErrsCount);
      assertEquals("at least one dup error in metadata: " + remoteErrMetadata,
          actualKnownErrsCount, actualKnownErrs.size());
    }

    // Sanity check our 2 existing docs are still here
    assertQueryDocIds(client, true, docId1, docId21);
    assertQueryDocIds(client, false, docId22);

    // Tolerate some failures along with a DELQ that should succeed
    rsp = update(params("update.chain", "tolerant-chain-max-errors-10", "commit", "true"),
        doc(f("id", docId22), f("foo_i", "not_a_num")))
        .deleteById(docId1, -1L)
        .deleteByQuery("zot_i:[42 to gibberish...")
        .deleteByQuery("foo_i:[50 TO 2000}")
        .process(client);
    assertEquals(0, rsp.getStatus());
    assertUpdateTolerantErrors("mix fails with one valid DELQ",
        rsp,
        delIErr(docId1, "version conflict"),
        delQErr("zot_i:[42 to gibberish..."),
        addErr(docId22, "not_a_num"));
    // One of our previous docs should have been deleted now
    assertQueryDocIds(client, true, docId1);
    assertQueryDocIds(client, false, docId21, docId22);
  }

  /** Asserts that the UpdateResponse contains the specified expectedErrs and no others */
  public void assertUpdateTolerantErrors(String assertionMsgPrefix, UpdateResponse response,
                                         ExpectedErr... expectedErrs) {
    @SuppressWarnings("unchecked")
    List<SimpleOrderedMap<String>> errors =
        (List<SimpleOrderedMap<String>>) response.getResponseHeader().get("errors");

    assertNotNull(assertionMsgPrefix + ": Null errors: " + response, errors);
    assertEquals(assertionMsgPrefix + ": Num error ids: " + errors,
        expectedErrs.length, errors.size());

    for (SimpleOrderedMap<String> err : errors) {
      String assertErrPre = assertionMsgPrefix + ": " + err.toString();

      String id = err.get("id");
      assertNotNull(assertErrPre + " ... null id", id);
      String type = err.get("type");
      assertNotNull(assertErrPre + " ... null type", type);
      String message = err.get("message");
      assertNotNull(assertErrPre + " ... null message", message);

      // Inefficient scan, but good enough for the size of sets we're dealing with
      boolean found = false;
      for (ExpectedErr expected : expectedErrs) {
        if (expected.type.equals(type) && expected.id.equals(id) &&
            (null == expected.msgSubStr || message.contains(expected.msgSubStr))) {
          found = true;
          break;
        }
      }
      assertTrue(assertErrPre + " ... unexpected err in: " + response, found);
    }
  }

  /** Convenience method when the only type of errors you expect are 'add' errors */
  public void assertUpdateTolerantAddErrors(String assertionMsgPrefix, UpdateResponse response,
                                            String... errorIdsExpected) {
    ExpectedErr[] expected = new ExpectedErr[errorIdsExpected.length];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = addErr(errorIdsExpected[i]);
    }
    assertUpdateTolerantErrors(assertionMsgPrefix, response, expected);
  }

  /**
   * Asserts that the specified document ids do/do-not exist in the index,
   * using the COLLECTION_CLIENT for queries.
   */
  public void assertQueryDocIds(SolrClient client, boolean shouldExist, String... ids)
      throws Exception {
    for (String id : ids) {
      assertEquals(client.toString() + " should " + (shouldExist ? "" : "not ") + "find id: " + id,
          (shouldExist ? 1 : 0),
          collectionClient.query(params("q", "{!term f=id}" + id)).getResults().getNumFound());
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

  public static ModifiableSolrParams params(String... params) {
    ModifiableSolrParams p = new ModifiableSolrParams();
    for (int i = 0; i < params.length; i += 2) {
      p.add(params[i], params[i + 1]);
    }
    return p;
  }

  /** Simple helper struct */
  public static final class ExpectedErr {
    final String type;
    final String id;
    final String msgSubStr; // ignored if null

    public ExpectedErr(String type, String id, String msgSubStr) {
      this.type = type;
      this.id = id;
      this.msgSubStr = msgSubStr;
    }

    @Override
    public String toString() {
      return "type=<" + type + ">,id=<" + id + ">,msgSubStr=<" + msgSubStr + ">";
    }
  }

  public static ExpectedErr addErr(String id, String msgSubStr) {
    return new ExpectedErr("ADD", id, msgSubStr);
  }

  public static ExpectedErr delIErr(String id, String msgSubStr) {
    return new ExpectedErr("DELID", id, msgSubStr);
  }

  public static ExpectedErr delQErr(String id, String msgSubStr) {
    return new ExpectedErr("DELQ", id, msgSubStr);
  }

  public static ExpectedErr addErr(String id) {
    return addErr(id, null);
  }

  public static ExpectedErr delIErr(String id) {
    return delIErr(id, null);
  }

  public static ExpectedErr delQErr(String id) {
    return delQErr(id, null);
  }
}
