# dsh-pet-bridge 插件

桌宠 App 的**数据端**：把「工作状态 / 余额 / 碎碎念」三条能力挂到本机 DSH Web 服务上，供悬浮窗 App 读取。

> 为什么需要插件：DSH 本体不对外暴露「当前在思考什么」「钱包余额」「宠物语录」这些数据，`/api/*` 还要 cookie 鉴权（token 只在进程内），第三方 App 拿不到。所以只能由一个跑在 DSH 里的插件把这些数据转成公开只读的 HTTP 路由。没有插件，桌宠本体（动画、Q 弹、甩飞、好感、大小）照常，只是这三项拿不到数据。

---

## 1. 它和 dsh-pet 的关系

`dsh-pet-bridge` 派生自 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 的宿主端代码，做了裁剪：

| 内容 | dsh-pet 原包 | 本插件 |
| --- | --- | --- |
| 网页桌宠 / 网页 UI 动效 | 有 | **移除**（手机端用不到） |
| 桌面 Electron 透明窗助手 | 有（首次自动下载 Electron） | **移除**，`@electron/get` 换成空实现 |
| 动画素材 `assets/webm` | 53 MB | **移除**（App 自带素材） |
| 字体 / 表情包 / 预览图 | 9 MB | **移除**（保留少量表情包供碎碎念配图） |
| 运行时依赖 `node_modules`（openai / anthropic / zod） | 50 MB | **移除**，改从 DSH 安装目录解析 |
| 三条数据路由 | 有 | **保留**（行为一致） |

体积从 114 MB 降到约 1.8 MB，安装后对 DSH 启动速度影响可忽略。

---

## 2. 提供的接口

插件路由前缀固定为 `/dsh-pet-7340`（与 dsh-pet 相同，桌宠 App 按此路径握手）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/dsh-pet-7340/state` | 主数据：`sections.workStatus`（思考/干活/等待/完成/出错）、`sections.balance`（钱包缓存）、`sections.notify`（会话事件）、`pets.main.say`（宠物语录） |
| POST | `/dsh-pet-7340/balance` | 触发一次余额刷新，返回 `{"ok":true}`；真实数值随后从 `/state` 读取 |
| POST | `/dsh-pet-7340/whisper?pet=main&surface=web` | 生成一句碎碎念，返回 `{"ok":true,"text":"…","image":"…"}` |
| POST | `/dsh-pet-7340/chat?pet=main&surface=web` | 发一条对话，body `{"text":"…"}` |
| POST | `/dsh-pet-7340/pet-display` | 设置显示位置，body `{"id":"main","display":"web"\|"desktop"\|"both"\|"none"}` |
| GET | `/dsh-pet-7340/config` | 完整配置（宠物、文案、动画映射） |

> `surface=web` 需要 `display` 为 `web` 或 `both`，否则 `/whisper` 返回 `{"ok":false,"reason":"pet-not-displayed"}`。桌宠 App 已内置自愈：遇到该错误会自动 `POST /pet-display {"display":"both"}` 后重试一次。

---

## 3. 安装

### 方式 A：zip 一键脚本（推荐）

1. 下载 `dsh-pet-bridge-6.0.0-min.zip`（Releases 附件），拷到手机；
2. 打开 DSHA → **终端**：

```sh
mkdir -p /tmp/pet && cd /tmp/pet
unzip -o /sdcard/Download/DSHA/dsh-pet-bridge-6.0.0-min.zip
sh dsh-pet-bridge/install.sh
```

3. 重启 DSHA。

脚本会：把插件拷到 `~/.dsh/profiles/web/node_modules/dsh-pet-bridge`；把 profile 的 `bundles` 里 `dsh-pet` 换成 `dsh-pet-bridge`（没有就追加）；打印最终列表。

### 方式 B：手动安装

```sh
# 1. 解压到 profile 的 node_modules
cp -a dsh-pet-bridge ~/.dsh/profiles/web/node_modules/

# 2. 编辑 ~/.dsh/profiles/web/package.json，把 bundles 里的 dsh-pet 换成 dsh-pet-bridge
#    例如： "bundles": ["dshmarket", "billion-context", "dsh-pet-bridge", ...]

# 3. 重启 DSHA
```

> 同一时间**只保留一个**桌宠数据插件：`dsh-pet` 与 `dsh-pet-bridge` 路由前缀相同，同时加载会冲突。

---

## 4. 验证

```sh
curl -s http://127.0.0.1:38859/dsh-pet-7340/state | head -c 200
```

返回带 `"sections"` 的 JSON 即成功。再试余额与碎碎念：

```sh
curl -s -X POST http://127.0.0.1:38859/dsh-pet-7340/balance
curl -s -X POST "http://127.0.0.1:38859/dsh-pet-7340/whisper?pet=main&surface=web"
```

端口以 DSHA 首页「实际 Web 端口」为准（默认 38859）。

---

## 5. 卸载 / 回滚

```sh
# 恢复原插件
cd ~/.dsh/profiles/web
sed -i 's/"dsh-pet-bridge"/"dsh-pet"/' package.json
rm -rf node_modules/dsh-pet-bridge
```

然后重启 DSHA。`install.sh` 首次运行前会保留 `package.json.bak2` 备份，也可直接还原。

---

## 6. 兼容性

- DSH：0.2.x（`web` profile），依赖 `webServer` / `credentials` / `connection` / `llm` 服务；
- 系统：Android（DSHA 容器内）与桌面端均可；
- 端口：跟随 DSH Web 端口，插件本身不监听端口。

## 7. 许可

派生自 dsh-pet（MIT），本插件同样以 MIT 发布；动画与表情素材版权归原作者。
