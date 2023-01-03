package com.osys.redislock.lock;

import com.osys.redislock.witchdog.aspect.LockMethodListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;

@Component(value = "redisLock")
@DependsOn({"stringRedisTemplate", "redisTemplate", "redisConnectionFactory"})
public class RedisLock {

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    /** 生成一个 uuid 标识一个线程 */
    public static String generationUuid() {
        // 线程标识
        return UUID.randomUUID().toString();
    }

    /**
     * 获取锁，可重入锁
     * @param lockKey 锁标识  KEY[1]
     * @param uuid 线程标识 ARGV[1]
     * @param expireTime 锁过期时间 ARGV[2]
     * @return 是否获取成功
     */
    @LockMethodListener(name = "tryGetReentrantLock")
    public boolean tryGetReentrantLock(String lockKey, String uuid, long expireTime) {
        if (expireTime <= 0) {
            return false;
        }
        boolean lock = false;
        // 最后返回 0 或 1（0表示获取锁失败，1表示获取锁成功）
        String luaScriptStr =
                // 是否有线程获取了锁 KEYS[1]
                "if (redis.call('exists', KEYS[1]) == 0) then " +
                    // 没有线程获取锁 KEYS[1]， 创建散列表数据类型的锁 KEYS[1]，并为 KEYS[1] 锁中的 ARGV[1] 字段值加上指定增量值 1
                    "redis.call('hincrby', KEYS[1], ARGV[1], 1); " +
                    // 设置 KEYS[1] 锁的到期时间(单位：ms)
                    "redis.call('pexpire', KEYS[1], ARGV[2]); " +
                    "return 1; " +
                    "end; " +
                // 当前线程持有该锁的数量
                "local beforeGetCurrentLockHasNum = 0; " +
                // 锁 KEYS[1] 中是否存在字段 ARGV[1]，如果存在，那么说明是当前线程 ARGV[1] 获取了锁 KEYS[1]
                "if (redis.call('hexists', KEYS[1], ARGV[1]) == 1) then " +
                    // 获取当前线程持有该锁的数量
                    "beforeGetCurrentLockHasNum = redis.call('hget', KEYS[1], ARGV[1]); " +
                    // 为 KEYS[1] 锁中的 ARGV[1] 字段值加上指定增量值(1)
                    "redis.call('hincrby', KEYS[1], ARGV[1], 1); " +
                    // 以毫秒为单位来设置 KEYS[1] 锁的过期时间 ARGV[2] --- (更新过期时间)
                    "redis.call('pexpire', KEYS[1], ARGV[2]); " +
                    // 获取线程 ARGV[1] 现在拥有锁 KEYS[1] 的数量，减去之前没有重入时的数量，看看是否重入成功，重入成功，那么结果=1
                    "local afterGetCurrentLockHasNum = redis.call('hget', KEYS[1], ARGV[1]) - beforeGetCurrentLockHasNum; " +
                    // 如果获取成功，那么结果=1，否则=0
                    "return afterGetCurrentLockHasNum; " +
                    "end; " +
                // 线程 ARGV[1] 不持有锁 KEYS[1]，锁已经被其它线程获取了，beforeGetCurrentLockHasNum = 0
                "return beforeGetCurrentLockHasNum; ";
        RedisScript<Long> luaScriptObj = new DefaultRedisScript<>(luaScriptStr, Long.class);
        String result = String.valueOf(stringRedisTemplate.execute(luaScriptObj,
                        // KEY[1]=lockKey
                        Collections.singletonList(lockKey),
                        // ARGV[1]=uuid，ARGV[2]=expireTime
                        uuid, String.valueOf(expireTime)));
        if (!"0".equals(result)) {
            lock = true;
        }
        return lock;
    }

