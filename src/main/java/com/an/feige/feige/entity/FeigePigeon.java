package com.an.feige.feige.entity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 飞鸽传书-用户鸽子（V1 每用户 1 只「小白」）。
 *
 * <p>status: IDLE 空闲 / SENDING 送信中 / LOST 失联(V1.1)。
 * 升级规则：当前等级 Lv N 升 N+1 需 N*100 经验；升级速度 +3 km/h。</p>
 */
public class FeigePigeon {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_LOST = "LOST";

    private Long id;

    private String openid;

    /** 鸽子名，默认「小白」。 */
    private String name;

    /** 等级，默认 1。 */
    private Integer level;

    /** 当前等级内经验。 */
    private Integer exp;

    /** 速度 km/h，默认 177。 */
    private BigDecimal speedKmh;

    /** 体力(❤️ 数)，默认 3。 */
    private Integer stamina;

    /** 成功送达次数。 */
    private Integer deliveredCount;

    /** 累计飞行里程 km。 */
    private BigDecimal totalMileage;

    /** 最远送信 km。 */
    private BigDecimal farthestDistance;

    private String status;

    private Date createAt;

    private Date updateAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getExp() {
        return exp;
    }

    public void setExp(Integer exp) {
        this.exp = exp;
    }

    public BigDecimal getSpeedKmh() {
        return speedKmh;
    }

    public void setSpeedKmh(BigDecimal speedKmh) {
        this.speedKmh = speedKmh;
    }

    public Integer getStamina() {
        return stamina;
    }

    public void setStamina(Integer stamina) {
        this.stamina = stamina;
    }

    public Integer getDeliveredCount() {
        return deliveredCount;
    }

    public void setDeliveredCount(Integer deliveredCount) {
        this.deliveredCount = deliveredCount;
    }

    public BigDecimal getTotalMileage() {
        return totalMileage;
    }

    public void setTotalMileage(BigDecimal totalMileage) {
        this.totalMileage = totalMileage;
    }

    public BigDecimal getFarthestDistance() {
        return farthestDistance;
    }

    public void setFarthestDistance(BigDecimal farthestDistance) {
        this.farthestDistance = farthestDistance;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Date getCreateAt() {
        return createAt;
    }

    public void setCreateAt(Date createAt) {
        this.createAt = createAt;
    }

    public Date getUpdateAt() {
        return updateAt;
    }

    public void setUpdateAt(Date updateAt) {
        this.updateAt = updateAt;
    }
}
