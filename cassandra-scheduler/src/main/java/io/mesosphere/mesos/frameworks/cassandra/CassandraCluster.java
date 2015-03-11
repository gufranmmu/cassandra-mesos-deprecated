/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mesosphere.mesos.frameworks.cassandra;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ListMultimap;
import io.mesosphere.mesos.frameworks.cassandra.CassandraFrameworkProtos.*;
import io.mesosphere.mesos.frameworks.cassandra.util.Env;
import io.mesosphere.mesos.util.CassandraFrameworkProtosUtils;
import io.mesosphere.mesos.util.Clock;
import org.apache.mesos.Protos;
import org.jetbrains.annotations.NotNull;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.regex.Pattern;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.FluentIterable.from;
import static com.google.common.collect.Lists.newArrayList;
import static io.mesosphere.mesos.util.CassandraFrameworkProtosUtils.*;
import static io.mesosphere.mesos.util.Functions.*;
import static io.mesosphere.mesos.util.ProtoUtils.*;
import static io.mesosphere.mesos.util.Tuple2.tuple2;

/**
 * The processing model that is used by Mesos is that of an actor system.
 *
 * This means that we're guaranteed to only have one thread calling a method in the scheduler at any one time.
 *
 * Design considerations:
 * <ol>
 *     <li>
 *         Mesos will soon have the concept of a dynamic reservation, this means that as a framework we can reserve
 *         resources for the framework. This will be leveraged to ensure that we receive resource offers for all hosts
 *         As part of the reservation process we will make sure to always reserve enough resources to ensure that we
 *         will receive an offer. This fact will allow us to not have to implement our own task time based scheduler.
 *     </li>
 *     <li>
 *         Periodic tasks will be defined with a time specifying when they are to be run. When a resource offer is
 *         received the time will be evaluated and if the current time is past when the task is scheduled to be run
 *         a task will be returned for the offer.
 *     </li>
 * </ol>
 */
public final class CassandraCluster {
    private static final Logger LOGGER = LoggerFactory.getLogger(CassandraCluster.class);

    private static final Joiner JOINER = Joiner.on("','");
    private static final Joiner SEEDS_FORMAT_JOINER = Joiner.on(',');
    private static final Pattern URL_FOR_RESOURCE_REPLACE = Pattern.compile("(?<!:)/+");

    public static final String PORT_STORAGE = "storage_port";
    public static final String PORT_STORAGE_SSL = "ssl_storage_port";
    public static final String PORT_NATIVE = "native_transport_port";
    public static final String PORT_RPC = "rpc_port";
    public static final String PORT_JMX = "jmx_port";

    // see: http://www.datastax.com/documentation/cassandra/2.1/cassandra/security/secureFireWall_r.html
    private static final Map<String, Long> defaultPortMappings = unmodifiableHashMap(
        tuple2(PORT_STORAGE, 7000L),
        tuple2(PORT_STORAGE_SSL, 7001L),
        tuple2(PORT_JMX, 7199L),
        tuple2(PORT_NATIVE, 9042L),
        tuple2(PORT_RPC, 9160L)
    );

    private static final Map<String, String> executorEnv = unmodifiableHashMap(
        tuple2("JAVA_OPTS", "-Xms256m -Xmx256m")
    );

    @NotNull
    private final Clock clock;
    @NotNull
    private final String httpServerBaseUrl;

    @NotNull
    private final ExecutorCounter execCounter;
    @NotNull
    private final PersistedCassandraClusterState clusterState;
    @NotNull
    private final PersistedCassandraClusterHealthCheckHistory healthCheckHistory;
    @NotNull
    private final PersistedCassandraFrameworkConfiguration configuration;
    @NotNull
    private final PersistedCassandraClusterJobs jobsState;

    public static int getPortMapping(CassandraFrameworkConfiguration configuration, String name) {
        for (PortMapping portMapping : configuration.getPortMappingList()) {
            if (portMapping.getName().equals(name))
                return portMapping.getPort();
        }
        Long port = defaultPortMappings.get(name);
        if (port == null) {
            throw new IllegalStateException("no port mapping for " + name);
        }
        return port.intValue();
    }

