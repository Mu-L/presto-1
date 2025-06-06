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
package com.facebook.presto.iceberg;

import com.facebook.presto.hive.HdfsContext;
import com.facebook.presto.hive.HdfsEnvironment;
import com.facebook.presto.spi.PrestoException;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.hadoop.HadoopInputFile;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.SeekableInputStream;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.facebook.presto.iceberg.IcebergErrorCode.ICEBERG_FILESYSTEM_ERROR;
import static java.util.Objects.requireNonNull;

public class HdfsInputFile
        implements InputFile
{
    private final InputFile delegate;
    private final HdfsEnvironment environment;
    private final String user;
    private final AtomicLong length;

    public HdfsInputFile(Path path, HdfsEnvironment environment, HdfsContext context, Optional<Long> length)
    {
        requireNonNull(path, "path is null");
        this.environment = requireNonNull(environment, "environment is null");
        this.length = new AtomicLong(length.orElse(-1L));
        requireNonNull(context, "context is null");
        try {
            if (this.length.get() < 0) {
                this.delegate = HadoopInputFile.fromPath(path, environment.getFileSystem(context, path), environment.getConfiguration(context, path));
            }
            else {
                this.delegate = HadoopInputFile.fromPath(path, this.length.get(), environment.getFileSystem(context, path), environment.getConfiguration(context, path));
            }
        }
        catch (IOException e) {
            throw new PrestoException(ICEBERG_FILESYSTEM_ERROR, "Failed to create input file: " + path, e);
        }
        this.user = context.getIdentity().getUser();
    }

    public HdfsInputFile(Path path, HdfsEnvironment environment, HdfsContext context)
    {
        this(path, environment, context, Optional.empty());
    }

    @Override
    public long getLength()
    {
        return length.updateAndGet(value -> {
            if (value < 0) {
                return environment.doAs(user, delegate::getLength);
            }
            return value;
        });
    }

    @Override
    public SeekableInputStream newStream()
    {
        // Hack: this wrapping is required to circumvent https://github.com/prestodb/presto/issues/16206
        return new HdfsInputStream(environment.doAs(user, delegate::newStream));
    }

    @Override
    public String location()
    {
        return delegate.location();
    }

    @Override
    public boolean exists()
    {
        return environment.doAs(user, delegate::exists);
    }

    private static class HdfsInputStream
            extends SeekableInputStream
    {
        private final SeekableInputStream delegate;

        public HdfsInputStream(SeekableInputStream delegate)
        {
            this.delegate = requireNonNull(delegate, "delegate is null");
        }

        @Override
        public int read()
                throws IOException
        {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len)
                throws IOException
        {
            return delegate.read(b, off, len);
        }

        @Override
        public long getPos()
                throws IOException
        {
            return delegate.getPos();
        }

        @Override
        public void seek(long newPos)
                throws IOException
        {
            delegate.seek(newPos);
        }

        @Override
        public void close()
                throws IOException
        {
            delegate.close();
        }
    }
}
