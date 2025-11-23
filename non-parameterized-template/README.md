# Process-Based Mini Cluster Transformation Templates

## Overview

This directory contains **generalized templates** for transforming any distributed system's mini cluster testing framework from in-process (all nodes in same JVM) to **process-based** (each node in separate JVM process). This enables multi-version testing, rolling upgrades, and realistic production-like testing scenarios.

**Originally developed for**: Apache Hadoop HDFS (ProcessBasedMiniDFSCluster)
**Generalized for**: Any distributed system with a mini cluster testing framework (HBase, ZooKeeper, Kafka, Cassandra, etc.)

---

## What's Included

This template package includes **three comprehensive step-by-step guides** that work together:

1. **`step1-process-based-cluster-implementation-template.md`** (~1,700 lines)
   - Implementation plan for creating the process-based cluster framework
   - Architecture, design patterns, class structure
   - Process management, classpath isolation, configuration management
   - 6-week implementation timeline with detailed tasks

2. **`step2-upgrade-base-test-generation-template.md`** (~400 lines)
   - Template for generating parameterized upgrade base test class
   - Automatic lifecycle management, checkpoint-based upgrade testing
   - Complete test isolation with guaranteed cleanup

3. **`step3-test-transformation-guide-template.md`** (~2,000 lines)
   - Comprehensive guide for transforming existing tests
   - API mapping tables, transformation patterns
   - Step-by-step process, best practices, decision trees

---

## Quick Start

### Step 1: Fill in the Templates

For each template file:

1. **Open the template** in your text editor
2. **Search for `{PLACEHOLDER}`** - all placeholders are marked with curly braces
3. **Replace each placeholder** with your project-specific value
4. **Fill in the API mapping tables** (especially in Step 3)
5. **Save your customized version** (recommended: `{ProjectName}-step1-...md`)

### Step 2: Follow the Steps in Order

Execute the templates in sequence:

1. **Step 1**: Implement the process-based cluster framework (~6 weeks)
2. **Step 2**: Generate the base test class for parameterized testing (~1-2 days)
3. **Step 3**: Transform existing tests using the transformation guide (ongoing)

---

## Detailed Usage Instructions

### Template 1: Process-Based Cluster Implementation

**File**: `step1-process-based-cluster-implementation-template.md`

**Purpose**: Guide you through implementing the core process-based mini cluster infrastructure.

**Key Placeholders** (there are ~50 total):
- `{PROJECT_NAME}` - e.g., "Apache HBase", "Apache ZooKeeper"
- `{MINI_CLUSTER_CLASS}` - e.g., "MiniHBaseCluster", "MiniZKCluster"
- `{PROCESS_BASED_CLUSTER_CLASS}` - e.g., "ProcessBasedMiniHBaseCluster"
- `{NODE_TYPE_1_NAME}` - e.g., "Master", "ZooKeeper Server"
- `{NODE_TYPE_1_CLASS}` - e.g., "HMaster", "QuorumPeer"
- `{NODE_TYPE_2_NAME}` - e.g., "RegionServer", "Follower"
- `{NODE_TYPE_2_CLASS}` - e.g., "HRegionServer", "Follower"
- `{CLIENT_CLASS}` - e.g., "Connection", "ZooKeeper"
- ... and many more (see template for full list)

**How to Use**:
1. Fill in all `{PLACEHOLDER}` values for your project
2. Customize the architecture section to match your cluster topology
3. Follow the 6-week implementation plan (or adjust timeline as needed)
4. Use the checklists to track progress

**Output**: A customized implementation guide for your process-based cluster

---

### Template 2: Upgrade Base Test Generation

**File**: `step2-upgrade-base-test-generation-template.md`

**Purpose**: Generate a base test class that provides automatic lifecycle management and parameterized checkpoint-based upgrade testing.

**Key Placeholders**:
- `{CLUSTER_TYPE_DESCRIPTION}` - e.g., "distributed column-oriented database"
- `{PROCESS_BASED_CLUSTER_CLASS_FQN}` - Full package name
- `{CLIENT_CLASS_FQN}` - Full package name for client
- `{CONFIG_CLASS_FQN}` - Full package name for configuration
- `{CLUSTER_INIT_CODE}` - Code snippet to initialize cluster
- `{CLIENT_INIT_CODE}` - Code snippet to initialize client
- `{CLUSTER_SHUTDOWN_CODE}` - Code snippet to shutdown cluster
- `{UPGRADE_INVOCATION_CODE}` - Code to invoke rolling upgrade
- `{PROCESS_PATTERN}` - Regex to match process names for cleanup
- `{TEMP_DIR_PATTERN}` - Pattern for temporary cluster directories
- ... and more (see template for full list)

**How to Use**:
1. Fill in all placeholders in the "AI Agent Prompt Template" section
2. Provide the filled template to an AI programming agent (ChatGPT, Claude, etc.) or use as your own implementation guide
3. The AI will generate a complete `ProcessBasedUpgradeTestBase` class
4. Review, test, and integrate the generated class into your project

