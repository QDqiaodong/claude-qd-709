<template>
  <div>
    <!-- 说明条 -->
    <section class="panel">
      <div class="panel-head">
        <h3>封锁解除交接台</h3>
        <span class="panel-sub">
          已验收作业由施工负责人发起解除申请，逐项确认清障、撤离、撤机、复测；另一名复核人给出唯一结论。
          作业状态、区段状态或重级病害销号情况一旦变化，旧申请立即失效，须按最新现场重新提交。
        </span>
        <div class="panel-extra">
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openSubmit()">发起封锁解除</el-button>
        </div>
      </div>

      <!-- 筛选：线路 / 区段 / 交接状态 -->
      <div class="filter-bar">
        <span class="filter-label">线路</span>
        <el-select v-model="filter.lineId" clearable placeholder="全部线路" style="width: 200px" @change="onLineChange">
          <el-option v-for="l in works.lineList.value" :key="l.id" :label="l.name" :value="l.id" />
        </el-select>
        <span class="filter-label">区段</span>
        <el-select v-model="filter.sectionId" clearable placeholder="全部区段" style="width: 170px" @change="load">
          <el-option v-for="s in sectionOptions" :key="s.id" :label="s.sectionNo + '（' + s.statusName + '）'" :value="s.id" />
        </el-select>
        <span class="filter-label">交接状态</span>
        <el-radio-group v-model="filter.status" @change="load">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="PENDING">待复核</el-radio-button>
          <el-radio-button label="APPROVED">已通过</el-radio-button>
          <el-radio-button label="REJECTED">已退回</el-radio-button>
          <el-radio-button label="INVALID">已失效</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="list" border stripe size="small" :row-class-name="rowClass" v-loading="loading">
        <el-table-column prop="releaseNo" label="交接单号" width="130" />
        <el-table-column prop="orderNo" label="作业单号" width="130" />
        <el-table-column prop="lineName" label="线路" width="100" />
        <el-table-column prop="sectionNo" label="区段" width="86" />
        <el-table-column prop="workItemName" label="作业项目" width="90" />
        <el-table-column prop="leader" label="施工负责人" width="100" />
        <el-table-column prop="reviewer" label="复核人" width="100">
          <template #default="{ row }">{{ row.reviewer || '—' }}</template>
        </el-table-column>
        <el-table-column label="负责人建议" width="170">
          <template #default="{ row }">
            {{ row.suggestionName }}
            <span v-if="row.suggestion === 'SPEED_LIMIT'" class="muted">{{ row.suggestedSpeed }}km/h</span>
          </template>
        </el-table-column>
        <el-table-column label="最终结论" width="170">
          <template #default="{ row }">
            <span v-if="row.finalDecisionName">
              {{ row.finalDecisionName }}
              <span v-if="row.finalDecision === 'SPEED_LIMIT'" class="muted">{{ row.finalSpeed }}km/h</span>
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="交接状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submittedAt" label="提交时间" width="170">
          <template #default="{ row }">{{ fmt(row.submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情/处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 申请详情抽屉：快照、四项确认、失效变化项、复核操作、完整轨迹 -->
    <el-drawer v-model="detailVisible" :title="detail ? '交接申请 ' + detail.releaseNo : '交接详情'" size="640px">
      <div v-if="detail" v-loading="detailLoading">
        <!-- 状态横条 -->
        <el-alert
          :title="'当前状态：' + detail.statusName"
          :type="statusAlert(detail.status)"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        >
          <template v-if="detail.status === 'INVALID'">
            <div style="line-height: 1.6">
              现场已变化，旧结论不能继续套用，必须由施工负责人<strong>基于最新现场重新提交</strong>。
              <div style="margin-top: 4px">失效变化项：{{ detail.invalidReason }}</div>
            </div>
          </template>
          <template v-else-if="detail.status === 'APPROVED'">
            交接已通过：作业 {{ detail.orderNo }} 已在同一次业务结果中销号，区段已更新为「{{ detail.finalDecisionName }}」。
          </template>
          <template v-else-if="detail.status === 'REJECTED'">
            复核人已退回整改，请按退回意见修正后重新提交。
          </template>
          <template v-else>
            等待复核人逐项确认；复核期间现场任一项变化，申请将自动失效。
          </template>
        </el-alert>

        <!-- 基本信息 -->
        <h4 class="block-h">作业与区段（发起时锁定）</h4>
        <div class="kv-grid">
          <div class="kv"><span>作业单号</span><b>{{ detail.orderNo }} · {{ detail.workItemName }}</b></div>
          <div class="kv"><span>线路/区段</span><b>{{ detail.lineName }} · {{ detail.sectionNo }}</b></div>
          <div class="kv"><span>施工负责人</span><b>{{ detail.leader }}</b></div>
          <div class="kv"><span>复核人</span><b>{{ detail.reviewer || '待指派' }}</b></div>
          <div class="kv"><span>设计时速</span><b>{{ detail.currentDesignSpeed }} km/h</b></div>
          <div class="kv"><span>提交时间</span><b>{{ fmt(detail.submittedAt) }}</b></div>
        </div>

        <!-- 现场快照 vs 当前 -->
        <h4 class="block-h">发起时现场快照 vs 当前现场（失效检测）</h4>
        <el-table :data="detail.changes" border size="small">
          <el-table-column prop="changeTypeName" label="比对项" width="150" />
          <el-table-column label="发起时">
            <template #default="{ row }"><span class="snapshot-text">{{ row.snapshotText }}</span></template>
          </el-table-column>
          <el-table-column label="当前">
            <template #default="{ row }">
              <span :style="{ color: row.changed ? '#c62828' : '', fontWeight: row.changed ? 600 : 400 }">
                {{ row.currentText }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="是否变化" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.changed ? 'danger' : 'success'">{{ row.changed ? '已变化' : '一致' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>

        <!-- 四项现场确认 -->
        <h4 class="block-h">施工负责人四项现场确认</h4>
        <div class="check-list">
          <div class="check-item"><span class="check-name">现场清障</span><span>{{ detail.siteClear }}</span></div>
          <div class="check-item"><span class="check-name">人员撤离</span><span>{{ detail.staffWithdrawn }}</span></div>
          <div class="check-item"><span class="check-name">机具撤出</span><span>{{ detail.equipmentRemoved }}</span></div>
          <div class="check-item"><span class="check-name">线路复测</span><span>{{ detail.retestResult }}</span></div>
        </div>
        <div class="kv-grid">
          <div class="kv"><span>负责人建议</span>
            <b>{{ detail.suggestionName }}
              <template v-if="detail.suggestion === 'SPEED_LIMIT'">（{{ detail.suggestedSpeed }}km/h）</template>
            </b>
          </div>
          <div class="kv" v-if="detail.limitReason"><span>限速原因</span><b>{{ detail.limitReason }}</b></div>
        </div>

        <!-- 复核结论 -->
        <template v-if="detail.finalDecisionName">
          <h4 class="block-h">复核结论（{{ fmt(detail.reviewedAt) }} · {{ detail.reviewer }}）</h4>
          <div class="kv-grid">
            <div class="kv"><span>最终结论</span><b>{{ detail.finalDecisionName }}
              <template v-if="detail.finalDecision === 'SPEED_LIMIT'">（{{ detail.finalSpeed }}km/h）</template>
            </b></div>
            <div class="kv" v-if="detail.finalReason"><span>复核意见</span><b>{{ detail.finalReason }}</b></div>
          </div>
        </template>

        <!-- 操作区 -->
        <div class="action-area">
          <el-button
            v-if="detail.status === 'PENDING' || detail.status === 'REJECTED'"
            type="warning" plain
            @click="openResubmit"
          >按最新现场重新提交</el-button>
          <template v-if="detail.status === 'PENDING'">
            <el-button type="success" @click="openReview('APPROVE')">逐项确认通过</el-button>
            <el-button type="danger" plain @click="openReview('REJECT')">退回整改</el-button>
          </template>
          <el-alert
            v-if="detail.currentHeavyOpen && detail.status === 'PENDING'"
            title="该区段当前仍有未销号重级病害：只能继续限速或继续封锁，绝不能恢复正常。"
            type="error" :closable="false" show-icon style="margin-top: 10px"
          />
          <el-alert
            v-if="!detail.currentHeavyOpen && detail.status === 'PENDING'"
            title="当前无未销号重级病害，但不自动放行；是否恢复正常由复核人逐项确认后承担最终结论。"
            type="info" :closable="false" show-icon style="margin-top: 10px"
          />
        </div>

        <!-- 完整处理轨迹 -->
        <h4 class="block-h">完整处理轨迹</h4>
        <el-timeline>
          <el-timeline-item
            v-for="log in detail.logs"
            :key="log.id"
            :timestamp="fmt(log.actedAt) + ' · ' + log.operator"
            :type="logTag(log.action)"
          >
            <el-tag size="small" :type="statusTag(log.resultStatus)" style="margin-right: 8px">{{ log.resultStatusName }}</el-tag>
            <b>{{ log.actionName }}</b>
            <div class="muted" style="margin-top: 2px; white-space: pre-wrap">{{ log.remark }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 发起 / 重提表单 -->
    <el-dialog
      v-model="submitVisible"
      :title="submitForm.id ? '按最新现场重新提交 · ' + submitForm.releaseNo : '发起封锁解除申请'"
      width="620px"
    >
      <el-form :model="submitForm" label-width="110px">
        <el-form-item v-if="!submitForm.id" label="已验收作业单" required>
          <el-select v-model="submitForm.workOrderId" style="width: 100%" placeholder="选择已验收（CHECKED）作业单" @change="onPickOrder">
            <el-option
              v-for="o in checkedOrders"
              :key="o.id"
              :label="o.orderNo + ' · ' + o.sectionNo + ' · ' + o.workItemName + ' · 负责人 ' + o.leader"
              :value="o.id"
            />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="pickedHeavy"
          title="该作业区段存在未销号重级病害，不能建议恢复正常，只能继续限速或继续封锁。"
          type="error" :closable="false" show-icon style="margin-bottom: 10px"
        />
        <el-form-item label="现场清障" required>
          <el-input v-model="submitForm.siteClear" type="textarea" :rows="2" placeholder="限界清障、路料回收、栅栏恢复情况" />
        </el-form-item>
        <el-form-item label="人员撤离" required>
          <el-input v-model="submitForm.staffWithdrawn" type="textarea" :rows="2" placeholder="人员数量、撤至位置、点名确认情况" />
        </el-form-item>
        <el-form-item label="机具撤出" required>
          <el-input v-model="submitForm.equipmentRemoved" type="textarea" :rows="2" placeholder="撤出机具清单、回库登记情况" />
        </el-form-item>
        <el-form-item label="线路复测" required>
          <el-input v-model="submitForm.retestResult" type="textarea" :rows="2" placeholder="轨道检查仪/轨检车复测结果、几何尺寸" />
        </el-form-item>
        <el-form-item label="解除建议" required>
          <el-radio-group v-model="submitForm.suggestion">
            <el-radio label="NORMAL" :disabled="pickedHeavy">恢复正常</el-radio>
            <el-radio label="SPEED_LIMIT">继续限速</el-radio>
            <el-radio label="BLOCKED">继续封锁</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="submitForm.suggestion === 'SPEED_LIMIT'" label="限速值(km/h)" required>
          <el-input-number v-model="submitForm.suggestedSpeed" :min="1" :max="pickedDesignSpeed || 350" />
          <span class="muted" style="margin-left: 8px">不得高于设计时速 {{ pickedDesignSpeed || '—' }} km/h</span>
        </el-form-item>
        <el-form-item v-if="submitForm.suggestion === 'SPEED_LIMIT'" label="限速原因" required>
          <el-input v-model="submitForm.limitReason" type="textarea" :rows="2" placeholder="如：路基沉降整治后观察期，限速 45km/h" />
        </el-form-item>
        <el-form-item label="施工负责人" required>
          <el-input v-model="submitForm.leader" placeholder="发起申请的施工负责人" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doSubmit">{{ submitForm.id ? '重新提交' : '提交申请' }}</el-button>
      </template>
    </el-dialog>

    <!-- 复核对话框 -->
    <el-dialog v-model="reviewVisible" :title="reviewForm.action === 'APPROVE' ? '逐项确认通过' : '退回整改'" width="560px">
      <el-form :model="reviewForm" label-width="100px">
        <el-form-item label="复核人" required>
          <el-input v-model="reviewForm.reviewer" placeholder="复核人必须与施工负责人不是同一人" />
        </el-form-item>
        <el-alert
          v-if="detail && reviewForm.reviewer.trim() === detail.leader"
          title="复核人与施工负责人同名，提交将被拒绝。"
          type="error" :closable="false" show-icon style="margin-bottom: 10px"
        />
        <template v-if="reviewForm.action === 'APPROVE'">
          <el-form-item label="现场四项">
            <el-checkbox v-model="reviewForm.itemsClear">已逐项核对清障、撤离、撤机、复测记录齐全且与现场一致</el-checkbox>
          </el-form-item>
          <el-form-item label="最终结论" required>
            <el-radio-group v-model="reviewForm.decision">
              <el-radio label="NORMAL" :disabled="detail && detail.currentHeavyOpen">恢复正常</el-radio>
              <el-radio label="SPEED_LIMIT">继续限速</el-radio>
              <el-radio label="BLOCKED">继续封锁</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-alert
            v-if="detail && detail.currentHeavyOpen"
            title="当前仍有未销号重级病害，禁止恢复正常。"
            type="error" :closable="false" show-icon style="margin-bottom: 10px"
          />
          <el-alert
            v-if="detail && !detail.currentHeavyOpen"
            title="无重级病害不等于自动放行，最终结论由复核人承担。"
            type="info" :closable="false" show-icon style="margin-bottom: 10px"
          />
          <el-form-item v-if="reviewForm.decision === 'SPEED_LIMIT'" label="限速值(km/h)" required>
            <el-input-number v-model="reviewForm.finalSpeed" :min="1" :max="(detail && detail.currentDesignSpeed) || 350" />
            <span class="muted" style="margin-left: 8px">≤ 设计时速 {{ detail ? detail.currentDesignSpeed : '—' }}</span>
          </el-form-item>
          <el-form-item v-if="reviewForm.decision === 'SPEED_LIMIT'" label="限速原因" required>
            <el-input v-model="reviewForm.finalReason" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="复核意见">
            <el-input v-model="reviewForm.comment" type="textarea" :rows="2" placeholder="可选" />
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="整改意见" required>
            <el-input v-model="reviewForm.comment" type="textarea" :rows="4" placeholder="逐项指出需整改的问题，施工负责人将按最新现场重新提交" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button :type="reviewForm.action === 'APPROVE' ? 'success' : 'danger'" :loading="submitting" @click="doReview">
          {{ reviewForm.action === 'APPROVE' ? '确认通过并交接' : '退回整改' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { blockReleaseApi, sectionApi, workOrderApi } from '../api'

const works = inject('works')
const route = useRoute()
const router = useRouter()

const list = ref([])
const loading = ref(false)
const filter = ref({ lineId: null, sectionId: null, status: '' })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const submitVisible = ref(false)
const submitting = ref(false)
const submitForm = ref(emptySubmitForm())
const checkedOrders = ref([])
const allSections = ref([])

const reviewVisible = ref(false)
const reviewForm = ref(emptyReviewForm())

const reviewerName = ref(localStorage.getItem('rw-reviewer') || '')

function emptySubmitForm() {
  return {
    id: null,
    releaseNo: '',
    workOrderId: null,
    siteClear: '',
    staffWithdrawn: '',
    equipmentRemoved: '',
    retestResult: '',
    suggestion: 'SPEED_LIMIT',
    suggestedSpeed: 45,
    limitReason: '',
    leader: ''
  }
}

function emptyReviewForm() {
  return {
    action: 'APPROVE',
    reviewer: reviewerName.value || '',
    itemsClear: false,
    decision: 'SPEED_LIMIT',
    finalSpeed: 45,
    finalReason: '',
    comment: ''
  }
}

/** 区段下拉：选了线路就只列该线路，否则用全局已加载区段 */
const sectionOptions = computed(() => {
  if (filter.value.lineId) return allSections.value.filter((s) => s.lineId === filter.value.lineId)
  return allSections.value
})

const pickedOrder = computed(() => checkedOrders.value.find((o) => o.id === submitForm.value.workOrderId) || null)
const pickedSection = computed(() => {
  const sid = pickedOrder.value ? pickedOrder.value.sectionId : detail.value ? detail.value.sectionId : null
  return allSections.value.find((s) => s.id === sid) || null
})
const pickedDesignSpeed = computed(() => (pickedSection.value ? pickedSection.value.designSpeed : null))
/** 选中作业的区段当前是否有未销号重级病害 */
const pickedHeavy = computed(() => pickedSection.value ? (pickedSection.value.worstLevel === 'HEAVY') : false)

// ---------------- 列表 ----------------

async function load() {
  loading.value = true
  try {
    list.value = await blockReleaseApi.list({
      lineId: filter.value.lineId || undefined,
      sectionId: filter.value.sectionId || undefined,
      status: filter.value.status || undefined
    })
  } finally {
    loading.value = false
  }
}

async function loadSections() {
  allSections.value = await sectionApi.list(filter.value.lineId)
}

function onLineChange() {
  filter.value.sectionId = null
  loadSections()
  load()
}

// ---------------- 详情 ----------------

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await blockReleaseApi.detail(row.id)
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  detail.value = await blockReleaseApi.detail(detail.value.id)
  await load()
}

// ---------------- 发起 / 重提 ----------------

async function openSubmit(preselectOrderId) {
  submitForm.value = emptySubmitForm()
  await loadCheckedOrders()
  if (preselectOrderId) submitForm.value.workOrderId = preselectOrderId
  submitVisible.value = true
}

function openResubmit() {
  if (!detail.value) return
  const d = detail.value
  submitForm.value = {
    id: d.id,
    releaseNo: d.releaseNo,
    workOrderId: d.workOrderId,
    siteClear: d.siteClear,
    staffWithdrawn: d.staffWithdrawn,
    equipmentRemoved: d.equipmentRemoved,
    retestResult: d.retestResult,
    suggestion: d.suggestion,
    suggestedSpeed: d.suggestedSpeed || 45,
    limitReason: d.limitReason || '',
    leader: d.leader
  }
  // 重提也需要区段重级病害提示
  loadCheckedOrders().then(() => {})
  submitVisible.value = true
}

async function loadCheckedOrders() {
  const all = await workOrderApi.list()
  checkedOrders.value = all.filter((o) => o.status === 'CHECKED')
  // 顺带把所有区段缓存下来，供重级病害/设计时速判断
  allSections.value = await sectionApi.list()
}

function onPickOrder() {
  const order = pickedOrder.value
  if (order) {
    submitForm.value.leader = order.leader
    if (pickedSection.value && pickedSection.value.designSpeed) {
      submitForm.value.suggestedSpeed = Math.min(submitForm.value.suggestedSpeed || 45, pickedSection.value.designSpeed)
    }
    if (pickedHeavy.value && submitForm.value.suggestion === 'NORMAL') {
      submitForm.value.suggestion = 'SPEED_LIMIT'
    }
  }
}

async function doSubmit() {
  const f = submitForm.value
  if (!f.id && !f.workOrderId) return ElMessage.warning('请选择已验收作业单')
  for (const [key, label] of [
    ['siteClear', '现场清障结果'],
    ['staffWithdrawn', '人员撤离情况'],
    ['equipmentRemoved', '机具撤出情况'],
    ['retestResult', '线路复测结果']
  ]) {
    if (!f[key] || !f[key].trim()) return ElMessage.warning(label + '必须逐项填写')
  }
  if (!f.leader.trim()) return ElMessage.warning('施工负责人不能为空')
  if (f.suggestion === 'SPEED_LIMIT') {
    if (!f.suggestedSpeed || f.suggestedSpeed <= 0) return ElMessage.warning('请填写限速值')
    if (pickedDesignSpeed.value && f.suggestedSpeed > pickedDesignSpeed.value) {
      return ElMessage.warning('限速值不能高于设计时速 ' + pickedDesignSpeed.value)
    }
    if (!f.limitReason.trim()) return ElMessage.warning('选择继续限速必须填写原因')
  }
  if (pickedHeavy.value && f.suggestion === 'NORMAL') {
    return ElMessage.warning('区段仍有未销号重级病害，不能建议恢复正常')
  }
  submitting.value = true
  try {
    const payload = {
      workOrderId: f.workOrderId,
      siteClear: f.siteClear, staffWithdrawn: f.staffWithdrawn,
      equipmentRemoved: f.equipmentRemoved, retestResult: f.retestResult,
      suggestion: f.suggestion,
      suggestedSpeed: f.suggestion === 'SPEED_LIMIT' ? f.suggestedSpeed : null,
      limitReason: f.suggestion === 'SPEED_LIMIT' ? f.limitReason : null,
      leader: f.leader
    }
    if (f.id) {
      await blockReleaseApi.resubmit(f.id, payload)
      ElMessage.success('已按最新现场重新提交')
    } else {
      const created = await blockReleaseApi.submit(payload)
      ElMessage.success('封锁解除申请已提交')
      // 从作业页带过来的发起动作，清掉 query，避免刷新重复弹
      router.replace({ path: route.path, query: {} })
      if (created && created.id) {
        submitVisible.value = false
        await load()
        const row = list.value.find((r) => r.id === created.id)
        if (row) openDetail(row)
        return
      }
    }
    submitVisible.value = false
    await Promise.all([load(), works.reloadBoard(), works.reloadOverview()])
  } finally {
    submitting.value = false
  }
}

// ---------------- 复核 ----------------

function openReview(action) {
  if (!detail.value) return
  const d = detail.value
  if (d.status !== 'PENDING') return ElMessage.warning('只有待复核申请可以处理')
  reviewForm.value = emptyReviewForm()
  reviewForm.value.action = action
  reviewForm.value.decision = d.currentHeavyOpen
    ? 'SPEED_LIMIT'
    : (d.suggestion === 'NORMAL' || d.suggestion === 'SPEED_LIMIT' || d.suggestion === 'BLOCKED' ? d.suggestion : 'SPEED_LIMIT')
  reviewForm.value.finalSpeed = d.suggestedSpeed || (d.currentDesignSpeed ? Math.min(45, d.currentDesignSpeed) : 45)
  reviewVisible.value = true
}

async function doReview() {
  const d = detail.value
  const f = reviewForm.value
  if (!f.reviewer.trim()) return ElMessage.warning('请填写复核人')
  if (f.reviewer.trim() === d.leader) return ElMessage.warning('复核人不能与施工负责人是同一人')
  if (f.action === 'APPROVE') {
    if (!f.itemsClear) return ElMessage.warning('必须逐项核对四项现场确认后才能通过')
    if (d.currentHeavyOpen && f.decision === 'NORMAL') return ElMessage.warning('仍有未销号重级病害，不能恢复正常')
    if (f.decision === 'SPEED_LIMIT') {
      if (!f.finalSpeed || f.finalSpeed <= 0) return ElMessage.warning('请填写限速值')
      if (f.finalSpeed > d.currentDesignSpeed) return ElMessage.warning('限速值不能高于设计时速 ' + d.currentDesignSpeed)
      if (!f.finalReason.trim()) return ElMessage.warning('继续限速必须填写原因')
    }
  } else if (!f.comment.trim()) {
    return ElMessage.warning('退回整改必须填写整改意见')
  }
  localStorage.setItem('rw-reviewer', f.reviewer.trim())
  submitting.value = true
  try {
    await blockReleaseApi.review(d.id, {
      reviewer: f.reviewer.trim(),
      action: f.action,
      comment: f.comment || null,
      decision: f.action === 'APPROVE' ? f.decision : null,
      finalSpeed: f.action === 'APPROVE' && f.decision === 'SPEED_LIMIT' ? f.finalSpeed : null,
      finalReason: f.action === 'APPROVE' && f.decision === 'SPEED_LIMIT' ? f.finalReason : null
    })
    ElMessage.success(f.action === 'APPROVE' ? '复核通过，作业已销号，区段状态已更新' : '已退回整改')
    reviewVisible.value = false
    detailVisible.value = false
    await Promise.all([load(), works.reloadBoard(), works.reloadOverview()])
  } finally {
    submitting.value = false
  }
}

// ---------------- 展示辅助 ----------------

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
function statusTag(status) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', INVALID: 'info' }[status] || 'info'
}
function statusAlert(status) {
  return { PENDING: 'warning', APPROVED: 'success', REJECTED: 'error', INVALID: 'info' }[status] || 'info'
}
function logTag(action) {
  return { SUBMIT: 'primary', RESUBMIT: 'primary', REJECT: 'danger', APPROVE: 'success', INVALIDATE: 'info' }[action] || ''
}
function rowClass({ row }) {
  return row.status === 'INVALID' ? 'row-done' : ''
}

// 从养修作业页「发起交接」跳转过来：?orderId=xx 直接打开发起框
watch(() => route.query.orderId, (v) => {
  if (v) openSubmit(Number(v))
}, { immediate: false })

watch(() => works.lineId.value, async (lineId) => {
  filter.value.lineId = lineId || null
  filter.value.sectionId = null
  await Promise.all([loadSections(), load()])
}, { immediate: false })

onMounted(async () => {
  filter.value.lineId = works.lineId.value || null
  await Promise.all([loadSections(), load()])
  if (route.query.orderId) openSubmit(Number(route.query.orderId))
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin: 10px 0 12px;
}
.filter-label {
  font-size: 13px;
  color: #5a6b73;
}
.block-h {
  margin: 16px 0 8px;
  font-size: 14px;
  color: #243b44;
  border-left: 3px solid #00838f;
  padding-left: 8px;
}
.check-list {
  border: 1px solid #e3e8ea;
  border-radius: 6px;
  overflow: hidden;
}
.check-item {
  display: flex;
  gap: 10px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.5;
  border-bottom: 1px solid #eef1f2;
}
.check-item:last-child {
  border-bottom: none;
}
.check-name {
  flex: 0 0 70px;
  color: #00838f;
  font-weight: 600;
}
.snapshot-text {
  color: #5a6b73;
}
.action-area {
  margin: 18px 0 8px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
