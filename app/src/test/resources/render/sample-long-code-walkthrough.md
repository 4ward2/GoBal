# 加了重试装饰器

新文件：`app/src/main/kotlin/.../net/RetryingCall.kt`，别跟 `*.kt` 里别的重试逻辑搞混了——
这是唯一一处，其余地方都改成调用它。

```kotlin
class RetryingCall<T>(
    private val maxAttempts: Int,
    private val baseDelayMs: Long,
    private val block: suspend () -> T,
) {
    suspend fun execute(): T {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (error: IOException) {
                lastError = error
                attempt++
                val delay = baseDelayMs * (1 shl (attempt - 1))
                delay(delay)
            }
        }
        throw checkNotNull(lastError)
    }
}
```

用法很短：

```kotlin
val result = RetryingCall(3, 200) { api.fetch() }.execute()
```

改动点：

- 指数退避，不是固定间隔
- 只重试 `IOException`，业务异常不重试
- 最后一次失败会把原始异常原样抛出，不包一层

受影响的调用点：

1. `ModelGateway.chat()`
2. `ModelGateway.chatStream()`
3. `ProviderRequests.listModels()`

验证过的场景：

- [x] 第一次就成功
- [x] 前两次超时，第三次成功
- [x] 三次都失败，异常原样冒出去

```bash
./gradlew :app:testFossDebugUnitTest --tests "*.RetryingCallTest"
```

没有动 `*.gradle.kts` 或者 `*.md`，纯 Kotlin 改动。
