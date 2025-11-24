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

import static org.apache.lucene.tests.util.LuceneTestCase.expectThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.RequestStatusState;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.junit.Test;

/**
 * ProcessBased integration test verifying particular errors are reported correctly by the
 * Collection-level backup/restore APIs.
 *
 * <p>This test uses ProcessBasedMiniSolrCloudCluster which provides a default
 * LocalFileSystemRepository (no custom solr.xml configuration needed).
 */
public class BackupRestoreApiErrorConditionsTest_ProcessBased
    extends ProcessBasedUpgradeTestBase {

  private static final int NUM_SHARDS = 1;
  private static final int NUM_REPLICAS = 1;
  private static final String COLLECTION_NAME = "initial_collection";
  private static final String BACKUP_NAME = "backup_name";
  private static final long ASYNC_COMMAND_WAIT_PERIOD_MILLIS = 10 * 1000;

  /**
   * Tests that backup operations report proper error messages when an unknown backup repository is
   * requested.
   */
  @Test
  public void testBackupOperationsReportErrorWhenUnknownBackupRepositoryRequested_NO_UPGRADE()
      throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestBackupOperationsReportErrorWhenUnknownBackupRepositoryRequested();
  }

  @Test
  public void testBackupOperationsReportErrorWhenUnknownBackupRepositoryRequested_AFTER_CLUSTER_START()
      throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestBackupOperationsReportErrorWhenUnknownBackupRepositoryRequested();
  }

  private void doTestBackupOperationsReportErrorWhenUnknownBackupRepositoryRequested()
      throws Exception {
    File backupLocation = Files.createTempDirectory("backup-test").toFile();
    backupLocation.deleteOnExit();

    System.setProperty("solr.allowPaths", "*");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(NUM_SHARDS)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();
      cluster.waitForAllNodes(30);
      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf1");

      // Create collection
      final RequestStatusState createState =
          CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", NUM_SHARDS, NUM_REPLICAS)
              .processAndWait(solrClient, ASYNC_COMMAND_WAIT_PERIOD_MILLIS);
      assertEquals(RequestStatusState.COMPLETED, createState);

      // Check message for create-backup
      Exception e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
                    .setRepositoryName("some-nonexistent-repo-name")
                    .setLocation(backupLocation.getAbsolutePath())
                    .process(solrClient);
              });
      assertTrue(
          "Expected error message about repository not found, got: " + e.getMessage(),
          e.getMessage()
              .contains("Could not find a backup repository with name some-nonexistent-repo-name"));

      // Check message for list-backup
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.listBackup(BACKUP_NAME)
                    .setBackupLocation(backupLocation.getAbsolutePath())
                    .setBackupRepository("some-nonexistent-repo-name")
                    .process(solrClient);
              });
      assertTrue(
          "Expected error message about repository not found, got: " + e.getMessage(),
          e.getMessage()
              .contains("Could not find a backup repository with name some-nonexistent-repo-name"));

      // Check message for delete-backup
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.deleteBackupById(BACKUP_NAME, 1)
                    .setLocation(backupLocation.getAbsolutePath())
                    .setRepositoryName("some-nonexistent-repo-name")
                    .process(solrClient);
              });
      assertTrue(
          "Expected error message about repository not found, got: " + e.getMessage(),
          e.getMessage()
              .contains("Could not find a backup repository with name some-nonexistent-repo-name"));

      // Check message for restore-backup
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.restoreCollection(
                        COLLECTION_NAME + "_restored", BACKUP_NAME)
                    .setLocation(backupLocation.getAbsolutePath())
                    .setRepositoryName("some-nonexistent-repo-name")
                    .process(solrClient);
              });
      assertTrue(
          "Expected error message about repository not found, got: " + e.getMessage(),
          e.getMessage()
              .contains("Could not find a backup repository with name some-nonexistent-repo-name"));
    } finally {
      System.clearProperty("solr.allowPaths");
    }
  }

  /**
   * Tests that backup operations report proper error messages when a nonexistent location is
   * provided.
   */
  @Test
  public void testBackupOperationsReportErrorWhenNonexistentLocationProvided_NO_UPGRADE()
      throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestBackupOperationsReportErrorWhenNonexistentLocationProvided();
  }

  @Test
  public void testBackupOperationsReportErrorWhenNonexistentLocationProvided_AFTER_CLUSTER_START()
      throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestBackupOperationsReportErrorWhenNonexistentLocationProvided();
  }

  private void doTestBackupOperationsReportErrorWhenNonexistentLocationProvided() throws Exception {
    File validBackupLocation = Files.createTempDirectory("backup-test").toFile();
    validBackupLocation.deleteOnExit();
    String invalidLocation = validBackupLocation.getAbsolutePath() + File.separator + "someNonexistentLocation";

    System.setProperty("solr.allowPaths", "*");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(NUM_SHARDS)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();
      cluster.waitForAllNodes(30);
      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf1");

      // Create collection
      final RequestStatusState createState =
          CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", NUM_SHARDS, NUM_REPLICAS)
              .processAndWait(solrClient, ASYNC_COMMAND_WAIT_PERIOD_MILLIS);
      assertEquals(RequestStatusState.COMPLETED, createState);

      // Check message for create-backup (using default repository)
      Exception e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
                    .setLocation(invalidLocation)
                    .process(solrClient);
              });
      assertTrue("Expected error about location, got: " + e.getMessage(),
          e.getMessage().contains("specified location"));
      assertTrue("Expected error about location not existing, got: " + e.getMessage(),
          e.getMessage().contains("does not exist"));

      // Check message for list-backup (using default repository)
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.listBackup(BACKUP_NAME)
                    .setBackupLocation(invalidLocation)
                    .process(solrClient);
              });
      assertTrue("Expected error about location, got: " + e.getMessage(),
          e.getMessage().contains("specified location"));
      assertTrue("Expected error about location not existing, got: " + e.getMessage(),
          e.getMessage().contains("does not exist"));

      // Check message for delete-backup (using default repository)
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.deleteBackupById(BACKUP_NAME, 1)
                    .setLocation(invalidLocation)
                    .process(solrClient);
              });
      assertTrue("Expected error about location, got: " + e.getMessage(),
          e.getMessage().contains("specified location"));
      assertTrue("Expected error about location not existing, got: " + e.getMessage(),
          e.getMessage().contains("does not exist"));

      // Check message for restore-backup (using default repository)
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.restoreCollection(
                        COLLECTION_NAME + "_restored", BACKUP_NAME)
                    .setLocation(invalidLocation)
                    .process(solrClient);
              });
      assertTrue("Expected error about location, got: " + e.getMessage(),
          e.getMessage().contains("specified location"));
      assertTrue("Expected error about location not existing, got: " + e.getMessage(),
          e.getMessage().contains("does not exist"));
    } finally {
      System.clearProperty("solr.allowPaths");
    }
  }

  /**
   * Tests that list and delete operations fail properly on old (non-incremental) backup
   * locations.
   */
  @Test
  public void testListAndDeleteFailOnOldBackupLocations_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestListAndDeleteFailOnOldBackupLocations();
  }

  @Test
  public void testListAndDeleteFailOnOldBackupLocations_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestListAndDeleteFailOnOldBackupLocations();
  }

  @Test
  public void testListAndDeleteFailOnOldBackupLocations_AFTER_BACKUP() throws Exception {
    upgradeCheckpoint = "AFTER_BACKUP";
    doTestListAndDeleteFailOnOldBackupLocations();
  }

  private void doTestListAndDeleteFailOnOldBackupLocations() throws Exception {
    File nonIncrementalBackupLocation = Files.createTempDirectory("backup-test-noninc").toFile();
    nonIncrementalBackupLocation.deleteOnExit();

    System.setProperty("solr.allowPaths", "*");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(NUM_SHARDS)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();
      cluster.waitForAllNodes(30);
      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf1");

      // Create collection
      final RequestStatusState createState =
          CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", NUM_SHARDS, NUM_REPLICAS)
              .processAndWait(solrClient, ASYNC_COMMAND_WAIT_PERIOD_MILLIS);
      assertEquals(RequestStatusState.COMPLETED, createState);

      // Create a non-incremental backup (using default repository)
      final RequestStatusState backupState =
          CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
              .setLocation(nonIncrementalBackupLocation.getAbsolutePath())
              .setIncremental(false)
              .processAndWait(solrClient, ASYNC_COMMAND_WAIT_PERIOD_MILLIS);
      assertEquals(RequestStatusState.COMPLETED, backupState);

      checkpoint("AFTER_BACKUP");

      // Check message for list-backup
      Exception e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.listBackup(BACKUP_NAME)
                    .setBackupLocation(nonIncrementalBackupLocation.getAbsolutePath())
                    .process(solrClient);
              });
      assertTrue("Expected error about backup name, got: " + e.getMessage(),
          e.getMessage().contains("The backup name [backup_name] at location"));
      assertTrue("Expected error about non-incremental backup, got: " + e.getMessage(),
          e.getMessage()
              .contains(
                  "holds a non-incremental (legacy) backup, but backup-listing is only supported on incremental backups"));

      // Check message for delete-backup
      e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.deleteBackupById(BACKUP_NAME, 1)
                    .setLocation(nonIncrementalBackupLocation.getAbsolutePath())
                    .process(solrClient);
              });
      assertTrue("Expected error about backup name, got: " + e.getMessage(),
          e.getMessage().contains("The backup name [backup_name] at location"));
      assertTrue("Expected error about non-incremental backup, got: " + e.getMessage(),
          e.getMessage()
              .contains(
                  "holds a non-incremental (legacy) backup, but backup-deletion is only supported on incremental backups"));
    } finally {
      System.clearProperty("solr.allowPaths");
    }
  }

  /** Tests that delete operation fails properly when a nonexistent backup ID is provided. */
  @Test
  public void testDeleteFailsOnNonexistentBackupId_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestDeleteFailsOnNonexistentBackupId();
  }

  @Test
  public void testDeleteFailsOnNonexistentBackupId_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestDeleteFailsOnNonexistentBackupId();
  }

  @Test
  public void testDeleteFailsOnNonexistentBackupId_AFTER_BACKUP() throws Exception {
    upgradeCheckpoint = "AFTER_BACKUP";
    doTestDeleteFailsOnNonexistentBackupId();
  }

  private void doTestDeleteFailsOnNonexistentBackupId() throws Exception {
    File validBackupLocation = Files.createTempDirectory("backup-test-validinc").toFile();
    validBackupLocation.deleteOnExit();

    System.setProperty("solr.allowPaths", "*");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(NUM_SHARDS)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();
      cluster.waitForAllNodes(30);
      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf1");

      // Create collection
      final RequestStatusState createState =
          CollectionAdminRequest.createCollection(COLLECTION_NAME, "conf1", NUM_SHARDS, NUM_REPLICAS)
              .processAndWait(solrClient, ASYNC_COMMAND_WAIT_PERIOD_MILLIS);
      assertEquals(RequestStatusState.COMPLETED, createState);

      // Create an incremental backup (using default repository)
      final RequestStatusState backupState =
          CollectionAdminRequest.backupCollection(COLLECTION_NAME, BACKUP_NAME)
              .setLocation(validBackupLocation.getAbsolutePath())
              .processAndWait(solrClient, ASYNC_COMMAND_WAIT_PERIOD_MILLIS);
      assertEquals(RequestStatusState.COMPLETED, backupState);

      checkpoint("AFTER_BACKUP");

      // Try to delete a nonexistent backup ID
      Exception e =
          expectThrows(
              Exception.class,
              () -> {
                CollectionAdminRequest.deleteBackupById(BACKUP_NAME, 123)
                    .setLocation(validBackupLocation.getAbsolutePath())
                    .process(solrClient);
              });
      assertTrue("Expected error about backup ID not found, got: " + e.getMessage(),
          e.getMessage().contains("Backup ID [123] not found; cannot be deleted"));
    } finally {
      System.clearProperty("solr.allowPaths");
    }
  }
}
