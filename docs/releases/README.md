# 发布更新日志

发布日志按版本生成，不在普通功能提交时创建。只有用户明确要求“准备发布”
或“发版”时，AI 才根据上一个版本标签到当前提交的变化生成一份新的
Markdown 文件。

GitHub Release 的正文来自固定文件：

```text
docs/releases/<tag>.md
```

例如发布 `v1.0.6` 时，必须存在：

```text
docs/releases/v1.0.6.md
```

发布工作流会在打标签后检查该文件。文件缺失时，任务会直接失败，避免发布一份没有更新内容的 Release。

## 每次发布前

1. 由 AI 找到上一个版本标签：

```bash
git describe --tags --abbrev=0
```

2. 由 AI 查看两个版本之间的提交和文件变化：

```bash
git log --oneline <上一个标签>..HEAD
git diff --stat <上一个标签>..HEAD
```

3. AI 以 [TEMPLATE.md](TEMPLATE.md) 为格式，为新版本生成
   `docs/releases/vX.Y.Z.md`。

4. 按用户能理解的语言整理更新内容。不要只复制英文 commit message；需要
   合并同类项，并说明行为变化、修复结果和升级注意事项。

5. 检查相对链接、版本号、APK 名称和本地图片路径。

6. 用户明确要求发布后，提交更新日志并创建、推送标签：

```bash
git tag v1.0.6
git push origin main --tags
```

GitHub Actions 会构建 APK，并把对应 Markdown 文件完整显示在 Release 页面。

如果用户只是要求生成或修改更新日志，AI 只修改 Markdown 文件，不提交、
不打标签，也不推送。

## 内容要求

- 新增：用户能看到的新功能。
- 修复：明确问题现象和修复后的结果。
- 优化：体验、性能、界面和兼容性改进。
- 升级注意：数据、权限、签名或安装方式变化；没有时写“无”。
- 完整变更：加入上一个标签到当前标签的 GitHub compare 链接。

如果功能较多，可以增加“重点更新”“已知限制”等小节，但不要使用大量内部术语。
