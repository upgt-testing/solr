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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.zookeeper.CreateMode;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased upgrade test for LiveNodes watching stress testing.
 *
 * <p>Tests that parallel additions of fake live_nodes entries in ZooKeeper are correctly detected
 * by ZkStateReader's caching mechanism.
 *
 * <p>Transformed from {@link TestStressLiveNodes} to support checkpoint-based upgrade testing.
 */
public class TestStressLiveNodes_ProcessBased extends ProcessBasedUpgradeTestBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  // Simplified iteration count for practical testing
  private static final int NUM_ITERS = 20;
  private static final int WAIT_TIME_SECONDS = 30;

  private CloudSolrClient cloudClient;

  @Test
  public void testStressLiveNodes_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runStressTest();
  }

  @Test
  public void testStressLiveNodes_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runStressTest();
  }

  private void runStressTest() throws Exception {
    // Setup cluster with single node
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .withUpgradeVersionFromSystemProperty()
            .build();
    cluster.start();
    solrClient = cluster.getSolrClient();

    cloudClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Run stress test iterations
    testLiveNodesStress();
  }

  private void testLiveNodesStress() throws Exception {
    for (int iter = 0; iter < NUM_ITERS; iter++) {
      // Sanity check: ZK should have 1 real live node
      List<String> actualLiveNodes = getTrueLiveNodesFromZk();
      assertEquals("iter" + iter + ": " + actualLiveNodes, 1, actualLiveNodes.size());

      // Force update cached live nodes
      cloudClient.getClusterStateProvider().connect();

      // Verify local state knows about the 1 real live node
      List<String> cachedLiveNodes = getCachedLiveNodesFromLocalState(actualLiveNodes.size());
      assertEquals(
          "iter" + iter + " " + actualLiveNodes.size() + " != " + cachedLiveNodes.size(),
          actualLiveNodes,
          cachedLiveNodes);

      // Parallel adds to live_nodes
      final int numThreads = 2 + (iter % 3); // 2-4 threads
      final int numNodesPerThread = 1 + (iter % 4); // 1-4 nodes per thread

      log.info(
          "Parallel adds to live nodes: iter={}, threads={}, nodesPerThread={}",
          iter,
          numThreads,
          numNodesPerThread);

      // Create thrashers (NOTE: ephemeral nodes, so can't close until assertions done)
      final List<LiveNodeTrasher> thrashers = new ArrayList<>(numThreads);
      for (int i = 0; i < numThreads; i++) {
        thrashers.add(new LiveNodeTrasher("T" + iter + "_" + i, numNodesPerThread));
      }

      try {
        ExecutorService executorService =
            ExecutorUtil.newMDCAwareFixedThreadPool(
                thrashers.size() + 1,
                new SolrNamedThreadFactory("live_nodes_thrasher_iter" + iter));

        executorService.invokeAll(thrashers);
        executorService.shutdown();

        if (!executorService.awaitTermination(WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
          for (LiveNodeTrasher thrasher : thrashers) {
            thrasher.stop();
          }
        }

        assertTrue(
            "iter" + iter + ": thrashers didn't finish",
            executorService.awaitTermination(WAIT_TIME_SECONDS, TimeUnit.SECONDS));

        // Verify real live_nodes from ZK match what thrashers added
        int totalAdded = 1; // 1 real node initially
        for (LiveNodeTrasher thrasher : thrashers) {
          totalAdded += thrasher.getNumAdded();
        }

        actualLiveNodes = getTrueLiveNodesFromZk();
        assertEquals("iter" + iter, totalAdded, actualLiveNodes.size());

        // Verify local client knows correct set of live nodes
        cachedLiveNodes = getCachedLiveNodesFromLocalState(actualLiveNodes.size());
        assertEquals(
            "iter" + iter + " " + actualLiveNodes.size() + " != " + cachedLiveNodes.size(),
            actualLiveNodes,
            cachedLiveNodes);

      } finally {
        for (LiveNodeTrasher thrasher : thrashers) {
          thrasher.close(); // Free ephemeral nodes
        }
      }
    }
  }

  /** Returns true set of live nodes from ZK as sorted list */
  private List<String> getTrueLiveNodesFromZk() throws Exception {
    SolrZkClient zkClient = cluster.getZkClient();
    ArrayList<String> result =
        new ArrayList<>(zkClient.getChildren(ZkStateReader.LIVE_NODES_ZKNODE, null, true));
    Collections.sort(result);
    return result;
  }

  /**
   * Returns cached live nodes from CloudSolrClient. Retries with sleep until size matches
   * expected.
   */
  private List<String> getCachedLiveNodesFromLocalState(int expectedCount) throws Exception {
    ArrayList<String> result = null;

    for (int i = 0; i < 10; i++) {
      result = new ArrayList<>(cloudClient.getClusterState().getLiveNodes());
      if (expectedCount != result.size()) {
        log.info(
            "Sleeping #{} to give watchers chance to finish: {} != {}",
            i,
            expectedCount,
            result.size());
        Thread.sleep(200);
      } else {
        break;
      }
    }

    if (expectedCount != result.size()) {
      log.error(
          "Gave up waiting for live nodes to match expected: {} != {}",
          expectedCount,
          result.size());
    }

    Collections.sort(result);
    return result;
  }

  /** Thread that adds fake ephemeral live_nodes entries to ZK */
  private class LiveNodeTrasher implements Callable<Integer> {
    private final String id;
    private final int numNodesToAdd;
    private final SolrZkClient zkClient;

    private boolean running = false;
    private int numAdded = 0;

    public LiveNodeTrasher(String id, int numNodesToAdd) {
      this.id = id;
      this.numNodesToAdd = numNodesToAdd;
      // Use cluster's ZK client connection info to create new client
      this.zkClient =
          new SolrZkClient.Builder()
              .withUrl(cluster.getZkServer().getZkAddress())
              .withTimeout(15000, TimeUnit.MILLISECONDS)
              .build();
    }

    @Override
    public Integer call() throws Exception {
      running = true;
      for (int i = 0; i < numNodesToAdd && running; i++) {
        String nodeName = "fake_node_" + id + "_" + i + ":8983_solr";
        String nodePath = ZkStateReader.LIVE_NODES_ZKNODE + "/" + nodeName;

        try {
          zkClient.makePath(nodePath, CreateMode.EPHEMERAL, true);
          numAdded++;
        } catch (Exception e) {
          log.error("Failed to create node {}", nodePath, e);
          throw e;
        }
      }
      return numAdded;
    }

    public void stop() {
      running = false;
    }

    public int getNumAdded() {
      return numAdded;
    }

    public void close() {
      if (zkClient != null) {
        zkClient.close();
      }
    }
  }
}
