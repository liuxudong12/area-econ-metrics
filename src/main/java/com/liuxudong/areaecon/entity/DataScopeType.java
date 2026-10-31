package com.liuxudong.areaecon.entity;

/**
 * 数据范围枚举 —— 决定"这个人能看到多少行"。
 *
 * 【为什么用枚举而不是 String 常量】
 *   ① 编译期就能挡住拼写错误。写 {@code "REGIN"} 编译器不管，运行期就是"谁都能看"；
 *      写 {@code DataScopeType.REGIN} 直接编译不过。
 *   ② 数据库里存的值和代码里的取值**有唯一映射**，不用到处散落字符串。
 *   ③ 新增一档（比如 SELF 只看自己录的）时，只要改这一个文件和拦截器的一个 switch，
 *      编译器会把所有没处理的 switch 分支提示出来。
 *
 * 【本项目只有两档，为什么不干脆用 boolean】
 * 因为真实项目里几乎一定会有第三档、第四档：
 *   SELF       只看自己创建的
 *   DEPT       只看本部门
 *   DEPT_AND_SUB 本部门及下级部门
 * 用 boolean 表达"全部 / 按辖区"两态，等要加第三档时就得改所有方法签名。
 * **留一个枚举位，比事后改接口便宜得多。**
 */
public enum DataScopeType {

    /** 全部：不加任何行级过滤条件 */
    ALL,

    /** 按辖区：只看 region_code 以"用户辖区前缀"开头的行 */
    REGION;

    /** 把数据库里的字符串转成枚举，不认识的返回 null（由调用方决定怎么处理） */
    public static DataScopeType parse(String value) {
        if (value == null) {
            return null;
        }
        for (DataScopeType t : values()) {
            if (t.name().equalsIgnoreCase(value.trim())) {
                return t;
            }
        }
        return null;
    }
}
