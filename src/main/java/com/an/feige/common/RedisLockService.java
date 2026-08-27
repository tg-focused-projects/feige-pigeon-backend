package com.an.feige.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 轻量 Redis 分布式锁：SET NX PX 抢占 + Lua「比对值再删除」安全释放。
 *
 * <p>用途：多实例部署时保证 Quartz 扫描任务同一轮只有一个实例真正执行；
 * 实例崩溃由 TTL 自动过期兜底（TTL 应大于任务最长耗时、小于调度周期）。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>总开关关闭({@code feige.lock.enabled=false})时行为与单实例时代完全一致；</li>
 *   <li>开关开启但 Redis 不可达时<strong>跳过本轮</strong>而非照常执行——宁可延迟一分钟，
 *       绝不重复处理（业务幂等虽在，重复推送体验仍差）。</li>
 * </ul>
 */
@Component
public class RedisLockService {

    private static final Logger logger = LoggerFactory.getLogger(RedisLockService.class);
    private static final String KEY_PREFIX = "feige:lock:";

    /** 只有值等于自己的 requestId 才允许删除，防止误删已超时易主的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
          + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 受锁保护的任务体 */
    @FunctionalInterface
    public interface JobTask {
        void run();
    }

    /**
     * 尝试获取分布式锁并执行 task。
     *
     * @return true=本实例执行了任务；false=被其他实例持锁或 Redis 异常而跳过
     */
    public boolean runWithLock(String name, long ttlSeconds, boolean enabled, JobTask task) {
        if (!enabled) {
            return safeRun(name, task);
        }
        String key = KEY_PREFIX + name;
        String requestId = UUID.randomUUID().toString();

        Boolean acquired;
        try {
            acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, requestId, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("[分布式锁] Redis不可达, 任务[{}]本轮跳过执行(防重复), {}s后下一轮重试",
                    name, ttlSeconds, e);
            return false;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            logger.info("[分布式锁] 任务[{}]已被其他实例持锁, 本轮跳过", name);
            return false;
        }

        try {
            logger.debug("[分布式锁] 获得[{}] ttl={}s requestId={}", name, ttlSeconds, requestId);
            return safeRun(name, task);
        } finally {
            try {
                Long released = stringRedisTemplate.execute(
                        UNLOCK_SCRIPT, Collections.singletonList(key), requestId);
                if (released == null || released != 1L) {
                    logger.warn("[分布式锁] [{}]释放时锁已过期易主(requestId不匹配), 忽略", name);
                }
            } catch (Exception e) {
                logger.warn("[分布式锁] [{}]释放异常(将由TTL兜底过期)", name, e);
            }
        }
    }

    private boolean safeRun(String name, JobTask task) {
        try {
            task.run();
            return true;
        } catch (RuntimeException e) {
            logger.error("[分布式锁] 任务[{}]执行失败", name, e);
            throw e;
        }
    }
}
