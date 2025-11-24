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
package org.apache.solr.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.SolrPing;
import org.apache.solr.client.solrj.response.SolrPingResponse;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.junit.Test;

/**
 * ProcessBased test for PingRequestHandler in a distributed cluster.
 *
 * <p>Simplified version of PingRequestHandlerTest focusing on cluster-level ping operations.
 *
 * <p>Note: Original test (234 lines) contains mostly unit tests that directly instantiate
 * PingRequestHandler and call handler methods (handler.init(), handler.inform(h.getCore()),
 * handler.handleRequestBody()) - not cluster tests. These unit tests also manipulate healthcheck
 * files on local filesystem. Only testPingInClusterWithNoHealthCheck is a cluster test using
 * client API. This ProcessBased version includes only the cluster test.
 */
public class PingRequestHandlerTest_ProcessBased extends ProcessBasedUpgradeTestBase {

  protected int NUM_SHARDS = 2;
  protected int REPLICATION_FACTOR = 2;

  @Test
  public void testPingInClusterWithNoHealthCheck_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestPingInClusterWithNoHealthCheck();
  }

  @Test
  public void testPingInClusterWithNoHealthCheck_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestPingInClusterWithNoHealthCheck();
  }

  @Test
  public void testPingInClusterWithNoHealthCheck_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    doTestPingInClusterWithNoHealthCheck();
  }

  private void doTestPingInClusterWithNoHealthCheck() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(3)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset (use _default which doesn't have test-specific dependencies)
    Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "_default", "conf");
    String configName = "solrCloudCollectionConfig";
    cluster.uploadConfigSet(configDir, configName);

    // Create collection
    String collectionName = "testSolrCloudCollection";
    CollectionAdminRequest.createCollection(collectionName, configName, NUM_SHARDS, REPLICATION_FACTOR)
        .process(solrClient);    checkpoint("AFTER_COLLECTION_CREATE");

    // Send distributed ping query
    SolrPingWithDistrib reqDistrib = new SolrPingWithDistrib();
    reqDistrib.setDistrib(true);
    SolrPingResponse rsp = reqDistrib.process(solrClient, collectionName);
    assertEquals("Distributed ping should return status 0", 0, rsp.getStatus());
    assertTrue(
        "Distributed ping should indicate ZK connection",
        rsp.getResponseHeader().getBooleanArg("zkConnected"));

    // Send non-distributed ping query
    SolrPing reqNonDistrib = new SolrPing();
    rsp = reqNonDistrib.process(solrClient, collectionName);
    assertEquals("Non-distributed ping should return status 0", 0, rsp.getStatus());
    assertTrue(
        "Non-distributed ping should indicate ZK connection",
        rsp.getResponseHeader().getBooleanArg("zkConnected"));
  }

  /** Helper class to add distrib parameter to SolrPing. */
  static class SolrPingWithDistrib extends SolrPing {
    public SolrPing setDistrib(boolean distrib) {
      getParams().add("distrib", distrib ? "true" : "false");
      return this;
    }
  }
}
