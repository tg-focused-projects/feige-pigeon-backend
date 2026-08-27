package com.an.feige.job;

import com.an.feige.common.RedisLockService;
import com.an.feige.feige.service.FeigeLifecycleService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;

/**
 * 未认领过期扫描：把超过 72h 认领截止的 FLYING_UNCLAIMED 信件置为 UNCLAIMED_EXPIRED 并释放鸽子。
 *
 * <p>多实例部署时通过 Redis 分布式锁互斥（{@code feige.lock.enabled} 开关控制），
 * 同一轮触发只有一个实例真正扫描。</p>
 */
@DisallowConcurrentExecution
public class FeigeUnclaimedExpireJob implements Job {

    static final String LOCK_NAME = "unclaimedExpireScan";

    @Resource
    private FeigeLifecycleService feigeLifecycleService;
    @Resource
    private RedisLockService redisLockService;
    @Value("${feige.lock.enabled:false}")
    private boolean lockEnabled;
    @Value("${feige.lock.ttl-seconds:30}")
    private long lockTtlSeconds;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            redisLockService.runWithLock(LOCK_NAME, lockTtlSeconds, lockEnabled, () -> {
                for (String letterId : feigeLifecycleService.selectDueExpire()) {
                    feigeLifecycleService.expireUnclaimed(letterId);
                }
            });
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }
}
