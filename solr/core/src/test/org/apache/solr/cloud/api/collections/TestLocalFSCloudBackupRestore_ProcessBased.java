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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.junit.Test;

/**
 * ProcessBased test for local file-system backup/restore capability.
 *
 * <p>Simplified version of TestLocalFSCloudBackupRestore focusing on core backup/restore
 * functionality without custom repository error injection.
 *
 * <p>Note: Original test extends AbstractCloudBackupRestoreTestCase (513 lines) and uses custom
 * solr.xml with PoisonedRepository for error testing - not compatible with ProcessBased. This
 * simplified version uses default LocalFileSystemRepository and tests basic backup/restore
 * workflow.
 */
// Backups do checksum validation against a footer value not present in 'SimpleText'
@LuceneTestCase.SuppressCodecs({"SimpleText"})
public class TestLocalFSCloudBackupRestore_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final String COLLECTION_NAME = "backuprestore";
  private static final String BACKUP_NAME = "mytestbackup";
  private static final int NUM_SHARDS = 2;
  private static final int NUM_REPLICAS = 2;

  @Test
  public void testBackupAndRestore_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestBackupAndRestore();
  }

  @Test
  public void testBackupAndRestore_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestBackupAndRestore();
  }

  @Test
  public void testBackupAndRestore_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = "AFTER_COLLECTION_CREATE";
    doTestBackupAndRestore();
  }

  @Test
  public void testBackupAndRestore_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = "AFTER_INDEX";
    doTestBackupAndRestore();
  }

  @Test
  public void testBackupAndRestore_AFTER_BACKUP() throws Exception {
    upgradeCheckpoint = "AFTER_BACKUP";
    doTestBackupAndRestore();
  }

  private void doTestBackupAndRestore() throws Exception {
    File backupLocationDir = Files.createTempDirectory("backup-test").toFile();
    backupLocationDir.deleteOnExit();
    String backupLocation = backupLocationDir.getAbsolutePath();

    System.setProperty("solr.allowPaths", "*");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(NUM_SHARDS)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf1");

      // Create collection
      CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", NUM_SHARDS, NUM_REPLICAS)
          .process(solrClient);      checkpoint("AFTER_COLLECTION_CREATE");

      // Index some documents
      int numDocs = 100;
      for (int i = 0; i < numDocs; i++) {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.add("id", String.valueOf(i), "name_s", "name" + i);
        updateRequest.commit(solrClient, COLLECTION_NAME);
      }

      // Verify documents indexed
      QueryResponse queryResponse = solrClient.query(COLLECTION_NAME, new SolrQuery("*:*"));
      assertEquals(
          "Should have " + numDocs + " documents", numDocs, queryResponse.getResults().getNumFound());

      checkpoint("AFTER_INDEX");

      // Perform backup using default repository
      CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
          .setLocation(backupLocation)
          .process(solrClient);

      checkpoint("AFTER_BACKUP");

      // Verify backup directory exists
      File backupDir = new File(backupLocation);
      assertTrue("Backup location should exist", backupDir.exists());
      assertTrue("Backup location should be a directory", backupDir.isDirectory());

      // Delete original collection
      CollectionAdminRequest.deleteCollection(COLLECTION_NAME).process(solrClient);

      // Wait for collection to be deleted
      waitForCollectionToDisappear(COLLECTION_NAME);

      // Restore collection from backup
      String restoredCollectionName = COLLECTION_NAME + "_restored";
      CollectionAdminRequest.restoreCollection(restoredCollectionName, BACKUP_NAME)
          .setLocation(backupLocation)
          .process(solrClient);      // Verify restored collection has all documents
      queryResponse = solrClient.query(restoredCollectionName, new SolrQuery("*:*"));
      assertEquals(
          "Restored collection should have " + numDocs + " documents",
          numDocs,
          queryResponse.getResults().getNumFound());

      // Verify a specific document
      queryResponse = solrClient.query(restoredCollectionName, new SolrQuery("id:42"));
      assertEquals("Should find document with id:42", 1, queryResponse.getResults().getNumFound());
      assertEquals(
          "Document should have correct name field",
          "name42",
          queryResponse.getResults().get(0).getFieldValue("name_s"));
    } finally {
      System.clearProperty("solr.allowPaths");
    }
  }

  /**
   * Test incremental backup functionality.
   */
  @Test
  public void testIncrementalBackup_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestIncrementalBackup();
  }

  @Test
  public void testIncrementalBackup_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestIncrementalBackup();
  }

  private void doTestIncrementalBackup() throws Exception {
    File backupLocationDir = Files.createTempDirectory("backup-incremental").toFile();
    backupLocationDir.deleteOnExit();
    String backupLocation = backupLocationDir.getAbsolutePath();

    System.setProperty("solr.allowPaths", "*");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(NUM_SHARDS)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf1");

      // Create collection
      CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", NUM_SHARDS, NUM_REPLICAS)
          .process(solrClient);      // Index initial documents
      int initialDocs = 50;
      for (int i = 0; i < initialDocs; i++) {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.add("id", String.valueOf(i), "name_s", "initial" + i);
        updateRequest.commit(solrClient, COLLECTION_NAME);
      }

      // First backup (incremental)
      CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
          .setLocation(backupLocation)
          .setIncremental(true)
          .process(solrClient);

      // Index more documents
      int moreDocs = 25;
      for (int i = initialDocs; i < initialDocs + moreDocs; i++) {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.add("id", String.valueOf(i), "name_s", "additional" + i);
        updateRequest.commit(solrClient, COLLECTION_NAME);
      }

      // Second incremental backup
      CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
          .setLocation(backupLocation)
          .setIncremental(true)
          .process(solrClient);

      // Delete original collection
      CollectionAdminRequest.deleteCollection(COLLECTION_NAME).process(solrClient);
      waitForCollectionToDisappear(COLLECTION_NAME);

      // Restore from incremental backup (should get all documents)
      String restoredCollectionName = COLLECTION_NAME + "_restored_inc";
      CollectionAdminRequest.restoreCollection(restoredCollectionName, BACKUP_NAME)
          .setLocation(backupLocation)
          .process(solrClient);      // Verify all documents are restored
      QueryResponse queryResponse = solrClient.query(restoredCollectionName, new SolrQuery("*:*"));
      assertEquals(
          "Restored collection should have all documents",
          initialDocs + moreDocs,
          queryResponse.getResults().getNumFound());
    } finally {
      System.clearProperty("solr.allowPaths");
    }
  }

  /**
   * Wait for a collection to disappear from cluster state.
   */
  private void waitForCollectionToDisappear(String collectionName) throws Exception {
    int maxWaitSecs = 330;
    for (int i = 0; i < maxWaitSecs; i++) {
      ClusterState clusterState = solrClient.getClusterState();
      DocCollection coll = clusterState.getCollectionOrNull(collectionName);
      if (coll == null) {
        return;
      }
      Thread.sleep(1000);
    }
    throw new IllegalStateException(
        "Collection " + collectionName + " did not disappear within " + maxWaitSecs + " seconds");
  }
}
