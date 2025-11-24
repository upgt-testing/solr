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

package org.apache.solr.response;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.NoOpResponseParser;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.Test;

/**
 * ProcessBased transformation of TestRawTransformer (simplified).
 *
 * <p>Tests Raw JSON and XML output for fields when used with and without the unique key field.
 *
 * <p>Original test randomly used standalone or cloud mode. This transformation only tests cloud
 * mode using ProcessBasedMiniSolrCloudCluster. All test logic is client-side (QueryRequest
 * operations).
 *
 * <p>See SOLR-7993
 */
public class TestRawTransformer_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final int MAX = 10;

  @Test
  public void testTransformers_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestTransformers();
  }

  @Test
  public void testTransformers_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestTransformers();
  }

  @Test
  public void testTransformers_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE;
    runTestTransformers();
  }

  @Test
  public void testTransformers_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_INDEX;
    runTestTransformers();
  }

  private void runTestTransformers() throws Exception {
    final String configName = "testRawTransformerConfig";
    final Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "_default", "conf");

    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(3)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();    // Upload config
    cluster.uploadConfigSet(configDir, configName);

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Create collection
    solrClient = cluster.getSolrClient();
    CollectionAdminRequest.createCollection("collection1", configName, 3, 1)
        .setProperties(
            Map.of("config", "solrconfig-minimal.xml", "schema", "schema_latest.xml"))
        .process(solrClient);

    checkpoint(SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE);

    // Index documents
    initIndex();

    checkpoint(SolrUpgradeCheckpoints.AFTER_INDEX);

    // Test XML transformer
    testXmlTransformer();

    // Test JSON transformer
    testJsonTransformer();
  }

  private void initIndex() throws Exception {
    // Build a simple index
    for (int i = 0; i < MAX; i++) {
      SolrInputDocument sdoc = new SolrInputDocument();
      sdoc.addField("id", i);
      // below are single-valued fields
      sdoc.addField(
          "subject",
          "{poffL:[{offL:[{oGUID:\"79D5A31D-B3E4-4667-B812-09DF4336B900\",oID:\"OO73XRX\",prmryO:1,oRank:1,addTp:\"Office\",addCd:\"AA4GJ5T\",ad1:\"102 S 3rd St Ste 100\",city:\"Carson City\",st:\"MI\",zip:\"48811\",lat:43.176885,lng:-84.842919,phL:[\"(989) 584-1308\"],faxL:[\"(989) 584-6453\"]}]}]}");
      sdoc.addField(
          "author",
          "<root><child1>some</child1><child2>trivial</child2><child3>xml</child3></root>");
      // below are multiValued fields
      sdoc.addField("links", "{an_array:[1,2,3]}");
      sdoc.addField("links", "{an_array:[4,5,6]}");
      sdoc.addField("content_type", "<root>one</root>");
      sdoc.addField("content_type", "<root>two</root>");
      solrClient.add("collection1", sdoc);
    }
    solrClient.commit("collection1");
    assertEquals(
        MAX,
        solrClient
            .query("collection1", new ModifiableSolrParams(Map.of("q", new String[] {"*:*"})))
            .getResults()
            .getNumFound());
  }

  private void testXmlTransformer() throws Exception {
    QueryRequest req =
        new QueryRequest(
            new ModifiableSolrParams(
                Map.of(
                    "q",
                    new String[] {"*:*"},
                    "fl",
                    new String[] {"author:[xml],content_type:[xml]"},
                    "wt",
                    new String[] {"xml"})));
    req.setResponseParser(XML_NOOP_RESPONSE_PARSER);
    String strResponse = (String) solrClient.request(req, "collection1").get("response");
    assertTrue(
        "response does not contain raw XML encoding: " + strResponse,
        strResponse.contains(
            "<raw name=\"author\"><root><child1>some</child1><child2>trivial</child2><child3>xml</child3></root></raw>"));
    assertTrue(
        "response (multiValued) does not contain raw XML encoding: " + strResponse,
        Pattern.compile(
                "<arr name=\"content_type\">\\s*<raw><root>one</root></raw>\\s*<raw><root>two</root></raw>\\s*</arr>")
            .matcher(strResponse)
            .find());

    req =
        new QueryRequest(
            new ModifiableSolrParams(
                Map.of(
                    "q",
                    new String[] {"*:*"},
                    "fl",
                    new String[] {"author,content_type"},
                    "wt",
                    new String[] {"xml"})));
    req.setResponseParser(XML_NOOP_RESPONSE_PARSER);
    strResponse = (String) solrClient.request(req, "collection1").get("response");
    assertTrue(
        "response does not contain escaped XML encoding: " + strResponse,
        strResponse.contains("<str name=\"author\">&lt;root&gt;&lt;child1"));
    assertTrue(
        "response (multiValued) does not contain escaped XML encoding: " + strResponse,
        Pattern.compile("<arr name=\"content_type\">\\s*<str>&lt;root&gt;")
            .matcher(strResponse)
            .find());

    req =
        new QueryRequest(
            new ModifiableSolrParams(
                Map.of(
                    "q",
                    new String[] {"*:*"},
                    "fl",
                    new String[] {"author:[xml],content_type:[xml]"},
                    "wt",
                    new String[] {"json"})));
    req.setResponseParser(JSON_NOOP_RESPONSE_PARSER);
    strResponse = (String) solrClient.request(req, "collection1").get("response");
    assertTrue(
        "unexpected serialization of XML field value in JSON response: " + strResponse,
        strResponse.contains("\"author\":\"<root><child1>some</child1>"));
    assertTrue(
        "unexpected (multiValued) serialization of XML field value in JSON response: "
            + strResponse,
        strResponse.contains("\"content_type\":[\"<root>one</root>"));
  }

  private void testJsonTransformer() throws Exception {
    QueryRequest req =
        new QueryRequest(
            new ModifiableSolrParams(
                Map.of(
                    "q",
                    new String[] {"*:*"},
                    "fl",
                    new String[] {"subject:[json],links:[json]"},
                    "wt",
                    new String[] {"json"})));
    req.setResponseParser(JSON_NOOP_RESPONSE_PARSER);
    String strResponse = (String) solrClient.request(req, "collection1").get("response");
    assertTrue(
        "response does not contain right JSON encoding: " + strResponse,
        strResponse.contains("\"subject\":{poffL:[{offL:[{oGUID:\"7"));
    assertTrue(
        "response (multiValued) does not contain right JSON encoding: " + strResponse,
        Pattern.compile("\"links\":\\[\\{an_array:\\[1,2,3]},\\s*\\{an_array:\\[4,5,6]}]")
            .matcher(strResponse)
            .find());

    req =
        new QueryRequest(
            new ModifiableSolrParams(
                Map.of(
                    "q",
                    new String[] {"*:*"},
                    "fl",
                    new String[] {"id", "subject,links"},
                    "wt",
                    new String[] {"json"})));
    req.setResponseParser(JSON_NOOP_RESPONSE_PARSER);
    strResponse = (String) solrClient.request(req, "collection1").get("response");
    assertTrue(
        "response does not contain right JSON encoding: " + strResponse,
        strResponse.contains("subject\":\""));
    assertTrue(
        "response (multiValued) does not contain right JSON encoding: " + strResponse,
        strResponse.contains("\"links\":[\""));
  }

  private static final NoOpResponseParser XML_NOOP_RESPONSE_PARSER = new NoOpResponseParser();
  private static final NoOpResponseParser JSON_NOOP_RESPONSE_PARSER =
      new NoOpResponseParser() {
        @Override
        public String getWriterType() {
          return "json";
        }
      };
}
