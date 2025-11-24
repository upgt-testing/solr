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
package org.apache.solr.search.stats;
import static com.carrotsearch.randomizedtesting.RandomizedTest.getRandom;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static com.carrotsearch.randomizedtesting.RandomizedTest.rarely;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomInt;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.CompositeIdRouter;
import org.apache.solr.common.cloud.ImplicitDocRouter;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased transformation of TestDistribIDF.
 *
 * <p>Tests distributed IDF (Inverse Document Frequency) across shards with different stats cache
 * implementations (ExactStatsCache vs LRUStatsCache).
 */
public class TestDistribIDF_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void testSimpleQuery_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestSimpleQuery();
  }

  @Test
  public void testSimpleQuery_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestSimpleQuery();
  }

  private void runTestSimpleQuery() throws Exception {
    if (getRandom().nextBoolean()) {
      System.setProperty("solr.statsCache", ExactStatsCache.class.getName());
    } else {
      System.setProperty("solr.statsCache", LRUStatsCache.class.getName());
    }

    try {
      // set some system properties for use by tests
      System.setProperty("solr.test.sys.prop1", "propone");
      System.setProperty("solr.test.sys.prop2", "proptwo");

      cluster =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(3)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();      cluster.uploadConfigSet(
          Paths.get(SolrTestCaseJ4.TEST_HOME(), "collection1", "conf"), "conf1");
      cluster.uploadConfigSet(
          Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "configset-2", "conf"), "conf2");

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // 3 shards. 3rd shard won't have any data.
      createCollection("onecollection", "conf1", ImplicitDocRouter.NAME);
      createCollection("onecollection_local", "conf2", ImplicitDocRouter.NAME);

      solrClient = cluster.getSolrClient();

      SolrInputDocument doc = new SolrInputDocument();
      doc.setField("id", "1");
      doc.setField("cat", "football");
      doc.addField(ShardParams._ROUTE_, "a");
      solrClient.add("onecollection", doc);
      solrClient.add("onecollection_local", doc);

      doc = new SolrInputDocument();
      doc.setField("id", "2");
      doc.setField("cat", "football");
      doc.addField(ShardParams._ROUTE_, "b");
      solrClient.add("onecollection", doc);
      solrClient.add("onecollection_local", doc);

      int nDocs = TestUtil.nextInt(getRandom(), 10, 100);
      for (int i = 0; i < nDocs; i++) {
        doc = new SolrInputDocument();
        doc.setField("id", "" + (3 + i));
        String cat = TestUtil.randomSimpleString(getRandom());
        if (!cat.equals("football")) { // Making sure no other document has the query term in it.
          doc.setField("cat", cat);
          // Put most documents in shard b so that 'football' becomes 'rare' in shard b
          if (rarely()) {
            doc.addField(ShardParams._ROUTE_, "a");
          } else {
            doc.addField(ShardParams._ROUTE_, "b");
          }
          solrClient.add("onecollection", doc);
          solrClient.add("onecollection_local", doc);
        }
      }

      solrClient.commit("onecollection");
      solrClient.commit("onecollection_local");

      // Test against all nodes
      for (int i = 0; i < cluster.getNodeCount(); i++) {
        String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
        try (SolrClient nodeClient = new HttpSolrClient.Builder(baseUrl).build();
            SolrClient nodeClientLocal = new HttpSolrClient.Builder(baseUrl).build()) {

          SolrQuery query = new SolrQuery("cat:football");
          query.setFields("*,score");
          QueryResponse queryResponse = nodeClient.query("onecollection", query);
          assertEquals(2, queryResponse.getResults().getNumFound());
          float score1 = (float) queryResponse.getResults().get(0).get("score");
          float score2 = (float) queryResponse.getResults().get(1).get("score");
          assertEquals(
              "Doc1 score=" + score1 + " Doc2 score=" + score2, 0, Float.compare(score1, score2));

          query = new SolrQuery("cat:football");
          query.setShowDebugInfo(true);
          query.setFields("*,score");
          queryResponse = nodeClientLocal.query("onecollection_local", query);
          assertEquals(2, queryResponse.getResults().getNumFound());
          assertEquals("2", queryResponse.getResults().get(0).get("id"));
          assertEquals("1", queryResponse.getResults().get(1).get("id"));
          float score1_local = (float) queryResponse.getResults().get(0).get("score");
          float score2_local = (float) queryResponse.getResults().get(1).get("score");
          assertEquals(
              "Doc1 score=" + score1_local + " Doc2 score=" + score2_local,
              1,
              Float.compare(score1_local, score2_local));
        }
      }
    } finally {
      System.clearProperty("solr.statsCache");
      System.clearProperty("solr.test.sys.prop1");
      System.clearProperty("solr.test.sys.prop2");
    }
  }

  @Test
  public void testMultiCollectionQuery_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestMultiCollectionQuery();
  }

  @Test
  public void testMultiCollectionQuery_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestMultiCollectionQuery();
  }

  private void runTestMultiCollectionQuery() throws Exception {
    if (getRandom().nextBoolean()) {
      System.setProperty("solr.statsCache", ExactStatsCache.class.getName());
    } else {
      System.setProperty("solr.statsCache", LRUStatsCache.class.getName());
    }

    try {
      System.setProperty("solr.test.sys.prop1", "propone");
      System.setProperty("solr.test.sys.prop2", "proptwo");

      cluster =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(3)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();      cluster.uploadConfigSet(
          Paths.get(SolrTestCaseJ4.TEST_HOME(), "collection1", "conf"), "conf1");
      cluster.uploadConfigSet(
          Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "configset-2", "conf"), "conf2");

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // collection1 and collection2 are collections which have distributed idf enabled
      // collection1_local and collection2_local don't have distributed idf available
      createCollection("collection1", "conf1");
      createCollection("collection1_local", "conf2");
      createCollection("collection2", "conf1");
      createCollection("collection2_local", "conf2");

      solrClient = cluster.getSolrClient();
      addDocsRandomly();

      // Test against all nodes
      for (int i = 0; i < cluster.getNodeCount(); i++) {
        String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
        try (SolrClient nodeClient = new HttpSolrClient.Builder(baseUrl).build();
            SolrClient nodeClientLocal = new HttpSolrClient.Builder(baseUrl).build()) {

          SolrQuery query = new SolrQuery("cat:football");
          query.setFields("*,score").add("collection", "collection1,collection2");
          QueryResponse queryResponse = nodeClient.query("collection1", query);
          assertEquals(2, queryResponse.getResults().getNumFound());
          float score1 = (float) queryResponse.getResults().get(0).get("score");
          float score2 = (float) queryResponse.getResults().get(1).get("score");
          assertEquals(
              "Doc1 score=" + score1 + " Doc2 score=" + score2, 0, Float.compare(score1, score2));

          query = new SolrQuery("cat:football");
          query.setFields("*,score").add("collection", "collection1_local,collection2_local");
          queryResponse = nodeClientLocal.query("collection1_local", query);
          assertEquals(2, queryResponse.getResults().getNumFound());
          assertEquals("2", queryResponse.getResults().get(0).get("id"));
          assertEquals("1", queryResponse.getResults().get(1).get("id"));
          float score1_local = (float) queryResponse.getResults().get(0).get("score");
          float score2_local = (float) queryResponse.getResults().get(1).get("score");
          assertEquals(
              "Doc1 score=" + score1_local + " Doc2 score=" + score2_local,
              1,
              Float.compare(score1_local, score2_local));
        }
      }
    } finally {
      System.clearProperty("solr.statsCache");
      System.clearProperty("solr.test.sys.prop1");
      System.clearProperty("solr.test.sys.prop2");
    }
  }

  @Test
  public void testDisableDistribStats_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestDisableDistribStats();
  }

  @Test
  public void testDisableDistribStats_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestDisableDistribStats();
  }

  @SuppressWarnings("unchecked")
  private void runTestDisableDistribStats() throws Exception {
    if (getRandom().nextBoolean()) {
      System.setProperty("solr.statsCache", ExactStatsCache.class.getName());
    } else {
      System.setProperty("solr.statsCache", LRUStatsCache.class.getName());
    }

    try {
      System.setProperty("solr.test.sys.prop1", "propone");
      System.setProperty("solr.test.sys.prop2", "proptwo");

      cluster =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(3)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();      cluster.uploadConfigSet(
          Paths.get(SolrTestCaseJ4.TEST_HOME(), "collection1", "conf"), "conf1");

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // single collection with implicit router
      final String COLLECTION = "collection1";
      createCollection(COLLECTION, "conf1", ImplicitDocRouter.NAME);
      solrClient = cluster.getSolrClient();

      SolrInputDocument doc = new SolrInputDocument();
      doc.setField("id", "1");
      doc.setField("cat", "tv");
      doc.addField(ShardParams._ROUTE_, "a");
      solrClient.add(COLLECTION, doc);

      doc = new SolrInputDocument();
      doc.setField("id", "2");
      doc.setField("cat", "ipad");
      doc.addField(ShardParams._ROUTE_, "b");
      solrClient.add(COLLECTION, doc);

      solrClient.commit(COLLECTION);

      // distributed stats implicitly enabled by default
      SolrQuery query = new SolrQuery("q", "cat:tv", "fl", "id,score", "debug", "track");
      QueryResponse rsp = solrClient.query(COLLECTION, query);
      NamedList<Object> track = (NamedList<Object>) rsp.getDebugMap().get("track");
      assertNotNull(track);
      assertNotNull("stats cache hit", track.get("PARSE_QUERY"));

      // distributed stats explicitly disabled
      query.set("distrib.statsCache", "false");
      query.set("q", "{!terms f=id}1,2");
      rsp = solrClient.query(COLLECTION, query);
      track = (NamedList<Object>) rsp.getDebugMap().get("track");
      assertNotNull(track);
      assertNull("NO stats cache hit", track.get("PARSE_QUERY"));
      assertNotNull("just search", track.get("EXECUTE_QUERY"));

      // distributed stats explicitly enabled
      query.set("distrib.statsCache", "true");
      query.set("q", "name:ipad"); // trick around LRUStatsCache
      rsp = solrClient.query(COLLECTION, query);
      track = (NamedList<Object>) rsp.getDebugMap().get("track");
      assertNotNull(track);
      assertNotNull("stats cache hit:" + track, track.get("PARSE_QUERY"));
    } finally {
      System.clearProperty("solr.statsCache");
      System.clearProperty("solr.test.sys.prop1");
      System.clearProperty("solr.test.sys.prop2");
    }
  }

  private void createCollection(String name, String config) throws Exception {
    createCollection(name, config, CompositeIdRouter.NAME);
  }

  private void createCollection(String name, String config, String router) throws Exception {
    CollectionAdminResponse response;
    if (router.equals(ImplicitDocRouter.NAME)) {
      CollectionAdminRequest.Create create =
          CollectionAdminRequest.createCollectionWithImplicitRouter(name, config, "a,b,c", 1);
      response = create.process(solrClient);    } else {
      CollectionAdminRequest.Create create =
          CollectionAdminRequest.createCollection(name, config, 2, 1)
              .setPerReplicaState(SolrCloudTestCase.USE_PER_REPLICA_STATE);
      response = create.process(solrClient);    }

    if (response.getStatus() != 0 || response.getErrorMessages() != null) {
      fail("Could not create collection. Response" + response);
    }
  }

  private void addDocsRandomly() throws IOException, SolrServerException {
    SolrInputDocument doc = new SolrInputDocument();
    doc.setField("id", 1);
    doc.setField("cat", "football");
    solrClient.add("collection1", doc);
    solrClient.add("collection1_local", doc);

    doc = new SolrInputDocument();
    doc.setField("id", 2);
    doc.setField("cat", "football");
    solrClient.add("collection2", doc);
    solrClient.add("collection2_local", doc);

    int nDocs = TestUtil.nextInt(getRandom(), 10, 100);
    int collection1Count = 1;
    int collection2Count = 1;
    for (int i = 0; i < nDocs; i++) {
      doc = new SolrInputDocument();
      doc.setField("id", 3 + i);
      String cat = TestUtil.randomSimpleString(getRandom());
      if (!cat.equals("football")) { // Making sure no other document has the query term in it.
        doc.setField("cat", cat);
        // Put most documents in collection2* so that 'football' becomes 'rare' in collection2*
        if (rarely()) {
          solrClient.add("collection1", doc);
          solrClient.add("collection1_local", doc);
          collection1Count++;
        } else {
          solrClient.add("collection2", doc);
          solrClient.add("collection2_local", doc);
          collection2Count++;
        }
      }
    }
    log.info(
        "numDocs={}. collection1Count={} collection2Count={}",
        nDocs,
        collection1Count,
        collection2Count);

    solrClient.commit("collection1");
    solrClient.commit("collection2");
    solrClient.commit("collection1_local");
    solrClient.commit("collection2_local");
  }
}
