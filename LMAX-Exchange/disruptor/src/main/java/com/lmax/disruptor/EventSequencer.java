package com.lmax.disruptor;

// done
// 事件序号生成器(RingBuffer)
public interface EventSequencer<T> extends DataProvider<T>, Sequenced
{

}
