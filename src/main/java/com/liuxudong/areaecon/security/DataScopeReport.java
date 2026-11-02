package com.liuxudong.areaecon.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.apache.ibatis.annotations.Mapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * "漏网之鱼扫描器" —— 数据权限落地时最实用、也最容易被忽略的一个东西。
 *
 * ============================================================================
 * 一、为什么必须有它
 * ============================================================================
 * 数据权限用的是"显式注解"方案（理由见 {@link DataScope} 的注释）。
 * 显式方案的代价就是：**漏标 = 漏权限**。
 *
 * 而"漏标"这件事有三个特点，让它必须靠工具来兜：
 *   ① 不会报错。SQL 正常执行、接口 200、数据看起来也完整 —— 只是多了别人家的。
 *   ② 代码评审很难发现。看 diff 时它看起来就是一个正常的 Mapper 方法，
 *      缺一个注解不会让人警觉（缺的是"没有的东西"，人眼对缺失不敏感）。
 *   ③ 换个写法就会漏。{@code selectPage} 被 {@code @Select} 改写成自定义方法，
 *      重构的时候注解很容易跟着旧方法一起被删掉。
 *
 * 所以安全评审的常规动作是：**把"应该受保护"和"实际受保护"两张清单拉出来对一遍**。
 * 这个类就是生成那张清单的。
 *
 * ============================================================================
 * 二、它能查什么、不能查什么（老实说清楚）
 * ============================================================================
 *   能查：Mapper 接口里哪些方法标了 @DataScope，哪些看起来是查询却没标。
 *   不能查：XML 里写了但接口里没声明的方法（这种本身就是 bug）；
 *           也不判断"这个方法该不该标" —— 那是业务语义，机器猜不出来。
 *
 * 所以它的定位是**提示**，不是**断言**。
 * 输出里那些 SUSPECT 需要人一条条看过：确实是漏洞的补注解，
 * 确实不该受约束的（比如查字典表）在文档里记一笔理由。
 * **"检查工具给结论，人给理由"** —— 这是安全类工具的正确分工。
 */
@Slf4j
@Component
public class DataScopeReport {

    /** 只扫这一个包。写死包名是刻意的：范围小、结果确定、不会误伤别的模块 */
    private static final String MAPPER_PACKAGE = "com.liuxudong.areaecon.mapper";

    /** 方法名以这些前缀开头的，认为是"查询"，没标 @DataScope 就值得怀疑 */
    private static final String[] QUERY_PREFIXES = {
            "select", "count", "list", "query", "get", "find", "page"
    };

    /**
     * 一条扫描结果。
     *
     * @param verdict PROTECTED（已标）/ SUSPECT（像查询但没标）/ WRITE（写操作，本就不该按行过滤）
     */
    public record Item(String mapper, String method, String verdict, String note) {
    }

    public List<Item> scan() {
        List<Item> result = new ArrayList<>();

        // Spring 提供的类路径扫描器。第一个参数 false = 不使用默认过滤器
        // （默认过滤器会把接口排除掉，只留具体类 —— 而我们要的正是接口）。
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // ★ 这一行是必须的：默认实现要求"独立且是具体类"，
                        //   Mapper 全是 interface，不重写的话扫出来是空的。
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Mapper.class));

        Set<org.springframework.beans.factory.config.BeanDefinition> defs =
                scanner.findCandidateComponents(MAPPER_PACKAGE);

        for (org.springframework.beans.factory.config.BeanDefinition def : defs) {
            String className = def.getBeanClassName();
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException e) {
                log.warn("[权限扫描] 类加载失败，跳过：{}", className);
                continue;
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge() || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                boolean marked = m.getAnnotation(DataScope.class) != null;
                String name = m.getName();

                String verdict;
                String note;
                if (marked) {
                    DataScope ds = m.getAnnotation(DataScope.class);
                    verdict = "PROTECTED";
                    note = "按 " + (ds.alias().isEmpty() ? ds.column() : ds.alias() + "." + ds.column())
                            + " 过滤";
                } else if (looksLikeQuery(name)) {
                    verdict = "SUSPECT";
                    note = "看起来是查询却没标 @DataScope —— 请确认它是否应该受数据权限约束";
                } else {
                    verdict = "WRITE";
                    note = "写操作。数据权限在这里的形态是「改/删之前先校验归属」，本项目未实现";
                }
                result.add(new Item(clazz.getSimpleName(), name, verdict, note));
            }
        }

        result.sort(Comparator.comparing(Item::mapper).thenComparing(Item::method));
        return result;
    }

    private boolean looksLikeQuery(String methodName) {
        String lower = methodName.toLowerCase();
        for (String p : QUERY_PREFIXES) {
            if (lower.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
