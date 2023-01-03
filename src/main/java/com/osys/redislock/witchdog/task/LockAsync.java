package com.osys.redislock.witchdog.task;

import com.osys.redislock.lock.RedisLock;

/**
 * <p><b>{@link LockAsync} Description</b>:
 * </p>
 *
 * @author osys
 */
public class LockAsync implements Runnable {

    private final RedisLock redisLock;
    private final String lockKey;
    private final String uuid;
    private final long expireTime;

    public LockAsync(RedisLock redisLock, String lockKey, String uuid, long expireTime) {
        this.redisLock = redisLock;
        this.lockKey = lockKey;
        this.uuid = uuid;
        this.expireTime = expireTime;
    }

    @Override
    public void run() {
        // 可重入锁续期
        boolean isSuccess = this.redisLock.reentrantLockRenewal(this.lockKey, this.uuid, this.expireTime);
        System.out.printf("【锁标识：%s】【线程标识：%s】【线程还在执行，重置过期时间：%s ms】【重置%s】\n",
                this.lockKey, this.uuid, this.expireTime, isSuccess ? "成功" : "失败");
    }
}
