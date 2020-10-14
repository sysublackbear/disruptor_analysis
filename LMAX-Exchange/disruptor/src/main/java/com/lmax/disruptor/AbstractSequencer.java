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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.lmax.disruptor.util.Util;

/**
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 * 管理事件处理者序列和事件发布者发布序列
 * 抽象序号生成器，作为单生产者和多生产者序列号生成器的超类，实现一些公共的功能（添加/删除gatingSequence）
 */
public abstract class AbstractSequencer implements Sequencer
{
    // final表明这个类永远不能被继承
    // AtomicReferenceFieldUpdater是基于反射的工具类，下面代表对AbstractSequencer中的gatingSequences成员进行原子更新
    private static final AtomicReferenceFieldUpdater<AbstractSequencer, Sequence[]> SEQUENCE_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(AbstractSequencer.class, Sequence[].class, "gatingSequences");
    // 队列大小
    protected final int bufferSize;
    // 等待策略
    protected final WaitStrategy waitStrategy;
    // 事件发布者已经发布到的sequence
    // 生产者的序列，表示生产者的进度
    /* 生产者的序列，表示生产者的进度。
        * PS: 代码里面的带cursor的都表示生产者们的Sequence。
        * <p>
        * 消费者与生产者之间的交互（可见性保证）是通过volatile变量的读写来保证的。(volatile代表一个线程做出了修改，另一个线程能立马看到新值)
        * 消费者们观察生产者的进度，当看见生产者进度增大时，生产者这期间的操作对消费者来说都是可见的。
        * volatile的happens-before原则-----生产者的进度变大(写volatile)先于消费者看见它变大(读volatile)。
        * 在多生产者情况下，只能看见空间分配操作，要确定哪些数据发布还需要额外保证.
     */
    // 线程安全对象
    protected final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    // 事件处理者处理到的序列对象
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Create with the specified buffer size and wait strategy.
     *
     * @param bufferSize   The total number of entries, must be a positive power of 2.
     * @param waitStrategy The wait strategy used by this sequencer
     */
    public AbstractSequencer(int bufferSize, WaitStrategy waitStrategy)
    {
        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1)  // 二进制中bit为1的位数不止一位
        {
            // bufferSize必须为2的次方幂
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /**
     * 获取生产者的生产进度(已发布的最大序号)
     * @see Sequencer#getCursor()
     */
    @Override
    public final long getCursor()
    {
        return cursor.get();
    }

    /**
     * 获取缓冲区的大小
     * @see Sequencer#getBufferSize()
     */
    @Override
    public final int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * 添加末端消费者的消费进度，这些消费者的进度需要被关心
     * @see Sequencer#addGatingSequences(Sequence...)
     */
    @Override
    public final void addGatingSequences(Sequence... gatingSequences)
    {
        // SequenceGroups工具类?
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /**
     * 移除消费者的进度信息，这些消费者的进度无需再被关心
     * @see Sequencer#removeGatingSequence(Sequence)
     */
    @Override
    public boolean removeGatingSequence(Sequence sequence)
    {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
     * @see Sequencer#getMinimumSequence()
     * 获取最小的序列号
     */
    @Override
    public long getMinimumSequence()
    {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }

    /**
     * 为这些消费者创建一个屏障,用于协调它们和生产者之间的进度
     * @see Sequencer#newBarrier(Sequence...)
     */
    @Override
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack)
    {
        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, sequencesToTrack);
    }

    /**
     * 创建一个数据轮询器
     * Creates an event poller for this sequence that will use the supplied data provider and
     * gating sequences.
     *
     * @param dataProvider    The data source for users of this event poller
     * @param gatingSequences Sequence to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    @Override
    public <T> EventPoller<T> newPoller(DataProvider<T> dataProvider, Sequence... gatingSequences)
    {
        return EventPoller.newInstance(dataProvider, this, new Sequence(), cursor, gatingSequences);
    }

    @Override
    public String toString()
    {
        return "AbstractSequencer{" +
            "waitStrategy=" + waitStrategy +
            ", cursor=" + cursor +
            ", gatingSequences=" + Arrays.toString(gatingSequences) +
            '}';
    }
}