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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.*;
import java.io.*;
import java.util.*;
import java.sql.Time;

/***************************************************
 * PendingReplicationBlocks does the bookkeeping of all
 * blocks that are getting replicated.
 *
 * It does the following:
 * 1)  record blocks that are getting replicated at this instant.
 * 2)  a coarse grain timer to track age of replication request
 * 3)  a thread that periodically identifies replication-requests
 *     that never made it.
 *
 ***************************************************/
class PendingReplicationBlocks implements Writable {
  private Map<Block, PendingBlockInfo> pendingReplications;
  private ArrayList<Block> timedOutItems;
  Daemon timerThread = null;
  private volatile boolean fsRunning = true;
  PendingReplicationMonitor pendingReplicationMonitor;


  //
  // It might take anywhere between 5 to 10 minutes before
  // a request is timed out.
  //
  private long timeout = 5 * 60 * 1000;
  private long defaultRecheckInterval = 5 * 60 * 1000;

  PendingReplicationBlocks(long timeoutPeriod) {
    if ( timeoutPeriod > 0 ) {
      this.timeout = timeoutPeriod;
    }
    init();
  }

  PendingReplicationBlocks() {
    init();
  }

  void init() {
    pendingReplications = new HashMap<Block, PendingBlockInfo>();
    timedOutItems = new ArrayList<Block>();
    
    if(FSNamesystem.bft){
    	pendingReplicationMonitor = new PendingReplicationMonitor();
    }else{
    	this.timerThread = new Daemon(new PendingReplicationMonitor());
    	timerThread.start();
    }
  }

  /**
   * Add a block to the list of pending Replications
   */
  void add(Block block, int numReplicas) {
    synchronized (pendingReplications) {
      PendingBlockInfo found = pendingReplications.get(block);
      if (found == null) {
        pendingReplications.put(block, new PendingBlockInfo(numReplicas));
      } else {
        found.incrementReplicas(numReplicas);
        found.setTimeStamp();
      }
    }
  }

  /**
   * One replication request for this block has finished.
   * Decrement the number of pending replication requests
   * for this block.
   */
  void remove(Block block) {
    synchronized (pendingReplications) {
      PendingBlockInfo found = pendingReplications.get(block);
      if (found != null) {
      	FSNamesystem.LOG.debug("Removing pending replication for block" + block);
        found.decrementReplicas();
        if (found.getNumReplicas() <= 0) {
          pendingReplications.remove(block);
        }
      }
    }
  }

  /**
   * The total number of blocks that are undergoing replication
   */
  int size() {
    return pendingReplications.size();
  } 

  /**
   * How many copies of this block is pending replication?
   */
  int getNumReplicas(Block block) {
    synchronized (pendingReplications) {
      PendingBlockInfo found = pendingReplications.get(block);
      if (found != null) {
        return found.getNumReplicas();
      }
    }
    return 0;
  }

  /**
   * Returns a list of blocks that have timed out their 
   * replication requests. Returns null if no blocks have
   * timed out.
   */
  Block[] getTimedOutBlocks() {
    synchronized (timedOutItems) {
      if (timedOutItems.size() <= 0) {
        return null;
      }
      Block[] blockList = timedOutItems.toArray(
                                                new Block[timedOutItems.size()]);
      timedOutItems.clear();
      return blockList;
    }
  }

  /**
   * An object that contains information about a block that 
   * is being replicated. It records the timestamp when the 
   * system started replicating the most recent copy of this
   * block. It also records the number of replication
   * requests that are in progress.
   */
  static class PendingBlockInfo {
    private long timeStamp;
    private int numReplicasInProgress;

    PendingBlockInfo(int numReplicas) {
      this.timeStamp = FSNamesystem.now();
      this.numReplicasInProgress = numReplicas;
    }

    long getTimeStamp() {
      return timeStamp;
    }

    void setTimeStamp() {
      timeStamp = FSNamesystem.now();
    }

    void incrementReplicas(int increment) {
      numReplicasInProgress += increment;
    }

    void decrementReplicas() {
      numReplicasInProgress--;
      assert(numReplicasInProgress >= 0);
    }

    int getNumReplicas() {
      return numReplicasInProgress;
    }
  }

  /*
   * A periodic thread that scans for blocks that never finished
   * their replication request.
   */
  class PendingReplicationMonitor implements Runnable {
    public void run() {
      while (fsRunning) {
        long period = Math.min(defaultRecheckInterval, timeout);
        try {
          pendingReplicationCheck();
          Thread.sleep(period);
        } catch (InterruptedException ie) {
          FSNamesystem.LOG.debug(
                "PendingReplicationMonitor thread received exception. " + ie);
        }
      }
    }