    public CassandraCluster(
        @NotNull final Clock clock,
        @NotNull final String httpServerBaseUrl,
        @NotNull final ExecutorCounter execCounter,
        @NotNull final PersistedCassandraClusterState clusterState,
        @NotNull final PersistedCassandraClusterHealthCheckHistory healthCheckHistory,
        @NotNull final PersistedCassandraClusterJobs jobsState,
        @NotNull final PersistedCassandraFrameworkConfiguration configuration
    ) {
        this.clock = clock;
        this.httpServerBaseUrl = httpServerBaseUrl;
        this.execCounter = execCounter;
        this.clusterState = clusterState;
        this.healthCheckHistory = healthCheckHistory;
        this.jobsState = jobsState;
        this.configuration = configuration;
    }

    @NotNull
    public PersistedCassandraClusterState getClusterState() {
        return clusterState;
    }

    @NotNull
    public PersistedCassandraFrameworkConfiguration getConfiguration() {
        return configuration;
    }

    @NotNull
    public PersistedCassandraClusterJobs getJobsState() {
        return jobsState;
    }

    public void removeTask(@NotNull final String taskId, Protos.TaskStatus status) {
        List<CassandraNode> nodes = clusterState.nodes();
        List<CassandraNode> newNodes = new ArrayList<>(nodes.size());
        boolean changed = false;
        for (CassandraNode cassandraNode : nodes) {
            if (cassandraNode.hasMetadataTask() && cassandraNode.getMetadataTask().getTaskId().equals(taskId)) {
                // TODO shouldn't we also assume that the server task is no longer running ??
                // TODO do we need to remove the executor metadata ??

                changed = true;
                removeExecutorMetadata(cassandraNode.getMetadataTask().getExecutorId());
                newNodes.add(CassandraNode.newBuilder(cassandraNode)
                        .clearMetadataTask()
                        .clearServerTask()
                        .build());
            } else if (cassandraNode.hasServerTask() && cassandraNode.getServerTask().getTaskId().equals(taskId)) {
                changed = true;
                newNodes.add(CassandraNode.newBuilder(cassandraNode)
                        .clearServerTask()
                        .build());
            } else {
                newNodes.add(cassandraNode);
            }
        }
        if (changed)
            clusterState.nodes(newNodes);

        ClusterJobStatus clusterJob = getCurrentClusterJob();
        if (clusterJob != null) {
            if (clusterJob.hasCurrentNode() && clusterJob.getCurrentNode().getTaskId().equals(taskId)) {
                jobsState.removeTaskForCurrentNode(status, clusterJob);
            }
        }
    }

    public void removeExecutor(@NotNull final String executorId) {
        final FluentIterable<CassandraNode> update = from(clusterState.nodes())
            .transform(cassandraNodeToBuilder())
            .transform(new ContinuingTransform<CassandraNode.Builder>() {
                @Override
                public CassandraNode.Builder apply(final CassandraNode.Builder input) {
                    if (input.hasCassandraNodeExecutor() && input.getCassandraNodeExecutor().getExecutorId().equals(executorId)) {
                        return input
                            .clearMetadataTask()
                            .clearServerTask();
                    }
                    return input;
                }
            })
            .transform(cassandraNodeBuilderToCassandraNode());
        clusterState.nodes(newArrayList(update));
        removeExecutorMetadata(executorId);
    }

    @NotNull
    public Optional<String> getExecutorIdForTask(@NotNull final String taskId) {
        return headOption(
            from(clusterState.nodes())
                .filter(cassandraNodeForTaskId(taskId))
                .filter(cassandraNodeHasExecutor())
                .transform(executorIdFromCassandraNode())
        );
    }

    public void addExecutorMetadata(@NotNull final ExecutorMetadata executorMetadata) {
        clusterState.executorMetadata(append(
            clusterState.executorMetadata(),
            executorMetadata
        ));
    }

