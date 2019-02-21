/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr;

import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.CcrIntegTestCase;
import org.elasticsearch.xpack.core.ccr.action.FollowStatsAction;
import org.elasticsearch.xpack.core.ccr.action.PutFollowAction;
import org.elasticsearch.xpack.core.ccr.client.CcrClient;
import org.hamcrest.Matchers;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;

@TestLogging("org.elasticsearch.xpack.ccr:TRACE,org.elasticsearch.index.shard:DEBUG")
public class FollowerFailOverIT extends CcrIntegTestCase {

    @Override
    protected boolean reuseClusters() {
        return false;
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/38633")
    public void testFailOverOnFollower() throws Exception {
        int numberOfReplicas = between(1, 2);
        getFollowerCluster().startMasterOnlyNode();
        getFollowerCluster().ensureAtLeastNumDataNodes(numberOfReplicas + between(1, 2));
        String leaderIndexSettings = getIndexSettings(1, numberOfReplicas,
            singletonMap(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), "true"));
        assertAcked(leaderClient().admin().indices().prepareCreate("leader-index").setSource(leaderIndexSettings, XContentType.JSON));
        AtomicBoolean stopped = new AtomicBoolean();
        Thread[] threads = new Thread[between(1, 8)];
        AtomicInteger docID = new AtomicInteger();
        Semaphore availableDocs = new Semaphore(0);
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                while (stopped.get() == false) {
                    try {
                        if (availableDocs.tryAcquire(10, TimeUnit.MILLISECONDS) == false) {
                            continue;
                        }
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                    if (frequently()) {
                        String id = Integer.toString(frequently() ? docID.incrementAndGet() : between(0, 10)); // sometimes update
                        IndexResponse indexResponse = leaderClient().prepareIndex("leader-index", "doc", id)
                            .setSource("{\"f\":" + id + "}", XContentType.JSON).get();
                        logger.info("--> index id={} seq_no={}", indexResponse.getId(), indexResponse.getSeqNo());
                    } else {
                        String id = Integer.toString(between(0, docID.get()));
                        DeleteResponse deleteResponse = leaderClient().prepareDelete("leader-index", "doc", id).get();
                        logger.info("--> delete id={} seq_no={}", deleteResponse.getId(), deleteResponse.getSeqNo());
                    }
                }
            });
            threads[i].start();
        }
        availableDocs.release(between(100, 200));
        PutFollowAction.Request follow = putFollow("leader-index", "follower-index");
        follow.getParameters().setMaxReadRequestOperationCount(randomIntBetween(32, 2048));
        follow.getParameters().setMaxReadRequestSize(new ByteSizeValue(randomIntBetween(1, 4096), ByteSizeUnit.KB));
        follow.getParameters().setMaxOutstandingReadRequests(randomIntBetween(1, 10));
        follow.getParameters().setMaxWriteRequestOperationCount(randomIntBetween(32, 2048));
        follow.getParameters().setMaxWriteRequestSize(new ByteSizeValue(randomIntBetween(1, 4096), ByteSizeUnit.KB));
        follow.getParameters().setMaxOutstandingWriteRequests(randomIntBetween(1, 10));
        logger.info("--> follow request {}", Strings.toString(follow));
        followerClient().execute(PutFollowAction.INSTANCE, follow).get();
        disableDelayedAllocation("follower-index");
        ensureFollowerGreen("follower-index");
        awaitGlobalCheckpointAtLeast(followerClient(), new ShardId(resolveFollowerIndex("follower-index"), 0), between(30, 80));
        final ClusterState clusterState = getFollowerCluster().clusterService().state();
        for (ShardRouting shardRouting : clusterState.routingTable().allShards("follower-index")) {
            if (shardRouting.primary()) {
                DiscoveryNode assignedNode = clusterState.nodes().get(shardRouting.currentNodeId());
                getFollowerCluster().restartNode(assignedNode.getName(), new InternalTestCluster.RestartCallback());
                break;
            }
        }
        availableDocs.release(between(50, 200));
        ensureFollowerGreen("follower-index");
        availableDocs.release(between(50, 200));
        awaitGlobalCheckpointAtLeast(followerClient(), new ShardId(resolveFollowerIndex("follower-index"), 0), between(100, 150));
        stopped.set(true);
        for (Thread thread : threads) {
            thread.join();
        }
        assertIndexFullyReplicatedToFollower("leader-index", "follower-index");
        pauseFollow("follower-index");
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/33337")
    public void testFollowIndexAndCloseNode() throws Exception {
        getFollowerCluster().ensureAtLeastNumDataNodes(3);
        String leaderIndexSettings = getIndexSettings(3, 1, singletonMap(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), "true"));
        assertAcked(leaderClient().admin().indices().prepareCreate("index1").setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen("index1");

        AtomicBoolean run = new AtomicBoolean(true);
        Semaphore availableDocs = new Semaphore(0);
        Thread thread = new Thread(() -> {
            int counter = 0;
            while (run.get()) {
                try {
                    if (availableDocs.tryAcquire(10, TimeUnit.MILLISECONDS) == false) {
                        continue;
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                final String source = String.format(Locale.ROOT, "{\"f\":%d}", counter++);
                IndexResponse indexResp = leaderClient().prepareIndex("index1", "doc")
                    .setSource(source, XContentType.JSON)
                    .setTimeout(TimeValue.timeValueSeconds(1))
                    .get();
                logger.info("--> index id={} seq_no={}", indexResp.getId(), indexResp.getSeqNo());
            }
        });
        thread.start();

        PutFollowAction.Request followRequest = putFollow("index1", "index2");
        followRequest.getParameters().setMaxReadRequestOperationCount(randomIntBetween(32, 2048));
        followRequest.getParameters().setMaxReadRequestSize(new ByteSizeValue(randomIntBetween(1, 4096), ByteSizeUnit.KB));
        followRequest.getParameters().setMaxOutstandingReadRequests(randomIntBetween(1, 10));
        followRequest.getParameters().setMaxWriteRequestOperationCount(randomIntBetween(32, 2048));
        followRequest.getParameters().setMaxWriteRequestSize(new ByteSizeValue(randomIntBetween(1, 4096), ByteSizeUnit.KB));
        followRequest.getParameters().setMaxOutstandingWriteRequests(randomIntBetween(1, 10));
        followerClient().execute(PutFollowAction.INSTANCE, followRequest).get();
        disableDelayedAllocation("index2");
        logger.info("--> follow request {}", Strings.toString(followRequest));

        int maxOpsPerRead = followRequest.getParameters().getMaxReadRequestOperationCount();
        int maxNumDocsReplicated = Math.min(between(50, 500), between(maxOpsPerRead, maxOpsPerRead * 10));
        availableDocs.release(maxNumDocsReplicated / 2 + 1);
        atLeastDocsIndexed(followerClient(), "index2", maxNumDocsReplicated / 3);
        getFollowerCluster().stopRandomNonMasterNode();
        availableDocs.release(maxNumDocsReplicated / 2 + 1);
        atLeastDocsIndexed(followerClient(), "index2", maxNumDocsReplicated * 2 / 3);
        run.set(false);
        thread.join();

        assertIndexFullyReplicatedToFollower("index1", "index2");
        pauseFollow("index2");
        assertMaxSeqNoOfUpdatesIsTransferred(resolveLeaderIndex("index1"), resolveFollowerIndex("index2"), 3);
    }

    public void testAddNewReplicasOnFollower() throws Exception {
        int numberOfReplicas = between(0, 1);
        String leaderIndexSettings = getIndexSettings(1, numberOfReplicas,
            singletonMap(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), "true"));
        assertAcked(leaderClient().admin().indices().prepareCreate("leader-index").setSource(leaderIndexSettings, XContentType.JSON));
        PutFollowAction.Request follow = putFollow("leader-index", "follower-index");
        followerClient().execute(PutFollowAction.INSTANCE, follow).get();
        getFollowerCluster().ensureAtLeastNumDataNodes(numberOfReplicas + between(2, 3));
        ensureFollowerGreen("follower-index");
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicInteger docID = new AtomicInteger();
        boolean appendOnly = randomBoolean();
        Thread indexingOnLeader = new Thread(() -> {
            while (stopped.get() == false) {
                try {
                    if (appendOnly) {
                        String id = Integer.toString(docID.incrementAndGet());
                        leaderClient().prepareIndex("leader-index", "doc", id).setSource("{\"f\":" + id + "}", XContentType.JSON).get();
                    } else if (frequently()) {
                        String id = Integer.toString(frequently() ? docID.incrementAndGet() : between(0, 100));
                        leaderClient().prepareIndex("leader-index", "doc", id).setSource("{\"f\":" + id + "}", XContentType.JSON).get();
                    } else {
                        String id = Integer.toString(between(0, docID.get()));
                        leaderClient().prepareDelete("leader-index", "doc", id).get();
                    }
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            }
        });
        indexingOnLeader.start();
        Thread flushingOnFollower = new Thread(() -> {
            while (stopped.get() == false) {
                try {
                    if (rarely()) {
                        followerClient().admin().indices().prepareFlush("follower-index").get();
                    }
                    if (rarely()) {
                        followerClient().admin().indices().prepareRefresh("follower-index").get();
                    }
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            }
        });
        flushingOnFollower.start();
        awaitGlobalCheckpointAtLeast(followerClient(), new ShardId(resolveFollowerIndex("follower-index"), 0), 50);
        followerClient().admin().indices().prepareUpdateSettings("follower-index")
            .setSettings(Settings.builder().put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas + 1).build()).get();
        ensureFollowerGreen("follower-index");
        awaitGlobalCheckpointAtLeast(followerClient(), new ShardId(resolveFollowerIndex("follower-index"), 0), 100);
        stopped.set(true);
        flushingOnFollower.join();
        indexingOnLeader.join();
        assertIndexFullyReplicatedToFollower("leader-index", "follower-index");
        pauseFollow("follower-index");
    }

    public void testReadRequestsReturnLatestMappingVersion() throws Exception {
        InternalTestCluster leaderCluster = getLeaderCluster();
        Settings nodeAttributes = Settings.builder().put("node.attr.box", "large").build();
        String dataNode = leaderCluster.startDataOnlyNode(nodeAttributes);
        assertAcked(
            leaderClient().admin().indices().prepareCreate("leader-index")
                .setSettings(Settings.builder()
                    .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                    .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                    .put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), "true")
                    .put("index.routing.allocation.require.box", "large"))
                .get()
        );
        getFollowerCluster().startDataOnlyNode(nodeAttributes);
        followerClient().execute(PutFollowAction.INSTANCE, putFollow("leader-index", "follower-index")).get();
        ensureFollowerGreen("follower-index");
        ClusterService clusterService = leaderCluster.clusterService(dataNode);
        ShardId shardId = clusterService.state().routingTable().index("leader-index").shard(0).shardId();
        IndicesService indicesService = leaderCluster.getInstance(IndicesService.class, dataNode);
        IndexShard indexShard = indicesService.getShardOrNull(shardId);
        // Block the ClusterService from exposing the cluster state with the mapping change. This makes the ClusterService
        // have an older mapping version than the actual mapping version that IndexService will use to index "doc1".
        final CountDownLatch latch = new CountDownLatch(1);
        clusterService.addLowPriorityApplier(event -> {
            IndexMetaData imd = event.state().metaData().index("leader-index");
            if (imd != null && imd.mapping() != null &&
                XContentMapValues.extractValue("properties.balance.type", imd.mapping().sourceAsMap()) != null) {
                try {
                    logger.info("--> block ClusterService from exposing new mapping version");
                    latch.await();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        });
        leaderCluster.client().admin().indices().preparePutMapping().setType("doc")
            .setSource("balance", "type=long").setTimeout(TimeValue.ZERO).get();
        try {
            // Make sure the mapping is ready on the shard before we execute the index request; otherwise the index request
            // will perform a dynamic mapping update which however will be blocked because the latch is remained closed.
            assertBusy(() -> {
                DocumentMapper mapper = indexShard.mapperService().documentMapper("doc");
                assertNotNull(mapper);
                assertNotNull(mapper.mappers().getMapper("balance"));
            });
            IndexResponse indexResp = leaderCluster.client().prepareIndex("leader-index", "doc", "1")
                .setSource("{\"balance\": 100}", XContentType.JSON).setTimeout(TimeValue.ZERO).get();
            assertThat(indexResp.getResult(), equalTo(DocWriteResponse.Result.CREATED));
            assertThat(indexShard.getGlobalCheckpoint(), equalTo(0L));
            // Make sure at least one read-request which requires mapping sync is completed.
            assertBusy(() -> {
                CcrClient ccrClient = new CcrClient(followerClient());
                FollowStatsAction.StatsResponses responses = ccrClient.followStats(new FollowStatsAction.StatsRequest()).actionGet();
                long bytesRead = responses.getStatsResponses().stream().mapToLong(r -> r.status().bytesRead()).sum();
                assertThat(bytesRead, Matchers.greaterThan(0L));
            }, 60, TimeUnit.SECONDS);
            latch.countDown();
            assertIndexFullyReplicatedToFollower("leader-index", "follower-index");
        } finally {
            latch.countDown(); // no effect if latch was counted down - this makes sure teardown can make progress.
            pauseFollow("follower-index");
        }
    }
}