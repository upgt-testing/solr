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
package org.apache.solr.cloud.upgrade;

/**
 * Constants for common upgrade checkpoint names.
 *
 * <p>These constants are used with {@link ProcessBasedUpgradeTestBase} to specify at which point
 * in a test the rolling upgrade should occur.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * @Test
 * public void testFeature_NO_UPGRADE() throws Exception {
 *   upgradeCheckpoint = SolrUpgradeCheckpoints.NO_UPGRADE;
 *   // ... test logic
 * }
 *
 * @Test
 * public void testFeature_AFTER_CLUSTER_START() throws Exception {
 *   upgradeCheckpoint = SolrUpgradeCheckpoints.AFTER_CLUSTER_START;
 *   // ... same test logic, but upgrade happens after cluster starts
 * }
 * }</pre>
 */
public class SolrUpgradeCheckpoints {

  /** Baseline test - no upgrade performed. */
  public static final String NO_UPGRADE = "NO_UPGRADE";

  /** Upgrade immediately after cluster starts. */
  public static final String AFTER_CLUSTER_START = "AFTER_CLUSTER_START";

  /** Upgrade after creating collection. */
  public static final String AFTER_COLLECTION_CREATE = "AFTER_COLLECTION_CREATE";

  /** Upgrade after indexing documents. */
  public static final String AFTER_INDEX = "AFTER_INDEX";

  /** Upgrade after commit. */
  public static final String AFTER_COMMIT = "AFTER_COMMIT";

  /** Upgrade after query. */
  public static final String AFTER_QUERY = "AFTER_QUERY";

  private SolrUpgradeCheckpoints() {
    // Utility class - prevent instantiation
  }
}
