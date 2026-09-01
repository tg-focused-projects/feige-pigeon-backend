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
