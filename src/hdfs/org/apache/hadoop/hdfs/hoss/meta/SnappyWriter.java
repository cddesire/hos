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

import org.apache.hadoop.hdfs.hoss.meta.BlockOutput;
import org.apache.hadoop.hdfs.hoss.meta.SnappyOutputStream;
import org.apache.hadoop.hdfs.hoss.meta.SnappyWriter;
import org.apache.hadoop.hdfs.hoss.meta.Util;

public class SnappyWriter implements BlockOutput {
  public static final SnappyWriter DUMMY = new SnappyWriter();

  private final byte[] buf = new byte[1024*1024];
  private final SnappyOutputStream snappyOutputStream;

  private int currentNumEntries;
  private int maxEntriesPerBlock;
  private boolean flushed;
  private final int maxBlockSize;

  // Only used to initialize dummy
  private SnappyWriter() {
    snappyOutputStream = null;
    maxBlockSize = 0;
  }

  public SnappyWriter(SnappyOutputStream snappyOutputStream, int maxEntriesPerBlock) {
    this.snappyOutputStream = snappyOutputStream;
    this.maxEntriesPerBlock = maxEntriesPerBlock;
    snappyOutputStream.setListener(this);
    maxBlockSize = this.snappyOutputStream.getMaxBlockSize();
  }

  public void afterFlush() {
    maxEntriesPerBlock = Math.max(currentNumEntries, maxEntriesPerBlock);
    currentNumEntries = 0;
    flushed = true;
  }

  @Override
  public void flush(boolean fsync) throws IOException {
    snappyOutputStream.flush();
    if (fsync) {
      snappyOutputStream.fsync();
    }
  }

  @Override
  public void put(byte[] key, int keyLen, byte[] value, int valueLen) throws IOException {
    int keySize = Util.unsignedVLQSize(keyLen + 1) + Util.unsignedVLQSize(valueLen);
    int totalSize = keySize + keyLen + valueLen;

    smartFlush(keySize, totalSize);
    flushed = false;
    currentNumEntries++;

    Util.writeUnsignedVLQ(keyLen + 1, snappyOutputStream);
    Util.writeUnsignedVLQ(valueLen, snappyOutputStream);
    snappyOutputStream.write(key, 0, keyLen);
    snappyOutputStream.write(value, 0, valueLen);


    // Make sure that the beginning of each block is the start of a key/value pair
    if (flushed && snappyOutputStream.getPending() > 0) {
      snappyOutputStream.flush();
    }
  }

  @Override
  public void put(byte[] key, int keyLen, InputStream value, long valueLen) throws IOException {
    int keySize = Util.unsignedVLQSize(keyLen + 1) + Util.unsignedVLQSize(valueLen);
    long totalSize = keySize + keyLen + valueLen;

    smartFlush(keySize, totalSize);
    flushed = false;
    currentNumEntries++;

    Util.writeUnsignedVLQ(keyLen + 1, snappyOutputStream);
    Util.writeUnsignedVLQ(valueLen, snappyOutputStream);
    snappyOutputStream.write(key, 0, keyLen);
    Util.copy(valueLen, value, snappyOutputStream, buf);

    // Make sure that the beginning of each block is the start of a key/value pair
    if (flushed && snappyOutputStream.getPending() > 0) {
      snappyOutputStream.flush();
    }
  }

  private void smartFlush(int keySize, long totalSize) throws IOException {
    int remaining = snappyOutputStream.remaining();
    if (remaining < keySize) {
      flush(false);
    } else if (remaining < totalSize && totalSize < maxBlockSize - remaining) {
      flush(false);
    }
  }

  @Override
  public void delete(byte[] key, int keyLen) throws IOException {
    int keySize = 1 + Util.unsignedVLQSize(keyLen + 1);
    smartFlush(keySize, keySize + keyLen);

    flushed = false;
    currentNumEntries++;

    snappyOutputStream.write(0);
    Util.writeUnsignedVLQ(keyLen, snappyOutputStream);
    snappyOutputStream.write(key, 0, keyLen);

    // Make sure that the beginning of each block is the start of a key/value pair
    if (flushed && snappyOutputStream.getPending() > 0) {
      snappyOutputStream.flush();
    }
  }

  @Override
  public void close(boolean fsync) throws IOException {
    flush(fsync);
    snappyOutputStream.close();
  }

  public int getMaxEntriesPerBlock() {
    return maxEntriesPerBlock;
  }
}
