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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors health of Solr node processes.
 *
 * <p>Performs HTTP-based health checks to verify that Solr nodes are responsive.
 */
public class HealthMonitor {
  private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);

  private static final int CONNECT_TIMEOUT_MS = 5000;
  private static final int READ_TIMEOUT_MS = 5000;

  /**
   * Check if a Solr node is healthy via HTTP ping.
   *
   * <p>Uses /admin/info/system endpoint which is available even without cores loaded.
   *
   * @param baseUrl Base URL of the Solr node (e.g., "http://localhost:8983/solr")
   * @return true if node is healthy
   * @throws IOException if health check fails
   */
  public boolean checkSolrHealth(String baseUrl) throws IOException {
    // Use /admin/info/system instead of /admin/ping because:
    // - /admin/ping requires a core to exist
    // - /admin/info/system works on empty Solr nodes
    String healthUrl = baseUrl + "/admin/info/system";

    try {
      URL url = new URL(healthUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setInstanceFollowRedirects(true);

      int responseCode = conn.getResponseCode();
      conn.disconnect();

      boolean healthy = (responseCode == 200);
      log.debug("Health check for {}: {} (response: {})", baseUrl, healthy, responseCode);
      return healthy;

    } catch (IOException e) {
      log.debug("Health check failed for {}: {}", baseUrl, e.getMessage());
      throw e;
    }
  }

  /**
   * Check if a URL is reachable.
   *
   * @param urlString URL to check
   * @return true if reachable
   */
  public boolean isReachable(String urlString) {
    try {
      return checkSolrHealth(urlString);
    } catch (IOException e) {
      return false;
    }
  }
}
