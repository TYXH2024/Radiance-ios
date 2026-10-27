# rt-probe v1.2 现场判读：闪退与探针无关，根因锁定 Vulkan 渲染档

> 现场：2026-10-27 14:35–14:36（设备时间），iPad Air 11-inch M3（iPad15,3 / iPadOS 18.3.2），
> AngelAuraAmethyst（Prisma fork 6.5.0）+ Fabric Loader 0.20.1 + MC 1.21.4 + LWJGL 3.3.3，
> mods：fabric-api 0.110.5+1.21.4 + **rtprobe 1.2.0**（rt-probe-v1.2 延迟执行版）。
> 证据归档：本目录（hs_err_pid6534.log / latestlog.txt / fatal_trace.txt）。

## 一、崩溃解剖

两次连续启动（fatal_trace.txt：14:35:47 与 14:36:22，间隔 35 秒）崩在同一位置，**确定性复现**：

```
SIGSEGV (0xb) at pc=0x0000000000000000, si_code=SEGV_ACCERR   ← 跳转到 NULL
Render thread [_thread_in_native], elapsed 4.74s

Native frames:
C  [libmobileglues.dylib+0x23f2d8]  glGetError+0x14
j  org.lwjgl.system.JNI.callI(J)I
j  org.lwjgl.opengl.GL.createCapabilities(...)
j  net.minecraft.class_1041.<init>(...)        ← Window 构造
j  net.minecraft.class_3682.method_16038(...)  ← WindowProvider.getWindow
j  net.minecraft.class_310.<init>(...)         ← MinecraftClient 构造
j  net.minecraft.client.main.Main.main
```

反汇编（崩溃点前后）显示 `glGetError` 是一个 **GOT 间接跳转 stub**：

```
glGetError+0x4:
    adrp x8, <GOT page>
    add  x8, x8, <off>
    ldr  x8, [x8, #0x3e0]   ; 从 GOT 槽加载真实实现指针
    blr  x8                  ; x8 == NULL → pc=0 → SIGSEGV
```

寄存器佐证：`x8=0x0`、`pc=0x0`、`x2` 指向 `glGetError+0`。**即 MobileGlues（GL→Vulkan 翻译层）的 GL 函数分派表槽位从未被填充**——GL 上下文/翻译层后端初始化未完成，MC 就开始调 GL 了（`GL.createCapabilities()` 的第一步就是 `glGetError` 探测）。

## 二、探针零介入的证据链（本次最关键产出）

rt-probe v1.2 的延迟执行设计**完全生效**，崩溃时刻探针没有执行任何原生调用：

| # | 证据 | 出处 |
|---|---|---|
| 1 | mod 列表含 `rtprobe 1.2.0`（v1.2 延迟版本体） | latestlog `Loading 52 mods` |
| 2 | 线程表只有 `RTProbe-Watchdog`（daemon, blocked——90 秒兜底计时器在睡），**无 `RTProbe` 工作线程** | hs_err Java Threads 段 |
| 3 | latestlog 全文 **零 `[RTProbe]` 输出**（探针主体从未运行） | Grep 全文复核 |
| 4 | 崩溃 elapsed **4.74s**，远早于 90s watchdog 与 `CLIENT_STARTED`（MC 根本没启动完） | hs_err SUMMARY |
| 5 | 游戏目录未生成 `rt_probe_report.txt`（探针未跑→未写报告） | 用户回传清单 |

类加载链同样干净：`FabricEntry.onInitialize` 只启动了 watchdog 线程；`FabricClientEntry.onInitializeClient` 只做了 fabric-api 事件反射注册；`RTProbeMod`/`VulkanProbe`/`org.lwjgl.vulkan.*` 类在崩溃前**从未被加载**（lambda 延迟引用）。

**因此本次实测在事实上等价于"完全移除探针"的基线对照实验（v1.2 Release 使用说明里要求的第三组对照）——结论：基线同样闪退。**

## 三、归因修正：上一轮"并发竞争"假说被证伪

`docs/chapter1-rt-probe-v1.1-field/VERDICT.md` 曾把 v1.1 闪退的首要嫌疑定为
"探针在 mod 初始化阶段的后台线程创建 VkInstance 并泄漏未销毁，与 Render thread 上
MobileGlues 初始化并发竞争 MoltenVK"。v1.2 已按该假说修复（延迟执行 + 全路径销毁），
**但 v1.2（探针零介入）仍在同一位置确定性崩溃——假说证伪**。

三版崩溃点对照（同一台 M3 / 同一启动器）：

