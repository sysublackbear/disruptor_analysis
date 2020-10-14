package com.lmax.disruptor;

import java.util.concurrent.TimeUnit;

import static com.lmax.disruptor.util.Util.awaitNanos;

/**
 * 如果生产者生产速率不够，则阻塞式等待生产者一段时间
 * 如果是等待依赖的其它消费者，则轮询式等待
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * However it will periodically wake up if it has been idle for specified period by throwing a
 * {@link TimeoutException}.  To make use of this, the event handler class should implement the {@link TimeoutHandler},
 * which the {@link BatchEventProcessor} will call if the timeout occurs.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public class TimeoutBlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();
    private final long timeoutInNanos;

    public TimeoutBlockingWaitStrategy(final long timeout, final TimeUnit units)
    {
        timeoutInNanos = units.toNanos(timeout);
    }

    @Override
    public long waitFor(
        final long sequence,
        final Sequence cursorSequence,
        final Sequence dependentSequence,
        final SequenceBarrier barrier)
        throws AlertException, InterruptedException, TimeoutException
    {
        long timeoutNanos = timeoutInNanos;

        long availableSequence;
        // 阻塞式等待生产者
        if (cursorSequence.get() < sequence)
        {
            synchronized (mutex)
            {
                while (cursorSequence.get() < sequence)
                {
                    barrier.checkAlert();
                    timeoutNanos = awaitNanos(mutex, timeoutNanos);
                    if (timeoutNanos <= 0)
                    {
                        throw TimeoutException.INSTANCE;
                    }
                }
            }
        }
        // 轮询式等待其它依赖的消费者消费完该事件
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        synchronized (mutex)
        {
            mutex.notifyAll();
        }
    }

    @Override
    public String toString()
    {
        return "TimeoutBlockingWaitStrategy{" +
            "mutex=" + mutex +
            ", timeoutInNanos=" + timeoutInNanos +
            '}';
    }
}
