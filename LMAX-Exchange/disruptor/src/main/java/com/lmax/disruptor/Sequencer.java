/*
 * Copyright 2012 LMAX Ltd.
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
 * Coordinates claiming sequences for access to a data structure while tracking dependent {@link Sequence}s
 * Sequencer中的方法大多是给事件发布者使用。newBarrier()给事件处理者使用
 */
public interface Sequencer extends Cursored, Sequenced
{
    /**
     * Set to -1 as sequence starting point
     * 游标初始值
     */
    long INITIAL_CURSOR_VALUE = -1L;

    /**
     * Claim a specific sequence.  Only used if initialising the ring buffer to
     * a specific value.
     * 初始化RingBuffer为指定的sequence
     *
     * @param sequence The sequence to initialise too.
     */
    void claim(long sequence);

    /**
     * Confirms if a sequence is published and the event is available for use; non-blocking.
     *
     * @param sequence of the buffer to check
     * @return true if the sequence is available for use, false if not
     * 消费者调用，判断sequence是否可以消费
     */
    boolean isAvailable(long sequence);

    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     * 将sequence添加到gatingSequences中
     *
     * @param gatingSequences The sequences to add.
     */
    void addGatingSequences(Sequence... gatingSequences);

    /**
     * Remove the specified sequence from this sequencer.
     * 从gating sequences中移除指定的sequence
     *
     * @param sequence to be removed.
     * @return <code>true</code> if this sequence was found, <code>false</code> otherwise.
     */
    boolean removeGatingSequence(Sequence sequence);

    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     * 事件处理者用来追踪ringBuffer中可以用的sequence
     *
     * @param sequencesToTrack All of the sequences that the newly constructed barrier will wait on.
     * @return A sequence barrier that will track the specified sequences.
     * @see SequenceBarrier
     */
    SequenceBarrier newBarrier(Sequence... sequencesToTrack);

    /**
     * Get the minimum sequence value from all of the gating sequences
     * added to this ringBuffer.
     * 事件发布者获取gating sequence中最小的sequence
     *
     * @return The minimum gating sequence or the cursor sequence if
     * no sequences have been added.
     */
    long getMinimumSequence();

    /**
     * Get the highest sequence number that can be safely read from the ring buffer.  Depending
     * on the implementation of the Sequencer this call may need to scan a number of values
     * in the Sequencer.  The scan will range from nextSequence to availableSequence.  If
     * there are no available values <code>&gt;= nextSequence</code> the return value will be
     * <code>nextSequence - 1</code>.  To work correctly a consumer should pass a value that
     * is 1 higher than the last sequence that was successfully processed.
     * 消费者用来获取从nextSequence到avaliableSequence之间最大的sequence。
     * 如果是多线程生产者判断nextSequence是否可用，否则返回nextSequence-1。单线程直接返回availableSequence
     *
     * @param nextSequence      The sequence to start scanning from.
     * @param availableSequence The sequence to scan to.
     * @return The highest value that can be safely read, will be at least <code>nextSequence - 1</code>.
     */
    long getHighestPublishedSequence(long nextSequence, long availableSequence);

    <T> EventPoller<T> newPoller(DataProvider<T> provider, Sequence... gatingSequences);
}