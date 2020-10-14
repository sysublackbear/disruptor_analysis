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

import com.lmax.disruptor.util.Util;

// 单生产者的缓存行填充 避免与无关对象产生伪共享
abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected long p1, p2, p3, p4, p5, p6, p7;
    // 调用父类方法
    SingleProducerSequencerPad(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     * 已预分配的缓存，因为是单线程的生产者，不存在竞争，因此采用普通的long变量
     */
    // 维护事件发布者发布的序列和事件处理者处理到的最小序列
    long nextValue = Sequence.INITIAL_VALUE;
    // 网关序列的最小序号缓存。
    /**
     * Q: 该缓存值的作用？
     * A: 除了直观上的减少对{@link #gatingSequences}的遍历产生的volatile读以外，还可以提高缓存命中率。
     * <p>
     * 由于消费者的{@link Sequence}变更较为频繁，因此消费者的{@link Sequence}的缓存极易失效。
     * 如果生产者频繁读取消费者的{@link Sequence}，极易遇见缓存失效问题（伪共享），从而影响性能。
     * 通过缓存一个值（在必要的时候更新），可以极大的减少对消费者的{@link Sequence}的读操作，从而提高性能。
     * PS: 使用一个变化频率较低的值代替一个变化频率较高的值，提高读效率。
     *
     * 在每次查询消费者的进度后，就会对它进行缓存
     */
    long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.</p>
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.</p>
 * 单线程的发布者
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected long p1, p2, p3, p4, p5, p6, p7;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * requiredCapacity: 需要的容量
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(int requiredCapacity, boolean doStore)
    {
        // 已分配序号缓存
        long nextValue = this.nextValue;

        // 可能构成环路的点：环形缓冲区可能追尾的点 = 等于本次申请的序号-环形缓冲区大小
        // 如果该序号大于最慢消费者的进度，那么表示追尾了，需要等待
        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        // 消费者的最慢进度
        long cachedGatingSequence = this.cachedValue;

        // 注意: nextValue和cachedGatingSequence都不会取模环形队列的长度

        // wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路(追尾)，还需要更多的空间，上次看见的序号缓存无效，
        // cachedGatingSequence > nextValue 表示消费者的进度大于生产者进度，正常情况下不可能，
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // 因为publish使用的是set()/putOrderedLong，并不保证消费者能及时看见发布的数据，
            // 当我再次申请更多的空间时，必须保证消费者能消费发布的数据（那么就需要进度对消费者立即可见，使用volatile写即可）
            // 插入StoreLoad内存屏障/栅栏，确保立即的可见性。
            if (doStore)
            {
                // 插入内存屏障(单线程没必要)
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }
            // 重新找队尾
            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            // 根据最新的消费者进度，仍然形成环路(产生追尾)，则表示空间不足
            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {
        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    // 发布者申请序列
    public long next(int n)
    {
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }
        // 获取事件发布者发布到的序列值
        long nextValue = this.nextValue;

        long nextSequence = nextValue + n;

        // wrap代表申请的序列绕一圈以后的位置
        long wrapPoint = nextSequence - bufferSize;
        // 获取事件处理器处理到的序列值
        long cachedGatingSequence = this.cachedValue;

        // wrapPoint > cachedGatingSequence: 代表绕一圈并且位置大于事件处理者处理到的序列，证明消息处理不过来
        // cachedGatingSequence > nextValue: 说明事件发布者的位置位于事件处理者的屁股后面
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            // 更新到cursor
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            // 如果末端的消费者们仍然没让出该插槽则等待，直到消费者们让出该插槽
            // 注意：这是导致死锁的重要原因！
            // 死锁分析：如果消费者挂掉了，而它的sequence没有从gatingSequences中删除的话，则生产者会死锁，它永远等不到消费者更新。

            // 会引发死锁
            // gatingSequences：消费者消费到的序号
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?  // 自旋1ns
            }

            // 这里只写了缓存，并未写volatile变量，因为只是预分配了空间但是并未被发布数据，不需要让其他消费者感知到。
            // 消费者只会感知到真正被发布的序号
            this.cachedValue = minSequence;   // 更新事件处理到的序列
        }

        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        // this.nextValue += n 更新已分配空间序号缓存
        // 这段空间已申请下来，但是还未发布(未填充数据)
        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        // 单生产者模式下，预分配空间是操作的 nextValue,因此修改nextValue即可
        // 这里可能导致 nextValue < cachedValue
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     * 事件发布调用的方法.里面会唤醒阻塞的消费者
     */
    @Override
    public void publish(long sequence)
    {
        // 更新发布进度，使用的是set（putOrderedLong），并没有保证对其他线程立即可见(最终会看见)
        // 在下一次申请更多的空间时，如果发现需要消费者加快消费，则必须保证数据对消费者可见
        cursor.set(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        publish(hi);
    }

    /**
     * 指定序号的数据是否准备好了
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        return sequence <= cursor.get();
    }

    /**
     * 因为是单生产者，那么区间段内的数据都是发布的
     * 注意: 多生产者模式下，已发布的数据可能是不连续的，因此不能直接该序号进行消费。必须顺序的消费，不能跳跃
     * @param lowerBound
     * @param availableSequence The sequence to scan to.
     * @return
     */
    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        return availableSequence;
    }
}
