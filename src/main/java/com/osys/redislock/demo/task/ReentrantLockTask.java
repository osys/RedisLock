package com.osys.redislock.demo.task;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osys.redislock.lock.RedisLock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * <p><b>{@link ReentrantLockTask} Description</b>:
 * </p>
 *
 * @author osys
 */
public class ReentrantLockTask implements Callable<JsonArray> {

    // 每个线程的重入次数记录
    public final static ThreadLocal<Integer> LOCAL_REENTRANT = new ThreadLocal<>();
    private int reentrant;
    private String lockKey;
    private String uuid;
    private long expireTime;
    private long taskTime;
    private RedisLock redisLock;

    public ReentrantLockTask(int reentrant, String lockKey, String uuid, long expireTime, long taskTime, RedisLock redisLock) {
        this.reentrant = reentrant;
        this.lockKey = lockKey;
        this.uuid = uuid;
        this.expireTime = expireTime;
        this.taskTime = taskTime;
        this.redisLock = redisLock;
    }

    @Override
    public JsonArray call() throws Exception {
        ReentrantLockTask.LOCAL_REENTRANT.set(reentrant);
        List<ReentrantLockLog> reentrantLockLogs = reentrantMethod(new ArrayList<>());
        JsonArray array = new JsonArray();
        for (ReentrantLockLog reentrantLockLog : reentrantLockLogs) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("threadName", reentrantLockLog.getThreadName());
            jsonObject.addProperty("uuid", reentrantLockLog.getUuid());
            jsonObject.addProperty("lockTime", reentrantLockLog.getLockTime());
            jsonObject.addProperty("releaseTime", reentrantLockLog.getReleaseTime());
            jsonObject.addProperty("reentrantCount", reentrantLockLog.getReentrantCount());
            jsonObject.addProperty("lock", reentrantLockLog.getLock());
            jsonObject.addProperty("release", reentrantLockLog.getRelease());
            jsonObject.addProperty("lockKey", this.lockKey);
            array.add(jsonObject);
        }
        ReentrantLockTask.LOCAL_REENTRANT.remove();
        return array;
    }

    public List<ReentrantLockLog> reentrantMethod(List<ReentrantLockLog> reentrantLockLogList) throws InterruptedException {
        // 获取锁，直到获取成功
        boolean lock = false;
        long lockTime = System.currentTimeMillis();
        while (!lock) {
            lock = redisLock.tryGetReentrantLock(lockKey, uuid, expireTime);
            lockTime = System.currentTimeMillis();
        }
        // 可以看作获取锁后 do something
        Thread.sleep(taskTime);
        // 记录一下，当前线程，获取一把锁，重入日志情况
        int ree = reentrantLockLogList.size() + 1;
        ReentrantLockLog lockLog =
                new ReentrantLockLog(Thread.currentThread().getName(), uuid, true, lockTime, true, System.currentTimeMillis(), ree);
        reentrantLockLogList.add(lockLog);
        if (ree < ReentrantLockTask.LOCAL_REENTRANT.get()) {
            reentrantMethod(reentrantLockLogList);
        }
        // 释放锁
        boolean release;
        try {
            release = redisLock.tryReleaseReentrantLock(lockKey, uuid);
        } catch (Exception e) {
            release = false;
        }
        long releaseTime = System.currentTimeMillis();
        // 更新释放锁的情况
        lockLog.setRelease(release);
        lockLog.setReleaseTime(releaseTime);
        return reentrantLockLogList;
    }

    private class ReentrantLockLog {
        private String threadName;
        private String uuid;
        private Long lockTime;
        private Long releaseTime;
        private int reentrantCount;
        private boolean lock;
        private boolean release;

        public ReentrantLockLog(String threadName, String uuid,
                                boolean lock, Long lockTime,
                                boolean release, Long releaseTime,
                                int reentrantNum) {
            this.threadName = threadName;
            this.uuid = uuid;
            this.lockTime = lockTime;
            this.releaseTime = releaseTime;
            this.reentrantCount = reentrantNum;
            this.lock = lock;
            this.release = release;
        }

        public String getThreadName() {
            return threadName;
        }

        public void setThreadName(String threadName) {
            this.threadName = threadName;
        }

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public Long getLockTime() {
            return lockTime;
        }

        public void setLockTime(Long lockTime) {
            this.lockTime = lockTime;
        }

        public Long getReleaseTime() {
            return releaseTime;
        }

        public void setReleaseTime(Long releaseTime) {
            this.releaseTime = releaseTime;
        }

        public int getReentrantCount() {
            return reentrantCount;
        }

        public void setReentrantCount(int reentrantCount) {
            this.reentrantCount = reentrantCount;
        }

        public boolean getLock() {
            return lock;
        }

        public void setLock(boolean lock) {
            this.lock = lock;
        }

        public boolean getRelease() {
            return release;
        }

        public void setRelease(boolean release) {
            this.release = release;
        }
    }
}
