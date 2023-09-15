package com.osys.demo;

import com.osys.redislock.lock.RedisLock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class DemoService {

    @Resource
    private RedisLock redisLock;

    private final static String LOCK_KEY_DEMO = "LOCK_KEY";

    private static final ThreadPoolExecutor POOL_EXECUTOR = new ThreadPoolExecutor(
            10,
            100,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>()
    );

    /**
     * 测试
     */
    public void demo() {
        Runnable runnable = () -> {
            // 获取锁
            long start = System.currentTimeMillis();
            String threadKey = RedisLock.generationUuid();
            boolean lock = false;
            try {
                lock = getLock(threadKey);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (!lock) {
                return;
            }

            // 模拟任务执行 2s
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            // 释放锁
            boolean unLock = false;
            try {
                unLock = releaseLock(threadKey);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };

        // 模拟多个线程竞争锁
        for (int i = 0; i < 5; i++) {
            POOL_EXECUTOR.execute(runnable);
        }
    }

    /**
     * 轮询的获取锁，直到获取到锁为止
     */
    private boolean getLock(String threadKey) throws InterruptedException {
        // 锁过期时间 1s
        boolean lock = redisLock.lock(LOCK_KEY_DEMO, threadKey, 1000L);
        if (!lock) {
            Thread.sleep(100);  // 避免迭代过多，栈溢出
            return getLock(threadKey);
        }
        return true;
    }

    /**
     * 释放锁
     */
    private boolean releaseLock(String threadKey) throws InterruptedException {
        boolean unlock = redisLock.unlock(LOCK_KEY_DEMO, threadKey);
        if (!unlock) {
            Thread.sleep(100);  // 避免迭代过多，栈溢出
            return releaseLock(threadKey);
        }
        return true;
    }
}
