package com.an.feige.job;

import com.an.feige.feige.service.FeigeLifecycleService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import javax.annotation.Resource;

/**
 * 未认领过期扫描：把超过 72h 认领截止的 FLYING_UNCLAIMED 信件置为 UNCLAIMED_EXPIRED 并释放鸽子。
 */
@DisallowConcurrentExecution
public class FeigeUnclaimedExpireJob implements Job {

    @Resource
    private FeigeLifecycleService feigeLifecycleService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            for (String letterId : feigeLifecycleService.selectDueExpire()) {
                feigeLifecycleService.expireUnclaimed(letterId);
            }
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }
}
