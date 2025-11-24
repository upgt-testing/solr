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

import static com.carrotsearch.randomizedtesting.RandomizedTest.getRandom;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomInt;
import static com.carrotsearch.randomizedtesting.RandomizedTest.randomIntBetween;
import static org.apache.solr.SolrTestCaseJ4.TEST_PATH;
import static org.apache.solr.SolrTestCaseJ4.params;
import static org.apache.solr.SolrTestCaseJ4.sdoc;
import static org.apache.solr.cloud.TrollingIndexReaderFactory.CheckMethodName;
import static org.apache.solr.cloud.TrollingIndexReaderFactory.Trap;
import static org.apache.solr.cloud.TrollingIndexReaderFactory.catchClass;
import static org.apache.solr.cloud.TrollingIndexReaderFactory.catchCount;
import static org.apache.solr.cloud.TrollingIndexReaderFactory.catchTrace;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.Repeat;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.process.ProcessBasedMiniSolrCloudCluster;
import org.apache.solr.cloud.upgrade.ProcessBasedUpgradeTestBase;
import org.apache.solr.cloud.upgrade.SolrUpgradeCheckpoints;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.handler.component.FacetComponent;
import org.apache.solr.handler.component.QueryComponent;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.facet.FacetModule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProcessBased upgrade test for {@link org.apache.lucene.index.ExitableDirectoryReader}
 *
 * <p>Note: This is a partial transformation that removes internal metrics validation but keeps all
 * core timeout testing functionality.
 */
