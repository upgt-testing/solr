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
import static org.junit.Assert.assertNotNull;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased upgrade test for stress testing atomic updates.
 *
 * <p>Simplified/focused transformation from {@link TestStressCloudBlindAtomicUpdates}. Tests
 * parallel atomic "inc" operations on numeric fields across upgrade checkpoints.
 *
 * <p><b>Note:</b> This is a simplified version focusing on essential atomic update stress testing.
 * Original test (562 lines) tests multiple field type combinations (dv, stored, indexed variants).
 * This version focuses on core stress testing pattern with single field type.
 */
public class TestStressCloudBlindAtomicUpdates_ProcessBased extends ProcessBasedUpgradeTestBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final String COLLECTION_NAME = "stress_test_col";

  // Simplified constants
  private static final int NUM_THREADS = 3;
  private static final int DOC_ID_INCR = 2;
  private static final int NUM_DOCS_TO_CHECK = 20;
  private static final int NUM_DOCS_IN_INDEX = NUM_DOCS_TO_CHECK * DOC_ID_INCR;

  private CloudSolrClient collectionClient;
  private final List<SolrClient> nodeClients = new ArrayList<>();
  private ExecutorService execService;

  @Test
  public void testAtomicUpdates_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runStressTest();
  }

  @Test
  public void testAtomicUpdates_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runStressTest();
  }

  @Test
  public void testAtomicUpdates_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE;
    runStressTest();
  }

  @Test
  public void testAtomicUpdates_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_INDEX;
    runStressTest();
  }

  private void runStressTest() throws Exception {
    // Setup executor service for parallel workers
    execService =
        ExecutorUtil.newMDCAwareFixedThreadPool(
            NUM_THREADS, new SolrNamedThreadFactory("AtomicUpdateWorkers"));

    try {
      setupCluster();
      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      createCollection();
      checkpoint(SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE);

      // Test atomic updates with docValues field
      testField("long_dv");

    } finally {
      if (execService != null) {
        ExecutorUtil.shutdownAndAwaitTermination(execService);
        execService = null;
      }
      closeNodeClients();
    }
  }

  private void setupCluster() throws Exception {
    int numShards = 2;
    int repFactor = 2;
    int numNodes = numShards * repFactor;

    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(numNodes)
            .withStartVersionFromSystemProperty()
            .withUpgradeVersionFromSystemProperty()
            .build();
    cluster.start();
    solrClient = cluster.getSolrClient();

    // Upload configset
    Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "_default", "conf");
    cluster.uploadConfigSet(configDir, "stress_config");
  }

  private void createCollection() throws Exception {
    CollectionAdminRequest.createCollection(COLLECTION_NAME, "stress_config", 2, 2)
        .withProperty("config", "solrconfig-tlog.xml")
        .withProperty("schema", "schema-minimal-atomic-stress.xml")
        .process(solrClient);

    // Give collection time to become active
    Thread.sleep(2000);

    collectionClient = cluster.getSolrClient();

    // Create per-node clients
    for (int i = 0; i < cluster.getNodeCount(); i++) {
      String baseUrl = cluster.getJettySolrRunnerBaseUrl(i);
      nodeClients.add(new HttpSolrClient.Builder(baseUrl + "/" + COLLECTION_NAME).build());
    }
  }

  private void testField(String numericFieldName) throws Exception {
    log.info(
        "Testing {}: numDocs={}, threads={}, incr={}",
        numericFieldName,
        NUM_DOCS_IN_INDEX,
        NUM_THREADS,
        DOC_ID_INCR);

    final CountDownLatch abortLatch = new CountDownLatch(1);
    final AtomicLong[] expected = new AtomicLong[NUM_DOCS_TO_CHECK];

    // Seed the index with initial values
    for (int id = 0; id < NUM_DOCS_IN_INDEX; id++) {
      final int initValue = new Random().nextInt(1000);
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", "" + id);
      doc.addField(numericFieldName, initValue);

      collectionClient.add(doc);

      if (id % DOC_ID_INCR == 0) {
        expected[id / DOC_ID_INCR] = new AtomicLong(initValue);
      }
    }

    collectionClient.commit();

    checkpoint(SolrUpgradeCheckpoints.AFTER_INDEX);

    // Verify seeding
    ModifiableSolrParams queryParams = new ModifiableSolrParams();
    queryParams.set("q", "*:*");
    assertEquals(NUM_DOCS_IN_INDEX, collectionClient.query(queryParams).getResults().getNumFound());

    // Spawn parallel workers to hammer atomic updates
    List<Future<Worker>> results = new ArrayList<>(NUM_THREADS);
    for (int workerId = 0; workerId < NUM_THREADS; workerId++) {
      Worker worker =
          new Worker(
              workerId,
              expected,
              abortLatch,
              new Random(System.currentTimeMillis() + workerId),
              numericFieldName);
      results.add(execService.submit(worker, worker));
    }

    // Check worker results
    for (Future<Worker> r : results) {
      try {
        Worker w = r.get();
        if (!w.finishedOk) {
          abortLatch.countDown();
          log.error("Worker {} didn't finish ok", w.workerId);
        }
      } catch (ExecutionException ee) {
        Throwable rootCause = ee.getCause();
        if (rootCause instanceof Error) {
          throw (Error) rootCause;
        }
        throw ee;
      }
    }

    // Verify final values match expected
    collectionClient.commit();
    for (int id = 0; id < expected.length; id++) {
      final long expectedValue = expected[id].get();
      final int docId = id * DOC_ID_INCR;
      ModifiableSolrParams verifyParams = new ModifiableSolrParams();
      verifyParams.set("q", "id:" + docId);
      verifyParams.set("fl", numericFieldName);
      final long actualValue =
          (Long) collectionClient.query(verifyParams).getResults().get(0).getFieldValue(numericFieldName);
      assertEquals(
          "Doc " + docId + " field " + numericFieldName + " mismatch",
          expectedValue,
          actualValue);
    }
  }

  private void closeNodeClients() {
    for (SolrClient client : nodeClients) {
      if (client != null) {
        try {
          client.close();
        } catch (Exception e) {
          log.warn("Error closing node client", e);
        }
      }
    }
    nodeClients.clear();
  }

  /** Worker thread that performs atomic increment operations */
  private class Worker implements Runnable {
    final int workerId;
    final AtomicLong[] expected;
    final CountDownLatch abortLatch;
    final Random rand;
    final String fieldName;
    boolean finishedOk = false;

    public Worker(
        int workerId,
        AtomicLong[] expected,
        CountDownLatch abortLatch,
        Random rand,
        String fieldName) {
      this.workerId = workerId;
      this.expected = expected;
      this.abortLatch = abortLatch;
      this.rand = rand;
      this.fieldName = fieldName;
    }

    @Override
    public void run() {
      try {
        // Perform multiple atomic increment operations
        final int numOps = 50; // Simplified from original's atLeast()
        for (int opNum = 0; opNum < numOps && 0 < abortLatch.getCount(); opNum++) {
          // Pick random doc
          int docIndex = rand.nextInt(expected.length);
          int docId = docIndex * DOC_ID_INCR;

          // Random increment value
          int incValue = rand.nextInt(100) - 50; // Range: -50 to +50

          // Build atomic update
          SolrInputDocument doc = new SolrInputDocument();
          doc.addField("id", "" + docId);
          Map<String, Object> atomicUpdate = new HashMap<>();
          atomicUpdate.put("inc", incValue);
          SolrInputField field = new SolrInputField(fieldName);
          field.setValue(atomicUpdate);
          doc.put(fieldName, field);

          // Pick random client
          SolrClient client = nodeClients.get(rand.nextInt(nodeClients.size()));

          // Send update
          UpdateRequest req = new UpdateRequest();
          req.add(doc);
          req.process(client);

          // Track expected value
          expected[docIndex].addAndGet(incValue);
        }
        finishedOk = true;
      } catch (Exception e) {
        abortLatch.countDown();
        log.error("Worker {} failed", workerId, e);
        throw new RuntimeException("Worker " + workerId + " failed", e);
      }
    }
  }
}
