# Daily sync 📋

今天把三件事都收尾了，细节如下。

## 1. 清单层级

- 后端
  - `AuthPlugin`
    - 单测补了 4 条
    - 文档还没写
  - `RateLimiter`
    - 配置项挪到 `ServerConfig`
- 前端
  - 设置页
    - 加了「清空缓存」按钮
  - 聊天页
    - 修了滚动跳动

有序版本（同样带层级）：

1. 先合并 `AuthPlugin`
   1. 跑一遍全量单测
   2. 确认 lint 干净
2. 再合并 `RateLimiter`
2) 这一步用了另一种写法（圆括号），应该一样能认出来

## 2. 待办

- [x] 合并后端两个 PR
- [X] 通知到群里
- [ ] 下周三前补文档

## 3. 快速验证

```
ls
```

跑完没问题。顺带一句：别把 `2 * 3` 当成斜体，也别动 `snake_case_name`，
`*.kt` 这种通配符同理不该变样式。

对比一下旧接口和新接口：

| 接口 | 旧延迟 | 新延迟 |
|---|---|---|
| `/login` | 480ms | 120ms |
| `/profile` | 90ms | 88ms |

截图：![登录页耗时对比](https://example.com/shot1.png)

参考链接：[文字和网址相同的例子](https://example.com/same)
不过这条不一样：[https://example.com/same](https://example.com/same)

---

> 组里有人问："`RateLimiter` 会不会跟 `AuthPlugin` 抢锁？"
> > 不会，两个用的是完全独立的 `Mutex`。

就这些，明天继续～ 🚀
