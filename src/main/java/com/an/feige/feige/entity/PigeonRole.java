package com.an.feige.feige.entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 六只首发鸽子角色（规格14.3）。
 *
 * <p>名称与性格为已确定的首发工作名；统一 177km/h、无属性强弱、
 * 不同外观/动作/性格文案；同一用户不能重复拥有同一角色。</p>
 */
public final class PigeonRole {

    /** 小白：认真、有点谨慎。 */
    public static final String XIAOBAI = "XIAOBAI";
    /** 胖墩：贪吃、可靠。 */
    public static final String PANGDUN = "PANGDUN";
    /** 灰灰：安静、沉稳。 */
    public static final String HUIHUI = "HUIHUI";
    /** 阿闪：精神充沛、动作利落。 */
    public static final String ASHAN = "ASHAN";
    /** 老邮差：经验感、慢性子。 */
    public static final String LAOYOUCHAI = "LAOYOUCHAI";
    /** 花翎：好奇、爱看风景。 */
    public static final String HUALING = "HUALING";

    private static final Map<String, String[]> ROLES = new LinkedHashMap<>();

    static {
        ROLES.put(XIAOBAI, new String[]{"小白", "认真，有点谨慎"});
        ROLES.put(PANGDUN, new String[]{"胖墩", "贪吃，但很可靠"});
        ROLES.put(HUIHUI, new String[]{"灰灰", "安静，沉稳"});
        ROLES.put(ASHAN, new String[]{"阿闪", "精神充沛，动作利落"});
        ROLES.put(LAOYOUCHAI, new String[]{"老邮差", "经验老道，慢性子"});
        ROLES.put(HUALING, new String[]{"花翎", "好奇，爱看风景"});
    }

    private PigeonRole() {
    }

    /** 全部候选角色（按规格顺序），供鸽舍空位/候选列表展示。 */
    public static Map<String, String[]> all() {
        return new LinkedHashMap<>(ROLES);
    }

    /** 角色是否合法。 */
    public static boolean isValid(String roleKey) {
        return roleKey != null && ROLES.containsKey(roleKey);
    }

    /** 角色默认名（未改名前的展示名）。 */
    public static String defaultName(String roleKey) {
        String[] info = ROLES.get(roleKey);
        return info == null ? "小白" : info[0];
    }

    /** 角色性格文案。 */
    public static String motto(String roleKey) {
        String[] info = ROLES.get(roleKey);
        return info == null ? "认真，有点谨慎" : info[1];
    }
}
