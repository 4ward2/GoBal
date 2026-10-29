# 为什么要限流 🤔

限流器用的是令牌桶，核心是这条递推关系：

$$
tokens_{t} = \min(capacity, tokens_{t-1} + rate \cdot \Delta t)
$$

直观理解：桶里最多装 `capacity` 个令牌，每过 `Δt` 秒补 `rate · Δt` 个，
发一次请求扣一个。代码里对应的就是 `TokenBucket.take()`：

```kotlin
fun take(): Boolean {
    refill()
    if (tokens < 1) return false
    tokens -= 1
    return true
}
```

单行公式也很常见，比如判断是否该拒绝请求：

\[ tokens < 1 \]

> 产品那边问过一次："为什么不是简单的滑动窗口？"
> > 因为令牌桶天然支持*突发流量*，滑动窗口在边界附近会抖动。

补充几个边界情况：

- `capacity = 0`：永远拒绝，等价于**关闭**这个接口
- `rate = 0`：桶不会回血，相当于**总开关**
- 时钟回退（比如系统时间被用户改小）：`Δt` 会变成负数
  - 已经在 `refill()` 里加了 `maxOf(0, delta)` 保护

最后贴一下压测结果，`QPS` 从 1200 冲到 4000 时的丢弃率：

| QPS | 丢弃率 |
|---|---|
| 1200 | 0% |
| 2500 | 3.2% |
| 4000 | 41.7% |

链接：[令牌桶算法](https://en.wikipedia.org/wiki/Token_bucket)，
以及文字和网址一样的情况：[https://en.wikipedia.org/wiki/Token_bucket](https://en.wikipedia.org/wiki/Token_bucket)。

今天先到这，有问题随时喊我 😺