    /**
     * Iterate through all items and detect timed-out items
     */
    void pendingReplicationCheck() {
      synchronized (pendingReplications) {
        Iterator iter = pendingReplications.entrySet().iterator();
        long now = FSNamesystem.now();
        FSNamesystem.LOG.debug("PendingReplicationMonitor checking Q");
        while (iter.hasNext()) {
          Map.Entry entry = (Map.Entry) iter.next();
          PendingBlockInfo pendingBlock = (PendingBlockInfo) entry.getValue();
          if (now > pendingBlock.getTimeStamp() + timeout) {
            Block block = (Block) entry.getKey();
            synchronized (timedOutItems) {
              timedOutItems.add(block);
            }
            FSNamesystem.LOG.warn(
                "PendingReplicationMonitor timed out block " + block);
            iter.remove();
          }
        }
      }
    }
  }

  /*
   * Shuts down the pending replication monitor thread.
   * Waits for the thread to exit.
   */
  void stop() {
    fsRunning = false;
    if(timerThread == null)
    	return;
    timerThread.interrupt();
    try {
      timerThread.join(3000);
    } catch (InterruptedException ie) {
    }
  }

  /**
   * Iterate through all items and print them.
   */
  void metaSave(PrintWriter out) {
    synchronized (pendingReplications) {
      out.println("Metasave: Blocks being replicated: " +
                  pendingReplications.size());
      Iterator iter = pendingReplications.entrySet().iterator();
      while (iter.hasNext()) {
        Map.Entry entry = (Map.Entry) iter.next();
        PendingBlockInfo pendingBlock = (PendingBlockInfo) entry.getValue();
        Block block = (Block) entry.getKey();
        out.println(block + 
                    " StartTime: " + new Time(pendingBlock.timeStamp) +
                    " NumReplicaInProgress: " + 
                    pendingBlock.numReplicasInProgress);
      }
    }
  }


  public void readFields(DataInput in) throws IOException {
    FSNamesystem fsNamesys = FSNamesystem.getFSNamesystem();
    BlocksMap blocksMap = fsNamesys.blocksMap;
    
    pendingReplications.clear();
    int size = in.readInt();
    for (int i=0; i < size; i++){
    	Block _block = new Block(in.readLong());
    	//System.out.println("### READ 1 : " + _block);
      Block b = blocksMap.getStoredBlock(_block);
      if(b == null){
      	System.out.println("Block " + b +" was not found in blocksMap");
      	b = _block;
      }
      long timeStamp = in.readLong();
      int numReplicasInProgress = in.readInt();
      
      PendingBlockInfo pbi = new PendingBlockInfo(numReplicasInProgress);
      pbi.timeStamp = timeStamp;
      
      pendingReplications.put(b, pbi);
    }
    
    timedOutItems.clear();
    size = in.readInt();
    for (int i=0; i<size; i++){
      timedOutItems.add(blocksMap.getStoredBlock(new Block(in.readLong())));
    }
    
  }

  public void write(DataOutput out) throws IOException {
  	BlocksMap blocksMap = FSNamesystem.getFSNamesystem().blocksMap;
    //System.out.println("### WRITE 1");
    int size = pendingReplications.size();
    out.writeInt(size);
    //System.out.println("### WRITE 2 : " + size);
    for (Block b: pendingReplications.keySet()){
    	if(blocksMap.getStoredBlock(b) == null){
    		System.out.println("Block " + b + " is in pendingReplicatin but not in blocksmap!");
    	}
      out.writeLong(b.getBlockId());
      //System.out.println("bid"+b.getBlockId());
      PendingBlockInfo pbi = pendingReplications.get(b);
      out.writeLong(pbi.timeStamp);
      //System.out.println("pbistamp:"+pbi.timeStamp);
      out.writeInt(pbi.numReplicasInProgress);
      //System.out.println("nrip"+pbi.numReplicasInProgress);
    }
    //System.out.println("### WRITE 3");

    size = timedOutItems.size();
    out.writeInt(size);
    Iterator<Block> iter = timedOutItems.iterator();
    //System.out.println("### WRITE 4 : " + size);
    while (iter.hasNext()){
      Block b = iter.next();
      out.writeLong(b.getBlockId());
      //System.out.println("bid"+b.getBlockId());
    }
    //System.out.println("### WRITE 5");
    
  }
}
