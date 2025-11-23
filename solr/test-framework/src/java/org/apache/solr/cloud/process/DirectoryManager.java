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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages temporary directory structure for ProcessBased Solr cluster.
 *
 * <p>Creates and manages the following directory structure:
 *
 * <pre>
 * /tmp/process-minisolr-{timestamp}/
 * ├── jetty0/
 * │   ├── ports.properties
 * │   ├── node-identity.properties
 * │   ├── conf/
 * │   ├── data/
 * │   ├── logs/
 * │   └── pid
 * ├── jetty1/
 * └── cluster.properties
 * </pre>
 */
public class DirectoryManager {
  private static final Logger log = LoggerFactory.getLogger(DirectoryManager.class);

  private static final String CLUSTER_DIR_PREFIX = "process-minisolr-";

  private final File baseDir;
  private final File clusterDir;

  /**
   * Create a DirectoryManager with auto-generated cluster directory.
   *
   * @throws IOException if directory creation fails
   */
  public DirectoryManager() throws IOException {
    this(null);
  }

  /**
   * Create a DirectoryManager with specified base directory.
   *
   * @param baseDir Base directory for cluster (uses system temp if null)
   * @throws IOException if directory creation fails
   */
  public DirectoryManager(File baseDir) throws IOException {
    if (baseDir == null) {
      this.baseDir = new File(System.getProperty("java.io.tmpdir"));
    } else {
      this.baseDir = baseDir;
    }

    // Create unique cluster directory with timestamp
    long timestamp = System.currentTimeMillis();
    this.clusterDir = new File(this.baseDir, CLUSTER_DIR_PREFIX + timestamp);

    if (!clusterDir.mkdirs()) {
      throw new IOException("Failed to create cluster directory: " + clusterDir);
    }

    log.info("Created cluster directory: {}", clusterDir.getAbsolutePath());
  }

  /**
   * Get the cluster base directory.
   *
   * @return Cluster directory
   */
  public File getClusterDir() {
    return clusterDir;
  }

  /**
   * Create directory structure for a Solr node.
   *
   * @param nodeId Node identifier (e.g., "jetty0")
   * @return Node work directory
   * @throws IOException if directory creation fails
   */
  public File createNodeDir(String nodeId) throws IOException {
    File nodeDir = new File(clusterDir, nodeId);
    if (!nodeDir.exists() && !nodeDir.mkdirs()) {
      throw new IOException("Failed to create node directory: " + nodeDir);
    }

    // Create subdirectories
    File confDir = new File(nodeDir, "conf");
    File dataDir = new File(nodeDir, "data");
    File logsDir = new File(nodeDir, "logs");

    if (!confDir.mkdirs()) {
      throw new IOException("Failed to create conf directory: " + confDir);
    }
    if (!dataDir.mkdirs()) {
      throw new IOException("Failed to create data directory: " + dataDir);
    }
    if (!logsDir.mkdirs()) {
      throw new IOException("Failed to create logs directory: " + logsDir);
    }

    log.info("Created node directory structure for {}: {}", nodeId, nodeDir.getAbsolutePath());
    return nodeDir;
  }

  /**
   * Get the node directory path.
   *
   * @param nodeId Node identifier
   * @return Node directory
   */
  public File getNodeDir(String nodeId) {
    return new File(clusterDir, nodeId);
  }

  /**
   * Get the configuration directory for a node.
   *
   * @param nodeId Node identifier
   * @return Configuration directory
   */
  public File getNodeConfDir(String nodeId) {
    return new File(getNodeDir(nodeId), "conf");
  }

  /**
   * Get the data directory for a node.
   *
   * @param nodeId Node identifier
   * @return Data directory
   */
  public File getNodeDataDir(String nodeId) {
    return new File(getNodeDir(nodeId), "data");
  }

  /**
   * Get the logs directory for a node.
   *
   * @param nodeId Node identifier
   * @return Logs directory
   */
  public File getNodeLogsDir(String nodeId) {
    return new File(getNodeDir(nodeId), "logs");
  }

  /**
   * Get the PID file for a node.
   *
   * @param nodeId Node identifier
   * @return PID file
   */
  public File getNodePidFile(String nodeId) {
    return new File(getNodeDir(nodeId), "pid");
  }

  /**
   * Clean up the entire cluster directory.
   *
   * @throws IOException if cleanup fails
   */
  public void cleanup() throws IOException {
    if (clusterDir.exists()) {
      log.info("Cleaning up cluster directory: {}", clusterDir.getAbsolutePath());
      deleteDirectory(clusterDir.toPath());
    }
  }

  /**
   * Clean up a specific node directory.
   *
   * @param nodeId Node identifier
   * @throws IOException if cleanup fails
   */
  public void cleanupNode(String nodeId) throws IOException {
    File nodeDir = getNodeDir(nodeId);
    if (nodeDir.exists()) {
      log.info("Cleaning up node directory for {}: {}", nodeId, nodeDir.getAbsolutePath());
      deleteDirectory(nodeDir.toPath());
    }
  }

  /**
   * Recursively delete a directory.
   *
   * @param path Directory path to delete
   * @throws IOException if deletion fails
   */
  private void deleteDirectory(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }

    Files.walkFileTree(
        path,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Clean up old cluster directories (older than specified age).
   *
   * @param baseDir Base directory to search
   * @param maxAgeMillis Maximum age in milliseconds
   * @return Number of directories cleaned up
   */
  public static int cleanupOldClusterDirectories(File baseDir, long maxAgeMillis) {
    int cleaned = 0;

    File[] oldDirs =
        baseDir.listFiles(
            (dir, name) -> name.startsWith(CLUSTER_DIR_PREFIX) && name.matches(".*\\d{13}$"));

    if (oldDirs == null) {
      return 0;
    }

    long cutoffTime = System.currentTimeMillis() - maxAgeMillis;

    for (File dir : oldDirs) {
      try {
        BasicFileAttributes attrs =
            Files.readAttributes(dir.toPath(), BasicFileAttributes.class);
        if (attrs.creationTime().toMillis() < cutoffTime) {
          log.info("Cleaning up old cluster directory: {}", dir.getAbsolutePath());
          DirectoryManager dm = new DirectoryManager(baseDir);
          dm.deleteDirectory(dir.toPath());
          cleaned++;
        }
      } catch (IOException e) {
        log.warn("Failed to clean up old cluster directory {}: {}", dir, e.getMessage());
      }
    }

    log.info("Cleaned up {} old cluster directories", cleaned);
    return cleaned;
  }
}
