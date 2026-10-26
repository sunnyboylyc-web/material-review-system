<script setup lang="ts">
import CollectionStatusPanel from './CollectionStatusPanel.vue'
defineProps<{ active: boolean; preview?: boolean }>()
</script>
<template>
  <section class="panel collection-operations" aria-labelledby="collection-operations-title">
    <div class="panel__header"><div><p class="panel__index">COLLECTION STATUS</p><h3 id="collection-operations-title">采集状态</h3><p>查看执行记录及固定时序；采集指标配置位于本页末尾，账户绑定和启用开关在“项目配置”中保存。</p></div></div>
    <details class="configuration-schedule">
      <summary>采集时序与运行说明</summary>
      <p>北京时间（Asia/Shanghai）每天 09:00、09:30、10:00、10:30、11:00、12:00 尝试采集昨日。每个账户首次入库且回读一致后，后续时点不再请求；其他失败账户仍继续尝试。遇到平台 51010，每页最多延迟重试五次，依次至少等待 5、10、20、40、60 秒，每个项目每轮最多延迟重试十二次；等待期间先处理其他项目，同一时间只执行一个采集请求，实际等待可能更长。同页五次重试仍失败或项目额度用完后，本轮停止该项目，后续时点再尝试。首次成功不代表平台最终数据，后续修订需维护人员手动补采。</p>
      <p>每天08:00、08:30补采北京时间T−2至T−8（含首尾7天）的缺口，昨天留给09:00。人工或自动已有完整数据（含零数据日）均跳过，异常占用保留待核。补采完整查询全域和乘方，仅一边有数据时采用该边；两边都有数据则标记冲突、不入库，保留来源交由人工处理；两边均成功且为空才确认零数据日。任一边失败都不入库，两边不相加。51010等待60秒重试1次，40100、40000、400153各最多2次，额度按项目日期一轮共享。单轮25分钟采集预算，未完成日期留给后续窗口，超出T−8不再自动补采。</p>
      <p class="configuration-attention">看板“刷新”只查询已入库数据，不触发采集。本页不提供补采执行或定时开关；定时任务是否启用以服务器部署回执为准。</p>
    </details>
    <p v-if="preview" class="configuration-attention">本地演示不连接真实采集状态；不触发采集，不表示正式服务器任务状态。</p>
    <CollectionStatusPanel v-else :active="active" />
    <slot />
  </section>
</template>
