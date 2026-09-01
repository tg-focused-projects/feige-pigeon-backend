package com.an.feige.feige.service;

import com.an.feige.feige.mapper.FeigeLetterMapper;
import com.an.feige.feige.mapper.FeigePigeonMapper;
import com.an.feige.feige.entity.FeigeLetter;
import com.an.feige.feige.entity.FeigePigeon;
import com.an.feige.feige.entity.PigeonRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞鸽传书-用户鸽子服务。
 *
 * <p>V1.1 多鸽体系（规格14.3/15.1）：最多6只首发角色，统一177km/h，
 * 独立履历；首次进入自动获得免费「小白」（角色XIAOBAI），
 * 其余角色由鸽舍创建/选择获得（V1.2 起第2~6只为付费权益）。</p>
 */
@Service
public class FeigePigeonService {

    private static final Logger log = LoggerFactory.getLogger(FeigePigeonService.class);
    private static final BigDecimal DEFAULT_SPEED = new BigDecimal("177.00");
    /** 每用户最多6只（规格15.1）。 */
    private static final int MAX_PIGEONS = 6;

    @Resource
    private FeigePigeonMapper feigePigeonMapper;

    @Resource
    private FeigeLetterMapper feigeLetterMapper;
    @Value("${feige.pigeon.paid-enabled:false}")
    private boolean paidEnabled;

    /** 取用户鸽子（小白），不存在则初始化「小白」（规格3.2：首次进入自动获得）。 */
    public FeigePigeon getOrInitByOpenid(String openid) {
        FeigePigeon pigeon = feigePigeonMapper.selectByOpenidAndRole(openid, FeigePigeon.ROLE_XIAOBAI);
        if (pigeon != null) {
            return pigeon;
        }
        Date now = new Date();
        FeigePigeon fresh = new FeigePigeon();
        fresh.setOpenid(openid);
        fresh.setName(PigeonRole.defaultName(FeigePigeon.ROLE_XIAOBAI));
        fresh.setRoleKey(FeigePigeon.ROLE_XIAOBAI);
        fresh.setLevel(1);
        fresh.setExp(0);
        fresh.setSpeedKmh(DEFAULT_SPEED);
        fresh.setStamina(3);
        fresh.setDeliveredCount(0);
        fresh.setTotalMileage(BigDecimal.ZERO);
        fresh.setFarthestDistance(BigDecimal.ZERO);
        fresh.setStatus(FeigePigeon.STATUS_IDLE);
        fresh.setCreateAt(now);
        fresh.setUpdateAt(now);
        feigePigeonMapper.insertSelective(fresh);
        return fresh;
    }

    public FeigePigeon getByOpenid(String openid) {
        return feigePigeonMapper.selectByOpenid(openid);
    }

    public FeigePigeon getById(Long id) {
        return id == null ? null : feigePigeonMapper.selectByPrimaryKey(id);
    }

    /** 按角色查用户鸽子（V1.2 权益/重复购买判断）。 */
    public FeigePigeon getByOpenidAndRole(String openid, String roleKey) {
        return feigePigeonMapper.selectByOpenidAndRole(openid, roleKey);
    }

    /** 置鸽子为送信中；返回是否成功（空闲才能放飞）。 */
    public boolean markSending(Long id) {
        return feigePigeonMapper.markSending(id, new Date()) > 0;
    }

    /** 释放鸽子：置回空闲（抵达/召回/未认领过期时）。 */
    public void release(Long id) {
        if (id != null) {
            feigePigeonMapper.markIdle(id, new Date());
        }
    }

    /**
     * 鸽舍全部鸽子（规格16.3）：首次进入自动获得小白（规格3.2）。
     */
    public List<FeigePigeon> listByOpenid(String openid) {
        getOrInitByOpenid(openid);
        return feigePigeonMapper.selectListByOpenid(openid);
    }

    /**
     * 创建指定角色鸽子（规格14.3/15.5）：同一用户不能重复拥有同一角色。
     *
     * @return 创建成功返回鸽子；角色非法/已拥有/超过6只上限返回 null
     */
    public FeigePigeon createByRole(String openid, String roleKey) {
        if (!PigeonRole.isValid(roleKey)) {
            return null;
        }
        if (paidEnabled && !FeigePigeon.ROLE_XIAOBAI.equals(roleKey)) {
            // 付费开关开启：第2~6只必须走订单权益发放，禁止直接创建
            log.warn("付费开关开启，拒绝直接创建鸽子 openid={} roleKey={}", openid, roleKey);
            return null;
        }
        if (feigePigeonMapper.selectByOpenidAndRole(openid, roleKey) != null) {
            return null;
        }
        if (feigePigeonMapper.selectListByOpenid(openid).size() >= MAX_PIGEONS) {
            return null;
        }
        Date now = new Date();
        FeigePigeon fresh = new FeigePigeon();
        fresh.setOpenid(openid);
        fresh.setName(PigeonRole.defaultName(roleKey));
        fresh.setRoleKey(roleKey);
        fresh.setLevel(1);
        fresh.setExp(0);
        fresh.setSpeedKmh(DEFAULT_SPEED);
        fresh.setStamina(3);
        fresh.setDeliveredCount(0);
        fresh.setTotalMileage(BigDecimal.ZERO);
        fresh.setFarthestDistance(BigDecimal.ZERO);
        fresh.setStatus(FeigePigeon.STATUS_IDLE);
        fresh.setCreateAt(now);
        fresh.setUpdateAt(now);
        feigePigeonMapper.insertSelective(fresh);
        return fresh;
    }

