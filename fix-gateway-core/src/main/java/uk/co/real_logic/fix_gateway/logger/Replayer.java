/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.logger;

import uk.co.real_logic.aeron.Publication;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.BufferClaim;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.MutableDirectBuffer;
import uk.co.real_logic.fix_gateway.decoder.ResendRequestDecoder;
import uk.co.real_logic.fix_gateway.dictionary.IntDictionary;
import uk.co.real_logic.fix_gateway.messages.FixMessageDecoder;
import uk.co.real_logic.fix_gateway.otf.OtfParser;
import uk.co.real_logic.fix_gateway.session.SessionHandler;
import uk.co.real_logic.fix_gateway.util.AsciiFlyweight;
import uk.co.real_logic.fix_gateway.util.MutableAsciiFlyweight;

import java.nio.charset.StandardCharsets;

import static uk.co.real_logic.fix_gateway.logger.PossDupFinder.NO_ENTRY;

public class Replayer implements SessionHandler, LogHandler
{
    public static final int SIZE_OF_LENGTH_FIELD = 2;
    public static final byte[] POSS_DUP_FIELD = "43=Y\001".getBytes(StandardCharsets.US_ASCII);

    private final ResendRequestDecoder resendRequest = new ResendRequestDecoder();
    private final AsciiFlyweight asciiFlyweight = new AsciiFlyweight();
    private final MutableAsciiFlyweight mutableAsciiFlyweight = new MutableAsciiFlyweight();
    private final LogScanner logScanner;
    private final Publication publication;

    private final BufferClaim claim;
    private final PossDupFinder acceptor = new PossDupFinder();
    private final OtfParser parser = new OtfParser(acceptor, new IntDictionary());

    public Replayer(final LogScanner logScanner, final Publication publication, final BufferClaim claim)
    {
        this.logScanner = logScanner;
        this.publication = publication;
        this.claim = claim;
    }

    public void onMessage(final DirectBuffer srcBuffer,
                          final int srcOffset,
                          final int length,
                          final long connectionId,
                          final long sessionId,
                          final int messageType)
    {
        if (messageType == ResendRequestDecoder.MESSAGE_TYPE)
        {
            asciiFlyweight.wrap(srcBuffer);
            resendRequest.decode(asciiFlyweight, srcOffset, length);

            final int beginSeqNo = resendRequest.beginSeqNo();
            final int endSeqNo = resendRequest.endSeqNo();
            if (endSeqNo < beginSeqNo)
            {
                return;
            }

            logScanner.query(this, sessionId, beginSeqNo, endSeqNo);
        }
    }

    public boolean onLogEntry(
        final FixMessageDecoder messageFrame,
        final DirectBuffer srcBuffer,
        final int srcOffset,
        final int messageOffset,
        final int srcLength)
    {
        final int messageLength = srcLength - (messageOffset - srcOffset);
        parser.onMessage(srcBuffer, messageOffset, messageLength);
        final int possDupSrcOffset = acceptor.possDupOffset();
        try
        {
            if (possDupSrcOffset == NO_ENTRY)
            {
                final int newLength = srcLength + POSS_DUP_FIELD.length;
                claimBuffer(newLength);
                copyPossDupField(srcBuffer, srcOffset, srcLength, claim.buffer(), claim.offset());
            }
            else
            {
                claimBuffer(srcLength);

                final MutableDirectBuffer claimBuffer = claim.buffer();
                final int claimOffset = claim.offset();
                claimBuffer.putBytes(claimOffset, srcBuffer, srcOffset, srcLength);
                setPossDupFlag(srcOffset, possDupSrcOffset, claimBuffer, claimOffset);
            }
        }
        finally
        {
            // TODO: tombstone the claim on exception
            claim.commit();
        }

        return true;
    }

    private void claimBuffer(final int newLength)
    {
        while (publication.tryClaim(newLength, claim) < 0)
        {
            // TODO: backoff
            Thread.yield();
        }
    }

    private void copyPossDupField(final DirectBuffer srcBuffer,
                                  final int srcOffset,
                                  final int srcLength,
                                  final MutableDirectBuffer claimBuffer,
                                  final int claimOffset)
    {
        final int sendingTimeSrcOffset = acceptor.sendingTimeOffset();
        final int firstLength = sendingTimeSrcOffset - srcOffset;
        final int sendingTimeClaimOffset = srcToClaim(sendingTimeSrcOffset, srcOffset, claimOffset);
        final int remainingClaimOffset = sendingTimeClaimOffset + POSS_DUP_FIELD.length;

        claimBuffer.putBytes(claimOffset, srcBuffer, srcOffset, firstLength);
        claimBuffer.putBytes(sendingTimeClaimOffset, POSS_DUP_FIELD);
        claimBuffer.putBytes(remainingClaimOffset, srcBuffer, sendingTimeSrcOffset, srcLength - firstLength);
    }

    private void setPossDupFlag(final int srcOffset,
                                final int possDupSrcOffset,
                                final MutableDirectBuffer claimBuffer,
                                final int claimOffset)
    {
        final int possDupClaimOffset = srcToClaim(possDupSrcOffset, srcOffset, claimOffset);
        mutableAsciiFlyweight.wrap(claimBuffer);
        mutableAsciiFlyweight.putChar(possDupClaimOffset, 'Y');
    }

    private int srcToClaim(final int srcIndexedOffset,
                           final int srcOffset,
                           final int claimOffset)
    {
        return srcIndexedOffset - srcOffset + claimOffset;
    }

}
