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

import io.aeron.logbuffer.Header;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;
import org.agrona.ErrorHandler;
import org.agrona.IoUtil;
import org.agrona.collections.Long2ObjectCache;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.decoder.HeaderDecoder;
import uk.co.real_logic.artio.messages.FixMessageDecoder;
import uk.co.real_logic.artio.messages.FixMessageEncoder;
import uk.co.real_logic.artio.messages.MessageHeaderDecoder;
import uk.co.real_logic.artio.messages.MessageHeaderEncoder;
import uk.co.real_logic.artio.storage.messages.ReplayIndexRecordEncoder;
import uk.co.real_logic.artio.util.AsciiBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.function.LongFunction;

import static io.aeron.logbuffer.FrameDescriptor.*;
import static org.agrona.UnsafeAccess.UNSAFE;
import static uk.co.real_logic.artio.engine.logger.ReplayIndexDescriptor.*;
import static uk.co.real_logic.artio.messages.MessageStatus.OK;

/**
 * Builds an index of a composite key of session id and sequence number for a given stream.
 *
 * Written Positions are stored in a separate file at {@link ReplayIndexDescriptor#replayPositionPath(String, int)}.
 *
 * Buffer Consists of:
 *
 * MessageHeader
 * Head position counter
 * Tail position counter
 * Multiple ReplayIndexRecord entries
 */
public class ReplayIndex implements Index
{
    private final LongFunction<SessionIndex> newSessionIndex = SessionIndex::new;
    private final AsciiBuffer asciiBuffer = new MutableAsciiBuffer();
    private final MessageHeaderDecoder frameHeaderDecoder = new MessageHeaderDecoder();
    private final FixMessageDecoder messageFrame = new FixMessageDecoder();
    private final HeaderDecoder fixHeader = new HeaderDecoder();
    private final ReplayIndexRecordEncoder replayIndexRecord = new ReplayIndexRecordEncoder();
    private final MessageHeaderEncoder indexHeaderEncoder = new MessageHeaderEncoder();
    private final IndexedPositionWriter positionWriter;
    private final IndexedPositionReader positionReader;

    private final Long2ObjectCache<SessionIndex> fixSessionIdToIndex;

    private final String logFileDir;
    private final int requiredStreamId;
    private final int indexFileSize;
    private final BufferFactory bufferFactory;
    private final AtomicBuffer positionBuffer;
    private final RecordingIdLookup recordingIdLookup;

    public ReplayIndex(
        final String logFileDir,
        final int requiredStreamId,
        final int indexFileSize,
        final int cacheNumSets,
        final int cacheSetSize,
        final BufferFactory bufferFactory,
        final AtomicBuffer positionBuffer,
        final ErrorHandler errorHandler,
        final RecordingIdLookup recordingIdLookup)
    {
        this.logFileDir = logFileDir;
        this.requiredStreamId = requiredStreamId;
        this.indexFileSize = indexFileSize;
        this.bufferFactory = bufferFactory;
        this.positionBuffer = positionBuffer;
        this.recordingIdLookup = recordingIdLookup;

        checkIndexFileSize(indexFileSize);
        fixSessionIdToIndex = new Long2ObjectCache<>(cacheNumSets, cacheSetSize, SessionIndex::close);
        final String replayPositionPath = replayPositionPath(logFileDir, requiredStreamId);
        positionWriter = new IndexedPositionWriter(
            positionBuffer, errorHandler, 0, replayPositionPath);
        positionReader = new IndexedPositionReader(positionBuffer);
    }

    @Override
    public int doWork()
    {
        return recordingIdLookup.poll();
    }

    long continuedFixSessionId;
    int continuedSequenceNumber;
    int continuedSequenceIndex;

