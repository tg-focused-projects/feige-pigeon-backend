package com.an.feige.user.mapper;

import com.an.feige.user.entity.FgUser;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * fg_user Mapper（注解式；项目未开 mapUnderscoreToCamelCase，列用别名映射驼峰）。
 */
public interface FgUserMapper {

    String COLS = "id, openid, session_key AS sessionKey, nickname, face, mobile, "
            + "app_type AS appType, status, create_at AS createAt, update_at AS updateAt";

    @Insert("INSERT INTO fg_user (openid, session_key, nickname, face, mobile, app_type, status, create_at, update_at) "
            + "VALUES (#{openid}, #{sessionKey}, #{nickname}, #{face}, #{mobile}, #{appType}, #{status}, #{createAt}, #{updateAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSelective(FgUser user);

    @Select("SELECT " + COLS + " FROM fg_user WHERE openid = #{openid} ORDER BY id DESC LIMIT 1")
    FgUser selectByOpenid(@Param("openid") String openid);

    @Update("UPDATE fg_user SET session_key = #{sessionKey}, update_at = #{updateAt} WHERE id = #{id}")
    int updateSessionKey(@Param("id") Long id, @Param("sessionKey") String sessionKey, @Param("updateAt") java.util.Date updateAt);

    @Update("UPDATE fg_user SET nickname = #{nickname}, face = #{face}, mobile = #{mobile}, update_at = #{updateAt} WHERE id = #{id}")
    int updateProfile(@Param("id") Long id, @Param("nickname") String nickname, @Param("face") String face,
                      @Param("mobile") String mobile, @Param("updateAt") java.util.Date updateAt);
}