    /**
     * 释放锁，可重入锁
     * @param lockKey 锁标识 KEY[1]
     * @param uuid 线程标识 ARGV[1]
     * @return 是否释放成功
     */
    @LockMethodListener(name = "tryReleaseReentrantLock")
    public boolean tryReleaseReentrantLock(String lockKey, String uuid) {
        boolean release = false;
        // 最后返回 0 或 1（0表示没有释放锁，或者释放锁失败。1表示释放锁成功）
        String luaScriptStr =
                // 查看线程 ARGV[1] 是否获取了 KEYS[1] 锁
                "if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then " +
                    // 线程 ARGV[1] 没有获取 KEYS[1] 锁
                    "return 0; " +
                    "end; " +
                // 线程 ARGV[1] 获取了 KEYS[1] 锁，释放一个锁，然后查看当前重入次数(持有量)
                "local counter = redis.call('hincrby', KEYS[1], ARGV[1], -1); " +
                "if (counter == 0) then " +
                    // 释放锁后，不再持有锁，删除 KEYS[1] 锁，允许后续别的线程加锁
                    "redis.call('del', KEYS[1]); " +
                    "return 1; " +
                    "end; " +
                // 释放锁后，还持有锁，返回释放锁的数量：1
                "return 1; ";
        RedisScript<Long> luaScriptObj = new DefaultRedisScript<>(luaScriptStr, Long.class);
        String result = String.valueOf(stringRedisTemplate.execute(luaScriptObj, Collections.singletonList(lockKey), uuid));
        if ("1".equals(result)) {
            release = true;
        }
        return release;
    }

    /**
     * 获取 uuid 线程持有 lockKey 可重入锁的数量
     * @param lockKey 锁标识 KEY[1]
     * @param uuid 线程标识 ARGV[1]
     * @return 当前线程持有 ARGV[1] 可重入锁的数量
     */
    public int currentThreadReentrantLockCount(String lockKey, String uuid) {
        int count = 0;
        String luaScriptStr =
                // 查看线程 ARGV[1] 是否获取了 KEYS[1] 锁
                "if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then " +
                    // 线程 ARGV[1] 没有获取 KEYS[1] 锁
                    "return 0; " +
                "end; " +
                // 线程 ARGV[1] 获取了 KEYS[1] 锁
                "return redis.call('hget', KEYS[1], ARGV[1]); ";
        RedisScript<String> luaScriptObj = new DefaultRedisScript<>(luaScriptStr, String.class);
        String result = stringRedisTemplate.execute(luaScriptObj, Collections.singletonList(lockKey), uuid);
        if (result != null) {
            count = Integer.parseInt(result);
        }
        return count;
    }

    /**
     * 可重入锁，锁续期
     * @param lockKey 锁标识 KEY[1]
     * @param uuid 线程标识 ARGV[1]
     * @param expireTime 重置时，所得过期时间 ARGV[2]
     * @return 续期成功/无需续期 true，续期失败 false
     */
    public boolean reentrantLockRenewal(String lockKey, String uuid, long expireTime) {
        String luaScriptStr =
                // 是否是该线程 ARGV[1] 获取了锁 KEYS[1]
                "if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then " +
                    // 不是该线程 ARGV[1] 获取了锁 KEYS[1]
                    "return 'fail'; " +
                "end; " +
                // 是该线程 ARGV[1] 获取了锁 KEYS[1]，重置过期时间 ARGV[2]
                "redis.call('pexpire', KEYS[1], ARGV[2]); " +
                "return 'success'; ";
        return renewal(luaScriptStr, lockKey, uuid, expireTime);
    }

    /**
     * 锁续期
     * @param luaScriptStr 执行脚本
     * @param lockKey 锁标识 KEY[1]
     * @param uuid 线程标识 ARGV[1]
     * @param expireTime 重置时，所得过期时间 ARGV[2]
     * @return 锁时间重置 true、锁过期时间还很长 true
     */
    private boolean renewal(String luaScriptStr, String lockKey, String uuid, long expireTime) {
        if (expireTime <= 0) {
            return false;
        }
        boolean renewal = false;
        RedisScript<String> luaScriptObj = new DefaultRedisScript<>(luaScriptStr, String.class);
        String result = stringRedisTemplate.execute(luaScriptObj, Collections.singletonList(lockKey), uuid, String.valueOf(expireTime));
        if ("success".equals(result)) {
            renewal = true;
        }
        return renewal;
    }
}
