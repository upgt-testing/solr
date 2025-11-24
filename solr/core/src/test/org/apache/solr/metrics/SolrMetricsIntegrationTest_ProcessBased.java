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

package org.apache.solr.metrics;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.http.client.HttpClient;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.cloud.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.util.Utils;
import org.junit.Test;

/**
 * ProcessBased transformation of SolrMetricsIntegrationTest (simplified).
 *
 * <p>This transformation includes only the cluster test (testZkMetrics) that tests ZooKeeper
 * metrics via /admin/metrics endpoint. The original test also contained two unit tests
 * (testConfigureReporter, testCoreContainerMetrics) that require direct CoreContainer access - not
 * suitable for ProcessBased transformation.
 */
public class SolrMetricsIntegrationTest_ProcessBased extends ProcessBasedUpgradeTestBase {

  @Test
  public void testZkMetrics_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runTestZkMetrics();
  }

  @Test
  public void testZkMetrics_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runTestZkMetrics();
  }

  private void runTestZkMetrics() throws Exception {
    System.setProperty("metricsEnabled", "true");
    try {
      cluster =
          new ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(3)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();
      cluster.waitForAllNodes(30);

      // Upload config
      cluster.uploadConfigSet(
          Paths.get(SolrTestCaseJ4.TEST_HOME(), "collection1", "conf"), "conf");

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Get base URL from node 0
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
      String metricsUrl = baseUrl + "/admin/metrics?key=solr.node:CONTAINER.zkClient&wt=json";

      try (HttpSolrClient solrClient =
          new HttpSolrClient.Builder(baseUrl).withDefaultCollection("").build()) {
        HttpClient httpClient = solrClient.getHttpClient();

        @SuppressWarnings("unchecked")
        Map<String, Object> zkMetrics =
            (Map<String, Object>)
                Utils.getObjectByPath(
                    Utils.executeGET(httpClient, metricsUrl, Utils.JSONCONSUMER),
                    false,
                    List.of("metrics", "solr.node:CONTAINER.zkClient"));

        Set<String> allKeys =
            Set.of(
                "watchesFired",
                "reads",
                "writes",
                "bytesRead",
                "bytesWritten",
                "multiOps",
                "cumulativeMultiOps",
                "childFetches",
                "cumulativeChildrenFetched",
                "existsChecks",
                "deletes");

        for (String k : allKeys) {
          assertNotNull("Expected metric key: " + k, zkMetrics.get(k));
        }

        // Perform an operation that affects ZK metrics
        Utils.executeGET(
            httpClient, baseUrl + "/api/cluster/zookeeper/children/live_nodes", Utils.JSONCONSUMER);

        @SuppressWarnings("unchecked")
        Map<String, Object> zkMetricsNew =
            (Map<String, Object>)
                Utils.getObjectByPath(
                    Utils.executeGET(httpClient, metricsUrl, Utils.JSONCONSUMER),
                    false,
                    List.of("metrics", "solr.node:CONTAINER.zkClient"));

        // Verify metrics changed after operations
        assertTrue(
            "childFetches should have increased",
            findDelta(zkMetrics, zkMetricsNew, "childFetches") >= 1);
        assertTrue(
            "cumulativeChildrenFetched should have increased",
            findDelta(zkMetrics, zkMetricsNew, "cumulativeChildrenFetched") >= 3);
        assertTrue(
            "existsChecks should have increased",
            findDelta(zkMetrics, zkMetricsNew, "existsChecks") >= 4);
      }
    } finally {
      System.clearProperty("metricsEnabled");
    }
  }

  private long findDelta(Map<String, Object> m1, Map<String, Object> m2, String k) {
    return ((Number) m2.get(k)).longValue() - ((Number) m1.get(k)).longValue();
  }
}
