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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a real Solr process using bin/solr script.
 *
 * <p>This implementation uses the standard Solr start/stop scripts instead of embedding
 * JettySolrRunner. This provides:
 *
 * <ul>
 *   <li>Production-like testing environment
 *   <li>Works with packaged Solr distributions
 *   <li>No test-framework dependencies required
 *   <li>Simpler classpath management (handled by bin/solr)
 * </ul>
 */
public class SolrProcessManager extends ProcessNodeManager {
  private static final Logger log = LoggerFactory.getLogger(SolrProcessManager.class);

  private static final long DEFAULT_STARTUP_TIMEOUT_MS = 90000; // 90 seconds

  private final File solrHome;
  private final HealthMonitor healthMonitor;

  /**
   * Create a SolrProcessManager.
   *
   * @param nodeId Node identifier
   * @param nodeIndex Node index
   * @param distribution Solr distribution
   * @param workDir Work directory
   * @param jettyPort Solr HTTP port
   * @param zkHost ZooKeeper connection string
   * @param nodeProps Additional properties (currently unused but kept for API compatibility)
   */
  public SolrProcessManager(
      String nodeId,
      int nodeIndex,
      SolrDistribution distribution,
      File workDir,
      int jettyPort,
      String zkHost,
      Properties nodeProps) {
    super(nodeId, nodeIndex, distribution, workDir, jettyPort, zkHost, nodeProps);
    this.solrHome = distribution.getSolrHome();
    this.healthMonitor = new HealthMonitor();
  }

  @Override
  public void start() throws IOException {
    if (isAlive()) {
      log.warn("Solr process {} is already running", nodeId);
      return;
    }

    log.info("Starting Solr node {} on port {} using bin/solr", nodeId, jettyPort);

    // Prepare directories
    File dataDir = new File(workDir, "data");
    File logsDir = new File(workDir, "logs");
    dataDir.mkdirs();
    logsDir.mkdirs();

    // Build start command
    List<String> command = buildStartCommand();

    log.info("Starting Solr with command: {}", String.join(" ", command));
    System.out.println("=== STARTING SOLR PROCESS ===");
    System.out.println("Node: " + nodeId);
    System.out.println("Solr home: " + solrHome);
    System.out.println("Working directory: " + workDir);
    System.out.println("Command: " + String.join(" ", command));
    System.out.println("=============================");

    // Start process
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(solrHome);
    builder.redirectErrorStream(false);

    // Set environment variables
    builder.environment().put("SOLR_PID_DIR", workDir.getAbsolutePath());
    builder.environment().put("SOLR_LOGS_DIR", new File(workDir, "logs").getAbsolutePath());

    process = builder.start();
    long pid = process.pid();
    writePidFile(pid);

    log.info("Started Solr process for {} with PID {}", nodeId, pid);
    System.out.println("Started Solr subprocess with PID: " + pid);

    // Consume output to prevent buffer overflow
    consumeProcessOutput(process, nodeId);

    // Wait for Solr to be ready
    if (!waitForProcessReady(DEFAULT_STARTUP_TIMEOUT_MS)) {
      killProcess();
      throw new IOException("Failed to start Solr node " + nodeId + " within timeout");
    }

    log.info("Solr node {} is ready and healthy", nodeId);
  }

  @Override
  public void stop() throws IOException {
    if (!isAlive()) {
      log.debug("Solr process {} is not running", nodeId);
      return;
    }

    log.info("Stopping Solr node {}", nodeId);

    // Try using bin/solr stop command first
    boolean stopped = stopUsingSolrScript();

    if (!stopped) {
      log.warn("bin/solr stop failed or timed out, killing process directly");
      killProcess();
    }

    process = null;
    log.info("Stopped Solr node {}", nodeId);
  }

  @Override
  public boolean isHealthy() throws IOException {
    if (!isAlive()) {
      return false;
    }
    return healthMonitor.checkSolrHealth(getBaseUrl());
  }

  @Override
  public String getBaseUrl() {
    return "http://127.0.0.1:" + jettyPort + "/solr";
  }

  /**
   * Build the bin/solr start command.
   *
   * @return Command as list of strings
   */
  private List<String> buildStartCommand() {
    List<String> command = new ArrayList<>();

    // Solr script
    File solrScript = new File(solrHome, "bin/solr");
    command.add(solrScript.getAbsolutePath());

    // Start command
    command.add("start");

    // Cloud mode
    command.add("-c");

    // Port
    command.add("-p");
    command.add(String.valueOf(jettyPort));

    // ZooKeeper
    command.add("-z");
    command.add(zkHost);

    // Data directory (solr home for this node)
    command.add("-s");
    command.add(new File(workDir, "data").getAbsolutePath());

    // Foreground mode (keeps process attached)
    command.add("-f");

    // Memory
    command.add("-m");
    command.add("512m");

    // Force - skip version/Java checks
    command.add("-force");

    return command;
  }

  /**
   * Stop Solr using bin/solr stop command.
   *
   * @return true if stopped successfully
   */
  private boolean stopUsingSolrScript() {
    try {
      List<String> command = new ArrayList<>();
      File solrScript = new File(solrHome, "bin/solr");
      command.add(solrScript.getAbsolutePath());
      command.add("stop");
      command.add("-p");
      command.add(String.valueOf(jettyPort));

      log.info("Stopping Solr with command: {}", String.join(" ", command));

      ProcessBuilder builder = new ProcessBuilder(command);
      builder.directory(solrHome);
      Process stopProcess = builder.start();

      boolean finished = stopProcess.waitFor(30, TimeUnit.SECONDS);

      if (finished && stopProcess.exitValue() == 0) {
        log.info("Solr stopped successfully via bin/solr stop");
        return true;
      } else {
        log.warn(
            "bin/solr stop did not complete successfully (finished: {}, exit: {})",
            finished,
            finished ? stopProcess.exitValue() : "N/A");
        return false;
      }

    } catch (IOException | InterruptedException e) {
      log.warn("Error running bin/solr stop: {}", e.getMessage());
      return false;
    }
  }
}
