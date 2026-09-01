package com.an.feige.feige.entity;

import java.util.Date;

/**
 * 飞鸽传书-多鸽购买订单（规格15：价格绑定鸽舍位置，权益发放幂等）。
 *
 * <p>status: CREATED 已下单待支付 / PAID 已支付(权益已发放或待发放) / REFUNDED 已退款(不删历史,
 * 鸽子继续可用,规格15.5) / CANCELLED 已取消。支付结果以后端回调为准（规格15.5）。</p>
 */
public class FeigeOrder {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private Long id;

    /** 内部订单号（唯一，不可猜测）。 */
    private String orderNo;

    private String openid;

    /** 购买角色（PANGDUN/HUIHUI/ASHAN/LAOYOUCHAI/HUALING）。 */
    private String roleKey;

    /** 鸽舍位置序号（2~6，价格绑定位置，规格15.3）。 */
    private Integer slotIndex;

    /** 金额（分）。 */
    private Integer amountFen;

    private String status;

    /** 支付平台交易号（回调写入）。 */
    private String payTradeNo;

    private Date payTime;

    private Date refundTime;

    private Date createAt;

    private Date updateAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getRoleKey() { return roleKey; }
    public void setRoleKey(String roleKey) { this.roleKey = roleKey; }
    public Integer getSlotIndex() { return slotIndex; }
    public void setSlotIndex(Integer slotIndex) { this.slotIndex = slotIndex; }
    public Integer getAmountFen() { return amountFen; }
    public void setAmountFen(Integer amountFen) { this.amountFen = amountFen; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayTradeNo() { return payTradeNo; }
    public void setPayTradeNo(String payTradeNo) { this.payTradeNo = payTradeNo; }
    public Date getPayTime() { return payTime; }
    public void setPayTime(Date payTime) { this.payTime = payTime; }
    public Date getRefundTime() { return refundTime; }
    public void setRefundTime(Date refundTime) { this.refundTime = refundTime; }
    public Date getCreateAt() { return createAt; }
    public void setCreateAt(Date createAt) { this.createAt = createAt; }
    public Date getUpdateAt() { return updateAt; }
    public void setUpdateAt(Date updateAt) { this.updateAt = updateAt; }
}
