import { useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { RiBarChart2Line, RiLineChartLine, RiTableLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import type { AdminDashboardSummary, DashboardDailyPoint } from './admin-ops.api';
import { formatStudyDuration } from './OverviewKpiRow';

// Fixed entity-to-color mapping (theme --chart-1..4) validated by dataviz/scripts/validate_palette.js
// in both light and dark themes for color-blind safety.
const PRACTICE_SERIES = [
  { key: 'lessonCount', labelKey: 'overviewDashboard.series.lessons', color: 'var(--chart-1)' },
  { key: 'grammarAnswerCount', labelKey: 'overviewDashboard.series.grammar', color: 'var(--chart-3)' },
  { key: 'vocabularyReviewCount', labelKey: 'overviewDashboard.series.vocab', color: 'var(--chart-2)' },
  { key: 'toeicQuestionCount', labelKey: 'overviewDashboard.series.toeic', color: 'var(--chart-4)' },
] as const;

type SeriesKey = (typeof PRACTICE_SERIES)[number]['key'];

type CustomTooltipPayloadItem = {
  dataKey?: string;
  name?: string;
  value?: number | string;
  color?: string;
};

type CustomTooltipProps = {
  active?: boolean;
  payload?: CustomTooltipPayloadItem[];
  label?: string;
  labelFormatter?: (raw: string) => string;
  nameMap?: Record<string, string>;
};

function DashboardChartTooltip({
  active,
  payload,
  label,
  labelFormatter,
  nameMap,
}: CustomTooltipProps) {
  if (!active || !payload?.length || !label) {
    return null;
  }
  return (
    <div className="min-w-44 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-sm">
      <p className="mb-1.5 font-medium text-muted-foreground tabular-nums">
        {labelFormatter ? labelFormatter(label) : label}
      </p>
      <div className="space-y-1">
        {payload.map((entry) => {
          const dataKey = String(entry.dataKey ?? '');
          const seriesName = nameMap?.[dataKey] ?? String(entry.name ?? dataKey);
          const color = entry.color ?? 'var(--chart-1)';
          return (
            <div key={dataKey} className="flex items-center justify-between gap-4">
              <span className="inline-flex items-center gap-2 text-muted-foreground">
                <span
                  className="inline-block h-0.5 w-3 shrink-0 rounded-full"
                  style={{ backgroundColor: color }}
                  aria-hidden="true"
                />
                {seriesName}
              </span>
              <span className="font-semibold text-foreground tabular-nums">
                {typeof entry.value === 'number'
                  ? entry.value.toLocaleString()
                  : String(entry.value ?? 0)}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function formatShortDate(isoDate: string, locale: string) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  return new Intl.DateTimeFormat(locale, {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(date);
}

function hasAnyActivity(series: DashboardDailyPoint[]) {
  return series.some(
    (d) =>
      d.activeLearners > 0 ||
      d.lessonCount > 0 ||
      d.vocabularyReviewCount > 0 ||
      d.grammarAnswerCount > 0 ||
      d.toeicQuestionCount > 0,
  );
}

export function OverviewActivityChart({ data }: { data: AdminDashboardSummary }) {
  const { t, i18n } = useTranslation('admin');
  const [chartTab, setChartTab] = useState<'learners' | 'practice'>('practice');
  const [viewMode, setViewMode] = useState<'chart' | 'table'>('chart');
  const [hiddenSeries, setHiddenSeries] = useState<Set<SeriesKey>>(new Set());

  const visibleSeries = useMemo(
    () => PRACTICE_SERIES.filter((s) => !hiddenSeries.has(s.key)),
    [hiddenSeries],
  );
  const topVisibleKey = visibleSeries[visibleSeries.length - 1]?.key;

  const seriesNameMap = useMemo(() => {
    const map: Record<string, string> = {
      activeLearners: t('overviewDashboard.series.activeLearners'),
    };
    for (const s of PRACTICE_SERIES) {
      map[s.key] = t(s.labelKey);
    }
    return map;
  }, [t]);

  const toggleSeries = (key: SeriesKey) => {
    setHiddenSeries((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else if (next.size < PRACTICE_SERIES.length - 1) {
        // Keep at least one series active so the chart remains legible.
        next.add(key);
      }
      return next;
    });
  };

  const formatDate = (iso: string) => formatShortDate(iso, i18n.language);
  const emptyActivity = !hasAnyActivity(data.dailySeries);

  return (
    <Card className="lg:col-span-8">
      <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <CardTitle>{t('overviewDashboard.dailyActivityTitle')}</CardTitle>
          <CardDescription>{t('overviewDashboard.dailyActivityHelp')}</CardDescription>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Tabs
            value={chartTab}
            onValueChange={(value) => setChartTab(value as 'learners' | 'practice')}
          >
            <TabsList>
              <TabsTrigger value="practice">
                <RiBarChart2Line className="size-3.5" aria-hidden="true" />
                {t('overviewDashboard.chartTabs.practice')}
              </TabsTrigger>
              <TabsTrigger value="learners">
                <RiLineChartLine className="size-3.5" aria-hidden="true" />
                {t('overviewDashboard.chartTabs.learners')}
              </TabsTrigger>
            </TabsList>
          </Tabs>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setViewMode((m) => (m === 'chart' ? 'table' : 'chart'))}
            aria-pressed={viewMode === 'table'}
          >
            <RiTableLine className="size-3.5" data-icon="inline-start" aria-hidden="true" />
            {viewMode === 'chart'
              ? t('overviewDashboard.viewMode.table')
              : t('overviewDashboard.viewMode.chart')}
          </Button>
        </div>
      </CardHeader>

      <CardContent className="space-y-3">
        {chartTab === 'practice' && viewMode === 'chart' && (
          <div className="flex flex-wrap items-center gap-2">
            {PRACTICE_SERIES.map((series) => {
              const active = !hiddenSeries.has(series.key);
              return (
                <button
                  key={series.key}
                  type="button"
                  onClick={() => toggleSeries(series.key)}
                  aria-pressed={active}
                  className={`inline-flex items-center gap-2 rounded-md border px-2.5 py-1 text-xs font-medium transition-opacity focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring ${
                    active ? 'bg-background text-foreground' : 'bg-muted/40 text-muted-foreground opacity-55'
                  }`}
                >
                  <span
                    className="size-2.5 shrink-0 rounded-[2px]"
                    style={{ backgroundColor: series.color }}
                    aria-hidden="true"
                  />
                  {t(series.labelKey)}
                </button>
              );
            })}
          </div>
        )}

        {emptyActivity && viewMode === 'chart' ? (
          <div className="flex h-64 flex-col items-center justify-center rounded-lg border border-dashed p-6 text-center">
            <p className="text-sm font-medium text-foreground">
              {t('overviewDashboard.noDailyTitle')}
            </p>
            <p className="mt-1 max-w-sm text-xs text-muted-foreground">
              {t('overviewDashboard.noDailyHelp')}
            </p>
          </div>
        ) : viewMode === 'table' ? (
          <div className="max-h-72 overflow-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('overviewDashboard.columns.date')}</TableHead>
                  <TableHead className="text-right">{t('overviewDashboard.columns.learners')}</TableHead>
                  <TableHead className="text-right">{t('overviewDashboard.columns.lessons')}</TableHead>
                  <TableHead className="text-right">{t('overviewDashboard.columns.grammar')}</TableHead>
                  <TableHead className="text-right">{t('overviewDashboard.columns.vocab')}</TableHead>
                  <TableHead className="text-right">{t('overviewDashboard.columns.toeic')}</TableHead>
                  <TableHead className="text-right">{t('overviewDashboard.columns.duration')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.dailySeries.map((day) => (
                  <TableRow key={day.activityDate}>
                    <TableCell className="font-medium tabular-nums">
                      {formatDate(day.activityDate)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{day.activeLearners}</TableCell>
                    <TableCell className="text-right tabular-nums">{day.lessonCount}</TableCell>
                    <TableCell className="text-right tabular-nums">{day.grammarAnswerCount}</TableCell>
                    <TableCell className="text-right tabular-nums">{day.vocabularyReviewCount}</TableCell>
                    <TableCell className="text-right tabular-nums">{day.toeicQuestionCount}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatStudyDuration(day.studySeconds, t)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ) : chartTab === 'learners' ? (
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={240}>
              <AreaChart
                data={data.dailySeries}
                margin={{ top: 8, right: 8, left: 0, bottom: 4 }}
              >
                <CartesianGrid vertical={false} stroke="var(--border)" strokeWidth={1} />
                <XAxis
                  dataKey="activityDate"
                  tickFormatter={formatDate}
                  tickLine={false}
                  axisLine={{ stroke: 'var(--border)', strokeWidth: 1 }}
                  tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                  minTickGap={18}
                />
                <YAxis
                  allowDecimals={false}
                  width={36}
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                />
                <Tooltip
                  cursor={{ stroke: 'var(--border)', strokeWidth: 1 }}
                  content={
                    <DashboardChartTooltip
                      labelFormatter={formatDate}
                      nameMap={seriesNameMap}
                    />
                  }
                />
                <Area
                  type="monotone"
                  dataKey="activeLearners"
                  stroke="var(--chart-1)"
                  strokeWidth={2}
                  fill="var(--chart-1)"
                  fillOpacity={0.1}
                  activeDot={{
                    r: 4,
                    fill: 'var(--chart-1)',
                    stroke: 'var(--card)',
                    strokeWidth: 2,
                  }}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="h-64 w-full">
            <ResponsiveContainer width="100%" height="100%" minWidth={0} minHeight={240}>
              <BarChart
                data={data.dailySeries}
                margin={{ top: 8, right: 8, left: 0, bottom: 4 }}
              >
                <CartesianGrid vertical={false} stroke="var(--border)" strokeWidth={1} />
                <XAxis
                  dataKey="activityDate"
                  tickFormatter={formatDate}
                  tickLine={false}
                  axisLine={{ stroke: 'var(--border)', strokeWidth: 1 }}
                  tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                  minTickGap={18}
                />
                <YAxis
                  allowDecimals={false}
                  width={36}
                  tickLine={false}
                  axisLine={false}
                  tick={{ fontSize: 11, fill: 'var(--muted-foreground)' }}
                />
                <Tooltip
                  cursor={{ fill: 'var(--muted)', fillOpacity: 0.45 }}
                  content={
                    <DashboardChartTooltip
                      labelFormatter={formatDate}
                      nameMap={seriesNameMap}
                    />
                  }
                />
                {visibleSeries.map((series) => (
                  <Bar
                    key={series.key}
                    dataKey={series.key}
                    stackId="practice"
                    fill={series.color}
                    stroke="var(--card)"
                    strokeWidth={2}
                    maxBarSize={24}
                    radius={series.key === topVisibleKey ? [4, 4, 0, 0] : [0, 0, 0, 0]}
                  />
                ))}
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
