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
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomInt;
import static com.carrotsearch.randomizedtesting.RandomizedTest.rarely;
import static org.apache.solr.SolrTestCaseJ4.params;
import static org.apache.solr.SolrTestCaseJ4.sdoc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.SolrParams;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased upgrade test for pseudo return fields in SolrCloud.
 *
 * <p>Transformed from {@link TestCloudPseudoReturnFields} to support checkpoint-based upgrade
 * testing with real Solr processes.
 *
 * @see org.apache.solr.search.TestPseudoReturnFields
 * @see TestRandomFlRTGCloud
 */
public class TestCloudPseudoReturnFields_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String DEBUG_LABEL = MethodHandles.lookup().lookupClass().getName();
  private static final String COLLECTION_NAME = DEBUG_LABEL + "_collection";

  // randomized for testing '[shards]' behavior...
  private int repFactor;

  /** A collection specific client for operations at the cloud level */
  private CloudSolrClient collectionClient;

  /** One client per node */
  private final ArrayList<SolrClient> clients = new ArrayList<>(5);

  @Test
  public void testCopyPk_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestCopyPk();
  }

  @Test
  public void testCopyPk_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestCopyPk();
  }

  @Test
  public void testCopyPk_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    runTestCopyPk();
  }

  @Test
  public void testCopyPk_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = "AFTER_INDEX";
    runTestCopyPk();
  }

  private void setupCluster() throws Exception {
    // replication factor will impact whether we expect a list of urls from the '[shard]'
    // augmenter...
    repFactor = getRandom().nextInt(10) < 8 ? 1 : 2;  // usually() equivalent: ~80% chance of 1
    // ... and we definitely want to ensure forwarded requests to other shards work ...
    final int numShards = 2;
    // ... including some forwarded requests from nodes not hosting a shard
    final int numNodes = 1 + (numShards * repFactor);

    final String configName = DEBUG_LABEL + "_config-set";
    final Path configDir =
        Paths.get(SolrTestCaseJ4.TEST_HOME(), "..", "collection1", "conf");

    // Create cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(numNodes)
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
    collectionProperties.put("schema", "schema-pseudo-fields.xml");
    CollectionAdminRequest.createCollection(COLLECTION_NAME, configName, numShards, repFactor)
        .setProperties(collectionProperties)
        .process(solrClient);

    collectionClient = new CloudSolrClient.Builder(
        java.util.List.of(cluster.getZkHost()), java.util.Optional.empty())
        .build();
    collectionClient.setDefaultCollection(COLLECTION_NAME);

    checkpoint("AFTER_COLLECTION_CREATE");

    // Create per-node clients
    for (int i = 0; i < numNodes; i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      clients.add(new HttpSolrClient.Builder(baseUrl + "/" + COLLECTION_NAME).build());
    }

    // Index docs
    assertEquals(
        0,
        collectionClient
            .add(sdoc("id", "42", "newid", "420", "val_i", "1", "ssto", "X", "subject", "aaa"))
            .getStatus());
    assertEquals(
        0,
        collectionClient
            .add(sdoc("id", "43", "newid", "430", "val_i", "9", "ssto", "X", "subject", "bbb"))
            .getStatus());
    assertEquals(
        0,
        collectionClient
            .add(sdoc("id", "44", "newid", "440", "val_i", "4", "ssto", "X", "subject", "aaa"))
            .getStatus());
    assertEquals(
        0,
        collectionClient
            .add(sdoc("id", "45", "newid", "450", "val_i", "6", "ssto", "X", "subject", "aaa"))
            .getStatus());
    assertEquals(
        0,
        collectionClient
            .add(sdoc("id", "46", "newid", "460", "val_i", "3", "ssto", "X", "subject", "ggg"))
            .getStatus());
    assertEquals(0, collectionClient.commit().getStatus());

    checkpoint("AFTER_INDEX");
  }

  private void runTestCopyPk() throws Exception {
    setupCluster();

    String fl = "oldid:id,newid";
    SolrDocumentList docs = assertSearch(params("q", "*:*", "rows", "10", "fl", fl));
    for (SolrDocument doc : docs) {
      assertTrue(
          fl + " => " + doc,
          Arrays.asList("420", "430", "440", "450", "460")
                  .indexOf((String) doc.getFieldValue("newid"))
              >= 0);
      assertTrue(
          fl + " => " + doc,
          Arrays.asList("42", "43", "44", "45", "46").indexOf((String) doc.getFieldValue("oldid"))
              >= 0);
    }

    closeClients();
  }

  /**
   * Given a set of query params, executes as a Query against a random SolrClient and asserts that
   * at least 1 doc is matched and at least 1 doc is returned
   */
  private SolrDocumentList assertSearch(SolrParams p) throws Exception {
    QueryResponse rsp = getRandClient(getRandom()).query(p);
    assertEquals("failed request: " + p.toString() + " => " + rsp.toString(), 0, rsp.getStatus());
    assertTrue(
        "does not match at least one doc: " + p + " => " + rsp,
        1 <= rsp.getResults().getNumFound());
    assertTrue(
        "rsp does not contain at least one doc: " + p + " => " + rsp, 1 <= rsp.getResults().size());
    return rsp.getResults();
  }

  /**
   * returns a random SolrClient -- either a CloudSolrClient, or an HttpSolrClient pointed at a node
   * in our cluster
   */
  private SolrClient getRandClient(Random rand) {
    int numClients = clients.size();
    int idx = TestUtil.nextInt(rand, 0, numClients);
    return (idx == numClients) ? collectionClient : clients.get(idx);
  }

  private void closeClients() {
    if (collectionClient != null) {
      try {
        collectionClient.close();
      } catch (Exception e) {
        log.warn("Error closing collection client", e);
      }
      collectionClient = null;
    }
    for (SolrClient client : clients) {
      try {
        client.close();
      } catch (Exception e) {
        log.warn("Error closing client", e);
      }
    }
    clients.clear();
  }
}
