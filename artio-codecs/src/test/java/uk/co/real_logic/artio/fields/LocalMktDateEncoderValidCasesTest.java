/*
 * Copyright 2015-2017 Real Logic Ltd.
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
package uk.co.real_logic.artio.fields;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

import static org.junit.Assert.assertThat;
import static uk.co.real_logic.artio.fields.LocalMktDateDecoderValidCasesTest.toLocalDay;
import static uk.co.real_logic.artio.util.CustomMatchers.sequenceEqualsAscii;

@RunWith(Parameterized.class)
public class LocalMktDateEncoderValidCasesTest
{
    private final String timestamp;

    @Parameters(name = "{0}")
    public static Iterable<Object> data()
    {
        return LocalMktDateDecoderValidCasesTest.data();
    }

    public LocalMktDateEncoderValidCasesTest(final String timestamp)
    {
        this.timestamp = timestamp;
    }

    @Test
    public void canParseTimestamp()
    {
        final int localDays = toLocalDay(timestamp);

        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[LocalMktDateEncoder.LENGTH]);
        final MutableAsciiBuffer timestampBytes = new MutableAsciiBuffer(buffer);
        LocalMktDateEncoder.encode(localDays, timestampBytes, 0);

        assertThat(timestampBytes, sequenceEqualsAscii(timestamp, 0, LocalMktDateEncoder.LENGTH));
    }
}