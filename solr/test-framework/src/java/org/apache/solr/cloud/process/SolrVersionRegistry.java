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
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for managing multiple Solr distributions.
 *
 * <p>Allows registering and retrieving different Solr versions for use in multi-version testing
 * and upgrade scenarios.
 */
public class SolrVersionRegistry {
  private static final Logger log = LoggerFactory.getLogger(SolrVersionRegistry.class);

  private final Map<String, SolrDistribution> distributions;

  public SolrVersionRegistry() {
    this.distributions = new HashMap<>();
  }

  /**
   * Register a Solr distribution.
   *
   * @param version Version identifier (can be any string, e.g., "9.8.0" or "start")
   * @param solrHome Path to Solr installation directory
   * @return The registered SolrDistribution
   * @throws IllegalArgumentException if solrHome is invalid
   */
  public SolrDistribution register(String version, String solrHome) {
    return register(version, new File(solrHome));
  }

  /**
   * Register a Solr distribution.
   *
   * @param version Version identifier
   * @param solrHome Solr installation directory
   * @return The registered SolrDistribution
   * @throws IllegalArgumentException if solrHome is invalid
   */
  public SolrDistribution register(String version, File solrHome) {
    if (distributions.containsKey(version)) {
      log.warn("Overwriting existing distribution for version {}", version);
    }

    SolrDistribution distribution = new SolrDistribution(version, solrHome);
    distributions.put(version, distribution);

    log.info("Registered Solr distribution: {}", distribution);
    return distribution;
  }

  /**
   * Get a registered Solr distribution.
   *
   * @param version Version identifier
   * @return SolrDistribution for the specified version
   * @throws IllegalArgumentException if version not found
   */
  public SolrDistribution get(String version) {
    SolrDistribution distribution = distributions.get(version);
    if (distribution == null) {
      throw new IllegalArgumentException(
          "No Solr distribution registered for version: "
              + version
              + ". Available versions: "
              + distributions.keySet());
    }
    return distribution;
  }

  /**
   * Check if a version is registered.
   *
   * @param version Version identifier
   * @return true if version is registered
   */
  public boolean hasVersion(String version) {
    return distributions.containsKey(version);
  }

  /**
   * Get all registered version identifiers.
   *
   * @return Map of version identifiers to distributions
   */
  public Map<String, SolrDistribution> getAll() {
    return new HashMap<>(distributions);
  }

  /**
   * Clear all registered distributions.
   */
  public void clear() {
    distributions.clear();
  }

  /**
   * Get the number of registered distributions.
   *
   * @return Number of distributions
   */
  public int size() {
    return distributions.size();
  }

  /**
   * Register a Solr distribution (alias for register).
   *
   * @param alias Alias for the distribution (e.g., "start", "upgrade")
   * @param distribution SolrDistribution to register
   */
  public void registerDistribution(String alias, SolrDistribution distribution) {
    if (distributions.containsKey(alias)) {
      log.warn("Overwriting existing distribution for alias {}", alias);
    }
    distributions.put(alias, distribution);
    log.info("Registered Solr distribution with alias '{}': {}", alias, distribution);
  }

  /**
   * Get a registered Solr distribution (alias for get).
   *
   * @param alias Alias identifier (e.g., "start", "upgrade")
   * @return SolrDistribution for the specified alias, or null if not found
   */
  public SolrDistribution getDistribution(String alias) {
    return distributions.get(alias);
  }

  @Override
  public String toString() {
    return "SolrVersionRegistry{" + "versions=" + distributions.keySet() + '}';
  }
}
