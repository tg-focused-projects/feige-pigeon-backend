package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigeSubscription;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * 飞鸽传书-通知订阅 Mapper（注解式，不走 XML）。
 *
 * <p>注意：项目未开启 mapUnderscoreToCamelCase，查询必须用别名显式映射驼峰属性。</p>
 */
public interface FeigeSubscriptionMapper {

    String COLS = "id, letter_id AS letterId, openid, type, notified, "
            + "notified_at AS notifiedAt, subscribed_at AS subscribedAt, "
            + "create_at AS createAt, update_at AS updateAt";

    /** 幂等订阅：已存在（同信+同人+同类型）则仅刷新订阅时间。 */
    @Insert("INSERT INTO feige_subscription "
            + "(letter_id, openid, type, notified, notified_at, subscribed_at, create_at, update_at) "
            + "VALUES (#{letterId}, #{openid}, #{type}, 0, NULL, #{now}, #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE subscribed_at = #{now}, update_at = #{now}")
    int upsert(@Param("letterId") String letterId,
               @Param("openid") String openid,
               @Param("type") String type,
               @Param("now") Date now);

    @Select("SELECT " + COLS + " FROM feige_subscription "
            + "WHERE letter_id = #{letterId} AND openid = #{openid} AND type = #{type} LIMIT 1")
    FeigeSubscription selectOne(@Param("letterId") String letterId,
                                @Param("openid") String openid,
                                @Param("type") String type);

    /** 某封信指定类型的全部订阅（推送用）。 */
    @Select("SELECT " + COLS + " FROM feige_subscription "
            + "WHERE letter_id = #{letterId} AND type = #{type} AND notified = 0")
    List<FeigeSubscription> selectPendingByLetterAndType(@Param("letterId") String letterId,
                                                         @Param("type") String type);

    /** 某封信指定用户全部订阅类型（飞行页展示）。 */
    @Select("SELECT " + COLS + " FROM feige_subscription "
            + "WHERE letter_id = #{letterId} AND openid = #{openid}")
    List<FeigeSubscription> selectByLetterAndUser(@Param("letterId") String letterId,
                                                  @Param("openid") String openid);

    /** 标记已推送（幂等：notified=0 才成功，防重复推送）。 */
    @Update("UPDATE feige_subscription SET notified = 1, notified_at = #{notifiedAt}, update_at = #{updateAt} "
            + "WHERE id = #{id} AND notified = 0")
    int markNotified(@Param("id") Long id,
                     @Param("notifiedAt") Date notifiedAt,
                     @Param("updateAt") Date updateAt);
}
