package com.an.feige.feige.entity;

import java.util.Date;

/**
 * 飞鸽传书-通知订阅（规格13.3：按信件+用户+类型独立记录）。
 *
 * <p>type: ARRIVAL 当前鸽子抵达（发件人/收件人分别订阅）/
 * REPLY_ARRIVAL 回信抵达（原发件人订阅）。
 * notified 用于推送幂等（每订阅独立，不用信件级字段代表双方）。</p>
 */
public class FeigeSubscription {

    public static final String TYPE_ARRIVAL = "ARRIVAL";
    public static final String TYPE_REPLY_ARRIVAL = "REPLY_ARRIVAL";

    private Long id;

    private String letterId;

    private String openid;

    /** 订阅类型: ARRIVAL / REPLY_ARRIVAL。 */
    private String type;

    /** 是否已推送(0/1)。 */
    private Integer notified;

    private Date notifiedAt;

    private Date subscribedAt;

    private Date createAt;

    private Date updateAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLetterId() { return letterId; }
    public void setLetterId(String letterId) { this.letterId = letterId; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getNotified() { return notified; }
    public void setNotified(Integer notified) { this.notified = notified; }
    public Date getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Date notifiedAt) { this.notifiedAt = notifiedAt; }
    public Date getSubscribedAt() { return subscribedAt; }
    public void setSubscribedAt(Date subscribedAt) { this.subscribedAt = subscribedAt; }
    public Date getCreateAt() { return createAt; }
    public void setCreateAt(Date createAt) { this.createAt = createAt; }
    public Date getUpdateAt() { return updateAt; }
    public void setUpdateAt(Date updateAt) { this.updateAt = updateAt; }
}
