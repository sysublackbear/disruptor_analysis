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

import com.lmax.disruptor.util.ThreadHints;

/**
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.
 * <p>
 * This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();

    @Override
    public long waitFor(long sequence, Sequence cursorSequence, Sequence dependentSequence, SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        // 确保生产者已生产该数据了,这期间可能阻塞
        long availableSequence;
        if (cursorSequence.get() < sequence)
        {
            // 加锁
            synchronized (mutex)
            {
                while (cursorSequence.get() < sequence)
                {
                    // barrier.checkAlert()如果为true,主要是用于告诉等待策略,消费者已经被请求关闭,需要从等待中退出。
                    barrier.checkAlert();  // 一个alert告警
                    // 调用该方法的线程进入WAITING状态，只有等待另外线程的通知或被中断才会返回，需要注意，
                    // 调用wait()方法后，会释放对象的锁。
                    mutex.wait();
                }
            }
        }
        // 等待前驱消费者消费完对应的事件，这是实现消费者之间的happens-before的关键
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            ThreadHints.onSpinWait();   // 自旋锁
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
        return "BlockingWaitStrategy{" +
            "mutex=" + mutex +
            '}';
    }
}
