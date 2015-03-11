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

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.protobuf.InvalidProtocolBufferException;
import io.mesosphere.mesos.util.ProtoUtils;
import org.apache.mesos.Protos;
import org.apache.mesos.state.State;
import org.jetbrains.annotations.NotNull;

final class PersistedCassandraClusterJobs extends StatePersistedObject<CassandraFrameworkProtos.CassandraClusterJobs> {
    public PersistedCassandraClusterJobs(@NotNull final State state) {
        super(
            "CassandraClusterJobs",
            state,
            new Supplier<CassandraFrameworkProtos.CassandraClusterJobs>() {
                @Override
                public CassandraFrameworkProtos.CassandraClusterJobs get() {
                    return CassandraFrameworkProtos.CassandraClusterJobs.newBuilder().build();
                }
            },
            new Function<byte[], CassandraFrameworkProtos.CassandraClusterJobs>() {
                @Override
                public CassandraFrameworkProtos.CassandraClusterJobs apply(final byte[] input) {
                    try {
                        return CassandraFrameworkProtos.CassandraClusterJobs.parseFrom(input);
                    } catch (InvalidProtocolBufferException e) {
                        throw new ProtoUtils.RuntimeInvalidProtocolBufferException(e);
                    }
                }
            },
            new Function<CassandraFrameworkProtos.CassandraClusterJobs, byte[]>() {
                @Override
                public byte[] apply(final CassandraFrameworkProtos.CassandraClusterJobs input) {
                    return input.toByteArray();
                }
            }
        );
    }

    public void currentJob(CassandraFrameworkProtos.ClusterJobStatus current) {
        CassandraFrameworkProtos.CassandraClusterJobs.Builder builder = CassandraFrameworkProtos.CassandraClusterJobs.newBuilder(get());
        if (current == null)
            builder.clearCurrentClusterJob();
        else
            builder.setCurrentClusterJob(current);
        setValue(builder.build());
    }

    public void updateNodeStatus(CassandraFrameworkProtos.ClusterJobStatus currentJob, CassandraFrameworkProtos.NodeJobStatus nodeJobStatus) {
        CassandraFrameworkProtos.ClusterJobStatus.Builder builder = CassandraFrameworkProtos.ClusterJobStatus.newBuilder(currentJob);

        if (currentJob.getCurrentNode() != null && currentJob.getCurrentNode().getExecutorId().equals(nodeJobStatus.getExecutorId())) {
            if (nodeJobStatus.getRunning())
                currentJob(builder
                        .setCurrentNode(nodeJobStatus)
                        .build());
            else {
                CassandraFrameworkProtos.ClusterJobStatus.Builder jobUpdate = builder
                        .clearCurrentNode()
                        .addCompletedNodes(nodeJobStatus);
                if (jobUpdate.getRemainingNodesCount() == 0)
                    finishJob(jobUpdate
                            .setFinishedTimestamp(System.currentTimeMillis())
                            .build());
                else
                    currentJob(jobUpdate.build());
            }
        }
    }

    public void removeTaskForCurrentNode(Protos.TaskStatus status, CassandraFrameworkProtos.ClusterJobStatus currentJob) {
        CassandraFrameworkProtos.ClusterJobStatus.Builder builder = CassandraFrameworkProtos.ClusterJobStatus.newBuilder(currentJob);

        CassandraFrameworkProtos.NodeJobStatus.Builder currentNode = CassandraFrameworkProtos.NodeJobStatus.newBuilder(builder.getCurrentNode())
                .setFailed(true)
                .setFailureMessage(
                        "TaskStatus:" + status.getState()
                        + ", reason:" + status.getReason()
                        + ", source:" + status.getSource()
                        + ", healthy:" + status.getHealthy()
                        + ", message:" + status.getMessage()
                );

        currentJob(builder.addCompletedNodes(currentNode)
                .clearCurrentNode()
                .build());
    }

    public void nextNode(CassandraFrameworkProtos.ClusterJobStatus currentTask, CassandraFrameworkProtos.NodeJobStatus currentNode) {
        CassandraFrameworkProtos.ClusterJobStatus.Builder builder = CassandraFrameworkProtos.ClusterJobStatus.newBuilder(currentTask)
                .clearRemainingNodes()
                .setCurrentNode(currentNode);

        for (String nodeExecutorId : currentTask.getRemainingNodesList()) {
            if (!nodeExecutorId.equals(currentNode.getExecutorId()))
                builder.addRemainingNodes(nodeExecutorId);
        }

        currentJob(builder.build());
    }

    public void finishJob(CassandraFrameworkProtos.ClusterJobStatus currentTask) {
        CassandraFrameworkProtos.CassandraClusterJobs.Builder clusterJobsBuilder = CassandraFrameworkProtos.CassandraClusterJobs.newBuilder()
                .addLastClusterJobs(currentTask);
        for (CassandraFrameworkProtos.ClusterJobStatus clusterJobStatus : get().getLastClusterJobsList())
            if (clusterJobStatus.getJobType() != currentTask.getJobType())
                clusterJobsBuilder.addLastClusterJobs(clusterJobStatus);

        setValue(clusterJobsBuilder.build());
    }
}