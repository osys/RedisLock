package com.osys.redislock.witchdog;

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