/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.Session;
import com.facebook.presto.execution.QueryTracker.TrackedQuery;
import com.facebook.presto.resourcemanager.ClusterQueryTrackerService;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.resourceGroups.ResourceGroupQueryLimits;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.airlift.units.Duration;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.facebook.presto.SystemSessionProperties.getQueryClientTimeout;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxExecutionTime;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxQueuedTime;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxRunTime;
import static com.facebook.presto.execution.QueryLimit.Source.QUERY;
import static com.facebook.presto.execution.QueryLimit.Source.RESOURCE_GROUP;
import static com.facebook.presto.execution.QueryLimit.createDurationLimit;
import static com.facebook.presto.execution.QueryLimit.getMinimum;
import static com.facebook.presto.spi.StandardErrorCode.ABANDONED_QUERY;
import static com.facebook.presto.spi.StandardErrorCode.CLUSTER_HAS_TOO_MANY_RUNNING_TASKS;
import static com.facebook.presto.spi.StandardErrorCode.EXCEEDED_TIME_LIMIT;
import static com.facebook.presto.spi.StandardErrorCode.SERVER_SHUTTING_DOWN;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Comparator.comparingInt;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class QueryTracker<T extends TrackedQuery>
{
    private static final Logger log = Logger.get(QueryTracker.class);

    private final int maxQueryHistory;
    private final int maxTotalRunningTaskCountToKillQuery;
    private final int maxQueryRunningTaskCount;

    private final AtomicInteger runningTaskCount = new AtomicInteger();
    private final AtomicLong queriesKilledDueToTooManyTask = new AtomicLong();

    private final Duration minQueryExpireAge;

    private final ConcurrentMap<QueryId, T> queries = new ConcurrentHashMap<>();
    private final Queue<T> expirationQueue = new LinkedBlockingQueue<>();

    private final ScheduledExecutorService queryManagementExecutor;

    @GuardedBy("this")
    private ScheduledFuture<?> backgroundTask;

    private final Optional<ClusterQueryTrackerService> clusterQueryTrackerService;

    public QueryTracker(QueryManagerConfig queryManagerConfig, ScheduledExecutorService queryManagementExecutor, Optional<ClusterQueryTrackerService> clusterQueryTrackerService)
    {
        requireNonNull(queryManagerConfig, "queryManagerConfig is null");
        this.minQueryExpireAge = queryManagerConfig.getMinQueryExpireAge();
        this.maxQueryHistory = queryManagerConfig.getMaxQueryHistory();
        this.maxTotalRunningTaskCountToKillQuery = queryManagerConfig.getMaxTotalRunningTaskCountToKillQuery();
        this.maxQueryRunningTaskCount = queryManagerConfig.getMaxQueryRunningTaskCount();

        this.queryManagementExecutor = requireNonNull(queryManagementExecutor, "queryManagementExecutor is null");
        this.clusterQueryTrackerService = clusterQueryTrackerService;
    }

    public synchronized void start()
    {
        checkState(backgroundTask == null, "QueryTracker already started");
        backgroundTask = queryManagementExecutor.scheduleWithFixedDelay(() -> {
            try {
                failAbandonedQueries();
            }
            catch (Throwable e) {
                log.error(e, "Error cancelling abandoned queries");
            }

            try {
                enforceTimeLimits();
            }
            catch (Throwable e) {
                log.error(e, "Error enforcing query timeout limits");
            }

            try {
                if (maxTotalRunningTaskCountToKillQuery != Integer.MAX_VALUE && maxQueryRunningTaskCount != Integer.MAX_VALUE) {
                    enforceTaskLimits();
                }
            }
            catch (Throwable e) {
                log.error(e, "Error enforcing running task limits");
            }

            try {
                removeExpiredQueries();
            }
            catch (Throwable e) {
                log.error(e, "Error removing expired queries");
            }

            try {
                pruneExpiredQueries();
            }
            catch (Throwable e) {
                log.error(e, "Error pruning expired queries");
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void stop()
    {
        synchronized (this) {
            if (backgroundTask != null) {
                backgroundTask.cancel(true);
            }
        }

        boolean queryCancelled = false;
        for (T trackedQuery : queries.values()) {
            if (trackedQuery.isDone()) {
                continue;
            }

            log.info("Server shutting down. Query %s has been cancelled", trackedQuery.getQueryId());
            trackedQuery.fail(new PrestoException(SERVER_SHUTTING_DOWN, "Server is shutting down. Query " + trackedQuery.getQueryId() + " has been cancelled"));
            queryCancelled = true;
        }
        if (queryCancelled) {
            try {
                TimeUnit.SECONDS.sleep(5);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Collection<T> getAllQueries()
    {
        return ImmutableList.copyOf(queries.values());
    }

    public T getQuery(QueryId queryId)
            throws NoSuchElementException
    {
        return tryGetQuery(queryId)
                .orElseThrow(() -> new NoSuchElementException(queryId.toString()));
    }

    public Optional<T> tryGetQuery(QueryId queryId)
    {
        requireNonNull(queryId, "queryId is null");
        return Optional.ofNullable(queries.get(queryId));
    }

    public boolean addQuery(T execution)
    {
        return queries.putIfAbsent(execution.getQueryId(), execution) == null;
    }

    /**
     * Query is finished and expiration should begin.
     */
    public void expireQuery(QueryId queryId)
    {
        tryGetQuery(queryId)
                .ifPresent(query -> {
                    query.pruneFinishedQueryInfo();
                    expirationQueue.add(query);
                });
    }

    public long getRunningTaskCount()
    {
        return runningTaskCount.get();
    }

    public long getQueriesKilledDueToTooManyTask()
    {
        return queriesKilledDueToTooManyTask.get();
    }

    /**
     * Enforce query max runtime/queued/execution time limits
     */
    @VisibleForTesting
    void enforceTimeLimits()
    {
        for (T query : queries.values()) {
            if (query.isDone()) {
                continue;
            }
            Duration queryMaxRunTime = getQueryMaxRunTime(query.getSession());
            Duration queryMaxQueuedTime = getQueryMaxQueuedTime(query.getSession());
            QueryLimit<Duration> queryMaxExecutionTime = getMinimum(
                    createDurationLimit(getQueryMaxExecutionTime(query.getSession()), QUERY),
                    query.getResourceGroupQueryLimits()
                            .flatMap(ResourceGroupQueryLimits::getExecutionTimeLimit)
                            .map(rgLimit -> createDurationLimit(rgLimit, RESOURCE_GROUP)).orElse(null));
            long executionStartTime = query.getExecutionStartTimeInMillis();
            long createTimeInMillis = query.getCreateTimeInMillis();
            long queuedTimeInMillis = query.getQueuedTime().toMillis();
            if (queuedTimeInMillis > queryMaxQueuedTime.toMillis()) {
                query.fail(new PrestoException(EXCEEDED_TIME_LIMIT, "Query exceeded maximum queued time limit of " + queryMaxQueuedTime));
            }
            if (executionStartTime > 0 && (executionStartTime + queryMaxExecutionTime.getLimit().toMillis()) < currentTimeMillis()) {
                query.fail(
                        new PrestoException(EXCEEDED_TIME_LIMIT,
                                format(
                                        "Query exceeded the maximum execution time limit of %s defined at the %s level",
                                        queryMaxExecutionTime.getLimit(),
                                        queryMaxExecutionTime.getLimitSource().name())));
            }
            if (createTimeInMillis + queryMaxRunTime.toMillis() < currentTimeMillis()) {
                query.fail(new PrestoException(EXCEEDED_TIME_LIMIT, "Query exceeded maximum time limit of " + queryMaxRunTime));
            }
        }
    }

    private static class QueryAndTaskCount<T extends TrackedQuery>
    {
        private final T query;
        private final int taskCount;

        public QueryAndTaskCount(T query, int taskCount)
        {
            this.query = query;
            this.taskCount = taskCount;
        }

        public T getQuery()
        {
            return query;
        }

        public int getTaskCount()
        {
            return taskCount;
        }
    }

    /**
     * When cluster reaches max tasks limit and also a single query
     * exceeds a threshold,  kill this query
     */
    @VisibleForTesting
    void enforceTaskLimits()
    {
        int totalRunningTaskCount = 0;
        Queue<QueryAndTaskCount> taskCountQueue = new PriorityQueue<>(comparingInt(queryAndCount -> -1 * queryAndCount.getTaskCount()));

        for (T query : queries.values()) {
            if (query.isDone() || !(query instanceof QueryExecution)) {
                continue;
            }
            int runningTaskCount = ((QueryExecution) query).getRunningTaskCount();
            totalRunningTaskCount += runningTaskCount;
            if (runningTaskCount > maxQueryRunningTaskCount) {
                taskCountQueue.add(new QueryAndTaskCount(query, runningTaskCount));
            }
        }

        if (clusterQueryTrackerService.isPresent()) {
            totalRunningTaskCount = clusterQueryTrackerService.get().getRunningTaskCount();
        }

        runningTaskCount.set(totalRunningTaskCount);
        int runningTaskCountAfterKills = totalRunningTaskCount;

        while (runningTaskCountAfterKills > maxTotalRunningTaskCountToKillQuery && !taskCountQueue.isEmpty()) {
            QueryAndTaskCount<T> queryAndTaskCount = taskCountQueue.poll();
            queryAndTaskCount.getQuery().fail(new PrestoException(CLUSTER_HAS_TOO_MANY_RUNNING_TASKS, format(
                    "Query killed because the cluster is overloaded with too many tasks (%s) and this query was running with the highest number of tasks (%s). Please try again later.",
                    totalRunningTaskCount, queryAndTaskCount.getTaskCount())));
            runningTaskCountAfterKills -= queryAndTaskCount.getTaskCount();
            queriesKilledDueToTooManyTask.incrementAndGet();
        }
    }

    /**
     * Prune extraneous info from old queries
     */
    private void pruneExpiredQueries()
    {
        if (expirationQueue.size() <= maxQueryHistory) {
            return;
        }

        int count = 0;
        // we're willing to keep full info for up to maxQueryHistory queries
        for (T query : expirationQueue) {
            if (expirationQueue.size() - count <= maxQueryHistory) {
                break;
            }
            query.pruneExpiredQueryInfo();
            count++;
        }
    }

    /**
     * Remove completed queries after a waiting period
     */
    private void removeExpiredQueries()
    {
        long timeHorizonInMillis = currentTimeMillis() - minQueryExpireAge.toMillis();

        // we're willing to keep queries beyond timeHorizon as long as we have fewer than maxQueryHistory
        while (expirationQueue.size() > maxQueryHistory) {
            T query = expirationQueue.peek();
            if (query == null) {
                return;
            }

            // expirationQueue is FIFO based on query end time. Stop when we see the
            // first query that's too young to expire
            long endTimeInMillis = query.getEndTimeInMillis();
            if (endTimeInMillis == 0) {
                // this shouldn't happen but it is better to be safe here
                continue;
            }
            if (endTimeInMillis > timeHorizonInMillis) {
                return;
            }

            // only expire them if they are older than minQueryExpireAge. We need to keep them
            // around for a while in case clients come back asking for status
            QueryId queryId = query.getQueryId();

            log.debug("Remove query %s", queryId);
            queries.remove(queryId);
            expirationQueue.remove(query);
        }
    }

    private void failAbandonedQueries()
    {
        for (T query : queries.values()) {
            try {
                if (query.isDone()) {
                    continue;
                }

                if (isAbandoned(query)) {
                    log.info("Failing abandoned query %s", query.getQueryId());
                    query.fail(new PrestoException(
                            ABANDONED_QUERY,
                            format("Query %s has not been accessed since %sms: currentTime %sms",
                                    query.getQueryId(),
                                    query.getLastHeartbeatInMillis(),
                                    currentTimeMillis())));
                }
            }
            catch (RuntimeException e) {
                log.error(e, "Exception failing abandoned query %s", query.getQueryId());
            }
        }
    }

    private boolean isAbandoned(T query)
    {
        Duration queryClientTimeout = getQueryClientTimeout(query.getSession());
        long oldestAllowedHeartbeatInMillis = currentTimeMillis() - queryClientTimeout.toMillis();
        long lastHeartbeat = query.getLastHeartbeatInMillis();

        return lastHeartbeat > 0 && lastHeartbeat < oldestAllowedHeartbeatInMillis;
    }

    public interface TrackedQuery
    {
        QueryId getQueryId();

        boolean isDone();

        Session getSession();

        long getCreateTimeInMillis();

        Duration getQueuedTime();

        long getExecutionStartTimeInMillis();

        long getLastHeartbeatInMillis();

        long getEndTimeInMillis();

        default long getDurationUntilExpirationInMillis()
        {
            Duration queryClientTimeout = getQueryClientTimeout(getSession());
            long expireTime = getLastHeartbeatInMillis() + queryClientTimeout.toMillis();
            return Math.max(0, expireTime - currentTimeMillis());
        }

        Optional<ResourceGroupQueryLimits> getResourceGroupQueryLimits();

        void fail(Throwable cause);

        // XXX: This should be removed when the client protocol is improved, so that we don't need to hold onto so much query history

        /**
         * Prune info from finished queries which are in the expiry queue and the queue length is
         * greater than {@code query.max-history}
         */
        void pruneExpiredQueryInfo();

        /**
         * Prune info from finished queries which should not be kept around at all after the query
         * state machine has transitioned into a finished state
         */
        void pruneFinishedQueryInfo();
    }
}
