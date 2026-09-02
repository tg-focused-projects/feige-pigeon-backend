package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigeOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

/**
 * 飞鸽传书-订单 Mapper（注解式，不走 XML）。
 *
 * <p>注意：项目未开启 mapUnderscoreToCamelCase，查询必须用别名显式映射驼峰属性。</p>
 */
public interface FeigeOrderMapper {

    String COLS = "id, order_no AS orderNo, openid, role_key AS roleKey, slot_index AS slotIndex, "
            + "amount_fen AS amountFen, status, pay_trade_no AS payTradeNo, pay_time AS payTime, "
            + "refund_time AS refundTime, create_at AS createAt, update_at AS updateAt";

    @Insert("INSERT INTO feige_order "
            + "(order_no, openid, role_key, slot_index, amount_fen, status, create_at, update_at) "
            + "VALUES (#{orderNo}, #{openid}, #{roleKey}, #{slotIndex}, #{amountFen}, #{status}, #{createAt}, #{updateAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSelective(FeigeOrder record);

    @Select("SELECT " + COLS + " FROM feige_order WHERE order_no = #{orderNo} LIMIT 1")
    FeigeOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /** 用户某角色的最近一笔订单（权益判断用）。 */
    @Select("SELECT " + COLS + " FROM feige_order WHERE openid = #{openid} AND role_key = #{roleKey} "
            + "ORDER BY id DESC LIMIT 1")
    FeigeOrder selectLatestByOpenidAndRole(@Param("openid") String openid, @Param("roleKey") String roleKey);

    /** 用户某位置的最近一笔订单（防止同一位置重复下单）。 */
    @Select("SELECT " + COLS + " FROM feige_order WHERE openid = #{openid} AND slot_index = #{slotIndex} "
            + "ORDER BY id DESC LIMIT 1")
    FeigeOrder selectLatestByOpenidAndSlot(@Param("openid") String openid, @Param("slotIndex") Integer slotIndex);

    /** 支付确认（幂等：仅 CREATED 可置 PAID，重复回调不生效）。 */
    @Update("UPDATE feige_order SET status = 'PAID', pay_trade_no = #{payTradeNo}, pay_time = #{payTime}, update_at = #{updateAt} "
            + "WHERE order_no = #{orderNo} AND status = 'CREATED'")
    int markPaid(@Param("orderNo") String orderNo,
                 @Param("payTradeNo") String payTradeNo,
                 @Param("payTime") Date payTime,
                 @Param("updateAt") Date updateAt);

    /** 取消订单（仅 CREATED 可取消）。 */
    @Update("UPDATE feige_order SET status = 'CANCELLED', update_at = #{updateAt} "
            + "WHERE order_no = #{orderNo} AND status = 'CREATED'")
    int markCancelled(@Param("orderNo") String orderNo, @Param("updateAt") Date updateAt);

    /** 退款（仅 PAID 可退款；不删除历史，规格15.5）。 */
    @Update("UPDATE feige_order SET status = 'REFUNDED', refund_time = #{refundTime}, update_at = #{updateAt} "
            + "WHERE order_no = #{orderNo} AND status = 'PAID'")
    int markRefunded(@Param("orderNo") String orderNo,
                     @Param("refundTime") Date refundTime,
                     @Param("updateAt") Date updateAt);

    /** 用户全部订单（鸽舍购买记录展示）。 */
    @Select("SELECT " + COLS + " FROM feige_order WHERE openid = #{openid} ORDER BY id DESC")
    List<FeigeOrder> selectByOpenid(@Param("openid") String openid);

    /** 查单兜底：CREATED 且创建早于某时间点（下单后未支付/推送丢失的待确认单，一般仅查最近 N 分钟避免扫历史）。 */
    @Select("SELECT " + COLS + " FROM feige_order WHERE status = 'CREATED' "
            + "AND create_at <= #{before} AND create_at >= #{after} ORDER BY id ASC")
    List<FeigeOrder> selectCreatedBetween(@Param("after") Date after, @Param("before") Date before);
}