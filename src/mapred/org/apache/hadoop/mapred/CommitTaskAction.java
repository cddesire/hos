/* Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Represents a directive from the {@link org.apache.hadoop.mapred.JobTracker}
 * to the {@link org.apache.hadoop.mapred.TaskTracker} to commit the output
 * of the task.
 *
 */
class CommitTaskAction extends TaskTrackerAction {
  private TaskAttemptID taskId;

  public CommitTaskAction() {
    super(ActionType.COMMIT_TASK);
    taskId = new TaskAttemptID();
  }

  public CommitTaskAction(TaskAttemptID taskId) {
    super(ActionType.COMMIT_TASK);
    this.taskId = taskId;
  }

  public TaskAttemptID getTaskID() {
    return taskId;
  }

  public void write(DataOutput out) throws IOException {
    taskId.write(out);
  }

  public void readFields(DataInput in) throws IOException {
    taskId.readFields(in);
  }
}