    void removeExecutorMetadata(@NotNull final String executorId) {
        final FluentIterable<ExecutorMetadata> update = from(clusterState.executorMetadata())
            .filter(not(new Predicate<ExecutorMetadata>() {
                @Override
                public boolean apply(final ExecutorMetadata input) {
                    return input.getExecutorId().equals(executorId);
                }
            }));
        clusterState.executorMetadata(newArrayList(update));
    }

    private boolean shouldRunHealthCheck(@NotNull final String executorID) {
        final Optional<Long> previousHealthCheckTime = headOption(
            from(healthCheckHistory.entries())
                .filter(healthCheckHistoryEntryExecutorIdEq(executorID))
                .transform(healthCheckHistoryEntryToTimestamp())
                .toSortedList(Collections.reverseOrder(naturalLongComparator))
        );

        if (configuration.healthCheckInterval().toDuration().getStandardSeconds() <= 0) {
            return false;
        }

        if (previousHealthCheckTime.isPresent()) {
            final Duration duration = new Duration(new Instant(previousHealthCheckTime.get()), clock.now());
            return duration.isLongerThan(configuration.healthCheckInterval());
        } else {
            return true;
        }
    }

    public HealthCheckHistoryEntry lastHealthCheck(@NotNull final String executorId) {
        return healthCheckHistory.last(executorId);
    }

