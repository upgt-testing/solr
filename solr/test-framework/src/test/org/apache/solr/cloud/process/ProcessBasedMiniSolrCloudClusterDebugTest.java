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
package org.apache.solr.cloud.process;

import java.io.File;
import org.apache.solr.SolrTestCaseJ4;
import org.junit.Test;

/**
 * Debug test for ProcessBasedMiniSolrCloudCluster to diagnose subprocess startup issues.
 */
public class ProcessBasedMiniSolrCloudClusterDebugTest extends SolrTestCaseJ4 {

  @Test
  public void testSubprocessDebug() throws Exception {
    String startHome = System.getProperty("solr.start.home");

    if (startHome == null || startHome.isEmpty()) {
      System.out.println("Skipping debug test - solr.start.home not set");
      return;
    }

    File startHomeDir = new File(startHome);
    if (!startHomeDir.exists()) {
      System.out.println("Skipping debug test - solr.start.home does not exist: " + startHome);
      return;
    }

    System.out.println("=== DEBUG TEST START ===");
    System.out.println("Start home: " + startHome);

    ProcessBasedMiniSolrCloudCluster cluster = null;

    try {
      System.out.println("Creating cluster...");
      cluster = new ProcessBasedMiniSolrCloudCluster.Builder()
          .withNodeCount(1)
          .withStartVersionFromSystemProperty()
          .build();

      System.out.println("Starting cluster...");
      System.out.println("Cluster dir: " + cluster.getClusterDir());

      cluster.start();

      System.out.println("Cluster started successfully!");
      System.out.println("ZK host: " + cluster.getZkHost());
      System.out.println("Node 0 URL: " + cluster.getJettySolrRunnerBaseUrl(0));

      // Give it time to fully start
      Thread.sleep(5000);

      System.out.println("Checking node health...");
      boolean healthy = cluster.isNodeHealthy(0);
      System.out.println("Node 0 healthy: " + healthy);

    } catch (Exception e) {
      System.err.println("ERROR: " + e.getMessage());
      e.printStackTrace();
      throw e;
    } finally {
      if (cluster != null) {
        System.out.println("Shutting down cluster...");
        cluster.shutdown();
      }
    }

    System.out.println("=== DEBUG TEST END ===");
  }
}
