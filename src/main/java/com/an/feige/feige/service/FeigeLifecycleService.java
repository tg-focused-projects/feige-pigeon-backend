package com.an.feige.feige.service;

import com.an.feige.common.WeChatClient;
import com.an.feige.feige.mapper.FeigeLetterMapper;
import com.an.feige.feige.entity.FeigeLetter;
import com.an.feige.feige.entity.FeigePigeon;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞鸽传书-信件生命周期服务（定时任务与请求路径共用）。
 *
 * <p>负责两个幂等推进：抵达结算（IN_FLIGHT→ARRIVED + 释放鸽子 + 结算成长 + 通知）与
 * 未认领过期（FLYING_UNCLAIMED→UNCLAIMED_EXPIRED + 释放鸽子）。
 * 关键：必须与 {@link FeigeLetterMapper#markArrivedAndSettle} 的 settled=0 条件配合，
 * 保证并发扫描/查询只会结算一次成长。</p>
 */
@Service
public class FeigeLifecycleService {

    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Resource
    private FeigeLetterMapper feigeLetterMapper;

    @Resource
    private WeChatClient weChatClient;

    @Autowired
    private FeigePigeonService feigePigeonService;

    /** 抵达扫描：返回所有已到抵达时间的 IN_FLIGHT 信件 letterId。 */
    public List<String> selectDueArrival() {
        List<String> ids = new ArrayList<>();
        for (FeigeLetter letter : feigeLetterMapper.selectInFlightArrived()) {
            ids.add(letter.getLetterId());
        }
        return ids;
    }

    /** 未认领过期扫描：返回所有已过认领截止的 FLYING_UNCLAIMED 信件 letterId。 */
    public List<String> selectDueExpire() {
        List<String> ids = new ArrayList<>();
        for (FeigeLetter letter : feigeLetterMapper.selectFlyingUnclaimedExpired()) {
            ids.add(letter.getLetterId());
        }
        return ids;
    }

    /** 抵达推进 + 结算（幂等：settled=0 且已到抵达时间才执行）。 */
    @Transactional
    public boolean advanceToArrived(String letterId) {
        FeigeLetter letter = feigeLetterMapper.selectByLetterIdForUpdate(letterId);
        if (letter == null) {
            return false;
        }
        Integer settled = letter.getSettled();
        if (settled != null && settled == 1) {
            return false;
        }
        Date now = new Date();
        if (letter.getArrivalTime() == null || now.before(letter.getArrivalTime())) {
            return false;
        }
        String status = letter.getStatus();
        if (!FeigeLetter.STATUS_IN_FLIGHT.equals(status) && !FeigeLetter.STATUS_ARRIVED.equals(status)) {
            return false;
        }

        int deltaExp = 0;
        int beforeLevel = 0;
        int afterLevel = 0;
        int levelUp = 0;
        FeigePigeon pigeon = feigePigeonService.getById(letter.getPigeonId());
        if (pigeon != null && letter.getDistanceKm() != null) {
            Map<String, Object> snap = feigePigeonService.recordDelivery(pigeon, letter.getDistanceKm());
            deltaExp = ((Number) snap.get("deltaExp")).intValue();
            beforeLevel = ((Number) snap.get("beforeLevel")).intValue();
            afterLevel = ((Number) snap.get("afterLevel")).intValue();
            levelUp = ((Number) snap.get("levelUp")).intValue();
        } else {
            // 极端情况：鸽子丢失仍推进信件，成长按 0 计。
            feigePigeonService.release(letter.getPigeonId());
        }

        feigeLetterMapper.markArrivedAndSettle(letterId, deltaExp, beforeLevel, afterLevel, levelUp,
                now, now, now);

        if (letter.getSubscribed() != null && letter.getSubscribed() == 1) {
            if (feigeLetterMapper.updateNotified(letterId, 1, new Date()) > 0) {
                sendArrivalNotify(letter);
            }
        }
        return true;
    }

    /** 未认领过期（幂等：仅 FLYING_UNCLAIMED）。 */
    @Transactional
    public boolean expireUnclaimed(String letterId) {
        FeigeLetter letter = feigeLetterMapper.selectByLetterIdForUpdate(letterId);
        if (letter == null || !FeigeLetter.STATUS_FLYING_UNCLAIMED.equals(letter.getStatus())) {
            return false;
        }
        Date now = new Date();
        feigeLetterMapper.markExpired(letterId, now, now);
        feigePigeonService.release(letter.getPigeonId());
        return true;
    }

    /** 小程序订阅消息：模板未配置时静默跳过，不阻塞业务。 */
    private void sendArrivalNotify(FeigeLetter letter) {
        Map<String, Object> data = new HashMap<>();
        data.put("thing1", valueOf("信鸽已抵达"));
        data.put("thing2", valueOf(letter.getPigeonName() + "把这封信送到了你身边"));
        data.put("time10", valueOf(formatDate(letter.getArrivalTime())));
        weChatClient.pushSubscribeMessage(letter.getRecipientOpenid(),
                "pages/feige/letter?id=" + letter.getLetterId(), data);
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
