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
package org.apache.hadoop.hdfs.hoss.smallobject;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Writable;

public class KeyWritable implements Writable {
	//object id 
	private LongWritable objId = new LongWritable();
	//object size
	private LongWritable size = new LongWritable();
	
	public KeyWritable(){
		
	}
	
	public KeyWritable(long objId, long size){
		this.objId.set(objId);
		this.size.set(size);
	}
	
	public long getObjId() {
		return objId.get();
	}
	
	public long size(){
		return size.get();
	}
	
	@Override
	public void write(DataOutput out) throws IOException {
		objId.write(out);
		size.write(out);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		objId.readFields(in);
		size.readFields(in);
	}

	@Override
	public String toString() {
		return "object id: " + objId.get();
	}

}
