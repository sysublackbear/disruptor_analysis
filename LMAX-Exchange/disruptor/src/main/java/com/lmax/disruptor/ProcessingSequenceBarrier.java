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


/**
 * 消费者使用的事件处理器序号屏障
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier
{
    private final WaitStrategy waitStrategy;
    // 依赖的Sequence
    // EventProcessor(事件处理器)的Sequence必须小于或等于依赖的Sequence
    private final Sequence dependentSequence;
    private volatile boolean alerted = false;
    private final Sequence cursorSequence;
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
        final Sequencer sequencer,
        final WaitStrategy waitStrategy,
        final Sequence cursorSequence,
        final Sequence[] dependentSequences)
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;

        // 如果消费者不依赖于其他的消费者,那么只需要与生产者的进度进行协调
        if (0 == dependentSequences.length)
        {
            dependentSequence = cursorSequence;
        }
        else
        {
            // 固定的序列group
            // 如果依赖于其他消费者，那么追踪其他消费者的进度
            // FixedSequenceGroup直接实现了Sequence接口
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence)
        throws AlertException, InterruptedException, TimeoutException
    {
        checkAlert();

        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        // 目标sequence还未发布，超时了(TimeoutBlockingWaitStrategy)
        if (availableSequence < sequence)
        {
            return availableSequence;
        }
        // 目标sequence已经发布了，这里获取真正的最大序号(和生产者模型有关)
        // availableSequence >= sequence

        // 这里会有个缺陷,如果某个生产者异常退出了，这段空间便一直阻塞在这里。
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor()
    {
        // 这里实际返回的是依赖的生产者/消费者的进度 - 因为当依赖其它消费者时，查询生产者进度对于等待是没有意义的
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        // 标记为被中断，并唤醒正在等待的消费者
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}