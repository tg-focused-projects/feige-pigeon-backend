package com.an.feige.job;

import com.an.feige.feige.service.FeigeLifecycleService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import javax.annotation.Resource;

/**
 * 抵达扫描：把已到抵达时间的 IN_FLIGHT 信件推进为 ARRIVED，并幂等结算成长、释放鸽子。
 */
@DisallowConcurrentExecution
public class FeigeArrivalJob implements Job {

    @Resource
    private FeigeLifecycleService feigeLifecycleService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            for (String letterId : feigeLifecycleService.selectDueArrival()) {
                feigeLifecycleService.advanceToArrived(letterId);
            }
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }
}