@LuceneTestCase.SuppressSysoutChecks(bugUrl = "Solr logs expected output during startup")
public class CloudExitableDirectoryReaderTest_ProcessBased extends ProcessBasedUpgradeTestBase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final int NUM_DOCS_PER_TYPE = 20;
  private static final String sleep = "2";
  private static final String COLLECTION = "exitable";
  private static final int DEFAULT_TIMEOUT = 30; // seconds

  /** Client used for test requests (non-LBSolrClient to avoid client-side timeout logic) */
  private SolrClient client;

  private void setupClusterAndCollection() throws Exception {
    // Build and start cluster
    cluster =
        new ProcessBasedMiniSolrCloudCluster.Builder()
            .withNodeCount(3) // 3 nodes for distributed testing
            .withStartVersionFromSystemProperty()
            .withUpgradeVersionFromSystemProperty()
            .build();

    cluster.start();
    solrClient = cluster.getSolrClient();

    checkpoint(SolrUpgradeCheckpoints.AFTER_CLUSTER_START);

    // Upload configset
    Path configPath = Paths.get(TEST_PATH().toString(), "configsets", "exitable-directory", "conf");
    cluster.uploadConfigSet(configPath, "conf");

    // Create collection
    CollectionAdminRequest.createCollection(COLLECTION, "conf", 2, 1)
        .processAndWait(solrClient, DEFAULT_TIMEOUT);

    // Note: processAndWait ensures collection is created and active
    // Additional ZkStateReader check not available in ProcessBased cluster

    checkpoint(SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE);

    // Get a simple client for requests (not LBSolrClient)
    client = solrClient;

    // Index documents
    indexDocs();

    checkpoint(SolrUpgradeCheckpoints.AFTER_INDEX);
  }

  private void indexDocs() throws Exception {
    int counter;
    counter = 1;
    UpdateRequest req = new UpdateRequest();

    for (; (counter % NUM_DOCS_PER_TYPE) != 0; counter++) {
      final String v = "a" + counter;
      req.add(
          sdoc(
              "id",
              Integer.toString(counter),
              "name",
              v,
              "name_dv",
              v,
              "name_dvs",
              v,
              "name_dvs",
              v + "1",
              "num",
              "" + counter));
    }

    counter++;
    for (; (counter % NUM_DOCS_PER_TYPE) != 0; counter++) {
      final String v = "b" + counter;
      req.add(
          sdoc(
              "id",
              Integer.toString(counter),
              "name",
              v,
              "name_dv",
              v,
              "name_dvs",
              v,
              "name_dvs",
              v + "1",
              "num",
              "" + counter));
    }

    counter++;
    for (; counter % NUM_DOCS_PER_TYPE != 0; counter++) {
      final String v = "dummy term doc" + counter;
      req.add(
          sdoc(
              "id",
              Integer.toString(counter),
              "name",
              v,
              "name_dv",
              v,
              "name_dvs",
              v,
              "name_dvs",
              v + "1",
              "num",
              "" + counter));
    }

    req.commit(client, COLLECTION);
  }

  @Test
  public void testBasicTimeout_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    setupClusterAndCollection();
    runBasicTimeoutTests();
  }

  @Test
  public void testBasicTimeout_AFTER_CLUSTER_START() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
    setupClusterAndCollection();
    runBasicTimeoutTests();
  }

  @Test
  public void testBasicTimeout_AFTER_COLLECTION_CREATE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_COLLECTION_CREATE;
    setupClusterAndCollection();
    runBasicTimeoutTests();
  }

  @Test
  public void testBasicTimeout_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_INDEX;
    setupClusterAndCollection();
    runBasicTimeoutTests();
  }

  private void runBasicTimeoutTests() throws Exception {
    assertPartialResults(params("q", "name:a*", "timeAllowed", "1", "sleep", sleep));

    /*
    query rewriting for NUM_DOCS_PER_TYPE terms should take less
    time than this. Keeping it at 5 because the delaying search component delays all requests
    by at 1 second.
     */
    int fiveSeconds = 5000;

    Integer timeAllowed = TestUtil.nextInt(getRandom(), fiveSeconds, Integer.MAX_VALUE);
    assertSuccess(params("q", "name:a*", "timeAllowed", timeAllowed.toString()));

    assertPartialResults(params("q", "name:a*", "timeAllowed", "1", "sleep", sleep));

    timeAllowed = TestUtil.nextInt(getRandom(), fiveSeconds, Integer.MAX_VALUE);
    assertSuccess(params("q", "name:b*", "timeAllowed", timeAllowed.toString()));

    // negative timeAllowed should disable timeouts
    timeAllowed = TestUtil.nextInt(getRandom(), Integer.MIN_VALUE, -1);
    assertSuccess(params("q", "name:b*", "timeAllowed", timeAllowed.toString()));

    assertSuccess(params("q", "name:b*")); // no time limitation
  }

  @Test
  public void testClearbox_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    setupClusterAndCollection();
    runClearboxTests();
  }

  @Test
  public void testClearbox_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_INDEX;
    setupClusterAndCollection();
    runClearboxTests();
  }

  private void runClearboxTests() throws Exception {
    try (Trap catchIds = catchTrace(new CheckMethodName("doProcessSearchByIds"), () -> {})) {
      assertPartialResults(
          params("q", "{!cache=false}name:a*", "sort", "query($q,1) asc"),
          () -> assertTrue(catchIds.hasCaught()));
    } catch (AssertionError ae) {
      Trap.dumpLastStackTraces(log);
      throw ae;
    }

    // the point is to catch sort_values (fsv) timeout, between search and facet
    // I haven't found a way to encourage fsv to read index
    try (Trap catchFSV = catchTrace(new CheckMethodName("doFieldSortValues"), () -> {})) {
      assertPartialResults(
          params("q", "{!cache=false}name:a*", "sort", "query($q,1) asc"),
          () -> assertTrue(catchFSV.hasCaught()));
    } catch (AssertionError ae) {
      Trap.dumpLastStackTraces(log);
      throw ae;
    }

    try (Trap catchClass = catchClass(QueryComponent.class.getSimpleName(), () -> {})) {
      assertPartialResults(
          params("q", "{!cache=false}name:a*"), () -> assertTrue(catchClass.hasCaught()));
    } catch (AssertionError ae) {
      Trap.dumpLastStackTraces(log);
      throw ae;
    }

    try (Trap catchClass = catchClass(FacetComponent.class.getSimpleName())) {
      assertPartialResults(
          params(
              "q",
              "{!cache=false}name:a*",
              "facet",
              "true",
              "facet.method",
              "enum",
              "facet.field",
              "id"),
          () -> assertTrue(catchClass.hasCaught()));
    } catch (AssertionError ae) {
      Trap.dumpLastStackTraces(log);
      throw ae;
    }

    try (Trap catchClass = catchClass(FacetModule.class.getSimpleName())) {
      assertPartialResults(
          params(
              "q",
              "{!cache=false}name:a*",
              "json.facet",
              "{ ids: {" + " type: range, field : num, start : 0, end : 100, gap : 10 }}"),
          () -> assertTrue(catchClass.hasCaught()));
    } catch (AssertionError ae) {
      Trap.dumpLastStackTraces(log);
      throw ae;
    }
  }

  @Test
  @Repeat(iterations = 5)
  public void testCreepThenBite_NO_UPGRADE() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
    setupClusterAndCollection();
    runCreepThenBiteTest();
  }

  @Test
  @Repeat(iterations = 5)
  public void testCreepThenBite_AFTER_INDEX() throws Exception {
    upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_INDEX;
    setupClusterAndCollection();
    runCreepThenBiteTest();
  }

  private void runCreepThenBiteTest() throws Exception {
    int creep = 100;
    ModifiableSolrParams params = params("q", "{!cache=false}name:a*");
    SolrParams cases[] =
        new SolrParams[] {
          params("sort", "query($q,1) asc"),
          params("rows", "0", "facet", "true", "facet.method", "enum", "facet.field", "name"),
          params(
              "rows",
              "0",
              "json.facet",
              "{ ids: { type: range, field : num, start : 1, end : 99, gap : 9 }}"),
          params("q", "*:*", "rows", "0", "json.facet", "{ ids: { type: field, field : num}}"),
          params("q", "*:*", "rows", "0", "json.facet", "{ ids: { type: field, field : name_dv}}"),
          params("q", "*:*", "rows", "0", "json.facet", "{ ids: { type: field, field : name_dvs}}")
        }; // add more cases here

    params.add(cases[randomInt(cases.length - 1)]);
    for (; ; creep = (int) (creep * 1.5)) {
      try (Trap catchClass = catchCount(creep)) {

        params.set("boundary", creep);
        QueryResponse rsp = client.query(COLLECTION, params);
        assertEquals("" + rsp, 0, rsp.getStatus());
        if (!isPartial(rsp)) {
          assertFalse(catchClass.hasCaught());
          break;
        }
        assertTrue(catchClass.hasCaught());
      } catch (AssertionError ae) {
        Trap.dumpLastStackTraces(log);
        throw ae;
      }
    }
    int numBites = LuceneTestCase.atLeast(getRandom(), 100);
    for (int bite = 0; bite < numBites; bite++) {
      int boundary = randomInt(creep - 1);
      boolean omitHeader = RandomizedTest.randomBoolean();
      try (Trap catchCount = catchCount(boundary)) {
        params.set("omitHeader", "" + omitHeader);
        params.set("boundary", boundary);
        QueryResponse rsp = client.query(COLLECTION, params);
        assertEquals("" + rsp, 0, rsp.getStatus());
        // without responseHeader, whether the response is partial or not can't be known
        // omitHeader=true used in request to ensure that no NPE exceptions are thrown
        if (omitHeader) {
          continue;
        }
        assertEquals(
            "" + creep + " ticks were successful; trying " + boundary + " yields " + rsp,
            isPartial(rsp),
            catchCount.hasCaught());
      } catch (AssertionError ae) {
        Trap.dumpLastStackTraces(log);
        throw ae;
      }
    }
  }

  private boolean isPartial(QueryResponse rsp) {
    return Boolean.TRUE.equals(
        rsp.getHeader().getBooleanArg(SolrQueryResponse.RESPONSE_HEADER_PARTIAL_RESULTS_KEY));
  }

  /** execute a request, verify that we get partial results */
  private void assertPartialResults(ModifiableSolrParams p) throws Exception {
    assertPartialResults(p, () -> {});
  }

  private void assertPartialResults(ModifiableSolrParams p, Runnable postRequestCheck)
      throws Exception {
    QueryResponse rsp = client.query(COLLECTION, p);
    postRequestCheck.run();
    assertEquals(0, rsp.getStatus());
    assertEquals(
        SolrQueryResponse.RESPONSE_HEADER_PARTIAL_RESULTS_KEY + " were expected at " + rsp,
        Boolean.TRUE,
        rsp.getHeader().getBooleanArg(SolrQueryResponse.RESPONSE_HEADER_PARTIAL_RESULTS_KEY));
  }

  private void assertSuccess(ModifiableSolrParams p) throws Exception {
    QueryResponse rsp = client.query(COLLECTION, p);
    assertEquals(0, rsp.getStatus());
    assertEquals("Wrong #docs in response", (long) (NUM_DOCS_PER_TYPE - 1), rsp.getResults().getNumFound());
    assertNotEquals(
        SolrQueryResponse.RESPONSE_HEADER_PARTIAL_RESULTS_KEY + " weren't expected " + rsp,
        Boolean.TRUE,
        rsp.getHeader().getBooleanArg(SolrQueryResponse.RESPONSE_HEADER_PARTIAL_RESULTS_KEY));
  }
}
