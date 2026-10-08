# 04 · Git 与远程仓库上手指南

> 解决一个最容易被误解的问题：**`git commit` 只存在你自己电脑上，不会自动跑到 GitHub。**

---

## 一、四个概念，一张图记住

```
工作区  ──git add──▶  暂存区  ──git commit──▶  本地仓库  ──git push──▶  远程仓库
（你改的    ───────▶  （准备       ────────▶  （.git 目录，  ───────▶  （GitHub /
 文件）     挑选要提交的）                      存在你硬盘上）              Gitee）
                │                    │                      │
            本地，不联网          本地，不联网           需要联网 + 账号
```

**关键：前三个区域都在你的电脑里。** 只做 `add` 和 `commit`，GitHub 上什么都看不到。

---

## 二、完整流程（按顺序执行）

### 第 1–3 步：本地存档（不联网）

在 IDEA 底部的 `Terminal` 标签页，确认当前目录是项目根目录，然后：

```bash
git init                     # ① 在当前目录创建本地仓库（生成一个 .git 隐藏文件夹）
git add .                    # ② 把改动放进暂存区
git commit -m "chore: 初始化 Spring Boot 项目骨架，跑通 /ping 接口"   # ③ 存档
```

**`git init` 会创建在哪？**  
就创建在**你执行命令时所在的目录**。所以必须先 `cd` 到项目根目录  
（也就是有 `pom.xml` 的那一层）。如果建错了位置，删掉多出来的 `.git` 文件夹重来即可。

```bash
# 验证一下
git log --oneline            # 应该看到你刚提交的那一条
git ls-files                 # 看看哪些文件进了仓库
```

**`git ls-files` 里不应该出现 `target/` 和 `.idea/`。** 如果出现了，说明 `.gitignore` 没生效。

### 第 4–6 步：推送到远程（需要联网）

这三步是 `commit` 之外的额外动作：

```bash
# ④ 先把本地分支改名叫 main（GitHub 默认叫 main，老的叫 master）
git branch -M main

# ⑤ 关联远程仓库地址（这个地址在网站上建仓库后会给你）
git remote add origin https://github.com/你的用户名/area-econ-metrics.git

# ⑥ 推送上去
git push -u origin main
```

第一次 push 会弹窗要你登录 GitHub（浏览器授权或输入账号密码 / Token）。

---

## 三、GitHub 还是 Gitee？怎么选

|                | GitHub           | Gitee（码云） |
| -------------- | ---------------- | --------- |
| 技术圈认可度         | 更高，互联网公司面试官默认看这个 | 国内企业普遍认可  |
| 国内访问速度         | 不稳定，有时需要梯子       | 快，稳定      |
| 注册             | 需要邮箱验证，偶尔卡       | 快         |
| 简历上的链接能否被面试官打开 | **有风险**          | 基本不会出问题   |

**建议：两个都建，代码双份推。** 这样既保证面试官一定能打开，又能覆盖"更认 GitHub"的面试官。

配置成双远程，一个本地仓库同时推两边：

```bash
# origin 指向 GitHub
git remote add origin https://github.com/你的用户名/area-econ-metrics.git
# gitee 指向 Gitee
git remote add gitee  https://gitee.com/你的用户名/area-econ-metrics.git

# 推 GitHub
git push -u origin main
# 推 Gitee
git push -u gitee main
```

以后每次推送，两条命令各跑一次（或者写个小脚本）。

> 如果 GitHub 连接困难，先只搞 Gitee，**别让这件事卡住进度**。等你后面有时间再补 GitHub。

---

## 四、怎么创建远程仓库

### GitHub

