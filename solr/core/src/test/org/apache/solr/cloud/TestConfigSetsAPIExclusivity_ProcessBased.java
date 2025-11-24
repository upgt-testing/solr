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

import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest.Create;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest.Delete;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased upgrade test for ConfigSets API exclusivity.
 *
 * <p>Tests the exclusivity of the ConfigSets API by submitting concurrent API requests and
 * checking that the responses indicate the requests are handled sequentially for the same ConfigSet
 * and base ConfigSet.
 *
 * <p>Transformed from {@link TestConfigSetsAPIExclusivity} to support checkpoint-based upgrade
 * testing.
 */
public class TestConfigSetsAPIExclusivity_ProcessBased extends ProcessBasedUpgradeTestBase {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String GRANDBASE_CONFIGSET_NAME = "grandBaseConfigSet1";
  private static final String BASE_CONFIGSET_NAME = "baseConfigSet1";
  private static final String CONFIGSET_NAME = "configSet1";
  private static final String AFTER_GRANDBASE_UPLOAD = "AFTER_GRANDBASE_UPLOAD";

  @Test
  public void testAPIExclusivity_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    runAPIExclusivityTest();
  }

  @Test
  public void testAPIExclusivity_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    runAPIExclusivityTest();
  }

  @Test
  public void testAPIExclusivity_AFTER_GRANDBASE_UPLOAD() throws Exception {
    upgradeCheckpoint = AFTER_GRANDBASE_UPLOAD;
    runAPIExclusivityTest();
  }

  private void runAPIExclusivityTest() throws Exception {
    int trials = 20;

    // Initialize cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(1)
            .withStartVersionFromSystemProperty()
            .withUpgradeVersionFromSystemProperty()
            .build();
    cluster.start();
    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Setup grandbase configset
    setupBaseConfigSet(GRANDBASE_CONFIGSET_NAME);

    checkpoint(AFTER_GRANDBASE_UPLOAD);

    // Run concurrent create/delete operations
    CreateThread createBaseThread =
        new CreateThread(cluster, BASE_CONFIGSET_NAME, GRANDBASE_CONFIGSET_NAME, trials);
    CreateThread createThread =
        new CreateThread(cluster, CONFIGSET_NAME, BASE_CONFIGSET_NAME, trials);
    DeleteThread deleteBaseThread = new DeleteThread(cluster, BASE_CONFIGSET_NAME, trials);
    DeleteThread deleteThread = new DeleteThread(cluster, CONFIGSET_NAME, trials);
    List<ConfigSetsAPIThread> threads =
        Arrays.asList(createBaseThread, createThread, deleteBaseThread, deleteThread);

    for (ConfigSetsAPIThread thread : threads) {
      thread.start();
    }
    for (ConfigSetsAPIThread thread : threads) {
      thread.join();
    }
    List<Exception> exceptions = new ArrayList<>();
    for (ConfigSetsAPIThread thread : threads) {
      exceptions.addAll(thread.getUnexpectedExceptions());
    }
    assertEquals(
        "Unexpected exception: " + getFirstExceptionOrNull(exceptions), 0, exceptions.size());
  }

  private void setupBaseConfigSet(String baseConfigSetName) throws Exception {
    Path configsetPath = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "configset-2", "conf");
    cluster.uploadConfigSet(configsetPath, baseConfigSetName);
    // Make configset untrusted
    cluster
        .getZkClient()
        .setData(
            "/configs/" + baseConfigSetName,
            "{\"trusted\": false}".getBytes(StandardCharsets.UTF_8),
            true);
  }

  private Exception getFirstExceptionOrNull(List<Exception> list) {
    return list.size() == 0 ? null : list.get(0);
  }

  private abstract static class ConfigSetsAPIThread extends Thread {
    private ProcessBasedMiniSolrCloudCluster cluster;
    private int trials;
    private List<Exception> unexpectedExceptions = new ArrayList<>();
    private List<String> allowedExceptions =
        Arrays.asList(
            new String[] {
              "ConfigSet already exists",
              "ConfigSet does not exist to delete",
              "Base ConfigSet does not exist"
            });

    public ConfigSetsAPIThread(ProcessBasedMiniSolrCloudCluster cluster, int trials) {
      this.cluster = cluster;
      this.trials = trials;
    }

    public abstract ConfigSetAdminRequest<?, ?> createRequest();

    @Override
    public void run() {
      final String baseUrl = cluster.getJettySolrRunnerBaseUrl(0);
      try (SolrClient solrClient = new HttpSolrClient.Builder(baseUrl).build()) {
        ConfigSetAdminRequest<?, ?> request = createRequest();

        for (int i = 0; i < trials; ++i) {
          try {
            request.process(solrClient);
          } catch (Exception e) {
            verifyException(e);
          }
        }
      } catch (Exception e) {
        log.error("Error closing client", e);
      }
    }

    private void verifyException(Exception e) {
      for (String ex : allowedExceptions) {
        if (e.getMessage().contains(ex)) {
          return;
        }
      }
      unexpectedExceptions.add(e);
    }

    public List<Exception> getUnexpectedExceptions() {
      return unexpectedExceptions;
    }
  }

  private static class CreateThread extends ConfigSetsAPIThread {
    private String configSet;
    private String baseConfigSet;

    public CreateThread(
        ProcessBasedMiniSolrCloudCluster cluster, String configSet, String baseConfigSet, int trials) {
      super(cluster, trials);
      this.configSet = configSet;
      this.baseConfigSet = baseConfigSet;
    }

    @Override
    public Create createRequest() {
      Create create = new Create();
      create.setBaseConfigSetName(baseConfigSet).setConfigSetName(configSet);
      return create;
    }
  }

  private static class DeleteThread extends ConfigSetsAPIThread {
    private String configSet;

    public DeleteThread(ProcessBasedMiniSolrCloudCluster cluster, String configSet, int trials) {
      super(cluster, trials);
      this.configSet = configSet;
    }

    @Override
    public Delete createRequest() {
      Delete delete = new Delete();
      delete.setConfigSetName(configSet);
      return delete;
    }
  }
}
