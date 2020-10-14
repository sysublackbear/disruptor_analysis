/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

import java.util.concurrent.locks.LockSupport;

/**
 * 睡眠等待策略
 * 等待方式: 自旋 + yield + sleep
 * 表现: 延迟不均匀,吞吐量较低,但是cpu占有率也较低
 * 算是CPU与性能之间的一个折中,当CPU资源紧张时可以考虑使用该策略。
 *
 * Sleeping strategy that initially spins, then uses a Thread.yield(), and
 * eventually sleep (<code>LockSupport.parkNanos(n)</code>) for the minimum
 * number of nanos the OS and JVM will allow while the
 * {@link com.lmax.disruptor.EventProcessor}s are waiting on a barrier.
 * <p>
 * This strategy is a good compromise between performance and CPU resource.
 * Latency spikes can occur after quiet periods.  It will also reduce the impact
 * on the producing thread as it will not need signal any conditional variables
 * to wake up the event handling thread.
 */
public final class SleepingWaitStrategy implements WaitStrategy
{
    // 默认空循环次数200次
    private static final int DEFAULT_RETRIES = 200;
    // 默认每次睡眠时间(睡太久影响响应性,睡太短占用CPU资源)
    private static final long DEFAULT_SLEEP = 100;

    private final int retries;
    private final long sleepTimeNs;

    public SleepingWaitStrategy()
    {
        this(DEFAULT_RETRIES, DEFAULT_SLEEP);
    }

    public SleepingWaitStrategy(int retries)
    {
        this(retries, DEFAULT_SLEEP);
    }

    public SleepingWaitStrategy(int retries, long sleepTimeNs)
    {
        this.retries = retries;
        this.sleepTimeNs = sleepTimeNs;
    }

    @Override
    public long waitFor(
        final long sequence, Sequence cursor, final Sequence dependentSequence, final SequenceBarrier barrier)
        throws AlertException
    {
        long availableSequence;
        int counter = retries;

        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            // 当依赖的消费者还没消费完该序号的事件时执行等待方法
            counter = applyWaitMethod(barrier, counter);
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
    }

    private int applyWaitMethod(final SequenceBarrier barrier, int counter)
        throws AlertException
    {
        barrier.checkAlert();

        if (counter > 100)
        {
            --counter;  // 前100次自旋
        }
        else if (counter > 0)
        {
            --counter;
            // 后100次执行yield操作
            // 使当前线程从执行状态(运行状态)变为可执行态(就绪状态).cpu会从众多的可执行态里选择，也就是说,当前也就是刚刚的那个线程还是有可能会被
            // 再次执行到的,并不是说一定会执行其他线程而该线程在下一次中不会执行到了。
            Thread.yield();
        }
        else
        {
            // 线程睡眠,线程不断在睡眠态和就绪态之间来回切换
            // 不断在 运行、睡眠、等待调度 之间切换，线程上下文切换非常频繁。
            LockSupport.parkNanos(sleepTimeNs);
        }

        return counter;
    }
}
