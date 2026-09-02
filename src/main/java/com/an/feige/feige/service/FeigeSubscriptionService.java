package com.an.feige.feige.service;

import com.an.feige.common.WeChatClient;
import com.an.feige.feige.entity.FeigeLetter;
import com.an.feige.feige.entity.FeigeSubscription;
import com.an.feige.feige.mapper.FeigeLetterMapper;
import com.an.feige.feige.mapper.FeigeSubscriptionMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞鸽传书-通知订阅服务（规格13.1/13.2/13.3）。
 *
 * <p>订阅按「信件+用户+类型」独立记录（feige_subscription 表），
 * 取代信件级单字段 subscribed 代表双方的做法。
 * 两类订阅：ARRIVAL（当前鸽子抵达，发件人/收件人分别授权）、
 * REPLY_ARRIVAL（回信抵达，原发件人订阅）。推送由到达推进路径调用，
 * notified 独立幂等，模板未配置时静默跳过（不阻塞业务）。</p>
 */
@Service
public class FeigeSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(FeigeSubscriptionService.class);
    /** 微信订阅消息 time 字段格式（不带秒）。 */
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm";

    @Resource
    private FeigeSubscriptionMapper feigeSubscriptionMapper;

    @Resource
    private FeigeLetterMapper feigeLetterMapper;

    @Resource
    private WeChatClient weChatClient;

    @Value("${feige.wechat.arrival-template-id:}")
    private String arrivalTemplateId;

    @Value("${feige.wechat.reply-arrival-template-id:}")
    private String replyArrivalTemplateId;

    /** 幂等订阅（同信+同人+同类型重复订阅仅刷新时间）。 */
    public boolean subscribe(String letterId, String openid, String type) {
        if (StringUtils.isBlank(letterId) || StringUtils.isBlank(openid) || StringUtils.isBlank(type)) {
            return false;
        }
        if (!FeigeSubscription.TYPE_ARRIVAL.equals(type)
                && !FeigeSubscription.TYPE_REPLY_ARRIVAL.equals(type)) {
            return false;
        }
        FeigeLetter letter = feigeLetterMapper.selectByLetterId(letterId);
        if (letter == null) {
            return false;
        }
        // 已抵达/已拆信的信件无需订阅（到达通知只在飞行中生效）；
        // 仅 ARRIVAL 受此限制——REPLY_ARRIVAL 的原信此时通常已 DELIVERED（拆信后才可回信/订阅）
        if (!FeigeSubscription.TYPE_REPLY_ARRIVAL.equals(type)
                && (FeigeLetter.STATUS_ARRIVED.equals(letter.getStatus())
                    || FeigeLetter.STATUS_DELIVERED.equals(letter.getStatus()))) {
            return false;
        }
        feigeSubscriptionMapper.upsert(letterId, openid, type, new Date());
        return true;
    }

    /** 查询某信件某用户的订阅类型集合（飞行页展示 subscribedArrival/subscribedReplyArrival）。 */
    public List<String> typesOf(String letterId, String openid) {
        List<String> types = new ArrayList<>();
        for (FeigeSubscription sub : feigeSubscriptionMapper.selectByLetterAndUser(letterId, openid)) {
            types.add(sub.getType());
        }
        return types;
    }

    /**
     * 到达推送（抵达推进时调用）：推送给该信件 ARRIVAL 类型且未推送的订阅者。
     * 幂等：每个订阅 notified=0→1 才真正推送；模板未配置时静默跳过。
     * 文案按订阅者角色区分（模板字段：thing1 昵称 / time2 时间 / thing3 通知事项 / thing4 温馨提醒）：
     * 发件人「小白已经抵达收信城市·信已经送到」；收件人「一封给你的信已经抵达·小白正在等你」。
     */
    public void pushArrival(FeigeLetter letter) {
        push(letter, FeigeSubscription.TYPE_ARRIVAL, arrivalTemplateId,
                "pages/feige/letter?id=" + letter.getLetterId(), (sub) -> {
                    boolean isSender = letter.getSenderOpenid() != null
                            && letter.getSenderOpenid().equals(sub.getOpenid());
                    Map<String, Object> data = new HashMap<>();
                    String pigeonName = StringUtils.defaultString(letter.getPigeonName(), "信鸽");
                    data.put("thing1", valueOf(pigeonName));
                    data.put("time2", valueOf(formatDate(letter.getArrivalTime())));
                    if (isSender) {
                        // 发件人（寄出方）：「小白已经抵达收信城市」「信已经送到，可以查看这次旅程了」
                        data.put("thing3", valueOf(pigeonName + "已经抵达收信城市"));
                        data.put("thing4", valueOf("信已经送到，可以查看这次旅程了"));
                    } else {
                        // 收件人（收信方）：「一封给你的信已经抵达」「小白正在等你，回来接过这封信吧」
                        data.put("thing3", valueOf("一封给你的信已经抵达"));
                        data.put("thing4", valueOf(pigeonName + "正在等你，回来接过这封信吧"));
                    }
                    return data;
                });
    }

    /**
     * 回信到达推送（回信抵达推进时调用）：推送给该信件 REPLY_ARRIVAL 类型且未推送的订阅者。
     * 幂等：notified=0→1 才推送；模板未配置时静默跳过。
     */
    public void pushReplyArrival(FeigeLetter letter) {
        // 回信抵达：订阅挂在原信（reply_to_letter_id）上（规格13.2：原发件人「有回信时告诉我」）
        FeigeLetter origin = letter.getReplyToLetterId() == null ? letter
                : feigeLetterMapper.selectByLetterId(letter.getReplyToLetterId());
        if (origin == null) {
            return;
        }
        // 模板字段：thing1 昵称 / time2 时间 / thing3 通知事项 / thing4 温馨提醒
        push(origin, FeigeSubscription.TYPE_REPLY_ARRIVAL, replyArrivalTemplateId,
                "pages/feige/letter?id=" + letter.getLetterId(), (sub) -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("thing1", valueOf(StringUtils.defaultString(letter.getPigeonName(), "信鸽")));
                    data.put("time2", valueOf(formatDate(letter.getArrivalTime())));
                    data.put("thing3", valueOf("有人给你回了一封信"));
                    data.put("thing4", valueOf("你的信鸽带着回信抵达了，来看看吧"));
                    return data;
                });
    }

    private void push(FeigeLetter letter, String type, String templateId, String page,
                      MessageBuilder messageBuilder) {
        // 模板未配置（上线前）：跳过推送但推进 notified，避免留下永不推送的僵尸订阅；
        // 模板接通后，对之后新抵达的信件正常推送。
        if (StringUtils.isBlank(templateId)) {
            for (FeigeSubscription sub : feigeSubscriptionMapper.selectPendingByLetterAndType(letter.getLetterId(), type)) {
                feigeSubscriptionMapper.markNotified(sub.getId(), new Date(), new Date());
            }
            return;
        }
        for (FeigeSubscription sub : feigeSubscriptionMapper.selectPendingByLetterAndType(letter.getLetterId(), type)) {
            if (feigeSubscriptionMapper.markNotified(sub.getId(), new Date(), new Date()) <= 0) {
                continue;
            }
            Map<String, Object> data = messageBuilder.build(sub);
            boolean ok = weChatClient.pushSubscribeMessage(sub.getOpenid(), page, data, templateId);
            log.info("订阅推送 type={} openid={} letterId={} ok={}", type, sub.getOpenid(),
                    letter.getLetterId(), ok);
        }
    }

    /** 按订阅者生成推送模板数据（字段：thing1/time2/thing3/thing4）。 */
    private interface MessageBuilder {
        Map<String, Object> build(FeigeSubscription sub);
    }

    private String formatDate(Date date) {
        return date == null ? "" : new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    private Map<String, Object> valueOf(String value) {
        Map<String, Object> item = new HashMap<>();
        item.put("value", value);
        return item;
    }
}