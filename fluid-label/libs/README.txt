libs/ —— 本地 compileOnly 依赖（不进 jar 产物）

本目录需要一个文件：

  emi-1.1.24+1.20.1+forge.jar        1,121,750 字节

来源（目标实例内已安装的 EMI 生产 jar，字节级 ABI 一致）：

  C:\Users\18409\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods\emi-1.1.24+1.20.1+forge.jar

拷入方式：文件复制，不要重写内容。**不要走 maven**——`maven.terraformersmc.com` 在本机
不可达（302 → repo.sleeping.town 后 fetch failed，已验证）。

校验：
  - 字节数必须 = 1,121,750（PLAN §2.1）
  - `build.gradle` 里以 compileOnly 引用（PLAN §2.2，只有 D 能改 build.gradle）：
        compileOnly files("libs/emi-1.1.24+1.20.1+forge.jar")
  - 绝不能 runtimeOnly / 不能被打进产物（运行期 EMI 由用户自己装）。

⚠ 本文件由 B 阶段创建（B 无 shell/二进制复制能力，未能完成 jar 拷贝）。
