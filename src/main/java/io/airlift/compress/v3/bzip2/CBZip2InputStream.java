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

import java.io.InputStream;
import java.util.concurrent.ExecutorService;

/**
 * The name under which {@link BZip2InputStream} was published in version 3.8.
 *
 * @deprecated use {@link BZip2InputStream}
 */
@Deprecated
public class CBZip2InputStream
        extends BZip2InputStream
{
    public CBZip2InputStream(InputStream in)
    {
        super(in);
    }

    public CBZip2InputStream(InputStream in, boolean decompressConcatenated)
    {
        super(in, decompressConcatenated);
    }

    public CBZip2InputStream(InputStream in, boolean decompressConcatenated, ExecutorService executor, int maxConcurrentInFlight)
    {
        super(in, decompressConcatenated, executor, maxConcurrentInFlight);
    }
}
