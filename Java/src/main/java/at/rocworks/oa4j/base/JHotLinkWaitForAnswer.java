/*
    OA4J - WinCC Open Architecture for Java
    Copyright (C) 2017 Andreas Vogler

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package at.rocworks.oa4j.base;

import java.util.Date;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

/**
 *
 * @author vogler
 */
public abstract class JHotLinkWaitForAnswer implements Runnable {
    protected long cptr = 0; // c++ pointer to callback function (needed for disconnect)
    protected long cid = 0; // e.g. queryId (needed for disconnect)
    
    private int retCode;
    private int hdlId = 0;
    
    private static volatile int hdlSequence = 0;
    
    private JDpVCGroup message; 
    private JDpMsgAnswer answer;
    
    protected IAnswer cbAnswer;
    protected IHotLink cbHotlink;

    private Date initTimestamp;
    private Date doneTimestamp;
    public Date getInitTimestamp() {
        return initTimestamp;
    }
    public Date getDoneTimestamp() {
        return doneTimestamp;
    }
    
    protected final JSemaphore gotAnswer = new JSemaphore(false);
    
    // async
    private boolean async = false;    
    private LinkedBlockingQueue<JDpVCGroup> msgQueue;
    //private SemaphoreAnalog msgWait;    
    private final int MAX_QUEUE_SIZE = JManager.MAX_DEQUEUE_SIZE;
    private Thread thread;
    
    protected void setAsync() {
        setAsync(true);
    }
    
    protected void setAsync(boolean async) {
        if ( async && !this.async ) {
            if ( msgQueue == null ) 
                msgQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
            (thread=new Thread(this)).start();
        }
        if ( !async && this.async ) {
            thread.interrupt(); // stop thread
        }            
        this.async=async;    
    }    
    
    protected void setCPtr(long cptr) {
        this.cptr=cptr;
    }
    
    protected long getCPtr() {
        return this.cptr;
    }    
    
    protected void setCId(long cid) {
        this.cid = cid;
    }

    protected long getCId() {
        return this.cid;
    }
    
    protected int getHdlId() {
        if ( this.hdlId > 0 ) 
            return this.hdlId;
        else
            return this.hdlId = ++hdlSequence;
        
        //return this.hashCode();
    }
    
    public int getRetCode() {
        return retCode;
    }
    
    protected abstract int execute();
    
    protected JHotLinkWaitForAnswer action(IAnswer answer) {
        // if a callback is defined it should be async, so that 
        // the callback function does not block our manager loop    
        setAsync();
        this.cbAnswer=answer;
        return this;
    }         
    
    protected JHotLinkWaitForAnswer action(IHotLink hotlink) {
        // if a callback is defined it should be async, so that 
        // the callback function does not block our manager loop       
        setAsync(); 
        this.cbHotlink=hotlink;
        return this;
    }     
    
    protected void hotlink(JDpHLGroup group) {
        if ( this.cbHotlink != null) 
            this.cbHotlink.hotlink(group);
    }

    protected void answer(JDpMsgAnswer answer) {
        if (this.cbAnswer != null)
            this.cbAnswer.answer(answer);
    }    
    
    protected int call() {
        if ((retCode = execute()) != 0) { 
            answer = new JDpMsgAnswer();
            answer.setRetCode(retCode);
            gotAnswer.sendTrue();
        }
        return retCode;
    }
    protected void register() {
        JManager.getInstance().register(this);
    }
    
    protected void deregister() {
        JManager.getInstance().deregister(this);
        
        if ( async ) {
            thread.interrupt();
            //msgWait.addOne(); // notifiy thread        
        }        
    }
    
    public void waitForAnswer() {
        gotAnswer.awaitTrue();
    }    
    
    protected void setAnswer(JDpMsgAnswer answer) {
        this.answer=answer;
        this.answer.setRetCode(this.getRetCode());
    }
    
    public JDpMsgAnswer getAnswer() {
        return answer;
    }

    protected synchronized void answerInit() {
        initTimestamp = new Date();
        message = new JDpMsgAnswer(); 
    }

    protected synchronized void answerError(int errCode, String errText) {
        if (message!=null)
            message.setError(errCode, errText);
    }
    
    protected synchronized void hotlinkInit() {
        message = new JDpHLGroup();
    }
    
    protected synchronized void callbackItem(JDpVCItem item) {        
        message.addItem(item);
    }
    
    protected synchronized void callbackDone() {
        doneTimestamp = new Date();
        if (async) {
            callbackDoneAsync();
        } else {
            callbackDoneSync();
        }
    }
    
    private void callbackDoneSync()  {
        if (message != null) {
            if (message instanceof JDpMsgAnswer) { // Answer
                setAnswer((JDpMsgAnswer) message);
                answer(getAnswer());
                gotAnswer.sendTrue();
            } else if (message instanceof JDpHLGroup) { // Hotlink
                hotlink((JDpHLGroup) message);
            }
        }        
    }
    
    private void callbackDoneAsync() {
        try {
            msgQueue.add(this.message);
        } catch ( IllegalStateException ex ) { // Queue Full
            JDebug.StackTrace(Level.SEVERE, ex);
        }    
    }
    
    @Override
    public void run() {
        try {        
            while (true) {
                //JDebug.out.log(Level.INFO, "run queue wait...");
                JDpVCGroup msg;
                    msg = msgQueue.take();
                    if (msg instanceof JDpMsgAnswer) { 
                        setAnswer((JDpMsgAnswer) msg);
                        answer(getAnswer());
                        gotAnswer.sendTrue();
                    } else if (msg instanceof JDpHLGroup) {
                        try {
                            hotlink((JDpHLGroup) msg);
                        } catch (Exception ex) {
                            JDebug.StackTrace(Level.SEVERE, ex);
                        }
                    }                
            }
        } catch (InterruptedException ex) {
            //JDebug.out.log(Level.INFO, "run thread interrupted!");
        }                    
        //JDebug.out.log(Level.INFO, "run thread ended!");
    }
}
