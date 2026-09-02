package com.an.feige.feige.service;

import com.an.feige.feige.entity.FeigeOrder;
import com.an.feige.feige.entity.FeigePigeon;
import com.an.feige.feige.entity.PigeonRole;
import com.an.feige.feige.mapper.FeigeOrderMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 飞鸽传书-多鸽付费购买服务（规格15）。
 *
 * <p>价格绑定「第几个鸽舍位置」（15.3）；支付结果以后端回调为准（15.5）；
 * 订单与权益发放幂等（重复回调不重复创建）；退款不删除历史，鸽子继续可用（15.5）。
 * 开关 PAID_PIGEON_ENABLED（15.6）：关→第2~6只免费创建（无资格期兼容）；开→必须购买。</p>
 */
@Service
public class FeigePayService {

    private static final Logger log = LoggerFactory.getLogger(FeigePayService.class);
    /** 免费位置数（第1只小白免费，规格15.1）。 */
    private static final int FREE_SLOTS = 1;
    /** 每用户最多6只（规格15.1）。 */
    private static final int MAX_SLOTS = 6;

    @Resource
    private FeigeOrderMapper feigeOrderMapper;

    @Resource
    private FeigePigeonService feigePigeonService;

    @Value("${feige.pigeon.paid-enabled:false}")
    private boolean paidEnabled;

    @Value("${feige.pigeon.prices:0,100,300,600,1000,1500}")
    private String pricesConfig;

    @Value("${feige.pay.mock-pay:true}")
    private boolean mockPay;

    @Value("${feige.pay.mch-id:}")
    private String mchId;

    /**
     * 鸽舍槽位（规格16.3/15.4）：第1位小白；空位置展示候选角色与价格。
     *
     * @return { slots:[{ index, roleKey|null, name, status, amountFen, paid }], freeCount, maxSlots }
     */
    public Map<String, Object> slots(String openid) {
        // 物理 6 位置（规格15.1/15.3：价格绑定位置，不绑定角色）
        List<FeigePigeon> owned = feigePigeonService.listByOpenid(openid);
        Map<Integer, FeigePigeon> ownedBySlot = new HashMap<>();
        Map<String, FeigePigeon> ownedByRole = new HashMap<>();
        for (FeigePigeon p : owned) {
            ownedBySlot.put(p.getSlotIndex() == null ? 0 : p.getSlotIndex(), p);
            ownedByRole.put(p.getRoleKey(), p);
        }

        int[] prices = parsePrices();
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int slotIndex = 1; slotIndex <= MAX_SLOTS; slotIndex++) {
            int priceFen = slotIndex - 1 < prices.length ? prices[slotIndex - 1] : 0;
            FeigePigeon pigeon = ownedBySlot.get(slotIndex);
            Map<String, Object> slot = new HashMap<>();
            slot.put("index", slotIndex);
            slot.put("amountFen", slotIndex > FREE_SLOTS ? priceFen : 0);
            if (pigeon != null) {
                slot.put("roleKey", pigeon.getRoleKey());
                slot.put("name", pigeon.getName());
                slot.put("status", pigeon.getStatus());
                slot.put("deliveredCount", pigeon.getDeliveredCount());
                slot.put("totalMileage", pigeon.getTotalMileage());
                slot.put("paid", slotIndex > FREE_SLOTS);
                slot.put("occupied", true);
            } else {
                slot.put("roleKey", null);
                slot.put("name", null);
                slot.put("status", "EMPTY");
                slot.put("deliveredCount", 0);
                slot.put("totalMileage", 0);
                slot.put("paid", false);
                slot.put("occupied", false);
            }
            slots.add(slot);
        }

