package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigePigeon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 飞鸽传书-用户鸽子 Mapper（注解式，不走 XML）。
 *
 * <p>注意：项目未开启 mapUnderscoreToCamelCase，查询必须用别名显式映射驼峰属性。</p>
 */
public interface FeigePigeonMapper {

    String COLS = "id, openid, name, level, exp, speed_kmh AS speedKmh, stamina, "
            + "delivered_count AS deliveredCount, total_mileage AS totalMileage, "
            + "farthest_distance AS farthestDistance, status, "
            + "create_at AS createAt, update_at AS updateAt";

    @Insert("INSERT INTO feige_pigeon "
            + "(openid, name, level, exp, speed_kmh, stamina, delivered_count, total_mileage, "
            + " farthest_distance, status, create_at, update_at) "
            + "VALUES (#{openid}, #{name}, #{level}, #{exp}, #{speedKmh}, #{stamina}, #{deliveredCount}, "
            + " #{totalMileage}, #{farthestDistance}, #{status}, #{createAt}, #{updateAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSelective(FeigePigeon record);

    @Select("SELECT " + COLS + " FROM feige_pigeon WHERE openid = #{openid} LIMIT 1")
    FeigePigeon selectByOpenid(@Param("openid") String openid);

    @Select("SELECT " + COLS + " FROM feige_pigeon WHERE id = #{id}")
    FeigePigeon selectByPrimaryKey(@Param("id") Long id);

    @Select("SELECT " + COLS + " FROM feige_pigeon WHERE id = #{id} FOR UPDATE")
    FeigePigeon selectByPrimaryKeyForUpdate(@Param("id") Long id);

    /** 置为送信中（仅空闲时成功，防止同一只鸽子并发送多封）。 */
    @Update("UPDATE feige_pigeon SET status = 'SENDING', update_at = #{updateAt} "
            + "WHERE id = #{id} AND status = 'IDLE'")
    int markSending(@Param("id") Long id, @Param("updateAt") Date updateAt);

    /** 置回空闲（抵达/召回/未认领过期时释放鸽子）。 */
    @Update("UPDATE feige_pigeon SET status = 'IDLE', update_at = #{updateAt} "
            + "WHERE id = #{id} AND status = 'SENDING'")
    int markIdle(@Param("id") Long id, @Param("updateAt") Date updateAt);

    /** 送达结算：累计 deliver/里程/最远/经验，可能升级提速，并置回空闲。 */
    @Update("UPDATE feige_pigeon SET level = #{level}, exp = #{exp}, speed_kmh = #{speedKmh}, "
            + "delivered_count = #{deliveredCount}, total_mileage = #{totalMileage}, "
            + "farthest_distance = #{farthestDistance}, status = #{status}, update_at = #{updateAt} "
            + "WHERE id = #{id}")
    int settleDelivery(FeigePigeon record);
}
