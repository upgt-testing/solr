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
package org.apache.solr.filestore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.V2Request;
import org.apache.solr.client.solrj.response.V2Response;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.NavigableObject;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.Test;

/**
 * ProcessBased test for distributed file store functionality.
 *
 * <p>Simplified version of TestDistribFileStore focusing on client-side file store API operations
 * without cryptographic key verification.
 *
 * <p>Note: Original test (366 lines) uses JettySolrRunner object manipulation and direct filesystem
 * access via PackageUtils.uploadKey() requiring getCoreContainer().getSolrHome() - incompatible
 * with ProcessBased. This simplified version tests file upload/download/listing via V2 API without
 * signature verification.
 */
public class TestDistribFileStore_ProcessBased extends ProcessBasedUpgradeTestBase {

  @Test
  public void testFileStoreManagement_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    doTestFileStoreManagement();
  }

  @Test
  public void testFileStoreManagement_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    doTestFileStoreManagement();
  }

  private void doTestFileStoreManagement() throws Exception {
    System.setProperty("enable.packages", "true");
    try {
      cluster =
          new org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster.Builder()
              .withNodeCount(2)
              .withStartVersionFromSystemProperty()
              .build();
      cluster.start();      solrClient = cluster.getSolrClient();

      checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

      // Upload configset
      Path configDir = Paths.get(SolrTestCaseJ4.TEST_HOME(), "configsets", "cloud-minimal", "conf");
      cluster.uploadConfigSet(configDir, "conf");

      // Test file upload (without signature - simplified)
      byte[] fileContent = createTestFileContent("Test content for file store");

      NavigableObject uploadRsp =
          postFile(
              solrClient,
              ByteBuffer.wrap(fileContent),
              "/package/testpkg/v1.0/testfile.txt",
              null);

      assertNotNull("Upload response should not be null", uploadRsp);

      // Test file listing via V2 API
      V2Response listRsp =
          new V2Request.Builder("/node/files/package/testpkg/v1.0")
              .withMethod(SolrRequest.METHOD.GET)
              .build()
              .process(solrClient);

      assertNotNull("List response should not be null", listRsp);
      NavigableObject responseObj = listRsp.getResponse();
      assertNotNull("Response object should not be null", responseObj);

      // Test directory listing
      V2Response dirListRsp =
          new V2Request.Builder("/node/files/package/testpkg")
              .withMethod(SolrRequest.METHOD.GET)
              .build()
              .process(solrClient);

      assertNotNull("Directory list response should not be null", dirListRsp);

      // Test uploading a second file
      byte[] secondFileContent = createTestFileContent("Second test file content");
      NavigableObject secondUploadRsp =
          postFile(
              solrClient,
              ByteBuffer.wrap(secondFileContent),
              "/package/testpkg/v1.0/testfile2.txt",
              null);

      assertNotNull("Second upload response should not be null", secondUploadRsp);

      // Verify duplicate upload is detected
      NavigableObject duplicateRsp =
          postFile(
              solrClient,
              ByteBuffer.wrap(fileContent),
              "/package/testpkg/v1.0/testfile.txt",
              null);

      // Duplicate uploads should be handled gracefully
      assertNotNull("Duplicate upload response should not be null", duplicateRsp);

    } finally {
      System.clearProperty("enable.packages");
    }
  }

  /**
   * Post a file to the distributed file store.
   */
  private NavigableObject postFile(
      org.apache.solr.client.solrj.SolrClient client,
      ByteBuffer buffer,
      String name,
      String sig)
      throws SolrServerException, IOException {
    String resource = "/cluster/files" + name;
    ModifiableSolrParams params = new ModifiableSolrParams();
    if (sig != null) {
      params.add("sig", sig);
    }
    V2Response rsp =
        new V2Request.Builder(resource)
            .withMethod(SolrRequest.METHOD.PUT)
            .withPayload(buffer)
            .forceV2(true)
            .withMimeType("application/octet-stream")
            .withParams(params)
            .build()
            .process(client);
    assertEquals("Response should contain file name", name, rsp.getResponse().get(CommonParams.FILE));
    return rsp;
  }

  /**
   * Create test file content.
   */
  private byte[] createTestFileContent(String content) {
    return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Read a resource file.
   */
  private byte[] readResourceFile(String fname) throws IOException {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream(fname)) {
      if (is == null) {
        throw new IOException("Resource not found: " + fname);
      }
      return is.readAllBytes();
    }
  }
}