        // 候选角色：尚未拥有的全部角色（规格15.5：购买位置后从候选角色中选择入住）
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : PigeonRole.all().entrySet()) {
            String roleKey = entry.getKey();
            if (ownedByRole.containsKey(roleKey)) {
                continue;
            }
            Map<String, Object> cand = new HashMap<>();
            cand.put("roleKey", roleKey);
            cand.put("name", PigeonRole.defaultName(roleKey));
            cand.put("motto", PigeonRole.motto(roleKey));
            candidates.add(cand);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("slots", slots);
        data.put("candidates", candidates);
        data.put("freeCount", Math.max(0, MAX_SLOTS - owned.size()));
        data.put("maxSlots", MAX_SLOTS);
        data.put("paidEnabled", paidEnabled);
        data.put("mockPay", mockPay);
        return data;
    }
    /**
     * 创建购买订单（规格15.5：创建订单→支付→回调确认）。
     *
     * @return null 表示参数/状态非法；否则 { orderNo, roleKey, slotIndex, amountFen, status }
     */
    public Map<String, Object> createOrder(String openid, Integer slotIndex, String roleKey) {
        if (!paidEnabled) {
            log.info("付费开关关闭，跳过下单 openid={} slot={}", openid, slotIndex);
            return null;
        }
        if (!PigeonRole.isValid(roleKey) || FeigePigeon.ROLE_XIAOBAI.equals(roleKey)) {
            return null;
        }
        // 位置 2~6 可购买（规格15.3：价格绑定位置；位置1小白免费不售）
        if (slotIndex == null || slotIndex <= FREE_SLOTS || slotIndex > MAX_SLOTS) {
            return null;
        }
        if (feigePigeonService.getByOpenidAndRole(openid, roleKey) != null) {
            return null;
        }
        // 目标位置必须为空（买的是空位置）
        if (feigePigeonService.getByOpenidAndSlot(openid, slotIndex) != null) {
            return null;
        }
        // 防重复下单：该角色或该位置存在未完成订单（CREATED/PAID 均拦截，避免占位重复）
        FeigeOrder existRole = feigeOrderMapper.selectLatestByOpenidAndRole(openid, roleKey);
        if (existRole != null && !FeigeOrder.STATUS_CANCELLED.equals(existRole.getStatus())
                && !FeigeOrder.STATUS_REFUNDED.equals(existRole.getStatus())) {
            return null;
        }
        FeigeOrder existSlot = feigeOrderMapper.selectLatestByOpenidAndSlot(openid, slotIndex);
        if (existSlot != null && !FeigeOrder.STATUS_CANCELLED.equals(existSlot.getStatus())
                && !FeigeOrder.STATUS_REFUNDED.equals(existSlot.getStatus())) {
            return null;
        }
        int[] prices = parsePrices();
        int amountFen = slotIndex - 1 < prices.length ? prices[slotIndex - 1] : 0;

        Date now = new Date();
        FeigeOrder order = new FeigeOrder();
        order.setOrderNo(newOrderNo());
        order.setOpenid(openid);
        order.setRoleKey(roleKey);
        order.setSlotIndex(slotIndex);
        order.setAmountFen(amountFen);
        order.setStatus(FeigeOrder.STATUS_CREATED);
        order.setCreateAt(now);
        order.setUpdateAt(now);
        feigeOrderMapper.insertSelective(order);

        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("roleKey", roleKey);
        data.put("slotIndex", slotIndex);
        data.put("amountFen", amountFen);
        data.put("status", FeigeOrder.STATUS_CREATED);
        return data;
    }

    /**
     * 支付确认（统一入口：mock 支付与微信支付回调都走这里）。
     * 幂等：订单 CREATED→PAID 仅一次生效；PAID 后发放权益（创建鸽子）同样幂等。
     *
     * @param payTradeNo 支付平台交易号（mock 时可为空）
     * @return 支付确认结果；订单不存在/状态非法返回 null
     */
    @Transactional
    public Map<String, Object> confirmPaid(String orderNo, String payTradeNo) {
        FeigeOrder order = feigeOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            return null;
        }
        if (FeigeOrder.STATUS_PAID.equals(order.getStatus())) {
            // 重复回调：幂等返回当前状态（不重复发放权益）
            return orderResult(order);
        }
        if (!FeigeOrder.STATUS_CREATED.equals(order.getStatus())) {
            return null;
        }

        Date now = new Date();
        int rows = feigeOrderMapper.markPaid(orderNo, payTradeNo, now, now);
        if (rows <= 0) {
            FeigeOrder latest = feigeOrderMapper.selectByOrderNo(orderNo);
            return latest == null ? null : orderResult(latest);
        }

        // 权益发放：已支付才创建鸽子并入住订单位置（幂等：grantByRole 内部查重+位置占用检查）
        FeigePigeon pigeon = feigePigeonService.grantByRole(order.getOpenid(), order.getRoleKey(),
                order.getSlotIndex());
        if (pigeon == null) {
            log.warn("权益发放失败(可能已拥有/位置已占/超上限) orderNo={} roleKey={} slot={}",
                    orderNo, order.getRoleKey(), order.getSlotIndex());
        }
        log.info("支付确认成功 orderNo={} roleKey={} slot={} openid={}",
                orderNo, order.getRoleKey(), order.getSlotIndex(), order.getOpenid());
        // 返回最新订单状态（避免内存旧对象显示 CREATED）
        FeigeOrder latest = feigeOrderMapper.selectByOrderNo(orderNo);
        return orderResult(latest == null ? order : latest);
    }

    /**
     * 退款（规格15.5：不删除历史；若鸽子正在执行任务，当前旅程完成后再停止发起新任务——V1.2 仅记录状态）。
     * 幂等：仅 PAID 可退款。
     */
    public boolean refund(String orderNo) {
        FeigeOrder order = feigeOrderMapper.selectByOrderNo(orderNo);
        if (order == null || !FeigeOrder.STATUS_PAID.equals(order.getStatus())) {
            return false;
        }
        Date now = new Date();
        return feigeOrderMapper.markRefunded(orderNo, now, now) > 0;
    }

    /** 查询订单（鸽舍购买记录展示）。 */
    public List<FeigeOrder> ordersOf(String openid) {
        return feigeOrderMapper.selectByOpenid(openid);
    }

    /** 按订单号查订单（Controller 归属校验用）。 */
    public FeigeOrder getOrder(String orderNo) {
        return feigeOrderMapper.selectByOrderNo(orderNo);
    }

    public boolean isPaidEnabled() {
        return paidEnabled;
    }

    private Map<String, Object> orderResult(FeigeOrder order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderNo", order.getOrderNo());
        data.put("roleKey", order.getRoleKey());
        data.put("slotIndex", order.getSlotIndex());
        data.put("amountFen", order.getAmountFen());
        data.put("status", order.getStatus());
        data.put("paid", FeigeOrder.STATUS_PAID.equals(order.getStatus()));
        return data;
    }

    private int[] parsePrices() {
        String[] parts = StringUtils.defaultString(pricesConfig, "0,100,300,600,1000,1500").split(",");
        int[] prices = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                prices[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                prices[i] = 0;
            }
        }
        return prices;
    }

    private String newOrderNo() {
        return "OD" + UUID.randomUUID().toString().replace("-", "");
    }
}