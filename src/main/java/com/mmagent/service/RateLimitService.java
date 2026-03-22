package com.mmagent.service;

import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的会话并发控制服务
 *
 * <p>使用 Redisson 可重入锁保证同一 session 同一时刻只有一个进行中的对话请求。
 * waitTime=0 表示立即失败（等同于 SETNX 语义），leaseTime=300s 防止异常时锁永不释放。
 */
@Service
public class RateLimitService {

    private final RedissonReactiveClient redissonReactive;

    public RateLimitService(RedissonReactiveClient redissonReactive) {
        this.redissonReactive = redissonReactive;
    }

    /**
     * 尝试获取 session 锁（不等待，TTL=300s）
     *
     * @return true=获取成功，false=已被占用
     */
    public Mono<Boolean> tryAcquireSessionLock(String sessionId) {
        RLockReactive lock = redissonReactive.getLock("session:lock:" + sessionId);
        return lock.tryLock(0, 300, TimeUnit.SECONDS);
    }

    /**
     * 释放 session 锁（强制释放，无论持有者）
     */
    public Mono<Void> releaseSessionLock(String sessionId) {
        RLockReactive lock = redissonReactive.getLock("session:lock:" + sessionId);
        return lock.forceUnlock().then();
    }
}
