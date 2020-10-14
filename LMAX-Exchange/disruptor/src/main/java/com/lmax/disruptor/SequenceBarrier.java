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
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 * 序号屏障（协调生产者和消费者）：
 * 通过跟踪生产者的cursor和当前时间处理器依赖的sequence（dependentSequence/traceSequences），来协调对共享数据结构的访问
 * 
 * 主要目的：
 * 1.协调消费者与生产者和消费者与消费者之间的速度（进度控制）
 * 2.保证消费者与生产者和消费者与消费者之间的可见性（读写volatile变量实现）
 */
public interface SequenceBarrier
{
    /**
     * 在该屏障上等待，直到该序号的数据可以被消费。
     * 是否可消费取决于生产者的cursor和当前事件处理器依赖的Sequence
     * Wait for the given sequence to be available for consumption.
     * 等待给定的序列变为可用，然后消费这个序列。消费线程中使用
     *
     * @param sequence to wait for 事件处理器期望消费的下一个序号
     * @return the sequence up to which is available 看见的最大进度（不一定可消费）
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException     if a timeout occurs while waiting for the supplied sequence.
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * Get the current cursor value that can be read.
     * 获取生产者的光标（当前发布进度/序号）
     *
     * @return value of the cursor for entries that have been published.
     */
    long getCursor();
    
    // 下面几个方法可类比为线程的中断状态1操作

    /**
     * The current alert status for the barrier.
     * 查询状态标记是否被设置
     *
     * @return true if in alert otherwise false.
     */
    boolean isAlerted();

    /**
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     * 通知事件处理器有状态发生了变化（有点像中断）
     */
    void alert();

    /**
     * Clear the current alert status.
     * 清除上一个状态标记
     */
    void clearAlert();

    /**
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     *  检查标记，如果为true则抛出异常。
     *  主要是用于告诉等待策略，消费者已经被请求关闭，需要从等待中退出。
     *
     * @throws AlertException if alert has been raised.
     */
    void checkAlert() throws AlertException;
}
