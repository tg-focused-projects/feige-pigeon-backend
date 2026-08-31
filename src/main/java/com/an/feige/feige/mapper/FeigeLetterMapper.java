package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigeLetter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 飞鸽传书-信件 Mapper（注解式，不走 XML）。
 *
 * <p>注意：项目未开启 mapUnderscoreToCamelCase，查询必须用别名显式映射驼峰属性；
 * {@code read} 是 MySQL 保留字，必须加反引号。</p>
 */
public interface FeigeLetterMapper {

    String COLS = "id, letter_id AS letterId, share_token AS shareToken, "
            + "sender_openid AS senderOpenid, sender_province AS senderProvince, sender_city AS senderCity, "
            + "sender_lat AS senderLat, sender_lng AS senderLng, "
            + "recipient_openid AS recipientOpenid, recipient_province AS recipientProvince, "
            + "recipient_city AS recipientCity, recipient_lat AS recipientLat, recipient_lng AS recipientLng, "
            + "title, signature, "
            + "content, image_url AS imageUrl, pigeon_id AS pigeonId, pigeon_name AS pigeonName, "
            + "speed_kmh AS speedKmh, distance_km AS distanceKm, flight_hours AS flightHours, "
            + "departure_time AS departureTime, claim_expire_time AS claimExpireTime, "
            + "claimed_at AS claimedAt, recalled_at AS recalledAt, expired_at AS expiredAt, "
            + "arrival_time AS arrivalTime, status, "
            + "settled, settled_at AS settledAt, settle_exp_delta AS settleExpDelta, "
            + "settle_level_before AS settleLevelBefore, settle_level_after AS settleLevelAfter, "
            + "settle_level_up AS settleLevelUp, "
            + "subscribed, notified, `read`, create_at AS createAt, update_at AS updateAt";

