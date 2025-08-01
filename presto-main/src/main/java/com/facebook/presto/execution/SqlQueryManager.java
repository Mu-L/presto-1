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

import com.facebook.airlift.concurrent.ThreadPoolExecutorMBean;
import com.facebook.airlift.log.Logger;
import com.facebook.presto.ExceededCpuLimitException;
import com.facebook.presto.ExceededIntermediateWrittenBytesException;
import com.facebook.presto.ExceededOutputSizeLimitException;
import com.facebook.presto.ExceededScanLimitException;
import com.facebook.presto.Session;
import com.facebook.presto.cost.HistoryBasedPlanStatisticsManager;
import com.facebook.presto.cost.HistoryBasedPlanStatisticsTracker;
import com.facebook.presto.event.QueryMonitor;
import com.facebook.presto.execution.QueryExecution.QueryOutputInfo;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.execution.warnings.WarningCollectorFactory;
import com.facebook.presto.memory.ClusterMemoryManager;
import com.facebook.presto.resourcemanager.ClusterQueryTrackerService;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.resourceGroups.ResourceGroupQueryLimits;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.version.EmbedVersion;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.weakref.jmx.Flatten;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.facebook.airlift.concurrent.Threads.threadsNamed;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxCpuTime;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxOutputPositions;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxOutputSize;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxScanRawInputBytes;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxWrittenIntermediateBytesLimit;
import static com.facebook.presto.SystemSessionProperties.isCteMaterializationApplicable;
import static com.facebook.presto.execution.QueryLimit.Source.QUERY;
import static com.facebook.presto.execution.QueryLimit.Source.RESOURCE_GROUP;
import static com.facebook.presto.execution.QueryLimit.Source.SYSTEM;
import static com.facebook.presto.execution.QueryLimit.createDurationLimit;
import static com.facebook.presto.execution.QueryLimit.getMinimum;
import static com.facebook.presto.execution.QueryState.RUNNING;
import static com.facebook.presto.spi.StandardErrorCode.EXCEEDED_OUTPUT_POSITIONS_LIMIT;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static io.airlift.units.DataSize.Unit.BYTE;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class SqlQueryManager
        implements QueryManager
{
    private static final Logger log = Logger.get(SqlQueryManager.class);

    private final ClusterMemoryManager memoryManager;
    private final QueryMonitor queryMonitor;
    private final EmbedVersion embedVersion;
    private final QueryTracker<QueryExecution> queryTracker;

    private final Duration maxQueryCpuTime;
    private final DataSize maxQueryScanPhysicalBytes;

    private final DataSize maxWrittenIntermediatePhysicalBytes;
    private final long maxQueryOutputPositions;
    private final DataSize maxQueryOutputSize;

    private final ScheduledExecutorService queryManagementExecutor;
    private final ThreadPoolExecutorMBean queryManagementExecutorMBean;

    private final QueryManagerStats stats = new QueryManagerStats();

    private final HistoryBasedPlanStatisticsTracker historyBasedPlanStatisticsTracker;

    @Inject
    public SqlQueryManager(
            ClusterMemoryManager memoryManager,
            QueryMonitor queryMonitor,
            EmbedVersion embedVersion,
            QueryManagerConfig queryManagerConfig,
            WarningCollectorFactory warningCollectorFactory,
            HistoryBasedPlanStatisticsManager historyBasedPlanStatisticsManager,
            Optional<ClusterQueryTrackerService> clusterQueryTrackerService)
    {
        this.memoryManager = requireNonNull(memoryManager, "memoryManager is null");
        this.queryMonitor = requireNonNull(queryMonitor, "queryMonitor is null");
        this.embedVersion = requireNonNull(embedVersion, "embedVersion is null");

        this.maxQueryCpuTime = queryManagerConfig.getQueryMaxCpuTime();
        this.maxQueryScanPhysicalBytes = queryManagerConfig.getQueryMaxScanRawInputBytes();
        this.maxWrittenIntermediatePhysicalBytes = queryManagerConfig.getQueryMaxWrittenIntermediateBytes();
        this.maxQueryOutputPositions = queryManagerConfig.getQueryMaxOutputPositions();
        this.maxQueryOutputSize = queryManagerConfig.getQueryMaxOutputSize();

        this.queryManagementExecutor = Executors.newScheduledThreadPool(queryManagerConfig.getQueryManagerExecutorPoolSize(), threadsNamed("query-management-%s"));
        this.queryManagementExecutorMBean = new ThreadPoolExecutorMBean((ThreadPoolExecutor) queryManagementExecutor);

        this.queryTracker = new QueryTracker<>(queryManagerConfig, queryManagementExecutor, clusterQueryTrackerService);
        requireNonNull(historyBasedPlanStatisticsManager, "historyBasedPlanStatisticsManager is null");
        this.historyBasedPlanStatisticsTracker = historyBasedPlanStatisticsManager.getHistoryBasedPlanStatisticsTracker();
    }

    @PostConstruct
    public void start()
    {
        queryTracker.start();
        queryManagementExecutor.scheduleWithFixedDelay(() -> {
            try {
                enforceMemoryLimits();
            }
            catch (Exception e) {
                log.error(e, "Error enforcing memory limits");
            }

            try {
                enforceCpuLimits();
            }
            catch (Exception e) {
                log.error(e, "Error enforcing query CPU time limits");
            }

            try {
                enforceScanLimits();
            }
            catch (Exception e) {
                log.error(e, "Error enforcing query scan bytes limits");
            }

            try {
                enforceOutputPositionsLimits();
            }
            catch (Exception e) {
                log.error(e, "Error enforcing query output rows limits");
            }

            try {
                enforceWrittenIntermediateBytesLimit();
            }
            catch (Exception e) {
                log.error(e, "Error enforcing written intermediate limits");
            }

            try {
                enforceOutputSizeLimits();
            }
            catch (Exception e) {
                log.error(e, "Error enforcing query output size limits");
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Pulling out the checking of memory leaks to happen at a coarser granularity since it's a bit
        // expensive and does not need to happen as frequently as enforcement.
        queryManagementExecutor.scheduleWithFixedDelay(() -> {
            try {
                checkForMemoryLeaks();
            }
            catch (Exception e) {
                log.error(e, "Error checking memory leaks");
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop()
    {
        queryTracker.stop();
        queryManagementExecutor.shutdownNow();
    }

    @Override
    public List<BasicQueryInfo> getQueries()
    {
        return queryTracker.getAllQueries().stream()
                .map(queryExecution -> {
                    try {
                        return queryExecution.getBasicQueryInfo();
                    }
                    catch (RuntimeException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(toImmutableList());
    }

    @Override
    public void addOutputInfoListener(QueryId queryId, Consumer<QueryOutputInfo> listener)
    {
        requireNonNull(listener, "listener is null");

        queryTracker.getQuery(queryId).addOutputInfoListener(listener);
    }

    @Override
    public void addStateChangeListener(QueryId queryId, StateChangeListener<QueryState> listener)
    {
        requireNonNull(listener, "listener is null");

        queryTracker.getQuery(queryId).addStateChangeListener(listener);
    }

    @Override
    public ListenableFuture<QueryState> getStateChange(QueryId queryId, QueryState currentState)
    {
        return queryTracker.tryGetQuery(queryId)
                .map(query -> query.getStateChange(currentState))
                .orElseGet(() -> immediateFailedFuture(new NoSuchElementException()));
    }

    @Override
    public BasicQueryInfo getQueryInfo(QueryId queryId)
    {
        return queryTracker.getQuery(queryId).getBasicQueryInfo();
    }

    @Override
    public QueryInfo getFullQueryInfo(QueryId queryId)
            throws NoSuchElementException
    {
        return queryTracker.getQuery(queryId).getQueryInfo();
    }

    @Override
    public long getDurationUntilExpirationInMillis(QueryId queryId)
            throws NoSuchElementException
    {
        return queryTracker.getQuery(queryId).getDurationUntilExpirationInMillis();
    }

    @Override
    public Session getQuerySession(QueryId queryId)
            throws NoSuchElementException
    {
        return queryTracker.getQuery(queryId).getSession();
    }

    @Override
    public boolean isQuerySlugValid(QueryId queryId, String slug)
    {
        return queryTracker.getQuery(queryId).getSlug().equals(slug);
    }

    @Override
    public int getQueryRetryCount(QueryId queryId)
    {
        return queryTracker.getQuery(queryId).getRetryCount();
    }

    public Plan getQueryPlan(QueryId queryId)
    {
        return queryTracker.getQuery(queryId).getQueryPlan();
    }

    public void addFinalQueryInfoListener(QueryId queryId, StateChangeListener<QueryInfo> stateChangeListener)
    {
        queryTracker.getQuery(queryId).addFinalQueryInfoListener(stateChangeListener);
    }

    @Override
    public QueryState getQueryState(QueryId queryId)
    {
        return queryTracker.getQuery(queryId).getState();
    }

    @Override
    public void recordHeartbeat(QueryId queryId)
    {
        queryTracker.tryGetQuery(queryId)
                .ifPresent(QueryExecution::recordHeartbeat);
    }

    @Override
    public void createQuery(QueryExecution queryExecution)
    {
        requireNonNull(queryExecution, "queryExecution is null");

        if (!queryTracker.addQuery(queryExecution)) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, format("Query %s already registered", queryExecution.getQueryId()));
        }

        queryExecution.addFinalQueryInfoListener(finalQueryInfo -> {
            try {
                queryMonitor.queryCompletedEvent(finalQueryInfo);
            }
            finally {
                // execution MUST be added to the expiration queue or there will be a leak
                queryTracker.expireQuery(queryExecution.getQueryId());
            }
        });

        stats.trackQueryStats(queryExecution);
        // TODO(pranjalssh): Support plan statistics tracking for other query managers
        historyBasedPlanStatisticsTracker.updateStatistics(queryExecution);

        embedVersion.embedVersion(queryExecution::start).run();
    }

    @Override
    public void failQuery(QueryId queryId, Throwable cause)
    {
        requireNonNull(cause, "cause is null");

        queryTracker.tryGetQuery(queryId)
                .ifPresent(query -> query.fail(cause));
    }

    @Override
    public void cancelQuery(QueryId queryId)
    {
        log.debug("Cancel query %s", queryId);

        queryTracker.tryGetQuery(queryId)
                .ifPresent(QueryExecution::cancelQuery);
    }

    @Override
    public void cancelStage(StageId stageId)
    {
        requireNonNull(stageId, "stageId is null");

        log.debug("Cancel stage %s", stageId);

        queryTracker.tryGetQuery(stageId.getQueryId())
                .ifPresent(query -> query.cancelStage(stageId));
    }

    @Override
    @Managed
    @Flatten
    public QueryManagerStats getStats()
    {
        return stats;
    }

    @Managed(description = "Query management executor")
    @Nested
    public ThreadPoolExecutorMBean getManagementExecutor()
    {
        return queryManagementExecutorMBean;
    }

    @Managed
    public long getRunningTaskCount()
    {
        return queryTracker.getRunningTaskCount();
    }

    @Managed
    public long getQueriesKilledDueToTooManyTask()
    {
        return queryTracker.getQueriesKilledDueToTooManyTask();
    }

    @VisibleForTesting
    public HistoryBasedPlanStatisticsTracker getHistoryBasedPlanStatisticsTracker()
    {
        return historyBasedPlanStatisticsTracker;
    }

    /**
     * Enforce memory limits at the query level
     */
    private void enforceMemoryLimits()
    {
        List<QueryExecution> runningQueries = queryTracker.getAllQueries().stream()
                .filter(query -> query.getState() == RUNNING)
                .collect(toImmutableList());
        memoryManager.process(runningQueries);
    }

    private void checkForMemoryLeaks()
    {
        memoryManager.checkForLeaks(this::getQueries);
    }

    /**
     * Enforce query CPU time limits
     */
    private void enforceCpuLimits()
    {
        for (QueryExecution query : queryTracker.getAllQueries()) {
            Duration cpuTime = query.getTotalCpuTime();
            QueryLimit<Duration> queryMaxCpuTimeLimit = getMinimum(
                    createDurationLimit(maxQueryCpuTime, SYSTEM),
                    query.getResourceGroupQueryLimits()
                            .flatMap(ResourceGroupQueryLimits::getCpuTimeLimit)
                            .map(rgLimit -> createDurationLimit(rgLimit, RESOURCE_GROUP))
                            .orElse(null),
                    createDurationLimit(getQueryMaxCpuTime(query.getSession()), QUERY));
            if (cpuTime.compareTo(queryMaxCpuTimeLimit.getLimit()) > 0) {
                query.fail(new ExceededCpuLimitException(queryMaxCpuTimeLimit.getLimit(), queryMaxCpuTimeLimit.getLimitSource().name()));
            }
        }
    }

    /**
     * Enforce query scan physical bytes limits
     */
    private void enforceScanLimits()
    {
        for (QueryExecution query : queryTracker.getAllQueries()) {
            long rawInputSize = query.getRawInputDataSizeInBytes();
            DataSize sessionlimit = getQueryMaxScanRawInputBytes(query.getSession());
            DataSize limit = Ordering.natural().min(maxQueryScanPhysicalBytes, sessionlimit);
            if (Double.compare(rawInputSize, limit.getValue(BYTE)) >= 0) {
                query.fail(new ExceededScanLimitException(limit));
            }
        }
    }

    /**
     * Enforce WrittenIntermediateDataSize bytes limits
     */
    private void enforceWrittenIntermediateBytesLimit()
    {
        for (QueryExecution query : queryTracker.getAllQueries()) {
            if (!isCteMaterializationApplicable(query.getSession())) {
                // No Ctes Materialized
                continue;
            }
            long writtenIntermediateDataSize = query.getWrittenIntermediateDataSizeInBytes();
            DataSize sessionlimit = getQueryMaxWrittenIntermediateBytesLimit(query.getSession());
            DataSize limit = Ordering.natural().min(maxWrittenIntermediatePhysicalBytes, sessionlimit);
            if (Double.compare(writtenIntermediateDataSize, limit.getValue(BYTE)) >= 0) {
                query.fail(new ExceededIntermediateWrittenBytesException(limit));
            }
        }
    }

    /**
     * Enforce query output row limits
     */
    private void enforceOutputPositionsLimits()
    {
        for (QueryExecution query : queryTracker.getAllQueries()) {
            long outputPositions = query.getOutputPositions();
            long sessionLimit = getQueryMaxOutputPositions(query.getSession());
            long limit = Ordering.natural().min(maxQueryOutputPositions, sessionLimit);
            if (outputPositions > limit) {
                query.fail(new PrestoException(EXCEEDED_OUTPUT_POSITIONS_LIMIT, "Query has exceeded output rows Limit of " + limit));
            }
        }
    }

    /**
     * Enforce query output size limits
     */
    private void enforceOutputSizeLimits()
    {
        for (QueryExecution query : queryTracker.getAllQueries()) {
            long outputSize = query.getOutputDataSizeInBytes();
            DataSize sessionlimit = getQueryMaxOutputSize(query.getSession());
            DataSize limit = Ordering.natural().min(maxQueryOutputSize, sessionlimit);
            if (Double.compare(outputSize, limit.getValue(BYTE)) >= 0) {
                query.fail(new ExceededOutputSizeLimitException(limit));
            }
        }
    }
}
