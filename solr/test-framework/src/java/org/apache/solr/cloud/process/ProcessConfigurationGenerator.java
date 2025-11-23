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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates configuration files for ProcessBased Solr nodes.
 *
 * <p>Creates node-specific configuration including:
 *
 * <ul>
 *   <li>Working directories (data, logs)
 *   <li>Node identity files for tracking across restarts
 * </ul>
 *
 * <p>Note: solr.xml and other Solr configuration is handled by bin/solr script, so we don't
 * generate it here.
 */
public class ProcessConfigurationGenerator {
  private static final Logger log = LoggerFactory.getLogger(ProcessConfigurationGenerator.class);

  /**
   * Create working directories for a Solr node.
   *
   * @param workDir Node work directory
   * @throws IOException if directory creation fails
   */
  public static void createNodeDirectories(File workDir) throws IOException {
    File dataDir = new File(workDir, "data");
    File logsDir = new File(workDir, "logs");

    if (!dataDir.mkdirs() && !dataDir.exists()) {
      throw new IOException("Failed to create data directory: " + dataDir);
    }
    if (!logsDir.mkdirs() && !logsDir.exists()) {
      throw new IOException("Failed to create logs directory: " + logsDir);
    }

    log.debug("Created directories for node in {}", workDir);
  }

  /**
   * Write node identity file for tracking across restarts.
   *
   * @param nodeId Node identifier
   * @param nodeIndex Node index
   * @param dir Directory to write to
   * @throws IOException if write fails
   */
  public static void writeNodeIdentity(String nodeId, int nodeIndex, File dir)
      throws IOException {
    Properties identity = new Properties();
    identity.setProperty("node.id", nodeId);
    identity.setProperty("node.index", String.valueOf(nodeIndex));
    identity.setProperty("created.time", String.valueOf(System.currentTimeMillis()));

    File identityFile = new File(dir, "node-identity.properties");
    try (FileOutputStream fos = new FileOutputStream(identityFile)) {
      identity.store(fos, "ProcessBased Solr Node Identity");
    }

    log.debug("Wrote node identity to {}", identityFile.getAbsolutePath());
  }
}
