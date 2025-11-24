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
package org.apache.solr.cloud.api.collections;

import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.tests.util.LuceneTestCase.Nightly;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.util.TimeOut;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased stress test for concurrent collection create/delete operations.
 *
 * <p>Tests that concurrent collection lifecycle operations work correctly across cluster upgrades.
 *
 * <p>Transformed from ConcurrentDeleteAndCreateCollectionTest to use checkpoint-based upgrade
 * testing.
 */
@Nightly
public class ConcurrentDeleteAndCreateCollectionTest_ProcessBased
    extends ProcessBasedUpgradeTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void testConcurrentCreateAndDeleteDoesNotFail_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestConcurrentCreateAndDeleteDoesNotFail();
  }

  @Test
  public void testConcurrentCreateAndDeleteDoesNotFail_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestConcurrentCreateAndDeleteDoesNotFail();
  }

  private void doTestConcurrentCreateAndDeleteDoesNotFail() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();
    cluster.waitForAllNodes(30);

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    final AtomicReference<Exception> failure = new AtomicReference<>();
    final int timeToRunSec = 30;
    final CreateDeleteCollectionThread[] threads = new CreateDeleteCollectionThread[10];

    // Upload configsets for each collection
    Path configsetPath = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "configset-2", "conf");
    for (int i = 0; i < threads.length; i++) {
      final String collectionName = "collection" + i;
      cluster.uploadConfigSet(configsetPath, collectionName);
    }

    final String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);

    for (int i = 0; i < threads.length; i++) {
      final String collectionName = "collection" + i;
      final SolrClient solrClient = new HttpSolrClient.Builder(baseUrl).build();
      threads[i] =
          new CreateDeleteSearchCollectionThread(
              "create-delete-search-" + i,
              collectionName,
              collectionName,
              timeToRunSec,
              solrClient,
              failure);
    }

    startAll(threads);
    joinAll(threads);

    assertNull("concurrent create and delete collection failed: " + failure.get(), failure.get());
  }

  @Test
  public void testConcurrentCreateAndDeleteOverTheSameConfig_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestConcurrentCreateAndDeleteOverTheSameConfig();
  }

  @Test
  public void testConcurrentCreateAndDeleteOverTheSameConfig_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestConcurrentCreateAndDeleteOverTheSameConfig();
  }

  private void doTestConcurrentCreateAndDeleteOverTheSameConfig() throws Exception {
    cluster =
        new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .build();
    cluster.start();
    cluster.waitForAllNodes(30);

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    final String configName = "testconfig";
    // Upload config once, to be used by all collections
    Path configsetPath = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "configset-2", "conf");
    cluster.uploadConfigSet(configsetPath, configName);

    final String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
    final AtomicReference<Exception> failure = new AtomicReference<>();
    final int timeToRunSec = 30;
    final CreateDeleteCollectionThread[] threads = new CreateDeleteCollectionThread[2];

    for (int i = 0; i < threads.length; i++) {
      final String collectionName = "collection" + i;
      final SolrClient solrClient = new HttpSolrClient.Builder(baseUrl).build();
      threads[i] =
          new CreateDeleteCollectionThread(
              "create-delete-" + i, collectionName, configName, timeToRunSec, solrClient, failure);
    }

    startAll(threads);
    joinAll(threads);

    assertNull("concurrent create and delete collection failed: " + failure.get(), failure.get());
  }

  private void joinAll(final CreateDeleteCollectionThread[] threads) {
    for (CreateDeleteCollectionThread t : threads) {
      try {
        t.joinAndClose();
      } catch (InterruptedException e) {
        Thread.interrupted();
        throw new RuntimeException(e);
      }
    }
  }

  private void startAll(final Thread[] threads) {
    for (Thread t : threads) {
      t.start();
    }
  }

  private static class CreateDeleteCollectionThread extends Thread {
    protected final String collectionName;
    protected final String configName;
    protected final long timeToRunSec;
    protected final SolrClient solrClient;
    protected final AtomicReference<Exception> failure;

    public CreateDeleteCollectionThread(
        String name,
        String collectionName,
        String configName,
        long timeToRunSec,
        SolrClient solrClient,
        AtomicReference<Exception> failure) {
      super(name);
      this.collectionName = collectionName;
      this.timeToRunSec = timeToRunSec;
      this.solrClient = solrClient;
      this.failure = failure;
      this.configName = configName;
    }

    @Override
    public void run() {
      final TimeOut timeout = new TimeOut(timeToRunSec, TimeUnit.SECONDS, TimeSource.NANO_TIME);
      while (!timeout.hasTimedOut() && failure.get() == null) {
        doWork();
      }
    }

    protected void doWork() {
      createCollection();
      deleteCollection();
    }

    protected void addFailure(Exception e) {
      log.error("Add Failure", e);
      synchronized (failure) {
        if (failure.get() != null) {
          failure.get().addSuppressed(e);
        } else {
          failure.set(e);
        }
      }
    }

    private void createCollection() {
      try {
        final CollectionAdminResponse response =
            CollectionAdminRequest.createCollection(collectionName, configName, 1, 1)
                .process(solrClient);
        if (response.getStatus() != 0) {
          addFailure(new RuntimeException("failed to create collection " + collectionName));
        }
      } catch (Exception e) {
        addFailure(e);
      }
    }

    private void deleteCollection() {
      try {
        final CollectionAdminRequest.Delete deleteCollectionRequest =
            CollectionAdminRequest.deleteCollection(collectionName);
        final CollectionAdminResponse response = deleteCollectionRequest.process(solrClient);
        if (response.getStatus() != 0) {
          addFailure(new RuntimeException("failed to delete collection " + collectionName));
        }
      } catch (Exception e) {
        addFailure(e);
      }
    }

    public void joinAndClose() throws InterruptedException {
      try {
        super.join(60000);
      } finally {
        IOUtils.closeQuietly(solrClient);
      }
    }
  }

  private static class CreateDeleteSearchCollectionThread extends CreateDeleteCollectionThread {

    public CreateDeleteSearchCollectionThread(
        String name,
        String collectionName,
        String configName,
        long timeToRunSec,
        SolrClient solrClient,
        AtomicReference<Exception> failure) {
      super(name, collectionName, configName, timeToRunSec, solrClient, failure);
    }

    @Override
    protected void doWork() {
      super.doWork();
      searchNonExistingCollection();
    }

    private void searchNonExistingCollection() {
      try {
        solrClient.query(collectionName, new SolrQuery("*"));
      } catch (Exception e) {
        if (!e.getMessage().contains("not found") && !e.getMessage().contains("Can not find")) {
          addFailure(e);
        }
      }
    }
  }
}
