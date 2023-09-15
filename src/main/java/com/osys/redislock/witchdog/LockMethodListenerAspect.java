package com.osys.redislock.witchdog;

import com.osys.redislock.lock.RedisLock;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Aspect
@Component
public class LockMethodListenerAspect {

    /**
     * 看门狗信息Map
     */
    private static final ConcurrentHashMap<String, LockInfo> WITCH_DOG_MAP = new ConcurrentHashMap<>();

    /**
     * 执行续期的线程池
     */
    private static final ScheduledThreadPoolExecutor SCHEDULED_THREAD_POOL_EXECUTOR =
            new ScheduledThreadPoolExecutor(30);

    @Resource(name = "redisLock")
    private RedisLock redisLock;

    @Pointcut("@annotation(lockMethodListener)")
    public void lockPointCut(LockMethodListener lockMethodListener) {
    }

    @AfterReturning(
            returning = "resultValue",
            value = "lockPointCut(lockMethodListener)",
            argNames = "joinPoint,lockMethodListener,resultValue"
    )
    public void afterMethodListener(JoinPoint joinPoint, LockMethodListener lockMethodListener, Boolean resultValue) {
        // 参数获取
        Object[] args = joinPoint.getArgs();
        boolean isGetLock = lockMethodListener.isGetLock();     // 获取锁true，释放锁false
        String lockKey = String.valueOf(args[0]);               // 锁标识
        String uuid = String.valueOf(args[1]);                  // 线程标识

        // 如果没有获取到锁，直接返回，无需启动 看门狗
        if (!resultValue && !isGetLock) {
            return;
        }

        // 锁(获取)：获取锁成功
        if (resultValue && isGetLock) {
            long expireTime = Long.parseLong(String.valueOf(args[2]));
            if (expireTime <= 0) {      // 若是执行加锁操作时，锁的过期时间设置为0，那么不用启动 看门狗
                return;
            }
            witch(lockKey, uuid, expireTime);
            return;
        }

        // 锁(释放)：释放锁成功
        if (isGetLock) {
            unWitch(lockKey, uuid);
        }
    }

    /**
     * 看门狗
     *
     * @param lockKey    锁标识
     * @param uuid       线程标识
     * @param expireTime 锁过期时间
     */
    public void witch(String lockKey, String uuid, long expireTime) {
        String key = lockKey + uuid;
        // 如果该线程已经启动过看门狗，那么不再启动看门狗，每个锁只启动一个看门狗
        LockInfo reentrantInfo = WITCH_DOG_MAP.get(lockKey + uuid);
        if (reentrantInfo != null) {
            ++reentrantInfo.reentrant;
            return;
        }

        // 该线程没有启动过看门狗（线程首次获取锁）
        // 创建一个锁续期相关的延时任务
        ScheduledFuture<?> future = SCHEDULED_THREAD_POOL_EXECUTOR.scheduleAtFixedRate(
                // 执行续期的task
                new LockAsync(this.redisLock, lockKey, uuid, expireTime),
                // 首次执行间隔时间
                expireTime / 2,
                // 后续执行间隔时间
                expireTime / 2,
                // 时间单位
                TimeUnit.MILLISECONDS
        );

        // 保存该线程看门狗，待任务完成后，删除该看门狗。启动看门狗时 reentrant = 1
        reentrantInfo = new LockInfo(future, 1);
        WITCH_DOG_MAP.put(key, reentrantInfo);
    }

    /**
     * 锁被释放，去掉看门狗
     *
     * @param lockKey 锁标识
     * @param uuid    线程标识
     */
    public void unWitch(String lockKey, String uuid) {
        // 任务完成后，删除对应线程看门狗（重入次数为0时，任务已经完成，可以删除看门狗）
        LockInfo reentrantInfo = WITCH_DOG_MAP.get(lockKey + uuid);
        if (reentrantInfo == null || reentrantInfo.future.isCancelled()) {
            return;
        }
        // 按照 锁重入次数进行判断，当重入次数为0时，关闭看门狗
        // --reentrantInfo.reentrant;
        // if (reentrantInfo.reentrant <= 0) {
        //     reentrantInfo.future.cancel(true);
        // }
        // 正常情况下，一但执行锁释放，直接关闭看门狗
        reentrantInfo.future.cancel(true);
    }

    /**
     * 看门狗相关信息
     */
    private static class LockInfo {
        /**
         * 看门狗任务
         */
        public ScheduledFuture<?> future;

        /**
         * 锁重入次数
         */
        public int reentrant;

        public LockInfo(ScheduledFuture<?> future, int reentrant) {
            this.future = future;
            this.reentrant = reentrant;
        }
    }

    /**
     * 执行锁续期的任务
     */
    public static class LockAsync implements Runnable {

        /**
         * redis锁类
         */
        private final RedisLock redisLock;

        /**
         * 锁标识
         */
        private final String lockKey;

        /**
         * 线程标识
         */
        private final String uuid;

        /**
         * 过期时间
         */
        private final long expireTime;

        public LockAsync(RedisLock redisLock, String lockKey, String uuid, long expireTime) {
            this.redisLock = redisLock;
            this.lockKey = lockKey;
            this.uuid = uuid;
            this.expireTime = expireTime;
        }

        @Override
        public void run() {
            // 锁续期
            this.redisLock.renewal(this.lockKey, this.uuid, this.expireTime);
        }
    }
}