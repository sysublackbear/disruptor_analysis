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

import sun.misc.Unsafe;

import com.lmax.disruptor.util.Util;


/**
 * 多生产者模型下的序号生成器
 * 注意:
 * 在使用该序号生成器时，调用{@link Sequencer#getCursor()}后必须 调用{@link Sequencer#getHighestPublishedSequence(long, long)}
 * 确定真正可用的序号。（因为多生产者模型下，生产者之间是无锁的，预分配空间，那么真正填充的数据可能是非连续的），因此需要确认。
 * <p>Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Suitable for use for sequencing across multiple publisher threads.</p>
 *
 * <p> * Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#next()}, to determine the highest available sequence that can be read, then
 * {@link Sequencer#getHighestPublishedSequence(long, long)} should be used.</p>
 */
public final class MultiProducerSequencer extends AbstractSequencer
{
    private static final Unsafe UNSAFE = Util.getUnsafe();
    // 获取{@link #availableBuffer}数组对象头元素偏移量
    private static final long BASE = UNSAFE.arrayBaseOffset(int[].class);
    // 获取{@link #availableBuffer}数组一个元素的地址偏移量(用于计算指定下标的元素的内存地址)
    private static final long SCALE = UNSAFE.arrayIndexScale(int[].class);

    /**
     * 上次获取到的最小序号缓存，会被并发的访问，因此用Sequence，而单线程的Sequencer中则使用了一个普通long变量。
     * 在任何时候查询了消费者进度信息时都需要更新它。
     * 某些时候可以减少{@link #gatingSequences}的遍历(减少volatile读操作)。
     *
     *
     * Util.getMinimumSequence(gatingSequences, current)的查询结果是递增的，但是缓存结果不一定是递增的，变量的更新存在竞争，
     * 它可能会被设置为一个更小的值。
     *
     * <p>
     * Q: 为什么在该字段上的竞争的良性的？
     * A: 生产者只需要确保不会覆盖未消费的槽位，因此看见消费者进度落后是允许的，即看见一个比自己上次看见的更旧的值是合法的。
     *
     * <p>
     * Q: 为什么使用{@link Sequence}类，而不是普通的 volatile long？
     * A: 为了避免与其它数据产生伪共享，提高读效率。
     * <p>
     * 该缓存值，除了直观上的减少对{@link #gatingSequences}的遍历产生的volatile读以外，还可以提高缓存命中率。
     * 由于消费者的{@link Sequence}变更较为频繁，因此消费者的{@link Sequence}的缓存极易失效。
     * 如果生产者频繁读取消费者的{@link Sequence}，极易遇见缓存失效问题（伪共享），从而影响性能。
     * 通过缓存一个值（在必要的时候更新），可以极大的减少对消费者的{@link Sequence}的读操作，从而提高性能。
     * PS: 使用一个变化频率较低的值代替一个变化频率较高的值，提高读效率。
     *
     * {@link SingleProducerSequencerFields#cachedValue}
     */
    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    // 多生产者模式下，标记哪些序号是真正被填充了数据的。(用于获取连续的可用空间)
    // 其实就是表明数据是属于第几环
    private final int[] availableBuffer;
    // 用于快速的计算序号对应的下标，与计算就可以，本质上和RingBuffer中计算插槽位置一样
    private final int indexMask;
    // 用来定位某个sequence到底转了多少圈，用来标识已被发布的sequence
    private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
        availableBuffer = new int[bufferSize];
        indexMask = bufferSize - 1;
        // 计算需要2的几次幂
        indexShift = Util.log2(bufferSize);
        // 初始化时,设置插槽上的所有标记为不可用
        initialiseAvailableBuffer();
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

    // 是否有足够的空间，多线程下返回的总是一个‘旧’值，不一定具有价值
    // 总体思路:
    // 可能构成环路的点 如果 大于消费者的消费进度，则表示会发生追尾，空间不足
    private boolean hasAvailableCapacity(Sequence[] gatingSequences, final int requiredCapacity, long cursorValue)
    {
        // 需要预分配这一段空间 cursorValue+1 ~ cursorValue+requiredCapacity这一段
        // 可能构成环路的点/环形缓冲区可能追尾的点 = 请求的序号 - 环形缓冲区大小
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        // 缓存的消费者们的最慢进度值，小于等于真实进度
        // (对单个线程来说可能看见一个比该线程上次看见的更小的值/对另一个线程来说就可能看见一个比生产进度更大的值)
        long cachedGatingSequence = gatingSequenceCache.get();

        // 1.wrapPoint > cachedGatingSequence 表示生产者追上消费者产生环路，上次看见的序号缓存无效，即缓冲区已满，此时需要获取消费者们最新的进度，以确定是否队列满。
        // 2.cachedGatingSequence > cursorValue   表示消费者的进度大于当前生产者进度，表示cursorValue无效，有以下可能：
        // 2.1 其它生产者发布了数据，并更新了gatingSequenceCache，并已被消费（当前线程进入该方法时可能被挂起，重新恢复调度时看见一个更大值）。
        // 2.2 claim的调用（建议忽略）
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue)
        {
            // 走进这里表示cachedGatingSequence过期或cursorValue过期，此时都需要获取最新的gatingSequence
            long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);

