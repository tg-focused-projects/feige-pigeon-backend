package com.an.feige.job;

import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Component;

import java.util.TimeZone;

/**
 * 飞鸽传书定时任务注册器（仿 search-admin 内容洞察热点追踪的做法，走 Quartz + SchedulerFactoryBean）。
 *
 * <p>在 Spring 根上下文就绪后动态注册两张扫描任务：抵达扫描、未认领过期扫描。
 * 每 1 分钟执行一次；使用独立时间组，重启后重新注册（RAMJobStore）。</p>
 */
@Component
public class FeigeJobRegistrar implements ApplicationListener<ContextRefreshedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(FeigeJobRegistrar.class);
    private static final String GROUP = "feige";
    private static final String SCAN_CRON = "0 */1 * * * ?";

    @Autowired
    private SchedulerFactoryBean schedulerFactoryBean;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // root 与 DispatcherServlet 两个上下文都扫描本包时，只让 root 上下文（无 parent）注册，
        // 避免双实例重复注册/重复 tick。
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        try {
            Scheduler scheduler = schedulerFactoryBean.getScheduler();
            register(scheduler, "feigeArrivalScan", FeigeArrivalJob.class,
                    SCAN_CRON, "feigeArrivalScanTrigger");
            register(scheduler, "feigeUnclaimedExpireScan", FeigeUnclaimedExpireJob.class,
                    SCAN_CRON, "feigeUnclaimedExpireScanTrigger");
            if (!scheduler.isStarted()) {
                scheduler.start();
            }
            logger.info("飞鸽传书定时任务已注册：arrivalScan / unclaimedExpireScan，cron={}", SCAN_CRON);
        } catch (Exception e) {
            logger.error("飞鸽传书定时任务注册失败", e);
        }
    }

    private void register(Scheduler scheduler, String jobName, Class<? extends Job> jobClass,
                          String cron, String triggerName) throws SchedulerException {
        JobKey jobKey = new JobKey(jobName, GROUP);
        TriggerKey triggerKey = new TriggerKey(triggerName, GROUP);
        CronScheduleBuilder schedule = CronScheduleBuilder.cronSchedule(cron)
                .inTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                .withMisfireHandlingInstructionDoNothing();
        if (scheduler.checkExists(jobKey)) {
            CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey).forJob(jobKey)
                    .withSchedule(schedule).build();
            if (scheduler.checkExists(triggerKey)) {
                scheduler.rescheduleJob(triggerKey, trigger);
            } else {
                scheduler.scheduleJob(trigger);
            }
        } else {
            JobDetail job = JobBuilder.newJob(jobClass).withIdentity(jobKey).build();
            CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity(triggerKey)
                    .withSchedule(schedule).build();
            scheduler.scheduleJob(job, trigger);
        }
    }
}
