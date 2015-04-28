/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.hoss.meta;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.hdfs.hoss.meta.BlockPositionedInputStream;

final class UncompressedBlockPositionedInputStream extends BlockPositionedInputStream {

  private final InputStream inputStream;
  private long position;

  public UncompressedBlockPositionedInputStream(InputStream inputStream, long start) {
    position = start;
    this.inputStream = inputStream;
  }

  @Override
  long getBlockPosition() {
    return position;
  }

  @Override
  public int read() throws IOException {
    position++;
    return inputStream.read();
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    position += len;
    return inputStream.read(b, off, len);
  }

  @Override
  public long skip(long n) throws IOException {
    long skipped = inputStream.skip(n);
    position += skipped;
    return skipped;
  }
}
