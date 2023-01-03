package com.osys.redislock.witchdog.aspect;

import com.osys.redislock.witchdog.task.LockListener;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


/**
 * <p><b>{@link LockMethodListenerAspect} Description</b>:
 * </p>
 *
 * @author osys
 */
@Aspect
@Component(value = "lockMethodListenerAspect")
public class LockMethodListenerAspect {

    @Resource(name = "lockListener")
    private LockListener lockListener;

    @Pointcut("@annotation(lockMethodListener)")
    public void lockPointCut(LockMethodListener lockMethodListener) {
    }

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
            this.lockListener.witchDog(lockKey, uuid, expireTime);
            return;
        }

        // 重入锁(释放)
        if ("tryReleaseReentrantLock".equals(name) && resultValue) {
            this.lockListener.cancelWitch(lockKey, uuid);
        }
    }
}