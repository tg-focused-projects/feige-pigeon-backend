package com.an.feige.feige.entity;

import java.util.Date;

/**
 * 飞鸽传书-飞行日志。
 *
 * <p>绑定后按进度一次性生成，落库保证访问者每次轮询看到一致的日志。</p>
 */
public class FeigeLetterEvent {

    public static final String TYPE_DEPART = "DEPART";
    public static final String TYPE_ARRIVE = "ARRIVE";
    public static final String TYPE_CITY_OVER = "CITY_OVER";
    public static final String TYPE_COUNTERWIND = "COUNTERWIND";
    public static final String TYPE_RAIN = "RAIN";
    public static final String TYPE_REST = "REST";
    public static final String TYPE_CAT_SCARE = "CAT_SCARE";
    public static final String TYPE_FOOD = "FOOD";
    public static final String TYPE_DRIFT = "DRIFT";

    private Long id;

    private String letterId;

    /** 事件序号(按时间排序)。 */
    private Integer seq;

    private String type;

    private String title;

    private String description;

    private Date atTime;

    private Date createAt;

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

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getAtTime() {
        return atTime;
    }

    public void setAtTime(Date atTime) {
        this.atTime = atTime;
    }

    public Date getCreateAt() {
        return createAt;
    }

    public void setCreateAt(Date createAt) {
        this.createAt = createAt;
    }
}
