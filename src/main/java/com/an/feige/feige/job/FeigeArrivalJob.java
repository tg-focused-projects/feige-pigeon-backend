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
 * 抵达扫描：把已到抵达时间的 IN_FLIGHT 信件推进为 ARRIVED，并幂等结算成长、释放鸽子。
 *
 * <p>多实例部署时通过 Redis 分布式锁互斥（{@code feige.lock.enabled} 开关控制），
 * 同一轮触发只有一个实例真正扫描。</p>
 */
@DisallowConcurrentExecution
public class FeigeArrivalJob implements Job {

    static final String LOCK_NAME = "arrivalScan";

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
                for (String letterId : feigeLifecycleService.selectDueArrival()) {
                    feigeLifecycleService.advanceToArrived(letterId);
                }
            });
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }
}