**Output**: A complete base test class with automatic cleanup and parameterized checkpoints

---

### Template 3: Test Transformation Guide

**File**: `step3-test-transformation-guide-template.md`

**Purpose**: Comprehensive guide for transforming your existing mini cluster tests to use the process-based cluster.

**Key Sections**:
1. **API Mapping Tables** (YOU MUST FILL THESE IN):
   - Table 1: Mini cluster → Process-based cluster method mappings
   - Table 2: Primary server component → Client API mappings
   - Table 3: Secondary server component → Client API mappings
   - Table 4: Admin operations mappings
   - Table 5: Monitoring/metrics mappings
   - Table 6: Test utility mappings

2. **Transformation Process**:
   - Step-by-step instructions
   - Common patterns
   - Decision trees

3. **Special Topics**:
   - Inserting upgrade calls
   - Parameterized checkpoints
   - Resource management during upgrades
   - What to comment out and when

**How to Use**:
1. **CRITICAL**: Fill in ALL API mapping tables first (Tables 1-6)
   - This is the most important step!
   - Map every server-side operation to its client-side equivalent
   - Document which operations have no client API (must be commented out)

2. Follow the step-by-step transformation process for each test

3. Use the decision tree when you're unsure how to transform an operation

4. Reference the common patterns section for frequently-encountered scenarios

**Output**: A transformation guide customized for your project, ready to use for transforming tests

---

## Example: HBase Mini Cluster

Here's what the placeholders would look like for HBase:

```markdown
{PROJECT_NAME} = "Apache HBase"
{PROJECT_ARTIFACT} = "hbase"
{MINI_CLUSTER_CLASS} = "MiniHBaseCluster"
{PROCESS_BASED_CLUSTER_CLASS} = "ProcessBasedMiniHBaseCluster"
{NODE_TYPE_1_NAME} = "Master"
{NODE_TYPE_1_CLASS} = "HMaster"
{NODE_TYPE_2_NAME} = "RegionServer"
{NODE_TYPE_2_CLASS} = "HRegionServer"
{CLIENT_CLASS} = "Connection"
{CLIENT_VAR} = "connection"
{GET_CLIENT_METHOD} = "getConnection"
{CONFIG_CLASS} = "Configuration"
{HIGH_LEVEL_CLIENT_API} = "Table/Admin API"
{MID_LEVEL_CLIENT_API} = "Connection API"
{LOW_LEVEL_RPC_API} = "AdminProtos.AdminService"
{ADMIN_TOOLS_API} = "HBaseAdmin"
{MONITORING_API} = "JMX/ClusterStatus"
```

---

## Example: ZooKeeper Mini Cluster

Here's what the placeholders would look like for ZooKeeper:

```markdown
{PROJECT_NAME} = "Apache ZooKeeper"
{PROJECT_ARTIFACT} = "zookeeper"
{MINI_CLUSTER_CLASS} = "MiniZKCluster" (or "QuorumPeerTestBase")
{PROCESS_BASED_CLUSTER_CLASS} = "ProcessBasedMiniZKCluster"
{NODE_TYPE_1_NAME} = "ZooKeeper Server"
{NODE_TYPE_1_CLASS} = "QuorumPeer"
{NODE_TYPE_2_NAME} = "Observer" (if applicable, or same as type 1)
{NODE_TYPE_2_CLASS} = "QuorumPeer"
{CLIENT_CLASS} = "ZooKeeper"
{CLIENT_VAR} = "zk"
{GET_CLIENT_METHOD} = "getZooKeeperClient"
{CONFIG_CLASS} = "QuorumPeerConfig"
{HIGH_LEVEL_CLIENT_API} = "ZooKeeper client API"
{MID_LEVEL_CLIENT_API} = "ZooKeeper client API"
{LOW_LEVEL_RPC_API} = "ClientCnxn"
{ADMIN_TOOLS_API} = "ZKUtil"
{MONITORING_API} = "JMX/ServerStats"
```

---

## Complete Workflow

### Phase 1: Preparation (1-2 days)

1. **Gather Information**:
   - List all node types in your cluster
   - Identify client APIs and how they're used
   - Document server-side components and their responsibilities
   - Identify internal operations that have no client API

2. **Fill in Templates**:
   - Start with Step 1 template (implementation plan)
   - Fill in all basic placeholders
   - Customize architecture diagrams

3. **Create API Mapping Tables**:
   - Fill in Table 1-6 in Step 3 template
   - This is the MOST IMPORTANT step for successful transformations
   - Be thorough - every server-side operation needs a mapping decision

### Phase 2: Implementation (~6 weeks)

1. **Follow Step 1 template** to implement the process-based cluster
   - Week 1-2: Core infrastructure (process management, classpath, configuration)
   - Week 3-4: Main cluster class implementation
   - Week 5: Multi-version support
   - Week 6: Testing and documentation

