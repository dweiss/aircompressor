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
import io.airlift.compress.v3.hadoop.HadoopOutputStream;
import io.airlift.compress.v3.hadoop.HadoopStreams;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

public class BZip2HadoopStreams
        implements HadoopStreams
{
    private final ExecutorService executor;
    private final int maxConcurrentInFlight;

    public BZip2HadoopStreams()
    {
        this.executor = null;
        this.maxConcurrentInFlight = 0;
    }

    /**
     * Creates streams whose input streams decompress the blocks of the bzip2 data concurrently.
     *
     * @param executor runs the block decompressions; not shut down or otherwise owned by the streams
     * @param maxConcurrentInFlight the maximum number of blocks each input stream reads ahead and
     * decompresses concurrently, see {@link BZip2InputStream#BZip2InputStream(InputStream, boolean, ExecutorService, int)}
     */
    public BZip2HadoopStreams(ExecutorService executor, int maxConcurrentInFlight)
    {
        this.executor = requireNonNull(executor, "executor is null");
        if (maxConcurrentInFlight < 1) {
            throw new IllegalArgumentException("maxConcurrentInFlight must be at least 1: " + maxConcurrentInFlight);
        }
        this.maxConcurrentInFlight = maxConcurrentInFlight;
    }

    @Override
    public String getDefaultFileExtension()
    {
        return ".bz2";
    }

    @Override
    public List<String> getHadoopCodecName()
    {
        return singletonList("org.apache.hadoop.io.compress.BZip2Codec");
    }

    @Override
    public HadoopInputStream createInputStream(InputStream in)
    {
        return new BZip2HadoopInputStream(in, executor, maxConcurrentInFlight);
    }

    @Override
    public HadoopOutputStream createOutputStream(OutputStream out)
    {
        return new BZip2HadoopOutputStream(out);
    }
}
