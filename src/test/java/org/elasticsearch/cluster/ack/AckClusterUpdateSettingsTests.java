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

package org.elasticsearch.cluster.ack;

import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.Test;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.TEST;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

@ClusterScope(scope = TEST)
public class AckClusterUpdateSettingsTests extends ElasticsearchIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        //to test that the acknowledgement mechanism is working we better disable the wait for publish
        //otherwise the operation is most likely acknowledged even if it doesn't support ack
        return ImmutableSettings.builder().put("discovery.zen.publish_timeout", 0).build();
    }

    @Test
    public void testClusterUpdateSettingsAcknowledgement() {
        client().admin().indices().prepareCreate("test")
                .setSettings(settingsBuilder()
                        .put("number_of_shards", atLeast(cluster().size()))
                        .put("number_of_replicas", 0)).get();
        ensureGreen();

        NodesInfoResponse nodesInfo = client().admin().cluster().prepareNodesInfo().get();
        String excludedNodeId = null;
        for (NodeInfo nodeInfo : nodesInfo) {
            if (nodeInfo.getNode().isDataNode()) {
                excludedNodeId = nodesInfo.getAt(0).getNode().id();
                break;
            }
        }
        assert excludedNodeId != null;

        ClusterUpdateSettingsResponse clusterUpdateSettingsResponse = client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(settingsBuilder().put("cluster.routing.allocation.exclude._id", excludedNodeId)).get();
        assertAcked(clusterUpdateSettingsResponse);
        assertThat(clusterUpdateSettingsResponse.getTransientSettings().get("cluster.routing.allocation.exclude._id"), equalTo(excludedNodeId));

        for (Client client : clients()) {
            ClusterState clusterState = getLocalClusterState(client);
            assertThat(clusterState.routingNodes().metaData().transientSettings().get("cluster.routing.allocation.exclude._id"), equalTo(excludedNodeId));
            for (IndexRoutingTable indexRoutingTable : clusterState.routingTable()) {
                for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                    for (ShardRouting shardRouting : indexShardRoutingTable) {
                        if (clusterState.nodes().get(shardRouting.currentNodeId()).id().equals(excludedNodeId)) {
                            //if the shard is still there it must be relocating and all nodes need to know, since the request was acknowledged
                            assertThat(shardRouting.relocating(), equalTo(true));
                        }
                    }
                }
            }
        }

        //let's wait for the relocation to be completed, otherwise there can be issues with after test checks (mock directory wrapper etc.)
        waitForRelocation();

        //removes the allocation exclude settings
        client().admin().cluster().prepareUpdateSettings().setTransientSettings(settingsBuilder().put("cluster.routing.allocation.exclude._id", "")).get();
    }

    @Test
    public void testClusterUpdateSettingsNoAcknowledgement() {
        client().admin().indices().prepareCreate("test")
                .setSettings(settingsBuilder()
                        .put("number_of_shards", atLeast(cluster().size()))
                        .put("number_of_replicas", 0)).get();
        ensureGreen();

        NodesInfoResponse nodesInfo = client().admin().cluster().prepareNodesInfo().get();
        String excludedNodeId = null;
        for (NodeInfo nodeInfo : nodesInfo) {
            if (nodeInfo.getNode().isDataNode()) {
                excludedNodeId = nodesInfo.getAt(0).getNode().id();
                break;
            }
        }
        assert excludedNodeId != null;

        ClusterUpdateSettingsResponse clusterUpdateSettingsResponse = client().admin().cluster().prepareUpdateSettings().setTimeout("0s")
                .setTransientSettings(settingsBuilder().put("cluster.routing.allocation.exclude._id", excludedNodeId)).get();
        assertThat(clusterUpdateSettingsResponse.isAcknowledged(), equalTo(false));
        assertThat(clusterUpdateSettingsResponse.getTransientSettings().get("cluster.routing.allocation.exclude._id"), equalTo(excludedNodeId));

        //let's wait for the relocation to be completed, otherwise there can be issues with after test checks (mock directory wrapper etc.)
        waitForRelocation();

        //removes the allocation exclude settings
        client().admin().cluster().prepareUpdateSettings().setTransientSettings(settingsBuilder().put("cluster.routing.allocation.exclude._id", "")).get();
    }

    private static ClusterState getLocalClusterState(Client client) {
        return client.admin().cluster().prepareState().setLocal(true).get().getState();
    }
}