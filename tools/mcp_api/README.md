# 素材视界只读MCP（D247，本地版）

另一端系统后端以MCP客户端连接本服务；服务只复用现有Java GET计算接口，不连接SQL、不保存复盘/日期、不采集/上传/删除/改配置。正式入口使用Bearer密钥，纯合成本地演示可为none；本地服务器固定127.0.0.1并核Host/Origin，不开放外网/CORS。MCP传输是Streamable HTTP，工具只读，不需要浏览器刷新。

## 环境

Python3.13，独立venv安装本目录requirements.txt（官方mcp==2.3.0及本次测试固定依赖；Windows包有平台标记）。不改变原venv，不启动本地MySQL。只读网关自身不需要Java/MySQL，Java仅为现有业务服务或本地合成数据源提供计算。

```powershell
python -m venv .local/venvs/mcp-d247
.local/venvs/mcp-d247/Scripts/python.exe -m pip install -r tools/mcp_api/requirements.txt
```

## 启动纯合成联调

已有工程JDK/Maven/依赖缓存时，从项目根运行：
```powershell
tools/mcp_api/start_demo.ps1
.local/venvs/mcp-d247/Scripts/python.exe -m tools.mcp_api.probe_client --demo
```

MCP默认24573/mcp，合成Java来源24571。演示使用实际DashboardCalculator和内存虚构事实，包含258个候选，能够验证排名200以后的筛选；不启动Spring/SQL、不加载生产配置。进程/日志在.local/d247-mcp，端口冲突拒绝，不停止不明进程。脚本依赖本工程现有便携工具路径；新机需先按交接环境文档准备，不能当一键装环境。

## 指定已有真实查询源（仅配置示例，未部署）

```powershell
.local/venvs/mcp-d247/Scripts/python.exe -m tools.mcp_api.server --port 24570 --source http://127.0.0.1:4200/uranus/titania --public-host mcp.example.invalid --auth-token-file /etc/material-review-mcp/access-token
```

这里的source是维护人启动时设置的Java只读API根，不是另一端模块的MCP地址；不能由AI工具参数改变。HTTPS正常验证证书，不跳过TLS；HTTP只允许loopback。无运行中的真实查询源会明确报错，不自动启动MySQL/恢复数据或回退合成数据。Synthetic来源必须显式--synthetic-source，且所有数据标记SYNTHETIC。本轮纯本地联调不执行本条生产部署示例。

## 工具/约束

7工具及完整接入说明见docs/mcp/另一端系统接入说明.md。周期先核三查询的同项目、四日期、v1.7/v2结构及版本指纹，再保留15分钟/4份/64MiB内存报告供分页和趋势使用。全量筛选后分页，未知非0，明细单页最多100/2MiB；日常TOP只保证上游200候选。类型CPC与整体无CPC明确区分，不补造指标。

汇总/比率由Java返回；MCP只增加网页同式composition分组÷整体展示值。每轮get_period_review读取当前事实，不依赖已保存复盘。无写入工具、任意SQL、任意URL/文件读取接口。

## 测试

```powershell
$env:MCP_TEST_ENDPOINT='http://127.0.0.1:24573/mcp'
.local/venvs/mcp-d247/Scripts/python.exe -m unittest discover -s tools/mcp_api -p 'test_*.py'
```

协议集成只针对本地合成源；未设置MCP_TEST_ENDPOINT时协议用例条件跳过，其他边界测试仍运行。覆盖真实官方SDK握手/工具发现/结构化输出/资源、实际Java计算器、四日期、全量筛选分页、AI组、未知/比例、版本冲突、日期错配、TTL/容量、只读路线和Origin拒绝。

正式运行状态以部署验收为准；模块不变更既有看板/库/采集/备份。正式发布时新contracts/mcp、Java测试与tools/mcp_api范围须按白名单审查后才可公开；不能把本轮内部文档或真实联调数据默认公开。官方参考：https://py.sdk.modelcontextprotocol.io/run/ ，https://py.sdk.modelcontextprotocol.io/servers/tools/ 。

## 正式认证

--public-host必须搭配--auth-token-file。密钥从受限文件读取，Linux拒绝其他用户可读的文件；不存在/不合法时失败，不回退none。ASGI Bearer门禁覆盖所有MCP HTTP请求，常量时间比对，不打印密钥，未提供/错误为401；密钥只单独分发，不提交Git或放对接包。--public-host只接受精确DNS名，Host/Origin保护不关闭。正式客户端用--token-file或SDK受限配置设置Authorization头，不把密钥字面量放命令行。
