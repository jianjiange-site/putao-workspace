# Dating 项目文档总览

> 本目录用于组织 vibe-coding 开发过程中的所有技术文档

## 目录结构

```
doc/
├── README.md           # 本文件，文档入口
├── specs/              # 原始需求/设计文档
│   └── post-service-design.md
├── plans/              # 实现计划（vibe-coding 前生成）
└── progress/           # 进度日志（session 后追加）
    ├── post-service-log.md
    └── infra-bootstrap-log.md
```

## 当前进度

| 服务 | 状态 | 文档 |
|------|------|------|
| post-service | 规划中 | [设计文档](./specs/post-service-design.md) |
| 基建（脚手架 / proto / 凭据） | 进行中 | [日志](./progress/infra-bootstrap-log.md) |

## 使用指南

### Vibe-Coding Session 开始前

1. 阅读 `specs/` 目录下对应服务的设计文档
2. 如果是首次实现，创建 `plans/YYYY-MM-DD-<service>-p1.md` 计划
3. 阅读 `progress/<service>-log.md` 了解历史进度

### Session 结束后

1. 在 `progress/<service>-log.md` 追加本轮完成内容
2. 如果遇到设计文档未覆盖的问题，在 progress 中记录偏差
3. 如果需要调整设计，修改 specs/ 后记录变更原因

### AI 辅助开发提示

当你开始一个新的 vibe-coding session 时，可以这样告诉 AI：

```
请先阅读 doc/progress/<service>-log.md 了解当前进度，
然后阅读 doc/specs/<service>-design.md 了解设计细节，
接着开始实现计划 doc/plans/YYYY-MM-DD-<service>.md 中的内容。
```

---

> 最后更新：2026-07-14
