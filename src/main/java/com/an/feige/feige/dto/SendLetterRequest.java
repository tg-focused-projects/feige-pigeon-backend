package com.an.feige.feige.dto;

import java.math.BigDecimal;

/**
 * 写信并放飞请求体（V2：改为 RequestBody JSON）。
 *
 * <p>lat/lng 可选：缺省时服务端按 province+city 从内置行政区划坐标表兜底；
 * 标题/落款可选（≤64 字）。</p>
 */
public class SendLetterRequest {

    /** 发件人 openid（必填）。 */
    private String openid;

    /** 信件标题（可选，≤64 字）。 */
    private String title;

    /** 正文（必填，≤500 字）。 */
    private String content;

    /** 配图 URL（可选）。 */
    private String imageUrl;

    /** 发件省份（可选，用于坐标兜底与展示）。 */
    private String province;

    /** 发件城市（可选）。 */
    private String city;

    /** 精确纬度（可选，优先使用）。 */
    private BigDecimal lat;

    /** 精确经度（可选，优先使用）。 */
    private BigDecimal lng;

    /** 落款（可选，≤64 字）。 */
    private String signature;

    /** 指定送信鸽子（可选，默认自动选）。 */
    private Long pigeonId;

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

    public Long getPigeonId() {
        return pigeonId;
    }

    public void setPigeonId(Long pigeonId) {
        this.pigeonId = pigeonId;
    }
}
