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
package org.apache.solr.ltr;

import static java.util.stream.Collectors.toList;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakLingering;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.commons.io.file.PathUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.ltr.feature.FieldValueFeature;
import org.apache.solr.ltr.feature.OriginalScoreFeature;
import org.apache.solr.ltr.feature.SolrFeature;
import org.apache.solr.ltr.feature.ValueFeature;
import org.apache.solr.ltr.model.LinearModel;
import org.apache.solr.ltr.store.FeatureStore;
import org.apache.solr.ltr.store.rest.ManagedFeatureStore;
import org.apache.solr.ltr.store.rest.ManagedModelStore;
import org.apache.solr.util.RestTestHarness;
import org.junit.AfterClass;
import org.junit.Test;

/**
 * ProcessBased upgrade test for Learning to Rank (LTR) functionality on SolrCloud.
 *
 * <p>Tests LTR feature extraction, model loading, and re-ranking across cluster upgrades.
 */
@ThreadLeakLingering(linger = 10)
public class TestLTROnSolrCloud_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final String COLLECTION = "collection1";
  private static final String CONF_DIR = COLLECTION + "/conf";
  private static final String FEATURE_FILE_NAME = "_schema_feature-store.json";
  private static final String MODEL_FILE_NAME = "_schema_model-store.json";

  private static Path tmpSolrHome;
  private static Path tmpConfDir;
  private RestTestHarness restTestHarness;

  private void setupTestInit(String solrconfig, String schema) throws Exception {
    tmpSolrHome = createTempDir();
    tmpConfDir = tmpSolrHome.resolve(CONF_DIR);
    tmpConfDir.toFile().deleteOnExit();
    PathUtils.copyDirectory(TEST_PATH(), tmpSolrHome.toAbsolutePath());

    final Path fstore = tmpConfDir.resolve(FEATURE_FILE_NAME);
    final Path mstore = tmpConfDir.resolve(MODEL_FILE_NAME);

    if (java.nio.file.Files.exists(fstore)) {
      java.nio.file.Files.delete(fstore);
    }
    if (java.nio.file.Files.exists(mstore)) {
      java.nio.file.Files.delete(mstore);
    }
    if (!solrconfig.equals("solrconfig.xml")) {
      java.nio.file.Files.copy(
          tmpSolrHome.resolve(CONF_DIR).resolve(solrconfig),
          tmpSolrHome.resolve(CONF_DIR).resolve("solrconfig.xml"));
    }
    if (!schema.equals("schema.xml")) {
      java.nio.file.Files.copy(
          tmpSolrHome.resolve(CONF_DIR).resolve(schema),
          tmpSolrHome.resolve(CONF_DIR).resolve("schema.xml"));
    }

    System.setProperty("managed.schema.mutable", "true");
    System.setProperty("enable.update.log", "true");
  }

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
    setupTestInit("solrconfig-ltr.xml", "schema.xml");

    // Setup cluster with 2 nodes, 1 shard, 1 replica (simplified from random config)
    cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
        .withNodeCount(2)
        .withStartVersionFromSystemProperty()
        .withUpgradeVersionFromSystemProperty()
        .build();
    cluster.start();
    cluster.waitForAllNodes(30);

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload config
    Path configDir = tmpSolrHome.resolve(CONF_DIR);
    cluster.uploadConfigSet(configDir, "conf1");

    // Get Solr client
    solrClient = cluster.getSolrClient();

    // Create collection
    createCollection(COLLECTION, "conf1", 1, 1);

    // Index documents
    indexDocuments(COLLECTION);

    // Initialize RestTestHarness by getting core name from ZkStateReader
    ZkStateReader zkStateReader = solrClient.getClusterStateProvider().getZkStateReader();
    DocCollection docCollection = zkStateReader.getCollection(COLLECTION);
    Replica replica = docCollection.getSlices().iterator().next().getReplicas().iterator().next();
    String coreName = replica.getCoreName();
    String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
    restTestHarness = new RestTestHarness(() -> baseUrl + "/solr/" + coreName);

    // Load models and features
    loadModelsAndFeatures();

    // Test regular query (sort by inverse popularity)
    SolrQuery query = new SolrQuery("{!func}sub(8,field(popularity))");
    query.setRequestHandler("/query");
    query.setFields("*,score");
    query.setParam("rows", "8");

    QueryResponse queryResponse = solrClient.query(COLLECTION, query);
    assertEquals(8, queryResponse.getResults().getNumFound());
    assertEquals("1", queryResponse.getResults().get(0).get("id").toString());
    assertEquals("2", queryResponse.getResults().get(1).get("id").toString());
    assertEquals("3", queryResponse.getResults().get(2).get("id").toString());
    assertEquals("4", queryResponse.getResults().get(3).get("id").toString());
    assertEquals("5", queryResponse.getResults().get(4).get("id").toString());
    assertEquals("6", queryResponse.getResults().get(5).get("id").toString());
    assertEquals("7", queryResponse.getResults().get(6).get("id").toString());
    assertEquals("8", queryResponse.getResults().get(7).get("id").toString());

    final Float original_result0_score = (Float) queryResponse.getResults().get(0).get("score");
    final Float original_result1_score = (Float) queryResponse.getResults().get(1).get("score");
    final Float original_result2_score = (Float) queryResponse.getResults().get(2).get("score");
    final Float original_result3_score = (Float) queryResponse.getResults().get(3).get("score");
    final Float original_result4_score = (Float) queryResponse.getResults().get(4).get("score");
    final Float original_result5_score = (Float) queryResponse.getResults().get(5).get("score");
    final Float original_result6_score = (Float) queryResponse.getResults().get(6).get("score");
    final Float original_result7_score = (Float) queryResponse.getResults().get(7).get("score");

    final String result0_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "64.0",
            "c3",
            "2.0",
            "original",
            "0.0",
            "dvIntFieldFeature",
            "8.0",
            "dvLongFieldFeature",
            "8.0",
            "dvFloatFieldFeature",
            "0.8",
            "dvDoubleFieldFeature",
            "0.8",
            "dvStrNumFieldFeature",
            "0.0",
            "dvStrBoolFieldFeature",
            "1.0");
    final String result1_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "49.0",
            "c3",
            "2.0",
            "original",
            "1.0",
            "dvIntFieldFeature",
            "7.0",
            "dvLongFieldFeature",
            "7.0",
            "dvFloatFieldFeature",
            "0.7",
            "dvDoubleFieldFeature",
            "0.7",
            "dvStrNumFieldFeature",
            "1.0",
            "dvStrBoolFieldFeature",
            "0.0");
    final String result2_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "36.0",
            "c3",
            "2.0",
            "original",
            "2.0",
            "dvIntFieldFeature",
            "6.0",
            "dvLongFieldFeature",
            "6.0",
            "dvFloatFieldFeature",
            "0.6",
            "dvDoubleFieldFeature",
            "0.6",
            "dvStrNumFieldFeature",
            "0.0",
            "dvStrBoolFieldFeature",
            "1.0");
    final String result3_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "25.0",
            "c3",
            "2.0",
            "original",
            "3.0",
            "dvIntFieldFeature",
            "5.0",
            "dvLongFieldFeature",
            "5.0",
            "dvFloatFieldFeature",
            "0.5",
            "dvDoubleFieldFeature",
            "0.5",
            "dvStrNumFieldFeature",
            "1.0",
            "dvStrBoolFieldFeature",
            "0.0");
    final String result4_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "16.0",
            "c3",
            "2.0",
            "original",
            "4.0",
            "dvIntFieldFeature",
            "4.0",
            "dvLongFieldFeature",
            "4.0",
            "dvFloatFieldFeature",
            "0.4",
            "dvDoubleFieldFeature",
            "0.4",
            "dvStrNumFieldFeature",
            "0.0",
            "dvStrBoolFieldFeature",
            "1.0");
    final String result5_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "9.0",
            "c3",
            "2.0",
            "original",
            "5.0",
            "dvIntFieldFeature",
            "3.0",
            "dvLongFieldFeature",
            "3.0",
            "dvFloatFieldFeature",
            "0.3",
            "dvDoubleFieldFeature",
            "0.3",
            "dvStrNumFieldFeature",
            "1.0",
            "dvStrBoolFieldFeature",
            "0.0");
    final String result6_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "4.0",
            "c3",
            "2.0",
            "original",
            "6.0",
            "dvIntFieldFeature",
            "2.0",
            "dvLongFieldFeature",
            "2.0",
            "dvFloatFieldFeature",
            "0.2",
            "dvDoubleFieldFeature",
            "0.2",
            "dvStrNumFieldFeature",
            "0.0",
            "dvStrBoolFieldFeature",
            "1.0");
    final String result7_features =
        FeatureLoggerTestUtils.toFeatureVector(
            "powpularityS",
            "1.0",
            "c3",
            "2.0",
            "original",
            "7.0",
            "dvIntFieldFeature",
            "-1.0",
            "dvLongFieldFeature",
            "-2.0",
            "dvFloatFieldFeature",
            "-3.0",
            "dvDoubleFieldFeature",
            "-4.0",
            "dvStrNumFieldFeature",
            "-5.0",
            "dvStrBoolFieldFeature",
            "0.0");

    // Test feature vectors returned (without re-ranking)
    query.setFields("*,score,features:[fv store=test]");
    queryResponse = solrClient.query(COLLECTION, query);
    assertEquals(8, queryResponse.getResults().getNumFound());
    assertEquals("1", queryResponse.getResults().get(0).get("id").toString());
    assertEquals("2", queryResponse.getResults().get(1).get("id").toString());
    assertEquals("3", queryResponse.getResults().get(2).get("id").toString());
    assertEquals("4", queryResponse.getResults().get(3).get("id").toString());
    assertEquals("5", queryResponse.getResults().get(4).get("id").toString());
    assertEquals("6", queryResponse.getResults().get(5).get("id").toString());
    assertEquals("7", queryResponse.getResults().get(6).get("id").toString());
    assertEquals("8", queryResponse.getResults().get(7).get("id").toString());

    assertEquals(original_result0_score, queryResponse.getResults().get(0).get("score"));
    assertEquals(original_result1_score, queryResponse.getResults().get(1).get("score"));
    assertEquals(original_result2_score, queryResponse.getResults().get(2).get("score"));
    assertEquals(original_result3_score, queryResponse.getResults().get(3).get("score"));
    assertEquals(original_result4_score, queryResponse.getResults().get(4).get("score"));
    assertEquals(original_result5_score, queryResponse.getResults().get(5).get("score"));
    assertEquals(original_result6_score, queryResponse.getResults().get(6).get("score"));
    assertEquals(original_result7_score, queryResponse.getResults().get(7).get("score"));

    assertEquals(result7_features, queryResponse.getResults().get(0).get("features").toString());
    assertEquals(result6_features, queryResponse.getResults().get(1).get("features").toString());
    assertEquals(result5_features, queryResponse.getResults().get(2).get("features").toString());
    assertEquals(result4_features, queryResponse.getResults().get(3).get("features").toString());
    assertEquals(result3_features, queryResponse.getResults().get(4).get("features").toString());
    assertEquals(result2_features, queryResponse.getResults().get(5).get("features").toString());
    assertEquals(result1_features, queryResponse.getResults().get(6).get("features").toString());
    assertEquals(result0_features, queryResponse.getResults().get(7).get("features").toString());

    // Test feature vectors returned (with re-ranking)
    query.setFields("*,score,features:[fv]");
    query.add("rq", "{!ltr model=powpularityS-model reRankDocs=8}");
    queryResponse = solrClient.query(COLLECTION, query);
    assertEquals(8, queryResponse.getResults().getNumFound());
    assertEquals("8", queryResponse.getResults().get(0).get("id").toString());
    assertEquals(result0_features, queryResponse.getResults().get(0).get("features").toString());
    assertEquals("7", queryResponse.getResults().get(1).get("id").toString());
    assertEquals(result1_features, queryResponse.getResults().get(1).get("features").toString());
    assertEquals("6", queryResponse.getResults().get(2).get("id").toString());
    assertEquals(result2_features, queryResponse.getResults().get(2).get("features").toString());
    assertEquals("5", queryResponse.getResults().get(3).get("id").toString());
    assertEquals(result3_features, queryResponse.getResults().get(3).get("features").toString());
    assertEquals("4", queryResponse.getResults().get(4).get("id").toString());
    assertEquals(result4_features, queryResponse.getResults().get(4).get("features").toString());
    assertEquals("3", queryResponse.getResults().get(5).get("id").toString());
    assertEquals(result5_features, queryResponse.getResults().get(5).get("features").toString());
    assertEquals("2", queryResponse.getResults().get(6).get("id").toString());
    assertEquals(result6_features, queryResponse.getResults().get(6).get("features").toString());
    assertEquals("1", queryResponse.getResults().get(7).get("id").toString());
    assertEquals(result7_features, queryResponse.getResults().get(7).get("features").toString());

    // Close RestTestHarness
    if (restTestHarness != null) {
      restTestHarness.close();
      restTestHarness = null;
    }
  }

  private void createCollection(String name, String config, int numShards, int numReplicas)
      throws Exception {
    CollectionAdminResponse response;
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(name, config, numShards, numReplicas);
    response = create.process(solrClient);

    if (response.getStatus() != 0 || response.getErrorMessages() != null) {
      fail("Could not create collection. Response" + response);
    }
    cluster.waitForActiveCollection(name, numShards, numShards * numReplicas);
  }

  private void indexDocument(String collection, String id, String title, String description, int popularity)
      throws Exception {
    SolrInputDocument doc = new SolrInputDocument();
    doc.setField("id", id);
    doc.setField("title", title);
    doc.setField("description", description);
    doc.setField("popularity", popularity);
    if (popularity != 1) {
      // check that empty values will be read as default
      doc.setField("dvIntField", popularity);
      doc.setField("dvLongField", popularity);
      doc.setField("dvFloatField", ((float) popularity) / 10);
      doc.setField("dvDoubleField", ((double) popularity) / 10);
      doc.setField("dvStrNumField", popularity % 2 == 0 ? "F" : "T");
      doc.setField("dvStrBoolField", popularity % 2 == 0 ? "T" : "F");
    }
    solrClient.add(collection, doc);
  }

  private void indexDocuments(final String collection) throws Exception {
    final int collectionSize = 8;
    // put documents in random order to check that advanceExact is working correctly
    List<Integer> docIds = IntStream.rangeClosed(1, collectionSize).boxed().collect(toList());
    Collections.shuffle(docIds, random());

    int docCounter = 1;
    for (int docId : docIds) {
      final int popularity = docId;
      indexDocument(collection, String.valueOf(docId), "a1", "bloom", popularity);
      // maybe commit in the middle in order to check that everything works fine for multi-segment case
      if (docCounter == collectionSize / 2 && random().nextBoolean()) {
        solrClient.commit(collection);
      }
      docCounter++;
    }
    solrClient.commit(collection, true, true);
  }

  private void loadModelsAndFeatures() throws Exception {
    final String featureStore = "test";
    final String[] featureNames =
        new String[] {
          "powpularityS",
          "c3",
          "original",
          "dvIntFieldFeature",
          "dvLongFieldFeature",
          "dvFloatFieldFeature",
          "dvDoubleFieldFeature",
          "dvStrNumFieldFeature",
          "dvStrBoolFieldFeature"
        };
    final String jsonModelParams =
        "{\"weights\":{\"powpularityS\":1.0,\"c3\":1.0,\"original\":0.1,"
            + "\"dvIntFieldFeature\":0.1,\"dvLongFieldFeature\":0.1,"
            + "\"dvFloatFieldFeature\":0.1,\"dvDoubleFieldFeature\":0.1,\"dvStrNumFieldFeature\":0.1,\"dvStrBoolFieldFeature\":0.1}}";

    loadFeature(
        featureNames[0],
        SolrFeature.class.getName(),
        featureStore,
        "{\"q\":\"{!func}pow(popularity,2)\"}");
    loadFeature(featureNames[1], ValueFeature.class.getName(), featureStore, "{\"value\":2}");
    loadFeature(featureNames[2], OriginalScoreFeature.class.getName(), featureStore, null);
    loadFeature(
        featureNames[3],
        FieldValueFeature.class.getName(),
        featureStore,
        "{\"field\":\"dvIntField\"}");
    loadFeature(
        featureNames[4],
        FieldValueFeature.class.getName(),
        featureStore,
        "{\"field\":\"dvLongField\"}");
    loadFeature(
        featureNames[5],
        FieldValueFeature.class.getName(),
        featureStore,
        "{\"field\":\"dvFloatField\"}");
    loadFeature(
        featureNames[6],
        FieldValueFeature.class.getName(),
        featureStore,
        "{\"field\":\"dvDoubleField\",\"defaultValue\":-4.0}");
    loadFeature(
        featureNames[7],
        FieldValueFeature.class.getName(),
        featureStore,
        "{\"field\":\"dvStrNumField\",\"defaultValue\":-5}");
    loadFeature(
        featureNames[8],
        FieldValueFeature.class.getName(),
        featureStore,
        "{\"field\":\"dvStrBoolField\"}");

    loadModel(
        "powpularityS-model",
        LinearModel.class.getName(),
        featureNames,
        featureStore,
        jsonModelParams);
    reloadCollection(COLLECTION);
  }

  private void reloadCollection(String collection) throws Exception {
    CollectionAdminRequest.Reload reloadRequest =
        CollectionAdminRequest.reloadCollection(collection);
    CollectionAdminResponse response = reloadRequest.process(solrClient);
    assertEquals(0, response.getStatus());
    assertTrue(response.isSuccess());
  }

  // Helper methods for loading features and models via REST API
  private String getFeatureInJson(String name, String type, String fstore, String params) {
    final StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("\"name\":").append('"').append(name).append('"').append(",\n");
    sb.append("\"store\":").append('"').append(fstore).append('"').append(",\n");
    sb.append("\"class\":").append('"').append(type).append('"');
    if (params != null) {
      sb.append(",\n");
      sb.append("\"params\":").append(params);
    }
    sb.append("\n}\n");
    return sb.toString();
  }

  private String getModelInJson(
      String name, String type, String[] features, String fstore, String params) {
    final StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("\"name\":").append('"').append(name).append('"').append(",\n");
    sb.append("\"store\":").append('"').append(fstore).append('"').append(",\n");
    sb.append("\"class\":").append('"').append(type).append('"').append(",\n");
    sb.append("\"features\":").append('[');
    if (features.length > 0) {
      for (final String feature : features) {
        sb.append("\n\t{ ");
        sb.append("\"name\":").append('"').append(feature).append('"').append("},");
      }
      sb.deleteCharAt(sb.length() - 1);
    }
    sb.append("\n]\n");
    if (params != null) {
      sb.append(",\n");
      sb.append("\"params\":").append(params);
    }
    sb.append("\n}\n");
    return sb.toString();
  }

  private void loadFeature(String name, String type, String fstore, String params)
      throws Exception {
    final String feature = getFeatureInJson(name, type, fstore, params);
    String response = restTestHarness.put(
        ManagedFeatureStore.REST_END_POINT, feature);
    assertTrue("Failed to load feature: " + response, response.contains("\"status\":0"));
  }

  private void loadModel(
      String name, String type, String[] features, String fstore, String params) throws Exception {
    final String model = getModelInJson(name, type, features, fstore, params);
    String response = restTestHarness.put(
        ManagedModelStore.REST_END_POINT, model);
    assertTrue("Failed to load model: " + response, response.contains("\"status\":0"));
  }

  @AfterClass
  public static void after() throws Exception {
    if (null != tmpSolrHome) {
      PathUtils.deleteDirectory(tmpSolrHome);
      tmpSolrHome = null;
    }
    System.clearProperty("managed.schema.mutable");
  }
}
