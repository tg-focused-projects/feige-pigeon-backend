package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigeLetterEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 飞鸽传书-飞行日志 Mapper（注解式，不走 XML）。
 *
 * <p>注意：项目未开启 mapUnderscoreToCamelCase，查询必须用别名显式映射驼峰属性。</p>
 */
public interface FeigeLetterEventMapper {

    String COLS = "id, letter_id AS letterId, seq, type, title, description, "
            + "at_time AS atTime, create_at AS createAt";

    @Insert("INSERT INTO feige_letter_event "
            + "(letter_id, seq, type, title, description, at_time, create_at) "
            + "VALUES (#{letterId}, #{seq}, #{type}, #{title}, #{description}, #{atTime}, #{createAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSelective(FeigeLetterEvent record);

    @Select("SELECT " + COLS + " FROM feige_letter_event WHERE letter_id = #{letterId} ORDER BY seq ASC, id ASC")
    List<FeigeLetterEvent> selectByLetterId(@Param("letterId") String letterId);
}
