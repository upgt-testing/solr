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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages port allocation and persistence for ProcessBased cluster nodes.
 *
 * <p>CRITICAL: Ports must be persisted to disk to ensure node identity preservation across
 * restarts and upgrades. If a node changes its port during restart, other nodes will treat it as a
 * new node rather than the same node restarting.
 *
 * <p>This class:
 *
 * <ul>
 *   <li>Allocates free ports from a configurable range
 *   <li>Persists port allocations to disk (ports.properties files)
 *   <li>Loads persisted ports on restart
 *   <li>Validates port availability before reuse
 * </ul>
 */
public class PortAllocator {
  private static final Logger log = LoggerFactory.getLogger(PortAllocator.class);

  /** Default starting port for allocation */
  private static final int DEFAULT_START_PORT = 50000;

  /** Default ending port for allocation range */
  private static final int DEFAULT_END_PORT = 59999;

  private final Set<Integer> usedPorts;
  private int nextPort;
  private final int startPort;
  private final int endPort;
  private final File workDir;

  /**
   * Create a PortAllocator with default port range (50000-59999).
   *
   * @param workDir Base directory for persisting port allocations
   */
  public PortAllocator(File workDir) {
    this(workDir, DEFAULT_START_PORT, DEFAULT_END_PORT);
  }

  /**
   * Create a PortAllocator with custom port range.
   *
   * @param workDir Base directory for persisting port allocations
   * @param startPort Starting port for allocation range (inclusive)
   * @param endPort Ending port for allocation range (inclusive)
   */
  public PortAllocator(File workDir, int startPort, int endPort) {
    this.workDir = workDir;
    this.startPort = startPort;
    this.endPort = endPort;
    this.nextPort = startPort;
    this.usedPorts = new HashSet<>();
  }

  /**
   * Allocate a port for the specified node.
   *
   * <p>If this node has a persisted port allocation, that port will be reused (if available).
   * Otherwise, a new port will be allocated and persisted.
   *
   * @param nodeId Unique identifier for the node (e.g., "jetty0")
   * @return Allocated port number
   * @throws IOException if port persistence fails or no ports available
   */
  public synchronized int allocatePort(String nodeId) throws IOException {
    // Check if port already allocated for this node
    Integer existingPort = loadPersistedPort(nodeId);
    if (existingPort != null) {
      if (isPortAvailable(existingPort)) {
        usedPorts.add(existingPort);
        log.info("Reusing persisted port {} for node {}", existingPort, nodeId);
        return existingPort;
      } else {
        throw new IOException(
            "Cannot restart node "
                + nodeId
                + ": Port "
                + existingPort
                + " is in use. "
                + "Node identity cannot be preserved. "
                + "Other nodes will see this as a new node, not a restart.");
      }
    }

    // Allocate new port
    int port = findAvailablePort();
    usedPorts.add(port);

    // CRITICAL: Persist port allocation
    persistPort(nodeId, port);

    log.info("Allocated new port {} for node {}", port, nodeId);
    return port;
  }

  /**
   * Find an available port in the configured range.
   *
   * @return Available port number
   * @throws IOException if no ports available in range
   */
  private int findAvailablePort() throws IOException {
    int attempts = 0;
    int maxAttempts = endPort - startPort + 1;

    while (attempts < maxAttempts) {
      int candidatePort = nextPort;
      nextPort++;
      if (nextPort > endPort) {
        nextPort = startPort; // Wrap around
      }

      if (!usedPorts.contains(candidatePort) && isPortAvailable(candidatePort)) {
        return candidatePort;
      }

      attempts++;
    }

    throw new IOException(
        "No available ports in range " + startPort + "-" + endPort + " after " + attempts + " attempts");
  }

  /**
   * Check if a port is available for binding.
   *
   * @param port Port number to check
   * @return true if port is available, false otherwise
   */
  private boolean isPortAvailable(int port) {
    try (ServerSocket socket = new ServerSocket(port)) {
      socket.setReuseAddress(true);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Load persisted port for a node from disk.
   *
   * @param nodeId Node identifier
   * @return Persisted port number, or null if not found
   */
  private Integer loadPersistedPort(String nodeId) {
    File portFile = getPortFile(nodeId);
    if (!portFile.exists()) {
      return null;
    }

    Properties props = new Properties();
    try (FileInputStream fis = new FileInputStream(portFile)) {
      props.load(fis);
      String portStr = props.getProperty("jetty.port");
      if (portStr != null) {
        return Integer.parseInt(portStr);
      }
    } catch (IOException | NumberFormatException e) {
      log.warn("Failed to load persisted port for node {}: {}", nodeId, e.getMessage());
    }

    return null;
  }

  /**
   * Persist port allocation to disk for future restarts.
   *
   * @param nodeId Node identifier
   * @param port Port number to persist
   * @throws IOException if persistence fails
   */
  private void persistPort(String nodeId, int port) throws IOException {
    File portFile = getPortFile(nodeId);
    portFile.getParentFile().mkdirs();

    Properties props = new Properties();
    props.setProperty("jetty.port", String.valueOf(port));
    props.setProperty("node.id", nodeId);
    props.setProperty("allocated.time", String.valueOf(System.currentTimeMillis()));

    try (FileOutputStream fos = new FileOutputStream(portFile)) {
      props.store(fos, "Port allocation for " + nodeId);
    }

    log.debug("Persisted port {} for node {} to {}", port, nodeId, portFile);
  }

  /**
   * Get the port file path for a node.
   *
   * @param nodeId Node identifier
   * @return File object for the port persistence file
   */
  private File getPortFile(String nodeId) {
    return new File(new File(workDir, nodeId), "ports.properties");
  }

  /**
   * Release a port allocation (marks it as available).
   *
   * @param port Port number to release
   */
  public synchronized void releasePort(int port) {
    usedPorts.remove(port);
    log.debug("Released port {}", port);
  }

  /**
   * Get the set of currently used ports.
   *
   * @return Set of allocated port numbers
   */
  public synchronized Set<Integer> getUsedPorts() {
    return new HashSet<>(usedPorts);
  }
}
