## 1. Redis 分布式锁说明

Java 实现 Redis 分布式锁案例已经上传到笔者的[GitHub](https://github.com/osys/RedisLock)，欢迎下载参考，如有错误希望大佬指正。

在大多数情况下，应该都是使用成熟的分布式锁框架，如 `Redisson`。这里只是根据 `Redisson` 部分源码思想进行的个人摸索，不宜在项目中使用。



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
@Component(value = "redisLock")
@DependsOn({"stringRedisTemplate", "redisTemplate", "redisConnectionFactory"})
public class RedisLock {

    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Qualifier(value = "stringRedisTemplate")
    public void setStringRedisTemplate(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

}
```



### 2.1 获取锁

```java
/**
 * 获取锁，可重入锁
 * @param lockKey 锁标识  KEY[1]
 * @param uuid 线程标识 ARGV[1]
 * @param expireTime 锁过期时间 ARGV[2]
 * @return 是否获取成功
 */
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
```



### 2.2 释放锁

```java
/**
 * 释放锁，可重入锁
 * @param lockKey 锁标识 KEY[1]
 * @param uuid 线程标识 ARGV[1]
 * @return 是否释放成功
 */
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
```



### 2.3 获取重入次数

```java
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
```



### 2.4 锁续期

```java
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
    // 获取锁、释放锁
    String name() default "";
}
```



### 3.2 使用注解

在获取锁方法【`tryGetReentrantLock`】、释放锁方法【`tryReleaseReentrantLock`】上使用该注解：

```java
@LockMethodListener(name = "tryGetReentrantLock")
public boolean tryGetReentrantLock(String lockKey, String uuid, long expireTime) {
    // 获取锁
}
```

```java
@LockMethodListener(name = "tryReleaseReentrantLock")
public boolean tryReleaseReentrantLock(String lockKey, String uuid) {
    // 释放锁
}
```



### 3.3 编写切面

前面虽说是已经完成了注解的编写，但无实质处理。下面针对使用了注解 `LockMethodListener` 的方法编写切面。下面代码需要用到 `LockListener.java` 锁监听类，后续 `3.4 锁监听类` 部分衔接。

> 创建切面类

```java
@Aspect
@Component(value = "lockMethodListenerAspect")
public class LockMethodListenerAspect {

    // 锁监听类
    private LockListener lockListener;

    @Autowired
    @Qualifier(value = "lockListener")
    public void setLockListener(LockListener lockListener) {
        this.lockListener = lockListener;
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
@AfterReturning(returning = "resultValue", value = "lockPointCut(lockMethodListener)")
public void afterMethodListener(JoinPoint joinPoint, LockMethodListener lockMethodListener, Boolean resultValue) {
    // 参数获取
    Object[] args = joinPoint.getArgs();
    String name = lockMethodListener.name();
    String lockKey = String.valueOf(args[0]);
    String uuid = String.valueOf(args[1]);

    // 重入锁(获取)
    if ("tryGetReentrantLock".equals(name) && resultValue) {
        long expireTime = Long.parseLong(String.valueOf(args[2]));
        if (expireTime <= 0) {
            return;
        }
        // 锁监听，锁定时续期任务
        this.lockListener.witchDog(lockKey, uuid, expireTime);
        return;
    }

    // 重入锁(释放)
    if ("tryReleaseReentrantLock".equals(name) && resultValue) {
        // 锁监听，释放锁后，取消锁定时续期任务
        this.lockListener.cancelWitch(lockKey, uuid);
    }
}
```



### 3.4 锁监听类

> 创建锁监听类

```java
@Component(value = "lockListener")
public class LockListener {

    /** 
     * 保存的是线程重入锁的信息
     * key - 线程(锁标识+线程标识)
     * value - 重入信息
     */
    private static final ConcurrentHashMap<String, ReentrantInfo> FUTURE_MAP = new ConcurrentHashMap<>();

    private RedisLock redisLock;

    @Autowired
    @Qualifier(value = "redisLock")
    public void setRedisLock(RedisLock redisLock) {
        this.redisLock = redisLock;
    }

    /** Executor */
    private static final ScheduledThreadPoolExecutor SCHEDULED_THREAD_POOL_EXECUTOR = new ScheduledThreadPoolExecutor(30);

    static {
        // 设置取消任务应在取消时立即从工作队列中删除的策略
        SCHEDULED_THREAD_POOL_EXECUTOR.setRemoveOnCancelPolicy(true);
    }

    /** 重入锁的信息保存实体类 */
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
```



> 看门狗

这里使用了 `LockAsync.java`，为锁续期的主要逻辑，在后续 `3.5 锁续期` 部分衔接。

```java
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
```



> 取消看门狗

```java
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
```



### 3.5 锁续期

```java
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
```

到这里，Java 实现 redis 锁已经完成。



## 4. 测试案例

Java 实现 Redis 分布式锁案例已经上传到笔者的[GitHub](https://github.com/osys/RedisLock)，完整代码可下载参考。测试案例不看也没啥，主要还是锁的实现思想。代码写得并不优美，大佬们凑合凑合。

### 4.1 执行效果

锁过期时长：`300ms`

任务执行时长：`1000ms`

* 一个线程获取释放锁(重入锁)：http://localhost:6666/oneThreadGetAndReleaseReentrantLock
* 多个线程获取释放锁(重入锁)：http://localhost:6666/multipleThreadGetAndReleaseReentrantLock

先看多个线程获取释放锁执行效果：

请求：

```http request
POST http://localhost:6666/multipleThreadGetAndReleaseReentrantLock
Content-Type: application/json;charset=UTF-8

{
  "reentrant": 3,
  "threadNum": 3
}
```

reentrant 每个线程重入次数 3 次，threadNum 竞争同一把锁的线程数 3 个。

控制台输出结果：看守物品数 = 重入次数

```
【优质看门狗: ReleaseLockceafe4b1-ad18-4b83-97bd-2a5192e45450】【看守物品数(+1): 1个】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【优质看门狗: ReleaseLockceafe4b1-ad18-4b83-97bd-2a5192e45450】【看守物品数(+1): 2个】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【优质看门狗: ReleaseLockceafe4b1-ad18-4b83-97bd-2a5192e45450】【看守物品数(+1): 3个】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【优质看门狗: ReleaseLockceafe4b1-ad18-4b83-97bd-2a5192e45450】【看守物品数(-1): 2个】
【锁标识：ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【线程还在执行，重置过期时间：300 ms】【重置成功】
【优质看门狗: ReleaseLockceafe4b1-ad18-4b83-97bd-2a5192e45450】【看守物品数(-1): 1个】
【优质看门狗: ReleaseLockceafe4b1-ad18-4b83-97bd-2a5192e45450】【看守物品数(-1): 0个】 ------------ 看门狗死掉！


......


------------------------------------ 操作结果 ------------------------------------ 
【获取或释放】【锁标识: ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【获取锁时间：2022-12-15 18:06:50.0090】【获取锁：true】【释放锁时间：2022-12-15 18:06:53.0104】【释放锁：true】【重入次数：1】
【获取或释放】【锁标识: ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【获取锁时间：2022-12-15 18:06:51.0097】【获取锁：true】【释放锁时间：2022-12-15 18:06:53.0104】【释放锁：true】【重入次数：2】
【获取或释放】【锁标识: ReleaseLock】【线程标识：ceafe4b1-ad18-4b83-97bd-2a5192e45450】【获取锁时间：2022-12-15 18:06:52.0102】【获取锁：true】【释放锁时间：2022-12-15 18:06:53.0104】【释放锁：true】【重入次数：3】
【获取或释放】【锁标识: ReleaseLock】【线程标识：a92b703d-06e0-4b9a-b914-644fa968c571】【获取锁时间：2022-12-15 18:06:53.0105】【获取锁：true】【释放锁时间：2022-12-15 18:06:56.0144】【释放锁：true】【重入次数：1】
【获取或释放】【锁标识: ReleaseLock】【线程标识：a92b703d-06e0-4b9a-b914-644fa968c571】【获取锁时间：2022-12-15 18:06:54.0115】【获取锁：true】【释放锁时间：2022-12-15 18:06:56.0144】【释放锁：true】【重入次数：2】
【获取或释放】【锁标识: ReleaseLock】【线程标识：a92b703d-06e0-4b9a-b914-644fa968c571】【获取锁时间：2022-12-15 18:06:55.0130】【获取锁：true】【释放锁时间：2022-12-15 18:06:56.0144】【释放锁：true】【重入次数：3】
【获取或释放】【锁标识: ReleaseLock】【线程标识：acb6fcbe-8dbc-49bc-a4ce-f98cbbaa6312】【获取锁时间：2022-12-15 18:06:56.0144】【获取锁：true】【释放锁时间：2022-12-15 18:06:59.0175】【释放锁：true】【重入次数：1】
【获取或释放】【锁标识: ReleaseLock】【线程标识：acb6fcbe-8dbc-49bc-a4ce-f98cbbaa6312】【获取锁时间：2022-12-15 18:06:57.0157】【获取锁：true】【释放锁时间：2022-12-15 18:06:59.0174】【释放锁：true】【重入次数：2】
【获取或释放】【锁标识: ReleaseLock】【线程标识：acb6fcbe-8dbc-49bc-a4ce-f98cbbaa6312】【获取锁时间：2022-12-15 18:06:58.0159】【获取锁：true】【释放锁时间：2022-12-15 18:06:59.0174】【释放锁：true】【重入次数：3】
```



### 4.2 案例实现

忽略的代码，可从笔者的[GitHub](https://github.com/osys/RedisLock)下载完整案例。

> controller

```java
@RestController(value = "demoController")
@RequestMapping(path = "/")
public class DemoController {

    private DemoService demoService;

    @Autowired
    @Qualifier(value = "demoService")
    public void setDemoService(DemoService demoService) {
        this.demoService = demoService;
    }

    /** 一个线程，获取+释放redis锁（不存在锁竞争） */
    @RequestMapping(path = {"/oneThreadGetAndReleaseReentrantLock"}, method = {RequestMethod.POST})
    public String oneThreadGetAndReleaseReentrantLock(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ExecutionException, InterruptedException {
        // 获取参数
        JsonObject requestParams = getParams(request);
        return demoService.oneThreadGetAndReleaseReentrantLock(requestParams, true).toString();
    }

    /** 多个线程，获取+释放redis锁（存在锁竞争） */
    @RequestMapping(path = {"/multipleThreadGetAndReleaseReentrantLock"}, method = {RequestMethod.POST})
    public String multipleThreadGetAndReleaseReentrantLock(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ExecutionException, InterruptedException {
        // 获取参数
        JsonObject requestParams = getParams(request);
        return demoService.multipleThreadGetAndReleaseReentrantLock(requestParams, true).toString();
    }

    /** 获取参数 */
    public JsonObject getParams(HttpServletRequest request) throws IOException {
        // ......
    }
}
```



> service

service 中使用的 `ReentrantLockTask.java` 的实现不做多说明，可从笔者的 GitHub 上下载参考。

```java
@Service(value = "demoService")
public class DemoService {

    private RedisLock redisLock;

    /** 锁过期时间 */
    private static long expireTime = 300;

    /** 任务执行时间 */
    private static long taskTime = 1000;

    /** 执行任务的 Executor */
    private static final ExecutorService EXECUTOR_SERVICE = new ThreadPoolExecutor(
            100, 500, 60 * 1000L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new ThreadPoolExecutorFactoryBean()
    );

    @Autowired
    @Qualifier(value = "redisLock")
    public void setRedisLock(RedisLock redisLock) {
        this.redisLock = redisLock;
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
        // 重入次数 reentrant次
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
        // 获取锁的日志输出
    }
}
```



> ReentrantLockTask.java

```java
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

        // getter、setter ...
    }
}
```

案例到这里就已经实现了。