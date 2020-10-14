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

import java.util.concurrent.atomic.AtomicInteger;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 * 批量事件处理器，一个单线程的消费者(只有一个EventProcessor)
 * 代理EventHandler，管理处理事件以外的其他事情(如: 拉取事件, 等待事件...)
 * 如果{@link EventHandler}实现了{@link LifecycleAware}，那么在线程启动后停止前将会收到一个通知。
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    // 闲置状态IDLE
    private static final int IDLE = 0;
    // 暂停状态HALTED
    private static final int HALTED = IDLE + 1;
    // 运行状态RUNNING
    private static final int RUNNING = HALTED + 1;

    // 运行状态标记
    private final AtomicInteger running = new AtomicInteger(IDLE);
    // 处理事件时的异常处理器
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();
    // 数据提供者
    private final DataProvider<T> dataProvider;
    // 消费者依赖的屏障，用于协调该消费者与生产者/其他消费者之间的速度。
    private final SequenceBarrier sequenceBarrier;
    // 事件处理方法，真正处理事件的对象
    private final EventHandler<? super T> eventHandler;
    // 消费者的消费进度
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final TimeoutHandler timeoutHandler;
    // 批处理开始时的通知器
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        // 如果eventHandler还实现了其他接口
        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
            (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
            (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        // 标记已被中断，如果事件处理器在屏障上等待，那么需要“唤醒”事件处理器，响应中断/停止请求。
        running.set(HALTED);
        sequenceBarrier.alert();  // 通知消费者状态变化，然后停留在这个状态上，直到状态被清除
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }  // 判断是否在运行

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        // IDLE -> RUNNING
        // 原子变量，当能从IDLE切换到RUNNING状态时，前一个线程一定退出了run()
        // 具备happens-before原则，上一个线程修改的状态对于新线程是可见的。
        if (running.compareAndSet(IDLE, RUNNING))
        {
            // 检查中断、停止请求
            // 相当于重置所有状态标记，方便下一次的重新运行
            sequenceBarrier.clearAlert();

            notifyStart();
            try
            {
                if (running.get() == RUNNING)
                {
                    processEvents();
                }
            }
            finally
            {
                // notifyStart调用成功才会走到这里
                notifyShutdown();
                // 在退出的时候会恢复到IDLE状态，且是原子变量，具备happens-before原则
                // 靠volatile来保证
                running.set(IDLE);
            }
        }
        else
        {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                // 到这里可能是running状态，主要是lmax支持的多了，如果不允许重用，线程退出时(notifyShutdown后)不修改为idle状态，那么便不存在该问题。
                earlyExit();
            }
        }
    }

    private void processEvents()
    {
        T event = null;
        // 下一个消费的序号， -1 到 0，这个很重要，对于理解 WorkProcessor有帮助
        // 因为Sequencer的初始值设置了-1,加1便是0,从0开始
        long nextSequence = sequence.get() + 1L;

        // 死循环，因此不会让出线程，需要独立的线程(每一个EventProcessor都需要独立的线程)
        while (true)
        {
            try
            {
                // 通过屏障获取到的最大可用序号，比起自己去查询的话，类自身就简单干净一些，复用性更好
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                if (batchStartAware != null && availableSequence >= nextSequence)
                {
                    // 批量处理事件开始时发送通知
                    batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                }
                // 批量消费，由于没有其它事件处理器和我竞争序号，这些序号我都是可以消费的
                while (nextSequence <= availableSequence)
                {
                    // 根据序号获取事件(RingBuffer)
                    event = dataProvider.get(nextSequence);
                    // 调用事件的处理钩子函数onEvent(业务逻辑)
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;  // 序号叠加
                }

                sequence.set(availableSequence);  // 往前挪到多少个序号
            }
            catch (final TimeoutException e)
            {
                // 超时异常,执行超时的回调函数
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                if (running.get() != RUNNING)
                {
                    break;
                }
            }
            catch (final Throwable ex)
            {
                // 事件处理异常了
                exceptionHandler.handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
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

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}