1. 登录 [github.com](https://github.com) → 右上角 `+` → `New repository`
2. **Repository name** 填 `area-econ-metrics`
3. **Description** 填 `区域经济运行指标服务 - Spring Boot 3 + MyBatis-Plus + Redis`
4. 选 **Public**（公开，面试官才能看到）
5. ⚠️ **下面三个勾全部不要勾**（`Add a README file` / `.gitignore` / `license`）  
   ——因为你本地已经有代码了，勾了会造成两个不相关的历史，push 时会冲突
6. 点 `Create repository`
7. 创建完页面上会显示一段 `git remote add origin https://github.com/...`，把那个地址复制下来

### Gitee

流程几乎一样：登录 [gitee.com](https://gitee.com) → 右上角 `+` → `新建仓库`。  
同样选**公开**，同样**不要勾**「使用 Readme 文件初始化这个仓库」。

---

## 五、日常最常用的 5 条命令

以后每天只需要这几条：

我把仓库建好了接下来怎么做

**commit message 的写法建议**（面试官会看）：

| 前缀       | 用途    | 例子                             |
| -------- | ----- | ------------------------------ |
| `feat:`  | 新功能   | `feat: 新增指标值分页查询接口`            |
| `fix:`   | 修 bug | `fix: 修复周期排序在跨年时错乱`            |
| `perf:`  | 性能优化  | `perf: 为大屏聚合查询添加联合索引，耗时下降 89%` |
| `docs:`  | 文档    | `docs: 补充 README 中的设计说明`       |
| `chore:` | 杂项    | `chore: 初始化项目骨架`               |
| `test:`  | 测试    | `test: 补充 Excel 异步导入的幂等测试`     |

**别写"update""修改一下""111"这种。** 十四条清晰的 commit message 本身就是你专业度的证明。

---

## 六、常见报错对照表

| 报错                                                                            | 原因                         | 怎么解决                                                                                      |
| ----------------------------------------------------------------------------- | -------------------------- | ----------------------------------------------------------------------------------------- |
| `fatal: not a git repository`                                                 | 当前目录没有 `.git`              | 先 `cd` 到项目根目录，再 `git init`                                                                |
| `error: remote origin already exists`                                         | 已经关联过 origin 了             | `git remote set-url origin 新地址` 覆盖，或先 `git remote remove origin`                          |
| `Updates were rejected because the remote contains work that you do not have` | 建远程仓库时勾了 README，远程有本地没有的提交 | 见下方处理                                                                                     |
| `fatal: refusing to merge unrelated histories`                                | 同上                         | 见下方处理                                                                                     |
| `Permission denied (publickey)`                                               | 用 SSH 地址但没配密钥              | 换成 HTTPS 地址（`https://` 开头）                                                                |
| `OpenSSL SSL_read: Connection was reset`                                      | 网络问题（GitHub 常见）            | 重试，或改用 Gitee                                                                              |
| `remote: Support for password authentication was removed`                     | GitHub 不再支持密码推送            | 去 GitHub `Settings → Developer settings → Personal access tokens` 生成 Token，推送时密码位置填 Token |

### 建仓库时误勾了 README 的补救

```bash
# 把远程的改动拉下来并合并（允许合并不相关历史）
git pull origin main --allow-unrelated-histories

# 如果出现冲突，手动解决后：
git add .
git commit -m "merge: 合并远程初始提交"
git push -u origin main
```

---

## 七、安全检查（push 之前一定要做）

```bash
# 1. 看清单有没有把不该传的传上去
git ls-files

# 2. 检查有没有密码、密钥之类的东西
git ls-files | grep -iE "\.env|secret|password|credential|\.key|\.pem"
```

**`application.yml` 里的数据库密码怎么办？**

本地练习库的 `Root@1234` 传上去没什么风险，但**养成习惯**：真项目的密码绝不进 Git。

推荐做法（做完第 1 天可以顺手改）：

```yaml
spring:
  datasource:
    password: ${DB_PASSWORD:Root@1234}   # 优先读环境变量，没有就用默认值
```

或者把密码单独放 `application-local.yml`（`.gitignore` 里我已经把这行加好了）。

---

## 八、做完这些，你的验收标准

- [ ] `git log --oneline` 能看到至少一条自己的提交
- [ ] `git ls-files` 里没有 `target/` 和 `.idea/`
- [ ] 远程仓库页面上能看到你的代码
- [ ] 把仓库地址填进简历，用**无痕窗口**（不登录）打开能正常看到内容
