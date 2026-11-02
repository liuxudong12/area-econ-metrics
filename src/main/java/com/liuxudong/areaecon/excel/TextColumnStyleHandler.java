package com.liuxudong.areaecon.excel;

import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.metadata.holder.WriteSheetHolder;
import com.alibaba.excel.write.metadata.holder.WriteWorkbookHolder;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.HashSet;
import java.util.Set;

/**
 * 把指定的列设成「文本格式」。
 *
 * ==========================================================================
 * 【要解决的问题：Excel 会把 2027-01 自动变成日期】
 * ==========================================================================
 * 这是 Excel 导入功能里最经典、最高频的坑，几乎每个做过的人都踩过。
 *
 * 现场还原：
 *   1. 用户下载模板，在"周期编码"那格输入 2027-01
 *   2. 一按回车，格子里的字变成了 **2027/1/1**
 *   3. 用户可能没在意（"反正看起来差不多"），保存上传
 *   4. 我们按字符串读，拿到的是 "2027/1/1"
 *   5. 数据库里存的是 "2027-01"，对不上 → 唯一索引不冲突，但统计查不出来
 *   6. 用户投诉："我明明填对了，为什么报表里没数据？"
 *
 * 根因在于：Excel 的单元格分"类型"。默认是"常规"，输入 2027-01 时
 * Excel 认为你想表达一个日期，于是自动转换并把**显示格式**也改成了日期。
 * 转换发生在用户输入的那一刻，**跟我们的程序没有任何关系** ——
 * 我们收到的就已经是日期值了（序列号 46023 之类），再怎么处理也恢复不出原字符串。
 *
 * 唯一的解法是**在用户输入之前**就把格子标成"文本"类型。
 *
 * ==========================================================================
 * 【为什么只设"列默认格式"就够了 —— 一个我实测过的问题】
 * ==========================================================================
 * 一开始我担心两件事：列默认格式管不到用户新增的行、也管不到示例行的单元格。
 * 于是写了个 CellWriteHandler 逐个单元格设样式。实测下来发现它被 EasyExcel
 * 内置的样式策略覆盖了（写出来的单元格是 General），但**发现根本不需要它**。
 *
 * 实测证据（xlsx 解压后看 sheet1.xml）：
 *   列定义  <col min="3" max="3" style="1"/>             style=1 → numFmtId=49（文本）
 *   示例行  <c r="C2" s="3" t="inlineStr"><t>2027-01</t></c>
 *
 * 第二行那个 `t="inlineStr"` 说明了关键：**程序写进去的值本身就是字符串类型**，
 * 不是"数字 + 日期格式"。Excel 打开时看到的是字符串，压根不会去转换。
 * 所以示例行即使显示格式是 General，也永远是 2027-01。
 *
 * 那为什么还必须要 setDefaultColumnStyle？
 *   因为它管的是**用户之后新增的单元格** —— 那些格子现在还不存在，
 *   没有样式可言，用的是列的默认格式。用户在第 3 行敲 2027-01 时，
 *   会乖乖按文本处理。**这才是唯一真正需要解决的问题。**
 *
 * 结论：只在 sheet 级别设一次列格式，够了。多写的那层 handler 是无效代码，删掉。
 * （这里有条经验：**先把问题验证清楚，再决定要不要写那层代码**。
 *   我如果一开始就去实测，能省掉半小时。）
 *
 * ==========================================================================
 * 【为什么 "@" 就是文本格式】
 * ==========================================================================
 * Excel 内置格式里，"@" 是"文本"的格式代码（相当于把整列当成字符串显示），
 * 它的内置编号是 49。用 getFormat("@") 拿到它，比记 49 这个魔数可读性好得多。
 */
public class TextColumnStyleHandler implements SheetWriteHandler {

    /** 有哪些列要设成文本 */
    private final Set<Integer> textColumns = new HashSet<>();

    public TextColumnStyleHandler(int... columns) {
        for (int c : columns) {
            textColumns.add(c);
        }
    }

    /**
     * sheet 创建完成、还没有写数据的时候回调 —— 正好是设列格式的时机。
     */
    @Override
    public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder,
                                 WriteSheetHolder writeSheetHolder) {
        if (textColumns.isEmpty()) {
            return;
        }
        Workbook workbook = writeWorkbookHolder.getWorkbook();
        // 所有列共用一个样式对象。POI 对每个 workbook 的样式数量有上限
        // （xlsx 是 64000 个），能复用就复用 —— 这是用 POI 的基本习惯。
        CellStyle textStyle = workbook.createCellStyle();
        textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));

        for (Integer col : textColumns) {
            writeSheetHolder.getSheet().setDefaultColumnStyle(col, textStyle);
        }
    }
}
