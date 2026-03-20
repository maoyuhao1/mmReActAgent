package com.mmagent.service;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 基于 Redis 的会话并发控制服务
 *
 * <p>使用 SETNX 实现同一 session 在同一时刻只允许一个进行中的对话请求。
 */
@Service
public class RateLimitService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public RateLimitService(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取 session 锁（SETNX，TTL=300s）
     *
     * @return true=获取成功，false=已被占用
     */
    public Mono<Boolean> tryAcquireSessionLock(String sessionId) {
        return redisTemplate.opsForValue()
                .setIfAbsent("session:lock:" + sessionId, "1", Duration.ofSeconds(300));
    }

    /**
     * 释放 session 锁
     */
    public Mono<Void> releaseSessionLock(String sessionId) {
        return redisTemplate.delete("session:lock:" + sessionId).then();
    }
}