            // 这里存在竞态条件，多线程模式下，可能会被设置为多个线程看见的结果中的任意一个。
            // 可能比cachedGatingSequence更小，可能比cursorValue更大。
            // 但该竞争是良性的，产生的结果是可控的，不会导致错误（不会导致生产者覆盖未消费的数据）。
            gatingSequenceCache.set(minSequence);

            // 根据最新的消费者进度，仍然形成环路(产生追尾)，则表示空间不足
            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(long sequence)
    {
        // 因为多生产者模式下，预分配空间是直接操作的cursor，因此直接设置cursor
        cursor.set(sequence);
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
    public long next(int n)
    {
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }

        long current;
        long next;

        do
        {
            current = cursor.get();  // cursor是Sequencer的生产序列器,也是一个Sequence类
            next = current + n;

            long wrapPoint = next - bufferSize;  // 用来判断next是否超出buff的长度

            // 当前处理队列尾，在多消费者模式下，消费者会预分配处理位置，所以gatingSequenceCache可能会超过写入的位置
            long cachedGatingSequence = gatingSequenceCache.get();

            if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current)
            {
                // 1. wrapPoint > cachedGatingSequence: 如果合法序列已经在处理队尾的前面了,说明处理不过来，需要等等
                // (所以需要注意,业务handler里面的阻塞时间不能超过执行整个队列事件的时间,如果这个时候还有handler阻塞,disruptor就不能再写入数据了,所有生产者线程都会阻塞,而且因为大量线程LockSupport.parkNanos(1),还会消耗大量的性能)
                // 2. cachedGatingSequence > current: 如果队列尾在当前写入位置的前面，说明写入位置已经过期，也等等

                // gatingSequences就是所有消费者的序列,这个方法会gatingSequence设置为所有消费者执行的最小序列号和当前写入的最小值
                // 实际上就是获得处理的队尾,如果队尾是current的话,说明所有的消费者都执行完成任务在等待新的事件了
                long gatingSequence = Util.getMinimumSequence(gatingSequences, current);

                if (wrapPoint > gatingSequence)
                {
                    LockSupport.parkNanos(1); // TODO, should we spin based on the wait strategy?
                    continue;
                }

                gatingSequenceCache.set(gatingSequence);
            }
            else if (cursor.compareAndSet(current, next))  // 每个线程获取不同的一段数组空间进行操作。这个通过CAS很容易达到。只需要在分配元素的时候，通过CAS判断一下这段空间是否已经分配出去即可。
            {
                break;
            }
        }
        while (true);

        return next;
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

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
        }
        while (!cursor.compareAndSet(current, next));  // 这里和SingleProducerSequencer区别在于它会提早做CAS commit操作

        return next;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

    private void initialiseAvailableBuffer()
    {
        for (int i = availableBuffer.length - 1; i != 0; i--)
        {
            setAvailableBufferValue(i, -1);
        }

        setAvailableBufferValue(0, -1);
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        setAvailable(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(long lo, long hi)
    {
        for (long l = lo; l <= hi; l++)
        {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * 设置目标插槽上的数据可用，将对应插槽上的标记置为可用标记（第几环）。
     * The below methods work on the availableBuffer flag.
     * <p>
     * The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     * <p>
     * --  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     */
    private void setAvailable(final long sequence)
    {
        // calculateIndex(sequence): 取模
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

    private void setAvailableBufferValue(int index, int flag)
    {
        long bufferAddress = (index * SCALE) + BASE;
        UNSAFE.putOrderedInt(availableBuffer, bufferAddress, flag);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(long sequence)
    {
        int index = calculateIndex(sequence);
        int flag = calculateAvailabilityFlag(sequence);
        long bufferAddress = (index * SCALE) + BASE;
        return UNSAFE.getIntVolatile(availableBuffer, bufferAddress) == flag;
    }

    @Override
    public long getHighestPublishedSequence(long lowerBound, long availableSequence)
    {
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++)
        {
            // 看availableBuffer数组上的值是否绕到该圈了
            if (!isAvailable(sequence))
            {
                return sequence - 1;
            }
        }

        return availableSequence;
    }

    // 计算sequence对应可用标记
    private int calculateAvailabilityFlag(final long sequence)
    {
        // 无符号右移
        return (int) (sequence >>> indexShift);
    }

    private int calculateIndex(final long sequence)
    {
        return ((int) sequence) & indexMask;
    }
}
