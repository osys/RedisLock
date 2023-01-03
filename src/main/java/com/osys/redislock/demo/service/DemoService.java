package com.osys.redislock.demo.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.osys.redislock.demo.task.ReentrantLockTask;
import com.osys.redislock.lock.RedisLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolExecutorFactoryBean;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p><b>{@link 1} Description</b>:
 * </p>
 *
 * @author osys
 */
@Service(value = "demoService")
public class DemoService {

    @Resource(name = "redisLock")
    private RedisLock redisLock;

    /** 锁过期时间 */
    private static long expireTime = 300;

    /** 任务执行时间 */
    private static long taskTime = 1000;

    private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(
            100, 500, 60 * 1000L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadPoolExecutorFactoryBean()
    );

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    public void demo() {
        Set<String> keys = stringRedisTemplate.keys("channe*");
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            System.out.printf("stringRedisTemplate.opsForValue().get(\"%s\") = %s\n",
                    key, stringRedisTemplate.opsForValue().get(key));
        }
    }

    /**
     * 一个线程获取可重入锁。任务比锁过期时间长。也会测试的是看门狗机制
     * @param requestParams 请求参数
     * @param isPrint 释放打印数获取锁的信息
     * @return 获取锁信息结果
     */
    public JsonArray oneThreadGetAndReleaseReentrantLock(JsonObject requestParams, boolean isPrint)
            throws ExecutionException, InterruptedException {
        // 锁标识
        String lockKey = "ReleaseLock";
        // 线程标识
        String uuid = RedisLock.generationUuid();
        // 重入次数 reentrant 次
        int reentrant = requestParams.get("reentrant").getAsInt();
        // 执行获取锁操作
        Future<JsonArray> future = EXECUTOR_SERVICE.submit(
                new ReentrantLockTask(reentrant, lockKey, uuid, expireTime, taskTime, redisLock));
        // 结果
        JsonArray logArray = future.get();
        // 按时间顺序输出当前线程每次重入获取锁的时间
        if (isPrint) {
            System.out.println("------------------------------------ 操作结果 ------------------------------------ ");
            this.printLog(logArray);
        }
        return logArray;
    }

    /**
     * 多个线程获取可重入锁。任务比锁过期时间长。也会测试的是看门狗机制
     * @param requestParams 请求参数
     * @param isPrint 释放打印数获取锁的信息
     * @return 获取锁信息结果
     */
    public JsonArray multipleThreadGetAndReleaseReentrantLock(JsonObject requestParams, boolean isPrint)
            throws ExecutionException, InterruptedException {
        // threadNum 个任务
        int threadNum = requestParams.get("threadNum").getAsInt();
        // 重入次数 reentrant 次
        int reentrant = requestParams.get("reentrant").getAsInt();
        // 执行任务：threadNum 个线程任务，每个任务重入次数 reentrant 次
        List<Future<JsonArray>> futures = new ArrayList<>();
        for (int i = 0; i < threadNum; i++) {
            // 锁标识
            String lockKey = "ReleaseLock";
            // 线程标识
            String uuid = RedisLock.generationUuid();
            Future<JsonArray> submit = EXECUTOR_SERVICE.submit(
                    new ReentrantLockTask(reentrant, lockKey, uuid, expireTime, taskTime, redisLock));
            futures.add(submit);
        }
        // 结果
        JsonArray arrays = new JsonArray();
        for (Future<JsonArray> future : futures) {
            arrays.addAll(future.get());
        }
        // 按时间顺序输出各个线程每次重入获取锁的时间
        if (isPrint) {
            System.out.println("------------------------------------ 操作结果 ------------------------------------ ");
            this.printLog(arrays);
        }
        return arrays;
    }

    private void printLog(JsonArray logArray) {
        Map<Long, String> lockLogMap = new HashMap<>();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSS");
        for (JsonElement element : logArray) {
            JsonObject jsonObj = element.getAsJsonObject();
            String lockLog = String.format("【获取或释放】【锁标识: %s】【线程标识：%s】【获取锁时间：%s】【获取锁：%s】【释放锁时间：%s】【释放锁：%s】【重入次数：%s】",
                    jsonObj.get("lockKey").getAsString(),
                    jsonObj.get("uuid").getAsString(),
                    simpleDateFormat.format(new Date(jsonObj.get("lockTime").getAsLong())),
                    jsonObj.get("lock").getAsString(),
                    simpleDateFormat.format(new Date(jsonObj.get("releaseTime").getAsLong())),
                    jsonObj.get("release").getAsString(),
                    jsonObj.get("reentrantCount").getAsString()
            );
            lockLogMap.put(jsonObj.get("lockTime").getAsLong(), lockLog);
        }
        List<Long> lockLogTimes = new ArrayList<>(lockLogMap.keySet());
        Collections.sort(lockLogTimes);
        for (Long lockLogTime : lockLogTimes) {
            System.out.println(lockLogMap.get(lockLogTime));
        }
    }
}