    public void onFragment(
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int srcLength,
        final Header header)
    {
        final int streamId = header.streamId();
        final long endPosition = header.position();
        final byte flags = header.flags();
        final int length = BitUtil.align(srcLength, FRAME_ALIGNMENT);

        if (streamId != requiredStreamId)
        {
            return;
        }

        final boolean beginMessage = (flags & BEGIN_FRAG_FLAG) == BEGIN_FRAG_FLAG;
        if ((flags & UNFRAGMENTED) == UNFRAGMENTED || beginMessage)
        {
            int offset = srcOffset;
            frameHeaderDecoder.wrap(srcBuffer, offset);
            if (frameHeaderDecoder.templateId() == FixMessageEncoder.TEMPLATE_ID)
            {
                final int actingBlockLength = frameHeaderDecoder.blockLength();
                offset += frameHeaderDecoder.encodedLength();

                messageFrame.wrap(srcBuffer, offset, actingBlockLength, frameHeaderDecoder.version());
                if (messageFrame.status() == OK)
                {
                    offset += actingBlockLength + 2;

                    asciiBuffer.wrap(srcBuffer);
                    fixHeader.decode(asciiBuffer, offset, messageFrame.bodyLength());

                    final long fixSessionId = messageFrame.session();
                    final int sequenceNumber = fixHeader.msgSeqNum();
                    final int sequenceIndex = messageFrame.sequenceIndex();

                    if (beginMessage)
                    {
                        continuedFixSessionId = fixSessionId;
                        continuedSequenceNumber = sequenceNumber;
                        continuedSequenceIndex = sequenceIndex;
                    }

                    fixSessionIdToIndex
                        .computeIfAbsent(fixSessionId, newSessionIndex)
                        .onRecord(streamId, endPosition, length, sequenceNumber, sequenceIndex, header);
                }
            }
        }
        else
        {
            fixSessionIdToIndex
                .computeIfAbsent(continuedFixSessionId, newSessionIndex)
                .onRecord(streamId, endPosition, length, continuedSequenceNumber, continuedSequenceIndex, header);
        }
    }

    public void close()
    {
        positionWriter.close();
        fixSessionIdToIndex.clear();
        IoUtil.unmap(positionBuffer.byteBuffer());
    }

    public void readLastPosition(final IndexedPositionConsumer consumer)
    {
        positionReader.readLastPosition(consumer);
    }

    private final class SessionIndex implements AutoCloseable
    {
        private final ByteBuffer wrappedBuffer;
        private final AtomicBuffer buffer;
        private final int capacity;

        SessionIndex(final long fixSessionId)
        {
            final File logFile = logFile(logFileDir, fixSessionId, requiredStreamId);
            final boolean exists = logFile.exists();
            this.wrappedBuffer = bufferFactory.map(logFile, indexFileSize);
            this.buffer = new UnsafeBuffer(wrappedBuffer);

            capacity = recordCapacity(buffer.capacity());
            if (!exists)
            {
                indexHeaderEncoder
                    .wrap(buffer, 0)
                    .blockLength(replayIndexRecord.sbeBlockLength())
                    .templateId(replayIndexRecord.sbeTemplateId())
                    .schemaId(replayIndexRecord.sbeSchemaId())
                    .version(replayIndexRecord.sbeSchemaVersion());
            }
            else
            {
                // Reset the positions in order to avoid wraps at the start.
                final int resetPosition = offset(beginChange(buffer), capacity);
                beginChangeOrdered(buffer, resetPosition);
                endChangeOrdered(buffer, resetPosition);
            }
        }

        void onRecord(
            final int streamId,
            final long endPosition,
            final int length,
            final int sequenceNumber,
            final int sequenceIndex,
            final Header header)
        {
            final long beginChangePosition = beginChange(buffer);
            final long changePosition = beginChangePosition + RECORD_LENGTH;
            final int aeronSessionId = header.sessionId();
            final long recordingId = recordingIdLookup.getRecordingId(aeronSessionId);
            final long beginPosition = endPosition - length;

            beginChangeOrdered(buffer, changePosition);
            UNSAFE.storeFence();

            final int offset = offset(beginChangePosition, capacity);

            replayIndexRecord
                .wrap(buffer, offset)
                .streamId(streamId)
                .position(beginPosition)
                .sequenceNumber(sequenceNumber)
                .sequenceIndex(sequenceIndex)
                .recordingId(recordingId)
                .length(length);

            positionWriter.indexedUpTo(aeronSessionId, recordingId, endPosition);
            positionWriter.updateChecksums();

            endChangeOrdered(buffer, changePosition);
        }

        public void close()
        {
            IoUtil.unmap(wrappedBuffer);
        }
    }
}
