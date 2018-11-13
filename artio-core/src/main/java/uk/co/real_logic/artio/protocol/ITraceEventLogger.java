package uk.co.real_logic.artio.protocol;

import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import org.agrona.DirectBuffer;

public interface ITraceEventLogger
{
    void log(DirectBuffer buffer, int offset, int length, int messageType);

    void log(String name, Subscription subscription);

    void log(String name, ExclusivePublication exclusivePublication);

    void log(long eventId);
}
