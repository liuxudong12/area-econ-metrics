"""
校验导出的 xlsx：sheet 数量、每个 sheet 的数据行数、表头、首行样例。

【为什么要用 read_only=True】
openpyxl 默认会把整个工作簿的所有单元格对象加载进内存。
一个 48 万行 × 9 列的文件大约是 430 万个 Cell 对象，在普通笔记本上直接吃掉 2~3GB。
read_only=True 用的是流式解析器，只保留当前行，内存几乎不涨 ——
和我们在 Java 端"游标分页"是同一个思路：**不要一次把所有数据搬进内存。**

用法：
    python tools/verify-export.py data/export/xxx.xlsx
"""
import sys
from openpyxl import load_workbook


def check(path: str) -> None:
    wb = load_workbook(path, read_only=True, data_only=True)
    print(f"文件      : {path}")
    print(f"sheet 数  : {len(wb.sheetnames)}")
    total = 0
    for name in wb.sheetnames:
        ws = wb[name]
        rows = ws.max_row or 0
        # read_only 模式下 max_row 可能为 None，用实际遍历数一遍更稳
        data_rows = 0
        header = None
        first = None
        for i, row in enumerate(ws.iter_rows(values_only=True)):
            if i == 0:
                header = row
            else:
                # 第 1 行是表头，之后每一行都是数据 —— 包括第 2 行（i==1）。
                # 这里曾经写成 elif i == 1 单独处理，结果少算了一行，
                # 导出的行数和 COUNT(*) 差 1，排查了十几分钟。
                # 教训：**统计行数时，"跳过表头"和"跳过首行数据"是两件事，别写在一起。**
                data_rows += 1
                if first is None:
                    first = row
        total += data_rows
        print(f"  sheet[{name}] 数据行={data_rows}")
        if header:
            print(f"      表头={list(header)}")
        if first:
            print(f"      首行={list(first)}")
    print(f"合计数据行: {total}")
    wb.close()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python tools/verify-export.py <xlsx路径>")
        sys.exit(1)
    check(sys.argv[1])
