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

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.Test;

/**
 * ProcessBased upgrade test for PhrasesIdentificationComponent in distributed mode.
 *
 * <p>Transformed from {@link TestCloudPhrasesIdentificationComponent} to support checkpoint-based
 * upgrade testing with real Solr processes.
 *
 * <p>Tests that Phrase Identification works across a cloud cluster using distributed term stat
 * collection.
 *
 * @see org.apache.solr.handler.component.PhrasesIdentificationComponentTest
 */
public class TestCloudPhrasesIdentificationComponent_ProcessBased
    extends ProcessBasedUpgradeTestBase {

  private static final String DEBUG_LABEL = MethodHandles.lookup().lookupClass().getName();
  private static final String COLLECTION_NAME = DEBUG_LABEL + "_collection";

  /** A collection specific client for operations at the cloud level */
  private CloudSolrClient collectionClient;

  /** One client per node */
  private final ArrayList<SolrClient> clients = new ArrayList<>(5);

  // Test parameters (randomized once per test)
  private int repFactor;
  private int numShards;
  private int numNodes;

  @Test
  public void testBasicPhrases_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestBasicPhrases();
  }

  @Test
  public void testBasicPhrases_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestBasicPhrases();
  }

  @Test
  public void testBasicPhrases_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    runTestBasicPhrases();
  }

  @Test
  public void testBasicPhrases_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = "AFTER_INDEX";
    runTestBasicPhrases();
  }

  @Test
  public void testEmptyInput_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestEmptyInput();
  }

  @Test
  public void testEmptyInput_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestEmptyInput();
  }

  @Test
  public void testEmptyInput_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    runTestEmptyInput();
  }

  @Test
  public void testEmptyInput_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = "AFTER_INDEX";
    runTestEmptyInput();
  }

  private void setupCluster() throws Exception {
    // Multi replicas should not matter...
    repFactor = usually() ? 1 : 2;
    // ... but we definitely want to test multiple shards
    numShards = TestUtil.nextInt(random(), 1, (usually() ? 2 : 3));
    numNodes = (numShards * repFactor);

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
    cluster.start();
    cluster.waitForAllNodes(30);

    // Upload config
    cluster.uploadConfigSet(configDir, configName);

    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Create collection with custom properties
    Map<String, String> collectionProperties = new LinkedHashMap<>();
    collectionProperties.put("config", "solrconfig-phrases-identification.xml");
    collectionProperties.put("schema", "schema-phrases-identification.xml");
    CollectionAdminRequest.createCollection(COLLECTION_NAME, configName, numShards, repFactor)
        .setProperties(collectionProperties)
        .process(solrClient);

    collectionClient = cluster.getSolrClient(COLLECTION_NAME);

    waitForRecoveriesToFinish(collectionClient);

    checkpoint("AFTER_COLLECTION_CREATE");

    // Create per-node clients
    for (int i = 0; i < numNodes; i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      clients.add(new HttpSolrClient.Builder(baseUrl + "/" + COLLECTION_NAME).build());
    }

    // Index some docs...
    collectionClient.add(
        sdoc(
            "id", "42",
            "title", "Tale of the Brown Fox: was he lazy?",
            "body", "No. The quick brown fox was a very brown fox who liked to get into trouble."));
    collectionClient.add(
        sdoc(
            "id", "43",
            "title", "A fable in two acts",
            "body", "The brOwn fOx jumped. The lazy dog did not"));
    collectionClient.add(
        sdoc(
            "id", "44",
            "title", "Why the LazY dog was lazy",
            "body",
                "News flash: Lazy Dog was not actually lazy, it just seemed so compared to Fox"));
    collectionClient.add(
        sdoc(
            "id", "45",
            "title", "Why Are We Lazy?",
            "body", "Because we are. that's why"));
    collectionClient.commit();

    checkpoint("AFTER_INDEX");
  }

  private void runTestBasicPhrases() throws Exception {
    setupCluster();

    final String input = " did  a Quick    brown FOX perniciously jump over the lazy dog";
    final String expected = " did  a Quick    {brown FOX} perniciously jump over {the lazy dog}";

    // based on the documents indexed, these assertions should all pass regardless of
    // how many shards we have, or whether the request is done via /phrases or /select...
    for (String path : Arrays.asList("/select", "/phrases")) {
      // ... or if we muck with "q" and use the alternative phrases.q for the bits we care about...
      for (SolrParams p :
          Arrays.asList(
              params("q", input, "phrases", "true"),
              params("q", "*:*", "phrases.q", input, "phrases", "true"),
              params("q", "-*:*", "phrases.q", input, "phrases", "true"))) {
        final QueryRequest req = new QueryRequest(p);
        req.setPath(path);
        final QueryResponse rsp = req.process(getRandClient(random()));
        try {
          @SuppressWarnings({"unchecked"})
          NamedList<Object> phrases = (NamedList<Object>) rsp.getResponse().get("phrases");
          assertEquals("input", input, phrases.get("input"));
          assertEquals("summary", expected, phrases.get("summary"));

          @SuppressWarnings({"unchecked"})
          final List<NamedList<Object>> details = (List<NamedList<Object>>) phrases.get("details");
          assertNotNull("null details", details);
          assertEquals("num phrases found", 2, details.size());

          final NamedList<Object> lazy_dog = details.get(0);
          assertEquals("dog text", "the lazy dog", lazy_dog.get("text"));
          assertEquals("dog score", 0.166666D, (Double) lazy_dog.get("score"), 0.000001D);

          final NamedList<Object> brown_fox = details.get(1);
          assertEquals("fox text", "brown FOX", brown_fox.get("text"));
          assertEquals("fox score", 0.083333D, (Double) brown_fox.get("score"), 0.000001D);

        } catch (AssertionError e) {
          throw new AssertionError(e.getMessage() + " ::: " + path + " ==> " + rsp, e);
        }
      }
    }

    closeClients();
  }

  private void runTestEmptyInput() throws Exception {
    setupCluster();

    // empty input shouldn't error, just produce empty results...
    for (String input : Arrays.asList("", "  ")) {
      for (SolrParams p :
          Arrays.asList(
              params("q", "*:*", "phrases.q", input, "phrases", "true"),
              params("q", "-*:*", "phrases.q", input, "phrases", "true"))) {
        final QueryRequest req = new QueryRequest(p);
        req.setPath("/phrases");
        final QueryResponse rsp = req.process(getRandClient(random()));
        try {
          @SuppressWarnings({"unchecked"})
          NamedList<Object> phrases = (NamedList<Object>) rsp.getResponse().get("phrases");
          assertEquals("input", input, phrases.get("input"));
          assertEquals("summary", input, phrases.get("summary"));

          @SuppressWarnings({"unchecked"})
          final List<NamedList<Object>> details = (List<NamedList<Object>>) phrases.get("details");
          assertNotNull("null details", details);
          assertEquals("num phrases found", 0, details.size());

        } catch (AssertionError e) {
          throw new AssertionError(e.getMessage() + " ==> " + rsp, e);
        }
      }
    }

    closeClients();
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

  private void waitForRecoveriesToFinish(CloudSolrClient client) throws Exception {
    assertNotNull(client.getDefaultCollection());
    cluster.waitForActiveCollection(
        client.getDefaultCollection(), 30, TimeUnit.SECONDS, numShards, numShards * repFactor);
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