    @Insert("INSERT INTO feige_letter "
            + "(letter_id, share_token, sender_openid, sender_province, sender_city, sender_lat, sender_lng, "
            + " title, signature, content, image_url, pigeon_id, pigeon_name, speed_kmh, "
            + " departure_time, claim_expire_time, status, settled, subscribed, notified, `read`, "
            + " create_at, update_at) "
            + "VALUES (#{letterId}, #{shareToken}, #{senderOpenid}, #{senderProvince}, #{senderCity}, "
            + " #{senderLat}, #{senderLng}, #{title}, #{signature}, #{content}, #{imageUrl}, "
            + " #{pigeonId}, #{pigeonName}, #{speedKmh}, "
            + " #{departureTime}, #{claimExpireTime}, #{status}, #{settled}, #{subscribed}, #{notified}, "
            + " #{read}, #{createAt}, #{updateAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSelective(FeigeLetter record);

    @Select("SELECT " + COLS + " FROM feige_letter WHERE letter_id = #{letterId} LIMIT 1")
    FeigeLetter selectByLetterId(@Param("letterId") String letterId);

    @Select("SELECT " + COLS + " FROM feige_letter WHERE share_token = #{shareToken} LIMIT 1")
    FeigeLetter selectByShareToken(@Param("shareToken") String shareToken);

    @Select("SELECT " + COLS + " FROM feige_letter WHERE letter_id = #{letterId} FOR UPDATE")
    FeigeLetter selectByLetterIdForUpdate(@Param("letterId") String letterId);

    /**
     * 原子认领：仅当未认领且未过期、且发件人不能认领自己、且（匿名或指定收件人）时成功。
     * 单条 UPDATE 保证并发下只有一个用户成功。status 统一先置为 IN_FLIGHT，是否到抵达由推进方法处理。
     */
    @Update("UPDATE feige_letter SET "
            + "recipient_openid = #{openid}, recipient_province = #{province}, recipient_city = #{city}, "
            + "recipient_lat = #{lat}, recipient_lng = #{lng}, distance_km = #{distanceKm}, "
            + "flight_hours = #{flightHours}, claimed_at = #{claimedAt}, arrival_time = #{arrivalTime}, "
            + "status = 'IN_FLIGHT', update_at = #{updateAt} "
            + "WHERE share_token = #{shareToken} AND status = 'FLYING_UNCLAIMED' "
            + "AND claim_expire_time > #{now} "
            + "AND (recipient_openid IS NULL OR recipient_openid = #{openid}) "
            + "AND sender_openid <> #{openid}")
    int claimLetter(@Param("shareToken") String shareToken,
                    @Param("openid") String openid,
                    @Param("province") String province,
                    @Param("city") String city,
                    @Param("lat") BigDecimal lat,
                    @Param("lng") BigDecimal lng,
                    @Param("distanceKm") BigDecimal distanceKm,
                    @Param("flightHours") BigDecimal flightHours,
                    @Param("claimedAt") Date claimedAt,
                    @Param("arrivalTime") Date arrivalTime,
                    @Param("updateAt") Date updateAt,
                    @Param("now") Date now);

    /** 召回：仅未认领且未过期才可召回。 */
    @Update("UPDATE feige_letter SET status = 'RECALLED', recalled_at = #{recalledAt}, update_at = #{updateAt} "
            + "WHERE letter_id = #{letterId} AND status = 'FLYING_UNCLAIMED' "
            + "AND recipient_openid IS NULL AND claim_expire_time > #{now}")
    int markRecalled(@Param("letterId") String letterId,
                     @Param("recalledAt") Date recalledAt,
                     @Param("updateAt") Date updateAt,
                     @Param("now") Date now);

    /** 到达并结算：IN_FLIGHT（或已 ARRIVED 但未结算）→ ARRIVED + settled=1 + 快照；幂等（settled=0 才写）。 */
    @Update("UPDATE feige_letter SET status = 'ARRIVED', settled = 1, settled_at = #{settledAt}, "
            + "settle_exp_delta = #{expDelta}, settle_level_before = #{levelBefore}, "
            + "settle_level_after = #{levelAfter}, settle_level_up = #{levelUp}, update_at = #{updateAt} "
            + "WHERE letter_id = #{letterId} AND settled = 0 "
            + "AND arrival_time IS NOT NULL AND arrival_time <= #{now} "
            + "AND status IN ('IN_FLIGHT', 'ARRIVED')")
    int markArrivedAndSettle(@Param("letterId") String letterId,
                             @Param("expDelta") Integer expDelta,
                             @Param("levelBefore") Integer levelBefore,
                             @Param("levelAfter") Integer levelAfter,
                             @Param("levelUp") Integer levelUp,
                             @Param("settledAt") Date settledAt,
                             @Param("updateAt") Date updateAt,
                             @Param("now") Date now);

    /** 未认领过期：FLYING_UNCLAIMED → UNCLAIMED_EXPIRED。 */
    @Update("UPDATE feige_letter SET status = 'UNCLAIMED_EXPIRED', expired_at = #{expiredAt}, update_at = #{updateAt} "
            + "WHERE letter_id = #{letterId} AND status = 'FLYING_UNCLAIMED'")
    int markExpired(@Param("letterId") String letterId,
                    @Param("expiredAt") Date expiredAt,
                    @Param("updateAt") Date updateAt);

    /** 拆信：ARRIVED/DELIVERED → DELIVERED 并置 read=1（幂等）。 */
    @Update("UPDATE feige_letter SET status = 'DELIVERED', `read` = 1, update_at = #{updateAt} "
            + "WHERE letter_id = #{letterId} AND status IN ('ARRIVED', 'DELIVERED')")
    int markDelivered(@Param("letterId") String letterId, @Param("updateAt") Date updateAt);

    @Update("UPDATE feige_letter SET subscribed = #{subscribed}, update_at = #{updateAt} "
            + "WHERE letter_id = #{letterId}")
    int updateSubscribed(@Param("letterId") String letterId,
                         @Param("subscribed") Integer subscribed,
                         @Param("updateAt") Date updateAt);

    @Update("UPDATE feige_letter SET notified = #{notified}, update_at = #{updateAt} "
            + "WHERE letter_id = #{letterId} AND notified = 0")
    int updateNotified(@Param("letterId") String letterId,
                       @Param("notified") Integer notified,
                       @Param("updateAt") Date updateAt);

    /** 抵达扫描：已认领且已到抵达时间。 */
    @Select("SELECT " + COLS + " FROM feige_letter WHERE status = 'IN_FLIGHT' AND arrival_time <= NOW()")
    List<FeigeLetter> selectInFlightArrived();

    /** 未认领过期扫描：FLYING_UNCLAIMED 且已过认领截止。 */
    @Select("SELECT " + COLS + " FROM feige_letter WHERE status = 'FLYING_UNCLAIMED' AND claim_expire_time <= NOW()")
    List<FeigeLetter> selectFlyingUnclaimedExpired();

    @Select("SELECT " + COLS + " FROM feige_letter WHERE sender_openid = #{openid} "
            + "ORDER BY create_at DESC, id DESC LIMIT #{offset}, #{limit}")
    List<FeigeLetter> selectSentByOpenid(@Param("openid") String openid,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM feige_letter WHERE sender_openid = #{openid}")
    int countSentByOpenid(@Param("openid") String openid);
}
