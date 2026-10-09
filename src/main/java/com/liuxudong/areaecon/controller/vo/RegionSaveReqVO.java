package com.liuxudong.areaecon.controller.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 区域新增 / 修改的请求对象（VO = View Object，也有人叫 DTO）。
 *
 * 【为什么不让前端直接把 Region 实体传进来？】
 * 三个理由，面试常问：
 *   ① 安全：实体里有 deleted、createTime、updateTime 这些字段。如果直接用实体接收，
 *      恶意前端可以传 deleted=0 把已删除的数据"复活"，或者伪造 createTime。
 *      VO 只暴露"允许用户填的字段"，这叫"参数白名单"。
 *   ② 解耦：数据库加了个内部字段，不该逼着前端跟着改。
 *   ③ 校验信息：校验注解要写在"请求对象"上，写在实体上会把校验规则和表结构绑死。
 *
 * 【这些注解在哪生效？】
 * 在 Controller 的参数上加了 @Valid（或 @Validated）之后，Spring 才会去读这些注解。
 * 校验不通过会抛 MethodArgumentNotValidException，被 GlobalExceptionHandler 接住，
 * 变成 {"code":400,"message":"regionName: 区域名称不能为空"} 这样的规范响应。
 *
 * 【常用校验注解速查】
 *   @NotNull   不能为 null（数字、对象用这个）
 *   @NotBlank  不能为 null、不能是空串、不能只有空格（字符串用这个）
 *   @NotEmpty  不能为 null、不能是空集合/空串
 *   @Size      长度范围（min / max），可用于字符串和集合
 *   @Min/@Max  数值范围
 *   @Pattern   正则匹配
 *   @Email    邮箱格式
 *
 * 【注意 placeholder = "字段名"】
 * message 里写的是给**用户看**的话，所以要说人话："区域编码不能为空"，
 * 而不是 "must not be blank"。
 *
 * 【关于 id 字段】
 * 新增时不用传（传了也会被忽略），修改时必须传。这里放在同一个 VO 里简化处理，
 * 真实项目里常见两种做法：① 拆成 XxxCreateReqVO / XxxUpdateReqVO 两个类（yudao 系项目就是这么做的）；
 * ② 用一个 VO + 校验分组（@Validated(Group.class)）。你要知道这两种写法都存在。
 */
@Data
public class RegionSaveReqVO {

    /** 主键：新增时为空，修改时必填 */
    private Long id;

    /** 区域编码，如 431300。必须唯一（数据库 uk_region_code 兜底） */
    @NotBlank(message = "区域编码不能为空")
    @Size(max = 32, message = "区域编码长度不能超过 32 位")
    @Pattern(regexp = "^[0-9A-Za-z]+$", message = "区域编码只能是数字或字母")
    private String regionCode;

    /** 区域名称，如 娄底市 */
    @NotBlank(message = "区域名称不能为空")
    @Size(max = 64, message = "区域名称长度不能超过 64 位")
    private String regionName;

    /** 上级区域编码，顶层（省）为 null，所以不加 @NotNull */
    @Size(max = 32, message = "上级区域编码长度不能超过 32 位")
    private String parentCode;

    /** 层级：1 省 / 2 市 / 3 区县 */
    @NotNull(message = "层级不能为空")
    @Min(value = 1, message = "层级只能是 1（省）/ 2（市）/ 3（区县）")
    @Max(value = 3, message = "层级只能是 1（省）/ 2（市）/ 3（区县）")
    private Integer regionLevel;

    /** 排序号，可以不传（不传按 0 处理） */
    @Min(value = 0, message = "排序号不能为负数")
    private Integer sort;
}
