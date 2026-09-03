package com.an.feige.job;

import com.an.feige.common.RedisLockService;
import com.an.feige.feige.service.FeigePayService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.util.List;

/**
 * 订单超时自动取消任务（V12-5）：释放 CREATED 残留单对槽位/角色的占位。
 *
 * <p>场景：用户下单后未支付且未再下单（放弃购买/前端异常），CREATED 单会一直占用
 * 同槽位/同角色的下单资格；本任务每分钟扫描创建超过 {@code feige.pay.order-expire-minutes}
 * （默认 15 分钟）的 CREATED 单置 CANCELLED，保证之后用户能正常下单。</p>
 *
 * <p>多实例通过 Redis 锁互斥（同 FeigeArrivalJob/FeigeXPayQueryJob 模式）。</p>
 */
@DisallowConcurrentExecution
public class FeigeOrderExpireJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(FeigeOrderExpireJob.class);

    static final String LOCK_NAME = "orderExpireScan";

    @Resource
    private FeigePayService feigePayService;
    @Resource
    private RedisLockService redisLockService;
    @Value("${feige.lock.enabled:false}")
    private boolean lockEnabled;
    @Value("${feige.lock.ttl-seconds:30}")
    private long lockTtlSeconds;
    /** 订单超时分钟数（V12-5；下单未支付超时自动取消，默认 15 分钟）。 */
    @Value("${feige.pay.order-expire-minutes:15}")
    private int orderExpireMinutes;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            if (orderExpireMinutes <= 0) {
                return;
            }
            redisLockService.runWithLock(LOCK_NAME, lockTtlSeconds, lockEnabled, () -> {
                List<String> cancelled = feigePayService.expireStaleOrders(orderExpireMinutes);
                if (!cancelled.isEmpty()) {
                    log.info("订单超时取消完成 count={}", cancelled.size());
                }
            });
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }
}
