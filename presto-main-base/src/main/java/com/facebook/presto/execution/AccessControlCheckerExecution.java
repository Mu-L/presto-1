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

import com.facebook.presto.Session;
import com.facebook.presto.common.analyzer.PreparedQuery;
import com.facebook.presto.common.resourceGroups.QueryType;
import com.facebook.presto.memory.VersionedMemoryPoolId;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.analyzer.AnalyzerContext;
import com.facebook.presto.spi.analyzer.AnalyzerProvider;
import com.facebook.presto.spi.analyzer.QueryAnalysis;
import com.facebook.presto.spi.analyzer.QueryAnalyzer;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.resourceGroups.ResourceGroupQueryLimits;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.inject.Inject;
import io.airlift.units.Duration;

import javax.annotation.Nullable;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.facebook.presto.SystemSessionProperties.getQueryAnalyzerTimeout;
import static com.facebook.presto.util.AnalyzerUtil.checkAccessPermissions;
import static com.facebook.presto.util.AnalyzerUtil.getAnalyzerContext;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class AccessControlCheckerExecution
        implements QueryExecution
{
    protected final QueryAnalyzer queryAnalyzer;
    protected final PreparedQuery preparedQuery;
    protected final TransactionManager transactionManager;
    protected final Metadata metadata;
    protected final AccessControl accessControl;
    protected final QueryStateMachine stateMachine;
    protected final ScheduledExecutorService timeoutThreadExecutor;

    private final String slug;
    private final int retryCount;
    private final AtomicReference<Optional<ResourceGroupQueryLimits>> resourceGroupQueryLimits = new AtomicReference<>(Optional.empty());

    private final AnalyzerContext analyzerContext;
    private final String query;

    public AccessControlCheckerExecution(
            QueryAnalyzer queryAnalyzer,
            PreparedQuery preparedQuery,
            String slug,
            int retryCount,
            TransactionManager transactionManager,
            Metadata metadata,
            AccessControl accessControl,
            QueryStateMachine stateMachine,
            ScheduledExecutorService timeoutThreadExecutor,
            String query)
    {
        this.queryAnalyzer = requireNonNull(queryAnalyzer, "queryAnalyzer is null");
        this.preparedQuery = requireNonNull(preparedQuery, "preparedQuery is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.stateMachine = requireNonNull(stateMachine, "stateMachine is null");
        this.slug = requireNonNull(slug, "slug is null");
        this.retryCount = retryCount;
        this.timeoutThreadExecutor = requireNonNull(timeoutThreadExecutor, "timeoutThreadExecutor is null");
        this.query = requireNonNull(query, "query is null");
        this.analyzerContext = getAnalyzerContext(queryAnalyzer, metadata.getMetadataResolver(stateMachine.getSession()), new PlanNodeIdAllocator(), new VariableAllocator(), stateMachine.getSession(), query);
    }

    @Override
    public String getSlug()
    {
        return slug;
    }

    @Override
    public int getRetryCount()
    {
        return retryCount;
    }

    @Override
    public VersionedMemoryPoolId getMemoryPool()
    {
        return stateMachine.getMemoryPool();
    }

    @Override
    public void setMemoryPool(VersionedMemoryPoolId poolId)
    {
        stateMachine.setMemoryPool(poolId);
    }

    @Override
    public Session getSession()
    {
        return stateMachine.getSession();
    }

    @Override
    public long getUserMemoryReservationInBytes()
    {
        return 0L;
    }

    @Override
    public long getTotalMemoryReservationInBytes()
    {
        return 0L;
    }

    @Override
    public long getCreateTimeInMillis()
    {
        return stateMachine.getCreateTimeInMillis();
    }

    @Override
    public Duration getQueuedTime()
    {
        return stateMachine.getQueuedTime();
    }

    @Override
    public long getExecutionStartTimeInMillis()
    {
        return stateMachine.getExecutionStartTimeInMillis();
    }

    @Override
    public long getLastHeartbeatInMillis()
    {
        return stateMachine.getLastHeartbeatInMillis();
    }

    @Override
    public long getEndTimeInMillis()
    {
        return stateMachine.getEndTimeInMillis();
    }

    @Override
    public Duration getTotalCpuTime()
    {
        return new Duration(0, NANOSECONDS);
    }

    @Override
    public long getRawInputDataSizeInBytes()
    {
        return 0L;
    }

    @Override
    public long getWrittenIntermediateDataSizeInBytes()
    {
        return 0L;
    }

    @Override
    public long getOutputPositions()
    {
        return 0L;
    }

    @Override
    public long getOutputDataSizeInBytes()
    {
        return 0L;
    }

    @Override
    public Optional<ResourceGroupQueryLimits> getResourceGroupQueryLimits()
    {
        return resourceGroupQueryLimits.get();
    }

    @Override
    public void setResourceGroupQueryLimits(ResourceGroupQueryLimits resourceGroupQueryLimits)
    {
        if (!this.resourceGroupQueryLimits.compareAndSet(Optional.empty(), Optional.of(requireNonNull(resourceGroupQueryLimits, "resourceGroupQueryLimits is null")))) {
            throw new IllegalStateException("Cannot set resourceGroupQueryLimits more than once");
        }
    }

    @Override
    public BasicQueryInfo getBasicQueryInfo()
    {
        return stateMachine.getFinalQueryInfo()
                .map(BasicQueryInfo::new)
                .orElseGet(() -> stateMachine.getBasicQueryInfo(Optional.empty()));
    }

    @Override
    public int getRunningTaskCount()
    {
        return stateMachine.getCurrentRunningTaskCount();
    }

    @Override
    public void start()
    {
        try {
            // transition to running
            if (!stateMachine.transitionToRunning()) {
                // query already running or finished
                return;
            }
            ListenableFuture<?> future = executeTask();

            Futures.addCallback(future, new FutureCallback<Object>()
            {
                @Override
                public void onSuccess(@Nullable Object result)
                {
                    stateMachine.transitionToFinishing();
                }

                @Override
                public void onFailure(Throwable throwable)
                {
                    fail(throwable);
                }
            }, directExecutor());
        }
        catch (Throwable e) {
            fail(e);
            throwIfInstanceOf(e, Error.class);
        }
    }

    private ListenableFuture<?> executeTask()
    {
        stateMachine.beginSemanticAnalyzing();

        QueryAnalysis queryAnalysis;
        try (TimeoutThread unused = new TimeoutThread(
                Thread.currentThread(),
                timeoutThreadExecutor,
                getQueryAnalyzerTimeout(getSession()))) {
            queryAnalysis = queryAnalyzer.analyze(analyzerContext, preparedQuery);
        }

        stateMachine.beginColumnAccessPermissionChecking();
        checkAccessPermissions(queryAnalysis.getAccessControlReferences(), query);
        stateMachine.endColumnAccessPermissionChecking();
        return immediateFuture(null);
    }

    @Override
    public void addOutputInfoListener(Consumer<QueryOutputInfo> listener)
    {
        // does not have an output
    }

    @Override
    public ListenableFuture<QueryState> getStateChange(QueryState currentState)
    {
        return stateMachine.getStateChange(currentState);
    }

    @Override
    public void addStateChangeListener(StateMachine.StateChangeListener<QueryState> stateChangeListener)
    {
        stateMachine.addStateChangeListener(stateChangeListener);
    }

    @Override
    public void addFinalQueryInfoListener(StateMachine.StateChangeListener<QueryInfo> stateChangeListener)
    {
        stateMachine.addQueryInfoStateChangeListener(stateChangeListener);
    }

    @Override
    public void fail(Throwable cause)
    {
        stateMachine.transitionToFailed(cause);
        stateMachine.updateQueryInfo(Optional.empty());
    }

    @Override
    public boolean isDone()
    {
        return getState().isDone();
    }

    @Override
    public void cancelQuery()
    {
        stateMachine.transitionToCanceled();
    }

    @Override
    public void cancelStage(StageId stageId)
    {
        // no-op
    }

    @Override
    public void recordHeartbeat()
    {
        stateMachine.recordHeartbeat();
    }

    @Override
    public void pruneExpiredQueryInfo()
    {
        // no-op
    }

    @Override
    public void pruneFinishedQueryInfo()
    {
        // no-op
    }

    @Override
    public QueryId getQueryId()
    {
        return stateMachine.getQueryId();
    }

    @Override
    public QueryInfo getQueryInfo()
    {
        return stateMachine.getFinalQueryInfo().orElseGet(() -> stateMachine.updateQueryInfo(Optional.empty()));
    }

    @Override
    public Plan getQueryPlan()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public QueryState getState()
    {
        return stateMachine.getQueryState();
    }

    public static class AccessControlCheckerExecutionFactory
            implements QueryExecution.QueryExecutionFactory<AccessControlCheckerExecution>
    {
        private final TransactionManager transactionManager;
        private final Metadata metadata;
        private final AccessControl accessControl;
        private final ScheduledExecutorService timeoutThreadExecutor;

        @Inject
        public AccessControlCheckerExecutionFactory(
                TransactionManager transactionManager,
                MetadataManager metadata,
                AccessControl accessControl,
                @ForTimeoutThread ScheduledExecutorService timeoutThreadExecutor)
        {
            this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.accessControl = requireNonNull(accessControl, "accessControl is null");
            this.timeoutThreadExecutor = requireNonNull(timeoutThreadExecutor, "timeoutThreadExecutor is null");
        }

        @Override
        public AccessControlCheckerExecution createQueryExecution(
                AnalyzerProvider analyzerProvider,
                PreparedQuery preparedQuery,
                QueryStateMachine stateMachine,
                String slug,
                int retryCount,
                WarningCollector warningCollector,
                Optional<QueryType> queryType,
                AccessControl accessControl,
                String query)
        {
            return createAccessControlChecker(analyzerProvider.getQueryAnalyzer(), preparedQuery, stateMachine, slug, retryCount, query);
        }

        private AccessControlCheckerExecution createAccessControlChecker(
                QueryAnalyzer queryAnalyzer,
                PreparedQuery preparedQuery,
                QueryStateMachine stateMachine,
                String slug,
                int retryCount,
                String query)
        {
            return new AccessControlCheckerExecution(queryAnalyzer, preparedQuery, slug, retryCount, transactionManager, metadata, accessControl, stateMachine, timeoutThreadExecutor, query);
        }
    }
}
