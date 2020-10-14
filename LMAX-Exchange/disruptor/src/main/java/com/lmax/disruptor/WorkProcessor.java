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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkPool消费者里面的事件处理单元,不是消费者,只是一个消费者里面的一个工作单元,多个WorkProcessor协作构成WorkPool消费者
 * WorkProcessor负责跟踪和拉取事件,WorkHandler负责处理事件
 * <p>A {@link WorkProcessor} wraps a single {@link WorkHandler}, effectively consuming the sequence
 * and ensuring appropriate barriers.</p>
 *
 * <p>Generally, this will be used as part of a {@link WorkerPool}.</p>
 *
 * @param <T> event implementation storing the details for the work to processed.
 */
public final class WorkProcessor<T>
    implements EventProcessor
{
    // 运行状态
    private final AtomicBoolean running = new AtomicBoolean(false);
    // WorkProcessor的处理进度(上一次处理的序号)
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    // 生产者和消费者共享的结构,WorkProcessor之间先竞争Sequence,竞争成功的那个可以消费
    private final RingBuffer<T> ringBuffer;
    // WorkProcessor所属的消费者的屏障
    private final SequenceBarrier sequenceBarrier;
    // WorkProcessor绑定的事件处理器
    private final WorkHandler<? super T> workHandler;
    // WorkProcessor绑定的异常处理器
    private final ExceptionHandler<? super T> exceptionHandler;
    // WorkerPool中的WorkProcessor竞争通信的媒介
    // todo:啥意思
    private final Sequence workSequence;

    // 设置为MAX_VALUE可以使得当前线程停止消费，且不影响生产者和其他消费者
    private final EventReleaser eventReleaser = new EventReleaser()
    {
        @Override
        public void release()
        {
            sequence.set(Long.MAX_VALUE);
        }
    };

    private final TimeoutHandler timeoutHandler;

    /**
     * Construct a {@link WorkProcessor}.
     *
     * @param ringBuffer       to which events are published.
     * @param sequenceBarrier  on which it is waiting.
     * @param workHandler      is the delegate to which events are dispatched.
     * @param exceptionHandler to be called back when an error occurs
     * @param workSequence     from which to claim the next event to be worked on.  It should always be initialised
     *                         as {@link Sequencer#INITIAL_CURSOR_VALUE}
     */
    public WorkProcessor(
        final RingBuffer<T> ringBuffer,
        final SequenceBarrier sequenceBarrier,
        final WorkHandler<? super T> workHandler,
        final ExceptionHandler<? super T> exceptionHandler,
        final Sequence workSequence)
    {
        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.workHandler = workHandler;
        this.exceptionHandler = exceptionHandler;
        this.workSequence = workSequence;

        if (this.workHandler instanceof EventReleaseAware)
        {
            ((EventReleaseAware) this.workHandler).setEventReleaser(eventReleaser);
        }

        timeoutHandler = (workHandler instanceof TimeoutHandler) ? (TimeoutHandler) workHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(false);
        sequenceBarrier.alert();
    }

    /**
     * remove workProcessor dynamic without message lost
     */
    public void haltLater()
    {
        running.set(false);
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * It is ok to have another thread re-run this method after a halt().
     * 暂停之后交给下一个线程运行是线程安全的
     *
     * @throws IllegalStateException if this processor is already running
     */
    @Override
    public void run()
    {
        // CAS操作保证只有一个线程能运行，和保证可见性（看见状态为false后于前一个线程将其设置为false）
        if (!running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Thread is already running");
        }
        // 检查中断、停止请求
        // 相当于重置所有状态标记，方便下一次的重新运行
        sequenceBarrier.clearAlert();

        notifyStart();
        // 是否处理了一个事件。在处理完一个事件之后会再次竞争序号进行消费
        boolean processedSequence = true;
        // 看见的已发布序号的缓存，这里是局部变量，在该变量上无竞争
        long cachedAvailableSequence = Long.MIN_VALUE;
        // 下一个要消费的序号(要消费的事件编号)，注意起始为-1 ，注意与BatchEventProcessor的区别
        // BatchEventProcessor初始值为 sequence.get()+1
        // 存为local variable 还减少大量的volatile变量读，且保证本次操作过程中的一致性
        long nextSequence = sequence.get();
        T event = null;
        while (true)
        {
            try
            {
                // 如果前一个事情被成功处理了--拉取下一个序号，并将上一个序号标记为已成功处理。
                // 一般来说，这都是正确的。
                // 这可以防止当workHandler抛出异常时,Sequence跨度太大。
                // if previous sequence was processed - fetch the next sequence and set
                // that we have successfully processed the previous sequence
                // typically, this will be true
                // this prevents the sequence getting too far forward if an exception
                // is thrown from the WorkHandler

                if (processedSequence)
                {
                    if (!running.get())
                    {
                        // 临时终止
                        sequenceBarrier.alert();
                        sequenceBarrier.checkAlert();
                    }
                    processedSequence = false;
                    // 多个workProcessor竞争workSequence
                    do
                    {
                        // 获取workProcessor所属的消费者的进度，与workSequence同步(感知其他消费者的进度)
                        nextSequence = workSequence.get() + 1L;
                        sequence.set(nextSequence - 1L);  // 将上一个序号标记为已成功处理
                    }
                    while (!workSequence.compareAndSet(nextSequence - 1L, nextSequence));
                }
                // 它只能保证竞争到的序号是可用的，因此只能只消费一个。
                // 而BatchEventProcessor看见的所有序号都是可用的(单线程)
                if (cachedAvailableSequence >= nextSequence)
                {
                    // 取出事件
                    event = ringBuffer.get(nextSequence);
                    workHandler.onEvent(event);
                    processedSequence = true;
                }
                else
                {
                    // 等待生产者进行生产，这里和BatchEventProcessor不同，
                    // 如果waitFor抛出TimeoutException、Throwable以外的异常，那么cachedAvailableSequence不会被更新，
                    // 也就不会导致nextSequence被标记为已消费！
                    cachedAvailableSequence = sequenceBarrier.waitFor(nextSequence);
                }
            }
            catch (final TimeoutException e)
            {
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                if (!running.get())
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // handle, mark as processed, unless the exception handler threw an exception
                exceptionHandler.handleEventException(ex, nextSequence, event);
                // 事件本身处理异常
                // 成功处理异常后标记当前事件已被消费
                processedSequence = true;
            }
        }

        notifyShutdown();
        // 写volatile，插入StoreLoad屏障，保证其他线程能看见我退出前的所有操作
        running.set(false);
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    private void notifyStart()
    {
        if (workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) workHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    private void notifyShutdown()
    {
        if (workHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) workHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}