    public void recordHealthCheck(@NotNull final String executorId, @NotNull final HealthCheckDetails details) {
        if (LOGGER.isDebugEnabled())
            LOGGER.debug("> recordHealthCheck(executorId : {}, details : {})", executorId, protoToString(details));
        if (!details.getHealthy()) {
            final Optional<CassandraNode> nodeOpt = headOption(
                from(clusterState.nodes())
                    .filter(cassandraNodeExecutorIdEq(executorId))
            );
            if (nodeOpt.isPresent()) {
                LOGGER.info(
                    "health check result unhealthy for node: {}. Message: '{}'",
                    nodeOpt.get().getCassandraNodeExecutor().getExecutorId(),
                    details.getMsg()
                    );
                // TODO: This needs to be smarter, right not it assumes that as soon as it's unhealth it's dead
                //removeTask(nodeOpt.get().getServerTask().getTaskId());
            }
        }
        healthCheckHistory.record(
            HealthCheckHistoryEntry.newBuilder()
                .setExecutorId(executorId)
                .setTimestamp(clock.now().getMillis())
                .setDetails(details)
                .build()
        );
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("< recordHealthCheck(executorId : {}, details : {})", executorId, protoToString(details));
        }
    }

    @NotNull
    public List<String> getSeedNodes() {
        return CassandraFrameworkProtosUtils.getSeedNodeIps(clusterState.nodes());
    }

    public TasksForOffer getTasksForOffer(@NotNull final Protos.Offer offer) {
        final Marker marker = MarkerFactory.getMarker("offerId:" + offer.getId().getValue() + ",hostname:" + offer.getHostname());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(marker, "> getTasksForOffer(offer : {})", protoToString(offer));
        }

        try {
            final Optional<CassandraNode> nodeOption = cassandraNodeForHostname(offer.getHostname());

            CassandraNode.Builder node;
            if (!nodeOption.isPresent()) {
                NodeCounts nodeCounts = clusterState.nodeCounts();
                if (nodeCounts.getNodeCount() >= configuration.numberOfNodes())
                    // number of C* cluster nodes already present
                    return null;

                boolean buildSeedNode = nodeCounts.getSeedCount() < configuration.numberOfSeeds();
                CassandraNode newNode = buildCassandraNode(offer, buildSeedNode);
                clusterState.nodes(append(
                        clusterState.nodes(),
                        newNode
                ));
                node = CassandraNode.newBuilder(newNode);
            } else
                node = CassandraNode.newBuilder(nodeOption.get());

            if (!node.hasCassandraNodeExecutor()) {
                final String executorId = getExecutorIdForOffer(offer);
                final CassandraNodeExecutor executor = getCassandraNodeExecutorSupplier(executorId);
                node.setCassandraNodeExecutor(executor);
            }

            TasksForOffer result = new TasksForOffer(node.getCassandraNodeExecutor());

            final CassandraNodeExecutor executor = node.getCassandraNodeExecutor();
            final String executorId = executor.getExecutorId();
            if (!node.hasMetadataTask()) {
                final CassandraNodeTask metadataTask = getMetadataTask(executorId, node.getIp());
                node.setMetadataTask(metadataTask);
                result.getLaunchTasks().add(metadataTask);
            } else {
                final Optional<ExecutorMetadata> maybeMetadata = getExecutorMetadata(executorId);
                if (maybeMetadata.isPresent()) {
                    if (!node.hasServerTask()) {
                        if (clusterState.nodeCounts().getSeedCount() < configuration.numberOfSeeds()) {
                            // we do not have enough executor metadata records to fulfil seed node requirement
                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug(marker, "Cannot launch non-seed node (seed node requirement not fulfilled)");
                            return null;
                        }

                        if (!canLaunchServerTask()) {
                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug(marker, "Cannot launch server (timed)");
                            return null;
                        }

                        if (!node.getSeed()) {
                            // when starting a non-seed node also check if at least one seed node is running
                            // (otherwise that node will fail to start)
                            boolean anySeedRunning = false;
                            boolean anyNodeInfluencingTopology = false;
                            for (CassandraNode cassandraNode : clusterState.nodes()) {
                                if (cassandraNode.hasServerTask()) {
                                    HealthCheckHistoryEntry lastHC = lastHealthCheck(cassandraNode.getCassandraNodeExecutor().getExecutorId());
                                    if (cassandraNode.getSeed()) {
                                        if (lastHC != null && lastHC.getDetails() != null && lastHC.getDetails().getInfo() != null
                                                && lastHC.getDetails().getHealthy()
                                                && lastHC.getDetails().getInfo().getJoined() && "NORMAL".equals(lastHC.getDetails().getInfo().getOperationMode()))
                                            anySeedRunning = true;
                                    }
                                    if (lastHC != null && lastHC.getDetails() != null && lastHC.getDetails().getInfo() != null
                                            && lastHC.getDetails().getHealthy()
                                            && (!lastHC.getDetails().getInfo().getJoined() || !"NORMAL".equals(lastHC.getDetails().getInfo().getOperationMode()))) {
                                        LOGGER.debug("Cannot start server task because of operation mode '{}' on node '{}'", lastHC.getDetails().getInfo().getOperationMode(), cassandraNode.getHostname());
                                        anyNodeInfluencingTopology = true;
                                    }
                                }
                            }
                            if (!anySeedRunning) {
                                LOGGER.debug("Cannot start server task because no seed node is running");
                                return null;
                            }
                            if (anyNodeInfluencingTopology) {
                                return null;
                            }
                        }

                        CassandraFrameworkConfiguration config = configuration.get();
                        final List<String> errors = hasResources(
                                offer,
                                config.getCpuCores(),
                                config.getMemMb(),
                                config.getDiskMb(),
                                portMappings(config)
                        );
                        if (!errors.isEmpty()) {
                            LOGGER.info(marker, "Insufficient resources in offer: {}. Details: ['{}']", offer.getId().getValue(), JOINER.join(errors));
                        } else {
                            final String taskId = node.getCassandraNodeExecutor().getExecutorId() + ".server";
                            final ExecutorMetadata metadata = maybeMetadata.get();
                            final CassandraNodeTask task = getServerTask(executorId, taskId, metadata, node);
                            node.setServerTask(task);
                            result.getLaunchTasks().add(task);

                            clusterState.updateLastServerLaunchTimestamp(clock.now().getMillis());
                        }
                    } else {
                        if (shouldRunHealthCheck(executorId)) {
                            result.getSubmitTasks().add(getHealthCheckTaskDetails());
                        }

                        handleClusterTask(executorId, result);
                    }
                }
            }

            if (!result.hasAnyTask())
                // nothing to do
                return null;

            final CassandraNode built = node.build();
            clusterState.addOrSetNode(built);

            return result;
        } finally {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug(marker, "< getTasksForOffer(offer : {}) = {}, {}", protoToString(offer));
        }
    }

    private boolean canLaunchServerTask() {
        return clock.now().getMillis() > nextPossibleServerLaunchTimestamp();
    }

    public long nextPossibleServerLaunchTimestamp() {
        long lastServerLaunchTimestamp = getClusterState().get().getLastServerLaunchTimestamp();
        long seconds = Math.max(getConfiguration().get().getBootstrapGraceTimeSeconds(), getConfiguration().get().getHealthCheckIntervalSeconds());
        return lastServerLaunchTimestamp + seconds * 1000L;
    }

    @NotNull
    public Optional<CassandraNode> cassandraNodeForHostname(String hostname) {
        return headOption(
            from(clusterState.nodes())
                .filter(cassandraNodeHostnameEq(hostname))
        );
    }

    @NotNull
    public Optional<CassandraNode> cassandraNodeForExecutorId(String executorId) {
        return headOption(
                from(clusterState.nodes())
                        .filter(cassandraNodeExecutorIdEq(executorId))
        );
    }

    private CassandraNode buildCassandraNode(Protos.Offer offer, boolean seed) {
        CassandraNode.Builder builder = CassandraNode.newBuilder()
                .setHostname(offer.getHostname())
                .setSeed(seed);
        try {
            InetAddress ia = InetAddress.getByName(offer.getHostname());

            int jmxPort = getPortMapping(PORT_JMX);
            if (ia.isLoopbackAddress()) {
                try (ServerSocket serverSocket = new ServerSocket(0)) {
                    jmxPort = serverSocket.getLocalPort();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            return builder.setIp(ia.getHostAddress())
                    .setJmxConnect(JmxConnect.newBuilder()
                                    .setIp("127.0.0.1")
                                    .setJmxPort(jmxPort)
                            // TODO JMX auth parameters go here
                    )
                    .build();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private int getPortMapping(String name) {
        return getPortMapping(configuration.get(), name);
    }

    private static Map<String, Long> portMappings(CassandraFrameworkConfiguration config) {
        Map<String, Long> r = new HashMap<>();
        for (String name : defaultPortMappings.keySet()) {
            r.put(name, (long) getPortMapping(config, name));
        }
        return r;
    }

    @NotNull
    private String getExecutorIdForOffer(@NotNull final Protos.Offer offer) {
        final FluentIterable<CassandraNode> filter =
            from(clusterState.nodes())
                .filter(cassandraNodeHasExecutor())
                .filter(cassandraNodeHostnameEq(offer.getHostname()));
        if (filter.isEmpty()) {
            return configuration.frameworkName() + ".node." + execCounter.getAndIncrement() + ".executor";
        } else {
            return filter.get(0).getCassandraNodeExecutor().getExecutorId();
        }
    }

    @NotNull
    private String getUrlForResource(@NotNull final String resourceName) {
        return URL_FOR_RESOURCE_REPLACE.matcher((httpServerBaseUrl + '/' + resourceName)).replaceAll("/");
    }

    @NotNull
    private CassandraNodeTask getServerTask(
            @NotNull final String executorId,
            @NotNull final String taskId,
            @NotNull final ExecutorMetadata metadata,
            @NotNull final CassandraNode.Builder node) {
        CassandraFrameworkConfiguration config = configuration.get();
        final TaskConfig taskConfig = TaskConfig.newBuilder()
            .addVariables(configValue("cluster_name", config.getFrameworkName()))
            .addVariables(configValue("broadcast_address", metadata.getIp()))
            .addVariables(configValue("rpc_address", metadata.getIp()))
            .addVariables(configValue("listen_address", metadata.getIp()))
            .addVariables(configValue("storage_port", getPortMapping(config, PORT_STORAGE)))
            .addVariables(configValue("ssl_storage_port", getPortMapping(config, PORT_STORAGE_SSL)))
            .addVariables(configValue("native_transport_port", getPortMapping(config, PORT_NATIVE)))
            .addVariables(configValue("rpc_port", getPortMapping(config, PORT_RPC)))
            .addVariables(configValue("seeds", SEEDS_FORMAT_JOINER.join(getSeedNodes())))
            .build();
        final TaskDetails taskDetails = TaskDetails.newBuilder()
            .setTaskType(TaskDetails.TaskType.CASSANDRA_SERVER_RUN)
            .setCassandraServerRunTask(
                    CassandraServerRunTask.newBuilder()
                            // we want to know the PID of Cassandra process
                            // have to start it in foreground in order to be able to detect runtime status in the executor
                            .addAllCommand(newArrayList("apache-cassandra-" + config.getCassandraVersion() + "/bin/cassandra", "-p", "cassandra.pid", "-f"))
                            .setTaskConfig(taskConfig)
                            .setVersion(config.getCassandraVersion())
                            .setTaskEnv(taskEnv(
                                    // see conf/cassandra-env.sh in the cassandra distribution for details
                                    // about these variables.
                                    tuple2("JMX_PORT", String.valueOf(node.getJmxConnect().getJmxPort())),
                                    tuple2("MAX_HEAP_SIZE", config.getMemMb() + "m"),
                                    // The example HEAP_NEWSIZE assumes a modern 8-core+ machine for decent pause
                                    // times. If in doubt, and if you do not particularly want to tweak, go with
                                    // 100 MB per physical CPU core.
                                    tuple2("HEAP_NEWSIZE", (int) (config.getCpuCores() * 100) + "m")
                            ))
                            .setJmx(node.getJmxConnect())
            )
                .build();

        return CassandraNodeTask.newBuilder()
            .setTaskId(taskId)
            .setExecutorId(executorId)
            .setCpuCores(configuration.cpuCores())
            .setMemMb(configuration.memMb())
            .setDiskMb(configuration.diskMb())
            .addAllPorts(portMappings(config).values())
            .setTaskDetails(taskDetails)
            .build();
    }

    @NotNull
    private Optional<ExecutorMetadata> getExecutorMetadata(@NotNull final String executorId) {
        final FluentIterable<ExecutorMetadata> filter = from(clusterState.executorMetadata())
            .filter(executorMetadataExecutorIdEq(executorId));
        return headOption(filter);
    }

    @NotNull
    private CassandraNodeExecutor getCassandraNodeExecutorSupplier(@NotNull final String executorId) {
        String osName = Env.option("OS_NAME").or(Env.osFromSystemProperty());
        String javaExec = "macosx".equals(osName)
            ? "$(pwd)/jre*/Contents/Home/bin/java"
            : "$(pwd)/jre*/bin/java";

        return CassandraNodeExecutor.newBuilder()
            .setExecutorId(executorId)
            .setSource(configuration.frameworkName())
            .setCpuCores(0.1)
            .setMemMb(16)
            .setDiskMb(16)
            .setCommand(javaExec)
//            .addCommandArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
            .addCommandArgs("-XX:+PrintCommandLineFlags")
            .addCommandArgs("$JAVA_OPTS")
            .addCommandArgs("-classpath")
            .addCommandArgs("cassandra-executor.jar")
            .addCommandArgs("io.mesosphere.mesos.frameworks.cassandra.CassandraExecutor")
            .setTaskEnv(taskEnvFromMap(executorEnv))
            .addAllResource(newArrayList(
                resourceUri(getUrlForResource("/jre-7-" + osName + ".tar.gz"), true),
                resourceUri(getUrlForResource("/apache-cassandra-" + configuration.cassandraVersion() + "-bin.tar.gz"), true),
                resourceUri(getUrlForResource("/cassandra-executor.jar"), false)
            ))
            .build();
    }

    private static TaskDetails getHealthCheckTaskDetails() {
        return TaskDetails.newBuilder()
            .setTaskType(TaskDetails.TaskType.HEALTH_CHECK)
            .setHealthCheckTask(HealthCheckTask.getDefaultInstance())
            .build();
    }

    @NotNull
    private CassandraNodeTask getMetadataTask(@NotNull final String executorId, String ip) {
        final TaskDetails taskDetails = TaskDetails.newBuilder()
            .setTaskType(TaskDetails.TaskType.EXECUTOR_METADATA)
            .setExecutorMetadataTask(
                    ExecutorMetadataTask.newBuilder()
                            .setExecutorId(executorId)
                            .setIp(ip)
            )
            .build();
        return CassandraNodeTask.newBuilder()
            .setTaskId(executorId)
            .setExecutorId(executorId)
            .setCpuCores(0.1)
            .setMemMb(16)
            .setDiskMb(16)
            .setTaskDetails(taskDetails)
            .build();
    }

    @NotNull
    private static List<String> hasResources(
        @NotNull final Protos.Offer offer,
        final double cpu,
        final long mem,
        final long disk,
        @NotNull final Map<String, Long> portMapping
    ) {
        final List<String> errors = newArrayList();
        final ListMultimap<String, Protos.Resource> index = from(offer.getResourcesList()).index(resourceToName());
        final Double availableCpus = resourceValueDouble(headOption(index.get("cpus"))).or(0.0);
        final Long availableMem = resourceValueLong(headOption(index.get("mem"))).or(0L);
        final Long availableDisk = resourceValueLong(headOption(index.get("disk"))).or(0L);
        if (availableCpus <= cpu) {
            errors.add(String.format("Not enough cpu resources. Required %f only %f available.", cpu, availableCpus));
        }
        if (availableMem <= mem) {
            errors.add(String.format("Not enough mem resources. Required %d only %d available", mem, availableMem));
        }
        if (availableDisk <= disk) {
            errors.add(String.format("Not enough disk resources. Required %d only %d available", disk, availableDisk));
        }

        final TreeSet<Long> ports = resourceValueRange(headOption(index.get("ports")));
        for (final Map.Entry<String, Long> entry : portMapping.entrySet()) {
            final String key = entry.getKey();
            final Long value = entry.getValue();
            if (!ports.contains(value)) {
                errors.add(String.format("Unavailable port %d(%s). %d other ports available.", value, key, ports.size()));
            }
        }
        return errors;
    }

    public int updateNodeCount(int nodeCount) {
        try {
            configuration.numberOfNodes(nodeCount);
        } catch (IllegalArgumentException e) {
            LOGGER.info("Cannout update number-of-nodes", e);
        }
        return configuration.numberOfNodes();
    }

    // cluster tasks

    private void handleClusterTask(String executorId, TasksForOffer tasksForOffer) {
        ClusterJobStatus currentTask = getCurrentClusterJob();
        if (currentTask == null)
            return;

        if (currentTask.hasCurrentNode()) {
            NodeJobStatus node = currentTask.getCurrentNode();
            if (executorId.equals(node.getExecutorId())) {
                // submit status request
                tasksForOffer.getSubmitTasks().add(TaskDetails.newBuilder()
                    .setTaskType(TaskDetails.TaskType.NODE_JOB_STATUS)
                    .build());

                LOGGER.info("Inquiring cluster job status for {} from {}", currentTask.getJobType().name(),
                        node.getExecutorId());

                return;
            }
            return;
        }

        if (currentTask.getAborted() && !currentTask.hasCurrentNode()) {
            jobsState.currentJob(null);
            // TODO record aborted job in history??
            return;
        }

        if (!currentTask.hasCurrentNode()) {
            List<String> remainingNodes = new ArrayList<>(currentTask.getRemainingNodesList());
            if (remainingNodes.isEmpty()) {
                jobsState.finishJob(currentTask);
                return;
            }

            if (!remainingNodes.remove(executorId)) {
                return;
            }

            Optional<CassandraNode> nextNode = cassandraNodeForExecutorId(executorId);
            if (!nextNode.isPresent()) {
                currentTask = ClusterJobStatus.newBuilder()
                        .clearRemainingNodes()
                        .addAllRemainingNodes(remainingNodes)
                        .build();
                jobsState.currentJob(currentTask);
                return;
            }


            final TaskDetails taskDetails = TaskDetails.newBuilder()
                    .setTaskType(TaskDetails.TaskType.NODE_JOB)
                    .setNodeJobTask(NodeJobTask.newBuilder().setJobType(currentTask.getJobType()))
                    .build();
            CassandraNodeTask cassandraNodeTask = CassandraNodeTask.newBuilder()
                    .setTaskId(executorId + '.' + currentTask.getJobType().name())
                    .setExecutorId(executorId)
                    .setCpuCores(0.1)
                    .setMemMb(16)
                    .setDiskMb(16)
                    .setTaskDetails(taskDetails)
                    .build();
            tasksForOffer.getLaunchTasks().add(cassandraNodeTask);

            NodeJobStatus currentNode = NodeJobStatus.newBuilder()
                    .setExecutorId(cassandraNodeTask.getExecutorId())
                    .setTaskId(cassandraNodeTask.getTaskId())
                    .setJobType(currentTask.getJobType())
                    .setTaskId(cassandraNodeTask.getTaskId())
                    .setStartedTimestamp(clock.now().getMillis())
                    .build();
            jobsState.nextNode(currentTask, currentNode);

            LOGGER.info("Starting cluster job {} on {}/{}", currentTask.getJobType().name(), nextNode.get().getIp(),
                    nextNode.get().getHostname());
        }
    }

    public void onNodeJobStatus(SlaveStatusDetails statusDetails) {
        ClusterJobStatus currentTask = getCurrentClusterJob();
        if (currentTask == null) {
            return;
        }

        if (!statusDetails.hasNodeJobStatus()) {
            // TODO add some failure handling here
            return;
        }

        NodeJobStatus nodeJobStatus = statusDetails.getNodeJobStatus();

        if (currentTask.getJobType() != nodeJobStatus.getJobType()) {
            // oops - status message of other type...  ignore for now
            LOGGER.warn("Got node job status of tye {} - but expected {}", nodeJobStatus.getJobType(), currentTask.getJobType());
            return;
        }

        LOGGER.info("Got node job status from {}, running={}", nodeJobStatus.getExecutorId(), nodeJobStatus.getRunning());

        jobsState.updateNodeStatus(currentTask, nodeJobStatus);
    }

    public boolean startClusterTask(ClusterJobType jobType) {
        if (jobsState.get().hasCurrentClusterJob())
            return false;

        ClusterJobStatus.Builder builder = ClusterJobStatus.newBuilder()
                .setJobType(jobType)
                .setStartedTimestamp(clock.now().getMillis());

        for (CassandraNode cassandraNode : clusterState.nodes()) {
            builder.addRemainingNodes(cassandraNode.getCassandraNodeExecutor().getExecutorId());
        }

        jobsState.currentJob(builder.build());
        return true;
    }

    public boolean abortClusterJob(ClusterJobType jobType) {
        ClusterJobStatus current = getCurrentClusterJob(jobType);
        if (current == null || current.getAborted())
            return false;

        current = ClusterJobStatus.newBuilder(current)
                .setAborted(true).build();
        jobsState.currentJob(current);
        return true;
    }

    public ClusterJobStatus getCurrentClusterJob() {
        CassandraClusterJobs jobState = jobsState.get();
        return jobState.hasCurrentClusterJob() ? jobState.getCurrentClusterJob() : null;
    }

    public ClusterJobStatus getCurrentClusterJob(ClusterJobType jobType) {
        ClusterJobStatus current = getCurrentClusterJob();
        return current != null && current.getJobType() == jobType ? current : null;
    }

    public ClusterJobStatus getLastClusterJob(ClusterJobType jobType) {
        List<ClusterJobStatus> list = jobsState.get().getLastClusterJobsList();
        if (list == null)
            return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            ClusterJobStatus clusterJobStatus = list.get(i);
            if (clusterJobStatus.getJobType() == jobType)
                return clusterJobStatus;
        }
        return null;
    }
}