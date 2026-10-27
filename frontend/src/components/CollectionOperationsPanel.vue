<script setup lang="ts">
import CollectionStatusPanel from './CollectionStatusPanel.vue'
defineProps<{ active: boolean; preview?: boolean }>()
</script>
<template>
  <section class="panel collection-operations" aria-labelledby="collection-operations-title">
    <div class="panel__header"><div><p class="panel__index">COLLECTION STATUS</p><h3 id="collection-operations-title">采集状态</h3><p>查看执行记录及固定时序；采集指标配置位于本页末尾，账户绑定和启用开关在“项目配置”中保存。</p></div></div>
    <details class="configuration-schedule">
      <summary>采集时序与运行说明</summary>
      <p>北京时间（Asia/Shanghai）每天 09:00、09:30、10:00、10:30、11:00、11:30、12:00 尝试采集昨日。每个账户首次入库且回读一致后，后续时点跳过已有业务数据，其他失败账户继续尝试。全局串行，每次请求至少间隔2秒。日常和补采统一：51010、40100、40000、400153每类错误每个项目日期一轮最多追加2次，每次至少等待60秒；四类共用8次总上限，补采两主题也共用。40100限流时所有项目共同暂停，其他等待可顺序穿插处理。主播查询同样执行该规则，网络异常仅有界重试。首次成功不代表平台最终数据，后续修订需维护人员核对处理。</p>
      <p>每天08:00、08:30补采北京时间T−2至T−8（含首尾7天）的缺口，昨天留给09:00。人工或自动已有完整数据（含零数据日）均跳过，异常占用保留待核。补采完整查询全域和乘方，仅一边有数据时采用该边；两边都有数据则标记冲突、不入库，保留来源交由人工处理；两边均成功且为空才确认零数据日。任一边失败都不入库，两边不相加。已有日期若存在待补全素材仍检查标识，只补对应素材并生成可追溯的新版本，不重复累计已入库数据。单轮25分钟采集预算，未完成日期留给后续窗口，超出T−8不再自动补采。</p>
      <p>创建时间缺失时，优先引用同一抖音号和素材ID的已核实历史日期并保留来源；无可靠日期且消耗明确为0、其他核心指标亦为0的素材暂缓入库并标记待补全，其余正常数据入库。有消耗或消耗未知、日期无可靠来源时仍拦截整日入库。原始返回保留，不补造日期；待补全自动处理窗口为T−2至T−8，超窗保留并提示人工处理。</p>
    <p class="configuration-attention">看板“刷新”只查询已入库数据，不触发采集。本页不提供补采执行或定时开关；定时任务是否启用以服务器部署回执为准。</p>
    </details>
    <p v-if="preview" class="configuration-attention">本地演示不连接真实采集状态；不触发采集，不表示正式服务器任务状态。</p>
    <CollectionStatusPanel v-else :active="active" />
    <slot />
  </section>
</template>
