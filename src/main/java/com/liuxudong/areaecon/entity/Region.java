package com.liuxudong.areaecon.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 区域维表（dim_region）—— 对应数据库里那张表。
 *
 * 【实体类是什么】
 * 它就是"表在 Java 里的样子"：表有 9 个字段，这个类就有 9 个属性，一一对应。
 * 每个属性叫什么名字？——把数据库的下划线命名转成驼峰：
 *     region_code  →  regionCode
 *     parent_code  →  parentCode
 * 这个转换是自动的，因为 application.yml 里开了 map-underscore-to-camel-case: true。
 *
 * 【两个注解】
 * @TableName("dim_region")  告诉 MyBatis-Plus 这个类对应哪张表。
 *                           不加的话它默认按类名猜表名（Region → region），就找不到了。
 * @TableId(type = IdType.AUTO)  标记哪个属性是主键，且是数据库自增。
 *                           这样 insert 的时候不用自己塞 id，数据库会生成并回填。
 *
 * 【@TableLogic 是干什么的】
 * 逻辑删除。普通删除是 DELETE 物理删掉一行；逻辑删除只是把 deleted 置为 1。
 * 加了它之后，MyBatis-Plus 的 delete 语句会自动变成 UPDATE ... SET deleted = 1，
 * 而所有 select 会自动帮你带上 WHERE deleted = 0 —— 你什么都不用写。
 *
 * 面试常问：为什么用逻辑删除而不是物理删除？
 * 答：数据可追溯、可恢复；但要注意查询要带上条件（这就是为什么要框架自动加），
 *     以及唯一索引要考虑 deleted 字段（否则删过的数据会让新数据插不进去）。
 */
@Data
@TableName("dim_region")
public class Region {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 区域编码，如 431300 */
    private String regionCode;

    /** 区域名称，如 娄底市 */
    private String regionName;

    /** 上级区域编码，根节点为 null */
    private String parentCode;

    /** 层级：1 省 / 2 市 / 3 区县 */
    private Integer regionLevel;

    /** 排序号 */
    private Integer sort;

    /** 逻辑删除标记：0 正常 / 1 已删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库有默认值，插入时不用管） */
    private LocalDateTime createTime;

    /** 更新时间（数据库有 ON UPDATE 默认值，更新时不用管） */
    private LocalDateTime updateTime;
}
