/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hedwig.server.common;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.bookkeeper.util.MathUtils;
import org.apache.hedwig.exceptions.PubSubException;
import org.apache.hedwig.server.persistence.BookkeeperPersistenceManager;
import org.apache.hedwig.server.stats.HedwigServerStatsLogger.HedwigServerSimpleStatType;
import org.apache.hedwig.server.stats.ServerStatsProvider;
import org.apache.hedwig.util.Callback;

import com.google.protobuf.ByteString;

public class TopicOpQueuer {
    /**
     * Map from topic to the queue of operations for that topic.
     */
    protected HashMap<ByteString, Queue<Runnable>> topic2ops = new HashMap<ByteString, Queue<Runnable>>();

    protected final ScheduledExecutorService scheduler;

    public TopicOpQueuer(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public interface Op extends Runnable {
    }

    /**
     * Update the persist queue size in ServerStatsProvider only for the necessary Op.
     * We do this all in one file so as not to distribute these calls to different operations.
     * @param op
     * @param increment if true, increment, else decrement
     */
    private void updatePersistQueueSize(Op op, boolean increment) {
        if (op instanceof BookkeeperPersistenceManager.ConsumeUntilOp
                || op instanceof BookkeeperPersistenceManager.PersistOp
                || op instanceof BookkeeperPersistenceManager.RangeScanOp
                || op instanceof BookkeeperPersistenceManager.UpdateLedgerOp) {
            if (increment) {
                ServerStatsProvider.getStatsLoggerInstance()
                        .getSimpleStatLogger(HedwigServerSimpleStatType.PERSIST_QUEUE).inc();
            } else {
                ServerStatsProvider.getStatsLoggerInstance()
                        .getSimpleStatLogger(HedwigServerSimpleStatType.PERSIST_QUEUE).dec();
            }
        }
    }
    public abstract class AsynchronousOp<T> implements Op {
        final public ByteString topic;
        final public Callback<T> cb;
        final public Object ctx;

        public AsynchronousOp(final ByteString topic, final Callback<T> cb, Object ctx) {
            this.topic = topic;
            this.cb = new Callback<T>() {
                @Override
                public void operationFailed(Object ctx, PubSubException exception) {
                    onOperationFailed();
                    cb.operationFailed(ctx, exception);
                    popAndRunNext(topic);
                }

                @Override
                public void operationFinished(Object ctx, T resultOfOperation) {
                    onOperationFinished();
                    cb.operationFinished(ctx, resultOfOperation);
                    popAndRunNext(topic);
                }
            };
            this.ctx = ctx;
        }

        protected void onOperationFailed() {
            // We finished running an async op. Decrement the persist queue size
            // if required
            updatePersistQueueSize(AsynchronousOp.this, false);
        }

        protected void onOperationFinished() {
            // We finished running an async op. Decrement the persist queue size
            // if required
            updatePersistQueueSize(AsynchronousOp.this, false);
        }
    }

    public abstract class TimedAsynchronousOp<T> extends AsynchronousOp<T> {
        final long enqueueTime;
        @SuppressWarnings("rawtypes")
        final Enum opType;

        @SuppressWarnings("rawtypes")
        public TimedAsynchronousOp(final ByteString topic, final Callback<T> cb, Object ctx, Enum opType) {
            super(topic, cb, ctx);
            this.enqueueTime = MathUtils.nowInNano();
            this.opType = opType;
        }

        @Override
        protected void onOperationFailed() {
            ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(opType)
                    .registerFailedEvent(MathUtils.elapsedMicroSec(enqueueTime));
            super.onOperationFailed();
        }

        @Override
        protected void onOperationFinished() {
            ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(opType)
                    .registerSuccessfulEvent(MathUtils.elapsedMicroSec(enqueueTime));
            super.onOperationFinished();
        }
    }

    public abstract class SynchronousOp implements Op {
        final public ByteString topic;

        public SynchronousOp(ByteString topic) {
            this.topic = topic;
        }

        @Override
        public final void run() {
            runInternal();
            onOperationFinished();
            popAndRunNext(topic);
        }

        protected abstract void runInternal();

        protected void onOperationFinished() {
            // We finished running a sync op. Decrement the persist queue size if
            // required.
            updatePersistQueueSize(SynchronousOp.this, false);
        }
    }

    public abstract class TimedSynchronousOp extends SynchronousOp {
        final long enqueueTime;
        @SuppressWarnings("rawtypes")
        final Enum opType;

        @SuppressWarnings("rawtypes")
        public TimedSynchronousOp(ByteString topic, Enum opType) {
            super(topic);
            this.enqueueTime = MathUtils.nowInNano();
            this.opType = opType;
        }

        @Override
        protected void onOperationFinished() {
            ServerStatsProvider.getStatsLoggerInstance().getOpStatsLogger(opType)
                    .registerSuccessfulEvent(MathUtils.elapsedMicroSec(enqueueTime));
            super.onOperationFinished();
        }
    }

    protected synchronized void popAndRunNext(ByteString topic) {
        Queue<Runnable> ops = topic2ops.get(topic);
        if (!ops.isEmpty())
            ops.remove();
        if (!ops.isEmpty())
            scheduler.submit(ops.peek());
    }

    public void pushAndMaybeRun(ByteString topic, Op op) {
        int size;
        synchronized (this) {
            Queue<Runnable> ops = topic2ops.get(topic);
            if (ops == null) {
                ops = new LinkedList<Runnable>();
                topic2ops.put(topic, ops);
            }
            ops.add(op);
            // increment = true
            updatePersistQueueSize(op, true);
            size = ops.size();
        }
        if (size == 1) {
            op.run();
        }
    }

    public Runnable peek(ByteString topic) {
        return topic2ops.get(topic).peek();
    }
}
