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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for managing Solr node processes.
 *
 * <p>Provides common functionality for:
 *
 * <ul>
 *   <li>Starting and stopping node processes
 *   <li>Monitoring process health
 *   <li>Managing process lifecycle
 *   <li>Building classpaths
 * </ul>
 */
public abstract class ProcessNodeManager {
  private static final Logger log = LoggerFactory.getLogger(ProcessNodeManager.class);

  protected Process process;
  protected final Properties nodeProps;
  protected final SolrDistribution distribution;
  protected final File workDir;
  protected final String nodeId;
  protected final int nodeIndex;
  protected final int jettyPort;
  protected final String zkHost;

  /**
   * Create a ProcessNodeManager.
   *
   * @param nodeId Node identifier (e.g., "jetty0")
   * @param nodeIndex Node index number
   * @param distribution Solr distribution to use
   * @param workDir Work directory for the node
   * @param jettyPort Jetty HTTP port
   * @param zkHost ZooKeeper connection string
   * @param nodeProps Additional node properties
   */
  protected ProcessNodeManager(
      String nodeId,
      int nodeIndex,
      SolrDistribution distribution,
      File workDir,
      int jettyPort,
      String zkHost,
      Properties nodeProps) {
    this.nodeId = nodeId;
    this.nodeIndex = nodeIndex;
    this.distribution = distribution;
    this.workDir = workDir;
    this.jettyPort = jettyPort;
    this.zkHost = zkHost;
    this.nodeProps = nodeProps != null ? nodeProps : new Properties();
  }

  /**
   * Start the node process.
   *
   * @throws IOException if process startup fails
   */
  public abstract void start() throws IOException;

  /**
   * Stop the node process.
   *
   * @throws IOException if process shutdown fails
   */
  public abstract void stop() throws IOException;

  /**
   * Check if the node is healthy.
   *
   * @return true if node is healthy
   * @throws IOException if health check fails
   */
  public abstract boolean isHealthy() throws IOException;

  /**
   * Get the base URL for the node.
   *
   * @return Base URL (e.g., "http://localhost:50001/solr")
   */
  public abstract String getBaseUrl();

  /**
   * Check if the process is alive.
   *
   * @return true if process is running
   */
  public boolean isAlive() {
    return process != null && process.isAlive();
  }

  /**
   * Wait for the process to be ready.
   *
   * @param timeoutMs Timeout in milliseconds
   * @return true if process became ready within timeout
   */
  protected boolean waitForProcessReady(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;

    while (System.currentTimeMillis() < deadline) {
      if (!isAlive()) {
        log.error("Process {} died during startup", nodeId);
        return false;
      }

      try {
        if (isHealthy()) {
          log.info("Process {} is ready", nodeId);
          return true;
        }
      } catch (IOException e) {
        log.debug("Health check failed for {}: {}", nodeId, e.getMessage());
      }

      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    log.error("Process {} did not become ready within {}ms", nodeId, timeoutMs);
    return false;
  }

  /**
   * Kill the process forcefully.
   */
  protected void killProcess() {
    if (process != null) {
      if (process.isAlive()) {
        log.warn("Forcefully destroying process for {}", nodeId);
        process.destroyForcibly();
        try {
          process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      process = null;
    }
  }

  /**
   * Write process ID to file for tracking.
   *
   * @param pid Process ID
   * @throws IOException if write fails
   */
  protected void writePidFile(long pid) throws IOException {
    File pidFile = new File(workDir, "pid");
    try (FileWriter writer = new FileWriter(pidFile)) {
      writer.write(String.valueOf(pid));
    }
    log.debug("Wrote PID {} to {}", pid, pidFile);
  }

  /**
   * Get process ID.
   *
   * @return Process ID, or -1 if not available
   */
  public long getPid() {
    if (process == null || !process.isAlive()) {
      return -1;
    }
    return process.pid();
  }

  /**
   * Start consuming process output to prevent buffer overflow.
   *
   * @param process Process to consume output from
   * @param nodeId Node identifier for logging
   */
  protected void consumeProcessOutput(Process process, String nodeId) {
    // Consume stdout
    Thread stdoutThread =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  log.info("[{} STDOUT] {}", nodeId, line);
                  System.out.println("[" + nodeId + " STDOUT] " + line);
                }
              } catch (IOException e) {
                log.warn("Error reading stdout from {}: {}", nodeId, e.getMessage());
              }
            },
            "stdout-" + nodeId);
    stdoutThread.setDaemon(true);
    stdoutThread.start();

    // Consume stderr
    Thread stderrThread =
        new Thread(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  log.error("[{} STDERR] {}", nodeId, line);
                  System.err.println("[" + nodeId + " STDERR] " + line);
                }
              } catch (IOException e) {
                log.warn("Error reading stderr from {}: {}", nodeId, e.getMessage());
              }
            },
            "stderr-" + nodeId);
    stderrThread.setDaemon(true);
    stderrThread.start();
  }

  // Getters
  public String getNodeId() {
    return nodeId;
  }

  public int getNodeIndex() {
    return nodeIndex;
  }

  public int getJettyPort() {
    return jettyPort;
  }

  public File getWorkDir() {
    return workDir;
  }

  public SolrDistribution getDistribution() {
    return distribution;
  }
}
