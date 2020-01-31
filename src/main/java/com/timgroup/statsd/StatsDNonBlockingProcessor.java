package com.timgroup.statsd;

import java.nio.ByteBuffer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class StatsDNonBlockingProcessor extends StatsDProcessor {

    private final Queue<String>[] messages;
    private final Queue<Integer>[] processorWorkQueue;

    private final int qcapacity;
    private final AtomicInteger[] qsize;  // qSize will not reflect actual size, but a close estimate.

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

            while (!((empty = processorWorkQueue[this.processorQueueId].isEmpty()) && shutdown)) {

                try {
                    if (empty) {
                        Thread.sleep(WAIT_SLEEP_MS);
                        continue;
                    }

                    if (Thread.interrupted()) {
                        return;
                    }

                    final int messageQueueIdx = processorWorkQueue[this.processorQueueId].poll();
                    final String message = messages[messageQueueIdx].poll();
                    if (message != null) {
                        qsize[messageQueueIdx].decrementAndGet();
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

    StatsDNonBlockingProcessor(final int queueSize, final StatsDClientErrorHandler handler,
            final int maxPacketSizeBytes, final int poolSize, final int workers,
            final int lockShardGrain) throws Exception {

        super(queueSize, handler, maxPacketSizeBytes, poolSize, workers, lockShardGrain);
        this.qsize = new AtomicInteger[lockShardGrain];
        this.qcapacity = queueSize;

        this.messages = new ConcurrentLinkedQueue[lockShardGrain];
        for (int i = 0 ; i < lockShardGrain ; i++) {
            this.qsize[i] = new AtomicInteger();
            this.messages[i] = new ConcurrentLinkedQueue<String>();
            this.qsize[i].set(0);
        }

        this.processorWorkQueue = new ConcurrentLinkedQueue[workers];
        for (int i = 0 ; i < workers ; i++) {
            this.processorWorkQueue[i] = new ConcurrentLinkedQueue<Integer>();
        }
    }

    @Override
    boolean send(final String message) {
        if (!shutdown) {
            long threadId = Thread.currentThread().getId();
            // modulo reduction alternative to: long shard = threadID % [shard]this.lockShardGrain;
            // ref: https://lemire.me/blog/2016/06/27/a-fast-alternative-to-the-modulo-reduction/
            int shard = (int)((threadId * (long)this.lockShardGrain) >> 32);
            int processQueue = (int)((threadId * (long)this.workers) >> 32);

            if (qsize[shard].get() < qcapacity) {
                messages[shard].offer(message);
                qsize[shard].incrementAndGet();
                processorWorkQueue[processQueue].offer(shard);
                return true;
            }
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

    boolean isShutdown() {
        return shutdown;
    }

    void shutdown() {
        shutdown = true;
    }
}
