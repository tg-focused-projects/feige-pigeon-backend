package com.an.feige.feige.mapper;

import com.an.feige.feige.entity.FeigePayGoods;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 飞鸽传书-虚拟支付道具商品配置 Mapper（V5.1，注解式）。
 *
 * <p>注意：项目未开启 mapUnderscoreToCamelCase，查询必须用别名显式映射驼峰属性。</p>
 */
public interface FeigePayGoodsMapper {

    String COLS = "id, slot_index AS slotIndex, product_id AS productId, price_fen AS priceFen, "
            + "remark, create_at AS createAt, update_at AS updateAt";

    /** 全量商品配置（按槽位升序，slots 展示/下单定价用）。 */
    @Select("SELECT " + COLS + " FROM feige_pay_goods ORDER BY slot_index ASC")
    List<FeigePayGoods> selectAll();

    /** 按槽位查商品配置。 */
    @Select("SELECT " + COLS + " FROM feige_pay_goods WHERE slot_index = #{slotIndex} LIMIT 1")
    FeigePayGoods selectBySlotIndex(@Param("slotIndex") Integer slotIndex);
}
