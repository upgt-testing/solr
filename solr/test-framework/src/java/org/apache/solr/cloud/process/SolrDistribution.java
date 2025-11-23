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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a Solr distribution installation.
 *
 * <p>This simplified version validates that a Solr distribution is properly installed by checking
 * for the bin/solr script. Used with SolrProcessManager which launches real Solr processes using
 * the bin/solr script.
 */
public class SolrDistribution {
  private static final Logger log = LoggerFactory.getLogger(SolrDistribution.class);

  private final String version;
  private final File solrHome;

  /**
   * Create a SolrDistribution from a Solr installation directory.
   *
   * @param version Version identifier (e.g., "9.8.0")
   * @param solrHome Path to Solr installation directory
   * @throws IllegalArgumentException if solrHome doesn't exist or is invalid
   */
  public SolrDistribution(String version, File solrHome) {
    if (!solrHome.exists() || !solrHome.isDirectory()) {
      throw new IllegalArgumentException("Invalid Solr home directory: " + solrHome);
    }

    this.version = version;
    this.solrHome = solrHome;

    validateDistribution();

    log.info("Validated Solr distribution {} at {}", version, solrHome.getAbsolutePath());
  }

  /** Validate that the distribution has bin/solr script. */
  private void validateDistribution() {
    File solrScript = new File(solrHome, "bin/solr");

    if (!solrScript.exists()) {
      throw new IllegalStateException(
          "Invalid Solr distribution at "
              + solrHome
              + ": bin/solr script not found. "
              + "Expected path: "
              + solrScript.getAbsolutePath());
    }

    if (!solrScript.canExecute()) {
      log.warn(
          "bin/solr script at {} is not executable, attempting to set executable bit",
          solrScript);
      if (!solrScript.setExecutable(true)) {
        throw new IllegalStateException(
            "Invalid Solr distribution at "
                + solrHome
                + ": bin/solr script exists but is not executable and cannot be made executable");
      }
    }

    log.debug("Validated bin/solr script at {}", solrScript.getAbsolutePath());
  }

  public String getVersion() {
    return version;
  }

  public File getSolrHome() {
    return solrHome;
  }

  /**
   * Get the absolute path to solrHome.
   *
   * @return Absolute path string
   */
  public String getSolrHomePath() {
    return solrHome.getAbsolutePath();
  }

  @Override
  public String toString() {
    return "SolrDistribution{" + "version='" + version + '\'' + ", solrHome=" + solrHome + '}';
  }
}
