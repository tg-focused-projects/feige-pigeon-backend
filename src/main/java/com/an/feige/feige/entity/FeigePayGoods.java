package com.an.feige.feige.entity;

import java.util.Date;

/**
 * 飞鸽传书-虚拟支付道具商品配置（V5.1）。
 *
 * <p>取代环境变量 FG_PIGEON_PRICES/FG_PAY_GOODS_IDS 双源：鸽舍收费槽位(2~6)的
 * 微信道具 productId 与价格(分)以本表为准。product_id/price_fen 需人工与微信
 * 「虚拟支付 → 道具管理」发布的道具一致（价格不一致微信下单报 -15013）。</p>
 */
public class FeigePayGoods {

    private Long id;

    /** 鸽舍位置序号（2~6，价格绑定位置，规格15.3）。 */
    private Integer slotIndex;

    /** 微信虚拟支付道具 productId（后台道具管理）。 */
    private String productId;

    /** 价格（分，须与后台道具价格一致）。 */
    private Integer priceFen;

    /** 备注（道具名等）。 */
    private String remark;

    private Date createAt;

    private Date updateAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getSlotIndex() { return slotIndex; }
    public void setSlotIndex(Integer slotIndex) { this.slotIndex = slotIndex; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public Integer getPriceFen() { return priceFen; }
    public void setPriceFen(Integer priceFen) { this.priceFen = priceFen; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Date getCreateAt() { return createAt; }
    public void setCreateAt(Date createAt) { this.createAt = createAt; }
    public Date getUpdateAt() { return updateAt; }
    public void setUpdateAt(Date updateAt) { this.updateAt = updateAt; }
}
