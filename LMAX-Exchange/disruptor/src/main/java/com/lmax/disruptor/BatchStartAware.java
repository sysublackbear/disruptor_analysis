package com.lmax.disruptor;

public interface BatchStartAware
{
    // 钩子函数
    void onBatchStart(long batchSize);
}
