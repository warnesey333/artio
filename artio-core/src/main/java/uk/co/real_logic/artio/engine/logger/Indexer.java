/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ArchiveException;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.collections.CollectionUtil;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentInvoker;
import org.agrona.concurrent.IdleStrategy;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.DebugLogger;
import uk.co.real_logic.artio.LogTag;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;
import uk.co.real_logic.artio.engine.CompletionPosition;

import java.util.List;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static uk.co.real_logic.artio.GatewayProcess.ARCHIVE_REPLAY_STREAM;

/**
 * Incrementally builds indexes by polling a subscription.
 */
public class Indexer implements Agent, ControlledFragmentHandler
{
    private static final int LIMIT = 20;

    private final List<Index> indices;
    private final Subscription subscription;
    private final String agentNamePrefix;
    private final CompletionPosition completionPosition;

    public Indexer(
        final List<Index> indices,
        final Subscription subscription,
        final String agentNamePrefix,
        final CompletionPosition completionPosition,
        final AeronArchive aeronArchive,
        final ErrorHandler errorHandler)
    {
        this.indices = indices;
        this.subscription = subscription;
        this.agentNamePrefix = agentNamePrefix;
        this.completionPosition = completionPosition;
        catchIndexUp(aeronArchive, errorHandler);
    }

    public int doWork()
    {
        return subscription.controlledPoll(this, LIMIT) + CollectionUtil.sum(indices, Index::doWork);
    }

    private void catchIndexUp(final AeronArchive aeronArchive, final ErrorHandler errorHandler)
    {
        final IdleStrategy idleStrategy = CommonConfiguration.backoffIdleStrategy();
        final AgentInvoker aeronInvoker = aeronArchive.context().aeron().conductorAgentInvoker();

        for (final Index index : indices)
        {
            index.readLastPosition((aeronSessionId, recordingId, indexStoppedposition) ->
            {
                try
                {
                    final long recordingStoppedPosition = aeronArchive.getStopPosition(recordingId);
                    if (recordingStoppedPosition > indexStoppedposition)
                    {
                        DebugLogger.log(
                            LogTag.INDEX,
                            "Catchup [%s]: recordingId = %d, recordingStopped @ %d, indexStopped @ %d",
                            index.getName(),
                            recordingId,
                            recordingStoppedPosition,
                            indexStoppedposition);

                        final long length = recordingStoppedPosition - indexStoppedposition;
                        try (Subscription subscription = aeronArchive.replay(
                            recordingId, indexStoppedposition, length, IPC_CHANNEL, ARCHIVE_REPLAY_STREAM))
                        {
                            // Only do 1 replay at a time
                            while (subscription.imageCount() != 1)
                            {
                                idle(idleStrategy, aeronInvoker);
                            }
                            idleStrategy.reset();

                            final Image replayImage = subscription.imageAtIndex(0);

                            while (replayImage.position() < recordingStoppedPosition)
                            {
                                replayImage.poll(index, LIMIT);

                                idle(idleStrategy, aeronInvoker);
                            }
                            idleStrategy.reset();
                        }
                    }
                }
                catch (final ArchiveException e)
                {
                    errorHandler.onError(e);
                }
            });
        }
    }

    private void idle(final IdleStrategy idleStrategy, final AgentInvoker aeronInvoker)
    {
        if (aeronInvoker != null)
        {
            aeronInvoker.invoke();
        }

        idleStrategy.idle();
    }

    public Action onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        final int streamId = header.streamId();
        final int aeronSessionId = header.sessionId();
        final long endPosition = header.position();
        DebugLogger.log(
            LogTag.INDEX,
            "Indexing @ %d from [%d, %d]%n",
            endPosition,
            streamId,
            aeronSessionId);
        for (int i = 0; i < indices.size(); i++)
        {
            index.onFragment(buffer, offset, length, header);
        }

        return CONTINUE;
    }

    public void onClose()
    {
        quiesce();

        Exceptions.closeAll(() -> Exceptions.closeAll(indices), subscription);
    }

    private void quiesce()
    {
        while (!completionPosition.hasCompleted())
        {
            Thread.yield();
        }

        if (completionPosition.wasStartupComplete())
        {
            return;
        }

        // We know that any remaining data to quiesce at this point must be in the subscription.
        subscription.controlledPoll(this::quiesceFragment, Integer.MAX_VALUE);
    }

    private Action quiesceFragment(
        final DirectBuffer buffer, final int offset, final int length, final Header header)
    {
        if (completedPosition(header.sessionId()) <= header.position())
        {
            return onFragment(buffer, offset, length, header);
        }

        return CONTINUE;
    }

    private long completedPosition(final int aeronSessionId)
    {
        return completionPosition.positions().get(aeronSessionId);
    }

    public String roleName()
    {
        return agentNamePrefix + "Indexer";
    }
}
