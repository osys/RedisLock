## 1. Redis 分布式锁说明

Java 实现 Redis 分布式锁案例已经上传到笔者的[GitHub](https://github.com/osys/RedisLock)，欢迎下载参考，如有错误希望大佬指正。

在大多数情况下，应该都是使用成熟的分布式锁框架，如 `Redisson`。这里只是根据 `Redisson` 部分源码思想进行的个人摸索，编写了一个利用Redis实现的分布式可重入锁，包含看门狗对锁进行续期。



### 1.1 什么是 Redis 分布式锁

* 在 Java 中提供了 synchronized 和 Lock 锁，来保证多线程程序中的线程安全问题。
* 分布式锁指的是，在分布式系统，不同的进程中，访问共享资源的一张锁的实现。

如果`不同的系统`或同一个系统的`不同主机`之间共享了某个临界资源，往往需要互斥来防止彼此干扰，以保证一致性。



### 1.2 分布式锁需要满足的条件

1. **互斥性**。在任意时刻，只有一个线程能持有锁。
2. **重入锁**。一个线程能重复获取同一把锁。
3. **不会发生死锁**。即使有一个线程在持有锁的期间崩溃而没有主动解锁，也能保证后续其他客户端能加锁。 
4. **具有容错性**。只要大部分的 Redis 节点正常运行，就可以加锁和解锁。 
5. **解铃还须系铃人**。加锁和解锁必须是同一个线程，自己不能把别人加的锁给解了。
6. **锁过期续费**。在线程任务未完成的情况下，需要自动续约锁，以防锁过期。



### 1.3 Redis 分布式锁原理

> 加锁

* 一个分布式系统中，可能会存在各种各样的分布式锁，这些锁，都应该有一个标识，如：`lock1`、`lock2`、`lock3`...
* 对于一把锁，如标识为`lock1`的锁，可能会有好几个不同机器上的线程在竞争。
* 竞争锁的线程，也应该给它们一个线程标识，如：`uuid1`、`uuid2`、`uuid3`...
* 如果线程 `uuid1` 获取了锁 `lock1`，在还未释放锁的时候，允许线程 `uuid1` 能重复获取 `lock1`(记录获取数量)，释放直到获取该锁的数量为`零`(锁不再被线程`uuid1`持有)

Redis 哈希是结构化为字段值对集合的记录类型。可以使用散列来表示基本对象并存储计数器分组等。

```
hset key filed value
```

散列表(hashmap)能够满足保存：`锁标识`:`线程标识`:`重入次数`



> 解锁

如上面`加锁`中所述，锁是可以重入的。

* 一个线程可以重复获取同一把锁，因此每次解锁，该锁的记录值 `value` 应该减1
* 如果某个线程获取锁 `lock1` 的值为零了，锁应该被释放，这时候要允许别的线程获取锁 `lock1`。



### 1.4 Redis 分布式锁实现原理(lua 脚本说明)

我们都知道 redis 是单线程的，因此可以通过lua脚本来获取锁、释放锁、锁续期，保证原子性。

> 加锁

KEYS[1] ---- 锁标识

ARGV[1] ---- 线程标识

ARGV[2] ---- 锁过期时间

```lua
-- 是否有线程获取了锁 KEYS[1]
if (redis.call('exists', KEYS[1]) == 0) then 
    -- 没有线程获取锁 KEYS[1]， 创建散列表数据类型的锁 KEYS[1]，并为 KEYS[1] 锁中的 ARGV[1] 字段值加上指定增量值 1
    redis.call('hincrby', KEYS[1], ARGV[1], 1); 
    -- 设置 KEYS[1] 锁的到期时间(单位：ms)
    redis.call('pexpire', KEYS[1], ARGV[2]); 
    return 1; 
	end; 
-- 当前线程持有该锁的数量
local beforeGetCurrentLockHasNum = 0; 
-- 锁 KEYS[1] 中是否存在字段 ARGV[1]，如果存在，那么说明是当前线程 ARGV[1] 获取了锁 KEYS[1]
if (redis.call('hexists', KEYS[1], ARGV[1]) == 1) then 
    -- 获取当前线程持有该锁的数量
    beforeGetCurrentLockHasNum = redis.call('hget', KEYS[1], ARGV[1]); 
    -- 为 KEYS[1] 锁中的 ARGV[1] 字段值加上指定增量值(1)
    redis.call('hincrby', KEYS[1], ARGV[1], 1); 
    -- 以毫秒为单位来设置 KEYS[1] 锁的过期时间 ARGV[2] --- (更新过期时间)
    redis.call('pexpire', KEYS[1], ARGV[2]); 
    -- 获取线程 ARGV[1] 现在拥有锁 KEYS[1] 的数量，减去之前没有重入时的数量，看看是否重入成功，重入成功，那么结果=1
    local afterGetCurrentLockHasNum = redis.call('hget', KEYS[1], ARGV[1]) - beforeGetCurrentLockHasNum; 
    -- 如果获取成功，那么结果=1，否则=0
    return afterGetCurrentLockHasNum; 
	end; 
-- 线程 ARGV[1] 不持有锁 KEYS[1]，锁已经被其它线程获取了，beforeGetCurrentLockHasNum = 0
return beforeGetCurrentLockHasNum;
```



> 解锁

KEYS[1] ---- 锁标识

ARGV[1] ---- 线程标识

```lua
-- 查看线程 ARGV[1] 是否获取了 KEYS[1] 锁
if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then 
    -- 线程 ARGV[1] 没有获取 KEYS[1] 锁
    return 0; 
	end; 
-- 线程 ARGV[1] 获取了 KEYS[1] 锁，释放一个锁，然后查看当前重入次数(持有量)
local counter = redis.call('hincrby', KEYS[1], ARGV[1], -1); 
if (counter == 0) then " +
    -- 释放锁后，不再持有锁，删除 KEYS[1] 锁，允许后续别的线程加锁
    redis.call('del', KEYS[1]); 
    return 1; 
	end; 
-- 释放锁后，还持有锁，返回释放锁的数量：1
return 1; 
```



> 锁续期

KEYS[1] ---- 锁标识

ARGV[1] ---- 线程标识

ARGV[2] ---- 重置时间

```lua
-- 是否是该线程 ARGV[1] 获取了锁 KEYS[1]
if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then 
    -- 不是该线程 ARGV[1] 获取了锁 KEYS[1]
    return 'fail';
    end;
-- 是该线程 ARGV[1] 获取了锁 KEYS[1]，重置过期时间 ARGV[2]
redis.call('pexpire', KEYS[1], ARGV[2]); 
return 'success';
```



## 2. Java 实现 Redis 分布式锁

这里通过 Spring Boot 示例项目，使用 `spring-boot-starter-data-redis` 来连接 redis。主要是体现其实现思想。

创建一个 RedisLock 类：

```java
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

    /** 这里使用的是spring-boot-starter-data-redis，使用别的Redis连接工具也一样 */
    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    /** 生成一个 uuid 标识一个线程 */
    public static String generationUuid() {
        // 线程标识
        return UUID.randomUUID().toString();
    }
}

```



### 2.1 获取锁

```java
/**
 * 获取锁，可重入锁
 *
 * @param lockKey    锁标识  KEY[1]
 * @param uuid       线程标识 ARGV[1]
 * @param expireTime 锁过期时间 ARGV[2]
 * @return 是否获取成功
 */
public boolean lock(String lockKey, String uuid, long expireTime) {
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
    String result = String.valueOf(stringRedisTemplate.execute(
            luaScriptObj,
            // KEY[1]=lockKey
            Collections.singletonList(lockKey),
            // ARGV[1]=uuid，ARGV[2]=expireTime
            uuid,
            String.valueOf(expireTime)
    ));
    if (!"0".equals(result)) {
        lock = true;
        System.out.printf("线程：%s，获取锁：%s，过期时间：%s\n", uuid, lockKey, expireTime);
    }
    return lock;
}
```



### 2.2 释放锁

```java
/**
 * 释放锁，可重入锁
 *
 * @param lockKey 锁标识 KEY[1]
 * @param uuid    线程标识 ARGV[1]
 * @return 是否释放成功
 */
public boolean unlock(String lockKey, String uuid) {
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
    String result = String.valueOf(stringRedisTemplate.execute(
            luaScriptObj,
            Collections.singletonList(lockKey),
            uuid
    ));
    if ("1".equals(result)) {
        release = true;
        System.out.printf("线程：%s，释放锁：%s\n", uuid, lockKey);
    }
    return release;
}
```



### 2.3 锁续期

```java
/**
 * 锁续期
 *
 * @param lockKey    锁标识 KEY[1]
 * @param uuid       线程标识 ARGV[1]
 * @param expireTime 重置时，所得过期时间 ARGV[2]
 * @return 续期成功/无需续期 true，续期失败 false
 */
public boolean renewal(String lockKey, String uuid, long expireTime) {
    String luaScriptStr =
            // 是否是该线程 ARGV[1] 获取了锁 KEYS[1]
            "if (redis.call('hexists', KEYS[1], ARGV[1]) == 0) then " +
                // 不是该线程 ARGV[1] 获取了锁 KEYS[1]
                "return 'fail'; " +
            "end; " +
            // 是该线程 ARGV[1] 获取了锁 KEYS[1]，重置过期时间 ARGV[2]
            "redis.call('pexpire', KEYS[1], ARGV[2]); " +
            "return 'success'; ";
    if (expireTime <= 0) {
        return false;
    }
    boolean renewal = false;
    RedisScript<String> luaScriptObj = new DefaultRedisScript<>(luaScriptStr, String.class);
    String result = stringRedisTemplate.execute(
            luaScriptObj,
            Collections.singletonList(lockKey),
            uuid,
            String.valueOf(expireTime)
    );
    if ("success".equals(result)) {
        renewal = true;
        System.out.printf("线程：%s，持有锁：%s，进行续期：%s\n", uuid, lockKey, expireTime);
    }
    return renewal;
}
```



## 3. Java 实现锁续期

### 3.1 创建一个注解

创建一个注解，用于标识获取锁、释放锁。

```java
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LockMethodListener {

    /**
     * 获取锁：true
     * 释放锁：false
     */
    boolean isGetLock() default false;
}
```



### 3.2 使用注解

在获取锁方法【`tryGetReentrantLock`】、释放锁方法【`tryReleaseReentrantLock`】上使用该注解：

```java
@LockMethodListener(isGetLock = true)
public boolean lock(String lockKey, String uuid, long expireTime) {
    // 获取锁
}
```

```java
@LockMethodListener(isGetLock = false)
public boolean unlock(String lockKey, String uuid) {
    // 释放锁
}
```



### 3.3 编写切面

前面虽说是已经完成了注解的编写，但无实质处理。下面针对使用了注解 `LockMethodListener` 的方法编写切面。下面代码需要用到 `LockListener.java` 锁监听类，后续 `3.4 锁监听类` 部分衔接。

> 创建切面类

```java
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
}
```



> 切面规则/表达式

```java
@Pointcut("@annotation(lockMethodListener)")
public void lockPointCut(LockMethodListener lockMethodListener) {
}
```



> 编写切入逻辑

一旦有线程调用了获取锁/释放锁方法后，就会执行该逻辑

```java
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

    // 如果没有获取到锁，直接返回，无需启动/关闭 看门狗
    if (!resultValue) {
        return;
    }

    // 锁(获取)
    if (isGetLock) {
        long expireTime = Long.parseLong(String.valueOf(args[2]));
        if (expireTime <= 0) {      // 若是执行加锁操作时，锁的过期时间设置为0，那么不用启动 看门狗
            return;
        }
        witch(lockKey, uuid, expireTime);
        return;
    }

    // 锁(释放)
    unWitch(lockKey, uuid);
}
```



### 3.4 监听锁续期方法

> 看门狗

```java
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
```



> 关闭看门狗

```java
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
```



> 切面整体代码如下：
>
> 1. 锁每隔半个过期时间，就会续期一次
> 2. 每次续期后，过期时间恢复为最初的过期时间

```java
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
```

到这里，Java 实现 redis 分布式锁已经完成。



## 4. 测试案例

```java
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
```



执行demo()方法，结果如下：

```
线程：7d87528f-8da9-40f5-a32b-7d3cc10fbb17，获取锁：LOCK_KEY，过期时间：1000
线程：7d87528f-8da9-40f5-a32b-7d3cc10fbb17，持有锁：LOCK_KEY，进行续期：1000
线程：7d87528f-8da9-40f5-a32b-7d3cc10fbb17，持有锁：LOCK_KEY，进行续期：1000
线程：7d87528f-8da9-40f5-a32b-7d3cc10fbb17，持有锁：LOCK_KEY，进行续期：1000
线程：7d87528f-8da9-40f5-a32b-7d3cc10fbb17，持有锁：LOCK_KEY，进行续期：1000
线程：7d87528f-8da9-40f5-a32b-7d3cc10fbb17，释放锁：LOCK_KEY
线程：63a22f14-d504-4098-82dc-cd893e87acac，获取锁：LOCK_KEY，过期时间：1000
线程：63a22f14-d504-4098-82dc-cd893e87acac，持有锁：LOCK_KEY，进行续期：1000
线程：63a22f14-d504-4098-82dc-cd893e87acac，持有锁：LOCK_KEY，进行续期：1000
线程：63a22f14-d504-4098-82dc-cd893e87acac，持有锁：LOCK_KEY，进行续期：1000
线程：63a22f14-d504-4098-82dc-cd893e87acac，释放锁：LOCK_KEY
线程：39bb17d2-cd09-4336-9c6a-219484fc709f，获取锁：LOCK_KEY，过期时间：1000
线程：39bb17d2-cd09-4336-9c6a-219484fc709f，持有锁：LOCK_KEY，进行续期：1000
线程：39bb17d2-cd09-4336-9c6a-219484fc709f，持有锁：LOCK_KEY，进行续期：1000
线程：39bb17d2-cd09-4336-9c6a-219484fc709f，持有锁：LOCK_KEY，进行续期：1000
线程：39bb17d2-cd09-4336-9c6a-219484fc709f，持有锁：LOCK_KEY，进行续期：1000
线程：39bb17d2-cd09-4336-9c6a-219484fc709f，释放锁：LOCK_KEY
线程：fb209186-18f8-490c-ae5f-c1939b77d3a5，获取锁：LOCK_KEY，过期时间：1000
线程：fb209186-18f8-490c-ae5f-c1939b77d3a5，持有锁：LOCK_KEY，进行续期：1000
线程：fb209186-18f8-490c-ae5f-c1939b77d3a5，持有锁：LOCK_KEY，进行续期：1000
线程：fb209186-18f8-490c-ae5f-c1939b77d3a5，持有锁：LOCK_KEY，进行续期：1000
线程：fb209186-18f8-490c-ae5f-c1939b77d3a5，持有锁：LOCK_KEY，进行续期：1000
线程：fb209186-18f8-490c-ae5f-c1939b77d3a5，释放锁：LOCK_KEY
线程：8370aba2-2d3c-4852-a8a0-17e6dfbb76cc，获取锁：LOCK_KEY，过期时间：1000
线程：8370aba2-2d3c-4852-a8a0-17e6dfbb76cc，持有锁：LOCK_KEY，进行续期：1000
线程：8370aba2-2d3c-4852-a8a0-17e6dfbb76cc，持有锁：LOCK_KEY，进行续期：1000
线程：8370aba2-2d3c-4852-a8a0-17e6dfbb76cc，持有锁：LOCK_KEY，进行续期：1000
线程：8370aba2-2d3c-4852-a8a0-17e6dfbb76cc，释放锁：LOCK_KEY
```

