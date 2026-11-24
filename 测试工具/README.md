# 可复现的本地测试

RoadProbe 是测试插件，不要安装到正式服。仅在独立 Paper 1.16.5 build 794 测试目录内运行，先自行接受 Minecraft EULA。Paper 下载与校验信息在[实测依赖记录](../实测结果/依赖记录.json)；本包不携带 Minecraft/Paper 二进制或替你接受 EULA。

测试服限制在 server-ip=127.0.0.1、server-port=25576，保持 online-mode=true。将 MapReset 安装到 plugins；把 config.test.yml 保存为 plugins/MapReset/config.yml，关闭自动补库。默认世界设为 mapreset_lobby；bukkit.yml 中 worlds.mapreset_lobby.generator=MapReset。

用 JDK 编译本测试插件：classpath 包含 mapreset-2.3.0.jar 和 Paper 首次启动生成的 cache/patched_1.16.5.jar；将 RoadProbe.java 编译后的类与 plugin.yml 打入 RoadProbe.jar，放到测试服 plugins。测试源码包含对该版本 NMS 碰撞形状的调用，仅用于测试，不在生产 MapReset JAR 中。

控制台执行 `roadprobe start 12345`。首次调用真实生产生成器，生成/扫描全部 1936 区块，缓存未修整的原版地形。之后从该缓存复跑生产规划、清树、地形修整、道路、护栏、建筑、自查及备用保存，避免每次修改算法都重生成整个原版地图。保存前观察器短暂停止生成任务，分批导出实际方块和三条通行线上的 NMS 碰撞形状，之后调用原生产自查及保存逻辑。测试结果位于 plugins/RoadProbe/seed-12345-verified。

重跑时应更改测试源码的输出目录/备用图名称，避免覆盖前一轮 READY 地图。地图不能从已经修整过的世界作为基线，应使用 raw-12345 中的原版快照。

render_capture.py 使用本地 Python + Pillow，根据导出 CSV 生成诊断图，不连接 Minecraft 客户端；运行路径需按你的测试目录调整。本次本机已具备 Python/Pillow，未下载它们。游戏渲染、真实玩家操作、多人负载仍需客户端验收。
