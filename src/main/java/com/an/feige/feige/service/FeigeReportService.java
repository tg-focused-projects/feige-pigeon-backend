package com.an.feige.feige.service;

import com.an.feige.feige.entity.FeigeLetter;
import com.an.feige.feige.entity.FeigeReport;
import com.an.feige.feige.mapper.FeigeLetterMapper;
import com.an.feige.feige.mapper.FeigeReportMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;

/**
 * 飞鸽传书-投诉服务（规格17.1：最小投诉功能，运营人工处理，不做删除/拉黑/自动封禁）。
 *
 * <p>类型：INAPPROPRIATE 不当内容 / HARASSMENT 骚扰或诈骗 / PRIVACY 侵犯隐私 / OTHER 其他。
 * 记录 letter_id/reporter/reported_sender/reason/description/status，由运营后台人工处理。</p>
 */
@Service
public class FeigeReportService {

    private static final int MAX_DESCRIPTION_LEN = 500;

    @Resource
    private FeigeReportMapper feigeReportMapper;

    @Resource
    private FeigeLetterMapper feigeLetterMapper;

    /**
     * 提交投诉。
     *
     * @return null 表示入参/信件非法；否则返回投诉记录 id 包装（供接口返回）
     */
    public Long report(String letterId, String reporterOpenid, String reason, String description) {
        if (letterId == null || letterId.trim().isEmpty()
                || reporterOpenid == null || reporterOpenid.trim().isEmpty()) {
            return null;
        }
        if (!FeigeReport.REASON_INAPPROPRIATE.equals(reason)
                && !FeigeReport.REASON_HARASSMENT.equals(reason)
                && !FeigeReport.REASON_PRIVACY.equals(reason)
                && !FeigeReport.REASON_OTHER.equals(reason)) {
            return null;
        }
        FeigeLetter letter = feigeLetterMapper.selectByLetterId(letterId);
        if (letter == null) {
            return null;
        }
        Date now = new Date();
        FeigeReport report = new FeigeReport();
        report.setLetterId(letterId);
        report.setReporterOpenid(reporterOpenid);
        report.setReportedSenderOpenid(letter.getSenderOpenid());
        report.setReason(reason);
        report.setDescription(description == null ? null
                : (description.length() > MAX_DESCRIPTION_LEN ? description.substring(0, MAX_DESCRIPTION_LEN) : description));
        report.setStatus(FeigeReport.STATUS_PENDING);
        report.setCreateAt(now);
        report.setUpdateAt(now);
        feigeReportMapper.insertSelective(report);
        return report.getId();
    }
}
