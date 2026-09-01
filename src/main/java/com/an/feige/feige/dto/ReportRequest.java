package com.an.feige.feige.dto;

/**
 * 投诉请求（规格17.1）。
 */
public class ReportRequest {

    private String letterId;

    private String openid;

    /** INAPPROPRIATE/HARASSMENT/PRIVACY/OTHER。 */
    private String reason;

    private String description;

    public String getLetterId() { return letterId; }
    public void setLetterId(String letterId) { this.letterId = letterId; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
