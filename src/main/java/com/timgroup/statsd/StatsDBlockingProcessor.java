package com.timgroup.statsd;

import java.lang.Thread;

import java.nio.ByteBuffer;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class StatsDBlockingProcessor extends StatsDProcessor {

    private final BlockingQueue<String>[] messages;
    private final BlockingQueue<Integer>[] processorWorkQueue;

    private class ProcessingTask implements Runnable {
        private final int processorQueueId;

        public ProcessingTask(int id) {
            this.processorQueueId = id;
        }

        public void run() {
            boolean empty;
            ByteBuffer sendBuffer;

            try {
                sendBuffer = bufferPool.borrow();
            } catch (final InterruptedException e) {
                handler.handle(e);
                return;
            }

            while (!(processorWorkQueue[this.processorQueueId].isEmpty() && shutdown)) {

                try {

                    if (Thread.interrupted()) {
                        return;
                    }

                    final int messageQueueIdx = processorWorkQueue[this.processorQueueId].poll();
                    final String message = messages[messageQueueIdx].poll(WAIT_SLEEP_MS, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        final byte[] data = message.getBytes(MESSAGE_CHARSET);
                        if (sendBuffer.capacity() < data.length) {
                            throw new InvalidMessageException(MESSAGE_TOO_LONG, message);
                        }
                        if (sendBuffer.remaining() < (data.length + 1)) {
                            outboundQueue.put(sendBuffer);
                            sendBuffer = bufferPool.borrow();
                        }
                        if (sendBuffer.position() > 0) {
                            sendBuffer.put((byte) '\n');
                        }
                        sendBuffer.put(data);
                        if (null == processorWorkQueue[this.processorQueueId].peek()) {
                            outboundQueue.put(sendBuffer);
                            sendBuffer = bufferPool.borrow();
                        }
                    }
                } catch (final InterruptedException e) {
                    if (shutdown) {
                        endSignal.countDown();
                        return;
                    }
                } catch (final Exception e) {
                    handler.handle(e);
                }
            }
            endSignal.countDown();
        }
    }

    StatsDBlockingProcessor(final int queueSize, final StatsDClientErrorHandler handler,
            final int maxPacketSizeBytes, final int poolSize, final int workers,
            final int lockShardGrain) throws Exception {

        super(queueSize, handler, maxPacketSizeBytes, poolSize, workers, lockShardGrain);

        this.messages = new ArrayBlockingQueue[lockShardGrain];
        for (int i = 0 ; i < lockShardGrain ; i++) {
            this.messages[i] = new ArrayBlockingQueue<String>(queueSize);
        }

        this.processorWorkQueue = new ArrayBlockingQueue[workers];
        for (int i = 0 ; i < workers ; i++) {
            this.processorWorkQueue[i] = new ArrayBlockingQueue<Integer>(queueSize);
        }
    }

    @Override
    boolean send(final String message) {
        try {
            long threadId = Thread.currentThread().getId();
            // modulo reduction alternative to: long shard = threadID % this.lockShardGrain;
            // ref: https://lemire.me/blog/2016/06/27/a-fast-alternative-to-the-modulo-reduction/
            int shard = (int)((threadId * (long)this.lockShardGrain) >> 32);
            int processQueue = (int)((threadId * (long)this.workers) >> 32);

            if (!shutdown) {
                messages[shard].put(message);
                processorWorkQueue[processQueue].put(shard);
                return true;
            }
        } catch (InterruptedException e) {
            // NOTHING
        }

        return false;
    }

    @Override
    public void run() {

        for (int i = 0 ; i < workers ; i++) {
            executor.submit(new ProcessingTask(i));
        }

        boolean done = false;
        while (!done) {
            try {
                endSignal.await();
                done = true;
            } catch (final InterruptedException e) {
                // NOTHING
            }
        }
    }
}
