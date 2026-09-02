package com.an.feige.feige.dto;

import java.math.BigDecimal;

/**
 * 收件人认领请求体（V2：改为 RequestBody JSON）。
 *
 * <p>经纬度可选：缺失时服务端按 province/city 从内置行政区划坐标表兜底，
 * 与 send/reply 处理逻辑保持一致。</p>
 */
public class BindLetterRequest {

    /** 分享凭证（必填）。 */
    private String shareToken;

    /** 收件人 openid（必填）。 */
    private String openid;

    /** 收件省份（可选）。 */
    private String province;

    /** 收件城市（可选）。 */
    private String city;

    /** 精确纬度（可选，优先使用）。 */
    private BigDecimal lat;

    /** 精确经度（可选，优先使用）。 */
    private BigDecimal lng;

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public String getOpenid() {
        return openid;
    }

    public void setOpenid(String openid) {
        this.openid = openid;
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
}
