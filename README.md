# VOID 增强（plugin-void）

Typecho 主题 [VOID](https://github.com/AlanDecode/Typecho-Theme-VOID) 配套插件（`VOID-3.5.1/2.配套插件/VOID`）的 Halo 移植。

移植者：AwKings。原作者：熊猫小A（https://blog.imalan.cn）。

移植说明（主题 + 插件一起讲）：https://blog.sanmizu.com/archives/halo-theme-void
主题仓库：https://github.com/AwKings/halo-theme-void

原插件是 PHP + 直接改数据库（给 `contents` 加 `likes/wordCount/viewsNum`，给 `comments` 加 `likes/dislikes`，另建 `votes` 表）。Halo 没有这些字段，所以这里改成：

- 投票数据存成 Halo 自定义模型 `VoidVote`（`void.halo.run/v1alpha1`）
- 统计类能力尽量复用 Halo 内核（浏览量、文章点赞计数），插件只补内核没有的部分（评论赞踩、互动明细）

## 功能对照

| Typecho 插件能力 | Halo 侧实现 | 状态 |
| --- | --- | --- |
| 文章点赞 `contents.likes` | `VoidVote` 记录 + 内核 upvote 计数 | ✅ |
| 评论赞踩 `comments.likes/dislikes` | `VoidVote` 记录，按目标聚合 | ✅ 后端 |
| 按 IP 去重、不允许改票 | 目标 + IP 派生确定性 `metadata.name` | ✅ |
| 互动记录表 `votes` | `VoidVote` 模型（含 IP、UA、标题、链接） | ✅ |
| 后台「互动」面板 | Console 菜单「工具 → 访客互动」 | ✅ |
| 浏览量 `viewsNum` | Halo 内核 `post.stats.visit` | ✅ 无需移植 |
| 字数统计 `wordCount` | 插件统计正文并写入文章注解 `void_words`（汉字按字、拉丁字母数字按词，与主题前端同口径）；启动时全量补算、保存时增量更新，卡片与归档页读这个注解 | ✅ |
| 评论归属地（17monipdb） | 未移植（需要 IP 库与后台 UI 扩展） | ❌ |
| 图片宽高后缀 `#vwid/#vhei` | `ImageSizeProbe` 渲染时探测宽高（本地读文件头、远程取 HTTP 头并缓存），相册做 justified 排版 | 🔁 |
| 图片排版 `[photos]` | `VoidContentParser` 服务端解析 + 宽高探测 | ✅ |
| 提示块 `[notice]` | `VoidContentParser` | ✅ |
| 注音 `{{字:音}}` | `VoidContentParser` | ✅ |
| 友链列表 `[links]` | `VoidContentParser` | ✅ |
| 标题锚点 `toc_N` | `VoidContentParser` | ✅ |

## 正文扩展语法

插件实现 `ReactivePostContentHandler`，在正文输出到主题前做一遍解析，
因此这些语法在 Markdown 与富文本编辑器里都能用，且不依赖前端 JS。
解析前会保护 `<pre>` / `<code>` 区域，代码示例里的同名标记不会被误伤；
解析抛异常时原样返回正文，不会让页面 500。

| 写法 | 结果 |
| --- | --- |
| `[photos]` … `[/photos]` 包住若干图片 | `.photos` 相册网格 + fancybox 灯箱 |
| `[notice]文本[/notice]` | `<p class="notice">` 提示块 |
| `{{漢字:かんじ}}` | `<ruby>` 注音 |
| `[links]` 内每行 `[名称](url)+(缩略图)` | 友链卡片列表 |
| 普通图片 | 自动包 `<figure>` + 灯箱 |
| `h2`–`h6` | 自动补 `id="toc_N"` |
| ` ```c++ ` / ` ```c# ` / ` ```f# ` / ` ```objective-c ` | 归一为 Prism 语言名 |

配套主题的 `templates/assets/css/void-content.css` 提供这些结构的样式。

## API

公开（匿名可访问，已通过角色模板聚合到 `anonymous`）：

```http
POST /apis/api.void.halo.run/v1alpha1/votes
Content-Type: application/json

{"subjectType":"post|comment","subjectName":"<metadata.name>","action":"up|down","title":"...","url":"..."}
```

返回 `{"code":200|302|403|400,"msg":"...","up":n,"down":n}`，语义与原插件一致：
`200` 新增成功，`302` 已经投过，`403` 不允许改票，`400` 参数错误。

```http
GET /apis/api.void.halo.run/v1alpha1/vote-stats?subjectType=comment&names=a,b,c
```

返回 `{"items":[{"name":"a","up":1,"down":0}]}`。

Console（需要 `plugin-void-view-activity` 角色）：

```http
GET /apis/console.api.void.halo.run/v1alpha1/activity?page=1&size=20
```

## 数据模型

```yaml
apiVersion: void.halo.run/v1alpha1
kind: VoidVote
metadata:
  name: v-<sha256(type|subjectName|ip)[0:32]>
spec:
  subjectType: post | comment
  subjectName: <目标 metadata.name>
  action: up | down
  ip: 1.2.3.4
  userAgent: Mozilla/5.0 ...
  title: 文章标题（冗余，供互动列表展示）
  url: 目标链接
```

`metadata.name` 由 `类型 + 目标 + IP` 派生，天然幂等：同一 IP 重复投票命中同一条记录，直接返回 `302`。

## 构建

```bash
export JAVA_HOME=/opt/data/opt/jdk-21.0.13+9
export PATH=/opt/data/opt/npm-global/bin:$JAVA_HOME/bin:$PATH
NODE_ENV=development pnpm install --dir ui   # 首次
./gradlew build -x test
```

产物：`build/libs/plugin-void-1.0.0.jar`。

## 安装

Console → 插件 → 安装 → 上传 jar → 启用。或走 API：

```bash
POST /apis/api.console.halo.run/v1alpha1/plugins/install   (multipart file)
PUT  /apis/api.console.halo.run/v1alpha1/plugins/plugin-void/plugin-state  {"enable":true}
```

升级用 `POST /apis/api.console.halo.run/v1alpha1/plugins/plugin-void/upgrade`，改完记得
`PUT /apis/api.console.halo.run/v1alpha1/plugins/plugin-void/reload`。

## 已知限制

- Halo 官方评论组件（PluginCommentWidget 3.x）是 Lit + 多层 shadow DOM，评论赞踩按钮**暂未**注入到前台评论区；后端接口已就绪，主题侧可自行渲染评论列表后调用。
- 评论归属地依赖 IP 库，未移植。
