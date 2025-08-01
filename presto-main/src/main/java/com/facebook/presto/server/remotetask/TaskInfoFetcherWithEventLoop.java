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
package com.facebook.presto.server.remotetask;

import com.facebook.airlift.http.client.HttpClient;
import com.facebook.airlift.http.client.HttpUriBuilder;
import com.facebook.airlift.http.client.Request;
import com.facebook.airlift.http.client.ResponseHandler;
import com.facebook.airlift.http.client.thrift.ThriftRequestUtils;
import com.facebook.airlift.http.client.thrift.ThriftResponseHandler;
import com.facebook.airlift.json.Codec;
import com.facebook.airlift.json.JsonCodec;
import com.facebook.airlift.json.smile.SmileCodec;
import com.facebook.drift.transport.netty.codec.Protocol;
import com.facebook.presto.Session;
import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.execution.StateMachine;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.execution.TaskStatus;
import com.facebook.presto.metadata.HandleResolver;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.server.RequestErrorTracker;
import com.facebook.presto.server.SimpleHttpResponseCallback;
import com.facebook.presto.server.SimpleHttpResponseHandler;
import com.facebook.presto.server.smile.BaseResponse;
import com.facebook.presto.server.thrift.ThriftHttpResponseHandler;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.Duration;
import io.netty.channel.EventLoop;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.facebook.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static com.facebook.airlift.http.client.Request.Builder.prepareGet;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CURRENT_STATE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_MAX_WAIT;
import static com.facebook.presto.server.RequestErrorTracker.taskRequestErrorTracker;
import static com.facebook.presto.server.RequestHelpers.setContentTypeHeaders;
import static com.facebook.presto.server.smile.AdaptingJsonResponseHandler.createAdaptingJsonResponseHandler;
import static com.facebook.presto.server.smile.FullSmileResponseHandler.createFullSmileResponseHandler;
import static com.facebook.presto.server.thrift.ThriftCodecWrapper.unwrapThriftCodec;
import static com.facebook.presto.spi.StandardErrorCode.REMOTE_TASK_ERROR;
import static com.google.common.base.Verify.verify;
import static io.airlift.units.Duration.nanosSince;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class TaskInfoFetcherWithEventLoop
        implements SimpleHttpResponseCallback<TaskInfo>
{
    private final TaskId taskId;
    private final Consumer<Throwable> onFail;
    private final StateMachine<TaskInfo> taskInfo;
    private final StateMachine<Optional<TaskInfo>> finalTaskInfo;
    private final Codec<TaskInfo> taskInfoCodec;

    private final long updateIntervalMillis;
    private final Duration taskInfoRefreshMaxWait;
    private long lastUpdateNanos;

    private final EventLoop taskEventLoop;
    private final HttpClient httpClient;
    private final RequestErrorTracker errorTracker;

    private final boolean summarizeTaskInfo;

    private long currentRequestStartNanos;
    private final RemoteTaskStats stats;
    private boolean running;

    private ScheduledFuture<?> scheduledFuture;
    private ListenableFuture<BaseResponse<TaskInfo>> future;

    private final boolean isBinaryTransportEnabled;
    private final boolean isThriftTransportEnabled;
    private final Session session;
    private final MetadataManager metadataManager;
    private final QueryManager queryManager;
    private final HandleResolver handleResolver;
    private final Protocol thriftProtocol;

    public TaskInfoFetcherWithEventLoop(
            Consumer<Throwable> onFail,
            TaskInfo initialTask,
            HttpClient httpClient,
            Duration updateInterval,
            Duration taskInfoRefreshMaxWait,
            Codec<TaskInfo> taskInfoCodec,
            Duration maxErrorDuration,
            boolean summarizeTaskInfo,
            EventLoop taskEventLoop,
            RemoteTaskStats stats,
            boolean isBinaryTransportEnabled,
            boolean isThriftTransportEnabled,
            Session session,
            MetadataManager metadataManager,
            QueryManager queryManager,
            HandleResolver handleResolver,
            Protocol thriftProtocol)
    {
        requireNonNull(initialTask, "initialTask is null");

        this.taskId = initialTask.getTaskId();
        this.onFail = requireNonNull(onFail, "onFail is null");
        this.taskInfo = new StateMachine<>("task " + taskId, taskEventLoop, initialTask);
        this.finalTaskInfo = new StateMachine<>("task-" + taskId, taskEventLoop, Optional.empty());
        this.taskInfoCodec = requireNonNull(taskInfoCodec, "taskInfoCodec is null");

        this.updateIntervalMillis = requireNonNull(updateInterval, "updateInterval is null").toMillis();
        this.taskInfoRefreshMaxWait = requireNonNull(taskInfoRefreshMaxWait, "taskInfoRefreshMaxWait is null");
        this.errorTracker = taskRequestErrorTracker(taskId, initialTask.getTaskStatus().getSelf(), maxErrorDuration, taskEventLoop, "getting info for task");

        this.summarizeTaskInfo = summarizeTaskInfo;

        this.taskEventLoop = requireNonNull(taskEventLoop, "taskEventLoop is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.isBinaryTransportEnabled = isBinaryTransportEnabled;
        this.isThriftTransportEnabled = isThriftTransportEnabled;
        this.session = requireNonNull(session, "session is null");
        this.metadataManager = requireNonNull(metadataManager, "metadataManager is null");
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
        this.handleResolver = requireNonNull(handleResolver, "handleResolver is null");
        this.thriftProtocol = requireNonNull(thriftProtocol, "thriftProtocol is null");
    }

    public TaskInfo getTaskInfo()
    {
        return taskInfo.get();
    }

    public void start()
    {
        verify(taskEventLoop.inEventLoop());

        if (running) {
            // already running
            return;
        }
        running = true;
        scheduleUpdate();
    }

    private void stop()
    {
        verify(taskEventLoop.inEventLoop());

        running = false;
        if (future != null) {
            // do not terminate if the request is already running to avoid closing pooled connections
            future.cancel(false);
            future = null;
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * Add a listener for the final task info.  This notification is guaranteed to be fired only once.
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor. Additionally, it is
     * possible notifications are observed out of order due to the asynchronous execution.
     */
    public void addFinalTaskInfoListener(StateChangeListener<TaskInfo> stateChangeListener)
    {
        AtomicBoolean done = new AtomicBoolean();
        StateChangeListener<Optional<TaskInfo>> fireOnceStateChangeListener = finalTaskInfo -> {
            if (finalTaskInfo.isPresent() && done.compareAndSet(false, true)) {
                stateChangeListener.stateChanged(finalTaskInfo.get());
            }
        };
        finalTaskInfo.addStateChangeListener(fireOnceStateChangeListener);
        fireOnceStateChangeListener.stateChanged(finalTaskInfo.get());
    }

    private void scheduleUpdate()
    {
        verify(taskEventLoop.inEventLoop());

        scheduledFuture = taskEventLoop.scheduleWithFixedDelay(() -> {
            try {
                // if the previous request still running, don't schedule a new request
                if (future != null && !future.isDone()) {
                    return;
                }

                if (nanosSince(lastUpdateNanos).toMillis() >= updateIntervalMillis) {
                    sendNextRequest();
                }
            }
            catch (Throwable t) {
                fatal(t);
                throw t;
            }
        }, 0, 100, MILLISECONDS);
    }

    private void sendNextRequest()
    {
        verify(taskEventLoop.inEventLoop());

        TaskInfo taskInfo = getTaskInfo();
        TaskStatus taskStatus = taskInfo.getTaskStatus();

        if (!running) {
            return;
        }

        // we already have the final task info
        if (isDone(getTaskInfo())) {
            stop();
            return;
        }

        // if we have an outstanding request
        if (future != null && !future.isDone()) {
            return;
        }

        // if throttled due to error, asynchronously wait for timeout and try again
        ListenableFuture<?> errorRateLimit = errorTracker.acquireRequestPermit();
        if (!errorRateLimit.isDone()) {
            errorRateLimit.addListener(this::sendNextRequest, taskEventLoop);
            return;
        }

        HttpUriBuilder httpUriBuilder = uriBuilderFrom(taskStatus.getSelf());
        URI uri = summarizeTaskInfo ? httpUriBuilder.addParameter("summarize").build() : httpUriBuilder.build();
        Request.Builder requestBuilder = setContentTypeHeaders(isBinaryTransportEnabled, prepareGet());

        ResponseHandler responseHandler;
        if (isThriftTransportEnabled) {
            requestBuilder = ThriftRequestUtils.prepareThriftGet(thriftProtocol);
            responseHandler = new ThriftResponseHandler(unwrapThriftCodec(taskInfoCodec));
        }
        else if (isBinaryTransportEnabled) {
            responseHandler = createFullSmileResponseHandler((SmileCodec<TaskInfo>) taskInfoCodec);
        }
        else {
            responseHandler = createAdaptingJsonResponseHandler((JsonCodec<TaskInfo>) taskInfoCodec);
        }

        if (taskInfoRefreshMaxWait.toMillis() != 0L) {
            requestBuilder.setHeader(PRESTO_CURRENT_STATE, taskStatus.getState().toString())
                    .setHeader(PRESTO_MAX_WAIT, taskInfoRefreshMaxWait.toString());
        }

        Request request = requestBuilder.setUri(uri).build();
        errorTracker.startRequest();
        future = httpClient.executeAsync(request, responseHandler);
        currentRequestStartNanos = System.nanoTime();
        FutureCallback callback;
        if (isThriftTransportEnabled) {
            callback = new ThriftHttpResponseHandler(this, request.getUri(), stats.getHttpResponseStats(), REMOTE_TASK_ERROR);
        }
        else {
            callback = new SimpleHttpResponseHandler<>(this, request.getUri(), stats.getHttpResponseStats(), REMOTE_TASK_ERROR);
        }

        Futures.addCallback(
                future,
                callback,
                taskEventLoop);
    }

    void updateTaskInfo(TaskInfo newValue)
    {
        verify(taskEventLoop.inEventLoop());

        boolean updated = taskInfo.setIf(newValue, oldValue -> {
            TaskStatus oldTaskStatus = oldValue.getTaskStatus();
            TaskStatus newTaskStatus = newValue.getTaskStatus();
            if (oldTaskStatus.getState().isDone()) {
                // never update if the task has reached a terminal state
                return false;
            }
            // don't update to an older version (same version is ok)
            return newTaskStatus.getVersion() >= oldTaskStatus.getVersion();
        });

        if (updated && newValue.getTaskStatus().getState().isDone()) {
            finalTaskInfo.compareAndSet(Optional.empty(), Optional.of(newValue));
            stop();
        }
    }

    @Override
    public void success(TaskInfo newValue)
    {
        verify(taskEventLoop.inEventLoop());

        lastUpdateNanos = System.nanoTime();

        long startNanos;
        startNanos = this.currentRequestStartNanos;
        updateStats(startNanos);
        errorTracker.requestSucceeded();
        updateTaskInfo(newValue);
    }

    @Override
    public void failed(Throwable cause)
    {
        verify(taskEventLoop.inEventLoop());

        lastUpdateNanos = System.nanoTime();

        try {
            // if task not already done, record error
            if (!isDone(getTaskInfo())) {
                errorTracker.requestFailed(cause);
            }
        }
        catch (Error e) {
            onFail.accept(e);
            throw e;
        }
        catch (RuntimeException e) {
            onFail.accept(e);
        }
    }

    @Override
    public void fatal(Throwable cause)
    {
        verify(taskEventLoop.inEventLoop());
        onFail.accept(cause);
    }

    private void updateStats(long currentRequestStartNanos)
    {
        verify(taskEventLoop.inEventLoop());
        stats.infoRoundTripMillis(nanosSince(currentRequestStartNanos).toMillis());
    }

    private static boolean isDone(TaskInfo taskInfo)
    {
        return taskInfo.getTaskStatus().getState().isDone();
    }
}
