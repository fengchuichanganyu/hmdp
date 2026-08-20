package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的超时时间，单位为秒
     * @return true代表获取锁成功，false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
