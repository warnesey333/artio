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
package uk.co.real_logic.artio.example_exchange;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.driver.MediaDriver;
import org.agrona.IoUtil;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SigInt;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.artio.CommonConfiguration;
import uk.co.real_logic.artio.engine.EngineConfiguration;
import uk.co.real_logic.artio.engine.FixEngine;
import uk.co.real_logic.artio.session.Session;
import uk.co.real_logic.artio.validation.AuthenticationStrategy;
import uk.co.real_logic.artio.validation.MessageValidationStrategy;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.aeron.CommonContext.IPC_CHANNEL;
import static io.aeron.driver.ThreadingMode.SHARED;

public class ExchangeApplication
{
    public static final String ACCEPTOR_COMP_ID = "exexchange";
    public static final String INITIATOR_COMP_ID = "extrader";

    private static Session session;

    public static void main(final String[] args) throws Exception
    {
        final MessageValidationStrategy validationStrategy = MessageValidationStrategy.targetCompId(ACCEPTOR_COMP_ID)
            .and(MessageValidationStrategy.senderCompId(Collections.singletonList(INITIATOR_COMP_ID)));

        final AuthenticationStrategy authenticationStrategy = AuthenticationStrategy.of(validationStrategy);

        // Static configuration lasts the duration of a FIX-Gateway instance
        final EngineConfiguration configuration = new EngineConfiguration()
            .bindTo("localhost", 9999)
            .libraryAeronChannel(IPC_CHANNEL);
        configuration.authenticationStrategy(authenticationStrategy);

        cleanupOldLogFileDir(configuration);

        final MediaDriver.Context context = new MediaDriver.Context()
            .threadingMode(SHARED)
            .dirDeleteOnStart(true);

        final Archive.Context archiveContext = new Archive.Context()
            .threadingMode(ArchiveThreadingMode.SHARED)
            .deleteArchiveOnStart(true);

        try (ArchivingMediaDriver driver = ArchivingMediaDriver.launch(context, archiveContext);
            FixEngine gateway = FixEngine.launch(configuration))
        {
            final ExchangeAgent exchangeAgent = new ExchangeAgent();
            final AtomicCounter errorCounter =
                driver.mediaDriver().context().countersManager().newCounter("exchange_agent_errors");
            final AgentRunner runner = new AgentRunner(
                CommonConfiguration.backoffIdleStrategy(),
                Throwable::printStackTrace,
                errorCounter,
                exchangeAgent);

            final Thread thread = AgentRunner.startOnThread(runner);

            // TODO: wait for input:
            final AtomicBoolean running = new AtomicBoolean(true);
            SigInt.register(() -> running.set(false));

            while (running.get())
            {
                Thread.sleep(100);
            }

            thread.join();
        }

        System.exit(0);
    }

    public static void cleanupOldLogFileDir(final EngineConfiguration configuration)
    {
        IoUtil.delete(new File(configuration.logFileDir()), true);
    }
}
