package com.an.feige.feige.service;

import com.an.feige.feige.mapper.FeigePigeonMapper;
import com.an.feige.feige.entity.FeigePigeon;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞鸽传书-用户鸽子服务。
 *
 * <p>V1 每用户一只「小白」：首访初始化，之后随每次送达成长（经验/等级/速度/里程）。</p>
 */
@Service
public class FeigePigeonService {

    private static final BigDecimal DEFAULT_SPEED = new BigDecimal("177.00");
    private static final BigDecimal SPEED_STEP = new BigDecimal("3");
    private static final BigDecimal EXP_RATIO = new BigDecimal("10");

    @Resource
    private FeigePigeonMapper feigePigeonMapper;

    /** 取用户鸽子，不存在则初始化「小白」。 */
    public FeigePigeon getOrInitByOpenid(String openid) {
        FeigePigeon pigeon = feigePigeonMapper.selectByOpenid(openid);
        if (pigeon != null) {
            return pigeon;
        }
        Date now = new Date();
        FeigePigeon fresh = new FeigePigeon();
        fresh.setOpenid(openid);
        fresh.setName("小白");
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

        int beforeLevel = pigeon.getLevel();
        int deltaExp = distanceKm.divide(EXP_RATIO, 0, RoundingMode.FLOOR).intValue();
        int exp = pigeon.getExp() + deltaExp;
        int level = pigeon.getLevel();
        BigDecimal speed = safe(pigeon.getSpeedKmh());
        while (exp >= level * 100) {
            exp -= level * 100;
            level++;
            speed = speed.add(SPEED_STEP);
        }
        update.setExp(exp);
        update.setLevel(level);
        update.setSpeedKmh(speed);
        feigePigeonMapper.settleDelivery(update);

        Map<String, Object> snap = new HashMap<>();
        snap.put("deltaExp", deltaExp);
        snap.put("beforeLevel", beforeLevel);
        snap.put("afterLevel", level);
        snap.put("afterExp", exp);
        snap.put("afterSpeed", speed);
        snap.put("levelUp", level > beforeLevel ? 1 : 0);
        return snap;
    }

    /** 送达结算：累计/最远/里程，经验+距离/10，满足阈值升级提速，置回空闲。返回结算后的最新鸽子。 */
    public FeigePigeon settleDelivery(FeigePigeon pigeon, BigDecimal distanceKm) {
        FeigePigeon update = new FeigePigeon();
        update.setId(pigeon.getId());
        update.setDeliveredCount(pigeon.getDeliveredCount() + 1);
        BigDecimal total = safe(pigeon.getTotalMileage()).add(distanceKm);
        update.setTotalMileage(total);
        update.setFarthestDistance(safe(pigeon.getFarthestDistance()).max(distanceKm));
        update.setUpdateAt(new Date());
        update.setStatus(FeigePigeon.STATUS_IDLE);

        // 经验 & 升级
        int deltaExp = distanceKm.divide(EXP_RATIO, 0, RoundingMode.FLOOR).intValue();
        int exp = pigeon.getExp() + deltaExp;
        int level = pigeon.getLevel();
        BigDecimal speed = safe(pigeon.getSpeedKmh());
        while (exp >= level * 100) {
            exp -= level * 100;
            level++;
            speed = speed.add(SPEED_STEP);
        }
        update.setExp(exp);
        update.setLevel(level);
        update.setSpeedKmh(speed);
        feigePigeonMapper.settleDelivery(update);
        return getById(pigeon.getId());
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
