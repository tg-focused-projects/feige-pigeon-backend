package com.an.feige.user.entity;

import java.util.Date;

/**
 * 飞鸽传书小程序用户（登录/注册）。
 */
public class FgUser {

    public static final int STATUS_NORMAL = 1;

    private Long id;
    private String openid;
    private String sessionKey;
    private String nickname;
    private String face;
    private String mobile;
    private Integer appType;
    private Integer status;
    private Date createAt;
    private Date updateAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getFace() { return face; }
    public void setFace(String face) { this.face = face; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public Integer getAppType() { return appType; }
    public void setAppType(Integer appType) { this.appType = appType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getCreateAt() { return createAt; }
    public void setCreateAt(Date createAt) { this.createAt = createAt; }
    public Date getUpdateAt() { return updateAt; }
    public void setUpdateAt(Date updateAt) { this.updateAt = updateAt; }
}
