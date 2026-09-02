package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigeReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

import java.util.Date;

/**
 * 飞鸽传书-投诉 Mapper（注解式，不走 XML）。
 */
public interface FeigeReportMapper {

    @Insert("INSERT INTO feige_report "
            + "(letter_id, reporter_openid, reported_sender_openid, reason, description, status, create_at, update_at) "
            + "VALUES (#{letterId}, #{reporterOpenid}, #{reportedSenderOpenid}, #{reason}, #{description}, "
            + " #{status}, #{createAt}, #{updateAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSelective(FeigeReport record);
}
