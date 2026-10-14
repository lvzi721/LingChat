<template>
  <div class="custom-scrollbar h-full overflow-y-auto px-2 py-2 md:px-6">
    <h2 class="text-brand border-brand mb-6 border-b pb-4 text-2xl font-semibold">
      {{ $t("settings.alarm.title") }}
    </h2>

    <p class="mb-5 px-1 text-sm text-gray-400">{{ $t("settings.alarm.intro") }}</p>

    <!-- 权限提示 -->
    <div
      v-if="needsPermission"
      class="mb-5 rounded-lg border border-amber-300/30 bg-amber-400/10 px-4 py-3 text-sm"
    >
      <p class="font-semibold text-amber-200">{{ $t("settings.alarm.permTitle") }}</p>
      <ul class="mt-2 space-y-1 text-gray-300">
        <li v-if="!permissions.notificationsGranted">
          {{ $t("settings.alarm.permNotifications") }}
        </li>
        <li v-if="!permissions.exactAlarmAllowed">
          {{ $t("settings.alarm.permExact") }}
        </li>
        <li v-if="!permissions.batteryOptimizationIgnored">
          {{ $t("settings.alarm.permBattery") }}
        </li>
      </ul>
      <button
        type="button"
        class="mt-3 rounded-lg border border-amber-200/30 bg-amber-300/15 px-3 py-2 text-amber-100
          transition-colors hover:bg-amber-300/25"
        @click="requestPermissions"
      >
        {{ $t("settings.alarm.permButton") }}
      </button>
    </div>

    <!-- 操作栏 -->
    <div class="mb-5 flex flex-wrap items-center gap-2">
      <button
        type="button"
        class="bg-brand rounded-lg px-4 py-2 text-sm font-medium text-white transition-opacity
          hover:opacity-90"
        @click="startCreate"
      >
        {{ $t("settings.alarm.add") }}
      </button>
      <button
        type="button"
        class="rounded-lg border border-white/20 bg-white/10 px-4 py-2 text-sm font-medium
          text-white transition-colors hover:bg-white/20"
        @click="stopRinging"
      >
        {{ $t("settings.alarm.stop") }}
      </button>
      <button
        type="button"
        class="rounded-lg border border-white/20 bg-white/5 px-4 py-2 text-sm font-medium
          text-white/80 transition-colors hover:bg-white/15"
        @click="refresh"
      >
        {{ $t("settings.alarm.refresh") }}
      </button>
      <span class="text-sm" :class="status.color">{{ status.message }}</span>
    </div>

    <!-- 编辑表单 -->
    <div
      v-if="editing"
      class="mb-5 rounded-lg border border-white/15 bg-white/5 p-4"
    >
      <p class="mb-4 text-sm font-semibold text-white/80">
        {{ form.id ? $t("settings.alarm.editTitle") : $t("settings.alarm.createTitle") }}
      </p>

      <div class="mb-4 flex flex-wrap items-center gap-3">
        <label class="text-sm text-gray-300">{{ $t("settings.alarm.time") }}</label>
        <input
          v-model.number="form.hour"
          type="number"
          min="0"
          max="23"
          class="w-20 rounded-lg border border-white/15 bg-white/10 px-3 py-2 text-center text-white
            focus:border-[rgba(121,217,255,0.6)] focus:outline-none"
        />
        <span class="text-white/60">:</span>
        <input
          v-model.number="form.minute"
          type="number"
          min="0"
          max="59"
          class="w-20 rounded-lg border border-white/15 bg-white/10 px-3 py-2 text-center text-white
            focus:border-[rgba(121,217,255,0.6)] focus:outline-none"
        />
      </div>

      <div class="mb-4">
        <label class="mb-1.5 block text-sm text-gray-300">{{ $t("settings.alarm.label") }}</label>
        <input
          v-model="form.label"
          type="text"
          maxlength="40"
          :placeholder="$t('settings.alarm.labelPlaceholder')"
          class="w-full rounded-lg border border-white/15 bg-white/10 px-3 py-2 text-white
            focus:border-[rgba(121,217,255,0.6)] focus:outline-none"
        />
      </div>

      <div class="mb-4">
        <label class="mb-1.5 block text-sm text-gray-300">{{ $t("settings.alarm.repeat") }}</label>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="day in WEEKDAYS"
            :key="day"
            type="button"
            class="h-10 w-10 rounded-lg border text-sm transition-colors"
            :class="
              form.days.includes(day)
                ? 'bg-brand/30 border-brand text-white font-semibold'
                : 'border-white/15 bg-white/5 text-white/70 hover:bg-white/15'
            "
            @click="toggleDay(day)"
          >
            {{ $t(`settings.alarm.weekday.${day}`) }}
          </button>
        </div>
        <p class="mt-1.5 text-sm text-gray-400">{{ $t("settings.alarm.repeatHint") }}</p>
      </div>

      <div class="mb-4">
        <label class="mb-1.5 block text-sm text-gray-300">{{ $t("settings.alarm.ringtone") }}</label>
        <select
          v-model="form.ringtoneUri"
          class="w-full cursor-pointer rounded-lg border border-white/15 bg-white/10 px-3 py-2
            text-white focus:border-[rgba(121,217,255,0.6)] focus:outline-none"
        >
          <option
            v-for="ringtone in ringtoneOptions"
            :key="ringtone.uri"
            :value="ringtone.uri"
            class="bg-slate-800 text-white"
          >
            {{ ringtone.title }}
          </option>
        </select>
      </div>

      <div class="flex items-center gap-2">
        <button
          type="button"
          class="bg-brand rounded-lg px-4 py-2 text-sm font-medium text-white transition-opacity
            hover:opacity-90"
          @click="save"
        >
          {{ $t("settings.alarm.save") }}
        </button>
        <button
          type="button"
          class="rounded-lg border border-white/20 bg-white/5 px-4 py-2 text-sm font-medium
            text-white/80 transition-colors hover:bg-white/15"
          @click="cancelEdit"
        >
          {{ $t("settings.alarm.cancel") }}
        </button>
      </div>
    </div>

    <!-- 列表 -->
    <p v-if="loading" class="px-1 text-sm text-gray-400">{{ $t("settings.alarm.loading") }}</p>
    <p v-else-if="alarms.length === 0" class="px-1 text-sm text-gray-400">
      {{ $t("settings.alarm.empty") }}
    </p>

    <div v-else class="space-y-3">
      <div
        v-for="item in alarms"
        :key="item.id"
        class="flex flex-wrap items-start gap-4 rounded-lg border border-white/10 bg-white/5 px-4 py-3"
      >
        <div class="min-w-24 text-center">
          <p class="text-3xl leading-none font-semibold text-white tabular-nums">
            {{ formatTime(item) }}
          </p>
          <p class="mt-1 text-xs text-white/50">{{ repeatText(item) }}</p>
        </div>

        <div class="min-w-40 flex-1">
          <p class="text-sm font-medium text-white/90">{{ item.label || $t("settings.alarm.untitled") }}</p>
          <p class="mt-1 text-xs text-white/60">
            {{ $t("settings.alarm.ringtoneLabel") }}：{{ item.ringtoneTitle }}
          </p>
          <p v-if="item.enabled && item.nextTriggerText" class="mt-1 text-xs text-emerald-300/80">
            {{ $t("settings.alarm.next") }}：{{ item.nextTriggerText }}
            <span v-if="!item.exact" class="text-amber-300/90">
              （{{ $t("settings.alarm.inexact") }}）
            </span>
          </p>
          <p v-else-if="!item.enabled" class="mt-1 text-xs text-white/40">
            {{ $t("settings.alarm.disabled") }}
          </p>
        </div>

        <div class="flex items-center gap-3">
          <Toggle :checked="item.enabled" @change="(value: boolean) => toggleEnabled(item, value)">
            {{ item.enabled ? $t("settings.alarm.on") : $t("settings.alarm.off") }}
          </Toggle>
          <button
            type="button"
            class="rounded-lg border border-white/20 bg-white/5 px-3 py-2 text-sm text-white/80
              transition-colors hover:bg-white/15"
            @click="startEdit(item)"
          >
            {{ $t("settings.alarm.edit") }}
          </button>
          <button
            type="button"
            class="rounded-lg border border-red-300/30 bg-red-400/10 px-3 py-2 text-sm text-red-200
              transition-colors hover:bg-red-400/20"
            @click="remove(item)"
          >
            {{ $t("settings.alarm.delete") }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed, onMounted, reactive, ref } from "vue";
  import { useI18n } from "vue-i18n";
  import Toggle from "@/components/base/widget/Toggle.vue";
  import { useDialogStore } from "@/stores/modules/ui/dialog";
  import {
    SILENT_RINGTONE_URI,
    alarmCancel,
    alarmList,
    alarmListRingtones,
    alarmPermissions,
    alarmRequestPermissions,
    alarmStop,
    alarmUpsert,
    type AlarmPermissions,
    type AlarmRecord,
    type RingtoneRecord,
  } from "@/api/services/alarm";

  const { t } = useI18n();
  const dialogStore = useDialogStore();

  /** 与后端 days 编码一致：周日=1 … 周六=7。 */
  const WEEKDAYS = [2, 3, 4, 5, 6, 7, 1];

  const alarms = ref<AlarmRecord[]>([]);
  const ringtones = ref<RingtoneRecord[]>([]);
  const permissions = reactive<AlarmPermissions>({
    notificationsGranted: true,
    exactAlarmAllowed: true,
    batteryOptimizationIgnored: true,
  });
  const loading = ref(true);
  const editing = ref(false);
  const status = reactive({ message: "", color: "#4ade80" });

  const form = reactive({
    id: null as string | null,
    label: "",
    hour: 7,
    minute: 30,
    days: [] as number[],
    ringtoneUri: SILENT_RINGTONE_URI as string,
  });

  const needsPermission = computed(
    () =>
      !permissions.notificationsGranted ||
      !permissions.exactAlarmAllowed ||
      !permissions.batteryOptimizationIgnored
  );

  /** 铃声下拉：始终包含当前选中项，避免自定义 URI 被 v-model 丢成空值。 */
  const ringtoneOptions = computed(() => {
    const options = ringtones.value.slice();
    if (!options.some((item) => item.uri === form.ringtoneUri)) {
      options.unshift({
        title: t("settings.alarm.ringtoneUnknown"),
        uri: form.ringtoneUri,
        isDefault: false,
      });
    }
    return options;
  });

  const showStatus = (message: string, color = "#4ade80") => {
    status.message = message;
    status.color = color;
    setTimeout(() => {
      status.message = "";
    }, 5000);
  };

  const formatTime = (item: AlarmRecord) =>
    `${String(item.hour).padStart(2, "0")}:${String(item.minute).padStart(2, "0")}`;

  const repeatText = (item: AlarmRecord) => {
    const days = item.days.slice().sort((a, b) => a - b);
    if (days.length === 0) {
      return t("settings.alarm.repeatOnce");
    }
    if (days.length === 7) {
      return t("settings.alarm.repeatDaily");
    }
    return days.map((day) => t(`settings.alarm.weekday.${day}`)).join(" ");
  };

  const loadPermissions = async () => {
    try {
      Object.assign(permissions, await alarmPermissions());
    } catch (error) {
      console.warn("读取闹钟权限状态失败:", error);
    }
  };

  const loadRingtones = async () => {
    try {
      ringtones.value = (await alarmListRingtones()).ringtones ?? [];
    } catch (error) {
      console.warn("读取铃声列表失败:", error);
    }
  };

  const refresh = async () => {
    loading.value = true;
    try {
      alarms.value = (await alarmList()).alarms ?? [];
    } catch (error) {
      showStatus(String(error), "#f87171");
    } finally {
      loading.value = false;
    }
    await loadPermissions();
  };

  const startCreate = async () => {
    if (ringtones.value.length === 0) {
      await loadRingtones();
    }
    const defaultRingtone =
      ringtones.value.find((item) => item.isDefault)?.uri ??
      ringtones.value.find((item) => item.uri !== SILENT_RINGTONE_URI)?.uri ??
      SILENT_RINGTONE_URI;
    form.id = null;
    form.label = "";
    form.hour = 7;
    form.minute = 30;
    form.days = [];
    form.ringtoneUri = defaultRingtone;
    editing.value = true;
  };

  const startEdit = async (item: AlarmRecord) => {
    if (ringtones.value.length === 0) {
      await loadRingtones();
    }
    form.id = item.id;
    form.label = item.label;
    form.hour = item.hour;
    form.minute = item.minute;
    form.days = item.days.slice();
    form.ringtoneUri = item.ringtoneUri ?? SILENT_RINGTONE_URI;
    editing.value = true;
  };

  const cancelEdit = () => {
    editing.value = false;
  };

  const toggleDay = (day: number) => {
    const index = form.days.indexOf(day);
    if (index >= 0) {
      form.days.splice(index, 1);
    } else {
      form.days.push(day);
    }
  };

  const save = async () => {
    const hour = Number(form.hour);
    const minute = Number(form.minute);
    if (!Number.isInteger(hour) || hour < 0 || hour > 23) {
      showStatus(t("settings.alarm.invalidHour"), "#f87171");
      return;
    }
    if (!Number.isInteger(minute) || minute < 0 || minute > 59) {
      showStatus(t("settings.alarm.invalidMinute"), "#f87171");
      return;
    }
    try {
      await alarmUpsert({
        id: form.id,
        label: form.label,
        hour,
        minute,
        days: form.days.slice().sort((a, b) => a - b),
        enabled: true,
        ringtoneUri: form.ringtoneUri,
      });
      editing.value = false;
      showStatus(t("settings.alarm.saved"));
      await refresh();
    } catch (error) {
      showStatus(String(error), "#f87171");
    }
  };

  const toggleEnabled = async (item: AlarmRecord, enabled: boolean) => {
    try {
      await alarmUpsert({
        id: item.id,
        label: item.label,
        hour: item.hour,
        minute: item.minute,
        days: item.days,
        enabled,
      });
      await refresh();
    } catch (error) {
      showStatus(String(error), "#f87171");
    }
  };

  const remove = async (item: AlarmRecord) => {
    const approved = await dialogStore.confirm(
      t("settings.alarm.deleteConfirm", { time: formatTime(item) }),
      t("settings.alarm.deleteTitle")
    );
    if (!approved) return;
    try {
      await alarmCancel(item.id);
      showStatus(t("settings.alarm.deleted"));
      await refresh();
    } catch (error) {
      showStatus(String(error), "#f87171");
    }
  };

  const stopRinging = async () => {
    try {
      await alarmStop();
      showStatus(t("settings.alarm.stopped"));
    } catch (error) {
      showStatus(String(error), "#f87171");
    }
  };

  const requestPermissions = async () => {
    try {
      Object.assign(permissions, await alarmRequestPermissions());
      showStatus(t("settings.alarm.permRequested"), "#7dd3fc");
    } catch (error) {
      showStatus(String(error), "#f87171");
    }
  };

  onMounted(async () => {
    await refresh();
  });
</script>