| 探针版本 | 探针行为 | 崩溃 |
|---|---|---|
| v1.0 | 后台线程 VK.create 撞 already-created，**之后零 Vulkan 调用** | 存在（v1.0 现场亦有 glGetError SIGSEGV 记录） |
| v1.1 | mod init 阶段后台线程完整跑通、判 C 落盘、实例泄漏未销毁 | glGetError NULL SIGSEGV |
| **v1.2** | **延迟至 CLIENT_STARTED，崩溃时零原生调用** | **glGetError NULL SIGSEGV（两次复现）** |

三版唯一公共变量不是探针，而是**渲染器档位**。

## 四、根因：Vulkan 渲染档（无 VulkanMod）的混合态

latestlog 的启动器取证行：

```
[SurfaceViewController] FPS counter setup: profileRenderer=libMoltenVK.dylib,
    isVulkan=1, configuredVulkanExpected=1 (actual path decided at runtime ...)
[PLDisplayLinkTarget] displayLinkTick #1 (configuredVulkan=1, actualVulkanPath=0, ...)
[JavaLauncher] RENDERER is set to libMoltenVK.dylib
[JavaLauncher] Vulkan renderer detected, MobileGlues used as GL fallback.
```

配置层选择了 Vulkan 渲染器（`renderer=libMoltenVK.dylib`，`configuredVulkan=1`），
但实例**没有安装 VulkanMod**，运行时判定 `actualVulkanPath=0`，回落到
"MobileGlues GL fallback"混合态：MC 1.21.4 按 OpenGL 渲染 →
`-Dorg.lwjgl.opengl.libname=mobileglues`（GL→Vulkan 翻译层）→ MoltenVK → Metal。
该混合态下窗口/上下文创建链路（glfw 桥 → CAMetalLayer → MobileGlues 后端初始化）
不能稳定完成 → GL 分派表空 → `glGetError` 撞 NULL。

**责任追溯（需向用户说明）**：用户最初运行在常规档位，v1.0 报告判 F 后的提示
"请把档位渲染器切换为 MoltenVK 后重跑"是**错误指引**——
`org.lwjgl.vulkan.libname=MoltenVK` 是启动器固定 JVM 参数（v1.0 现场 S0 快照已证明
未切档时该属性同样存在），**探针在常规 GL 档下同样能加载 MoltenVK**，切档对探针
没有必要，反而把用户带进了 Vulkan 渲染档的固有崩溃。v1.1 的判 C 数据恰好是在
Vulkan 档取到的（探针在 GL 初始化崩溃前抢跑完成），属"带病取证"——判定有效，
但游戏从未在该档位活到标题屏。

## 五、对第一章判定的影响：无

**判 C 结论不变**：捆绑 MoltenVK 1.4.3（实例 Vulkan 1.4.357）不暴露
`VK_KHR_acceleration_structure` / `VK_KHR_ray_query`，与渲染器档位无关
（换档不换库）。第二章路线（改包替换 ICD → KosmicKrisp / honeykrisp）维持。
证据见 `docs/chapter1-rt-probe-v1.1-field/VERDICT.md`。

## 六、下一步操作（用户侧，两步）

1. **把该实例的渲染器切回你平时正常游玩的档位**（常规/默认 GL 档；不要选
   Vulkan / libMoltenVK.dylib 档）——这是消除混合态、恢复 GL 链路稳定初始化的关键。
2. **保留 mods 里的 rt-probe v1.2 不动**，正常启动游戏。进到标题屏后约 1 分钟内
   `CLIENT_STARTED` 触发探针（报告"触发方式"字段会显示），随后回传：
   `<实例>/versions/fabric-loader-0.20.1-1.21.4/game/rt_probe_report.txt`。

预期结果：游戏正常进入标题屏（混合态崩溃消失）+ 探针在 GL/MobileGlues 完全就绪后
安全窗口运行 + 判 C 复现。该组合同时验证 v1.2 延迟执行与全路径销毁在真实会话中的
零副作用——为第二章替换 ICD 后的复测流程定型。

若切回常规档后仍闪退：附 latestlog.txt + hs_err_pid*.log 回传（此时与探针、与
Vulkan 档均无关，属启动器独立问题，另行排查）。

## 附：两次 fatal trace 的一致性

14:35:47 与 14:36:22 两条 Amethyst fatal trace 的原生栈帧完全同构
（`hooked_abort ← VMError::report_and_die ← JVM_handle_bsd_signal ←
glGetError`），ASLR 基址不同（0x13dccb2dc / 0x13fa3f2dc）但模块内偏移一致，
排除时序竞争类偶发故障，指向初始化路径的确定性缺陷。
