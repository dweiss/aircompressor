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
package io.airlift.compress.v3.bzip2;

import io.airlift.compress.v3.hadoop.HadoopInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;

// forked from Apache Hadoop
class BZip2HadoopInputStream
        extends HadoopInputStream
{
    private final InputStream in;
    private final ExecutorService executor;
    private final int maxConcurrentInFlight;
    private final byte[] oneByte = new byte[1];
    private CBZip2InputStream input;

    public BZip2HadoopInputStream(InputStream in)
    {
        this(in, null, 0);
    }

    BZip2HadoopInputStream(InputStream in, ExecutorService executor, int maxConcurrentInFlight)
    {
        this.in = requireNonNull(in, "in is null");
        this.executor = executor;
        this.maxConcurrentInFlight = maxConcurrentInFlight;
    }

    @Override
    public int read(byte[] buffer, int offset, int length)
            throws IOException
    {
        if (length == 0) {
            return 0;
        }

        if (input == null) {
            input = executor == null
                    ? new CBZip2InputStream(in, true)
                    : new CBZip2InputStream(in, true, executor, maxConcurrentInFlight);
        }

        return input.read(buffer, offset, length);
    }

    @Override
    public int read()
            throws IOException
    {
        int result = read(oneByte, 0, 1);
        if (result < 0) {
            return result;
        }
        return oneByte[0] & 0xff;
    }

    @Override
    public void resetState()
    {
        // drop the current compression stream and the input it has buffered, and new one will be created during the next read
        input = null;
    }

    @Override
    public void close()
            throws IOException
    {
        input = null;
        in.close();
    }
}
