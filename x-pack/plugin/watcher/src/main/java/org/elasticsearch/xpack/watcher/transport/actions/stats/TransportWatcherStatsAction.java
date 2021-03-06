/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.transport.actions.stats;

import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.nodes.TransportNodesAction;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.watcher.WatcherMetaData;
import org.elasticsearch.xpack.core.watcher.common.stats.Counters;
import org.elasticsearch.xpack.core.watcher.transport.actions.stats.WatcherStatsAction;
import org.elasticsearch.xpack.core.watcher.transport.actions.stats.WatcherStatsRequest;
import org.elasticsearch.xpack.core.watcher.transport.actions.stats.WatcherStatsResponse;
import org.elasticsearch.xpack.watcher.WatcherLifeCycleService;
import org.elasticsearch.xpack.watcher.execution.ExecutionService;
import org.elasticsearch.xpack.watcher.trigger.TriggerService;

import java.util.Arrays;
import java.util.List;

/**
 * Performs the stats operation.
 */
public class TransportWatcherStatsAction extends TransportNodesAction<WatcherStatsRequest, WatcherStatsResponse,
        WatcherStatsRequest.Node, WatcherStatsResponse.Node> {

    private final ExecutionService executionService;
    private final TriggerService triggerService;
    private final WatcherLifeCycleService lifeCycleService;

    @Inject
    public TransportWatcherStatsAction(TransportService transportService, ClusterService clusterService,
                                       ThreadPool threadPool, ActionFilters actionFilters, WatcherLifeCycleService lifeCycleService,
                                       ExecutionService executionService, TriggerService triggerService) {
        super(WatcherStatsAction.NAME, threadPool, clusterService, transportService, actionFilters,
            WatcherStatsRequest::new, WatcherStatsRequest.Node::new, ThreadPool.Names.MANAGEMENT, WatcherStatsResponse.Node.class);
        this.lifeCycleService = lifeCycleService;
        this.executionService = executionService;
        this.triggerService = triggerService;
    }

    @Override
    protected WatcherStatsResponse newResponse(WatcherStatsRequest request, List<WatcherStatsResponse.Node> nodes,
                                               List<FailedNodeException> failures) {
        return new WatcherStatsResponse(clusterService.getClusterName(), getWatcherMetaData(), nodes, failures);
    }

    @Override
    protected WatcherStatsRequest.Node newNodeRequest(String nodeId, WatcherStatsRequest request) {
        return new WatcherStatsRequest.Node(request, nodeId);
    }

    @Override
    protected WatcherStatsResponse.Node newNodeResponse() {
        return new WatcherStatsResponse.Node();
    }

    @Override
    protected WatcherStatsResponse.Node nodeOperation(WatcherStatsRequest.Node request, Task task) {
        WatcherStatsResponse.Node statsResponse = new WatcherStatsResponse.Node(clusterService.localNode());
        statsResponse.setWatcherState(lifeCycleService.getState());
        statsResponse.setThreadPoolQueueSize(executionService.executionThreadPoolQueueSize());
        statsResponse.setThreadPoolMaxSize(executionService.executionThreadPoolMaxSize());
        if (request.includeCurrentWatches()) {
            statsResponse.setSnapshots(executionService.currentExecutions());
        }
        if (request.includeQueuedWatches()) {
            statsResponse.setQueuedWatches(executionService.queuedWatches());
        }
        if (request.includeStats()) {
            Counters stats = Counters.merge(Arrays.asList(triggerService.stats(), executionService.executionTimes()));
            statsResponse.setStats(stats);
        }
        statsResponse.setWatchesCount(triggerService.count());
        return statsResponse;
    }

    private WatcherMetaData getWatcherMetaData() {
        WatcherMetaData watcherMetaData = clusterService.state().getMetaData().custom(WatcherMetaData.TYPE);
        if (watcherMetaData == null) {
            watcherMetaData = new WatcherMetaData(false);
        }
        return watcherMetaData;
    }
}
