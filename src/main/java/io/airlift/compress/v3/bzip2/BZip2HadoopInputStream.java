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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

// forked from Apache Hadoop
class BZip2HadoopInputStream
        extends HadoopInputStream
{
    private final BufferedInputStream bufferedIn;
    private final byte[] oneByte = new byte[1];
    private CBZip2InputStream input;

    public BZip2HadoopInputStream(InputStream in)
    {
        bufferedIn = new BufferedInputStream(in);
    }

    @Override
    public int read(byte[] buffer, int offset, int length)
            throws IOException
    {
        if (length == 0) {
            return 0;
        }

        if (input == null) {
            // If the stream starts with `BZ`, skip it
            bufferedIn.mark(2);
            if (bufferedIn.read() != 'B' || bufferedIn.read() != 'Z') {
                bufferedIn.reset();
            }
            input = new CBZip2InputStream(bufferedIn);
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
        // drop the current compression stream, and new one will be created during the next read
        input = null;
    }

    @Override
    public void close()
            throws IOException
    {
        input = null;
        bufferedIn.close();
    }
}
