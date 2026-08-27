package com.an.feige.feige.entity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 飞鸽传书-信件（V1.1 发送即起飞版）。
 *
 * <p>status: FLYING_UNCLAIMED 已起飞未认领 / IN_FLIGHT 已认领未抵达 / ARRIVED 已抵达未拆信 /
 * DELIVERED 已拆信(终态) / RECALLED 发件人召回(终态) / UNCLAIMED_EXPIRED 未认领过期(终态)。
 * 分享公开参数使用 {@code shareToken}（不可猜测），绝不暴露正文与精确坐标。</p>
 */
public class FeigeLetter {

    public static final String STATUS_FLYING_UNCLAIMED = "FLYING_UNCLAIMED";
    public static final String STATUS_IN_FLIGHT = "IN_FLIGHT";
    public static final String STATUS_ARRIVED = "ARRIVED";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_RECALLED = "RECALLED";
    public static final String STATUS_UNCLAIMED_EXPIRED = "UNCLAIMED_EXPIRED";
    /** V1.1 不使用，保留占位。 */
    public static final String STATUS_LOST = "LOST";

    private Long id;

    /** 内部唯一 ID（发件人/收件人确认后使用）。 */
    private String letterId;

    /** 不可猜测的分享凭证（公开参数，分享携带）。 */
    private String shareToken;

    private String senderOpenid;

    private String senderProvince;

    private String senderCity;

    private BigDecimal senderLat;

    private BigDecimal senderLng;

    private String recipientOpenid;

    private String recipientProvince;

    private String recipientCity;

    private BigDecimal recipientLat;

    private BigDecimal recipientLng;

    /** 正文(≤500 字，分享预览/未认领绝不返回，拆信后才返回)。 */
    private String content;

    private String imageUrl;

    private Long pigeonId;

    /** 送达时鸽子快照。 */
    private String pigeonName;

    /** 送达时速度快照。 */
    private BigDecimal speedKmh;

    /** 直线距离 km(认领后填)。 */
    private BigDecimal distanceKm;

    /** 飞行时长 = 距离/速度(认领后填)。 */
    private BigDecimal flightHours;

    /** 起飞=服务器当前时间(发送即起飞，不可改)。 */
    private Date departureTime;

    /** 认领截止 = 起飞+72h。 */
    private Date claimExpireTime;

    /** 收件人认领成功时间。 */
    private Date claimedAt;

    /** 主动召回时间。 */
    private Date recalledAt;

    /** 自动过期时间。 */
    private Date expiredAt;

    /** 预计到达 = 原始 departure + 飞行时长(认领后填)。 */
    private Date arrivalTime;

    private String status;

    /** 抵达成长是否已结算(0/1)。 */
    private Integer settled;

    /** 成长结算时间。 */
    private Date settledAt;

    /** 本次结算经验增量。 */
    private Integer settleExpDelta;

    /** 结算前等级。 */
    private Integer settleLevelBefore;

    /** 结算后等级。 */
    private Integer settleLevelAfter;

    /** 本次是否升级(0/1)。 */
    private Integer settleLevelUp;

    /** 是否订阅到达通知(0/1)。 */
    private Integer subscribed;

    /** 是否已发到达通知(0/1)。 */
    private Integer notified;

    /** 是否已拆信(0/1)。 */
    private Integer read;

    private Date createAt;

    private Date updateAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getLetterId() {
        return letterId;
    }

