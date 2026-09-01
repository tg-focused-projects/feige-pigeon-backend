package com.an.feige.feige.entity;

import java.util.Date;

/**
 * 飞鸽传书-内容投诉（规格17.1：最小投诉功能，运营人工处理）。
 *
 * <p>reason: INAPPROPRIATE 不当内容 / HARASSMENT 骚扰或诈骗 / PRIVACY 侵犯隐私 / OTHER 其他。
 * status: PENDING 待处理 / REVIEWED 已查看 / CLOSED 已关闭（不做自动封禁与复杂申诉）。</p>
 */
public class FeigeReport {

    public static final String REASON_INAPPROPRIATE = "INAPPROPRIATE";
    public static final String REASON_HARASSMENT = "HARASSMENT";
    public static final String REASON_PRIVACY = "PRIVACY";
    public static final String REASON_OTHER = "OTHER";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;

    private String letterId;

    private String reporterOpenid;

    private String reportedSenderOpenid;

    private String reason;

    private String description;

    private String status;

    private Date createAt;

    private Date updateAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLetterId() { return letterId; }
    public void setLetterId(String letterId) { this.letterId = letterId; }
    public String getReporterOpenid() { return reporterOpenid; }
    public void setReporterOpenid(String reporterOpenid) { this.reporterOpenid = reporterOpenid; }
    public String getReportedSenderOpenid() { return reportedSenderOpenid; }
    public void setReportedSenderOpenid(String reportedSenderOpenid) { this.reportedSenderOpenid = reportedSenderOpenid; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getCreateAt() { return createAt; }
    public void setCreateAt(Date createAt) { this.createAt = createAt; }
    public Date getUpdateAt() { return updateAt; }
    public void setUpdateAt(Date updateAt) { this.updateAt = updateAt; }
}
