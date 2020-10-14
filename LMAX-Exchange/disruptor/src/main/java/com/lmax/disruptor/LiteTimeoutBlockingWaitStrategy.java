package com.lmax.disruptor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.lmax.disruptor.util.Util.awaitNanos;

/**
 * 轻量级的带超时的阻塞等待策略
 * 如果生产者生产速率不够，则阻塞式等待生产者一段时间。
 * 如果是等待依赖的其它消费者，则轮询式等待。
 *
 * 警告: 与TimeoutBlockingWaitStrategy不同,消费者可能在有可消费者的事件时仍然处理阻塞状态，因此使用该策略时超时时间不可以太长。
 * 如果不需要精确的等待通知策略，该该策略可能在多数情况下都优于TimeoutBlockingWaitStrategy
 * Variation of the {@link TimeoutBlockingWaitStrategy} that attempts to elide conditional wake-ups
 * when the lock is uncontended.
 */
public class LiteTimeoutBlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();
    // 加入信号，减少不必要的锁申请
    private final AtomicBoolean signalNeeded = new AtomicBoolean(false);
    private final long timeoutInNanos;

    public LiteTimeoutBlockingWaitStrategy(final long timeout, final TimeUnit units)
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
        long nanos = timeoutInNanos;

        long availableSequence;
        if (cursorSequence.get() < sequence)
        {
            synchronized (mutex)
            {
                while (cursorSequence.get() < sequence)
                {
                    // 如果生产者生成速率不足,则准备等待
                    // 在等待前标记为需要被通知
                    signalNeeded.getAndSet(true);

                    barrier.checkAlert();
                    nanos = awaitNanos(mutex, nanos);
                    if (nanos <= 0)
                    {
                        throw TimeoutException.INSTANCE;
                    }
                }
            }
        }

        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        // 注意：由于这里并未先获取锁，因此即使这里getAndSet认为不需要通知，消费者可能立即又赋值为true
        // 从而导致这里并未进行通知，而消费者将会阻塞，直到超时或下一个事件到达
        // 这里加入CAS的必要性: 如果本身是false,就没必要重复notifyAll了
        // 去掉这个，就会全部人都去抢锁
        if (signalNeeded.getAndSet(false))
        {
            // 同一时间只有一个线程能进入临界区,一个线程执行完才轮到下一个线程
            synchronized (mutex)
            {
                mutex.notifyAll();
            }
        }
        // 假设刚执行到这里认为没有去更新了(因为为false),但这个时候如果消费者在等待,实际需要更新
        // 但这个时候看起来就像是"通知"丢了
    }

    @Override
    public String toString()
    {
        return "LiteTimeoutBlockingWaitStrategy{" +
            "mutex=" + mutex +
            ", signalNeeded=" + signalNeeded +
            ", timeoutInNanos=" + timeoutInNanos +
            '}';
    }
}
