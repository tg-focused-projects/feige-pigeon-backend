package com.an.feige.job;

import com.alibaba.fastjson.JSONObject;
import com.an.feige.common.RedisLockService;
import com.an.feige.common.WxXPayClient;
import com.an.feige.feige.entity.FeigeOrder;
import com.an.feige.feige.mapper.FeigeOrderMapper;
import com.an.feige.feige.service.FeigePayService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * 虚拟支付「发货推送丢失」兜底查单任务。
 *
 * <p>支付成功以微信推送 xpay_goods_deliver_notify 为准，但推送可能丢失（用户异常退出等），
 * 官方建议定期调 /xpay/query_order 主动查单，查到已支付就补发货并上报 notify_provide_goods。</p>
 *
 * <p>扫描最近一段时间内仍 CREATED 的订单（避免对早已放弃的老单反复查微信），
 * 多实例通过 Redis 锁互斥（同 FeigeArrivalJob 模式）。</p>
 */
@DisallowConcurrentExecution
public class FeigeXPayQueryJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(FeigeXPayQueryJob.class);

    static final String LOCK_NAME = "xpayQueryScan";
    /** 只查最近 10 分钟内下单仍未支付的订单（微信支付流程一般 <2min；防老单反复查）。 */
    private static final long SCAN_RECENT_MS = 10 * 60 * 1000L;

    @Resource
    private FeigePayService feigePayService;
    @Resource
    private FeigeOrderMapper feigeOrderMapper;
    @Resource
    private WxXPayClient wxXPayClient;
    @Resource
    private RedisLockService redisLockService;
    @Value("${feige.lock.enabled:false}")
    private boolean lockEnabled;
    @Value("${feige.lock.ttl-seconds:30}")
    private long lockTtlSeconds;
    @Value("${feige.pay.query-poll-enabled:false}")
    private boolean queryPollEnabled;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            if (!queryPollEnabled) {
                return;
            }
            redisLockService.runWithLock(LOCK_NAME, lockTtlSeconds, lockEnabled, () -> {
                Date now = new Date();
                List<FeigeOrder> pending = feigeOrderMapper.selectCreatedBetween(
                        new Date(now.getTime() - SCAN_RECENT_MS), now);
                for (FeigeOrder order : pending) {
                    JSONObject resp = wxXPayClient.queryOrder(order.getOpenid(), order.getOrderNo());
                    if (resp == null) {
                        continue;
                    }
                    JSONObject orderJson = resp.getJSONObject("order");
                    if (orderJson == null) {
                        // 平台查无此单（尚未支付/未同步），跳过
                        log.info("[xpay-query] 查单无结果 orderNo={} resp={}", order.getOrderNo(), resp.toJSONString());
                        continue;
                    }
                    int status = orderJson.getIntValue("status");
                    if (status == 2 || status == 3 || status == 4) {
                        String wxOrderId = orderJson.getString("wxpay_order_id");
                        log.info("[xpay-query] 查单发现已支付 orderNo={} status={} wxOrderId={}",
                                order.getOrderNo(), status, wxOrderId);
                        feigePayService.confirmFromXPayNotify(order.getOrderNo(), wxOrderId);
                    }
                }
            });
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }
}
