package com.liuxudong.areaecon.excel;

import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.metadata.holder.WriteSheetHolder;
import com.alibaba.excel.write.metadata.holder.WriteWorkbookHolder;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * 导出文件的 sheet 级样式：列宽 + 表头冻结 + 编码列文本化。
 *
 * ==========================================================================
 * 【为什么这三件事要放在一个 handler 里】
 * ==========================================================================
 * 因为它们都是"整列/整表"级别的设置，而且都必须在「sheet 建好、数据还没写」的
 * 那一刻完成 —— 也就是 {@link #afterSheetCreate} 这个回调。
 * 分散成三个 handler 只会让调用方多写三行注册代码，没有任何好处。
 *
 * ==========================================================================
 * 【为什么要冻结表头】
 * ==========================================================================
 * 导出 48 万行的时候，用户往下滚到第 3 万行，表头早就看不见了。
 * 面对 9 列数字，他根本分不清第 6 列是"周期编码"还是"指标值"。
 * 一个冻结窗格只要一行代码，但这是"这个文件能不能直接用"的分界线。
 *
 * 真实项目里这类"最后一公里"的细节，往往比技术方案本身更影响评价 ——
 * 因为技术方案别人看不见，文件难用是一眼就能感受到的。
 *
 * ==========================================================================
 * 【为什么编码列必须是文本格式（和导入那边同一个坑）】
 * ==========================================================================
 * 区域编码 430102、指标编码 1-1、周期编码 2027-01 —— 这些"看起来像数字/日期"的值，
 * 如果落在常规格式的格子里：
 *   · 2027-01 会被 Excel 显示成 2027/1/1
 *   · 043102 这种带前导 0 的编码会被吃掉 0，变成 43102
 *   · 1-1 会被识别成日期的一月一日
 *
 * 更坑的是：**导出文件经常被当成新的导入模板用**（用户导出来改完再传回去）。
 * 一旦编码列被转换，回传时就全错了，而且我们的报错信息只会说"区域编码不存在"，
 * 用户看着 Excel 里明明白白写着 43… 会彻底懵。
 *
 * 所以导出和导入必须用同一套约定：**所有编码列都是文本。**
 */
public class ExportSheetStyleHandler implements SheetWriteHandler {

    /** 需要设为文本格式的列索引（0 开始） */
    private final int[] textColumns;

    /** 各列宽度（字符数）。长度不足的部分用默认宽度 */
    private final int[] columnWidths;

    public ExportSheetStyleHandler(int[] textColumns, int[] columnWidths) {
        this.textColumns = textColumns;
        this.columnWidths = columnWidths;
    }

    @Override
    public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder,
                                 WriteSheetHolder writeSheetHolder) {
        Workbook workbook = writeWorkbookHolder.getWorkbook();

        // ---------- ① 列宽 ----------
        if (columnWidths != null) {
            for (int i = 0; i < columnWidths.length; i++) {
                // setColumnWidth 的单位是 1/256 个字符宽。
                // 这个是 POI 的历史遗留设计（要精确到像素级的宽度），
                // 所以每列都要 × 256 —— 忘了乘就得到一条几乎看不见的细缝，
                // 而且不报错，只是"导出的文件很窄"。属于典型的静默错误。
                writeSheetHolder.getSheet().setColumnWidth(i, columnWidths[i] * 256);
            }
        }

        // ---------- ② 冻结首行 ----------
        // createFreezePane(colSplit, rowSplit)：冻结前 rowSplit 行、前 colSplit 列。
        // 我们要的是"滚动时表头固定"，所以冻结 1 行、0 列。
        writeSheetHolder.getSheet().createFreezePane(0, 1);

        // ---------- ③ 编码列文本化 ----------
        if (textColumns != null && textColumns.length > 0) {
            // 整个 workbook 复用一个样式对象。
            // POI 对单 workbook 的样式数量有上限（xlsx 是 64000），
            // 每个单元格都 createCellStyle 一次会直接撞上限报错。
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int col : textColumns) {
                writeSheetHolder.getSheet().setDefaultColumnStyle(col, textStyle);
            }
        }
    }
}
