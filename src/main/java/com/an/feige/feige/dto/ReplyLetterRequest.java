package com.an.feige.feige.dto;

import java.math.BigDecimal;

/**
 * 回信请求体（V2：改为 RequestBody JSON）。
 *
 * <p>字段与写信基本一致，但以 letterId 关联原信件，且不指定鸽子（自动选择）。</p>
 */
public class ReplyLetterRequest {

    /** 回信人 openid（必填）。 */
    private String openid;

    /** 标题（可选，≤64 字）。 */
    private String title;

    /** 正文（必填，≤500 字）。 */
    private String content;

    /** 配图 URL（可选）。 */
    private String imageUrl;

    /** 发件省份（可选）。 */
    private String province;

    /** 发件城市（可选）。 */
    private String city;

    /** 精确纬度（可选）。 */
    private BigDecimal lat;

    /** 精确经度（可选）。 */
    private BigDecimal lng;

    /** 落款（可选，≤64 字）。 */
    private String signature;

    /** 原信件 letterId（必填）。 */
    private String letterId;

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public void setLat(BigDecimal lat) {
        this.lat = lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public void setLng(BigDecimal lng) {
        this.lng = lng;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getLetterId() {
        return letterId;
    }

    public void setLetterId(String letterId) {
        this.letterId = letterId;
    }
}
