/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006 MenTaLguY <mental@rydia.net>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.ext.fiber;

import org.jruby.Ruby;
import org.jruby.RubyThread;
import org.jruby.anno.CRuby;
import org.jruby.exceptions.RaiseException;
import org.jruby.ext.fiber.ThreadFiber.FiberRequest;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A RubyThread-aware BlockingQueue wrapper used by Fiber for transferring values.
 */
public class FiberQueue {
    protected BlockingQueue<FiberRequest> queue;
    protected final Ruby runtime;

    public FiberQueue(Ruby runtime) {
        this.runtime = runtime;
        this.queue = new ArrayBlockingQueue<>(1, false);
    }

    private static final RubyThread.Task<FiberQueue, FiberRequest> TAKE_TASK = new RubyThread.Task<FiberQueue, FiberRequest>() {
        @Override
        public FiberRequest run(ThreadContext context, FiberQueue queue) throws InterruptedException {
            return queue.getQueueSafe().take();
        }

        @Override
        public void wakeup(RubyThread thread, FiberQueue data) {
            thread.getNativeThread().interrupt();
        }
    };

    public IRubyObject shutdown(ThreadContext context) {
        queue = null;
        return context.nil;
    }
    
    public synchronized void shutdown() {
        queue = null;
    }

    public boolean isShutdown() {
        return queue == null;
    }

    public BlockingQueue<FiberRequest> getQueueSafe() {
        BlockingQueue<FiberRequest> queue = this.queue;
        checkShutdown();
        return queue;
    }

    public synchronized void checkShutdown() {
        if (queue == null) {
            throw RaiseException.from(runtime, runtime.getThreadError(), "queue shut down");
        }
    }

    // MRI's fiber_switch never waits or checks interrupts there; the fiber running now takes them, and the one
    // getting control checks once it has it. A finalized fiber is shut down and takes the kill sent to it.
    @CRuby(value = "fiber_switch", file = "cont.c")
    public FiberRequest pop(ThreadContext context) {
        while (true) {
            try {
                return context.getThread().executeTaskUnchecked(context, this, TAKE_TASK);
            } catch (InterruptedException ie) {
                if (isShutdown()) {
                    context.pollThreadEvents();
                    checkShutdown();
                }
            }
        }
    }

    // A fiber switch is not interruptible. A Thread#raise deferred by Thread.handle_interrupt can still leave
    // the Java interrupt flag set after we stopped waiting, so keep putting and restore the flag for the next poll.
    public void push(ThreadContext context, final FiberRequest arg) {
        checkShutdown();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    queue.put(arg);
                    return;
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

}
