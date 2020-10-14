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
 * An aggregate collection of {@link EventHandler}s that get called in sequence for each event.
 * 聚合EventHandler,这些EventHandler共用一个线程(EventProcessor),解决EventHandler需要大量线程的问题。
 * (普通的EventHandler都会被独立映射到一个EventProcessor，会分配一个独立的线程，线程创建压力较大)
 * 这些handler最终会在一个EventProcessor中，因此共享EventProcessor的序号(Sequence)
 *
 * 但是注意：任意一个EventHandler处理事件时或通知时出现异常，其他eventHandler就无法执行对应的方法
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class AggregateEventHandler<T>
    implements EventHandler<T>, LifecycleAware
{
    // EventHandler的集合
    private final EventHandler<T>[] eventHandlers;

    /**
     * Construct an aggregate collection of {@link EventHandler}s to be called in sequence.
     *
     * @param eventHandlers to be called in sequence.
     */
    @SafeVarargs
    public AggregateEventHandler(final EventHandler<T>... eventHandlers)
    {
        this.eventHandlers = eventHandlers;
    }

    @Override
    public void onEvent(final T event, final long sequence, final boolean endOfBatch)
        throws Exception
    {
        for (final EventHandler<T> eventHandler : eventHandlers)
        {
            eventHandler.onEvent(event, sequence, endOfBatch);
        }
    }

    @Override
    public void onStart()
    {
        for (final EventHandler<T> eventHandler : eventHandlers)
        {
            // 左边对象是否为右边类的实例
            if (eventHandler instanceof LifecycleAware)
            {
                ((LifecycleAware) eventHandler).onStart();
            }
        }
    }

    @Override
    public void onShutdown()
    {
        for (final EventHandler<T> eventHandler : eventHandlers)
        {
            if (eventHandler instanceof LifecycleAware)
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
        }
    }
}
