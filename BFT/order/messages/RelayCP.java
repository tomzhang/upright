// $Id$

package BFT.order.messages;

import BFT.util.UnsignedTypes;
import BFT.messages.MacMessage;
import BFT.order.statemanagement.CheckPointState;
import BFT.order.messages.MessageTags;


import BFT.messages.VerifiedMessageBase;

import BFT.Debug;

/**
 
 **/
public class RelayCP extends MacMessage{


    public RelayCP (CheckPointState cps,  int sendingReplica){
	super(MessageTags.RelayCP, computeSize(cps), sendingReplica);
	
	checkPoint = cps;

	int offset = getOffset();
	byte[] bytes = getBytes();

	byte[] tmp = cps.getBytes();
	// put down the certificateentry
	for (int i = 0; i < tmp.length; i++, offset++)
	    bytes[offset] = tmp[i];

	if (offset != getBytes().length - getAuthenticationSize())
	    Debug.kill("error in writing bytes down");
    }
    
    public RelayCP(byte[] bits){
	super(bits);
	int offset = getOffset();
	byte[] tmp;


	tmp = new byte[getBytes().length - getAuthenticationSize() - offset];
	for (int i = 0; i < tmp.length; i++, offset++)
	    tmp[i] = bits[offset];
	checkPoint = new CheckPointState(tmp);


	if (offset != getBytes().length - getAuthenticationSize())
	    throw new RuntimeException("invalid byte array");
    }

    public long getSequenceNumber(){
	return checkPoint.getCurrentSequenceNumber();
    }

    protected CheckPointState checkPoint;
    public CheckPointState getCheckPoint(){
	return checkPoint;
    }

    public int getSendingReplica(){
	return (int) getSender();
    }


    /** computes the size of the bits specific to RelayCP **/
    private static int computeSize(CheckPointState cps){
	return cps.getSize();
    }


    
}