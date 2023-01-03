package com.osys.redislock.witchdog.task;

import com.osys.redislock.lock.RedisLock;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>{@link LockListener} Description</b>:
 * </p>
 *
 * @author osys
 */
@Component(value = "lockListener")
public class LockListener {

    private static final ConcurrentHashMap<String, ReentrantInfo> FUTURE_MAP = new ConcurrentHashMap<>();

    @Resource(name = "redisLock")
    private RedisLock redisLock;

    private static final ScheduledThreadPoolExecutor SCHEDULED_THREAD_POOL_EXECUTOR = new ScheduledThreadPoolExecutor(30);

    static {
        // 设置取消任务应在取消时立即从工作队列中删除的策略
        SCHEDULED_THREAD_POOL_EXECUTOR.setRemoveOnCancelPolicy(true);
    }

    /**
     * 看门狗
     * @param lockKey 锁标识
     * @param uuid 线程标识
     * @param expireTime 锁过期时间
     */
    public void witchDog(String lockKey, String uuid, long expireTime) {
        String key = lockKey + uuid;
        // 如果该线程已经启动过看门狗，那么不再启动看门狗
        ReentrantInfo reentrantInfo = FUTURE_MAP.get(lockKey + uuid);
        if (reentrantInfo != null) {
            ++reentrantInfo.reentrant;
            System.out.printf("【优质看门狗: %s%s】【看守物品数(+1): %s个】\n",
                    lockKey, uuid, reentrantInfo.reentrant);
            return;
        }
        // 该线程没有启动过看门狗
        ScheduledFuture<?> future = SCHEDULED_THREAD_POOL_EXECUTOR.scheduleAtFixedRate(
                // 执行续期的task
                new LockAsync(this.redisLock, lockKey, uuid, expireTime),
                // 首次执行间隔时间
                expireTime / 2,
                // 后续执行间隔时间
                expireTime / 2,
                // 时间单位
                TimeUnit.MILLISECONDS);
        // 保存该线程看门狗，待任务完成后，删除该看门狗
        reentrantInfo = new ReentrantInfo(future, 1);
        FUTURE_MAP.put(key, reentrantInfo);
        System.out.printf("【优质看门狗: %s%s】【看守物品数(+1): %s个】\n",
                lockKey, uuid, reentrantInfo.reentrant);
    }

    /**
     * 锁被释放，去掉看门狗
     * @param lockKey 锁标识
     * @param uuid 线程标识
     */
    public void cancelWitch(String lockKey, String uuid) {
        // 任务完成后，删除对应线程看门狗（重入次数为0时，任务已经完成，可以删除看门狗）
        ReentrantInfo reentrantInfo = FUTURE_MAP.get(lockKey + uuid);
        if (reentrantInfo == null || reentrantInfo.future.isCancelled()) {
            return;
        }
        --reentrantInfo.reentrant;
        if (reentrantInfo.reentrant <= 0) {
            reentrantInfo.future.cancel(true);
            System.out.printf("【优质看门狗: %s%s】【看守物品数(-1): %s个】 ------------ 看门狗死掉！\n\n\n",
                    lockKey, uuid, 0);
            return;
        }
        System.out.printf("【优质看门狗: %s%s】【看守物品数(-1): %s个】\n",
                lockKey, uuid, reentrantInfo.reentrant);
    }

    private static class ReentrantInfo {
        // 看门狗任务
        public ScheduledFuture<?> future;

        // 重入次数
        public int reentrant;

        public ReentrantInfo(ScheduledFuture<?> future, int reentrant) {
            this.future = future;
            this.reentrant = reentrant;
        }
    }
}
