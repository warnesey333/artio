/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.engine.framer;

import uk.co.real_logic.fix_gateway.engine.SessionInfo;
import uk.co.real_logic.fix_gateway.library.session.Session;
import uk.co.real_logic.fix_gateway.library.session.SessionParser;

class GatewaySession implements SessionInfo
{
    private final long connectionId;
    private final String address;
    private final ReceiverEndPoint receiverEndPoint;

    private long sessionId;
    private Session session;

    GatewaySession(final long connectionId,
                   final long sessionId,
                   final String address,
                   final ReceiverEndPoint receiverEndPoint)
    {
        this.connectionId = connectionId;
        this.sessionId = sessionId;
        this.address = address;
        this.receiverEndPoint = receiverEndPoint;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public String address()
    {
        return address;
    }

    public void sessionId(final long sessionId)
    {
        this.sessionId = sessionId;
    }

    // TODO: package scope everything below here
    public long sessionId()
    {
        return sessionId;
    }

    int sessionBufferSize()
    {
        return 0;
    }

    int heartbeatIntervalInS()
    {
        return 0;
    }

    long sendingTimeWindow()
    {
        return 0;
    }

    void manage(final SessionParser sessionParser, final Session session)
    {
        this.session = session;
        receiverEndPoint.manage(sessionParser);
    }

    int poll(final long time)
    {
        return session.poll(time);
    }

    public String toString()
    {
        return "GatewaySession{" +
            "connectionId=" + connectionId +
            ", address='" + address + '\'' +
            ", sessionId=" + sessionId +
            '}';
    }
}