2. **Generate base test class** using Step 2 template
   - Fill in the template
   - Generate or implement the `ProcessBasedUpgradeTestBase` class
   - Test the base class with a simple test

### Phase 3: Test Transformation (Ongoing)

1. **Use Step 3 guide** to transform existing tests
   - Start with simple tests to get familiar with the process
   - Follow the step-by-step transformation process
   - Use the API mapping tables to guide transformations
   - Reference common patterns for frequent scenarios

2. **Measure progress**:
   - Track number of tests transformed
   - Track test coverage with process-based cluster
   - Document any patterns or issues you discover

3. **Iterate and improve**:
   - Update your API mapping tables as you discover new patterns
   - Add new common patterns to your transformation guide
   - Share learnings with your team

---

## Key Success Factors

### 1. Complete API Mapping Tables First ⭐⭐⭐

**This is the most critical step!** Before you start transforming tests:

- Fill in ALL tables in Step 3 template
- For every server-side operation, document its client-side equivalent
- Mark operations that have no client API
- This becomes your transformation "source of truth"

### 2. Preserve Test Logic

The goal is to preserve as much of the original test logic as possible:

- Find client-side equivalents for server-side operations (use your mapping tables!)
- Only comment out truly internal operations
- Maintain test intent even if implementation changes

### 3. Use Consistent Naming

All three templates use consistent placeholder naming:

- `{PROCESS_BASED_CLUSTER_CLASS}` is always your new cluster class
- `{NODE_TYPE_1_NAME}`, `{NODE_TYPE_2_NAME}` for node types
- `{CLIENT_CLASS}`, `{CLIENT_VAR}` for client
- This makes it easy to cross-reference between templates

### 4. Follow the Transformation Hierarchy

When transforming operations, try in this order:

1. High-level client API (easiest, most maintainable)
2. Mid-level client API
3. Low-level RPC API
4. Admin tools/utilities
5. Monitoring/JMX APIs
6. Comment out (last resort)

### 5. Test Incrementally

- Implement core infrastructure first (Step 1)
- Create base test class next (Step 2)
- Transform tests one at a time (Step 3)
- Run transformed tests frequently to catch issues early

---

## Troubleshooting

### "I can't find a client API for operation X"

1. Check your API mapping tables thoroughly
2. Check if there's an admin tool or utility that provides it
3. Check if monitoring/JMX exposes the information
4. Search your project's documentation for the operation
5. If truly no client API exists, comment out with proper documentation

### "The process-based cluster is too slow"

This is expected - process-based testing trades speed for realism:

- Each node in separate JVM = slower startup
- RPC overhead for all operations
- Natural heartbeat intervals (can't be artificially triggered)

Mitigations:
- Run process-based tests in CI/nightly builds, not on every commit
- Keep in-process tests for quick feedback
- Use process-based tests for integration/upgrade scenarios

### "I'm getting port conflicts"

Make sure:
- Your `PortAllocator` scans for available ports
- Old cluster processes are cleaned up between tests
- Your base test class (@Before) kills orphaned processes
- Tests run sequentially, not in parallel (or use different port ranges)

---

## Architecture Patterns Across Different Cluster Types

### Master-Worker (HBase, HDFS)
- NODE_TYPE_1 = Master/Coordinator
- NODE_TYPE_2 = Worker/Storage
- Typically one master, multiple workers

### Peer-to-Peer (Cassandra, Riak)
- NODE_TYPE_1 = Node
- All nodes are equal
- No distinguished master

### Quorum-Based (ZooKeeper, etcd)
- NODE_TYPE_1 = Server/Peer
- Leader election among peers
- May have observer nodes (NODE_TYPE_2)

### Multi-Master (some configurations)
- NODE_TYPE_1 = Master (multiple)
- NODE_TYPE_2 = Worker
- Multiple masters with coordination

**The templates support all these patterns** - just use NODE_TYPE_1, NODE_TYPE_2, etc. as needed!

---

## Contributing

If you use these templates for your project and discover improvements:

1. Document new patterns you found
2. Update your API mapping tables
3. Share common transformation patterns
4. Consider contributing back to improve these templates

---

## Support & Questions

For questions about using these templates:

1. Review the original HDFS implementation in the parent directory
2. Check the example placeholders for HBase/ZooKeeper above
3. Consult your project's API documentation
4. Ask your team members who are familiar with the codebase

---

## License

These templates are derived from Apache Hadoop's ProcessBasedMiniDFSCluster implementation and follow the same Apache License 2.0.

---

## Acknowledgments

These templates were created based on the ProcessBasedMiniDFSCluster implementation for Apache Hadoop HDFS, generalized for use with any distributed system testing framework.

**Original Hadoop HDFS implementation**: See parent directory for:
- `process-based-mini-cluster-plan.md`
- `UPGRADE_BASE_TEST_PROMPT.md`
- `UPGRADE-TEST-TRANSFORMATION-PROMPT.md`
- `UPGRADE-TESTING-README.md`

---

**End of README**

Good luck transforming your mini cluster testing framework! 🚀
