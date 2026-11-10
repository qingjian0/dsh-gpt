# DSH-GPT Runtime 集成验收清单

本清单区分静态代码测试与真实 DSH 宿主验收。完成静态测试不得自动勾选宿主集成项目。

## A. 包与插件生命周期
- [ ] 使用 Node.js 24 安装/解析插件包。
- [ ] DSH Profile 能解析 manifest 与 cordis.patch.yml。
- [ ] Plugin 正常加载，所有 inject 服务均可用。
- [ ] Plugin 卸载时 Provider、工具和提示词段落被正确清理。
- [ ] 重复加载/卸载不会产生重复工具或重复 Provider。

## B. Skills
- [ ] Skill Registry 中发现全部 7 个 bundled skills。
- [ ] list() 只返回候选元数据，不提前读取正文。
- [ ] 模型可通过官方 skill 工具按需加载完整正文。
- [ ] AbortSignal 取消加载时能及时停止。
- [ ] 本地 filesystem Provider 与 bundled Provider 共存，优先级符合 DSH 规则。

## C. 模式系统
- [ ] gpt_mode_list 返回全部 9 种模式。
- [ ] 用户显式指定模式时覆盖默认模式与自动路由。
- [ ] 自适应路由对工程、研究、批量、并行、故障和简单任务选择合理。
- [ ] 模式只影响工作策略，不改变宿主工具权限。

## D. 工作流状态机
- [ ] 创建任务后进入 understand。
- [ ] 阶段按 understand → inspect → plan → execute → verify → deliver 推进。
- [ ] verify 可回退 execute，其他非法跳转被拒绝。
- [ ] completed 在 deliver 之前必须被拒绝。
- [ ] blocked、failed、cancelled 均可记录并可查询。
- [ ] 决策摘要、证据、阻塞项和验收标准正确保存。
- [ ] 插件重启后内存态清空这一行为符合当前文档声明。

## E. 真实任务验收
- [ ] 研究任务完成来源交叉验证并输出证据。
- [ ] 工程任务能调用仓库工具、执行测试并核对 Git diff。
- [ ] 批量任务按依赖分组，不并行执行相互冲突的写操作。
- [ ] 故障恢复任务先定位根因，再最小修复并回归。
- [ ] 敏感或不可逆操作仍受 DSH 原生审批和权限控制。
- [ ] 最终报告区分已完成、未完成、失败和未验证。

## 验收记录
每次验收记录 DSH 版本、Profile、Node 版本、操作系统、插件 commit、测试任务、工具清单、日志引用和结果。不得把模拟测试写成真实宿主通过。