    /**
     * 支付权益发放专用创建（规格15.5）：仅支付确认路径调用，不受 paid-enabled 开关拦截。
     * 幂等：已拥有/超上限返回 null。
     */
    public FeigePigeon grantByRole(String openid, String roleKey) {
        if (!PigeonRole.isValid(roleKey)) {
            return null;
        }
        if (feigePigeonMapper.selectByOpenidAndRole(openid, roleKey) != null) {
            return null;
        }
        if (feigePigeonMapper.selectListByOpenid(openid).size() >= MAX_PIGEONS) {
            return null;
        }
        Date now = new Date();
        FeigePigeon fresh = new FeigePigeon();
        fresh.setOpenid(openid);
        fresh.setName(PigeonRole.defaultName(roleKey));
        fresh.setRoleKey(roleKey);
        fresh.setLevel(1);
        fresh.setExp(0);
        fresh.setSpeedKmh(DEFAULT_SPEED);
        fresh.setStamina(3);
        fresh.setDeliveredCount(0);
        fresh.setTotalMileage(BigDecimal.ZERO);
        fresh.setFarthestDistance(BigDecimal.ZERO);
        fresh.setStatus(FeigePigeon.STATUS_IDLE);
        fresh.setCreateAt(now);
        fresh.setUpdateAt(now);
        feigePigeonMapper.insertSelective(fresh);
        return fresh;
    }

    /**
     * 鸽子改名（规格3.2：首次送达后邀请改名；≤12字）。
     *
     * @return 更新行数；0 表示鸽子不存在
     */
    public int rename(Long pigeonId, String name) {
        if (pigeonId == null || name == null || name.trim().isEmpty()) {
            return 0;
        }
        String safe = name.trim().length() > 12 ? name.trim().substring(0, 12) : name.trim();
        return feigePigeonMapper.rename(pigeonId, safe, new Date());
    }

    /**
     * 旅程履历（规格14.2）：累计里程/送达次数/最远/去过城市/单次旅程履历。
     * 每趟旅程保存原始事实（起飞/到达/距离/时长/起终城市），供未来成就/邮戳复用。
     */
    public Map<String, Object> journeys(FeigePigeon pigeon) {
        Map<String, Object> data = new HashMap<>();
        data.put("pigeonId", pigeon.getId());
        data.put("name", pigeon.getName());
        data.put("roleKey", pigeon.getRoleKey());
        data.put("deliveredCount", pigeon.getDeliveredCount());
        data.put("totalMileage", pigeon.getTotalMileage());
        data.put("farthestDistance", pigeon.getFarthestDistance());
        data.put("cities", feigeLetterMapper.selectCitiesByPigeon(pigeon.getId()));

        List<Map<String, Object>> journeys = new ArrayList<>();
        for (FeigeLetter letter : feigeLetterMapper.selectJourneysByPigeon(pigeon.getId())) {
            Map<String, Object> item = new HashMap<>();
            item.put("letterId", letter.getLetterId());
            item.put("status", letter.getStatus());
            item.put("senderCity", joinCity(letter.getSenderProvince(), letter.getSenderCity()));
            item.put("recipientCity", joinCity(letter.getRecipientProvince(), letter.getRecipientCity()));
            item.put("distanceKm", letter.getDistanceKm());
            item.put("flightHours", letter.getFlightHours());
            item.put("departureTime", formatDate(letter.getDepartureTime()));
            item.put("arrivalTime", formatDate(letter.getArrivalTime()));
            item.put("reply", letter.getReplyToLetterId() != null);
            journeys.add(item);
        }
        data.put("journeys", journeys);
        return data;
    }

    private String joinCity(String province, String city) {
        if (city == null || city.trim().isEmpty()) {
            return province == null ? "" : province;
        }
        return (province == null ? "" : province) + " · " + city;
    }

    private String formatDate(Date date) {
        return date == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    /**
     * 送达结算并返回成长快照：累计 deliver/里程/最远，经验+距离/10，满足阈值升级提速。
     *
     * @return {deltaExp, beforeLevel, afterLevel, afterExp, afterSpeed, levelUp}
     */
    public Map<String, Object> recordDelivery(FeigePigeon pigeon, BigDecimal distanceKm) {
        FeigePigeon update = new FeigePigeon();
        update.setId(pigeon.getId());
        update.setDeliveredCount(pigeon.getDeliveredCount() + 1);
        BigDecimal total = safe(pigeon.getTotalMileage()).add(distanceKm);
        update.setTotalMileage(total);
        update.setFarthestDistance(safe(pigeon.getFarthestDistance()).max(distanceKm));
        update.setUpdateAt(new Date());
        update.setStatus(FeigePigeon.STATUS_IDLE);

        // V1 按产品规格 14.1：不计算/展示等级、经验、升级与提速，仅累积真实旅程数据
        feigePigeonMapper.settleDelivery(update);

        Map<String, Object> snap = new HashMap<>();
        snap.put("deltaExp", 0);
        snap.put("beforeLevel", 0);
        snap.put("afterLevel", 0);
        snap.put("afterExp", 0);
        snap.put("afterSpeed", safe(pigeon.getSpeedKmh()));
        snap.put("levelUp", 0);
        return snap;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}