    public void setLetterId(String letterId) {
        this.letterId = letterId;
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public String getSenderOpenid() {
        return senderOpenid;
    }

    public void setSenderOpenid(String senderOpenid) {
        this.senderOpenid = senderOpenid;
    }

    public String getSenderProvince() {
        return senderProvince;
    }

    public void setSenderProvince(String senderProvince) {
        this.senderProvince = senderProvince;
    }

    public String getSenderCity() {
        return senderCity;
    }

    public void setSenderCity(String senderCity) {
        this.senderCity = senderCity;
    }

    public BigDecimal getSenderLat() {
        return senderLat;
    }

    public void setSenderLat(BigDecimal senderLat) {
        this.senderLat = senderLat;
    }

    public BigDecimal getSenderLng() {
        return senderLng;
    }

    public void setSenderLng(BigDecimal senderLng) {
        this.senderLng = senderLng;
    }

    public String getRecipientOpenid() {
        return recipientOpenid;
    }

    public void setRecipientOpenid(String recipientOpenid) {
        this.recipientOpenid = recipientOpenid;
    }

    public String getRecipientProvince() {
        return recipientProvince;
    }

    public void setRecipientProvince(String recipientProvince) {
        this.recipientProvince = recipientProvince;
    }

    public String getRecipientCity() {
        return recipientCity;
    }

    public void setRecipientCity(String recipientCity) {
        this.recipientCity = recipientCity;
    }

    public BigDecimal getRecipientLat() {
        return recipientLat;
    }

    public void setRecipientLat(BigDecimal recipientLat) {
        this.recipientLat = recipientLat;
    }

    public BigDecimal getRecipientLng() {
        return recipientLng;
    }

    public void setRecipientLng(BigDecimal recipientLng) {
        this.recipientLng = recipientLng;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Long getPigeonId() {
        return pigeonId;
    }

    public void setPigeonId(Long pigeonId) {
        this.pigeonId = pigeonId;
    }

    public String getPigeonName() {
        return pigeonName;
    }

    public void setPigeonName(String pigeonName) {
        this.pigeonName = pigeonName;
    }

    public BigDecimal getSpeedKmh() {
        return speedKmh;
    }

    public void setSpeedKmh(BigDecimal speedKmh) {
        this.speedKmh = speedKmh;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(BigDecimal distanceKm) {
        this.distanceKm = distanceKm;
    }

    public BigDecimal getFlightHours() {
        return flightHours;
    }

    public void setFlightHours(BigDecimal flightHours) {
        this.flightHours = flightHours;
    }

    public Date getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(Date departureTime) {
        this.departureTime = departureTime;
    }

    public Date getClaimExpireTime() {
        return claimExpireTime;
    }

    public void setClaimExpireTime(Date claimExpireTime) {
        this.claimExpireTime = claimExpireTime;
    }

    public Date getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Date claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Date getRecalledAt() {
        return recalledAt;
    }

    public void setRecalledAt(Date recalledAt) {
        this.recalledAt = recalledAt;
    }

    public Date getExpiredAt() {
        return expiredAt;
    }

    public void setExpiredAt(Date expiredAt) {
        this.expiredAt = expiredAt;
    }

    public Date getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(Date arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getSettled() {
        return settled;
    }

    public void setSettled(Integer settled) {
        this.settled = settled;
    }

    public Date getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Date settledAt) {
        this.settledAt = settledAt;
    }

    public Integer getSettleExpDelta() {
        return settleExpDelta;
    }

    public void setSettleExpDelta(Integer settleExpDelta) {
        this.settleExpDelta = settleExpDelta;
    }

    public Integer getSettleLevelBefore() {
        return settleLevelBefore;
    }

    public void setSettleLevelBefore(Integer settleLevelBefore) {
        this.settleLevelBefore = settleLevelBefore;
    }

    public Integer getSettleLevelAfter() {
        return settleLevelAfter;
    }

    public void setSettleLevelAfter(Integer settleLevelAfter) {
        this.settleLevelAfter = settleLevelAfter;
    }

    public Integer getSettleLevelUp() {
        return settleLevelUp;
    }

    public void setSettleLevelUp(Integer settleLevelUp) {
        this.settleLevelUp = settleLevelUp;
    }

    public Integer getSubscribed() {
        return subscribed;
    }

    public void setSubscribed(Integer subscribed) {
        this.subscribed = subscribed;
    }

    public Integer getNotified() {
        return notified;
    }

    public void setNotified(Integer notified) {
        this.notified = notified;
    }

    public Integer getRead() {
        return read;
    }

    public void setRead(Integer read) {
        this.read = read;
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
