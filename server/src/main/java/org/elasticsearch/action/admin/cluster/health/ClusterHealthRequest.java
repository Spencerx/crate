/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.cluster.health;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.Version;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.MasterNodeReadRequest;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import io.crate.common.unit.TimeValue;

public class ClusterHealthRequest extends MasterNodeReadRequest<ClusterHealthRequest> {

    private TimeValue timeout = new TimeValue(30, TimeUnit.SECONDS);
    private ClusterHealthStatus waitForStatus;
    private boolean waitForNoRelocatingShards = false;
    private boolean waitForNoInitializingShards = false;
    private ActiveShardCount waitForActiveShards = ActiveShardCount.NONE;
    private String waitForNodes = "";
    private Priority waitForEvents = null;
    /**
     * Only used by the high-level REST Client. Controls the details level of the health information returned.
     * The default value is 'shards' so it is backward compatible with the transport client behaviour.
     */
    private Level level = Level.SHARDS;

    public ClusterHealthRequest() {
    }

    public TimeValue timeout() {
        return timeout;
    }

    public ClusterHealthRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        if (masterNodeTimeout == DEFAULT_MASTER_NODE_TIMEOUT) {
            masterNodeTimeout = timeout;
        }
        return this;
    }

    public ClusterHealthRequest timeout(String timeout) {
        return this.timeout(TimeValue.parseTimeValue(timeout, null, getClass().getSimpleName() + ".timeout"));
    }

    public ClusterHealthStatus waitForStatus() {
        return waitForStatus;
    }

    public ClusterHealthRequest waitForStatus(ClusterHealthStatus waitForStatus) {
        this.waitForStatus = waitForStatus;
        return this;
    }

    public ClusterHealthRequest waitForGreenStatus() {
        return waitForStatus(ClusterHealthStatus.GREEN);
    }

    public ClusterHealthRequest waitForYellowStatus() {
        return waitForStatus(ClusterHealthStatus.YELLOW);
    }

    public boolean waitForNoRelocatingShards() {
        return waitForNoRelocatingShards;
    }

    /**
     * Sets whether the request should wait for there to be no relocating shards before
     * retrieving the cluster health status.  Defaults to {@code false}, meaning the
     * operation does not wait on there being no more relocating shards.  Set to <code>true</code>
     * to wait until the number of relocating shards in the cluster is 0.
     */
    public ClusterHealthRequest waitForNoRelocatingShards(boolean waitForNoRelocatingShards) {
        this.waitForNoRelocatingShards = waitForNoRelocatingShards;
        return this;
    }

    public boolean waitForNoInitializingShards() {
        return waitForNoInitializingShards;
    }

    /**
     * Sets whether the request should wait for there to be no initializing shards before
     * retrieving the cluster health status.  Defaults to {@code false}, meaning the
     * operation does not wait on there being no more initializing shards.  Set to <code>true</code>
     * to wait until the number of initializing shards in the cluster is 0.
     */
    public ClusterHealthRequest waitForNoInitializingShards(boolean waitForNoInitializingShards) {
        this.waitForNoInitializingShards = waitForNoInitializingShards;
        return this;
    }

    public ActiveShardCount waitForActiveShards() {
        return waitForActiveShards;
    }

    /**
     * Sets the number of shard copies that must be active across all indices before getting the
     * health status. Defaults to {@link ActiveShardCount#NONE}, meaning we don't wait on any active shards.
     * Set this value to {@link ActiveShardCount#ALL} to wait for all shards (primary and
     * all replicas) to be active across all indices in the cluster. Otherwise, use
     * {@link ActiveShardCount#from(int)} to set this value to any non-negative integer, up to the
     * total number of shard copies to wait for.
     */
    public ClusterHealthRequest waitForActiveShards(ActiveShardCount waitForActiveShards) {
        if (waitForActiveShards.equals(ActiveShardCount.DEFAULT)) {
            // the default for cluster health request is 0, not 1
            this.waitForActiveShards = ActiveShardCount.NONE;
        } else {
            this.waitForActiveShards = waitForActiveShards;
        }
        return this;
    }

    /**
     * A shortcut for {@link #waitForActiveShards(ActiveShardCount)} where the numerical
     * shard count is passed in, instead of having to first call {@link ActiveShardCount#from(int)}
     * to get the ActiveShardCount.
     */
    public ClusterHealthRequest waitForActiveShards(final int waitForActiveShards) {
        return waitForActiveShards(ActiveShardCount.from(waitForActiveShards));
    }

    public String waitForNodes() {
        return waitForNodes;
    }

    /**
     * Waits for N number of nodes. Use "12" for exact mapping, "&gt;12" and "&lt;12" for range.
     */
    public ClusterHealthRequest waitForNodes(String waitForNodes) {
        this.waitForNodes = waitForNodes;
        return this;
    }

    public ClusterHealthRequest waitForEvents(Priority waitForEvents) {
        this.waitForEvents = waitForEvents;
        return this;
    }

    public Priority waitForEvents() {
        return this.waitForEvents;
    }

    /**
     * Set the level of detail for the health information to be returned.
     * Only used by the high-level REST Client
     * The default value is 'shards' so it is backward compatible with the transport client behaviour.
     */
    public void level(Level level) {
        this.level = Objects.requireNonNull(level, "level must not be null");
    }

    /**
     * Get the level of detail for the health information to be returned.
     * Only used by the high-level REST Client.
     * The default value is 'shards' so it is backward compatible with the transport client behaviour.
     */
    public Level level() {
        return level;
    }

    public ClusterHealthRequest(StreamInput in) throws IOException {
        super(in);
        if (in.getVersion().before(Version.V_6_0_0)) {
            // was indices array before
            in.readStringArray();
        }
        timeout = in.readTimeValue();
        if (in.readBoolean()) {
            waitForStatus = ClusterHealthStatus.fromValue(in.readByte());
        }
        waitForNoRelocatingShards = in.readBoolean();
        waitForActiveShards = ActiveShardCount.readFrom(in);
        waitForNodes = in.readString();
        if (in.readBoolean()) {
            waitForEvents = Priority.readFrom(in);
        }
        waitForNoInitializingShards = in.readBoolean();
        if (in.getVersion().before(Version.V_6_0_0)) {
            IndicesOptions.readIndicesOptions(in);
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        if (out.getVersion().before(Version.V_6_0_0)) {
            out.writeStringArray(Strings.EMPTY_ARRAY);
        }
        out.writeTimeValue(timeout);
        if (waitForStatus == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeByte(waitForStatus.value());
        }
        out.writeBoolean(waitForNoRelocatingShards);
        waitForActiveShards.writeTo(out);
        out.writeString(waitForNodes);
        if (waitForEvents == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            Priority.writeTo(waitForEvents, out);
        }
        out.writeBoolean(waitForNoInitializingShards);
        if (out.getVersion().before(Version.V_6_0_0)) {
            IndicesOptions.LENIENT_EXPAND_OPEN_CLOSED.writeIndicesOptions(out);
        }
    }

    public enum Level {
        CLUSTER, INDICES, SHARDS
    }
}
